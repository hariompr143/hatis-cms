package com.hatis.platform.deployment.application;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Maps a tenant and an environment to a Kubernetes namespace.
 *
 * <p>Centralised because the namespace is a security boundary: it is where
 * {@code ResourceQuota}, {@code NetworkPolicy} and RBAC apply, and it is the first
 * thing a compromised workload is confined by. Deriving it here means no caller can
 * supply an arbitrary namespace and land in someone else's — or in the platform's
 * own.
 */
@Component
public class NamespaceStrategy {

    private static final String PREFIX = "tenant-";
    private static final int MAX_LENGTH = 63;

    /**
     * One namespace per tenant environment.
     *
     * <p>Per-environment rather than per-tenant so that a staging workload cannot
     * reach a production database service in the same namespace, and so that
     * deleting an environment removes everything in it.
     */
    public String namespaceFor(UUID organizationId, UUID environmentId) {
        if (organizationId == null || environmentId == null) {
            throw new PlatformExceptions.Validation(
                    "organizationId and environmentId are required", java.util.Map.of());
        }
        String namespace = PREFIX + compact(organizationId) + "-" + compact(environmentId);
        return namespace.length() > MAX_LENGTH ? namespace.substring(0, MAX_LENGTH) : namespace;
    }

    /** The prefix a namespace must have to be accepted by the provider. */
    public String prefix() {
        return PREFIX;
    }

    /**
     * First 12 hex characters of the identifier.
     *
     * <p>Short enough to keep the name under the 63-character DNS label limit for
     * both identifiers, and still 48 bits — collisions would be found by the
     * namespace-already-exists check rather than silently sharing a namespace.
     */
    private static String compact(UUID value) {
        return value.toString().replace("-", "").substring(0, 12);
    }
}
