package com.hatis.platform.shared.event;

import com.hatis.platform.shared.id.Identifiers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The envelope every platform event is published in.
 *
 * <p>Consumers must be idempotent: {@code eventId} is stable across retries, and
 * {@code eventType} + {@code eventVersion} let a consumer skip events it cannot
 * yet understand instead of failing.
 *
 * <pre>
 * {
 *   "eventId":       "01890f...",
 *   "eventType":     "cms.content.published",
 *   "eventVersion":  1,
 *   "organizationId":"01890d...",
 *   "resourceType":  "content_item",
 *   "resourceId":    "01891a...",
 *   "correlationId": "01890c...",
 *   "occurredAt":    "2026-09-16T19:40:00Z",
 *   "data":          { … }
 * }
 * </pre>
 */
public record PlatformEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        UUID organizationId,
        String resourceType,
        UUID resourceId,
        String correlationId,
        Instant occurredAt,
        Map<String, Object> data) {

    public static Builder of(String eventType, UUID organizationId) {
        return new Builder(eventType, organizationId);
    }

    public static final class Builder {
        private final String eventType;
        private final UUID organizationId;
        private int eventVersion = 1;
        private String resourceType;
        private UUID resourceId;
        private String correlationId;
        private Map<String, Object> data = Map.of();

        private Builder(String eventType, UUID organizationId) {
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("eventType is required");
            }
            this.eventType = eventType;
            this.organizationId = organizationId;
        }

        public Builder version(int value) {
            this.eventVersion = value;
            return this;
        }

        public Builder resource(String type, UUID id) {
            this.resourceType = type;
            this.resourceId = id;
            return this;
        }

        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        public Builder data(Map<String, Object> value) {
            this.data = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }

        public PlatformEvent build() {
            return new PlatformEvent(Identifiers.newId(), eventType, eventVersion, organizationId,
                    resourceType, resourceId, correlationId, Instant.now(), data);
        }
    }
}
