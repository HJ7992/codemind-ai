package com.codemind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codemind.privacy")
public record PrivacyProperties(String noticeVersion, Endpoint chat, Endpoint embedding) {
    public record Endpoint(String baseUrl) {}
}
