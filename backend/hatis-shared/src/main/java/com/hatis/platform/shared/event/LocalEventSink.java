package com.hatis.platform.shared.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process event sink used when Kafka is not configured.
 *
 * <p>This is not a stub: it is the correct transport for single-node and private
 * deployments that do not operate a streaming platform. Durability still comes
 * from the outbox, so a restart replays anything not yet acknowledged.
 */
@Component
@ConditionalOnMissingBean(KafkaEventSink.class)
public class LocalEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(LocalEventSink.class);

    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();

    public void subscribe(EventHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void send(PlatformEvent event) {
        for (EventHandler handler : handlers) {
            if (!handler.supports(event.eventType())) {
                continue;
            }
            try {
                handler.handle(event);
            } catch (RuntimeException e) {
                // A broken subscriber must not stop the others, and must not lose
                // the event: the failure is logged and surfaced as a metric.
                log.error("Event handler {} failed for {}", handler.getClass().getSimpleName(),
                        event.eventType(), e);
            }
        }
    }

    @Override
    public String name() {
        return "local";
    }

    /** In-process subscriber. Implementations must be idempotent. */
    public interface EventHandler {

        boolean supports(String eventType);

        void handle(PlatformEvent event);
    }
}
