package com.hatis.platform.shared.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.config.PlatformProperties;
import com.hatis.platform.shared.tenant.TenantTransactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claims and publishes outbox work, one transaction per entry, in the tenant's name.
 *
 * <p>This exists as a separate bean from {@link OutboxRelay} for one reason, and the reason
 * is the whole point of the class. Spring applies {@code @TenantTransactional} — and
 * therefore the transaction that binds the row level security setting — through a proxy.
 * A call from one method of a bean to another method of the <em>same</em> bean never
 * crosses that proxy, so the annotation is silently inert: no transaction, no tenant
 * setting, and because {@code plat_outbox} fails closed, no rows. Injecting the bean into
 * itself behind {@code @Lazy} would work, but it depends on proxy semantics that nothing in
 * this repository tests — there is no {@code @SpringBootTest} here at all. A call between
 * two beans is a call through a proxy by construction, and is verifiable in a plain unit
 * test.
 *
 * <p>The caller is responsible for having bound a {@code TenantContext} before calling.
 * Every method here reads it on entry; the platform-wide variants additionally declare
 * {@code allowPlatformPrincipal} because for those rows "no tenant" is the correct binding
 * rather than a missing one.
 */
@Component
public class OutboxWork {

    private static final Logger log = LoggerFactory.getLogger(OutboxWork.class);

    private final OutboxRepository outbox;
    private final List<EventSink> sinks;
    private final ObjectMapper objectMapper;
    private final PlatformProperties properties;
    private final Map<String, Counter> published;
    private final Map<String, Counter> failed;

    /**
     * @param sinks every sink an event must reach, not one of them. An event legitimately
     *              goes to Kafka <em>and</em> out to customer webhooks, and taking a single
     *              sink would force one to be chosen at wiring time. Each sink is a bean;
     *              a deployment with neither still starts, and simply publishes nowhere.
     */
    public OutboxWork(OutboxRepository outbox,
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
    }

    /**
     * Ids of the entries one organization has queued, read inside that organization's
     * transaction so that row level security applies to the read.
     *
     * <p>The batch is bounded per organization rather than globally, so one busy tenant
     * cannot consume the whole batch and starve the rest.
     */
    @TenantTransactional(readOnly = true)
    public List<UUID> claimFor(UUID organizationId) {
        return outbox.findPendingIds(organizationId, Instant.now(), batchPage());
    }

    /**
     * Ids of the platform-wide entries queued, read with no tenant bound. Only rows with no
     * organization are visible from here, so this cannot reach into a tenant's queue.
     */
    @TenantTransactional(readOnly = true, allowPlatformPrincipal = true)
    public List<UUID> claimPlatformWide() {
        return outbox.findPendingPlatformIds(Instant.now(), batchPage());
    }

    /**
     * Publishes one entry in its own transaction and records the outcome on it.
     *
     * <p>The entry is re-read rather than passed in, so the transaction that publishes it is
     * the same one that read it and no detached entity crosses a transaction boundary. If
     * another replica published it first this quietly does nothing; consumers are
     * idempotent by {@code eventId}, which is what makes at-least-once safe here.
     */
    @TenantTransactional(allowPlatformPrincipal = true)
    public void publishOne(UUID entryId) {
        OutboxEntry entry = outbox.findById(entryId).orElse(null);
        if (entry == null || entry.getPublishedAt() != null) {
            return;
        }
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

    private PageRequest batchPage() {
        return PageRequest.of(0, properties.events().relayBatchSize());
    }
}
