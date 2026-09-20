package com.hatis.platform.shared.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events to the transactional outbox inside the caller's transaction.
 *
 * <p>{@code MANDATORY} propagation is deliberate: an event published outside a
 * transaction would be a programming error, because it could not be guaranteed to
 * match a committed state change. Failing loudly here is cheaper than a silent
 * inconsistency in production.
 */
@Component
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(PlatformEvent event) {
        try {
            outbox.save(new OutboxEntry(event, objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new PlatformExceptions.OperationFailed(
                    "Unable to serialise event " + event.eventType() + " for the outbox");
        }
    }
}
