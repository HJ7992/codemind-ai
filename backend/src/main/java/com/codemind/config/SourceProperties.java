package com.codemind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codemind")
public record SourceProperties(String sourceRoot) {}
