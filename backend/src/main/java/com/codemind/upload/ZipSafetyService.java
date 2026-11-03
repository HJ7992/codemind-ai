package com.codemind.upload;

import com.codemind.common.ApiException;
import com.codemind.config.UploadProperties;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ZipSafetyService {
    private static final int BUFFER_SIZE = 8192;
    private static final Pattern DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----");
    private static final Pattern HIGH_CONFIDENCE_TOKEN = Pattern.compile(
            "(?:AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,}|sk-[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})");

    private final UploadProperties properties;

    public ZipSafetyService(UploadProperties properties) {
        this.properties = properties;
    }

    public ExtractionResult extract(MultipartFile file, Path workDirectory) {
        Path archive = workDirectory.resolve("upload.zip");
        Path extracted = workDirectory.resolve("extracted");
        try {
            Files.createDirectories(extracted);
            String sha256 = copyArchive(file, archive);
            long compressedBytes = Files.size(archive);
            ExtractionAccumulator accumulator = readArchive(archive, extracted, compressedBytes);
            return new ExtractionResult(
                    extracted, accumulator.sourceFileCount, accumulator.sourceBytes, sha256);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_ZIP_ARCHIVE",
                    "ZIP 文件损坏或包含不支持的安全特性");
        }
    }

    private String copyArchive(MultipartFile file, Path archive) throws IOException {
        MessageDigest digest = sha256Digest();
        long total = 0;
        try (InputStream input = file.getInputStream();
                OutputStream output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > properties.maxZipBytes()) {
                    throw limitExceeded();
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_UPLOAD", "上传文件不能为空");
        }
        return hex(digest.digest());
    }

    private ExtractionAccumulator readArchive(Path archive, Path extracted, long compressedBytes)
            throws IOException {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        Set<String> normalizedPaths = new HashSet<>();
        try (InputStream fileInput = Files.newInputStream(archive);
                ZipArchiveInputStream zip = new ZipArchiveInputStream(fileInput)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                accumulator.entries++;
                if (accumulator.entries > properties.maxEntries()) {
                    throw limitExceeded();
                }
                validateEntrySecurity(entry);
                String normalizedPath = normalizePath(entry.getName());
                if (!normalizedPaths.add(normalizedPath.toLowerCase(Locale.ROOT))) {
                    throw new ApiException(
                            HttpStatus.CONFLICT, "DUPLICATE_ARCHIVE_PATH", "ZIP 中存在重复规范化路径");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                boolean source = isSourceFile(normalizedPath);
                byte[] content = readEntry(zip, source, accumulator);
                if (!source) {
                    continue;
                }
                validateUtf8AndSecrets(content);
                writeSource(extracted, normalizedPath, content);
                accumulator.sourceFileCount++;
                accumulator.sourceBytes += content.length;
            }
        }
        if (compressedBytes == 0
                || accumulator.totalUncompressedBytes / (double) compressedBytes
                        > properties.maxCompressionRatio()) {
            throw limitExceeded();
        }
        if (accumulator.sourceFileCount == 0) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "NO_SOURCE_FILES",
                    "ZIP 中没有可处理的 Java 或 Python 源码");
        }
        return accumulator;
    }

    private void validateEntrySecurity(ZipArchiveEntry entry) {
        if (entry.getGeneralPurposeBit().usesEncryption()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "ENCRYPTED_ZIP_REJECTED", "不支持加密 ZIP");
        }
        if (entry.isUnixSymlink()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "SYMLINK_REJECTED", "ZIP 中不允许包含符号链接");
        }
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.indexOf('\0') >= 0) {
            throw unsafePath();
        }
        String unified = rawPath.replace('\\', '/');
        if (unified.startsWith("/") || unified.startsWith("//") || DRIVE_PATH.matcher(unified).matches()) {
            throw unsafePath();
        }
        String nfc = Normalizer.normalize(unified, Normalizer.Form.NFC);
        Path path;
        try {
            path = Paths.get(nfc);
        } catch (java.nio.file.InvalidPathException exception) {
            throw unsafePath();
        }
        String normalized = path.normalize().toString().replace('\\', '/');
        if (path.isAbsolute()
                || normalized.startsWith("../")
                || normalized.equals("..")
                || normalized.isBlank()
                || normalized.equals(".")) {
            throw unsafePath();
        }
        long depth = java.util.Arrays.stream(normalized.split("/"))
                .filter(segment -> !segment.isBlank())
                .count();
        if (depth > properties.maxPathDepth()) {
            throw limitExceeded();
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..") || segment.isBlank()) {
                throw unsafePath();
            }
        }
        return normalized;
    }

    private byte[] readEntry(
            InputStream zip, boolean source, ExtractionAccumulator accumulator) throws IOException {
        ByteArrayOutputStream content = source ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            accumulator.totalUncompressedBytes += read;
            if (accumulator.totalUncompressedBytes > properties.maxUncompressedBytes()) {
                throw limitExceeded();
            }
            if (source) {
                if (content.size() + read > properties.maxSourceFileBytes()) {
                    throw limitExceeded();
                }
                content.write(buffer, 0, read);
            }
        }
        return source ? content.toByteArray() : new byte[0];
    }

    private void validateUtf8AndSecrets(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UNSUPPORTED_ENCODING",
                    "源码必须使用 UTF-8 编码");
        }
        if (PRIVATE_KEY.matcher(text).find() || HIGH_CONFIDENCE_TOKEN.matcher(text).find()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SENSITIVE_CONTENT_DETECTED",
                    "检测到高风险敏感信息，上传已拒绝并清理");
        }
    }

    private void writeSource(Path extracted, String normalizedPath, byte[] content) throws IOException {
        Path target = extracted.resolve(normalizedPath).normalize();
        if (!target.startsWith(extracted)) {
            throw unsafePath();
        }
        Files.createDirectories(target.getParent());
        int offset = content.length >= 3
                        && content[0] == (byte) 0xEF
                        && content[1] == (byte) 0xBB
                        && content[2] == (byte) 0xBF
                ? 3
                : 0;
        Files.write(target, java.util.Arrays.copyOfRange(content, offset, content.length),
                StandardOpenOption.CREATE_NEW);
    }

    private boolean isSourceFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".py");
    }

    private ApiException unsafePath() {
        return new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_ARCHIVE_PATH", "ZIP 中存在不安全路径");
    }

    private ApiException limitExceeded() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_LIMIT_EXCEEDED", "上传内容超过安全限制");
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }

    private String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static class ExtractionAccumulator {
        private int entries;
        private int sourceFileCount;
        private long sourceBytes;
        private long totalUncompressedBytes;
    }
}
