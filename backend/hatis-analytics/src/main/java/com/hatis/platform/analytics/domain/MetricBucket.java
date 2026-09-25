package com.hatis.platform.analytics.domain;

/**
 * The width of one point on a chart.
 *
 * <p>An enum rather than a free-text parameter because the value ends up inside a
 * {@code date_trunc} call, and that is a function argument PostgreSQL will not accept as a
 * bind parameter in every position. Deriving the SQL fragment from a closed set keeps the
 * query built from constant text: nothing a caller sends is ever concatenated into SQL.
 */
public enum MetricBucket {

    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH;

    /** The literal passed to {@code date_trunc}. Never caller-supplied. */
    public String truncUnit() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
