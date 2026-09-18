package com.hatis.platform.integration.application;

import com.hatis.platform.identity.application.TenantKeyService;
import com.hatis.platform.integration.domain.WebhookSigner;
import com.hatis.platform.integration.port.out.WebhookTransport;
import com.hatis.platform.shared.secret.EncryptionService;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fans a platform event out to the customer URLs subscribed to it, and keeps trying.
 *
 * <h2>Transaction boundaries</h2>
 *
 * This class is deliberately <em>not</em> transactional. It makes an HTTP call per
 * endpoint, and a transaction held open across a customer's slow endpoint would hold a
 * connection and row locks for as long as that endpoint takes. Every database touch is
 * delegated to {@link WebhookDeliveryLog}, which is transactional per operation, so a
 * delivery that takes thirty seconds holds nothing for thirty seconds.
 *
 * <p>That split is also forced by {@code @TenantTransactional}, which reads the tenant
 * context on entry through the proxy; transactional methods on this class calling each
 * other would not be intercepted.
 *
 * <h2>Tenant scope</h2>
 *
 * The endpoints, the deliveries and the outbox row all sit behind forced row level
 * security, so everything runs inside a context bound to the event's organization. The
 * organization is supplied by the caller rather than read from the event, because reading
 * the event requires the context that the read would establish — the publisher already
 * knows it.
 *
 * <h2>Retries</h2>
 *
 * The payload is re-read from {@code plat_outbox} on each attempt rather than stored on
 * the delivery, so {@code int_webhook_deliveries} holds bookkeeping and not another copy
 * of every event the platform has ever emitted. The sweep is per organization for the
 * same reason the rest of this class is: a cross-tenant scan for due work is not
 * expressible under row level security, so the caller iterates organizations — which
 * {@code org_organizations} permits, it being the one table here with no RLS.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    /** After this many attempts a delivery is FAILED and the customer is told. */
    static final int MAX_ATTEMPTS = 8;

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(15);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    private final WebhookDeliveryLog deliveryLog;
    private final WebhookTransport transport;
    private final TenantKeyService tenantKeys;
    private final EncryptionService encryption;

    public WebhookDispatcher(WebhookDeliveryLog deliveryLog,
                             WebhookTransport transport,
                             TenantKeyService tenantKeys,
                             EncryptionService encryption) {
        this.deliveryLog = deliveryLog;
        this.transport = transport;
        this.tenantKeys = tenantKeys;
        this.encryption = encryption;
    }

    /**
     * Delivers one event to every endpoint subscribed to it.
     *
     * <p>A failure to deliver never fails the call. The event is already committed by the
     * time it reaches here, and a customer's endpoint being down is not a reason to roll
     * back the change that caused the event.
     *
     * @return the delivery records opened, in endpoint order
     */
    public List<UUID> dispatch(UUID organizationId, UUID eventId) {
        return TenantContextHolder.callAs(contextFor(organizationId), () -> {
            WebhookDeliveryLog.Event event = deliveryLog.event(eventId);
            if (event == null) {
                log.debug("No outbox entry {} to deliver", eventId);
                return List.of();
            }
            List<WebhookDeliveryLog.Target> targets = deliveryLog.activeTargets();
            if (targets.isEmpty()) {
                return List.of();
            }
            TenantKeyService.TenantKey key = tenantKeys.keyForOrganization(organizationId);

            List<UUID> opened = new ArrayList<>();
            for (WebhookDeliveryLog.Target target : targets) {
                if (!target.subscribesTo(event.eventType())) {
                    continue;
                }
                UUID deliveryId = deliveryLog.open(target.endpointId(), eventId, event.eventType());
                opened.add(deliveryId);
                attempt(organizationId, deliveryId, target, event, 0, key);
            }
            return List.copyOf(opened);
        });
    }

    /**
     * Retries the deliveries of one organization that have come due.
     *
     * @return how many deliveries were attempted or closed out
     */
    public int retryDue(UUID organizationId, Instant now, int limit) {
        return TenantContextHolder.callAs(contextFor(organizationId), () -> {
            List<WebhookDeliveryLog.Due> due = deliveryLog.dueBefore(now, limit);
            if (due.isEmpty()) {
                return 0;
            }
            TenantKeyService.TenantKey key = tenantKeys.keyForOrganization(organizationId);
            int processed = 0;
            for (WebhookDeliveryLog.Due pending : due) {
                Optional<WebhookDeliveryLog.Target> target =
                        deliveryLog.target(pending.endpointId());
                if (target.isEmpty() || !target.get().active()) {
                    // Paused or deleted. Retrying would be pointless, and leaving it
                    // PENDING would keep it in the queue forever.
                    deliveryLog.recordSkipped(pending.deliveryId(),
                            "the endpoint no longer accepts deliveries");
                    processed++;
                    continue;
                }
                WebhookDeliveryLog.Event event = deliveryLog.event(pending.eventId());
                if (event == null) {
                    deliveryLog.recordSkipped(pending.deliveryId(),
                            "the event is no longer available to deliver");
                    processed++;
                    continue;
                }
                attempt(organizationId, pending.deliveryId(), target.get(), event,
                        pending.attempts(), key);
                processed++;
            }
            return processed;
        });
    }

    private void attempt(UUID organizationId, UUID deliveryId, WebhookDeliveryLog.Target target,
                         WebhookDeliveryLog.Event event, int attemptsSoFar,
                         TenantKeyService.TenantKey key) {
        String secret;
        try {
            secret = encryption.decryptWith(key.wrappedDek(), key.keyId(),
                    target.secretCiphertext());
        } catch (RuntimeException e) {
            // Most likely a tenant key rotation that has not re-encrypted this endpoint.
            // Retrying cannot help, and throwing would abort the rest of the fan-out.
            log.warn("Endpoint {} cannot sign deliveries under the current tenant key",
                    target.endpointId());
            deliveryLog.recordSkipped(deliveryId, "the signing secret could not be decrypted");
            return;
        }

        Instant signedAt = Instant.now();
        byte[] payload = event.payload().getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of(
                WebhookSigner.SIGNATURE_HEADER,
                WebhookSigner.sign(event.payload(), secret, signedAt),
                WebhookSigner.EVENT_HEADER, event.eventType(),
                WebhookSigner.DELIVERY_HEADER, deliveryId.toString());

        WebhookTransport.Outcome outcome;
        try {
            outcome = transport.deliver(new WebhookTransport.Request(
                    target.url(), payload, headers, DELIVERY_TIMEOUT));
        } catch (RuntimeException e) {
            // The transport contract is not to throw; treating a breach as unreachable
            // keeps one broken adapter from losing the delivery record.
            outcome = WebhookTransport.Outcome.unreachable(e.getClass().getSimpleName());
        }

        if (outcome.isDelivered()) {
            deliveryLog.recordDelivered(deliveryId, outcome.httpStatus(), Instant.now());
            return;
        }
        if (outcome.result() == WebhookTransport.Outcome.Result.REFUSED_BY_POLICY) {
            deliveryLog.recordSkipped(deliveryId, outcome.detail());
            return;
        }
        int attempts = attemptsSoFar + 1;
        boolean exhausted = !outcome.isRetryable() || attempts >= MAX_ATTEMPTS;
        deliveryLog.recordFailure(deliveryId, outcome, nextAttemptAt(attempts), exhausted);
    }

    /**
     * Exponential backoff with a ceiling.
     *
     * <p>The ceiling matters more than the curve: without it the eighth attempt would be
     * scheduled days out, which is longer than most customers would keep a broken endpoint
     * around and longer than the platform should pretend to remember.
     */
    private static Instant nextAttemptAt(int attempts) {
        long seconds = Math.min(
                BASE_BACKOFF.toSeconds() * (1L << Math.min(attempts - 1, 20)),
                MAX_BACKOFF.toSeconds());
        return Instant.now().plusSeconds(seconds);
    }

    private static TenantContext contextFor(UUID organizationId) {
        return TenantContext.of(organizationId, null, TenantContext.PrincipalType.SYSTEM);
    }
}
