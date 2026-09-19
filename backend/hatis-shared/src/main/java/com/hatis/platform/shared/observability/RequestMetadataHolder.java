package com.hatis.platform.shared.observability;

/** Thread-bound request metadata for audit and security event enrichment. */
public final class RequestMetadataHolder {

    private static final ThreadLocal<RequestMetadata> METADATA = new ThreadLocal<>();

    private RequestMetadataHolder() {
    }

    public static void set(RequestMetadata metadata) {
        METADATA.set(metadata);
    }

    public static RequestMetadata get() {
        RequestMetadata metadata = METADATA.get();
        return metadata == null ? RequestMetadata.unknown() : metadata;
    }

    public static void clear() {
        METADATA.remove();
    }
}
