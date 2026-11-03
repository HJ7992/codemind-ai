package com.codemind.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SourceVersionRepository extends JpaRepository<SourceVersionEntity, String> {
    Optional<SourceVersionEntity> findFirstByProjectIdOrderByCreatedAtDesc(String projectId);
}
