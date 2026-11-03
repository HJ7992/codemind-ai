package com.codemind.indexing;

public record ProjectUploadedEvent(String projectId, String sourceVersionId, String jobId) {}
