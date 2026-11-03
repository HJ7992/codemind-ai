package com.codemind.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "index_job")
public class IndexJobEntity {
    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "source_version_id", nullable = false, unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String sourceVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IndexJobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "processed_files", nullable = false)
    private int processedFiles;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IndexJobEntity() {}

    public IndexJobEntity(String id, String sourceVersionId, int maxAttempts) {
        this.id = id;
        this.sourceVersionId = sourceVersionId;
        this.maxAttempts = maxAttempts;
        this.status = IndexJobStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getSourceVersionId() {
        return sourceVersionId;
    }

    public IndexJobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getProcessedFiles() {
        return processedFiles;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void markRunning() {
        this.status = IndexJobStatus.RUNNING;
        this.attemptCount++;
        this.startedAt = Instant.now();
        this.lastHeartbeatAt = this.startedAt;
        this.finishedAt = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void markSucceeded(int files, int chunks) {
        this.status = IndexJobStatus.SUCCEEDED;
        this.processedFiles = files;
        this.chunkCount = chunks;
        this.finishedAt = Instant.now();
        this.lastHeartbeatAt = this.finishedAt;
    }

    public void markFailed(String code, String message) {
        this.status = IndexJobStatus.FAILED;
        this.errorCode = code;
        this.errorMessage = message == null ? null : message.substring(0, Math.min(1000, message.length()));
        this.finishedAt = Instant.now();
        this.lastHeartbeatAt = this.finishedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
