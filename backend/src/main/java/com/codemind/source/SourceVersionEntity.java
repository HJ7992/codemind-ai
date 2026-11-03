package com.codemind.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "source_version")
public class SourceVersionEntity {
    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "project_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String projectId;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "archive_sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String archiveSha256;

    @Column(name = "source_file_count", nullable = false)
    private int sourceFileCount;

    @Column(name = "total_source_bytes", nullable = false)
    private long totalSourceBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SourceVersionEntity() {}

    public SourceVersionEntity(
            String id,
            String projectId,
            String originalFilename,
            String storageKey,
            String archiveSha256,
            int sourceFileCount,
            long totalSourceBytes) {
        this.id = id;
        this.projectId = projectId;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.archiveSha256 = archiveSha256;
        this.sourceFileCount = sourceFileCount;
        this.totalSourceBytes = totalSourceBytes;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getArchiveSha256() {
        return archiveSha256;
    }

    public int getSourceFileCount() {
        return sourceFileCount;
    }

    public long getTotalSourceBytes() {
        return totalSourceBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
