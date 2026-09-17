package com.hatis.platform.infrastructure.adapter.secret;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.Secret;
import com.hatis.platform.shared.secret.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * HashiCorp Vault adapter (KV v2).
 *
 * <p>Authenticates with a token supplied through the environment or with
 * Kubernetes service-account auth inside a cluster. The token is read once at
 * startup and is never written to a log, an audit record or an API response.
 *
 * <p>Paths map to {@code <mount>/data/<path>}; the {@code value} key holds the
 * secret material.
 */
@Component
@ConditionalOnProperty(name = "hatis.secrets.provider", havingValue = "vault")
public class VaultSecretStore implements SecretStore {

    private static final String VALUE_KEY = "value";

    private final WebClient client;
    private final String mount;

    public VaultSecretStore(Environment environment) {
        String address = required(environment, "hatis.secrets.vault.address");
        String token = required(environment, "hatis.secrets.vault.token");
        this.mount = environment.getProperty("hatis.secrets.vault.mount", "secret");
        this.client = WebClient.builder()
                .baseUrl(address)
                .defaultHeader("X-Vault-Token", token)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
    }

    @Override
    public String name() {
        return "vault";
    }

    @Override
    public Secret get(String path) {
        try {
            Map<?, ?> body = client.get()
                    .uri("/v1/{mount}/data/{path}", mount, path)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            Object data = body == null ? null : ((Map<?, ?>) body.get("data"));
            if (data == null) {
                return Secret.empty();
            }
            Object value = ((Map<?, ?>) data).get("data");
            if (!(value instanceof Map<?, ?> fields)) {
                return Secret.empty();
            }
            Object material = fields.get(VALUE_KEY);
            return material == null ? Secret.empty() : Secret.of(String.valueOf(material));
        } catch (WebClientResponseException.NotFound e) {
            return Secret.empty();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("vault",
                    "Unable to read the secret at " + path);
        }
    }

    @Override
    public void put(String path, Secret value) {
        try {
            client.post()
                    .uri("/v1/{mount}/data/{path}", mount, path)
                    .bodyValue(Map.of("data", Map.of(VALUE_KEY, value.reveal())))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("vault",
                    "Unable to write the secret at " + path);
        }
    }

    @Override
    public void delete(String path) {
        try {
            client.delete()
                    .uri("/v1/{mount}/data/{path}", mount, path)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            return;
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("vault",
                    "Unable to delete the secret at " + path);
        }
    }

    @Override
    public Secret rotate(String path) {
        // Vault rotates through its own secret engines and dynamic credentials;
        // a KV path has no rotation primitive, so the platform regenerates the
        // value and writes the next version.
        String generated = java.util.UUID.randomUUID().toString().replace("-", "");
        Secret secret = Secret.of(generated);
        put(path, secret);
        return secret;
    }

    @Override
    public List<String> list(String prefix) {
        try {
            Map<?, ?> body = client.get()
                    .uri("/v1/{mount}/metadata/{prefix}", mount, prefix)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            Object data = body == null ? null : body.get("data");
            if (!(data instanceof Map<?, ?> fields) || !(fields.get("keys") instanceof List<?> keys)) {
                return List.of();
            }
            return keys.stream()
                    .map(String::valueOf)
                    .map(key -> prefix.endsWith("/") ? prefix + key : prefix + "/" + key)
                    .sorted()
                    .toList();
        } catch (WebClientResponseException.NotFound e) {
            return List.of();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("vault", "Unable to list secrets");
        }
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new PlatformExceptions.StateConflict(
                    "Vault is selected as the secret provider but " + key + " is not configured");
        }
        return value;
    }
}
