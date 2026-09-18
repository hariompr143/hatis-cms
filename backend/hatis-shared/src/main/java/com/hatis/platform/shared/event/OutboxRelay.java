package com.hatis.platform.shared.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.config.PlatformProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drains the transactional outbox into the configured {@link EventSink}.
 *
 * <p>Runs on the worker role only. Each entry is published at least once; the
 * {@code eventId} in the envelope is what makes downstream consumers idempotent.
 * A failed batch applies exponential backoff per entry rather than blocking the
 * whole queue, so one poison message cannot stall delivery of everything else.
 */
@Component
@ConditionalOnProperty(name = "hatis.role", havingValue = "worker", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final List<EventSink> sinks;
    private final ObjectMapper objectMapper;
    private final PlatformProperties properties;
    private final Map<String, Counter> published;
    private final Map<String, Counter> failed;
    private final Timer batchDuration;

    /**
     * @param sinks every sink an event must reach, not one of them. An event legitimately
     *              goes to Kafka <em>and</em> out to customer webhooks, and taking a single
     *              sink would force one to be chosen at wiring time. Each sink is a bean;
     *              a deployment with neither still starts, and simply publishes nowhere.
     */
    public OutboxRelay(OutboxRepository outbox,
                       List<EventSink> sinks,
                       ObjectMapper objectMapper,
                       PlatformProperties properties,
                       MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.sinks = List.copyOf(sinks);
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.published = new LinkedHashMap<>();
        this.failed = new LinkedHashMap<>();
        for (EventSink sink : this.sinks) {
            published.put(sink.name(), Counter.builder("hatis.events.published")
                    .tag("sink", sink.name()).register(meterRegistry));
            failed.put(sink.name(), Counter.builder("hatis.events.publish_failures")
                    .tag("sink", sink.name()).register(meterRegistry));
        }
        this.batchDuration = Timer.builder("hatis.events.relay.duration").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${hatis.events.relay-interval:2s}")
    public void drain() {
        batchDuration.record(this::drainBatch);
    }

    void drainBatch() {
        List<OutboxEntry> pending = outbox.findPending(
                Instant.now(), PageRequest.of(0, properties.events().relayBatchSize()));
        if (pending.isEmpty()) {
            return;
        }
        for (OutboxEntry entry : pending) {
            publishOne(entry);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOne(OutboxEntry entry) {
        String previousCorrelation = MDC.get("correlationId");
        if (entry.getCorrelationId() != null) {
            MDC.put("correlationId", entry.getCorrelationId());
        }
        try {
            PlatformEvent event = objectMapper.readValue(entry.getPayload(), PlatformEvent.class);
            // A failure in any sink fails the entry, so it is retried as a whole rather
            // than marked published with some sinks silently never having seen it.
            for (EventSink sink : sinks) {
                try {
                    sink.send(event);
                    published.get(sink.name()).increment();
                } catch (RuntimeException e) {
                    failed.get(sink.name()).increment();
                    throw e;
                }
            }
            entry.markPublished();
            outbox.save(entry);
        } catch (Exception e) {
            // No counter here: a failure attributable to a sink was already counted on the
            // way out of the loop above. What reaches this catch is either a payload that
            // would not deserialize or a failure to save the entry, neither of which
            // belongs to any sink.
            entry.markFailedAttempt();
            outbox.save(entry);
            log.warn("Failed to publish event {} (attempt {}): {}",
                    entry.getEventType(), entry.getAttempts(), e.getMessage());
        } finally {
            if (previousCorrelation == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previousCorrelation);
            }
        }
    }
}
