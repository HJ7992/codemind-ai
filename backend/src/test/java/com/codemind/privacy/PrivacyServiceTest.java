package com.codemind.privacy;

import com.codemind.common.ApiException;
import com.codemind.config.PrivacyProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyServiceTest {

    @Test
    void 返回脱敏地址和明确外发范围() {
        PrivacyProperties properties = new PrivacyProperties(
                "v1",
                new PrivacyProperties.Endpoint("https://user:secret@chat.example/v1?key=secret#fragment"),
                new PrivacyProperties.Endpoint("https://embedding.example/v1"));

        PrivacyNoticeResponse response = new PrivacyService(properties).getNotice();

        assertThat(response.chat().baseUrl()).isEqualTo("https://chat.example/v1");
        assertThat(response.embedding().baseUrl()).isEqualTo("https://embedding.example/v1");
        assertThat(response.chat().dataTypes()).contains("USER_QUESTION", "RETRIEVED_CODE_CHUNK");
        assertThat(response.embedding().dataTypes()).contains("JAVA_OR_PYTHON_CODE_CHUNK");
        assertThat(response.secretDetectionLimitation()).contains("无法保证");
    }

    @Test
    void 配置不完整时明确失败() {
        PrivacyProperties properties = new PrivacyProperties(
                "v1",
                new PrivacyProperties.Endpoint(""),
                new PrivacyProperties.Endpoint("https://embedding.example/v1"));

        assertThatThrownBy(() -> new PrivacyService(properties).getNotice())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("配置不完整");
    }
}
