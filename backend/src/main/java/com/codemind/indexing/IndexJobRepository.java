package com.codemind.indexing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IndexJobRepository extends JpaRepository<IndexJobEntity, String> {
    Optional<IndexJobEntity> findBySourceVersionId(String sourceVersionId);

    List<IndexJobEntity> findByStatusIn(List<IndexJobStatus> statuses);
}
