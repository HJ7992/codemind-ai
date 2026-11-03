CREATE TABLE project (
    id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_source_version_id CHAR(36) NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_project_status_created_at (status, created_at),
    CONSTRAINT chk_project_status CHECK (status IN ('INDEXING', 'READY', 'INDEX_FAILED', 'DELETING'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE source_version (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    archive_sha256 CHAR(64) NOT NULL,
    source_file_count INT NOT NULL DEFAULT 0,
    total_source_bytes BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_version_storage_key (storage_key),
    UNIQUE KEY uk_source_version_id_project (id, project_id),
    KEY idx_source_version_project_created_at (project_id, created_at),
    CONSTRAINT fk_source_version_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT chk_source_version_counts CHECK (source_file_count >= 0 AND total_source_bytes >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE project
    ADD CONSTRAINT fk_project_active_source_version FOREIGN KEY (active_source_version_id)
        REFERENCES source_version (id) ON DELETE SET NULL ON UPDATE RESTRICT;

CREATE TABLE index_job (
    id CHAR(36) NOT NULL,
    source_version_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 2,
    processed_files INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    last_heartbeat_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_index_job_source_version (source_version_id),
    KEY idx_index_job_status_updated_at (status, updated_at),
    CONSTRAINT fk_index_job_source_version FOREIGN KEY (source_version_id) REFERENCES source_version (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT chk_index_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_index_job_attempts CHECK (max_attempts > 0 AND attempt_count >= 0 AND attempt_count <= max_attempts),
    CONSTRAINT chk_index_job_progress CHECK (processed_files >= 0 AND chunk_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE source_file (
    id CHAR(36) NOT NULL,
    source_version_id CHAR(36) NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    relative_path_sha256 CHAR(64) GENERATED ALWAYS AS (SHA2(relative_path, 256)) STORED,
    language VARCHAR(16) NOT NULL,
    size_bytes BIGINT NOT NULL,
    line_count INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    parse_status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_file_id_version (id, source_version_id),
    UNIQUE KEY uk_source_file_version_path (source_version_id, relative_path_sha256),
    KEY idx_source_file_version_language (source_version_id, language),
    CONSTRAINT fk_source_file_source_version FOREIGN KEY (source_version_id) REFERENCES source_version (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT chk_source_file_language CHECK (language IN ('JAVA', 'PYTHON')),
    CONSTRAINT chk_source_file_parse_status CHECK (parse_status IN ('PARSED', 'FALLBACK', 'FAILED')),
    CONSTRAINT chk_source_file_size_lines CHECK (size_bytes >= 0 AND line_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE conversation (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    title VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_conversation_project_updated_at (project_id, updated_at),
    CONSTRAINT fk_conversation_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE message (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_version_id CHAR(36) NULL,
    model_name VARCHAR(128) NULL,
    retrieval_metadata JSON NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_message_conversation_created_at (conversation_id, created_at),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_message_source_version FOREIGN KEY (source_version_id) REFERENCES source_version (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_message_status CHECK (status IN ('COMPLETED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE message_citation (
    id CHAR(36) NOT NULL,
    message_id CHAR(36) NOT NULL,
    source_version_id CHAR(36) NOT NULL,
    source_file_id CHAR(36) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    citation_index INT NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    snippet MEDIUMTEXT NOT NULL,
    retrieval_score DOUBLE NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_citation_index (message_id, citation_index),
    KEY idx_message_citation_file (source_file_id, source_version_id),
    CONSTRAINT fk_message_citation_message FOREIGN KEY (message_id) REFERENCES message (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_message_citation_source_file FOREIGN KEY (source_file_id, source_version_id)
        REFERENCES source_file (id, source_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_message_citation_index CHECK (citation_index >= 1),
    CONSTRAINT chk_message_citation_lines CHECK (start_line >= 1 AND end_line >= start_line)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
