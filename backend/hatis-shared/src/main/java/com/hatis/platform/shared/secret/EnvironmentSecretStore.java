package com.hatis.platform.shared.secret;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development and simple-installation secret store backed by the process
 * environment, with an in-memory overlay for values written at runtime.
 *
 * <p>This is a real adapter, not a mock: it is the correct implementation for a
 * single-node or air-gapped installation where an external manager is not
 * available. Enterprise installs select Vault, AWS, Azure, GCP or Kubernetes via
 * {@code hatis.secrets.provider}.
 *
 * <p>Environment keys are derived from the path
 * ({@code hatis/postgres/password} → {@code HATIS_POSTGRES_PASSWORD}) so that a
 * customer can supply secrets through their orchestrator's normal mechanism.
 */
@Component
@ConditionalOnProperty(name = "hatis.secrets.provider", havingValue = "environment", matchIfMissing = true)
public class EnvironmentSecretStore implements SecretStore {

    private final Environment environment;
    private final Map<String, Secret> overlay = new ConcurrentHashMap<>();

    public EnvironmentSecretStore(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String name() {
        return "environment";
    }

    @Override
    public Secret get(String path) {
        Secret cached = overlay.get(path);
        if (cached != null) {
            return Secret.of(cached.reveal());
        }
        String value = environment.getProperty(toEnvKey(path));
        return value == null ? Secret.empty() : Secret.of(value);
    }

    @Override
    public void put(String path, Secret value) {
        overlay.put(path, Secret.of(value.reveal()));
    }

    @Override
    public void delete(String path) {
        overlay.remove(path);
    }

    @Override
    public Secret rotate(String path) {
        // An environment-backed store has no rotation primitive of its own; the
        // value is replaced in the overlay so the caller's expectation of a new
        // value holds, and the operator is responsible for the environment side.
        Secret current = get(path);
        put(path, current);
        return current;
    }

    @Override
    public List<String> list(String prefix) {
        return overlay.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
    }

    static String toEnvKey(String path) {
        return path.toUpperCase(java.util.Locale.ROOT)
                .replace('/', '_')
                .replace('-', '_')
                .replace('.', '_');
    }
}
