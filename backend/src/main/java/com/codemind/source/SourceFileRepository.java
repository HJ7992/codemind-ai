package com.codemind.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SourceFileRepository extends JpaRepository<SourceFileEntity, String> {
    Optional<SourceFileEntity> findBySourceVersionIdAndRelativePath(
            String sourceVersionId, String relativePath);

    void deleteBySourceVersionId(String sourceVersionId);
}
