package com.codemind.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageCitationRepository extends JpaRepository<MessageCitationEntity, String> {
    List<MessageCitationEntity> findByMessageIdOrderByCitationIndexAsc(String messageId);
}
