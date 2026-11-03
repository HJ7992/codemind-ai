package com.codemind;

import com.codemind.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
        })
@AutoConfigureMockMvc
class HealthContractTest {

    @MockitoBean
    private ProjectRepository projectRepository;

    @MockitoBean
    private com.codemind.source.SourceVersionRepository sourceVersionRepository;

    @MockitoBean
    private com.codemind.indexing.IndexJobRepository indexJobRepository;

    @MockitoBean
    private com.codemind.source.SourceFileRepository sourceFileRepository;

    @MockitoBean
    private com.codemind.conversation.ConversationRepository conversationRepository;

    @MockitoBean
    private com.codemind.conversation.MessageRepository messageRepository;

    @MockitoBean
    private com.codemind.conversation.MessageCitationRepository messageCitationRepository;

    @MockitoBean
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 健康检查只返回聚合状态() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
