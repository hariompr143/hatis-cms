package com.hatis.platform.notification.adapter.rest;

import com.hatis.platform.notification.application.NotificationService;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The user's own inbox, plus the administrative send.
 *
 * <p>Every inbox route is scoped to the authenticated principal inside
 * {@link NotificationService}; there is no route that takes a user id from the caller, and
 * no permission that would let one member read another's notifications. That is deliberate
 * rather than incidental — a notification says what a person was told, and an organization
 * administrator is not entitled to it by virtue of being an administrator.
 *
 * <p>{@code POST /v1/notifications} is the exception: it names a recipient, so it needs
 * {@code notification:send}, which the seeded roles grant to owners and organization
 * administrators only.
 */
@RestController
@RequestMapping("/v1/notifications")
@Tag(name = "Notifications", description = "User inbox and administrative notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "List the caller's notifications, newest first")
    public PageResponse<NotificationService.InboxItem> inbox(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return notifications.inbox(unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count the caller's unread notifications")
    public UnreadCount unreadCount() {
        return new UnreadCount(notifications.unreadCount());
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one of the caller's notifications read")
    public NotificationService.InboxItem markRead(@PathVariable UUID notificationId) {
        return notifications.markRead(notificationId);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every delivered notification of the caller read")
    public MarkedRead markAllRead() {
        return new MarkedRead(notifications.markAllRead());
    }

    @PostMapping
    @Operation(summary = "Notify a user of this organization")
    public ResponseEntity<NotificationService.InboxItem> send(@Valid @RequestBody SendRequest request) {
        var created = notifications.send(new NotificationService.NotifyCommand(request.userId(),
                request.channel(), request.templateKey(), request.subject(), request.body()));
        return ResponseEntity.status(HttpStatus.CREATED).body(NotificationService.InboxItem.from(created));
    }

    public record SendRequest(
            @NotNull UUID userId,
            @NotNull Notification.Channel channel,
            @NotBlank @Size(max = 120) String templateKey,
            @NotBlank @Size(max = 512) String subject,
            @NotBlank String body) {
    }

    public record UnreadCount(long unread) {
    }

    public record MarkedRead(int marked) {
    }
}
