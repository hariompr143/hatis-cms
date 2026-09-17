package com.hatis.platform.identity.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hatis.platform.identity.adapter.persistence.UserRepository;
import com.hatis.platform.organization.domain.Membership;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.EncryptionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the data encryption key that protects a user's secrets.
 *
 * <p>The wrapped DEK lives on the tenant's organization row, so rotating or
 * revoking a tenant's key is a single row update — and revoking it
 * cryptographically erases that tenant's protected values.
 *
 * <p>This class is the only place that reads key material on behalf of the
 * identity context; it joins through the organization table with plain SQL to
 * avoid a compile-time dependency on the organization context's entities.
 */
@Service
public class TenantKeyService {

    private static final String DEFAULT_KEY_ID = "default";

    private final JdbcTemplate jdbcTemplate;
    private final EncryptionService encryption;
    private final UserRepository users;

    public TenantKeyService(JdbcTemplate jdbcTemplate,
                            EncryptionService encryption,
                            UserRepository users) {
        this.jdbcTemplate = jdbcTemplate;
        this.encryption = encryption;
        this.users = users;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public TenantKey keyFor(UUID userId) {
        UUID organizationId = organizationFor(userId);
        if (organizationId == null) {
            // A user with no organization yet (mid sign-up) uses the platform key.
            return new TenantKey(platformWrappedKey(), DEFAULT_KEY_ID);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select encryption_key_id, encryption_key_wrapped from org_organizations where id = ?",
                organizationId);
        if (rows.isEmpty()) {
            throw new PlatformExceptions.NotFound("Organization", organizationId);
        }
        String keyId = (String) rows.get(0).get("encryption_key_id");
        String wrapped = (String) rows.get(0).get("encryption_key_wrapped");
        if (keyId == null || wrapped == null) {
            return new TenantKey(platformWrappedKey(), DEFAULT_KEY_ID);
        }
        return new TenantKey(wrapped, keyId);
    }

    private UUID organizationFor(UUID userId) {
        List<UUID> ids = jdbcTemplate.queryForList(
                "select organization_id from org_memberships where user_id = ? and status = ? order by created_at",
                UUID.class, userId, Membership.Status.ACTIVE.name());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Creates (once) and returns the platform-level key used before a tenant key exists. */
    private synchronized String platformWrappedKey() {
        List<String> existing = jdbcTemplate.queryForList(
                "select wrapped_key from plat_encryption_keys where key_id = ?", String.class, DEFAULT_KEY_ID);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String wrapped = encryption.wrapDataKey(DEFAULT_KEY_ID, encryption.generateDataKey());
        jdbcTemplate.update(
                "insert into plat_encryption_keys (id, key_id, wrapped_key, algorithm) values (?, ?, ?, ?)",
                com.hatis.platform.shared.id.Identifiers.newId(), DEFAULT_KEY_ID, wrapped, "AES-256-GCM");
        return wrapped;
    }

    public record TenantKey(String wrappedDek, String keyId) {
    }
}
