package com.codemind.common;

public record ErrorResponse(String errorCode, String message, String traceId) {}
