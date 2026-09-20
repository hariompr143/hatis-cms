package com.hatis.platform.shared.secret;

import java.time.Duration;
import java.util.List;

/**
 * Outbound port to an enterprise secret manager.
 *
 * <p>Adapters: environment (development and simple installs), HashiCorp Vault,
 * AWS Secrets Manager, Azure Key Vault, Google Secret Manager and Kubernetes
 * Secrets. The platform never requires a specific provider, and never stores a
 * provider credential in Git.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code get} returns a value that the caller must destroy after use.</li>
 *   <li>Implementations must never log the secret or the full path of a
 *       credential.</li>
 *   <li>Failures surface as {@code DependencyUnavailable}; callers fail closed.</li>
 * </ul>
 */
public interface SecretStore {

    String name();

    Secret get(String path);

    void put(String path, Secret value);

    void delete(String path);

    /** Rotates the value at {@code path}; returns the new value. */
    Secret rotate(String path);

    /** Paths that exist. Used by reconciliation jobs; must not return values. */
    List<String> list(String prefix);

    /** Suggested rotation interval advertised to operators. */
    default Duration rotationInterval() {
        return Duration.ofDays(90);
    }
}
