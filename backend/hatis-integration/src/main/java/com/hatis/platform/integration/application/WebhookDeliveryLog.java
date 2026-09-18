package com.hatis.platform.integration.application;

import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.adapter.persistence.WebhookDelivery;
import com.hatis.platform.integration.adapter.persistence.WebhookEndpoint;
import com.hatis.platform.integration.port.out.WebhookTransport;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.OutboxRepository;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional half of outbound delivery.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * {@link WebhookDispatcher} makes network calls, and a database transaction must never be
 * held open across one: a customer's endpoint that takes thirty seconds to answer would
 * otherwise hold a row lock and a connection for thirty seconds. So the dispatcher is
 * deliberately not transactional, and the bookkeeping lives here.
 *
 * <p>It cannot simply be a set of transactional methods on the dispatcher, because
 * {@code @TenantTransactional} reads the tenant context when the proxy is entered and
 * self-invocation does not go through the proxy. The same constraint produced the two-bean
 * split on the inbound side, for the same reason.
 *
 * <p>Every method here is tenant scoped: {@code int_webhook_endpoints},
 * {@code int_webhook_deliveries} and {@code plat_outbox} all carry forced row level
 * security, so the caller must already be inside the right tenant context.
 */
@Service
public class WebhookDeliveryLog {

    private final IntegrationRepositories.WebhookEndpointRepository endpoints;
    private final IntegrationRepositories.WebhookDeliveryRepository deliveries;
    private final OutboxRepository outbox;

    public WebhookDeliveryLog(IntegrationRepositories.WebhookEndpointRepository endpoints,
                              IntegrationRepositories.WebhookDeliveryRepository deliveries,
                              OutboxRepository outbox) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.outbox = outbox;
    }

    /** The event body, read back from the outbox so a retry sends the original payload. */
    @TenantTransactional
    public Event event(UUID eventId) {
        return outbox.findById(eventId)
                .map(e -> new Event(e.getId(), e.getEventType(), e.getPayload()))
                .orElse(null);
    }

    /** Every endpoint that could receive an event right now. */
    @TenantTransactional
    public List<Target> activeTargets() {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return endpoints.findByOrganizationIdAndActiveTrue(organizationId).stream()
                .map(Target::from)
                .toList();
    }

    @TenantTransactional
    public Optional<Target> target(UUID endpointId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return endpoints.findByIdAndOrganizationId(endpointId, organizationId).map(Target::from);
    }

    /** Opens the record for an attempt before the attempt is made, so a crash leaves a trace. */
    @TenantTransactional
    public UUID open(UUID endpointId, UUID eventId, String eventType) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        WebhookDelivery delivery = WebhookDelivery.pending(organizationId, endpointId,
                eventId, eventType);
        return deliveries.save(delivery).getId();
    }

    @TenantTransactional
    public void recordDelivered(UUID deliveryId, int responseStatus, Instant deliveredAt) {
        mutate(deliveryId, d -> d.recordDelivered(responseStatus, deliveredAt));
    }

    @TenantTransactional
    public void recordFailure(UUID deliveryId, WebhookTransport.Outcome outcome,
                              Instant nextAttempt, boolean exhausted) {
        mutate(deliveryId, d -> d.recordFailure(outcome.httpStatus(), describe(outcome),
                nextAttempt, exhausted));
    }

    @TenantTransactional
    public void recordSkipped(UUID deliveryId, String reason) {
        mutate(deliveryId, d -> d.recordSkipped(reason));
    }

    /** Pending deliveries whose next attempt has come due, oldest first. */
    @TenantTransactional
    public List<Due> dueBefore(Instant cutoff, int limit) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return deliveries.findByOrganizationIdAndStatusAndNextAttemptAtBefore(
                        organizationId, WebhookDelivery.Status.PENDING, cutoff)
                .stream()
                .sorted((a, b) -> a.getNextAttemptAt().compareTo(b.getNextAttemptAt()))
                .limit(limit)
                .map(d -> new Due(d.getId(), d.getEndpointId(), d.getEventId(), d.getAttempts()))
                .toList();
    }

    /** Deliveries opened by the event sink and not yet attempted, oldest first. */
    @TenantTransactional
    public List<Due> unattempted(int limit) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return deliveries
                .findByOrganizationIdAndStatusAndAttemptsAndNextAttemptAtIsNullOrderByCreatedAtAsc(
                        organizationId, WebhookDelivery.Status.PENDING, 0)
                .stream()
                .limit(limit)
                .map(d -> new Due(d.getId(), d.getEndpointId(), d.getEventId(), d.getAttempts()))
                .toList();
    }

    private void mutate(UUID deliveryId, java.util.function.Consumer<WebhookDelivery> change) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        WebhookDelivery delivery = deliveries.findByIdAndOrganizationId(deliveryId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Webhook delivery", deliveryId));
        change.accept(delivery);
        deliveries.save(delivery);
    }

    /**
     * The failure text stored on the delivery.
     *
     * <p>The transport returns an exception class name rather than a message precisely so
     * that this cannot pick up a URL or a header; the signature travels in a header, so a
     * message that echoed the request would put a valid signature in the database.
     */
    private static String describe(WebhookTransport.Outcome outcome) {
        return switch (outcome.result()) {
            case REJECTED -> "endpoint responded " + outcome.httpStatus();
            case UNREACHABLE -> outcome.detail() == null ? "unreachable" : outcome.detail();
            case REFUSED_BY_POLICY -> outcome.detail() == null ? "refused" : outcome.detail();
            case DELIVERED -> null;
        };
    }

    /** The event to deliver. The payload is the exact bytes that get signed. */
    public record Event(UUID id, String eventType, String payload) {
    }

    /**
     * Everything needed to sign and send to one endpoint.
     *
     * <p>Carries the ciphertext, never the plaintext: the dispatcher decrypts it in memory
     * for as long as one attempt takes.
     */
    public record Target(UUID endpointId, String url, List<String> events, boolean active,
                         String secretCiphertext, String dekId) {

        static Target from(WebhookEndpoint endpoint) {
            return new Target(endpoint.getId(), endpoint.getUrl(), endpoint.getEvents(),
                    endpoint.isActive(), endpoint.getSecretCiphertext(), endpoint.getDekId());
        }

        /** An empty subscription means every event, which is what a customer expects. */
        public boolean subscribesTo(String eventType) {
            return events == null || events.isEmpty() || events.contains(eventType);
        }
    }

    /** A delivery waiting for a retry. */
    public record Due(UUID deliveryId, UUID endpointId, UUID eventId, int attempts) {
    }
}
