package com.hatis.platform.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One point on a chart: the start of a bucket and the value aggregated for it.
 *
 * <p>It lives in the domain rather than beside the query that produces it because the REST
 * adapter returns it. A controller that returns a type from {@code adapter.persistence}
 * depends on the persistence adapter, which the architecture rules forbid — and the rule is
 * right here: a point on a chart is not a row, it is the answer to a question a customer asked.
 *
 * <p>{@code bucketStart} is the start of the {@link MetricBucket}, not the time of the last
 * sample in it, so two series requested with different bucket widths line up on the same axis.
 */
public record MetricPoint(Instant bucketStart, BigDecimal value) {
}
