package com.hatis.platform.security;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.UUID;

/**
 * Bridges Spring Security's {@code hasPermission} to the platform's authorization
 * service.
 *
 * <p>Used as {@code @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'project:read')")}.
 * This is a fast-fail convenience: the authoritative check is inside the
 * application service, which is also reachable from other contexts and from
 * background jobs where no HTTP authentication object exists.
 */
@Component
public class HatisPermissionEvaluator implements PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(HatisPermissionEvaluator.class);

    private final AuthorizationService authorization;

    public HatisPermissionEvaluator(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        return hasPermission(authentication, null, "ORGANIZATION", permission);
    }

    @Override
    public boolean hasPermission(Authentication authentication,
                                 Serializable targetId,
                                 String targetType,
                                 Object permission) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.organizationId() == null) {
            return false;
        }
        if (permission == null) {
            return false;
        }
        UUID scopeId = toUuid(targetId);
        AuthorizationService.ScopeType scopeType = toScopeType(targetType);

        // The path segment is verified against the authenticated tenant before it
        // is used as an authorization scope. A caller cannot widen their scope by
        // naming someone else's resource.
        if (scopeType == AuthorizationService.ScopeType.ORGANIZATION
                && scopeId != null
                && !scopeId.equals(context.organizationId())) {
            log.debug("Rejected scope {} that does not match tenant {}", scopeId, context.organizationId());
            return false;
        }
        return authorization.isAllowed(
                context.organizationId(),
                context.principalId(),
                String.valueOf(permission),
                scopeType,
                scopeId == null ? context.organizationId() : scopeId);
    }

    private static UUID toUuid(Serializable value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AuthorizationService.ScopeType toScopeType(String targetType) {
        if (targetType == null) {
            return AuthorizationService.ScopeType.ORGANIZATION;
        }
        try {
            return AuthorizationService.ScopeType.valueOf(targetType.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AuthorizationService.ScopeType.ORGANIZATION;
        }
    }
}
