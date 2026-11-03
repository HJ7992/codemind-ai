package com.codemind.conversation;

import com.codemind.ai.AiServiceClient;
import com.codemind.common.ApiException;
import com.codemind.project.ProjectEntity;
import com.codemind.project.ProjectRepository;
import com.codemind.project.ProjectStatus;
import com.codemind.source.SourceFileEntity;
import com.codemind.source.SourceFileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageCitationRepository citationRepository;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final AiServiceClient aiServiceClient;
    private final TransactionTemplate transactionTemplate;

    public ConversationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            MessageCitationRepository citationRepository,
            ProjectRepository projectRepository,
            SourceFileRepository sourceFileRepository,
            AiServiceClient aiServiceClient,
            TransactionTemplate transactionTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.citationRepository = citationRepository;
        this.projectRepository = projectRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.aiServiceClient = aiServiceClient;
        this.transactionTemplate = transactionTemplate;
    }

    public ConversationDtos.Response create(String projectId, String rawTitle) {
        ProjectEntity project = requireReadyProject(projectId);
        String title = cleanTitle(rawTitle);
        ConversationEntity conversation = new ConversationEntity(
                UUID.randomUUID().toString(), project.getId(), title);
        conversationRepository.saveAndFlush(conversation);
        return new ConversationDtos.Response(
                conversation.getId(),
                conversation.getProjectId(),
                conversation.getTitle(),
                conversation.getCreatedAt());
    }

    public ConversationDtos.ConversationView get(String conversationId) {
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在"));
        List<ConversationDtos.MessageView> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream()
                .map(this::messageView)
                .toList();
        return new ConversationDtos.ConversationView(
                conversation.getId(),
                conversation.getProjectId(),
                conversation.getTitle(),
                messages,
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }

    public ConversationDtos.AskResponse ask(String conversationId, String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        if (question.isEmpty() || question.length() > 4000) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_QUESTION", "问题长度必须在 1 到 4000 个字符之间");
        }
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在"));
        ProjectEntity project = requireReadyProject(conversation.getProjectId());
        String versionId = project.getActiveSourceVersionId();
        AiServiceClient.QaResult result =
                aiServiceClient.answer(project.getId(), versionId, question);
        if (result == null || result.answer() == null) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "CHAT_UPSTREAM_ERROR", "回答生成服务返回无效结果");
        }
        List<CitationMapping> mappings = new ArrayList<>();
        if (result.citations() != null) {
            for (AiServiceClient.CitationResult citation : result.citations()) {
                SourceFileEntity file = sourceFileRepository
                        .findBySourceVersionIdAndRelativePath(versionId, citation.filePath())
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "CITATION_SOURCE_NOT_FOUND",
                                "回答引用无法绑定真实源码文件"));
                mappings.add(new CitationMapping(citation, file));
            }
        }
        String assistantMessageId = UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(status -> {
            ConversationEntity currentConversation = conversationRepository
                    .findById(conversationId).orElseThrow();
            messageRepository.save(new MessageEntity(
                    UUID.randomUUID().toString(),
                    conversationId,
                    MessageRole.USER,
                    question,
                    MessageStatus.COMPLETED,
                    null,
                    null));
            messageRepository.save(new MessageEntity(
                    assistantMessageId,
                    conversationId,
                    MessageRole.ASSISTANT,
                    result.answer(),
                    MessageStatus.COMPLETED,
                    versionId,
                    result.retrievalMetadata() == null
                            ? null : result.retrievalMetadata().chatModel()));
            mappings.forEach(mapping -> citationRepository.save(new MessageCitationEntity(
                    UUID.randomUUID().toString(),
                    assistantMessageId,
                    versionId,
                    mapping.file().getId(),
                    mapping.citation().filePath(),
                    mapping.citation().citationIndex(),
                    mapping.citation().startLine(),
                    mapping.citation().endLine(),
                    mapping.citation().snippet(),
                    mapping.citation().score())));
            if (currentConversation.getTitle() == null || currentConversation.getTitle().isBlank()) {
                String title = question.length() > 80 ? question.substring(0, 80) : question;
                currentConversation.updateTitle(title);
            }
            currentConversation.touch();
            conversationRepository.save(currentConversation);
        });
        AiServiceClient.RetrievalMetadataResult metadata = result.retrievalMetadata();
        return new ConversationDtos.AskResponse(
                assistantMessageId,
                result.answer(),
                MessageStatus.COMPLETED,
                versionId,
                result.citations() == null ? List.of() : result.citations().stream()
                        .map(citation -> new ConversationDtos.CitationView(
                                citation.citationIndex(),
                                citation.filePath(),
                                citation.startLine(),
                                citation.endLine(),
                                citation.snippet(),
                                citation.score()))
                        .toList(),
                metadata == null ? null : new ConversationDtos.RetrievalMetadataView(
                        metadata.strategy(),
                        metadata.topK(),
                        metadata.chatProvider(),
                        metadata.chatModel(),
                        metadata.embeddingModel(),
                        metadata.retrievalDurationMs(),
                        metadata.generationDurationMs()));
    }

    private ProjectEntity requireReadyProject(String projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或已删除"));
        if (project.getStatus() != ProjectStatus.READY
                || project.getActiveSourceVersionId() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "PROJECT_NOT_READY", "项目尚未完成索引，不能进行问答");
        }
        return project;
    }

    private String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String cleaned = title.replaceAll("\\p{Cntrl}", "").strip();
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }

    private ConversationDtos.MessageView messageView(MessageEntity message) {
        List<ConversationDtos.CitationView> citations = citationRepository
                .findByMessageIdOrderByCitationIndexAsc(message.getId())
                .stream()
                .map(citation -> new ConversationDtos.CitationView(
                        citation.getCitationIndex(),
                        citation.getFilePath(),
                        citation.getStartLine(),
                        citation.getEndLine(),
                        citation.getSnippet(),
                        citation.getRetrievalScore()))
                .toList();
        return new ConversationDtos.MessageView(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getStatus(),
                message.getSourceVersionId(),
                message.getErrorCode(),
                citations,
                message.getCreatedAt());
    }

    private record CitationMapping(
            AiServiceClient.CitationResult citation, SourceFileEntity file) {}
}
