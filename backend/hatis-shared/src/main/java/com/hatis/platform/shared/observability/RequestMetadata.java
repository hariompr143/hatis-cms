package com.hatis.platform.shared.observability;

/**
 * Non-secret request metadata needed for audit records and abuse detection.
 *
 * <p>Captured once per request and cleared afterwards. Client IP honours
 * {@code X-Forwarded-For} only when the platform is configured behind a trusted
 * proxy, so a client cannot spoof the address that appears in an audit record.
 */
public record RequestMetadata(String ip, String userAgent, String method, String path, String origin) {

    public static RequestMetadata unknown() {
        return new RequestMetadata(null, null, null, null, null);
    }
}
