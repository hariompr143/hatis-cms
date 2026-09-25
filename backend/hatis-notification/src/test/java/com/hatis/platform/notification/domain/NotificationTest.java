package com.hatis.platform.notification.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The notification lifecycle.
 *
 * <p>The interesting cases are the ones where the row would otherwise lie. A notification
 * that was never delivered has nothing in the inbox to read, so marking it read is refused
 * rather than recorded. A failed attempt keeps its count, because a retry policy and a
 * customer both need to see that delivery was tried. And marking read twice is not a
 * conflict: clients retry, and somebody who has already seen a message has done nothing wrong.
 */
@DisplayName("Notification lifecycle")
class NotificationTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("a queued notification is pending and has attempted nothing")
    void queuedIsPending() {
        Notification notification = queue();

        assertThat(notification.getStatus()).isEqualTo(Notification.Status.PENDING);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.isUnread()).isFalse();
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    @DisplayName("a delivered notification is sent, counted, and unread")
    void deliveredIsSentAndUnread() {
        Notification notification = queue();

        notification.markSent();

        assertThat(notification.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.isUnread()).isTrue();
    }

    @Test
    @DisplayName("a failed attempt keeps the count, so a retry has something to reason about")
    void aFailedAttemptIsCounted() {
        Notification notification = queue();

        notification.markFailed();
        notification.markSent();

        assertThat(notification.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(notification.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("marking a delivered notification read records when")
    void markReadRecordsTheTime() {
        Notification notification = queue();
        notification.markSent();

        notification.markRead();

        assertThat(notification.getStatus()).isEqualTo(Notification.Status.READ);
        assertThat(notification.getReadAt()).isNotNull();
        assertThat(notification.isUnread()).isFalse();
    }

    @Test
    @DisplayName("marking read twice is not a conflict")
    void markReadIsIdempotent() {
        Notification notification = queue();
        notification.markSent();
        notification.markRead();

        notification.markRead();

        assertThat(notification.getStatus()).isEqualTo(Notification.Status.READ);
    }

    @Test
    @DisplayName("a notification that was never delivered cannot be marked read")
    void undeliveredNotificationsCannotBeRead() {
        Notification pending = queue();

        assertThatThrownBy(pending::markRead)
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("PENDING");

        pending.markFailed();

        assertThatThrownBy(pending::markRead)
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    @DisplayName("a notification that has been read is final")
    void readIsFinal() {
        Notification notification = queue();
        notification.markSent();
        notification.markRead();

        assertThatThrownBy(notification::markSent)
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("read");
        assertThatThrownBy(notification::markFailed)
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("read");
    }

    @Test
    @DisplayName("a notification must name its recipient, channel, template, subject and body")
    void requiredFieldsAreValidated() {
        assertThatThrownBy(() -> Notification.queue(organizationId, null, Notification.Channel.IN_APP,
                "cms.content.published", "Published", "It is live."))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("recipient");

        assertThatThrownBy(() -> Notification.queue(organizationId, userId, null,
                "cms.content.published", "Published", "It is live."))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("channel");

        assertThatThrownBy(() -> Notification.queue(organizationId, userId, Notification.Channel.IN_APP,
                " ", "Published", "It is live."))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("template");

        assertThatThrownBy(() -> Notification.queue(organizationId, userId, Notification.Channel.IN_APP,
                "cms.content.published", " ", "It is live."))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("subject");

        assertThatThrownBy(() -> Notification.queue(organizationId, userId, Notification.Channel.IN_APP,
                "cms.content.published", "Published", "  "))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("body");
    }

    @Test
    @DisplayName("a subject longer than the column that stores it is refused")
    void theSubjectIsBounded() {
        assertThatThrownBy(() -> Notification.queue(organizationId, userId, Notification.Channel.IN_APP,
                "cms.content.published", "s".repeat(513), "It is live."))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("512");
    }

    private Notification queue() {
        return Notification.queue(organizationId, userId, Notification.Channel.IN_APP,
                "cms.content.published", "Content published", "Your content is live.");
    }
}
