package com.codemind.project;

import com.codemind.ai.AiServiceClient;
import com.codemind.common.ApiException;
import com.codemind.config.SourceProperties;
import com.codemind.source.SourceVersionEntity;
import com.codemind.source.SourceVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Path;

@Service
public class ProjectDeleteService {
    private final ProjectRepository projectRepository;
    private final SourceVersionRepository sourceVersionRepository;
    private final AiServiceClient aiServiceClient;
    private final TransactionTemplate transactionTemplate;
    private final SourceProperties sourceProperties;

    public ProjectDeleteService(
            ProjectRepository projectRepository,
            SourceVersionRepository sourceVersionRepository,
            AiServiceClient aiServiceClient,
            TransactionTemplate transactionTemplate,
            SourceProperties sourceProperties) {
        this.projectRepository = projectRepository;
        this.sourceVersionRepository = sourceVersionRepository;
        this.aiServiceClient = aiServiceClient;
        this.transactionTemplate = transactionTemplate;
        this.sourceProperties = sourceProperties;
    }

    public void delete(String projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或已删除"));
        transactionTemplate.executeWithoutResult(status -> projectRepository.findById(projectId)
                .orElseThrow()
                .markDeleting());
        SourceVersionEntity version = sourceVersionRepository
                .findFirstByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
        try {
            if (version != null) {
                aiServiceClient.deleteIndex(projectId, version.getId());
                Path root = Path.of(sourceProperties.sourceRoot()).toAbsolutePath().normalize();
                Path target = root.resolve(version.getStorageKey()).normalize();
                if (!target.startsWith(root)) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST, "UNSAFE_STORAGE_PATH", "源码存储路径无效");
                }
                try {
                    FileSystemUtils.deleteRecursively(target);
                    Path projectDirectory = root.resolve("projects").resolve(projectId).normalize();
                    if (projectDirectory.startsWith(root)) {
                        FileSystemUtils.deleteRecursively(projectDirectory);
                    }
                } catch (java.io.IOException exception) {
                    throw new ApiException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "SOURCE_STORAGE_ERROR",
                            "源码目录清理失败");
                }
            }
            transactionTemplate.executeWithoutResult(status -> projectRepository.deleteById(projectId));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> projectRepository.findById(projectId)
                    .ifPresent(entity -> entity.markIndexFailed(
                            "PROJECT_DELETION_FAILED", "项目删除失败，请稍后重试")));
            throw exception;
        }
    }
}
