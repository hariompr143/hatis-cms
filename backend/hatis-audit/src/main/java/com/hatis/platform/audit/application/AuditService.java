package com.hatis.platform.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.audit.adapter.persistence.AuditLogRepository;
import com.hatis.platform.audit.domain.AuditLog;
import com.hatis.platform.shared.api.GlobalExceptionHandler;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.id.Identifiers;
import com.hatis.platform.shared.observability.RequestMetadata;
import com.hatis.platform.shared.observability.RequestMetadataHolder;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes tamper-evident audit records.
 *
 * <p>Two properties are load bearing:
 * <ol>
 *   <li><strong>Recording never breaks the business operation.</strong> A failure
 *       here is counted and logged; the caller's transaction is not rolled back.
 *       Losing an audit line is bad, losing a customer's deployment is worse — and
 *       the metric makes the loss visible instead of silent.</li>
 *   <li><strong>Secrets never reach the record.</strong> Metadata is passed
 *       through {@link #redact} and any key that looks credential-shaped is
 *       dropped entirely.</li>
 * </ol>
 */
@Service
public class AuditService implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Metadata keys that are never persisted, whatever their value. */
    private static final Set<String> FORBIDDEN_METADATA_KEYS = Set.of(
            "password", "secret", "token", "apikey", "api_key", "authorization",
            "privatekey", "private_key", "credential", "credentials", "cookie",
            "connectionstring", "connection_string", "signature", "otp", "mfaSecret");

    private static final String GENESIS_HASH = "0".repeat(64);

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Counter written;
    private final Counter failures;

    public AuditService(AuditLogRepository repository,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry,
                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.written = Counter.builder("hatis.audit.records").register(meterRegistry);
        this.failures = Counter.builder("hatis.audit.write_failures").register(meterRegistry);
    }

    @Override
    public void record(AuditRecord record) {
        AuditRecord enriched = enrich(record);
        try {
            // Executed through an explicit TransactionTemplate rather than a
            // self-invoked @Transactional method: self-invocation would bypass the
            // proxy and silently join (or skip) the caller's transaction.
            transactionTemplate.executeWithoutResult(status -> persist(enriched));
            written.increment();
        } catch (RuntimeException e) {
            failures.increment();
            log.error("Unable to write audit record action={} org={} : {}",
                    enriched.action(), enriched.organizationId(), e.getMessage());
        }
    }

    private void persist(AuditRecord record) {
        UUID organizationId = record.organizationId();
        String previousHash = GENESIS_HASH;
        long sequence = 0;
        if (organizationId != null) {
            List<AuditLog> head = repository.findChainHead(organizationId,
                    org.springframework.data.domain.PageRequest.of(0, 1));
            if (!head.isEmpty()) {
                previousHash = head.get(0).getRecordHash();
                sequence = head.get(0).getSequence() + 1;
            }
        }
        String recordHash = hash(previousHash, record, sequence);
        repository.save(new AuditLog(
                Identifiers.newId(),
                organizationId,
                record.actorType(),
                record.actorId(),
                record.actorEmail(),
                record.action(),
                record.resourceType(),
                record.resourceId(),
                record.result(),
                record.reason(),
                record.ip(),
                record.userAgent(),
                record.correlationId(),
                redactMetadata(record.metadata()),
                previousHash,
                recordHash,
                sequence,
                record.occurredAt()));
    }

    /** Fills in tenant, actor and request metadata the caller did not supply. */
    private AuditRecord enrich(AuditRecord record) {
        TenantContext context = TenantContextHolder.get();
        RequestMetadata request = RequestMetadataHolder.get();
        return AuditRecord.builder(record.action())
                .organization(record.organizationId() != null ? record.organizationId()
                        : (context == null ? null : context.organizationId()))
                .actor(record.actorId() != null ? record.actorType()
                                : (context == null ? AuditRecord.ActorType.SYSTEM : context.principalType() == null
                                        ? AuditRecord.ActorType.SYSTEM
                                        : AuditRecord.ActorType.valueOf(context.principalType().name())),
                        record.actorId() != null ? record.actorId()
                                : (context == null ? null : context.principalId()),
                        record.actorEmail())
                .resource(record.resourceType(), record.resourceId())
                .result(record.result())
                .reason(record.reason())
                .request(record.ip() != null ? record.ip() : request.ip(),
                        record.userAgent() != null ? record.userAgent() : request.userAgent())
                .correlationId(record.correlationId() != null ? record.correlationId()
                        : MDC.get(GlobalExceptionHandler.CORRELATION_ID_KEY))
                .metadata(record.metadata())
                .occurredAt(record.occurredAt())
                .build();
    }

    private String redactMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(redact(metadata));
        } catch (JsonProcessingException e) {
            return "{\"redacted\":true}";
        }
    }

    static Map<String, Object> redact(Map<String, Object> metadata) {
        java.util.Map<String, Object> safe = new java.util.LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            String normalised = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
            if (FORBIDDEN_METADATA_KEYS.contains(normalised)) {
                safe.put(key, "[REDACTED]");
                return;
            }
            if (value instanceof String text && looksSecret(text)) {
                safe.put(key, "[REDACTED]");
                return;
            }
            safe.put(key, value);
        });
        return safe;
    }

    private static boolean looksSecret(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("bearer ")
                || lower.startsWith("-----begin")
                || lower.startsWith("hatis_pk_")
                || lower.startsWith("hatis_sk_")
                || (value.length() >= 32 && value.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_')
                        && !value.contains("-") && !value.contains("_"));
    }

    /** Canonical, order-independent hash input so verification is reproducible. */
    static String hash(String previousHash, AuditRecord record, long sequence) {
        String canonical = String.join("|",
                previousHash,
                String.valueOf(sequence),
                String.valueOf(record.organizationId()),
                String.valueOf(record.actorType()),
                String.valueOf(record.actorId()),
                String.valueOf(record.action()),
                String.valueOf(record.resourceType()),
                String.valueOf(record.resourceId()),
                String.valueOf(record.result()),
                String.valueOf(record.reason()),
                String.valueOf(record.ip()),
                String.valueOf(record.correlationId()),
                String.valueOf(record.occurredAt() == null ? null : record.occurredAt().toEpochMilli()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
