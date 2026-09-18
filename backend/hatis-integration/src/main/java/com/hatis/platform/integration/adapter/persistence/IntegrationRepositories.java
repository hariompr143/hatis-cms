package com.hatis.platform.integration.adapter.persistence;

import com.hatis.platform.integration.domain.InboundIntegration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration persistence.
 *
 * <p>Management finders take {@code organizationId} explicitly, as everywhere else in
 * the platform: Spring Data adds no tenant predicate for us and row level security is
 * the backstop rather than the control.
 */
public interface IntegrationRepositories {

    interface IntegrationRepository extends JpaRepository<InboundIntegration, UUID> {

        Optional<InboundIntegration> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<InboundIntegration> findByOrganizationIdAndType(UUID organizationId,
                                                             InboundIntegration.Type type);

        Optional<InboundIntegration> findByOrganizationIdAndName(UUID organizationId, String name);

        /**
         * Resolves an inbound delivery by the identifier in its URL, before any tenant
         * is known.
         *
         * <p>This is the one finder in the module that is not tenant scoped, and it has
         * to be: the caller is GitHub, which presents no tenant. The organization is
         * <em>derived</em> from the row this returns and the delivery is accepted only
         * if it carries an HMAC under that row's secret. Scoping by a client-supplied
         * organization here would be exactly the mistake the rest of the platform
         * avoids — it would let a caller assert a tenant instead of proving one.
         *
         * <p>{@link com.hatis.platform.integration.application.InboundWebhookService}
         * is the only caller, and it never trusts anything from the request body for
         * tenant resolution.
         */
        @Override
        Optional<InboundIntegration> findById(UUID id);
    }

    /**
     * Outbound endpoints. Every finder takes {@code organizationId} explicitly; row
     * level security is the backstop, not the control.
     */
    interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

        Optional<WebhookEndpoint> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<WebhookEndpoint> findByOrganizationId(UUID organizationId);

        /** The endpoints an event has to be fanned out to, in registration order. */
        List<WebhookEndpoint> findByOrganizationIdAndActiveTrue(UUID organizationId);
    }

    /**
     * Delivery bookkeeping.
     *
     * <p>{@code findByStatusAndNextAttemptAtBefore} is the dispatcher's work queue. It is
     * deliberately not a locking query: claiming rows atomically across workers is the
     * dispatcher's job and it does it with {@code for update skip locked}, which no
     * derived finder can express.
     */
    interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

        Optional<WebhookDelivery> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<WebhookDelivery> findByEndpointIdAndOrganizationId(UUID endpointId, UUID organizationId);

        /**
         * The dispatcher's retry queue: pending deliveries whose next attempt is due.
         *
         * <p>Rows with no {@code next_attempt_at} are excluded, which is what a freshly
         * opened delivery looks like — the attempt that owns it is still in flight, and
         * picking it up here would deliver the same event twice.
         */
        List<WebhookDelivery> findByOrganizationIdAndStatusAndNextAttemptAtBefore(
                UUID organizationId, WebhookDelivery.Status status, Instant cutoff);

        long countByEndpointIdAndOrganizationIdAndStatus(UUID endpointId, UUID organizationId,
                                                         WebhookDelivery.Status status);
    }
}
