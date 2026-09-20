package com.hatis.platform.shared.event;

/**
 * Outbound port for publishing platform events.
 *
 * <p>The production implementation writes to a transactional outbox in the same
 * database transaction as the business change, so an event can never be
 * published for a change that was rolled back, and a committed change can never
 * lose its event. See {@code docs/architecture/13-event-architecture.md}.
 */
public interface EventPublisher {

    void publish(PlatformEvent event);
}
