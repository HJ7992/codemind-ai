package com.codemind.project;

import java.time.Instant;

public record ProjectResponse(
        String projectId,
        String name,
        ProjectStatus status,
        String activeSourceVersionId,
        String indexJobId,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {}
