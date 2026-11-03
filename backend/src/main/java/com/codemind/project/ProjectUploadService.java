package com.codemind.project;

import com.codemind.common.ApiException;
import com.codemind.config.PrivacyProperties;
import com.codemind.config.SourceProperties;
import com.codemind.indexing.IndexJobEntity;
import com.codemind.indexing.IndexJobRepository;
import com.codemind.indexing.ProjectUploadedEvent;
import com.codemind.source.SourceVersionEntity;
import com.codemind.source.SourceVersionRepository;
import com.codemind.upload.ExtractionResult;
import com.codemind.upload.ZipSafetyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class ProjectUploadService {
    private final PrivacyProperties privacyProperties;
    private final SourceProperties sourceProperties;
    private final ZipSafetyService zipSafetyService;
    private final ProjectRepository projectRepository;
    private final SourceVersionRepository sourceVersionRepository;
    private final IndexJobRepository indexJobRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxAttempts;

    public ProjectUploadService(
            PrivacyProperties privacyProperties,
            SourceProperties sourceProperties,
            ZipSafetyService zipSafetyService,
            ProjectRepository projectRepository,
            SourceVersionRepository sourceVersionRepository,
            IndexJobRepository indexJobRepository,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher,
            @Value("${CODEMIND_INDEX_MAX_ATTEMPTS:2}") int maxAttempts) {
        this.privacyProperties = privacyProperties;
        this.sourceProperties = sourceProperties;
        this.zipSafetyService = zipSafetyService;
        this.projectRepository = projectRepository;
        this.sourceVersionRepository = sourceVersionRepository;
        this.indexJobRepository = indexJobRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.maxAttempts = maxAttempts;
    }

    public UploadAcceptedResponse upload(
            MultipartFile file, String noticeVersion, boolean externalTransferAccepted) {
        validatePrivacyConfirmation(noticeVersion, externalTransferAccepted);
        String projectId = UUID.randomUUID().toString();
        String sourceVersionId = UUID.randomUUID().toString();
        String indexJobId = UUID.randomUUID().toString();
        String storageKey = "projects/" + projectId + "/versions/" + sourceVersionId;
        Path sourceRoot = Path.of(sourceProperties.sourceRoot()).toAbsolutePath().normalize();
        Path workDirectory = sourceRoot.resolve(".tmp").resolve(UUID.randomUUID().toString());
        Path finalDirectory = sourceRoot.resolve(storageKey).normalize();
        if (!finalDirectory.startsWith(sourceRoot)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_STORAGE_PATH", "源码存储路径无效");
        }

        try {
            Files.createDirectories(workDirectory);
            ExtractionResult result = zipSafetyService.extract(file, workDirectory);
            Files.createDirectories(finalDirectory.getParent());
            Files.move(result.extractedDirectory(), finalDirectory);
            String originalFilename = safeOriginalFilename(file.getOriginalFilename());
            String projectName = projectName(originalFilename);
            String archiveSha256 = result.archiveSha256();
            int sourceFileCount = result.sourceFileCount();
            long sourceBytes = result.totalSourceBytes();
            UploadAcceptedResponse response = transactionTemplate.execute(status -> {
                projectRepository.save(new ProjectEntity(projectId, projectName, ProjectStatus.INDEXING));
                sourceVersionRepository.save(new SourceVersionEntity(
                        sourceVersionId,
                        projectId,
                        originalFilename,
                        storageKey,
                        archiveSha256,
                        sourceFileCount,
                        sourceBytes));
                indexJobRepository.save(new IndexJobEntity(indexJobId, sourceVersionId, maxAttempts));
                return new UploadAcceptedResponse(
                        projectId, sourceVersionId, indexJobId, ProjectStatus.INDEXING);
            });
            eventPublisher.publishEvent(
                    new ProjectUploadedEvent(projectId, sourceVersionId, indexJobId));
            return response;
        } catch (IOException | RuntimeException exception) {
            FileSystemUtils.deleteRecursively(workDirectory.toFile());
            FileSystemUtils.deleteRecursively(finalDirectory.toFile());
            if (exception instanceof ApiException apiException) {
                throw apiException;
            }
            if (exception instanceof IOException) {
                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "SOURCE_STORAGE_ERROR", "源码存储处理失败");
            }
            throw (RuntimeException) exception;
        }
    }

    private void validatePrivacyConfirmation(String noticeVersion, boolean accepted) {
        if (!accepted) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PRIVACY_NOTICE_REQUIRED",
                    "上传前必须确认源码外发告知");
        }
        if (noticeVersion == null || !noticeVersion.equals(privacyProperties.noticeVersion())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PRIVACY_NOTICE_VERSION_MISMATCH",
                    "隐私告知已更新，请重新阅读并确认");
        }
    }

    private String safeOriginalFilename(String original) {
        String value = original == null ? "project.zip" : original.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (value.isEmpty()) {
            return "project.zip";
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private String projectName(String filename) {
        String name = filename.replaceFirst("(?i)\\.zip$", "").strip();
        return name.isEmpty() ? "project" : name;
    }
}
