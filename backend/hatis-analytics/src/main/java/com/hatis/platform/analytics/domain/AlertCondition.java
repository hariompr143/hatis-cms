package com.hatis.platform.analytics.domain;

import java.math.BigDecimal;

/**
 * The comparison an alert performs.
 *
 * <p>Kept as a domain type rather than a string so that the comparison is written once and
 * tested once. An alert is the one place in analytics where being wrong is expensive: a
 * {@code GT} evaluated as {@code GTE} pages somebody at exactly the threshold, and a
 * {@code LT} inverted silently stops alerting on the condition it was created for.
 *
 * <p>The values match the {@code condition} check constraint in
 * {@code V1_011__integration_analytics_notification.sql} exactly, which is what lets the
 * enum be stored with {@code EnumType.STRING}.
 */
public enum AlertCondition {

    GT("greater than"),
    GTE("greater than or equal to"),
    LT("less than"),
    LTE("less than or equal to"),
    EQ("equal to");

    private final String description;

    AlertCondition(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * Whether an observed value breaches the threshold.
     *
     * <p>Compared with {@link BigDecimal#compareTo} rather than {@code equals}: the column is
     * {@code numeric(24,6)}, so a value of {@code 12} read back from the database is
     * {@code 12.000000}, and {@code equals} would report those as different numbers.
     */
    public boolean isMet(BigDecimal observed, BigDecimal threshold) {
        if (observed == null || threshold == null) {
            return false;
        }
        int comparison = observed.compareTo(threshold);
        return switch (this) {
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            case EQ -> comparison == 0;
        };
    }
}
