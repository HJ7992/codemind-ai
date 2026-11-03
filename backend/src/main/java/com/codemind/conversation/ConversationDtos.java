package com.codemind.conversation;

import java.time.Instant;
import java.util.List;

public final class ConversationDtos {
    private ConversationDtos() {}

    public record CreateConversationRequest(String projectId, String title) {}

    public record Response(
            String conversationId, String projectId, String title, Instant createdAt) {}

    public record QuestionRequest(String question) {}

    public record CitationView(
            int citationIndex,
            String filePath,
            int startLine,
            int endLine,
            String snippet,
            Double score) {}

    public record MessageView(
            String messageId,
            MessageRole role,
            String content,
            MessageStatus status,
            String sourceVersionId,
            String errorCode,
            List<CitationView> citations,
            Instant createdAt) {}

    public record ConversationView(
            String conversationId,
            String projectId,
            String title,
            List<MessageView> messages,
            Instant createdAt,
            Instant updatedAt) {}

    public record RetrievalMetadataView(
            String strategy,
            int topK,
            String chatProvider,
            String chatModel,
            String embeddingModel,
            long retrievalDurationMs,
            long generationDurationMs) {}

    public record AskResponse(
            String messageId,
            String answer,
            MessageStatus status,
            String sourceVersionId,
            List<CitationView> citations,
            RetrievalMetadataView retrievalMetadata) {}
}
