package com.codemind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codemind.upload")
public record UploadProperties(
        long maxZipBytes,
        long maxUncompressedBytes,
        int maxEntries,
        long maxSourceFileBytes,
        int maxPathDepth,
        double maxCompressionRatio) {}
