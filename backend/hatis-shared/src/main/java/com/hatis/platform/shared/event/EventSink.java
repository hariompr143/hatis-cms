package com.hatis.platform.shared.event;

/**
 * Where drained outbox entries are handed to.
 *
 * <p>Two implementations ship in Phase 1:
 * <ul>
 *   <li>{@code KafkaEventSink} — publishes to the platform event topic when Kafka
 *       is configured.</li>
 *   <li>{@code LocalEventSink} — dispatches to in-process subscribers, used for
 *       single-node and private deployments that do not run Kafka.</li>
 * </ul>
 * Both are downstream of the same durable outbox, so switching transports never
 * loses an event.
 */
public interface EventSink {

    void send(PlatformEvent event);

    String name();
}
