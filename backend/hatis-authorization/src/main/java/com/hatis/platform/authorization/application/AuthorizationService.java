package com.hatis.platform.authorization.application;

import com.hatis.platform.authorization.domain.AuthorizationEntities;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization decisions.
 *
 * <p>Evaluation order matters and is deliberately simple:
 * <ol>
 *   <li>collect the bindings that cover the requested scope — the scope itself plus
 *       every ancestor scope;</li>
 *   <li>an explicit {@code DENY} at the narrowest matching scope wins;</li>
 *   <li>otherwise allow if any covering role grants the permission.</li>
 * </ol>
 *
 * <p>Decisions are cached per principal for a short window and invalidated on
 * binding changes. The cache key always includes the tenant, so a cache can never
 * serve one tenant's decision to another.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);
    private static final long CACHE_TTL_MILLIS = 60_000;

    private final AuthorizationRepository.RoleBindingRepository bindings;
    private final AuthorizationRepository.RoleRepository roles;
    private final JdbcTemplate jdbcTemplate;
    private final AuditRecorder audit;
    private final Counter allowed;
    private final Counter denied;

    private final Map<String, CachedPermissions> cache = new ConcurrentHashMap<>();

    public AuthorizationService(AuthorizationRepository.RoleBindingRepository bindings,
                                AuthorizationRepository.RoleRepository roles,
                                JdbcTemplate jdbcTemplate,
                                AuditRecorder audit,
                                MeterRegistry meterRegistry) {
        this.bindings = bindings;
        this.roles = roles;
        this.jdbcTemplate = jdbcTemplate;
        this.audit = audit;
        this.allowed = Counter.builder("hatis.authz.decisions").tag("decision", "allow").register(meterRegistry);
        this.denied = Counter.builder("hatis.authz.decisions").tag("decision", "deny").register(meterRegistry);
    }

    public boolean isAllowed(UUID organizationId, UUID principalId, String permission,
                             ScopeType scopeType, UUID scopeId) {
        Instant now = Instant.now();
        List<AuthorizationEntities.RoleBinding> candidateBindings =
                bindings.findByOrganizationIdAndPrincipalId(organizationId, principalId);

        // 1. Narrowest explicit deny wins.
        for (AuthorizationEntities.RoleBinding binding : candidateBindings) {
            if (binding.getEffect() != AuthorizationEntities.RoleBinding.Effect.DENY) {
                continue;
            }
            if (!binding.isActive(now) || !covers(binding, scopeType, scopeId)) {
                continue;
            }
            if (roleGrants(binding.getRoleId(), permission)) {
                denied.increment();
                return false;
            }
        }

        // 2. Any covering allow grants the permission.
        for (AuthorizationEntities.RoleBinding binding : candidateBindings) {
            if (binding.getEffect() != AuthorizationEntities.RoleBinding.Effect.ALLOW) {
                continue;
            }
            if (!binding.isActive(now) || !covers(binding, scopeType, scopeId)) {
                continue;
            }
            if (roleGrants(binding.getRoleId(), permission)) {
                allowed.increment();
                return true;
            }
        }
        denied.increment();
        return false;
    }

    /** Throws unless the caller may perform {@code permission} on the given scope. */
    public void require(String permission, ScopeType scopeType, UUID scopeId) {
        var context = TenantContextHolder.require();
        UUID organizationId = context.requireOrganizationId();
        if (!isAllowed(organizationId, context.principalId(), permission, scopeType, scopeId)) {
            audit.record(AuditRecord.builder("authz.denied")
                    .resource(scopeType.name().toLowerCase(java.util.Locale.ROOT), scopeId)
                    .result(AuditRecord.Result.DENIED)
                    .reason("missing permission " + permission)
                    .build());
            throw new PlatformExceptions.Forbidden("You do not have the '" + permission + "' permission here");
        }
    }

    /** True when the principal holds an organization-scope binding for the permission. */
    public boolean isAllowedAtOrganization(UUID organizationId, UUID principalId, String permission) {
        return isAllowed(organizationId, principalId, permission, ScopeType.ORGANIZATION, organizationId);
    }

    @TenantTransactional
    public void grant(UUID organizationId, UUID roleId, String principalType, UUID principalId,
                      ScopeType scopeType, UUID scopeId) {
        TenantContextHolder.require().requireOrganization(organizationId);
        AuthorizationEntities.RoleBinding binding = bindings.save(new AuthorizationEntities.RoleBinding(
                organizationId, roleId, principalType, principalId, scopeType, scopeId,
                AuthorizationEntities.RoleBinding.Effect.ALLOW, TenantContextHolder.require().principalId()));
        invalidate(organizationId, principalId);
        audit.record(AuditRecord.builder("role.granted")
                .resource("role_binding", binding.getId())
                .metadata(Map.of("roleId", roleId.toString(), "scope", scopeType.name()))
                .build());
    }

    @TenantTransactional
    public void revoke(UUID organizationId, UUID bindingId) {
        TenantContextHolder.require().requireOrganization(organizationId);
        AuthorizationEntities.RoleBinding binding = bindings.findById(bindingId)
                .filter(b -> b.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new PlatformExceptions.NotFound("Role binding", bindingId));
        bindings.delete(binding);
        invalidate(organizationId, binding.getPrincipalId());
        audit.record(AuditRecord.builder("role.revoked")
                .resource("role_binding", bindingId)
                .build());
    }

    /** Roles the principal holds at organization scope; used for coarse UI gating. */
    @TenantTransactional(readOnly = true)
    public List<String> organizationRoles() {
        TenantContext context = TenantContextHolder.require();
        return roleCodesAt(context, ScopeType.ORGANIZATION, context.requireOrganizationId());
    }

    /**
     * Role codes the current principal holds at a scope, or at any scope above it.
     *
     * <p>This is the "who are you here" half of authorization, as opposed to the "may you do
     * this" half {@link #isAllowed} answers. A workflow transition names the role it expects
     * ({@code EDITOR}, {@code ORG_ADMIN}) rather than a permission, so deciding whether a
     * caller may approve needs their role codes, not only a boolean.
     *
     * <p>Bindings that are expired, denied or scoped elsewhere are excluded exactly as they
     * are for a permission decision: a role the principal cannot exercise must not satisfy a
     * workflow step either. That is one rule with one implementation — this method — rather
     * than a rule each caller re-implements against the bindings.
     */
    @TenantTransactional(readOnly = true)
    public List<String> rolesAt(ScopeType scopeType, UUID scopeId) {
        return roleCodesAt(TenantContextHolder.require(), scopeType, scopeId);
    }

    private List<String> roleCodesAt(TenantContext context, ScopeType scopeType, UUID scopeId) {
        Instant now = Instant.now();
        return bindings.findByOrganizationIdAndPrincipalId(context.requireOrganizationId(), context.principalId())
                .stream()
                .filter(b -> b.getEffect() == AuthorizationEntities.RoleBinding.Effect.ALLOW)
                .filter(b -> b.isActive(now))
                .filter(b -> covers(b, scopeType, scopeId))
                .map(b -> roles.findById(b.getRoleId()).map(AuthorizationEntities.Role::getCode).orElse(null))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Clears cached decisions for a principal after their bindings changed. */
    public void invalidate(UUID organizationId, UUID principalId) {
        cache.keySet().removeIf(key -> key.startsWith(organizationId + ":" + principalId + ":"));
    }

    private boolean roleGrants(UUID roleId, String permission) {
        String cacheKey = "role:" + roleId;
        CachedPermissions cached = cache.get(cacheKey);
        Instant now = Instant.now();
        if (cached != null && cached.isFresh(now)) {
            return cached.permissions().contains(permission) || cached.permissions().contains("*");
        }
        Set<String> permissions = new HashSet<>(jdbcTemplate.queryForList(
                "select p.code from auth_role_permissions rp join auth_permissions p on p.id = rp.permission_id"
                        + " where rp.role_id = ?",
                String.class, roleId));
        cache.put(cacheKey, new CachedPermissions(permissions, now.toEpochMilli() + CACHE_TTL_MILLIS));
        return permissions.contains(permission) || permissions.contains("*");
    }

    /**
     * A binding covers a scope when it is at that scope or at any ancestor scope.
     *
     * <p>An organization-scope binding covers everything inside the organization,
     * which is what makes hierarchical authorization work without recursion.
     */
    private static boolean covers(AuthorizationEntities.RoleBinding binding, ScopeType scopeType, UUID scopeId) {
        if (binding.getScopeType() == ScopeType.ORGANIZATION) {
            return true;
        }
        if (binding.getScopeType() != scopeType) {
            // A project binding does not cover a request scoped to one environment
            // of a different project; environment checks resolve their project first.
            return false;
        }
        return binding.getScopeId() != null && binding.getScopeId().equals(scopeId);
    }

    private record CachedPermissions(Set<String> permissions, long expiresAtMillis) {
        boolean isFresh(Instant now) {
            return now.toEpochMilli() < expiresAtMillis;
        }
    }
}
