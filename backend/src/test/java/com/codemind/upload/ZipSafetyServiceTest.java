package com.codemind.upload;

import com.codemind.common.ApiException;
import com.codemind.config.UploadProperties;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipSafetyServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void 合法源码被解包且其他类型不落盘() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/src/Main.java", "class Main {}\n".getBytes(StandardCharsets.UTF_8));
        entries.put("demo/tool.py", "def run():\n    return 1\n".getBytes(StandardCharsets.UTF_8));
        entries.put("demo/README.md", "ignored".getBytes(StandardCharsets.UTF_8));

        ExtractionResult result = service().extract(zip(entries), tempDirectory.resolve("work"));

        assertThat(result.sourceFileCount()).isEqualTo(2);
        assertThat(result.archiveSha256()).hasSize(64);
        assertThat(Files.readString(result.extractedDirectory().resolve("demo/src/Main.java")))
                .isEqualTo("class Main {}\n");
        assertThat(result.extractedDirectory().resolve("demo/README.md")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.java", "/absolute.java", "C:/evil.java", "//server/evil.java", "..\\evil.java"})
    void 拒绝不安全路径(String path) {
        assertThatThrownBy(() -> service().extract(zip(Map.of(path, "class Evil {}".getBytes())), tempDirectory.resolve("work")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("UNSAFE_ARCHIVE_PATH");
    }

    @Test
    void 拒绝重复规范化路径() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            put(zip, "A/Test.java", "class A {}".getBytes());
            put(zip, "a/test.JAVA", "class B {}".getBytes());
        }

        assertThatThrownBy(() -> service().extract(multipart(bytes), tempDirectory.resolve("work")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("DUPLICATE_ARCHIVE_PATH");
    }

    @Test
    void 拒绝高置信度秘密且不写出源码() {
        Map<String, byte[]> entries = Map.of(
                "Secret.java", "-----BEGIN PRIVATE KEY-----\nabc".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service().extract(zip(entries), tempDirectory.resolve("work")))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("abc");
        assertThat(tempDirectory.resolve("work/extracted/Secret.java")).doesNotExist();
    }

    @Test
    void 拒绝非UTF8源码() {
        assertThatThrownBy(() -> service().extract(
                        zip(Map.of("Bad.java", new byte[] {(byte) 0xC3, 0x28})), tempDirectory.resolve("work")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("UNSUPPORTED_ENCODING");
    }

    @Test
    void 拒绝超过条目限制() {
        UploadProperties limits = new UploadProperties(10000, 10000, 1, 1000, 20, 1000);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("A.java", "class A {}".getBytes());
        entries.put("B.java", "class B {}".getBytes());

        assertThatThrownBy(() -> new ZipSafetyService(limits).extract(zip(entries), tempDirectory.resolve("work")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("UPLOAD_LIMIT_EXCEEDED");
    }

    @Test
    void 拒绝高压缩比内容() {
        UploadProperties limits = new UploadProperties(10000, 100000, 10, 10000, 20, 1);
        byte[] repetitive = "a".repeat(10000).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ZipSafetyService(limits).extract(
                        zip(Map.of("A.java", repetitive)), tempDirectory.resolve("work")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("UPLOAD_LIMIT_EXCEEDED");
    }

    @Test
    void 没有源码时明确失败() {
        assertThatThrownBy(() -> service().extract(
                        zip(Map.of("README.md", "text".getBytes())), tempDirectory.resolve("work")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("NO_SOURCE_FILES");
    }

    private ZipSafetyService service() {
        return new ZipSafetyService(new UploadProperties(100000, 1000000, 100, 100000, 20, 1000));
    }

    private MockMultipartFile zip(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    put(zip, entry.getKey(), entry.getValue());
                }
            }
            return multipart(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockMultipartFile multipart(ByteArrayOutputStream bytes) {
        return new MockMultipartFile("file", "demo.zip", "application/zip", bytes.toByteArray());
    }

    private void put(ZipArchiveOutputStream zip, String name, byte[] content) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        zip.putArchiveEntry(entry);
        zip.write(content);
        zip.closeArchiveEntry();
    }
}
