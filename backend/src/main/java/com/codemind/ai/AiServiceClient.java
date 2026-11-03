package com.codemind.ai;

import com.codemind.common.ApiException;
import com.codemind.common.TraceIdFilter;
import com.codemind.config.AiServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class AiServiceClient {
    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final AiServiceProperties properties;

    public AiServiceClient(AiServiceProperties properties) {
        this.properties = properties;
    }

    public IndexResult index(String jobId, String projectId, String versionId, String storageKey) {
        Map<String, Object> body = Map.of(
                "jobId", jobId,
                "projectId", projectId,
                "sourceVersionId", versionId,
                "storageKey", storageKey);
        try {
            return client(properties.indexTimeoutSeconds())
                    .post()
                    .uri("/internal/v1/indexes")
                    .body(body)
                    .retrieve()
                    .body(IndexResult.class);
        } catch (RestClientException exception) {
            log.warn("AI 索引调用失败 errorType={} status={}", exception.getClass().getSimpleName(),
                    statusOf(exception));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "AI_INDEXING_ERROR", "AI 索引服务调用失败");
        }
    }

    public QaResult answer(String projectId, String versionId, String question) {
        Map<String, Object> body = Map.of(
                "projectId", projectId,
                "sourceVersionId", versionId,
                "question", question);
        try {
            return client(properties.qaTimeoutSeconds())
                    .post()
                    .uri("/internal/v1/qa")
                    .body(body)
                    .retrieve()
                    .body(QaResult.class);
        } catch (RestClientException exception) {
            log.warn("AI 问答调用失败 errorType={} status={}", exception.getClass().getSimpleName(),
                    statusOf(exception));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "CHAT_UPSTREAM_ERROR", "回答生成服务暂时不可用");
        }
    }

    public void deleteIndex(String projectId, String versionId) {
        try {
            client(30)
                    .delete()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/v1/indexes/{versionId}")
                            .queryParam("project_id", projectId)
                            .build(versionId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("AI 索引删除失败 errorType={} status={}",
                    exception.getClass().getSimpleName(), statusOf(exception));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "AI_DELETE_ERROR", "AI 索引清理失败");
        }
    }

    private RestClient client(int timeoutSeconds) {
        if (properties.internalServiceToken() == null
                || properties.internalServiceToken().isBlank()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_NOT_CONFIGURED", "AI 服务凭证未配置");
        }
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.internalServiceToken());
        String traceId = traceId();
        if (!traceId.isBlank()) {
            builder.defaultHeader("X-Trace-Id", traceId);
        }
        return builder.build();
    }

    private int statusOf(RestClientException exception) {
        return exception instanceof RestClientResponseException response
                ? response.getStatusCode().value() : -1;
    }

    private String traceId() {
        String value = MDC.get(TraceIdFilter.MDC_KEY);
        return value == null ? "" : value;
    }

    public record IndexResult(
            String jobId,
            String status,
            int sourceFileCount,
            int chunkCount,
            String embeddingModel,
            List<SourceFileResult> sourceFiles) {}

    public record SourceFileResult(
            String relativePath,
            String language,
            long sizeBytes,
            int lineCount,
            String sha256,
            String parseStatus) {}

    public record QaResult(
            String answer,
            List<CitationResult> citations,
            RetrievalMetadataResult retrievalMetadata) {}

    public record CitationResult(
            int citationIndex,
            String filePath,
            int startLine,
            int endLine,
            String snippet,
            double score) {}

    public record RetrievalMetadataResult(
            String strategy,
            int topK,
            String chatProvider,
            String chatModel,
            String embeddingModel,
            long retrievalDurationMs,
            long generationDurationMs) {}
}
