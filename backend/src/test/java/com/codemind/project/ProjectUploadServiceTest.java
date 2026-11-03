package com.codemind.project;

import com.codemind.common.ApiException;
import com.codemind.config.PrivacyProperties;
import com.codemind.config.SourceProperties;
import com.codemind.config.UploadProperties;
import com.codemind.indexing.IndexJobEntity;
import com.codemind.indexing.IndexJobRepository;
import com.codemind.source.SourceVersionEntity;
import com.codemind.source.SourceVersionRepository;
import com.codemind.upload.ZipSafetyService;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProjectUploadServiceTest {
    @TempDir
    Path sourceRoot;

    @Test
    void 上传成功后分别持久化项目版本和待处理任务() throws IOException {
        ProjectRepository projects = mock(ProjectRepository.class);
        SourceVersionRepository versions = mock(SourceVersionRepository.class);
        IndexJobRepository jobs = mock(IndexJobRepository.class);
        ProjectUploadService service = service(projects, versions, jobs);

        UploadAcceptedResponse response = service.upload(zip("class Main {}\n"), "v1", true);

        assertThat(response.status()).isEqualTo(ProjectStatus.INDEXING);
        assertThat(response.projectId()).isNotBlank();
        assertThat(response.sourceVersionId()).isNotBlank();
        assertThat(response.indexJobId()).isNotBlank();
        verify(projects).save(any(ProjectEntity.class));
        verify(versions).save(any(SourceVersionEntity.class));
        verify(jobs).save(any(IndexJobEntity.class));
        Path source = sourceRoot.resolve("projects")
                .resolve(response.projectId())
                .resolve("versions")
                .resolve(response.sourceVersionId())
                .resolve("src/Main.java");
        assertThat(Files.readString(source)).isEqualTo("class Main {}\n");
    }

    @Test
    void 未经隐私确认时不处理文件() {
        ProjectRepository projects = mock(ProjectRepository.class);
        SourceVersionRepository versions = mock(SourceVersionRepository.class);
        IndexJobRepository jobs = mock(IndexJobRepository.class);
        ProjectUploadService service = service(projects, versions, jobs);

        assertThatThrownBy(() -> service.upload(zip("class Main {}"), "v1", false))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo("PRIVACY_NOTICE_REQUIRED");
        verify(projects, never()).save(any());
    }

    @Test
    void 安全校验失败后清理全部临时和最终目录() throws IOException {
        ProjectRepository projects = mock(ProjectRepository.class);
        SourceVersionRepository versions = mock(SourceVersionRepository.class);
        IndexJobRepository jobs = mock(IndexJobRepository.class);
        ProjectUploadService service = service(projects, versions, jobs);

        assertThatThrownBy(() -> service.upload(zip("-----BEGIN PRIVATE KEY-----\nabc"), "v1", true))
                .isInstanceOf(ApiException.class);
        try (var paths = Files.walk(sourceRoot)) {
            assertThat(paths).allMatch(Files::isDirectory);
        }
    }

    private ProjectUploadService service(
            ProjectRepository projects, SourceVersionRepository versions, IndexJobRepository jobs) {
        PrivacyProperties privacy = new PrivacyProperties(
                "v1",
                new PrivacyProperties.Endpoint("https://chat.example/v1"),
                new PrivacyProperties.Endpoint("https://embedding.example/v1"));
        ZipSafetyService zipSafety = new ZipSafetyService(
                new UploadProperties(100000, 1000000, 100, 100000, 20, 1000));
        return new ProjectUploadService(
                privacy,
                new SourceProperties(sourceRoot.toString()),
                zipSafety,
                projects,
                versions,
                jobs,
                new TransactionTemplate(new NoOpTransactionManager()),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                2);
    }

    private MockMultipartFile zip(String source) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
                zip.putArchiveEntry(new ZipArchiveEntry("src/Main.java"));
                zip.write(source.getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
            return new MockMultipartFile("file", "demo.zip", "application/zip", bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
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
