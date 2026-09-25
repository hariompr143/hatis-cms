package com.hatis.platform.notification.adapter.persistence;

import com.hatis.platform.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbox queries.
 *
 * <p>Every one of them names the user as well as the organization. A notification is the
 * most personal row the platform stores — it says what someone was told and when — so the
 * predicate that restricts a read to its owner belongs in the query rather than in a filter
 * someone might forget to apply.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndOrganizationIdAndUserId(UUID id, UUID organizationId, UUID userId);

    Page<Notification> findByOrganizationIdAndUserIdOrderByCreatedAtDesc(UUID organizationId,
                                                                         UUID userId,
                                                                         Pageable pageable);

    Page<Notification> findByOrganizationIdAndUserIdAndStatusInOrderByCreatedAtDesc(UUID organizationId,
                                                                                    UUID userId,
                                                                                    Collection<Notification.Status> statuses,
                                                                                    Pageable pageable);

    long countByOrganizationIdAndUserIdAndStatus(UUID organizationId, UUID userId, Notification.Status status);

    /**
     * Marks every delivered notification read, in one statement.
     *
     * <p>A bulk update rather than a load-and-save loop: an inbox with thousands of rows
     * would otherwise be thousands of {@code update} statements, each with its own optimistic
     * lock check, to reach a state that no reader can distinguish from the single statement.
     * The {@code updated_at} trigger on {@code ntf_notifications} still fires, so the bulk path
     * does not leave the timestamps behind the row's contents.
     */
    @Modifying
    @Query("""
            update Notification n
            set n.status = :read, n.readAt = :now
            where n.organizationId = :organizationId
              and n.userId = :userId
              and n.status = :sent
            """)
    int markAllRead(@Param("organizationId") UUID organizationId,
                    @Param("userId") UUID userId,
                    @Param("read") Notification.Status read,
                    @Param("sent") Notification.Status sent,
                    @Param("now") Instant now);
}
