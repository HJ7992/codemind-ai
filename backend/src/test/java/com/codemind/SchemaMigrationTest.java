package com.codemind;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SchemaMigrationTest {

    private static final Set<String> CORE_TABLES = Set.of(
            "project",
            "source_version",
            "index_job",
            "source_file",
            "conversation",
            "message",
            "message_citation");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("codemind")
            .withUsername("codemind")
            .withPassword("codemind-test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void 迁移创建全部约束且重复执行安全() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'codemind'",
                String.class);
        assertThat(tables).containsAll(CORE_TABLES);

        Integer innodbUtf8Tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'codemind' AND engine = 'InnoDB' "
                        + "AND table_collation = 'utf8mb4_0900_ai_ci' "
                        + "AND table_name IN ('project', 'source_version', 'index_job', 'source_file', "
                        + "'conversation', 'message', 'message_citation')",
                Integer.class);
        assertThat(innodbUtf8Tables).isEqualTo(7);

        Integer foreignKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT constraint_name) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'codemind' AND constraint_type = 'FOREIGN KEY'",
                Integer.class);
        assertThat(foreignKeys).isEqualTo(9);

        assertThatCode(() -> flyway.migrate()).doesNotThrowAnyException();
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(appliedMigrations).isEqualTo(1);
    }
}
