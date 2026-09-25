package com.hatis.platform.notification.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One message the platform owes one user.
 *
 * <p>Maps {@code ntf_notifications}. The row is the queue, the delivery record and the
 * inbox at once: it is written {@code PENDING} before anything is sent, so a delivery that
 * fails is a row a person can see rather than a log line nobody reads. That is also why
 * {@link #markFailed()} exists: a notification that could not be delivered is a fact about
 * the platform, and hiding it by deleting the row or leaving it {@code PENDING} forever
 * would make the inbox lie.
 *
 * <h2>What this table cannot record</h2>
 *
 * There is no column for the failure reason, so a failed delivery keeps its attempt count
 * and the reason is logged rather than stored. Adding a column for it would be the better
 * design and is deliberately not smuggled into this phase: the alternative — a
 * {@code last_error} column added by a migration — is a schema change, and the honest
 * Phase 1 position is that a customer can see <em>that</em> a delivery failed and how many
 * times, not why. The attempt count is what a retry policy would need; the retry policy is
 * Phase 2.
 */
@Entity
@Table(name = "ntf_notifications", indexes = {
        @Index(name = "ix_ntf_user_unread", columnList = "user_id,status,created_at")
})
public class Notification extends TenantScopedEntity {

    public enum Channel {
        IN_APP,
        EMAIL,
        WEBHOOK
    }

    public enum Status {
        PENDING,
        SENT,
        FAILED,
        READ
    }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private Channel channel;

    @Column(name = "template_key", nullable = false, length = 120, updatable = false)
    private String templateKey;

    @Column(name = "subject", nullable = false, length = 512)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
        super();
    }

    private Notification(UUID organizationId, UUID userId, Channel channel, String templateKey,
                        String subject, String body) {
        super(organizationId);
        if (userId == null) {
            throw new PlatformExceptions.Validation("A notification must name its recipient",
                    java.util.Map.of("field", "userId"));
        }
        if (channel == null) {
            throw new PlatformExceptions.Validation("A notification must name its channel",
                    java.util.Map.of("field", "channel"));
        }
        if (templateKey == null || templateKey.isBlank()) {
            throw new PlatformExceptions.Validation("A notification must name its template",
                    java.util.Map.of("field", "templateKey"));
        }
        if (subject == null || subject.isBlank() || subject.length() > 512) {
            throw new PlatformExceptions.Validation("A notification subject is required and may not exceed 512 characters",
                    java.util.Map.of("field", "subject"));
        }
        if (body == null || body.isBlank()) {
            throw new PlatformExceptions.Validation("A notification body is required",
                    java.util.Map.of("field", "body"));
        }
        this.userId = userId;
        this.channel = channel;
        this.templateKey = templateKey.trim();
        this.subject = subject.trim();
        this.body = body;
        this.status = Status.PENDING;
    }

    /** Queues a notification. Nothing has been delivered when this returns. */
    public static Notification queue(UUID organizationId, UUID userId, Channel channel,
                                     String templateKey, String subject, String body) {
        return new Notification(organizationId, userId, channel, templateKey, subject, body);
    }

    /** Records a delivery that the channel accepted. */
    public void markSent() {
        if (status == Status.READ) {
            throw new PlatformExceptions.StateConflict("A notification that has been read cannot be sent again");
        }
        this.status = Status.SENT;
        this.attempts = attempts + 1;
    }

    /** Records a delivery that failed, without losing the fact that it was attempted. */
    public void markFailed() {
        if (status == Status.READ) {
            throw new PlatformExceptions.StateConflict("A notification that has been read cannot be marked failed");
        }
        this.status = Status.FAILED;
        this.attempts = attempts + 1;
    }

    /**
     * Marks the notification read.
     *
     * <p>Idempotent on purpose: a client that retries a mark-read must not receive a
     * conflict for a state it already reached. Only a delivered notification can be read —
     * a pending or failed one has nothing in the inbox to read.
     */
    public void markRead() {
        if (status == Status.READ) {
            return;
        }
        if (status != Status.SENT) {
            throw new PlatformExceptions.StateConflict(
                    "A notification that is " + status + " cannot be marked read");
        }
        this.status = Status.READ;
        this.readAt = Instant.now();
    }

    public boolean isUnread() {
        return status == Status.SENT;
    }

    public UUID getUserId() {
        return userId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
