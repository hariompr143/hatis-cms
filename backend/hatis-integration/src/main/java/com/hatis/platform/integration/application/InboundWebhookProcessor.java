package com.hatis.platform.integration.application;

import com.hatis.platform.integration.domain.GitHubPushEvent;
import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.domain.InboundIntegration;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * The tenant-scoped half of inbound webhook handling.
 *
 * <p>Separated from {@link InboundWebhookService} for a mechanical reason, not a
 * stylistic one. {@code @TenantTransactional} reads the tenant from the thread when
 * the method is entered and rejects the call if none is bound, but an inbound
 * delivery has no tenant until the integration row has been read and its signature
 * verified. So the caller resolves and proves the tenant first, binds it, and only
 * then reaches this bean. Self-invocation would bypass the proxy and lose the
 * transaction, which is why this is a second bean rather than a private method.
 */
@Service
public class InboundWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookProcessor.class);

    private final IntegrationRepositories.IntegrationRepository store;
    private final AuditRecorder audit;
    private final EventPublisher events;

    public InboundWebhookProcessor(IntegrationRepositories.IntegrationRepository store,
                                   AuditRecorder audit,
                                   EventPublisher events) {
        this.store = store;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Records a verified push delivery: touches the integration, publishes a platform
     * event and writes the audit entry, all in one transaction.
     */
    @TenantTransactional
    public void acceptPush(InboundIntegration integration, GitHubPushEvent push, String deliveryId) {
        integration.recordUsed(Instant.now());
        store.save(integration);

        events.publish(PlatformEvent.of("integration.github.push", integration.getOrganizationId())
                .resource("integration", integration.getId())
                .data(Map.of(
                        "deliveryId", deliveryId,
                        "repository", push.repository(),
                        "ref", push.ref(),
                        "after", push.after(),
                        "before", push.before(),
                        "commitCount", push.commitCount(),
                        "created", push.created()))
                .build());

        audit.record(AuditRecord.builder("integration.webhook.received")
                .organization(integration.getOrganizationId())
                .actor(AuditRecord.ActorType.SERVICE_ACCOUNT, null, null)
                .resource("integration", integration.getId())
                .result(AuditRecord.Result.SUCCESS)
                .metadata(Map.of(
                        "provider", "github",
                        "deliveryId", deliveryId,
                        "repository", push.repository(),
                        "ref", push.ref()))
                .build());

        log.debug("Recorded GitHub push {} for integration {}", push.after(), integration.getId());
    }

    /** Acknowledges a verified delivery the platform does not act on. */
    @TenantTransactional
    public void acknowledge(InboundIntegration integration, String eventType, String deliveryId) {
        audit.record(AuditRecord.builder("integration.webhook.ignored")
                .organization(integration.getOrganizationId())
                .actor(AuditRecord.ActorType.SERVICE_ACCOUNT, null, null)
                .resource("integration", integration.getId())
                .result(AuditRecord.Result.SUCCESS)
                .reason("event type not acted on in Phase 1")
                .metadata(Map.of("provider", "github", "deliveryId", deliveryId, "eventType", eventType))
                .build());
    }
}
