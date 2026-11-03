package com.codemind.upload;

import java.nio.file.Path;

public record ExtractionResult(
        Path extractedDirectory, int sourceFileCount, long totalSourceBytes, String archiveSha256) {}
