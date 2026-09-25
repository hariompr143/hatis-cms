package com.hatis.platform.notification.adapter.channel;

import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.notification.port.out.ChannelSender;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook delivery: publishes the notification as a platform event.
 *
 * <p>The send itself is trivial and deliberately so. Everything a delivery needs — which
 * endpoints subscribe to {@code notification.created}, HMAC signing, the SSRF-guarded
 * transport, retries with backoff, and a delivery record per attempt — already exists in the
 * outbound webhook path, and reusing it means a webhook notification inherits those
 * properties instead of acquiring a second, weaker copy of them.
 *
 * <p>What that reuse changes about failure semantics is worth stating: this sender reports
 * success once the event is in the outbox, because from here the delivery is the outbox
 * relay's responsibility. A customer whose endpoint is down sees that in
 * {@code int_webhook_deliveries} — with the attempt count and next attempt time — rather than
 * in the notification row, which was never the right place to record an HTTP response.
 */
@Component
public class WebhookChannelSender implements ChannelSender {

    /** The event type a tenant's endpoints subscribe to for notifications. */
    public static final String EVENT_TYPE = "notification.created";

    private final EventPublisher events;

    public WebhookChannelSender(EventPublisher events) {
        this.events = events;
    }

    @Override
    public Notification.Channel channel() {
        return Notification.Channel.WEBHOOK;
    }

    @Override
    public void send(Notification notification) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationId", notification.getId().toString());
        data.put("recipientId", notification.getUserId().toString());
        data.put("templateKey", notification.getTemplateKey());
        data.put("subject", notification.getSubject());
        data.put("body", notification.getBody());

        events.publish(PlatformEvent.of(EVENT_TYPE, notification.getOrganizationId())
                .resource("notification", notification.getId())
                .data(data)
                .build());
    }
}
