package com.hatis.platform.shared.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.config.PlatformProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to a single outbox entry once it is claimed.
 *
 * <p>The interesting cases are the failures. A relay that only works when every sink
 * answers is not a relay, so the tests here assert what a throwing sink, an unreadable
 * payload and an entry that vanished between the claim and the publish each leave behind —
 * and above all that a failed entry stays queued rather than being marked done.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Outbox entry publishing")
class OutboxWorkTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    @Mock
    private OutboxRepository outbox;

    @Mock
    private EventSink sink;

    private SimpleMeterRegistry meters;
    private OutboxWork work;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        // Registered under the name the sink reports: the work keeps one counter per sink
        // and looks it up by that name.
        when(sink.name()).thenReturn("test");
        work = new OutboxWork(outbox, List.of(sink), objectMapper(), new PlatformProperties(),
                meters);
    }

    @Test
    @DisplayName("a published entry is marked published and counted against the sink")
    void aSuccessfulPublishMarksTheEntryPublished() throws Exception {
        givenEntry(entryFor("cms.content.published"));

        work.publishOne(ENTRY);

        assertThat(saved().getPublishedAt()).isNotNull();
        assertThat(meters.get("hatis.events.published").tag("sink", "test").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the payload handed to the sink is the event that was stored")
    void theSinkReceivesTheStoredEvent() throws Exception {
        givenEntry(entryFor("cms.content.published"));

        work.publishOne(ENTRY);

        ArgumentCaptor<PlatformEvent> sent = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(sink).send(sent.capture());
        assertThat(sent.getValue().eventType()).isEqualTo("cms.content.published");
        assertThat(sent.getValue().organizationId()).isEqualTo(ORG);
        assertThat(sent.getValue().correlationId()).isEqualTo("corr-1");
    }

    @Test
    @DisplayName("a sink that throws leaves the entry queued and backed off, and does not throw")
    void aSinkFailureBacksTheEntryOff() throws Exception {
        givenEntry(entryFor("cms.content.published"));
        doThrow(new IllegalStateException("endpoint unreachable")).when(sink).send(any());

        assertThatCode(() -> work.publishOne(ENTRY)).doesNotThrowAnyException();

        OutboxEntry saved = saved();
        assertThat(saved.getPublishedAt())
                .as("marking it published would lose the event")
                .isNull();
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getNextAttemptAt())
                .as("the backoff is what stops a dead broker being hammered every sweep")
                .isNotNull();
        assertThat(meters.get("hatis.events.publish_failures").tag("sink", "test").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a payload that will not deserialize fails the entry, not the relay")
    void anUndeserialisablePayloadFailsTheEntry() {
        givenEntry(new OutboxEntry(PlatformEvent.of("cms.content.published", ORG).build(),
                "not json at all"));

        assertThatCode(() -> work.publishOne(ENTRY)).doesNotThrowAnyException();

        assertThat(saved().getPublishedAt()).isNull();
        assertThat(saved().getAttempts()).isEqualTo(1);
        // Not verifyNoInteractions: the sink was asked its name while wiring up counters,
        // which is an interaction, just not a send.
        verify(sink, never()).send(any());
    }

    @Test
    @DisplayName("an entry another replica already published is left alone")
    void anAlreadyPublishedEntryIsLeftAlone() throws Exception {
        OutboxEntry entry = entryFor("cms.content.published");
        entry.markPublished();
        givenEntry(entry);

        work.publishOne(ENTRY);

        verify(sink, never()).send(any());
        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("an entry that has disappeared is not an error")
    void aMissingEntryIsNotAnError() {
        when(outbox.findById(ENTRY)).thenReturn(Optional.empty());

        assertThatCode(() -> work.publishOne(ENTRY)).doesNotThrowAnyException();

        verify(sink, never()).send(any());
        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("the batch is bounded per tenant, so one busy tenant cannot starve the rest")
    void theBatchIsBoundedPerTenant() {
        when(outbox.findPendingIds(eq(ORG), any(), any())).thenReturn(List.of());

        work.claimFor(ORG);

        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        verify(outbox).findPendingIds(eq(ORG), any(), page.capture());
        assertThat(page.getValue().getPageSize())
                .as("per tenant, not globally: a global bound let one busy organization "
                        + "consume the entire batch")
                .isEqualTo(200);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("the platform-wide claim asks only for rows with no organization")
    void thePlatformClaimAsksForOwnerlessRowsOnly() {
        when(outbox.findPendingPlatformIds(any(), any())).thenReturn(List.of());

        work.claimPlatformWide();

        verify(outbox).findPendingPlatformIds(any(), any());
        verify(outbox, never()).findPendingIds(any(), any(), any());
    }

    private void givenEntry(OutboxEntry entry) {
        when(outbox.findById(ENTRY)).thenReturn(Optional.of(entry));
    }

    private OutboxEntry saved() {
        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outbox).save(captor.capture());
        return captor.getValue();
    }

    private OutboxEntry entryFor(String eventType) throws Exception {
        PlatformEvent event = PlatformEvent.of(eventType, ORG)
                .resource("content_item", UUID.randomUUID())
                .correlationId("corr-1")
                .data(Map.of("itemId", "abc"))
                .build();
        return new OutboxEntry(event, objectMapper().writeValueAsString(event));
    }

    /** Mirrors the mapper Boot auto-configures, so Instant and records round-trip. */
    private static ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }
}
