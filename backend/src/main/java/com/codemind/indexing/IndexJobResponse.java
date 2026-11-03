package com.codemind.indexing;

import java.time.Instant;

public record IndexJobResponse(
        String jobId,
        String projectId,
        String sourceVersionId,
        IndexJobStatus status,
        int processedFiles,
        int sourceFileCount,
        int chunkCount,
        int attemptCount,
        int maxAttempts,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {}
