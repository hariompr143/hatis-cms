package com.hatis.platform.billing.application;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan-driven quotas and entitlements.
 *
 * <p>This is the only place that turns a plan into a number. Nothing else in the
 * platform contains a plan check, which is what makes product tiers a
 * configuration change rather than a code change — and what stops billing logic
 * from leaking into deployment or infrastructure code.
 *
 * <p>Limits are read from {@code bill_plan_limits} and compared against
 * {@code plat_usage_counters}. A missing limit row means "not capped by this
 * plan", not "deny": failing open on an uncapped key is the behaviour a customer
 * expects, and every capped key is seeded explicitly per plan.
 */
@Service
public class PlanQuotaService implements QuotaService {

    private final JdbcTemplate jdbcTemplate;

    public PlanQuotaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Quota limit(UUID organizationId, QuotaKey key) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select l.limit_value, l.unlimited
                from bill_plan_limits l
                join bill_plans p on p.id = l.plan_id
                join org_organizations o on o.plan_code = p.code
                where o.id = ? and l.limit_key = ?
                """, organizationId, key.wireValue());
        if (rows.isEmpty()) {
            return new Quota(key, Quota.UNLIMITED, true);
        }
        Map<String, Object> row = rows.get(0);
        boolean unlimited = Boolean.TRUE.equals(row.get("unlimited"));
        long value = row.get("limit_value") == null
                ? Quota.UNLIMITED
                : ((Number) row.get("limit_value")).longValue();
        return new Quota(key, unlimited ? Quota.UNLIMITED : value, unlimited);
    }

    @Override
    public long usage(UUID organizationId, QuotaKey key) {
        List<Long> values = jdbcTemplate.queryForList(
                "select quantity from plat_usage_counters where organization_id = ? and quota_key = ?",
                Long.class, organizationId, key.wireValue());
        return values.isEmpty() ? 0L : values.get(0);
    }

    @Override
    public void check(UUID organizationId, QuotaKey key, long additional) {
        Quota quota = limit(organizationId, key);
        long current = usage(organizationId, key);
        if (!quota.allows(additional, current)) {
            String limit = quota.unlimited() ? "unlimited" : String.valueOf(quota.limit());
            throw new PlatformExceptions.QuotaExceeded(key.wireValue(), limit, current + additional);
        }
    }

    @Override
    @Transactional
    public void record(UUID organizationId, QuotaKey key, long delta) {
        int updated = jdbcTemplate.update("""
                insert into plat_usage_counters (id, organization_id, quota_key, quantity, updated_at)
                values (gen_random_uuid(), ?, ?, ?, now())
                on conflict (organization_id, quota_key)
                do update set quantity = greatest(0, plat_usage_counters.quantity + excluded.quantity),
                              updated_at = now()
                """, organizationId, key.wireValue(), delta);
        if (updated == 0) {
            // A failed counter update must not silently let a tenant exceed a plan.
            throw new PlatformExceptions.OperationFailed("Unable to record usage for " + key.wireValue());
        }
    }

    /** Entitlement lookup used to gate whole capabilities per plan. */
    public boolean hasEntitlement(UUID organizationId, String code) {
        List<Boolean> values = jdbcTemplate.queryForList("""
                select e.enabled
                from bill_entitlement_codes e
                join bill_plans p on p.id = e.plan_id
                join org_organizations o on o.plan_code = p.code
                where o.id = ? and e.code = ?
                """, Boolean.class, organizationId, code);
        return !values.isEmpty() && Boolean.TRUE.equals(values.get(0));
    }

    /** Throws unless the plan includes the capability. */
    public void requireEntitlement(UUID organizationId, String code) {
        if (!hasEntitlement(organizationId, code)) {
            throw new PlatformExceptions.EntitlementMissing(code);
        }
    }

    /** All entitlements for a tenant, for the console to render feature gating. */
    public Map<String, String> entitlements(UUID organizationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select e.code, e.enabled, e.value
                from bill_entitlement_codes e
                join bill_plans p on p.id = e.plan_id
                join org_organizations o on o.plan_code = p.code
                where o.id = ?
                order by e.code
                """, organizationId);
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
            Object value = row.get("value");
            result.put(String.valueOf(row.get("code")),
                    !enabled ? "false" : (value == null ? "true" : String.valueOf(value)));
        }
        return result;
    }
}
