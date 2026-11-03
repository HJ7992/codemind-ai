package com.codemind.privacy;

import com.codemind.common.ApiException;
import com.codemind.config.PrivacyProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Service
public class PrivacyService {
    private final PrivacyProperties properties;

    public PrivacyService(PrivacyProperties properties) {
        this.properties = properties;
    }

    public PrivacyNoticeResponse getNotice() {
        if (properties.noticeVersion() == null || properties.noticeVersion().isBlank()) {
            throw configurationError();
        }
        return new PrivacyNoticeResponse(
                properties.noticeVersion(),
                new PrivacyNoticeResponse.EndpointNotice(
                        sanitize(properties.embedding().baseUrl()),
                        "将索引代码块转换为向量",
                        List.of("JAVA_OR_PYTHON_CODE_CHUNK")),
                new PrivacyNoticeResponse.EndpointNotice(
                        sanitize(properties.chat().baseUrl()),
                        "根据用户问题和检索代码片段生成回答",
                        List.of("USER_QUESTION", "RETRIEVED_CODE_CHUNK")),
                "代码文本将发送至上述第三方，请自行确认其数据保留政策。",
                "本地检测无法保证识别所有秘密，上传前请自行审查。");
    }

    private String sanitize(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.strip());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                throw configurationError();
            }
            return new URI(
                            uri.getScheme(),
                            null,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            null,
                            null)
                    .toString();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw configurationError();
        }
    }

    private ApiException configurationError() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MODEL_CONFIGURATION_INVALID",
                "模型服务配置不完整，无法生成隐私告知");
    }
}
