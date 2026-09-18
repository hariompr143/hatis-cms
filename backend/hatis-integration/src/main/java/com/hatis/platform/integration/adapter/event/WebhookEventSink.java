package com.hatis.platform.integration.adapter.event;

import com.hatis.platform.integration.application.WebhookDeliveryLog;
import com.hatis.platform.shared.event.EventSink;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns platform events into outbound webhook deliveries.
 *
 * <h2>This only opens delivery records — it never sends</h2>
 *
 * {@code send} runs inside {@code OutboxRelay}'s transaction, and a database transaction
 * must not be held open across an HTTP call to a customer's endpoint. So the sink does the
 * cheap, transactional half: find the endpoints subscribed to this event and open a PENDING
 * delivery for each. The sending is done later, outside any transaction, by
 * {@code WebhookDeliveryWorker} driving {@code WebhookDispatcher}.
 *
 * <p>Splitting it this way also gives retries somewhere to live. A delivery row that exists
 * before the first attempt means a crash between "event happened" and "customer told" is
 * recoverable, rather than lost.
 *
 * <h2>Why it does not throw</h2>
 *
 * The relay treats a throwing sink as a failed entry and retries the whole thing, which
 * would re-publish to every other sink as well and re-open the deliveries this sink had
 * already opened. There is no unique constraint on endpoint-plus-event to deduplicate
 * against, so a failure here is logged per endpoint and the entry is allowed to complete.
 * Losing one endpoint's delivery is the smaller fault compared with duplicating every
 * other sink's.
 */
@Component
public class WebhookEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventSink.class);

    private final WebhookDeliveryLog deliveryLog;

    public WebhookEventSink(WebhookDeliveryLog deliveryLog) {
        this.deliveryLog = deliveryLog;
    }

    @Override
    public void send(PlatformEvent event) {
        UUID organizationId = event.organizationId();
        if (organizationId == null || event.eventId() == null) {
            // A platform-wide event belongs to no tenant, and every webhook row is
            // tenant scoped. There is nobody to deliver it to.
            return;
        }
        TenantContextHolder.runAs(contextFor(organizationId), () -> {
            for (WebhookDeliveryLog.Target target : deliveryLog.activeTargets()) {
                if (!target.subscribesTo(event.eventType())) {
                    continue;
                }
                try {
                    deliveryLog.open(target.endpointId(), event.eventId(), event.eventType());
                } catch (RuntimeException e) {
                    log.error("Could not open a webhook delivery for endpoint {} and event {}",
                            target.endpointId(), event.eventId(), e);
                }
            }
        });
    }

    @Override
    public String name() {
        return "webhook";
    }

    private static TenantContext contextFor(UUID organizationId) {
        return TenantContext.of(organizationId, null, TenantContext.PrincipalType.SYSTEM);
    }
}
