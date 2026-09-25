package com.hatis.platform.analytics.application;

import com.hatis.platform.analytics.adapter.persistence.Alert;
import com.hatis.platform.analytics.adapter.persistence.AnalyticsRepositories;
import com.hatis.platform.analytics.adapter.persistence.MetricStore;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.notification.application.NotificationService;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.feature.FeatureFlagService;
import com.hatis.platform.shared.tenant.TenantTransactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluates one tenant's alerts, inside that tenant's transaction.
 *
 * <p>A separate bean from {@link AlertScanJob} for the reason {@code OutboxWork} is separate
 * from {@code OutboxRelay}, and it is worth repeating rather than cross-referencing: Spring
 * applies {@code @TenantTransactional} through a proxy, so a bean calling its own transactional
 * method runs with no transaction and no tenant binding at all. Since {@code anl_alerts},
 * {@code anl_metrics} and {@code plat_outbox} all carry forced row level security, the failure
 * mode is not an exception — it is an empty result that looks like "nothing to do".
 *
 * <p>The caller must have bound a {@code TenantContext} for the organization before calling.
 *
 * <h2>What a firing does</h2>
 *
 * <ol>
 *   <li>marks the alert {@code TRIGGERED} with the time, which both records the firing and
 *       starts the window during which it will not fire again;</li>
 *   <li>writes an audit record, because an alert is a decision the platform made about a
 *       customer's data;</li>
 *   <li>publishes {@code analytics.alert.triggered}, which is what reaches subscribed webhook
 *       endpoints through the existing outbound delivery path;</li>
 *   <li>raises one notification per configured recipient on each configured channel other than
 *       {@code WEBHOOK}, since that one is the event above.</li>
 * </ol>
 *
 * <p>The feature flag {@code analytics.alerts} is checked per organization, which is what makes
 * it a kill switch an operator can pull for one tenant rather than a deployment-wide setting.
 */
@Component
public class AlertWork {

    private static final Logger log = LoggerFactory.getLogger(AlertWork.class);

    public static final String FEATURE_FLAG = "analytics.alerts";
    public static final String EVENT_TYPE = "analytics.alert.triggered";

    private static final List<Alert.Status> LIVE_STATUSES =
            List.of(Alert.Status.ACTIVE, Alert.Status.TRIGGERED);

    private final AnalyticsRepositories.AlertRepository alerts;
    private final MetricStore metrics;
    private final NotificationService notifications;
    private final FeatureFlagService featureFlags;
    private final EventPublisher events;
    private final AuditRecorder audit;
    private final Counter triggered;

    public AlertWork(AnalyticsRepositories.AlertRepository alerts,
                     MetricStore metrics,
                     NotificationService notifications,
                     FeatureFlagService featureFlags,
                     EventPublisher events,
                     AuditRecorder audit,
                     MeterRegistry meterRegistry) {
        this.alerts = alerts;
        this.metrics = metrics;
        this.notifications = notifications;
        this.featureFlags = featureFlags;
        this.events = events;
        this.audit = audit;
        this.triggered = Counter.builder("hatis.analytics.alerts.triggered").register(meterRegistry);
    }

    /**
     * Evaluates every live alert of one organization.
     *
     * @return how many fired
     */
    @TenantTransactional
    public int evaluate(UUID organizationId) {
        if (!featureFlags.isEnabled(FEATURE_FLAG, organizationId, null)) {
            // Not an error and not logged per sweep: a disabled feature is a decision.
            return 0;
        }
        Instant now = Instant.now();
        int fired = 0;
        for (Alert alert : alerts.findByOrganizationIdAndStatusIn(organizationId, LIVE_STATUSES)) {
            if (!alert.isDue(now)) {
                continue;
            }
            BigDecimal observed = observe(organizationId, alert, now);
            if (observed == null || !alert.getCondition().isMet(observed, alert.getThreshold())) {
                continue;
            }
            fire(alert, observed, now);
            alerts.save(alert);
            fired++;
        }
        return fired;
    }

    /**
     * The value the alert compares against.
     *
     * <p>{@code null} when the window holds no samples — which is how a silent collector stays
     * silent instead of firing an alert that says "0 is less than 5". A metric that stopped
     * reporting is a different incident from the one the alert describes, and it belongs to
     * the collector's own monitoring.
     */
    private BigDecimal observe(UUID organizationId, Alert alert, Instant now) {
        MetricAggregation aggregation = metrics.preferredAggregation(organizationId, alert.getMetricKey());
        if (aggregation == null) {
            return null;
        }
        Instant from = now.minus(Duration.ofMinutes(alert.getWindowMinutes()));
        return metrics.valueInWindow(organizationId, alert.getMetricKey(), aggregation, from, now);
    }

    private void fire(Alert alert, BigDecimal observed, Instant now) {
        alert.trigger(now);
        triggered.increment();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alertId", alert.getId().toString());
        data.put("metricKey", alert.getMetricKey());
        data.put("condition", alert.getCondition().name());
        data.put("threshold", alert.getThreshold().toPlainString());
        data.put("observed", observed.toPlainString());
        data.put("windowMinutes", alert.getWindowMinutes());
        data.put("channels", alert.channels().stream().map(Enum::name).toList());

        events.publish(PlatformEvent.of(EVENT_TYPE, alert.getOrganizationId())
                .resource("alert", alert.getId())
                .data(data)
                .build());

        audit.record(AuditRecord.builder("analytics.alert.triggered")
                .resource("alert", alert.getId())
                .metadata(Map.of("metricKey", alert.getMetricKey(),
                        "observed", observed.toPlainString(),
                        "threshold", alert.getThreshold().toPlainString()))
                .build());

        for (Notification.Channel channel : alert.channels()) {
            if (channel == Notification.Channel.WEBHOOK) {
                // The published event is the webhook delivery; a second copy would arrive twice.
                continue;
            }
            for (UUID recipient : alert.recipients()) {
                try {
                    notifications.notify(new NotificationService.NotifyCommand(recipient, channel,
                            "analytics.alert.triggered", subjectFor(alert), bodyFor(alert, observed)));
                } catch (RuntimeException e) {
                    // NotificationService records delivery failures on the row, so this is the
                    // narrower case: it could not even write one. The alert still fired.
                    log.error("Could not raise a {} notification for alert {}",
                            channel, alert.getId(), e);
                }
            }
        }
    }

    private static String subjectFor(Alert alert) {
        return "Alert: " + alert.getMetricKey() + " is " + alert.getCondition().description()
                + " " + alert.getThreshold().toPlainString();
    }

    private static String bodyFor(Alert alert, BigDecimal observed) {
        return "Metric '" + alert.getMetricKey() + "' was " + observed.toPlainString()
                + " over the last " + alert.getWindowMinutes() + " minutes, which is "
                + alert.getCondition().description() + " the threshold of "
                + alert.getThreshold().toPlainString() + ".";
    }
}
