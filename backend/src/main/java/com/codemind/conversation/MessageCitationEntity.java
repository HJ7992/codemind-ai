package com.codemind.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "message_citation")
public class MessageCitationEntity {
    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "message_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String messageId;

    @Column(name = "source_version_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String sourceVersionId;

    @Column(name = "source_file_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String sourceFileId;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "citation_index", nullable = false)
    private int citationIndex;

    @Column(name = "start_line", nullable = false)
    private int startLine;

    @Column(name = "end_line", nullable = false)
    private int endLine;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String snippet;

    @Column(name = "retrieval_score")
    private Double retrievalScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageCitationEntity() {}

    public MessageCitationEntity(
            String id,
            String messageId,
            String sourceVersionId,
            String sourceFileId,
            String filePath,
            int citationIndex,
            int startLine,
            int endLine,
            String snippet,
            Double retrievalScore) {
        this.id = id;
        this.messageId = messageId;
        this.sourceVersionId = sourceVersionId;
        this.sourceFileId = sourceFileId;
        this.filePath = filePath;
        this.citationIndex = citationIndex;
        this.startLine = startLine;
        this.endLine = endLine;
        this.snippet = snippet;
        this.retrievalScore = retrievalScore;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSourceVersionId() {
        return sourceVersionId;
    }

    public String getSourceFileId() {
        return sourceFileId;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getCitationIndex() {
        return citationIndex;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getSnippet() {
        return snippet;
    }

    public Double getRetrievalScore() {
        return retrievalScore;
    }
}
