package com.hatis.platform.identity.adapter.persistence;
import com.hatis.platform.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * User persistence.
 *
 * <p>{@code findByEmail} is intentionally the only unscoped lookup in the whole
 * platform: authentication has to find a user before a tenant is known. Everything
 * after sign-in is tenant-scoped.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
