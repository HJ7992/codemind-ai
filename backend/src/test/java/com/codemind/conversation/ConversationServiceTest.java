package com.codemind.conversation;

import com.codemind.ai.AiServiceClient;
import com.codemind.common.ApiException;
import com.codemind.project.ProjectEntity;
import com.codemind.project.ProjectRepository;
import com.codemind.project.ProjectStatus;
import com.codemind.source.SourceFileEntity;
import com.codemind.source.SourceFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    @Test
    void 问答保存用户消息助手消息和真实引用() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        MessageCitationRepository citations = mock(MessageCitationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SourceFileRepository files = mock(SourceFileRepository.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        ProjectEntity project = new ProjectEntity("p1", "demo", ProjectStatus.INDEXING);
        project.markReady("v1");
        ConversationEntity conversation = new ConversationEntity("c1", "p1", "登录分析");
        when(conversations.findById("c1")).thenReturn(Optional.of(conversation));
        when(projects.findById("p1")).thenReturn(Optional.of(project));
        when(files.findBySourceVersionIdAndRelativePath("v1", "src/Auth.java"))
                .thenReturn(Optional.of(new SourceFileEntity(
                        "f1", "v1", "src/Auth.java", "JAVA", 20, 10, "a".repeat(64), "PARSED")));
        when(ai.answer("p1", "v1", "登录在哪里？")).thenReturn(new AiServiceClient.QaResult(
                "登录见 [1]。",
                List.of(new AiServiceClient.CitationResult(
                        1, "src/Auth.java", 3, 5, "boolean login() {}", 0.9)),
                new AiServiceClient.RetrievalMetadataResult(
                        "VECTOR", 5, "MOCK", "chat", "embedding", 1, 2)));
        ConversationService service = service(
                conversations, messages, citations, projects, files, ai);

        ConversationDtos.AskResponse response = service.ask("c1", "登录在哪里？");

        assertThat(response.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(response.sourceVersionId()).isEqualTo("v1");
        assertThat(response.citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.filePath()).isEqualTo("src/Auth.java");
                    assertThat(citation.startLine()).isEqualTo(3);
                    assertThat(citation.endLine()).isEqualTo(5);
                });
        verify(messages, org.mockito.Mockito.times(2)).save(any(MessageEntity.class));
        verify(citations).save(any(MessageCitationEntity.class));
    }

    @Test
    void 项目未就绪时不调用模型() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        AiServiceClient ai = mock(AiServiceClient.class);
        when(conversations.findById("c1"))
                .thenReturn(Optional.of(new ConversationEntity("c1", "p1", null)));
        when(projects.findById("p1"))
                .thenReturn(Optional.of(new ProjectEntity("p1", "demo", ProjectStatus.INDEXING)));
        ConversationService service = service(
                conversations,
                mock(MessageRepository.class),
                mock(MessageCitationRepository.class),
                projects,
                mock(SourceFileRepository.class),
                ai);

        assertThatThrownBy(() -> service.ask("c1", "问题"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("PROJECT_NOT_READY");
        verify(ai, never()).answer(any(), any(), any());
    }

    private ConversationService service(
            ConversationRepository conversations,
            MessageRepository messages,
            MessageCitationRepository citations,
            ProjectRepository projects,
            SourceFileRepository files,
            AiServiceClient ai) {
        return new ConversationService(
                conversations,
                messages,
                citations,
                projects,
                files,
                ai,
                new TransactionTemplate(new NoOpTransactionManager()));
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }
}
