package com.hatis.platform.analytics.domain;

/**
 * How several measurements of one metric are collapsed into one number for a bucket.
 *
 * <p>Stored per metric, and matching the {@code aggregation} check constraint in
 * {@code V1_011} exactly. {@link #LAST} is the odd one out and exists for gauges — "how many
 * assets are there" is not a sum, and adding it to a previous total would double the
 * customer's storage every time the collector ran.
 */
public enum MetricAggregation {

    SUM,
    AVG,
    MIN,
    MAX,
    COUNT,
    LAST
}
