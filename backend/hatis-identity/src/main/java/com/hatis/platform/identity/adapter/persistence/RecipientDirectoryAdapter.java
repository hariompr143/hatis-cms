package com.hatis.platform.identity.adapter.persistence;

import com.hatis.platform.identity.domain.User;
import com.hatis.platform.notification.port.out.RecipientDirectory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements the notification module's recipient lookup against the user records.
 *
 * <p>The adapter lives here because identity owns {@code idp_users}; the port it implements
 * lives in notification because notification is the side that needs an address. That is the
 * direction a port-and-adapter pair takes: the caller declares what it needs, the owner of the
 * data provides it, and neither reaches into the other's tables.
 *
 * <p>Two rules are enforced here rather than in a caller:
 *
 * <ul>
 *   <li>A soft-deleted account has no address. Application exclusions are a matter of record,
 *       and emailing an account that has been deleted — possibly at the account holder's own
 *       request — is exactly the mistake a lookup like this exists to prevent.</li>
 *   <li>A user with no address cannot be looked up at all, which surfaces as an empty result
 *       rather than an exception so that the notification is recorded as failed.</li>
 * </ul>
 */
@Component
public class RecipientDirectoryAdapter implements RecipientDirectory {

    private final UserRepository users;

    public RecipientDirectoryAdapter(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<String> emailOf(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return users.findById(userId)
                .filter(user -> user.getDeletedAt() == null)
                .map(User::getEmail);
    }
}
