package com.hatis.platform.shared.quota;

import java.util.UUID;

/**
 * Quota enforcement port.
 *
 * <p>Implemented by the {@code billing} context from plan entitlements. Every
 * context that creates a billable resource consults it <em>before</em> doing the
 * work, so a customer over quota is rejected up front instead of leaving
 * half-provisioned infrastructure behind.
 *
 * <p>Limits are always read from the plan, never from the request.
 */
public interface QuotaService {

    /** Returns the configured limit, or {@link Quota#UNLIMITED} when the plan does not cap it. */
    Quota limit(UUID organizationId, QuotaKey key);

    /** Current usage for the given key. */
    long usage(UUID organizationId, QuotaKey key);

    /** Throws {@code QuotaExceeded} when {@code additional} would breach the limit. */
    void check(UUID organizationId, QuotaKey key, long additional);

    /** Increments a usage counter after a resource was created. */
    void record(UUID organizationId, QuotaKey key, long delta);

    record Quota(QuotaKey key, long limit, boolean unlimited) {

        public static final long UNLIMITED = Long.MAX_VALUE;

        public boolean allows(long additional, long currentUsage) {
            return unlimited || currentUsage + additional <= limit;
        }
    }
}
