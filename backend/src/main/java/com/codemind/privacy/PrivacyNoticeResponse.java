package com.codemind.privacy;

import java.util.List;

public record PrivacyNoticeResponse(
        String noticeVersion,
        EndpointNotice embedding,
        EndpointNotice chat,
        String thirdPartyRetentionNotice,
        String secretDetectionLimitation) {
    public record EndpointNotice(String baseUrl, String purpose, List<String> dataTypes) {}
}
