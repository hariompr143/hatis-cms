package com.hatis.platform.integration.adapter.event;

import com.hatis.platform.integration.application.WebhookDeliveryLog;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The event sink's half of outbound delivery: turning events into delivery records.
 *
 * <p>The property under test is that this stage touches the database and nothing else. It
 * runs inside the outbox relay's transaction, so anything it did over the network would
 * hold that transaction open for as long as a customer's endpoint took to answer.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventSinkTest {

    private static final String EVENT_TYPE = "content.published";

    private final UUID organizationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @Mock
    private WebhookDeliveryLog deliveryLog;

    private WebhookEventSink sink;

    @BeforeEach
    void setUp() {
        // Built here rather than in a field initializer: @Mock fields are still null while
        // the test instance is being constructed, so the sink would have captured null.
        sink = new WebhookEventSink(deliveryLog);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("one delivery record is opened per subscribed endpoint")
    void opensADeliveryPerSubscribedEndpoint() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(deliveryLog.activeTargets()).thenReturn(List.of(
                target(first, List.of(EVENT_TYPE)),
                target(second, List.of())));

        sink.send(event());

        verify(deliveryLog).open(eq(first), eq(eventId), eq(EVENT_TYPE));
        verify(deliveryLog).open(eq(second), eq(eventId), eq(EVENT_TYPE));
    }

    @Test
    @DisplayName("endpoints subscribed to a different event are left alone")
    void skipsUnsubscribedEndpoints() {
        UUID other = UUID.randomUUID();
        when(deliveryLog.activeTargets())
                .thenReturn(List.of(target(other, List.of("asset.deleted"))));

        sink.send(event());

        verify(deliveryLog, never()).open(any(), any(), any());
    }

    @Test
    @DisplayName("a platform event with no tenant reaches no endpoint")
    void ignoresEventsWithNoTenant() {
        PlatformEvent event = PlatformEvent.of("platform.health", null)
                .resource("platform", UUID.randomUUID())
                .build();

        sink.send(event);

        // Every webhook row is tenant scoped, so there is nothing this could be stored
        // against, and no tenant context that would make reading the endpoints legal.
        verifyNoInteractions(deliveryLog);
    }

    @Test
    @DisplayName("one endpoint failing to open does not stop the others")
    void oneFailureDoesNotStopTheRest() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(deliveryLog.activeTargets()).thenReturn(List.of(
                target(broken, List.of(EVENT_TYPE)),
                target(healthy, List.of(EVENT_TYPE))));
        doThrow(new IllegalStateException("row locked"))
                .when(deliveryLog).open(eq(broken), any(), any());

        sink.send(event());

        // The sink does not throw: the relay would retry the whole entry, re-publishing to
        // every other sink and re-opening the deliveries that did succeed.
        verify(deliveryLog).open(eq(healthy), eq(eventId), eq(EVENT_TYPE));
    }

    @Test
    @DisplayName("the sink is named for the metric tag it produces")
    void reportsItsName() {
        assertThat(sink.name()).isEqualTo("webhook");
    }

    private WebhookDeliveryLog.Target target(UUID endpointId, List<String> events) {
        return new WebhookDeliveryLog.Target(endpointId, "https://hooks.acme.example/hatis",
                events, true, "ciphertext", "key-1");
    }

    private PlatformEvent event() {
        return PlatformEvent.of(EVENT_TYPE, organizationId)
                .resource("content_item", UUID.randomUUID())
                .data(Map.of("id", "c-1"))
                .build();
    }
}
