package com.hatis.platform.integration.adapter.persistence;

import com.hatis.platform.integration.domain.InboundIntegration;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
