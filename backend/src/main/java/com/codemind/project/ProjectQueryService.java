package com.codemind.project;

import com.codemind.common.ApiException;
import com.codemind.indexing.IndexJobEntity;
import com.codemind.indexing.IndexJobRepository;
import com.codemind.indexing.IndexJobResponse;
import com.codemind.source.SourceVersionEntity;
import com.codemind.source.SourceVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectQueryService {
    private final ProjectRepository projectRepository;
    private final SourceVersionRepository sourceVersionRepository;
    private final IndexJobRepository indexJobRepository;

    public ProjectQueryService(
            ProjectRepository projectRepository,
            SourceVersionRepository sourceVersionRepository,
            IndexJobRepository indexJobRepository) {
        this.projectRepository = projectRepository;
        this.sourceVersionRepository = sourceVersionRepository;
        this.indexJobRepository = indexJobRepository;
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(String projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或已删除"));
        SourceVersionEntity version = sourceVersionRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SOURCE_VERSION_NOT_FOUND", "项目关联的源码版本不存在"));
        IndexJobEntity job = indexJobRepository.findBySourceVersionId(version.getId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "INDEX_JOB_NOT_FOUND", "项目关联的索引任务不存在"));
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getStatus(),
                project.getActiveSourceVersionId(),
                job.getId(),
                project.getLastErrorCode(),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public java.util.List<ProjectResponse> listProjects() {
        return projectRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(project -> {
                    var version = sourceVersionRepository
                            .findFirstByProjectIdOrderByCreatedAtDesc(project.getId())
                            .orElse(null);
                    var job = version == null ? null : indexJobRepository
                            .findBySourceVersionId(version.getId()).orElse(null);
                    return new ProjectResponse(
                            project.getId(),
                            project.getName(),
                            project.getStatus(),
                            project.getActiveSourceVersionId(),
                            job == null ? null : job.getId(),
                            project.getLastErrorCode(),
                            project.getCreatedAt(),
                            project.getUpdatedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public IndexJobResponse getIndexJob(String jobId) {
        IndexJobEntity job = indexJobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "INDEX_JOB_NOT_FOUND", "索引任务不存在"));
        SourceVersionEntity version = sourceVersionRepository.findById(job.getSourceVersionId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SOURCE_VERSION_NOT_FOUND", "任务关联的源码版本不存在"));
        return new IndexJobResponse(
                job.getId(),
                version.getProjectId(),
                version.getId(),
                job.getStatus(),
                job.getProcessedFiles(),
                version.getSourceFileCount(),
                job.getChunkCount(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
