package com.hatis.platform.analytics.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison an alert makes.
 *
 * <p>This is the one place in analytics where being wrong pages a person or, worse, stops
 * alerting on the condition the alert was created for. The boundary cases are therefore the
 * test: {@code GT} at exactly the threshold, {@code GTE} at exactly the threshold, and a value
 * that came back from a {@code numeric(24,6)} column with a scale the caller never wrote.
 */
@DisplayName("Alert condition")
class AlertConditionTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("10");

    @Test
    @DisplayName("strict comparisons do not fire at the threshold")
    void strictComparisonsExcludeTheThreshold() {
        assertThat(AlertCondition.GT.isMet(new BigDecimal("10.000001"), THRESHOLD)).isTrue();
        assertThat(AlertCondition.GT.isMet(THRESHOLD, THRESHOLD)).isFalse();
        assertThat(AlertCondition.LT.isMet(new BigDecimal("9.999999"), THRESHOLD)).isTrue();
        assertThat(AlertCondition.LT.isMet(THRESHOLD, THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("inclusive comparisons fire at the threshold")
    void inclusiveComparisonsIncludeTheThreshold() {
        assertThat(AlertCondition.GTE.isMet(THRESHOLD, THRESHOLD)).isTrue();
        assertThat(AlertCondition.GTE.isMet(new BigDecimal("9.999999"), THRESHOLD)).isFalse();
        assertThat(AlertCondition.LTE.isMet(THRESHOLD, THRESHOLD)).isTrue();
        assertThat(AlertCondition.LTE.isMet(new BigDecimal("10.000001"), THRESHOLD)).isFalse();
        assertThat(AlertCondition.EQ.isMet(THRESHOLD, THRESHOLD)).isTrue();
        assertThat(AlertCondition.EQ.isMet(new BigDecimal("10.000001"), THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("a value read back from numeric(24,6) compares by value, not by scale")
    void scaleDoesNotChangeTheComparison() {
        assertThat(AlertCondition.EQ.isMet(new BigDecimal("12.000000"), new BigDecimal("12"))).isTrue();
        assertThat(AlertCondition.GT.isMet(new BigDecimal("12.000000"), new BigDecimal("12"))).isFalse();
    }

    @Test
    @DisplayName("a missing measurement never breaches a threshold")
    void missingValuesNeverBreach() {
        for (AlertCondition condition : AlertCondition.values()) {
            assertThat(condition.isMet(null, THRESHOLD)).isFalse();
            assertThat(condition.isMet(THRESHOLD, null)).isFalse();
            assertThat(condition.isMet(null, null)).isFalse();
        }
    }

    @Test
    @DisplayName("every condition describes itself, so a message needs no switch of its own")
    void everyConditionDescribesItself() {
        assertThat(AlertCondition.values()).allSatisfy(condition ->
                assertThat(condition.description()).isNotBlank());
    }
}
