package com.hatis.platform.analytics.domain;

import com.hatis.platform.shared.error.PlatformExceptions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One measured value, at one point in time, from one source.
 *
 * <p>A plain record rather than an entity, because {@code anl_metrics} is a fact table with no
 * {@code updated_at} and no lock column: a measurement is written once and never edited.
 * Mapping it as an aggregate would offer mutation that the table has no way to record.
 *
 * <h2>Why the bucket is not {@code now()}</h2>
 *
 * The caller supplies {@link #bucketStart} rather than letting the write path stamp the current
 * time. A metric collected at 09:03 and delivered at 09:04 belongs in the 09:00 bucket, and a
 * series built from arrival times drifts silently whenever a collector is delayed — the chart
 * looks plausible and is wrong. The value is truncated to the minute here so that two samples
 * of the same key within a minute are comparable.
 */
public record MetricSample(
        UUID organizationId,
        String key,
        String name,
        String unit,
        MetricAggregation aggregation,
        BigDecimal value,
        Map<String, Object> dimension,
        Instant bucketStart) {

    public static final int MAX_KEY_LENGTH = 120;
    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_UNIT_LENGTH = 32;
    public static final int MAX_DIMENSION_KEYS = 16;

    public MetricSample {
        if (organizationId == null) {
            throw new PlatformExceptions.Validation("A metric belongs to an organization",
                    Map.of("field", "organizationId"));
        }
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new PlatformExceptions.Validation(
                    "A metric key is required and may not exceed " + MAX_KEY_LENGTH + " characters",
                    Map.of("field", "key"));
        }
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new PlatformExceptions.Validation(
                    "A metric name is required and may not exceed " + MAX_NAME_LENGTH + " characters",
                    Map.of("field", "name"));
        }
        if (unit != null && unit.length() > MAX_UNIT_LENGTH) {
            throw new PlatformExceptions.Validation(
                    "A metric unit may not exceed " + MAX_UNIT_LENGTH + " characters",
                    Map.of("field", "unit"));
        }
        if (aggregation == null) {
            throw new PlatformExceptions.Validation("A metric needs an aggregation",
                    Map.of("field", "aggregation"));
        }
        if (value == null) {
            throw new PlatformExceptions.Validation("A metric needs a value", Map.of("field", "value"));
        }
        if (value.scale() > 6) {
            // numeric(24,6): PostgreSQL would round silently, and a silently rounded metric is
            // indistinguishable from a measured one.
            throw new PlatformExceptions.Validation(
                    "A metric value may not carry more than 6 decimal places", Map.of("field", "value"));
        }
        if (value.abs().precision() - value.scale() > 18) {
            throw new PlatformExceptions.Validation(
                    "A metric value may not exceed 18 integer digits", Map.of("field", "value"));
        }
        dimension = dimension == null ? Map.of() : Map.copyOf(dimension);
        if (dimension.size() > MAX_DIMENSION_KEYS) {
            throw new PlatformExceptions.Validation(
                    "A metric dimension may not carry more than " + MAX_DIMENSION_KEYS + " keys",
                    Map.of("field", "dimension"));
        }
        if (bucketStart == null) {
            throw new PlatformExceptions.Validation("A metric needs a bucket", Map.of("field", "bucketStart"));
        }
        key = key.trim();
        name = name.trim();
        unit = unit == null || unit.isBlank() ? null : unit.trim();
        bucketStart = bucketStart.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    }
}
