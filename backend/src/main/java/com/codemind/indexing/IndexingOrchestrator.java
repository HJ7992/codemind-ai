package com.codemind.indexing;

import com.codemind.ai.AiServiceClient;
import com.codemind.common.ApiException;
import com.codemind.project.ProjectEntity;
import com.codemind.project.ProjectRepository;
import com.codemind.source.SourceFileEntity;
import com.codemind.source.SourceFileRepository;
import com.codemind.source.SourceVersionEntity;
import com.codemind.source.SourceVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Component
public class IndexingOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(IndexingOrchestrator.class);

    private final ProjectRepository projectRepository;
    private final SourceVersionRepository sourceVersionRepository;
    private final SourceFileRepository sourceFileRepository;
    private final IndexJobRepository indexJobRepository;
    private final AiServiceClient aiServiceClient;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor indexingExecutor;

    public IndexingOrchestrator(
            ProjectRepository projectRepository,
            SourceVersionRepository sourceVersionRepository,
            SourceFileRepository sourceFileRepository,
            IndexJobRepository indexJobRepository,
            AiServiceClient aiServiceClient,
            TransactionTemplate transactionTemplate,
            TaskExecutor indexingExecutor) {
        this.projectRepository = projectRepository;
        this.sourceVersionRepository = sourceVersionRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.indexJobRepository = indexJobRepository;
        this.aiServiceClient = aiServiceClient;
        this.transactionTemplate = transactionTemplate;
        this.indexingExecutor = indexingExecutor;
    }

    @Async("indexingExecutor")
    @EventListener
    public void onProjectUploaded(ProjectUploadedEvent event) {
        runIndexing(event.projectId(), event.sourceVersionId(), event.jobId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        indexJobRepository.findByStatusIn(List.of(IndexJobStatus.PENDING, IndexJobStatus.RUNNING))
                .forEach(job -> indexingExecutor.execute(() -> {
                    sourceVersionRepository.findById(job.getSourceVersionId())
                            .ifPresent(version -> runIndexing(
                                    version.getProjectId(), version.getId(), job.getId()));
                }));
    }

    public void runIndexing(String projectId, String versionId, String jobId) {
        IndexJobEntity job = indexJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() == IndexJobStatus.SUCCEEDED
                || job.getStatus() == IndexJobStatus.FAILED) {
            return;
        }
        if (job.getAttemptCount() >= job.getMaxAttempts()) {
            markFailed(projectId, jobId, "INDEX_MAX_ATTEMPTS_EXCEEDED", "索引尝试次数已用尽");
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            IndexJobEntity current = indexJobRepository.findById(jobId).orElseThrow();
            current.markRunning();
        });

        SourceVersionEntity version = sourceVersionRepository.findById(versionId).orElse(null);
        if (version == null) {
            markFailed(projectId, jobId, "SOURCE_VERSION_NOT_FOUND", "源码版本不存在");
            return;
        }
        try {
            AiServiceClient.IndexResult result = aiServiceClient.index(
                    jobId, projectId, versionId, version.getStorageKey());
            if (result == null || result.sourceFiles() == null) {
                throw new IllegalStateException("AI 索引响应不完整");
            }
            transactionTemplate.executeWithoutResult(status -> {
                IndexJobEntity completed = indexJobRepository.findById(jobId).orElseThrow();
                ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
                sourceFileRepository.deleteBySourceVersionId(versionId);
                result.sourceFiles().forEach(file -> sourceFileRepository.save(new SourceFileEntity(
                        UUID.randomUUID().toString(),
                        versionId,
                        file.relativePath(),
                        file.language(),
                        file.sizeBytes(),
                        file.lineCount(),
                        file.sha256(),
                        file.parseStatus())));
                completed.markSucceeded(result.sourceFileCount(), result.chunkCount());
                project.markReady(versionId);
            });
        } catch (RuntimeException exception) {
            String code = exception instanceof ApiException apiException
                    ? apiException.getErrorCode()
                    : "INDEXING_FAILED";
            String message = exception instanceof ApiException apiException
                    ? apiException.getMessage()
                    : "索引处理失败";
            log.warn("索引任务失败 jobId={} errorCode={}", jobId, code);
            markFailed(projectId, jobId, code, message);
        }
    }

    private void markFailed(String projectId, String jobId, String code, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            indexJobRepository.findById(jobId).ifPresent(job -> job.markFailed(code, message));
            projectRepository.findById(projectId)
                    .ifPresent(project -> project.markIndexFailed(code, message));
        });
    }
}
