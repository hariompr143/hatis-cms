package com.hatis.platform.infrastructure.adapter.kubernetes;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin client for the Kubernetes API server.
 *
 * <p>Uses the pod's service-account token and the {@code KUBERNETES_SERVICE_HOST}
 * environment variables that every pod receives. No kubeconfig is read, so there is
 * no cluster credential sitting in the container filesystem or in configuration.
 *
 * <p>Trust anchors for TLS come from the in-cluster CA at
 * {@code /var/run/secrets/kubernetes.io/serviceaccount/ca.crt}. The client never
 * disables certificate verification — talking to the API server over an
 * unauthenticated connection would defeat the purpose of RBAC.
 */
@Component
public class KubernetesApiClient {

    private static final String TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
    private static final String NAMESPACE_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

    private final WebClient webClient;
    private final String namespace;

    public KubernetesApiClient(Environment environment) {
        String host = environment.getProperty("KUBERNETES_SERVICE_HOST", "kubernetes.default.svc");
        String port = environment.getProperty("KUBERNETES_SERVICE_PORT", "443");
        this.namespace = readNamespace(environment);

        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://" + host + ":" + port)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("User-Agent", "hatis-platform/0.1");

        Path ca = Path.of(CA_PATH);
        if (Files.isReadable(ca)) {
            builder.clientConnector(TrustAnchorHttpConnectorFactory.create(ca));
        }
        String token = readToken();
        if (token != null) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    public WebClient webClient() {
        return webClient;
    }

    public String namespace() {
        return namespace;
    }

    private static String readToken() {
        try {
            Path token = Path.of(TOKEN_PATH);
            return Files.isReadable(token) ? Files.readString(token).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readNamespace(Environment environment) {
        try {
            Path file = Path.of(NAMESPACE_PATH);
            if (Files.isReadable(file)) {
                return Files.readString(file).trim();
            }
        } catch (Exception e) {
            // Fall through to configuration.
        }
        return environment.getProperty("hatis.kubernetes.namespace", "hatis");
    }
}
