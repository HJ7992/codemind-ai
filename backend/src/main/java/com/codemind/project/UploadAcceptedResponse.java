package com.codemind.project;

public record UploadAcceptedResponse(
        String projectId, String sourceVersionId, String indexJobId, ProjectStatus status) {}
