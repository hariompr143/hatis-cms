package com.hatis.platform.notification.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.notification.adapter.persistence.NotificationRepository;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.notification.port.out.ChannelSender;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Raises notifications and answers questions about a user's inbox.
 *
 * <h2>A notification never fails the operation that raised it</h2>
 *
 * {@link #notify} returns normally even when delivery failed. The row is written first and
 * its status records what happened, so the failure is data a customer can see rather than an
 * exception that rolls back an approval because an SMTP server was slow. That is the same
 * contract {@code AuditRecorder} has, and for the same reason: the business change has
 * already happened, and a messaging problem must not pretend otherwise.
 *
 * <h2>Two entry points, on purpose</h2>
 *
 * {@link #notify} is the in-process API for other contexts — a workflow assigning a task, an
 * alert firing. Those callers have already been authorized for what they are doing, and a
 * background job has no role bindings to check against, so requiring a permission there would
 * mean either inventing a principal or leaving the check out silently. {@link #send} is the
 * entry point for callers reaching the platform over HTTP, and it checks
 * {@code notification:send} itself rather than trusting a controller to have done it.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** The permission required to notify a user other than yourself. */
    public static final String SEND_PERMISSION = "notification:send";

    private final NotificationRepository repository;
    private final Map<Notification.Channel, ChannelSender> senders;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;

    public NotificationService(NotificationRepository repository,
                               List<ChannelSender> senders,
                               AuthorizationService authorization,
                               AuditRecorder audit) {
        this.repository = repository;
        // Two senders for one channel would make delivery depend on bean ordering, so the
        // duplicate is refused here, where the failure names the channel, rather than at
        // runtime where it would look like a flaky delivery.
        this.senders = senders.stream()
                .collect(Collectors.toUnmodifiableMap(ChannelSender::channel, Function.identity()));
        this.authorization = authorization;
        this.audit = audit;
    }

    /** What a caller asks for. Delivery details belong to the channel, not to the caller. */
    public record NotifyCommand(UUID userId,
                                Notification.Channel channel,
                                String templateKey,
                                String subject,
                                String body) {
    }

    /**
     * Raises a notification for one user of the calling tenant.
     *
     * <p>Intended for other contexts inside the platform. It performs no permission check —
     * see the class comment for why — so it must not be reachable directly from a request
     * handler. {@link #send} is that entry point.
     */
    @TenantTransactional
    public Notification notify(NotifyCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Notification notification = repository.save(Notification.queue(organizationId,
                command.userId(), command.channel(), command.templateKey(), command.subject(), command.body()));
        dispatch(notification);
        return repository.save(notification);
    }

    /** Authorized entry point: the caller must be allowed to notify this tenant's users. */
    @TenantTransactional
    public Notification send(NotifyCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require(SEND_PERMISSION, ScopeType.ORGANIZATION, organizationId);
        return notify(command);
    }

    /** The caller's own inbox, newest first. Optionally only what is still unread. */
    @TenantTransactional(readOnly = true)
    public PageResponse<InboxItem> inbox(boolean unreadOnly, int page, int size) {
        var context = TenantContextHolder.require();
        UUID organizationId = context.requireOrganizationId();
        UUID userId = context.principalId();
        var pageable = PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = unreadOnly
                ? repository.findByOrganizationIdAndUserIdAndStatusInOrderByCreatedAtDesc(
                        organizationId, userId, List.of(Notification.Status.SENT), pageable)
                : repository.findByOrganizationIdAndUserIdOrderByCreatedAtDesc(organizationId, userId, pageable);
        return PageResponse.from(result, NotificationService::toInboxItem);
    }

    /** How many notifications the caller has not read. */
    @TenantTransactional(readOnly = true)
    public long unreadCount() {
        var context = TenantContextHolder.require();
        return repository.countByOrganizationIdAndUserIdAndStatus(
                context.requireOrganizationId(), context.principalId(), Notification.Status.SENT);
    }

    /** Marks one of the caller's own notifications read. */
    @TenantTransactional
    public InboxItem markRead(UUID notificationId) {
        var context = TenantContextHolder.require();
        UUID organizationId = context.requireOrganizationId();
        UUID userId = context.principalId();
        // Not found rather than forbidden when the row belongs to somebody else: whether a
        // colleague has a notification with this id is not the caller's business.
        Notification notification = repository.findByIdAndOrganizationIdAndUserId(notificationId, organizationId, userId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Notification", notificationId));
        notification.markRead();
        return toInboxItem(repository.save(notification));
    }

    /** Marks every delivered notification of the caller read; returns how many changed. */
    @TenantTransactional
    public int markAllRead() {
        var context = TenantContextHolder.require();
        return repository.markAllRead(context.requireOrganizationId(), context.principalId(),
                Notification.Status.READ, Notification.Status.SENT, Instant.now());
    }

    /**
     * Hands the notification to its channel and records the outcome.
     *
     * <p>The catch is deliberately broad. A channel is an external system, and the ways it
     * can fail — a mail library throwing its own exception type, an HTTP client wrapping a
     * timeout — are not a closed set. What matters is that the row records the failure and
     * the attempt, so the next attempt and the customer both start from the truth.
     */
    private void dispatch(Notification notification) {
        ChannelSender sender = senders.get(notification.getChannel());
        if (sender == null) {
            notification.markFailed();
            log.warn("No {} channel sender is configured on this deployment; notification {} is recorded as failed",
                    notification.getChannel(), notification.getId());
            auditFailure(notification, "channel not configured");
            return;
        }
        try {
            sender.send(notification);
            notification.markSent();
            audit.record(AuditRecord.builder("notification.sent")
                    .resource("notification", notification.getId())
                    .metadata(Map.of("channel", notification.getChannel().name(),
                            "template", notification.getTemplateKey(),
                            "recipient", notification.getUserId().toString()))
                    .build());
        } catch (RuntimeException e) {
            notification.markFailed();
            log.error("{} delivery of notification {} failed: {}",
                    notification.getChannel(), notification.getId(), e.toString());
            auditFailure(notification, e.getClass().getSimpleName());
        }
    }

    private void auditFailure(Notification notification, String reason) {
        audit.record(AuditRecord.builder("notification.failed")
                .resource("notification", notification.getId())
                .result(AuditRecord.Result.FAILED)
                .reason(reason)
                .metadata(Map.of("channel", notification.getChannel().name(),
                        "template", notification.getTemplateKey()))
                .build());
    }

    private static InboxItem toInboxItem(Notification notification) {
        return InboxItem.from(notification);
    }

    /**
     * What a client sees.
     *
     * <p>The entity is never returned directly: {@code organizationId} and the JPA version
     * belong to the persistence model, and the shape a client depends on should not change
     * because a mapping did. {@code attempts} is included on purpose — it is how a customer
     * sees that a delivery was tried and failed.
     */
    public record InboxItem(UUID id, String channel, String templateKey, String subject, String body,
                            String status, int attempts, Instant readAt, Instant createdAt) {

        public static InboxItem from(Notification notification) {
            return new InboxItem(notification.getId(), notification.getChannel().name(),
                    notification.getTemplateKey(), notification.getSubject(), notification.getBody(),
                    notification.getStatus().name(), notification.getAttempts(), notification.getReadAt(),
                    notification.getCreatedAt());
        }

        public boolean isUnread() {
            return Notification.Status.SENT.name().equals(status);
        }
    }
}
