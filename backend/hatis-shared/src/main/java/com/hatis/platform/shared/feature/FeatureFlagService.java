package com.hatis.platform.shared.feature;

import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.CRC32;

/**
 * Server-side feature flags.
 *
 * <p>Every sensitive capability is gated here rather than in the frontend: a
 * customer must not be able to enable a paid or dangerous feature by editing
 * client state. Flags support per-tenant enablement, plan-based enablement,
 * percentage rollout and an emergency kill switch that takes effect within the
 * refresh interval.
 *
 * <p>Rollout is deterministic per tenant (CRC32 of {@code key + organizationId})
 * so a tenant never flickers in and out of a rollout between requests.
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentMap<String, Flag> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public FeatureFlagService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isEnabled(String key) {
        TenantContext context = TenantContextHolder.get();
        return isEnabled(key, context == null ? null : context.organizationId(), null);
    }

    public boolean isEnabled(String key, java.util.UUID organizationId, String planCode) {
        if (!loaded) {
            refresh();
        }
        Flag flag = cache.get(key);
        if (flag == null) {
            // Unknown flags are off. Adding a flag is a deliberate, reviewed act.
            return false;
        }
        if (!flag.enabled) {
            return false;
        }
        if (organizationId != null && flag.organizationIds.contains(organizationId.toString())) {
            return true;
        }
        if (planCode != null && flag.planCodes.contains(planCode)) {
            return true;
        }
        if (flag.rolloutPercent >= 100) {
            return true;
        }
        if (flag.rolloutPercent <= 0 || organizationId == null) {
            return false;
        }
        return bucket(key, organizationId.toString()) < flag.rolloutPercent;
    }

    /** Refreshes from the database. Also runs on a schedule so operators need no restart. */
    @Scheduled(fixedDelayString = "${hatis.feature-flags.refresh-interval:30s}")
    public void refresh() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select key, enabled, rollout_percent, plan_codes, organization_ids
                    from plat_feature_flags
                    """);
            ConcurrentMap<String, Flag> next = new ConcurrentHashMap<>();
            for (Map<String, Object> row : rows) {
                String key = String.valueOf(row.get("key"));
                next.put(key, new Flag(
                        key,
                        Boolean.TRUE.equals(row.get("enabled")),
                        ((Number) row.getOrDefault("rollout_percent", 0)).intValue(),
                        toStringList(row.get("plan_codes")),
                        toStringList(row.get("organization_ids"))));
            }
            cache.clear();
            cache.putAll(next);
            loaded = true;
        } catch (RuntimeException e) {
            // A flag read failure must not stop the platform; the last known good
            // set stays in effect and the failure is logged loudly.
            log.error("Unable to refresh feature flags: {}", e.getMessage());
        }
    }

    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.sql.Array array) {
            try {
                Object raw = array.getArray();
                if (raw instanceof Object[] objects) {
                    return java.util.Arrays.stream(objects).map(String::valueOf).toList();
                }
            } catch (Exception e) {
                return List.of();
            }
        }
        if (value instanceof String text) {
            String trimmed = text.replace("{", "").replace("}", "");
            return trimmed.isBlank() ? List.of() : List.of(trimmed.split(","));
        }
        return List.of();
    }

    private static int bucket(String key, String organizationId) {
        CRC32 crc = new CRC32();
        crc.update((key + ":" + organizationId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 100);
    }

    private record Flag(String key,
                        boolean enabled,
                        int rolloutPercent,
                        List<String> planCodes,
                        List<String> organizationIds) {
    }
}
