package com.hatis.platform.analytics.adapter.persistence;

import com.hatis.platform.analytics.domain.AlertCondition;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An alert's delivery rules and its cooldown.
 *
 * <p>The cooldown is the part worth pinning down: an alert that fires on every sweep is an
 * alert somebody turns off. It fires again only once its window has passed, and the window is
 * what makes the difference between "the metric is over threshold" and "the metric was over
 * threshold an hour ago and still is".
 */
@DisplayName("Alert")
class AlertTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID firstUser = UUID.randomUUID();
    private final UUID secondUser = UUID.randomUUID();

    @Test
    @DisplayName("an alert starts active and watching the metric it was given")
    void anAlertStartsActive() {
        Alert alert = alert("  storage.bytes  ", 60, List.of(Notification.Channel.IN_APP), List.of(firstUser));

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.ACTIVE);
        assertThat(alert.getMetricKey()).isEqualTo("storage.bytes");
        assertThat(alert.getCondition()).isEqualTo(AlertCondition.GT);
        assertThat(alert.getThreshold()).isEqualByComparingTo("100");
        assertThat(alert.getWindowMinutes()).isEqualTo(60);
        assertThat(alert.getLastTriggeredAt()).isNull();
        assertThat(alert.getOrganizationId()).isEqualTo(organizationId);
    }

    @Test
    @DisplayName("an alert must name a metric, a condition and a threshold")
    void anAlertNeedsSomethingToWatch() {
        assertThatThrownBy(() -> alert("  ", 60, List.of(Notification.Channel.IN_APP), List.of(firstUser)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("metric");

        assertThatThrownBy(() -> new Alert(organizationId, "storage.bytes", null, BigDecimal.ONE,
                60, List.of(Notification.Channel.IN_APP), List.of(firstUser)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("condition");

        assertThatThrownBy(() -> new Alert(organizationId, "storage.bytes", AlertCondition.GT, null,
                60, List.of(Notification.Channel.IN_APP), List.of(firstUser)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("threshold");
    }

    @Test
    @DisplayName("the window is between a minute and thirty days")
    void theWindowIsBounded() {
        assertThat(alert("k", 1, List.of(Notification.Channel.IN_APP), List.of(firstUser)).getWindowMinutes())
                .isEqualTo(1);
        assertThat(alert("k", 43_200, List.of(Notification.Channel.IN_APP), List.of(firstUser)).getWindowMinutes())
                .isEqualTo(43_200);

        assertThatThrownBy(() -> alert("k", 0, List.of(Notification.Channel.IN_APP), List.of(firstUser)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("1 minute");
        assertThatThrownBy(() -> alert("k", 43_201, List.of(Notification.Channel.IN_APP), List.of(firstUser)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("30 days");
    }

    @Test
    @DisplayName("an alert must deliver somewhere, or it is a number nobody reads")
    void anAlertMustDeliverSomewhere() {
        assertThatThrownBy(() -> alert("k", 60, List.of(), List.of(firstUser)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("deliver");
        assertThatThrownBy(() -> alert("k", 60, null, List.of(firstUser)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("deliver");
    }

    @Test
    @DisplayName("in-app and email delivery need at least one recipient who is not null")
    void channelsThatNeedRecipientsGetOne() {
        assertThatThrownBy(() -> alert("k", 60, List.of(Notification.Channel.IN_APP), List.of()))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("recipient");

        assertThatThrownBy(() -> alert("k", 60, List.of(Notification.Channel.EMAIL),
                Collections.singletonList(null)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("recipient");
    }

    @Test
    @DisplayName("a webhook-only alert needs no recipient, because the event is the delivery")
    void aWebhookOnlyAlertNeedsNoRecipient() {
        Alert alert = alert("k", 60, List.of(Notification.Channel.WEBHOOK), null);

        assertThat(alert.channels()).containsExactly(Notification.Channel.WEBHOOK);
        assertThat(alert.recipients()).isEmpty();
    }

    @Test
    @DisplayName("channels are deduplicated and recipients are null-free")
    void deliveryIsDeduplicated() {
        Alert alert = alert("k", 60,
                List.of(Notification.Channel.IN_APP, Notification.Channel.IN_APP, Notification.Channel.EMAIL),
                Arrays.asList(null, firstUser, firstUser, secondUser));

        assertThat(alert.channels())
                .containsExactly(Notification.Channel.EMAIL, Notification.Channel.IN_APP);
        assertThat(alert.recipients()).containsExactly(firstUser, secondUser);
    }

    @Test
    @DisplayName("a stored channel this build does not know is ignored rather than fatal")
    void anUnknownStoredChannelIsIgnored() {
        Alert alert = alert("k", 60, List.of(Notification.Channel.IN_APP), List.of(firstUser));

        ReflectionTestUtils.setField(alert, "notifyChannels", new String[]{"IN_APP", "CARRIER_PIGEON"});

        assertThat(alert.channels()).containsExactly(Notification.Channel.IN_APP);
    }

    @Test
    @DisplayName("an alert fires once per window, not once per sweep")
    void firingWaitsOutTheWindow() {
        Alert alert = alert("k", 60, List.of(Notification.Channel.IN_APP), List.of(firstUser));
        Instant now = Instant.parse("2026-09-25T12:00:00Z");

        assertThat(alert.isDue(now)).isTrue();

        alert.trigger(now);

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.TRIGGERED);
        assertThat(alert.getLastTriggeredAt()).isEqualTo(now);
        assertThat(alert.isDue(now)).isFalse();
        assertThat(alert.isDue(now.plus(59, ChronoUnit.MINUTES))).isFalse();
        assertThat(alert.isDue(now.plus(60, ChronoUnit.MINUTES))).isTrue();
    }

    @Test
    @DisplayName("a paused alert never fires, and resuming it makes it due again")
    void aPausedAlertNeverFires() {
        Alert alert = alert("k", 60, List.of(Notification.Channel.IN_APP), List.of(firstUser));
        Instant now = Instant.parse("2026-09-25T12:00:00Z");

        alert.pause();
        alert.pause();

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.PAUSED);
        assertThat(alert.isDue(now)).isFalse();

        alert.resume();

        assertThat(alert.getStatus()).isEqualTo(Alert.Status.ACTIVE);
        assertThat(alert.isDue(now)).isTrue();
    }

    @Test
    @DisplayName("changing a condition keeps what the caller did not pass")
    void changeConditionKeepsWhatIsNotPassed() {
        Alert alert = alert("k", 60, List.of(Notification.Channel.IN_APP), List.of(firstUser));

        alert.changeCondition(AlertCondition.LT, new BigDecimal("5"), null);

        assertThat(alert.getCondition()).isEqualTo(AlertCondition.LT);
        assertThat(alert.getThreshold()).isEqualByComparingTo("5");
        assertThat(alert.getWindowMinutes()).isEqualTo(60);

        assertThatThrownBy(() -> alert.changeCondition(null, null, 0))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("window");
    }

    @Test
    @DisplayName("changing delivery moves both channels and recipients")
    void changeDeliveryReplacesBoth() {
        Alert alert = alert("k", 60, List.of(Notification.Channel.IN_APP), List.of(firstUser));

        alert.changeDelivery(List.of(Notification.Channel.WEBHOOK, Notification.Channel.EMAIL),
                List.of(secondUser));

        assertThat(alert.channels())
                .containsExactly(Notification.Channel.EMAIL, Notification.Channel.WEBHOOK);
        assertThat(alert.recipients()).containsExactly(secondUser);
    }

    private Alert alert(String metricKey, int windowMinutes, List<Notification.Channel> channels,
                        List<UUID> recipients) {
        return new Alert(organizationId, metricKey, AlertCondition.GT, new BigDecimal("100"),
                windowMinutes, channels, recipients);
    }
}
