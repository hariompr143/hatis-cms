package com.hatis.platform.integration.application;

import com.hatis.platform.identity.application.TenantKeyService;
import com.hatis.platform.integration.domain.WebhookSigner;
import com.hatis.platform.integration.port.out.WebhookTransport;
import com.hatis.platform.shared.secret.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fan-out, signing and retry decisions.
 *
 * <p>The signature assertion is the one that matters most: it checks the header the
 * dispatcher emits verifies against the endpoint's secret using the same code a receiver
 * would use. A dispatcher that signs the wrong bytes produces deliveries every customer
 * rejects, and nothing else in the suite would notice.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    private static final String EVENT_TYPE = "content.published";
    private static final String PAYLOAD = "{\"id\":\"c-1\",\"type\":\"page\"}";
    private static final String SECRET = "hwhsec_endpoint_signing_secret";

    private final UUID organizationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @Mock
    private WebhookDeliveryLog deliveryLog;
    @Mock
    private WebhookTransport transport;
    @Mock
    private TenantKeyService tenantKeys;
    @Mock
    private EncryptionService encryption;

    private WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new WebhookDispatcher(deliveryLog, transport, tenantKeys, encryption);
    }

    @Test
    @DisplayName("only endpoints subscribed to the event are delivered to")
    void deliversOnlyToSubscribedEndpoints() {
        UUID subscribed = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        stubEvent();
        stubKeyAndSecret();
        when(deliveryLog.activeTargets()).thenReturn(List.of(
                target(subscribed, List.of(EVENT_TYPE)),
                target(otherEvent, List.of("asset.deleted"))));
        when(deliveryLog.open(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.delivered(200));

        List<UUID> opened = dispatcher.dispatch(organizationId, eventId);

        assertThat(opened).hasSize(1);
        verify(deliveryLog).open(eq(subscribed), eq(eventId), eq(EVENT_TYPE));
        verify(deliveryLog, never()).open(eq(otherEvent), any(), any());
    }

    @Test
    @DisplayName("an endpoint with no subscription receives every event")
    void anEmptySubscriptionReceivesEverything() {
        UUID wildcard = UUID.randomUUID();
        stubEvent();
        stubKeyAndSecret();
        when(deliveryLog.activeTargets()).thenReturn(List.of(target(wildcard, List.of())));
        when(deliveryLog.open(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.delivered(204));

        dispatcher.dispatch(organizationId, eventId);

        verify(deliveryLog).open(eq(wildcard), eq(eventId), eq(EVENT_TYPE));
    }

    @Test
    @DisplayName("the signature it sends verifies against the endpoint secret")
    void theSignatureVerifies() {
        UUID endpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        stubEvent();
        stubKeyAndSecret();
        when(deliveryLog.activeTargets()).thenReturn(List.of(target(endpointId, List.of(EVENT_TYPE))));
        when(deliveryLog.open(any(), any(), any())).thenReturn(deliveryId);
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.delivered(200));

        dispatcher.dispatch(organizationId, eventId);

        WebhookTransport.Request request = capturedRequest();
        String signature = request.headers().get(WebhookSigner.SIGNATURE_HEADER);
        assertThat(signature).isNotNull();

        // Exactly what a receiving customer does: take the timestamp from the header and
        // verify the signature over timestamp.payload with the shared secret.
        Instant signedAt = Instant.ofEpochSecond(WebhookSigner.timestampOf(signature));
        assertThat(WebhookSigner.verify(signature, PAYLOAD, SECRET, signedAt)).isTrue();

        assertThat(request.headers()).containsEntry(WebhookSigner.EVENT_HEADER, EVENT_TYPE);
        assertThat(request.headers())
                .containsEntry(WebhookSigner.DELIVERY_HEADER, deliveryId.toString());
        assertThat(request.payload()).isEqualTo(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(request.url()).isEqualTo("https://hooks.acme.example/hatis");
    }

    @Test
    @DisplayName("a delivered attempt is recorded with the status the endpoint returned")
    void recordsADeliveredAttempt() {
        UUID deliveryId = UUID.randomUUID();
        stubSingleTarget(deliveryId);
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.delivered(202));

        dispatcher.dispatch(organizationId, eventId);

        verify(deliveryLog).recordDelivered(eq(deliveryId), eq(202), any(Instant.class));
        verify(deliveryLog, never()).recordFailure(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a rejected delivery is scheduled for retry, not failed outright")
    void schedulesARetryAfterRejection() {
        UUID deliveryId = UUID.randomUUID();
        stubSingleTarget(deliveryId);
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.rejected(503));

        Instant before = Instant.now();
        dispatcher.dispatch(organizationId, eventId);

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryLog).recordFailure(eq(deliveryId), any(), next.capture(), eq(false));
        // First retry is one base backoff out.
        assertThat(next.getValue()).isAfter(before);
        assertThat(next.getValue()).isBefore(before.plusSeconds(60));
    }

    @Test
    @DisplayName("the backoff grows with the attempt count and stays under the ceiling")
    void theBackoffGrowsAndIsCapped() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryLog.dueBefore(any(), anyInt())).thenReturn(List.of(
                new WebhookDeliveryLog.Due(deliveryId, UUID.randomUUID(), eventId, 4)));
        when(deliveryLog.target(any())).thenReturn(
                Optional.of(target(UUID.randomUUID(), List.of(EVENT_TYPE))));
        stubEvent();
        stubKeyAndSecret();
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.unreachable("timeout"));

        Instant before = Instant.now();
        dispatcher.retryDue(organizationId, before, 10);

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryLog).recordFailure(eq(deliveryId), any(), next.capture(), eq(false));
        // 15s * 2^4 = 240s.
        assertThat(next.getValue()).isBetween(before.plusSeconds(235), before.plusSeconds(250));
    }

    @Test
    @DisplayName("a policy refusal is skipped rather than retried")
    void aPolicyRefusalIsSkipped() {
        UUID deliveryId = UUID.randomUUID();
        stubSingleTarget(deliveryId);
        when(transport.deliver(any())).thenReturn(
                WebhookTransport.Outcome.refused("resolves to a private address"));

        dispatcher.dispatch(organizationId, eventId);

        // Retrying an address the platform refuses to contact would fail identically
        // forever and hide the real reason from the customer.
        verify(deliveryLog).recordSkipped(deliveryId, "resolves to a private address");
        verify(deliveryLog, never()).recordFailure(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("the delivery fails once the attempt budget is spent")
    void failsTheDeliveryWhenTheBudgetIsSpent() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryLog.dueBefore(any(), anyInt())).thenReturn(List.of(
                new WebhookDeliveryLog.Due(deliveryId, UUID.randomUUID(), eventId,
                        WebhookDispatcher.MAX_ATTEMPTS - 1)));
        when(deliveryLog.target(any())).thenReturn(
                Optional.of(target(UUID.randomUUID(), List.of(EVENT_TYPE))));
        stubEvent();
        stubKeyAndSecret();
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.unreachable("timeout"));

        dispatcher.retryDue(organizationId, Instant.now(), 10);

        verify(deliveryLog).recordFailure(eq(deliveryId), any(), any(), eq(true));
    }

    @Test
    @DisplayName("an undecryptable secret skips the delivery instead of aborting the fan-out")
    void anUndecryptableSecretSkipsTheDelivery() {
        UUID deliveryId = UUID.randomUUID();
        stubEvent();
        when(tenantKeys.keyForOrganization(organizationId))
                .thenReturn(new TenantKeyService.TenantKey("wdek", "key-1"));
        when(encryption.decryptWith(any(), any(), any()))
                .thenThrow(new IllegalStateException("key rotated"));
        when(deliveryLog.activeTargets())
                .thenReturn(List.of(target(UUID.randomUUID(), List.of(EVENT_TYPE))));
        when(deliveryLog.open(any(), any(), any())).thenReturn(deliveryId);

        dispatcher.dispatch(organizationId, eventId);

        // Nothing was sent, and the reason is recorded rather than thrown: one endpoint
        // with a stale key must not stop the others from being delivered to.
        verify(transport, never()).deliver(any());
        verify(deliveryLog).recordSkipped(eq(deliveryId), any());
    }

    @Test
    @DisplayName("the retry sweep skips an endpoint that no longer accepts deliveries")
    void theSweepSkipsAnInactiveEndpoint() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        when(deliveryLog.dueBefore(any(), anyInt())).thenReturn(List.of(
                new WebhookDeliveryLog.Due(deliveryId, endpointId, eventId, 1)));
        when(deliveryLog.target(endpointId)).thenReturn(Optional.of(
                new WebhookDeliveryLog.Target(endpointId, "https://hooks.acme.example/hatis",
                        List.of(EVENT_TYPE), false, "ct", "key-1")));
        when(tenantKeys.keyForOrganization(organizationId))
                .thenReturn(new TenantKeyService.TenantKey("wdek", "key-1"));

        int processed = dispatcher.retryDue(organizationId, Instant.now(), 10);

        assertThat(processed).isEqualTo(1);
        verify(deliveryLog).recordSkipped(eq(deliveryId), any());
        verify(transport, never()).deliver(any());
    }

    @Test
    @DisplayName("a missing outbox entry produces no deliveries at all")
    void aMissingEventDeliversNothing() {
        when(deliveryLog.event(eventId)).thenReturn(null);

        assertThat(dispatcher.dispatch(organizationId, eventId)).isEmpty();
        verify(transport, never()).deliver(any());
    }

    @Test
    @DisplayName("first attempts come from the unattempted queue, not the retry queue")
    void firstAttemptsUseTheUnattemptedQueue() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryLog.unattempted(anyInt())).thenReturn(List.of(
                new WebhookDeliveryLog.Due(deliveryId, UUID.randomUUID(), eventId, 0)));
        when(deliveryLog.target(any())).thenReturn(
                Optional.of(target(UUID.randomUUID(), List.of(EVENT_TYPE))));
        stubEvent();
        stubKeyAndSecret();
        when(transport.deliver(any())).thenReturn(WebhookTransport.Outcome.delivered(200));

        int processed = dispatcher.deliverPending(organizationId, 10);

        assertThat(processed).isEqualTo(1);
        verify(deliveryLog).recordDelivered(eq(deliveryId), eq(200), any(Instant.class));
        // A freshly opened delivery has no next_attempt_at, so the retry query would never
        // select it and the event would sit undelivered forever.
        verify(deliveryLog, never()).dueBefore(any(), anyInt());
    }

    @Test
    @DisplayName("a first attempt for a paused endpoint is skipped rather than left queued")
    void firstAttemptForAPausedEndpointIsSkipped() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        when(deliveryLog.unattempted(anyInt())).thenReturn(List.of(
                new WebhookDeliveryLog.Due(deliveryId, endpointId, eventId, 0)));
        when(deliveryLog.target(endpointId)).thenReturn(Optional.of(
                new WebhookDeliveryLog.Target(endpointId, "https://hooks.acme.example/hatis",
                        List.of(EVENT_TYPE), false, "ct", "key-1")));
        when(tenantKeys.keyForOrganization(organizationId))
                .thenReturn(new TenantKeyService.TenantKey("wdek", "key-1"));

        dispatcher.deliverPending(organizationId, 10);

        verify(deliveryLog).recordSkipped(eq(deliveryId), any());
        verify(transport, never()).deliver(any());
    }

    private WebhookTransport.Request capturedRequest() {
        ArgumentCaptor<WebhookTransport.Request> captor =
                ArgumentCaptor.forClass(WebhookTransport.Request.class);
        verify(transport).deliver(captor.capture());
        return captor.getValue();
    }

    private void stubEvent() {
        when(deliveryLog.event(eventId))
                .thenReturn(new WebhookDeliveryLog.Event(eventId, EVENT_TYPE, PAYLOAD));
    }

    private void stubKeyAndSecret() {
        when(tenantKeys.keyForOrganization(organizationId))
                .thenReturn(new TenantKeyService.TenantKey("wdek", "key-1"));
        when(encryption.decryptWith("wdek", "key-1", "ct")).thenReturn(SECRET);
    }

    private void stubSingleTarget(UUID deliveryId) {
        stubEvent();
        stubKeyAndSecret();
        when(deliveryLog.activeTargets())
                .thenReturn(List.of(target(UUID.randomUUID(), List.of(EVENT_TYPE))));
        when(deliveryLog.open(any(), any(), any())).thenReturn(deliveryId);
    }

    private WebhookDeliveryLog.Target target(UUID endpointId, List<String> events) {
        return new WebhookDeliveryLog.Target(endpointId, "https://hooks.acme.example/hatis",
                events, true, "ct", "key-1");
    }
}
