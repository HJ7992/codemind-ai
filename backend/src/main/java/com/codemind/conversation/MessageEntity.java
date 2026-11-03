package com.codemind.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "message")
public class MessageEntity {
    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MessageStatus status;

    @Column(name = "source_version_id", length = 36, columnDefinition = "CHAR(36)")
    private String sourceVersionId;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageEntity() {}

    public MessageEntity(
            String id,
            String conversationId,
            MessageRole role,
            String content,
            MessageStatus status,
            String sourceVersionId,
            String modelName) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.status = status;
        this.sourceVersionId = sourceVersionId;
        this.modelName = modelName;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public String getSourceVersionId() {
        return sourceVersionId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
