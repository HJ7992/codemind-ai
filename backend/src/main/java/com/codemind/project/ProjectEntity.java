package com.codemind.project;

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
@Table(name = "project")
public class ProjectEntity {
    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectStatus status;

    @Column(name = "active_source_version_id", length = 36, columnDefinition = "CHAR(36)")
    private String activeSourceVersionId;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectEntity() {}

    public ProjectEntity(String id, String name, ProjectStatus status) {
        this.id = id;
        this.name = name;
        this.status = status;
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

    public String getName() {
        return name;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public String getActiveSourceVersionId() {
        return activeSourceVersionId;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void markReady(String versionId) {
        this.status = ProjectStatus.READY;
        this.activeSourceVersionId = versionId;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    public void markIndexFailed(String code, String message) {
        this.status = ProjectStatus.INDEX_FAILED;
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
    }

    public void markDeleting() {
        this.status = ProjectStatus.DELETING;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
