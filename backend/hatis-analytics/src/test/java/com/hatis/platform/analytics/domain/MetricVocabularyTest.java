package com.hatis.platform.analytics.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enum values that end up inside SQL, pinned to what the database accepts.
 *
 * <p>{@link MetricBucket} and {@link MetricAggregation} are enums rather than strings because
 * their names are concatenated into query text; the values below are the check constraints in
 * {@code V1_011}, spelled out so that adding a constant without a migration — or a migration
 * without a constant — fails here rather than at runtime. {@code date_trunc} additionally
 * requires its unit to be lowercase, which {@link MetricBucket#truncUnit()} guarantees.
 */
@DisplayName("Metric vocabulary")
class MetricVocabularyTest {

    @Test
    @DisplayName("a bucket's literal is the lowercase name date_trunc accepts")
    void bucketUnitsAreLowercaseLiterals() {
        for (MetricBucket bucket : MetricBucket.values()) {
            assertThat(bucket.truncUnit())
                    .isEqualTo(bucket.name().toLowerCase(Locale.ROOT))
                    .isLowerCase();
        }
    }

    @Test
    @DisplayName("the aggregations are exactly the ones the V1_011 check constraint accepts")
    void aggregationsMatchTheDatabaseConstraint() {
        assertThat(Arrays.stream(MetricAggregation.values()).map(Enum::name).collect(Collectors.toList()))
                .containsExactly("SUM", "AVG", "MIN", "MAX", "COUNT", "LAST");
    }

    @Test
    @DisplayName("the conditions are exactly the ones the V1_011 check constraint accepts")
    void conditionsMatchTheDatabaseConstraint() {
        assertThat(Arrays.stream(AlertCondition.values()).map(Enum::name).collect(Collectors.toList()))
                .containsExactly("GT", "GTE", "LT", "LTE", "EQ");
    }
}
