package com.hatis.platform.shared.idempotency;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency for mutating API calls.
 *
 * <p>Usage from a controller:
 * <pre>
 * var reservation = idempotency.reserve(orgId, key, request.method, path, body);
 * if (reservation.replay() != null) {
 *     return reservation.replay();          // identical earlier request: return its response
 * }
 * var result = useCase.execute(command);
 * idempotency.complete(reservation.record().getId(), status, result);
 * </pre>
 *
 * <p>The reservation row is committed in its own transaction so that a concurrent
 * duplicate is blocked by the unique constraint even before the first request
 * finishes.
 */
@Service
public class IdempotencyService {

    /** How long a key is remembered. Long enough for any realistic client retry window. */
    public static final Duration RETENTION = Duration.ofHours(24);

    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(UUID organizationId, String key, String method, String path, String body) {
        validateKey(key);
        String requestHash = sha256(body == null ? "" : body);
        Optional<IdempotencyRecord> existing = repository.findByOrganizationIdAndKey(organizationId, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                throw new PlatformExceptions.IdempotencyConflict();
            }
            return new Reservation(record, record.isCompleted() ? record : null);
        }
        IdempotencyRecord record = repository.save(new IdempotencyRecord(
                organizationId, key, method, path, requestHash, Instant.now().plus(RETENTION)));
        return new Reservation(record, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID recordId, int status, String responseBodyJson) {
        repository.findById(recordId).ifPresent(record -> {
            record.complete(status, responseBodyJson);
            repository.save(record);
        });
    }

    @Transactional
    public int purgeExpired() {
        return repository.deleteExpired(Instant.now());
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128
                || !key.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':')) {
            throw new PlatformExceptions.Validation(
                    "The Idempotency-Key header must be 1-128 characters of [A-Za-z0-9-_:]",
                    java.util.Map.of());
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * @param record the reservation row
     * @param replay non-null when an identical request already completed and its
     *               response should be returned instead of executing again
     */
    public record Reservation(IdempotencyRecord record, IdempotencyRecord replay) {
    }
}
