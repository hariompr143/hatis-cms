package com.hatis.platform.shared.event;

import com.hatis.platform.shared.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes drained events to the platform Kafka topic.
 *
 * <p>Active when {@code hatis.events.transport=kafka}. The producer is configured
 * with {@code acks=all} and idempotence so a broker acknowledgement means the
 * event is durable, and the outbox row is only marked published after the send
 * succeeds — the combination gives at-least-once delivery with no silent loss.
 */
@Component
@ConditionalOnProperty(name = "hatis.events.transport", havingValue = "kafka")
public class KafkaEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSink.class);

    private final KafkaTemplate<String, PlatformEvent> template;
    private final PlatformProperties properties;

    public KafkaEventSink(KafkaTemplate<String, PlatformEvent> template, PlatformProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public void send(PlatformEvent event) {
        // Keying by organization keeps a tenant's events ordered on one partition.
        String key = event.organizationId() == null ? "platform" : event.organizationId().toString();
        try {
            template.send(properties.events().getTopic(), key, event).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.eventType(), e);
        } catch (Exception e) {
            log.warn("Kafka send failed for {}: {}", event.eventType(), e.getMessage());
            throw new IllegalStateException("Unable to publish event to Kafka", e);
        }
    }

    @Override
    public String name() {
        return "kafka";
    }
}
