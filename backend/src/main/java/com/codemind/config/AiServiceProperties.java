package com.codemind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codemind.ai")
public record AiServiceProperties(
        String baseUrl,
        String internalServiceToken,
        int indexTimeoutSeconds,
        int qaTimeoutSeconds,
        int maxAttempts) {}
