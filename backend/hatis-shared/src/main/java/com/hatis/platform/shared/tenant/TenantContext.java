package com.hatis.platform.shared.tenant;

import com.hatis.platform.shared.error.PlatformExceptions;

import java.util.Objects;
import java.util.UUID;

/**
 * The resolved request context: which tenant, which principal, which scope.
 *
 * <p>Populated <strong>only</strong> from verified material (a validated JWT or a
 * server-side API key lookup). A tenant identifier supplied by the client in a
 * header, query parameter or body is never accepted.
 */
public record TenantContext(
        UUID organizationId,
        UUID principalId,
        PrincipalType principalType,
        UUID projectId,
        UUID environmentId,
        String correlationId,
        boolean platformPrincipal) {

    public enum PrincipalType {
        USER,
        SERVICE_ACCOUNT,
        API_KEY,
        PLATFORM_OPERATOR,
        SYSTEM
    }

    public static TenantContext of(UUID organizationId, UUID principalId, PrincipalType type) {
        return new TenantContext(organizationId, principalId, type, null, null, null, false);
    }

    public static TenantContext platform(UUID principalId, String correlationId) {
        return new TenantContext(null, principalId, PrincipalType.PLATFORM_OPERATOR, null, null, correlationId, true);
    }

    public static TenantContext system(String correlationId) {
        return new TenantContext(null, null, PrincipalType.SYSTEM, null, null, correlationId, true);
    }

    public TenantContext withProject(UUID newProjectId) {
        return new TenantContext(organizationId, principalId, principalType, newProjectId, environmentId,
                correlationId, platformPrincipal);
    }

    public TenantContext withEnvironment(UUID newEnvironmentId) {
        return new TenantContext(organizationId, principalId, principalType, projectId, newEnvironmentId,
                correlationId, platformPrincipal);
    }

    /** Fails unless the given organization matches this context. Path segments are verified, never trusted. */
    public void requireOrganization(UUID candidate) {
        if (!Objects.equals(organizationId, candidate)) {
            throw new PlatformExceptions.TenantMismatch("organization");
        }
    }

    public UUID requireOrganizationId() {
        if (organizationId == null) {
            throw new PlatformExceptions.Forbidden("This operation requires a tenant context");
        }
        return organizationId;
    }
}
