package com.hatis.platform.analytics.application;

import com.hatis.platform.analytics.adapter.persistence.Alert;
import com.hatis.platform.analytics.adapter.persistence.AnalyticsRepositories;
import com.hatis.platform.analytics.adapter.persistence.MetricStore;
import com.hatis.platform.analytics.domain.AlertCondition;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.notification.application.NotificationService;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.feature.FeatureFlagService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a firing does, and what it does not.
 *
 * <p>Three of these cases are the ones that decide whether an alert is trustworthy. A sweep with
 * the flag off must not read a metric, because reading one would be work an operator asked the
 * platform not to do. A window with no samples must not fire, because "the collector stopped" is
 * a different incident from "the metric breached its threshold", and firing on the absence would
 * page somebody for the wrong one. And a firing must deliver exactly once per recipient per
 * channel: {@code WEBHOOK} is the published event, so notifying it again would arrive twice.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Alert work")
class AlertWorkTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID firstRecipient = UUID.randomUUID();
    private final UUID secondRecipient = UUID.randomUUID();

    @Mock
    private AnalyticsRepositories.AlertRepository alerts;
    @Mock
    private MetricStore metrics;
    @Mock
    private NotificationService notifications;
    @Mock
    private FeatureFlagService featureFlags;
    @Mock
    private EventPublisher events;
    @Mock
    private AuditRecorder audit;

    private SimpleMeterRegistry meterRegistry;
    private AlertWork work;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        work = new AlertWork(alerts, metrics, notifications, featureFlags, events, audit, meterRegistry);
    }

    @Test
    @DisplayName("a disabled kill switch evaluates nothing at all")
    void aDisabledFlagEvaluatesNothing() {
        when(featureFlags.isEnabled(AlertWork.FEATURE_FLAG, organizationId, null)).thenReturn(false);

        assertThat(work.evaluate(organizationId)).isZero();

        verifyNoInteractions(alerts, metrics, notifications, events, audit);
    }

    @Test
    @DisplayName("a window with no samples does not fire, because silence is not a breach")
    void anEmptyWindowDoesNotFire() {
        Alert alert = activeAlert();
        when(featureFlags.isEnabled(AlertWork.FEATURE_FLAG, organizationId, null)).thenReturn(true);
        when(alerts.findByOrganizationIdAndStatusIn(organizationId,
                List.of(Alert.Status.ACTIVE, Alert.Status.TRIGGERED))).thenReturn(List.of(alert));
        when(metrics.preferredAggregation(organizationId, alert.getMetricKey()))
                .thenReturn(MetricAggregation.SUM);
        when(metrics.valueInWindow(eq(organizationId), eq(alert.getMetricKey()),
                eq(MetricAggregation.SUM), any(Instant.class), any(Instant.class))).thenReturn(null);

        assertThat(work.evaluate(organizationId)).isZero();

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.ACTIVE);
        verify(alerts, never()).save(any(Alert.class));
        verifyNoInteractions(notifications, events, audit);
    }

    @Test
    @DisplayName("a metric with no samples at all never fires")
    void aMetricWithNoHistoryNeverFires() {
        Alert alert = activeAlert();
        when(featureFlags.isEnabled(AlertWork.FEATURE_FLAG, organizationId, null)).thenReturn(true);
        when(alerts.findByOrganizationIdAndStatusIn(organizationId,
                List.of(Alert.Status.ACTIVE, Alert.Status.TRIGGERED))).thenReturn(List.of(alert));
        when(metrics.preferredAggregation(organizationId, alert.getMetricKey())).thenReturn(null);

        assertThat(work.evaluate(organizationId)).isZero();

        verify(metrics, never()).valueInWindow(any(), any(), any(), any(), any());
        verify(alerts, never()).save(any(Alert.class));
    }

    @Test
    @DisplayName("an alert that fired inside its window waits, and is not even measured")
    void anAlertInsideItsWindowIsSkipped() {
        Alert alert = activeAlert();
        alert.trigger(Instant.now());
        when(featureFlags.isEnabled(AlertWork.FEATURE_FLAG, organizationId, null)).thenReturn(true);
        when(alerts.findByOrganizationIdAndStatusIn(organizationId,
                List.of(Alert.Status.ACTIVE, Alert.Status.TRIGGERED))).thenReturn(List.of(alert));

        assertThat(work.evaluate(organizationId)).isZero();

        verifyNoInteractions(metrics, notifications, events);
    }

    @Test
    @DisplayName("a breach fires once, publishes one event, and notifies each recipient on each channel")
    void aBreachFiresAndDelivers() {
        Alert alert = alertWithDelivery(List.of(Notification.Channel.IN_APP, Notification.Channel.EMAIL,
                Notification.Channel.WEBHOOK), List.of(firstRecipient, secondRecipient));
        when(featureFlags.isEnabled(AlertWork.FEATURE_FLAG, organizationId, null)).thenReturn(true);
        when(alerts.findByOrganizationIdAndStatusIn(organizationId,
                List.of(Alert.Status.ACTIVE, Alert.Status.TRIGGERED))).thenReturn(List.of(alert));
        when(metrics.preferredAggregation(organizationId, alert.getMetricKey()))
                .thenReturn(MetricAggregation.SUM);
        when(metrics.valueInWindow(eq(organizationId), eq(alert.getMetricKey()),
                eq(MetricAggregation.SUM), any(Instant.class), any(Instant.class)))
                .thenReturn(new BigDecimal("150"));

        assertThat(work.evaluate(organizationId)).isEqualTo(1);

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.TRIGGERED);
        assertThat(alert.getLastTriggeredAt()).isNotNull();
        verify(alerts).save(alert);

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().eventType()).isEqualTo(AlertWork.EVENT_TYPE);
        assertThat(published.getValue().organizationId()).isEqualTo(organizationId);
        assertThat(published.getValue().resourceType()).isEqualTo("alert");
        assertThat(published.getValue().data())
                .containsEntry("observed", "150")
                .containsEntry("threshold", "100")
                .containsEntry("windowMinutes", 60);

        ArgumentCaptor<NotificationService.NotifyCommand> commands =
                ArgumentCaptor.forClass(NotificationService.NotifyCommand.class);
        verify(notifications, org.mockito.Mockito.times(4)).notify(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(NotificationService.NotifyCommand::channel)
                .containsOnly(Notification.Channel.IN_APP, Notification.Channel.EMAIL);
        assertThat(commands.getAllValues())
                .extracting(NotificationService.NotifyCommand::userId)
                .containsOnly(firstRecipient, secondRecipient);
        assertThat(commands.getAllValues())
                .as("the published event is the webhook delivery, so a second copy would arrive twice")
                .noneMatch(command -> command.channel() == Notification.Channel.WEBHOOK);
        assertThat(commands.getAllValues())
                .extracting(NotificationService.NotifyCommand::templateKey)
                .containsOnly(AlertWork.EVENT_TYPE);

        ArgumentCaptor<AuditRecord> audited = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().action()).isEqualTo("analytics.alert.triggered");

        assertThat(meterRegistry.get("hatis.analytics.alerts.triggered").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a channel that cannot even write a notification does not undo the firing")
    void aNotificationFailureDoesNotUndoTheFiring() {
        Alert alert = alertWithDelivery(List.of(Notification.Channel.IN_APP), List.of(firstRecipient));
        when(featureFlags.isEnabled(AlertWork.FEATURE_FLAG, organizationId, null)).thenReturn(true);
        when(alerts.findByOrganizationIdAndStatusIn(organizationId,
                List.of(Alert.Status.ACTIVE, Alert.Status.TRIGGERED))).thenReturn(List.of(alert));
        when(metrics.preferredAggregation(organizationId, alert.getMetricKey()))
                .thenReturn(MetricAggregation.SUM);
        when(metrics.valueInWindow(eq(organizationId), eq(alert.getMetricKey()),
                eq(MetricAggregation.SUM), any(Instant.class), any(Instant.class)))
                .thenReturn(new BigDecimal("150"));
        when(notifications.notify(any(NotificationService.NotifyCommand.class)))
                .thenThrow(new IllegalStateException("the notification could not be written"));

        assertThat(work.evaluate(organizationId)).isEqualTo(1);

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.TRIGGERED);
        verify(events).publish(any(PlatformEvent.class));
        verify(audit).record(any(AuditRecord.class));
    }

    @Test
    @DisplayName("a condition that is not met leaves the alert alone")
    void aConditionThatIsNotMetDoesNotFire() {
        Alert alert = activeAlert();
        when(featureFlags.isEnabled(AlertWork.FEATURE_FLAG, organizationId, null)).thenReturn(true);
        when(alerts.findByOrganizationIdAndStatusIn(organizationId,
                List.of(Alert.Status.ACTIVE, Alert.Status.TRIGGERED))).thenReturn(List.of(alert));
        when(metrics.preferredAggregation(organizationId, alert.getMetricKey()))
                .thenReturn(MetricAggregation.SUM);
        when(metrics.valueInWindow(eq(organizationId), eq(alert.getMetricKey()),
                eq(MetricAggregation.SUM), any(Instant.class), any(Instant.class)))
                .thenReturn(new BigDecimal("100"));

        assertThat(work.evaluate(organizationId)).isZero();

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.ACTIVE);
        verify(alerts, never()).save(any(Alert.class));
        verifyNoInteractions(notifications, events, audit);
    }

    private Alert activeAlert() {
        return alertWithDelivery(List.of(Notification.Channel.IN_APP), List.of(firstRecipient));
    }

    private Alert alertWithDelivery(List<Notification.Channel> channels, List<UUID> recipients) {
        return new Alert(organizationId, "cms.pages.published", AlertCondition.GT,
                new BigDecimal("100"), 60, channels, recipients);
    }
}
