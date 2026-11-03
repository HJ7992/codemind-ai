package com.codemind.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "source_file")
public class SourceFileEntity {
    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "source_version_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String sourceVersionId;

    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String sha256;

    @Column(name = "parse_status", nullable = false, length = 32)
    private String parseStatus;

    protected SourceFileEntity() {}

    public SourceFileEntity(
            String id,
            String sourceVersionId,
            String relativePath,
            String language,
            long sizeBytes,
            int lineCount,
            String sha256,
            String parseStatus) {
        this.id = id;
        this.sourceVersionId = sourceVersionId;
        this.relativePath = relativePath;
        this.language = language;
        this.sizeBytes = sizeBytes;
        this.lineCount = lineCount;
        this.sha256 = sha256;
        this.parseStatus = parseStatus;
    }

    public String getId() {
        return id;
    }

    public String getSourceVersionId() {
        return sourceVersionId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getLanguage() {
        return language;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getLineCount() {
        return lineCount;
    }

    public String getParseStatus() {
        return parseStatus;
    }
}
