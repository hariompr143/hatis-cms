package com.hatis.platform.notification.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.notification.adapter.persistence.NotificationRepository;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.notification.port.out.ChannelSender;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery bookkeeping and the personal inbox.
 *
 * <p>Two properties carry the weight. A failed delivery is <em>data</em>: the attempt is
 * counted and the status says failed, and no exception reaches the operation that raised the
 * notification, because that operation has already succeeded. And an inbox is personal: every
 * query is scoped to the authenticated principal, and somebody else's notification reads as
 * not found rather than forbidden, since whether a colleague has one is not the caller's
 * business.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Notification service")
class NotificationServiceTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();

    @Mock
    private NotificationRepository repository;
    @Mock
    private AuthorizationService authorization;
    @Mock
    private AuditRecorder audit;

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(TenantContext.of(organizationId, principalId,
                TenantContext.PrincipalType.USER));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("a notification handed to its channel is marked sent and audited")
    void aDeliveredNotificationIsSent() {
        RecordingSender sender = new RecordingSender();
        when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        Notification notification = service(List.of(sender)).notify(command(Notification.Channel.IN_APP));

        assertThat(sender.delivered).isEqualTo(1);
        assertThat(notification.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(notification.getAttempts()).isEqualTo(1);

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().action()).isEqualTo("notification.sent");
    }

    @Test
    @DisplayName("a channel that refuses is recorded as failed, and the caller is not interrupted")
    void aRefusedDeliveryIsRecordedNotThrown() {
        ChannelSender refusing = new FailingSender();
        when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        Notification notification = service(List.of(refusing)).notify(command(Notification.Channel.EMAIL));

        assertThat(notification.getStatus()).isEqualTo(Notification.Status.FAILED);
        assertThat(notification.getAttempts())
                .as("the attempt count is what a retry policy would need")
                .isEqualTo(1);

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().action()).isEqualTo("notification.failed");
        assertThat(recorded.getValue().result()).isEqualTo(AuditRecord.Result.FAILED);
    }

    @Test
    @DisplayName("a channel with no sender on this deployment is failed, not silently dropped")
    void aChannelWithoutASenderFails() {
        when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        // No sender is registered for WEBHOOK at all, which is the deployment shape where
        // outbound webhooks are not configured.
        Notification notification = service(List.of()).notify(command(Notification.Channel.WEBHOOK));

        assertThat(notification.getStatus()).isEqualTo(Notification.Status.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().reason()).contains("channel not configured");
    }

    @Test
    @DisplayName("two senders claiming one channel are refused where the channel is named")
    void duplicateSendersAreRefused() {
        assertThatThrownBy(() -> service(List.of(new RecordingSender(), new RecordingSender())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("notifying without a tenant bound is refused")
    void notifyingWithoutATenantIsRefused() {
        TenantContextHolder.clear();

        assertThatThrownBy(() -> service(List.of(new RecordingSender()))
                .notify(command(Notification.Channel.IN_APP)))
                .isInstanceOf(PlatformExceptions.Unauthenticated.class);

        verify(repository, never()).save(any(Notification.class));
    }

    @Test
    @DisplayName("the HTTP entry point checks the send permission before anything is written")
    void sendChecksThePermission() {
        when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        service(List.of(new RecordingSender())).send(command(Notification.Channel.IN_APP));

        verify(authorization).require(NotificationService.SEND_PERMISSION, ScopeType.ORGANIZATION,
                organizationId);
    }

    @Test
    @DisplayName("a denied send writes nothing")
    void aDeniedSendWritesNothing() {
        doThrow(new PlatformExceptions.Forbidden("no"))
                .when(authorization).require(any(), any(), any());

        assertThatThrownBy(() -> service(List.of(new RecordingSender()))
                .send(command(Notification.Channel.IN_APP)))
                .isInstanceOf(PlatformExceptions.Forbidden.class);

        verify(repository, never()).save(any(Notification.class));
    }

    @Test
    @DisplayName("the inbox is scoped to the calling principal")
    void theInboxIsScopedToTheCaller() {
        when(repository.findByOrganizationIdAndUserIdOrderByCreatedAtDesc(eq(organizationId),
                eq(principalId), any(Pageable.class))).thenReturn(Page.empty());

        assertThat(service(List.of()).inbox(false, 0, 25).items()).isEmpty();

        verify(repository).findByOrganizationIdAndUserIdOrderByCreatedAtDesc(eq(organizationId),
                eq(principalId), any(Pageable.class));
    }

    @Test
    @DisplayName("an unread-only inbox filters to delivered notifications, not to everything")
    void unreadOnlyFiltersToDeliveredNotifications() {
        when(repository.findByOrganizationIdAndUserIdAndStatusInOrderByCreatedAtDesc(eq(organizationId),
                eq(principalId), eq(List.of(Notification.Status.SENT)), any(Pageable.class)))
                .thenReturn(Page.empty());

        service(List.of()).inbox(true, 0, 25);

        verify(repository).findByOrganizationIdAndUserIdAndStatusInOrderByCreatedAtDesc(
                eq(organizationId), eq(principalId), eq(List.of(Notification.Status.SENT)),
                any(Pageable.class));
    }

    @Test
    @DisplayName("the unread count counts delivered notifications only")
    void unreadCountCountsDeliveredNotifications() {
        when(repository.countByOrganizationIdAndUserIdAndStatus(organizationId, principalId,
                Notification.Status.SENT)).thenReturn(3L);

        assertThat(service(List.of()).unreadCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("somebody else's notification reads as not found")
    void anotherUsersNotificationIsNotFound() {
        UUID notificationId = UUID.randomUUID();
        when(repository.findByIdAndOrganizationIdAndUserId(notificationId, organizationId, principalId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(List.of()).markRead(notificationId))
                .as("not forbidden: whether a colleague has this notification is not disclosed")
                .isInstanceOf(PlatformExceptions.NotFound.class);
    }

    @Test
    @DisplayName("marking read returns the notification as read")
    void markingReadReturnsTheReadNotification() {
        Notification notification = Notification.queue(organizationId, principalId,
                Notification.Channel.IN_APP, "cms.content.published", "Published", "It is live.");
        notification.markSent();
        when(repository.findByIdAndOrganizationIdAndUserId(notification.getId(), organizationId, principalId))
                .thenReturn(Optional.of(notification));
        when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        NotificationService.InboxItem item = service(List.of()).markRead(notification.getId());

        assertThat(item.status()).isEqualTo("READ");
        assertThat(item.readAt()).isNotNull();
        assertThat(item.isUnread()).isFalse();
    }

    @Test
    @DisplayName("marking everything read is one statement scoped to the caller")
    void markAllReadIsOneScopedStatement() {
        when(repository.markAllRead(eq(organizationId), eq(principalId), eq(Notification.Status.READ),
                eq(Notification.Status.SENT), any(Instant.class))).thenReturn(7);

        assertThat(service(List.of()).markAllRead()).isEqualTo(7);

        verify(repository).markAllRead(eq(organizationId), eq(principalId), eq(Notification.Status.READ),
                eq(Notification.Status.SENT), any(Instant.class));
    }

    // ---------------------------------------------------------------- helpers

    private NotificationService service(List<ChannelSender> senders) {
        return new NotificationService(repository, senders, authorization, audit);
    }

    private static NotificationService.NotifyCommand command(Notification.Channel channel) {
        return new NotificationService.NotifyCommand(UUID.randomUUID(), channel, "cms.content.published",
                "Content published", "Your content is live.");
    }

    /** A channel that accepts everything, counting what it was handed. */
    private static final class RecordingSender implements ChannelSender {

        private final AtomicInteger delivered = new AtomicInteger();

        @Override
        public Notification.Channel channel() {
            return Notification.Channel.IN_APP;
        }

        @Override
        public void send(Notification notification) {
            delivered.incrementAndGet();
        }
    }

    /** A channel that refuses everything, the way an SMTP server or an endpoint does. */
    private static final class FailingSender implements ChannelSender {

        @Override
        public Notification.Channel channel() {
            return Notification.Channel.EMAIL;
        }

        @Override
        public void send(Notification notification) {
            throw new IllegalStateException("the channel refused the message");
        }
    }
}
