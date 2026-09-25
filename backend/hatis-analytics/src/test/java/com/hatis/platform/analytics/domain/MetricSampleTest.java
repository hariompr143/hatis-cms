package com.hatis.platform.analytics.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape of a measurement.
 *
 * <p>Two of these cases exist because of what happens without them. The bucket is truncated
 * to the minute so that two samples of one metric taken in the same minute are comparable in
 * SQL; a caller's millisecond timestamp would put them in different buckets. And the dimension
 * is copied, because the map a collector passes is very often the one it goes on mutating for
 * the next sample.
 */
@DisplayName("Metric sample")
class MetricSampleTest {

    private final UUID organizationId = UUID.randomUUID();
    private final Instant observed = Instant.parse("2026-09-25T10:03:47.812Z");

    @Test
    @DisplayName("a sample belongs to its bucket, not to the moment it arrived")
    void theBucketIsTruncatedToTheMinute() {
        MetricSample sample = sample(observed, Map.of("project", "web"));

        assertThat(sample.bucketStart()).isEqualTo(Instant.parse("2026-09-25T10:03:00Z"));
    }

    @Test
    @DisplayName("the dimension is copied, so a caller cannot edit a stored measurement")
    void theDimensionIsCopied() {
        Map<String, Object> dimension = new HashMap<>();
        dimension.put("project", "web");

        MetricSample sample = sample(observed, dimension);

        dimension.put("project", "mobile");

        assertThat(sample.dimension()).containsOnlyKeys("project").containsEntry("project", "web");
    }

    @Test
    @DisplayName("a missing dimension is an empty one, not a null")
    void aMissingDimensionIsEmpty() {
        assertThat(sample(observed, null).dimension()).isEmpty();
    }

    @Test
    @DisplayName("a blank unit means the metric has no unit, and key and name are trimmed")
    void textFieldsAreNormalised() {
        MetricSample sample = new MetricSample(organizationId, "  cms.requests  ", "  CMS requests  ",
                "   ", MetricAggregation.SUM, BigDecimal.ONE, Map.of(), observed);

        assertThat(sample.key()).isEqualTo("cms.requests");
        assertThat(sample.name()).isEqualTo("CMS requests");
        assertThat(sample.unit()).isNull();
    }

    @Test
    @DisplayName("every bound the numeric(24,6) column imposes is checked before the insert")
    void theColumnsBoundsAreEnforced() {
        assertThatThrownBy(() -> new MetricSample(null, "k", "n", null, MetricAggregation.SUM,
                BigDecimal.ONE, Map.of(), observed))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("organization");

        assertThatThrownBy(() -> sampleWithKey("  "))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("key");

        assertThatThrownBy(() -> sampleWithKey("k".repeat(121)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("120");

        assertThatThrownBy(() -> new MetricSample(organizationId, "k", "  ", null,
                MetricAggregation.SUM, BigDecimal.ONE, Map.of(), observed))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("name");

        assertThatThrownBy(() -> new MetricSample(organizationId, "k", "n", "u".repeat(33),
                MetricAggregation.SUM, BigDecimal.ONE, Map.of(), observed))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("32");

        assertThatThrownBy(() -> new MetricSample(organizationId, "k", "n", null, null,
                BigDecimal.ONE, Map.of(), observed))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("aggregation");

        assertThatThrownBy(() -> new MetricSample(organizationId, "k", "n", null,
                MetricAggregation.SUM, null, Map.of(), observed))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("value");

        assertThatThrownBy(() -> new MetricSample(organizationId, "k", "n", null,
                MetricAggregation.SUM, new BigDecimal("1.1234567"), Map.of(), observed))
                .as("PostgreSQL would round it silently, and a rounded metric looks measured")
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("6 decimal");

        assertThatThrownBy(() -> new MetricSample(organizationId, "k", "n", null,
                MetricAggregation.SUM, new BigDecimal("1" + "0".repeat(19)), Map.of(), observed))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("18");

        Map<String, Object> wide = new HashMap<>();
        for (int i = 0; i < 17; i++) {
            wide.put("key" + i, i);
        }
        assertThatThrownBy(() -> sample(observed, wide))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("16");

        assertThatThrownBy(() -> sample(null, Map.of()))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("bucket");
    }

    private MetricSample sampleWithKey(String key) {
        return new MetricSample(organizationId, key, "CMS requests", "count",
                MetricAggregation.SUM, BigDecimal.ONE, Map.of(), observed);
    }

    private MetricSample sample(Instant bucketStart, Map<String, Object> dimension) {
        return new MetricSample(organizationId, "cms.requests", "CMS requests", "count",
                MetricAggregation.SUM, new BigDecimal("12.5"), dimension, bucketStart);
    }
}
