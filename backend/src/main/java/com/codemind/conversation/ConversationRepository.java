package com.codemind.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
    Optional<ConversationEntity> findByIdAndProjectId(String id, String projectId);

    List<ConversationEntity> findTop20ByProjectIdOrderByUpdatedAtDesc(String projectId);
}
