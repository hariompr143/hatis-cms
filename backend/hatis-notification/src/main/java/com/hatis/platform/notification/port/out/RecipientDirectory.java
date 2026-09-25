package com.hatis.platform.notification.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a principal to the address a message should be sent to.
 *
 * <p>Defined here and implemented by the identity context, which owns the user records.
 * Notification must not read {@code idp_users}: a context that reaches into another
 * context's tables cannot be extracted, and the email column in particular is
 * {@code citext} with a case-insensitive unique index precisely because identity owns the
 * rules about how addresses are compared and stored.
 *
 * <p>{@link Optional} rather than an exception for a missing user: an account deleted
 * between the moment a notification was queued and the moment it was dispatched is an
 * ordinary race, and the right outcome is a notification that fails rather than a request
 * that breaks.
 */
public interface RecipientDirectory {

    Optional<String> emailOf(UUID userId);
}
