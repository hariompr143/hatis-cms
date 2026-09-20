package com.hatis.platform.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The published language for audit records.
 *
 * <p>Every context emits audit records through this shape and never writes to the
 * audit table directly, which keeps the audit store swappable (database, SIEM,
 * object storage) and keeps the hash chain consistent.
 */
public record AuditRecord(
        UUID organizationId,
        ActorType actorType,
        UUID actorId,
        String actorEmail,
        String action,
        String resourceType,
        UUID resourceId,
        Result result,
        String reason,
        String ip,
        String userAgent,
        String correlationId,
        Map<String, Object> metadata,
        Instant occurredAt) {

    public enum ActorType {
        USER,
        SERVICE_ACCOUNT,
        API_KEY,
        PLATFORM_OPERATOR,
        SYSTEM
    }

    public enum Result {
        SUCCESS,
        DENIED,
        FAILED
    }

    public static Builder builder(String action) {
        return new Builder(action);
    }

    /** Fluent builder; {@code action} is the only mandatory field. */
    public static final class Builder {
        private final String action;
        private UUID organizationId;
        private ActorType actorType = ActorType.SYSTEM;
        private UUID actorId;
        private String actorEmail;
        private String resourceType;
        private UUID resourceId;
        private Result result = Result.SUCCESS;
        private String reason;
        private String ip;
        private String userAgent;
        private String correlationId;
        private Map<String, Object> metadata = Map.of();
        private Instant occurredAt = Instant.now();

        private Builder(String action) {
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("audit action is required");
            }
            this.action = action;
        }

        public Builder organization(UUID value) {
            this.organizationId = value;
            return this;
        }

        public Builder actor(ActorType type, UUID id, String email) {
            this.actorType = type;
            this.actorId = id;
            this.actorEmail = email;
            return this;
        }

        public Builder resource(String type, UUID id) {
            this.resourceType = type;
            this.resourceId = id;
            return this;
        }

        public Builder result(Result value) {
            this.result = value;
            return this;
        }

        public Builder reason(String value) {
            this.reason = value;
            return this;
        }

        public Builder request(String ipAddress, String agent) {
            this.ip = ipAddress;
            this.userAgent = agent;
            return this;
        }

        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            this.metadata = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }

        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(organizationId, actorType, actorId, actorEmail, action, resourceType,
                    resourceId, result, reason, ip, userAgent, correlationId, metadata, occurredAt);
        }
    }
}
