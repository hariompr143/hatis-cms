package com.hatis.platform.analytics.adapter.persistence;

import com.hatis.platform.analytics.domain.AlertCondition;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A rule watching one metric.
 *
 * <p>Maps {@code anl_alerts}. It sits in the persistence adapter rather than in
 * {@code domain} for the reason {@code WebhookEndpoint} does: {@code notify_channels} is a
 * PostgreSQL {@code text[]} and {@code notify_user_ids} is a {@code uuid[]}, and mapping a real
 * SQL array requires Hibernate annotations the architecture rules keep out of {@code ..domain..}.
 * The decision logic that matters — whether a condition is met — lives in
 * {@link AlertCondition}, which is framework-free.
 *
 * <h2>Re-arming</h2>
 *
 * An alert that fires stays {@code TRIGGERED}: clearing it would need somebody to decide that
 * the condition is over, and no one is watching. Instead {@link #isDue(Instant)} refuses a
 * second firing inside one window, so a metric that stays above its threshold alerts once per
 * window rather than once per sweep. That is a product decision, and it is written here so
 * that "why did this alert only fire once" has an answer in the code.
 */
@Entity
@Table(name = "anl_alerts")
public class Alert extends TenantScopedEntity {

    public enum Status {
        ACTIVE,
        PAUSED,
        TRIGGERED
    }

    @Column(name = "metric_key", nullable = false, length = 120, updatable = false)
    private String metricKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 10)
    private AlertCondition condition;

    @Column(name = "threshold", nullable = false, precision = 24, scale = 6)
    private BigDecimal threshold;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Array(length = 8)
    @Column(name = "notify_channels", nullable = false, columnDefinition = "text[]")
    private String[] notifyChannels = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Array(length = 64)
    @Column(name = "notify_user_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] notifyUserIds = new UUID[0];

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    protected Alert() {
        super();
    }

    public Alert(UUID organizationId, String metricKey, AlertCondition condition, BigDecimal threshold,
                 int windowMinutes, List<Notification.Channel> channels, List<UUID> recipients) {
        super(organizationId);
        if (metricKey == null || metricKey.isBlank() || metricKey.length() > 120) {
            throw new PlatformExceptions.Validation(
                    "An alert must name the metric it watches", Map.of("field", "metricKey"));
        }
        if (condition == null) {
            throw new PlatformExceptions.Validation("An alert needs a condition",
                    Map.of("field", "condition"));
        }
        if (threshold == null) {
            throw new PlatformExceptions.Validation("An alert needs a threshold",
                    Map.of("field", "threshold"));
        }
        this.metricKey = metricKey.trim();
        this.condition = condition;
        this.threshold = threshold;
        this.windowMinutes = requireWindow(windowMinutes);
        this.status = Status.ACTIVE;
        changeDelivery(channels, recipients);
    }

    /** Which channels this alert delivers on, and to whom. */
    public void changeDelivery(List<Notification.Channel> channels, List<UUID> recipients) {
        Set<Notification.Channel> channelSet = channels == null ? Set.of() : new LinkedHashSet<>(channels);
        if (channelSet.isEmpty()) {
            throw new PlatformExceptions.Validation(
                    "An alert must deliver somewhere, or it is a number nobody reads",
                    Map.of("field", "notifyChannels"));
        }
        Set<UUID> recipientsWithoutNulls = new LinkedHashSet<>();
        if (recipients != null) {
            recipients.stream().filter(java.util.Objects::nonNull).forEach(recipientsWithoutNulls::add);
        }
        boolean needsRecipients = channelSet.stream().anyMatch(channel -> channel != Notification.Channel.WEBHOOK);
        if (needsRecipients && recipientsWithoutNulls.isEmpty()) {
            throw new PlatformExceptions.Validation(
                    "An alert delivering in-app or by email must name at least one recipient; "
                            + "an alert nobody receives is worse than no alert",
                    Map.of("field", "notifyUserIds"));
        }
        this.notifyChannels = channelSet.stream().map(Enum::name).sorted().toArray(String[]::new);
        this.notifyUserIds = recipientsWithoutNulls.toArray(UUID[]::new);
    }

    public void changeCondition(AlertCondition newCondition, BigDecimal newThreshold, Integer newWindowMinutes) {
        if (newCondition != null) {
            this.condition = newCondition;
        }
        if (newThreshold != null) {
            this.threshold = newThreshold;
        }
        if (newWindowMinutes != null) {
            this.windowMinutes = requireWindow(newWindowMinutes);
        }
    }

    public void pause() {
        if (status == Status.PAUSED) {
            return;
        }
        this.status = Status.PAUSED;
    }

    public void resume() {
        this.status = Status.ACTIVE;
    }

    /** Records a firing. The alert stays live; {@link #isDue} decides when it may fire again. */
    public void trigger(Instant at) {
        this.status = Status.TRIGGERED;
        this.lastTriggeredAt = at;
    }

    /**
     * Whether this alert may fire at {@code now}.
     *
     * <p>Paused alerts never fire. Everything else waits one window between firings, which is
     * what stops a metric that is over threshold for an hour from producing an alert per sweep.
     */
    public boolean isDue(Instant now) {
        if (status == Status.PAUSED) {
            return false;
        }
        return lastTriggeredAt == null || !lastTriggeredAt.plus(Duration.ofMinutes(windowMinutes)).isAfter(now);
    }

    public List<Notification.Channel> channels() {
        return Arrays.stream(notifyChannels)
                .map(name -> {
                    try {
                        return Notification.Channel.valueOf(name);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<UUID> recipients() {
        return List.of(notifyUserIds);
    }

    private static int requireWindow(int minutes) {
        if (minutes < 1 || minutes > 43_200) {
            throw new PlatformExceptions.Validation(
                    "An alert window must be between 1 minute and 30 days",
                    Map.of("field", "windowMinutes"));
        }
        return minutes;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public AlertCondition getCondition() {
        return condition;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getLastTriggeredAt() {
        return lastTriggeredAt;
    }
}
