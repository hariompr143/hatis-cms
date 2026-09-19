package com.hatis.platform.infrastructure.adapter.secret;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.Secret;
import com.hatis.platform.shared.secret.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.RotateSecretRequest;

import java.util.List;

/**
 * AWS Secrets Manager adapter.
 *
 * <p>Credentials come from the ambient chain (IAM role, IRSA, instance profile) —
 * never from configuration, and never from Git. If the platform cannot reach the
 * manager, operations fail closed with {@code DependencyUnavailable}; the platform
 * does not fall back to a weaker store, because a silent fallback to plaintext is
 * exactly the failure this abstraction exists to prevent.
 *
 * <p>Values are never logged. Only the secret <em>name</em> appears in messages,
 * and only on failure paths that already omit material.
 */
@Component
@ConditionalOnProperty(name = "hatis.secrets.provider", havingValue = "aws")
public class AwsSecretsManagerSecretStore implements SecretStore {

    private final SecretsManagerClient client;
    private final String prefix;

    public AwsSecretsManagerSecretStore(SecretsManagerProperties properties) {
        this.client = SecretsManagerClient.builder().region(Region.of(properties.region())).build();
        this.prefix = properties.prefix();
    }

    @Override
    public String name() {
        return "aws-secrets-manager";
    }

    @Override
    public Secret get(String path) {
        try {
            String value = client.getSecretValue(GetSecretValueRequest.builder()
                            .secretId(qualify(path))
                            .build())
                    .secretString();
            return value == null ? Secret.empty() : Secret.of(value);
        } catch (ResourceNotFoundException e) {
            return Secret.empty();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("secrets-manager",
                    "Unable to read the secret at " + path);
        }
    }

    @Override
    public void put(String path, Secret value) {
        String qualified = qualify(path);
        try {
            client.createSecret(CreateSecretRequest.builder()
                    .name(qualified)
                    .secretString(value.reveal())
                    .build());
        } catch (ResourceExistsException e) {
            client.updateSecret(builder -> builder.secretId(qualified).secretString(value.reveal()));
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("secrets-manager",
                    "Unable to write the secret at " + path);
        }
    }

    @Override
    public void delete(String path) {
        try {
            // ForceDeleteWithoutRecovery is deliberate: a secret the platform no
            // longer references must not linger in a recovery window.
            client.deleteSecret(DeleteSecretRequest.builder()
                    .secretId(qualify(path))
                    .forceDeleteWithoutRecovery(true)
                    .build());
        } catch (ResourceNotFoundException e) {
            return;
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("secrets-manager",
                    "Unable to delete the secret at " + path);
        }
    }

    @Override
    public Secret rotate(String path) {
        try {
            client.rotateSecret(RotateSecretRequest.builder().secretId(qualify(path)).build());
            return get(path);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("secrets-manager",
                    "Unable to rotate the secret at " + path);
        }
    }

    @Override
    public List<String> list(String searchPrefix) {
        try {
            return client.listSecrets(ListSecretsRequest.builder()
                            .filters(filter -> filter
                                    .key("name")
                                    .values(qualify(searchPrefix)))
                            .build())
                    .secretList()
                    .stream()
                    .map(summary -> stripPrefix(summary.name()))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("secrets-manager",
                    "Unable to list secrets");
        }
    }

    private String qualify(String path) {
        return prefix == null || prefix.isBlank() ? path : prefix + "/" + path;
    }

    private String stripPrefix(String name) {
        return prefix != null && !prefix.isBlank() && name.startsWith(prefix + "/")
                ? name.substring(prefix.length() + 1)
                : name;
    }

    public record SecretsManagerProperties(String region, String prefix) {
    }
}
