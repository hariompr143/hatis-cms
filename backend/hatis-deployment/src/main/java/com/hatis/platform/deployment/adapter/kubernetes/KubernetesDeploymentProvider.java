package com.hatis.platform.deployment.adapter.kubernetes;

import com.hatis.platform.deployment.port.out.DeploymentProvider;
import com.hatis.platform.infrastructure.adapter.kubernetes.KubernetesApiClient;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kubernetes {@link DeploymentProvider} talking to the API server directly.
 *
 * <p>Deliberately not a Kubernetes operator. An operator is the right answer once
 * the deployment model is stable; building one first would freeze a model that has
 * not met a real workload yet. What is here is the same reconciliation shape —
 * desired state in, observed state out — so the step up to an operator later is a
 * controller, not a redesign.
 *
 * <p>Namespace strategy: one namespace per tenant. That is what makes
 * {@code ResourceQuota}, {@code NetworkPolicy} and RBAC meaningful per customer,
 * and it is the boundary a compromised workload hits first.
 */
@Component
@ConditionalOnProperty(name = "hatis.deployment.provider", havingValue = "kubernetes", matchIfMissing = true)
public class KubernetesDeploymentProvider implements DeploymentProvider {

    private static final Logger log = LoggerFactory.getLogger(KubernetesDeploymentProvider.class);
    private static final String APPS_V1 = "/apis/apps/v1";
    private static final int MAX_LOG_LINES = 1000;

    private final WebClient client;
    private final String defaultNamespace;

    public KubernetesDeploymentProvider(KubernetesApiClient api) {
        this.client = api.webClient();
        this.defaultNamespace = api.namespace();
    }

    @Override
    public String name() {
        return "kubernetes";
    }

    @Override
    public Applied apply(DeploymentSpec spec) {
        validate(spec);
        String namespace = requireNamespace(spec.namespace());
        int revision = currentRevision(namespace, spec.name()) + 1;

        Map<String, Object> deployment = manifest(spec);
        try {
            if (currentRevision(namespace, spec.name()) == 0) {
                post(APPS_V1 + "/namespaces/{ns}/deployments", namespace, deployment);
            } else {
                put(APPS_V1 + "/namespaces/{ns}/deployments/{name}", namespace, spec.name(), deployment);
            }
        } catch (WebClientResponseException e) {
            log.warn("Deployment apply rejected for {}/{}: {}", namespace, spec.name(), e.getStatusCode());
            throw new PlatformExceptions.OperationFailed(
                    "The cluster rejected the deployment: " + e.getStatusCode());
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }

        Map<String, Object> service = serviceManifest(spec);
        upsert("/api/v1/namespaces/{ns}/services", "/api/v1/namespaces/{ns}/services/{name}",
                namespace, spec.name(), service);

        if (spec.ingressHost() != null && !spec.ingressHost().isBlank()) {
            upsert("/apis/networking.k8s.io/v1/namespaces/{ns}/ingresses",
                    "/apis/networking.k8s.io/v1/namespaces/{ns}/ingresses/{name}",
                    namespace, spec.name(), ingressManifest(spec));
        }

        return new Applied(namespace, spec.name(), revision, Instant.now());
    }

    @Override
    public Status status(String namespace, String name) {
        String ns = requireNamespace(namespace);
        try {
            Map<?, ?> body = client.get()
                    .uri(APPS_V1 + "/namespaces/{ns}/deployments/{name}", ns, name)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (body == null) {
                return Status.unavailable(name, "Deployment not found");
            }
            return toStatus(name, body);
        } catch (WebClientResponseException.NotFound e) {
            return Status.unavailable(name, "Deployment not found");
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }
    }

    @Override
    public void rollback(String namespace, String name, int revision) {
        String ns = requireNamespace(namespace);
        try {
            Map<?, ?> rollback = client.get()
                    .uri(APPS_V1 + "/namespaces/{ns}/replicasets?labelSelector="
                            + encodedSelector(name) + "&limit=200", ns)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            String target = revisionTemplate(rollback, revision)
                    .orElseThrow(() -> new PlatformExceptions.NotFound("Deployment revision", revision));

            Map<?, ?> current = client.get()
                    .uri(APPS_V1 + "/namespaces/{ns}/deployments/{name}", ns, name)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (current == null) {
                throw new PlatformExceptions.NotFound("Deployment", name);
            }
            Map<String, Object> restored = new LinkedHashMap<>((Map<String, Object>) current);
            Map<String, Object> spec = new LinkedHashMap<>((Map<String, Object>) restored.get("spec"));
            spec.put("template", parse(target));
            restored.put("spec", spec);
            put(APPS_V1 + "/namespaces/{ns}/deployments/{name}", ns, name, restored);
        } catch (PlatformExceptions.NotFound e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }
    }

    @Override
    public void scale(String namespace, String name, int replicas) {
        String ns = requireNamespace(namespace);
        Map<String, Object> patch = Map.of("spec", Map.of("replicas", replicas));
        try {
            client.patch()
                    .uri(APPS_V1 + "/namespaces/{ns}/deployments/{name}/scale", ns, name)
                    .header("Content-Type", "application/merge-patch+json")
                    .bodyValue(patch)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }
    }

    @Override
    public void delete(String namespace, String name) {
        String ns = requireNamespace(namespace);
        for (String uri : List.of(
                APPS_V1 + "/namespaces/{ns}/deployments/{name}",
                "/api/v1/namespaces/{ns}/services/{name}",
                "/apis/networking.k8s.io/v1/namespaces/{ns}/ingresses/{name}")) {
            try {
                client.delete().uri(uri, ns, name).retrieve().toBodilessEntity().block();
            } catch (WebClientResponseException.NotFound e) {
                // Already gone; delete must stay idempotent so a retry can finish.
            } catch (Exception e) {
                throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
            }
        }
    }

    @Override
    public List<String> logs(String namespace, String name, int maxLines) {
        String ns = requireNamespace(namespace);
        int limit = Math.min(Math.max(maxLines, 1), MAX_LOG_LINES);
        try {
            // tailLines is capped so a chatty container cannot exhaust the API
            // process heap through a log request.
            String body = client.get()
                    .uri(uri -> uri.path("/api/v1/namespaces/{ns}/pods/{pod}/log")
                            .queryParam("tailLines", limit)
                            .build(ns, name))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return body == null ? List.of() : List.of(body.split("\n"));
        } catch (WebClientResponseException.NotFound e) {
            return List.of();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }
    }

    @Override
    public void applySecret(String namespace, String name, Map<String, String> values) {
        String ns = requireNamespace(namespace);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", "v1");
        body.put("kind", "Secret");
        body.put("metadata", metadata(name, Map.of("app.kubernetes.io/managed-by", "hatis-platform")));
        body.put("type", "Opaque");
        Map<String, String> data = new LinkedHashMap<>();
        values.forEach((key, value) ->
                data.put(key, Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
        body.put("data", data);
        upsert("/api/v1/namespaces/{ns}/secrets", "/api/v1/namespaces/{ns}/secrets/{name}", ns, name, body);
    }

    @Override
    public void applyTlsSecret(String namespace, String name, String certificatePem, String privateKeyPem) {
        String ns = requireNamespace(namespace);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", "v1");
        body.put("kind", "Secret");
        body.put("metadata", metadata(name, Map.of("app.kubernetes.io/managed-by", "hatis-platform")));
        body.put("type", "kubernetes.io/tls");
        body.put("data", Map.of(
                "tls.crt", Base64.getEncoder().encodeToString(certificatePem.getBytes(StandardCharsets.UTF_8)),
                "tls.key", Base64.getEncoder().encodeToString(privateKeyPem.getBytes(StandardCharsets.UTF_8))));
        upsert("/api/v1/namespaces/{ns}/secrets", "/api/v1/namespaces/{ns}/secrets/{name}", ns, name, body);
    }

    @Override
    public Optional<String> existingNamespace(String namespace) {
        if (namespace == null || !isValidNamespace(namespace)) {
            return Optional.empty();
        }
        try {
            Map<?, ?> body = client.get()
                    .uri("/api/v1/namespaces/{ns}", namespace)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return body == null ? Optional.empty() : Optional.of(namespace);
        } catch (WebClientResponseException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }
    }

    // ------------------------------------------------------------- manifests

    private Map<String, Object> manifest(DeploymentSpec spec) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", spec.name());
        container.put("image", spec.image());
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("ports", List.of(Map.of("name", "http", "containerPort", spec.containerPort())));

        if (spec.env() != null && !spec.env().isEmpty()) {
            List<Map<String, Object>> env = new ArrayList<>();
            spec.env().forEach((key, value) -> env.add(Map.of("name", key, "value", value)));
            container.put("env", env);
        }
        if (spec.resources() != null) {
            container.put("resources", Map.of(
                    "requests", Map.of("cpu", spec.resources().cpuRequest(),
                            "memory", spec.resources().memoryRequest()),
                    "limits", Map.of("cpu", spec.resources().cpuLimit(),
                            "memory", spec.resources().memoryLimit())));
        }
        Probes probes = spec.probes() == null ? Probes.defaults() : spec.probes();
        container.put("livenessProbe", httpProbe(probes.livenessPath(), spec.containerPort(), probes));
        container.put("readinessProbe", httpProbe(probes.readinessPath(), spec.containerPort(), probes));
        // Dropping all capabilities and refusing privilege escalation is not
        // optional hardening; it is what limits the blast radius of a compromised
        // customer container.
        container.put("securityContext", Map.of(
                "allowPrivilegeEscalation", false,
                "readOnlyRootFilesystem", true,
                "runAsNonRoot", true,
                "capabilities", Map.of("drop", List.of("ALL"))));

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("containers", List.of(container));
        podSpec.put("volumes", List.of(Map.of("name", "tmp", "emptyDir", Map.of())));
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("securityContext", Map.of(
                "runAsNonRoot", true,
                "seccompProfile", Map.of("type", "RuntimeDefault")));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/name", spec.name());
        labels.put("app.kubernetes.io/part-of", "hatis");
        labels.put("app.kubernetes.io/managed-by", "hatis-platform");
        if (spec.labels() != null) {
            labels.putAll(spec.labels());
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("metadata", Map.of("labels", labels));
        template.put("spec", podSpec);

        Map<String, Object> deploymentSpec = new LinkedHashMap<>();
        deploymentSpec.put("replicas", spec.replicas());
        deploymentSpec.put("selector", Map.of("matchLabels", Map.of("app.kubernetes.io/name", spec.name())));
        deploymentSpec.put("template", template);
        deploymentSpec.put("strategy", Map.of(
                "type", "RollingUpdate",
                "rollingUpdate", Map.of("maxSurge", 1, "maxUnavailable", 0)));

        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("apiVersion", "apps/v1");
        deployment.put("kind", "Deployment");
        deployment.put("metadata", metadata(spec.name(), labels));
        deployment.put("spec", deploymentSpec);
        return deployment;
    }

    private Map<String, Object> serviceManifest(DeploymentSpec spec) {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("apiVersion", "v1");
        service.put("kind", "Service");
        service.put("metadata", metadata(spec.name(), Map.of("app.kubernetes.io/managed-by", "hatis-platform")));
        service.put("spec", Map.of(
                "type", "ClusterIP",
                "selector", Map.of("app.kubernetes.io/name", spec.name()),
                "ports", List.of(Map.of("name", "http", "port", 80,
                        "targetPort", spec.containerPort()))));
        return service;
    }

    private Map<String, Object> ingressManifest(DeploymentSpec spec) {
        Map<String, Object> ingress = new LinkedHashMap<>();
        ingress.put("apiVersion", "networking.k8s.io/v1");
        ingress.put("kind", "Ingress");

        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("cert-manager.io/cluster-issuer", "letsencrypt-prod");
        // Traffic arrives over TLS only; a plaintext request is redirected rather
        // than served, so no content is ever delivered unencrypted.
        annotations.put("nginx.ingress.kubernetes.io/ssl-redirect", "true");
        annotations.put("nginx.ingress.kubernetes.io/force-ssl-redirect", "true");
        if (spec.annotations() != null) {
            annotations.putAll(spec.annotations());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", spec.name());
        metadata.put("annotations", annotations);
        ingress.put("metadata", metadata);

        Map<String, Object> backend = Map.of("service", Map.of(
                "name", spec.name(),
                "port", Map.of("number", 80)));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("host", spec.ingressHost());
        rule.put("http", Map.of("paths", List.of(Map.of(
                "path", "/",
                "pathType", "Prefix",
                "backend", backend))));

        Map<String, Object> ingressSpec = new LinkedHashMap<>();
        ingressSpec.put("ingressClassName", "nginx");
        if (spec.tlsSecretName() != null && !spec.tlsSecretName().isBlank()) {
            ingressSpec.put("tls", List.of(Map.of(
                    "hosts", List.of(spec.ingressHost()),
                    "secretName", spec.tlsSecretName())));
        }
        ingressSpec.put("rules", List.of(rule));
        ingress.put("spec", ingressSpec);
        return ingress;
    }

    private static Map<String, Object> httpProbe(String path, int port, Probes probes) {
        return Map.of(
                "httpGet", Map.of("path", path, "port", port),
                "initialDelaySeconds", probes.initialDelay().toSeconds(),
                "periodSeconds", probes.period().toSeconds(),
                "failureThreshold", probes.failureThreshold(),
                "timeoutSeconds", 3);
    }

    private static Map<String, Object> metadata(String name, Map<String, String> labels) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("labels", labels);
        return metadata;
    }

    // -------------------------------------------------------------- plumbing

    private void validate(DeploymentSpec spec) {
        if (spec.name() == null || !spec.name().matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new PlatformExceptions.Validation(
                    "The deployment name must be a DNS-1123 label", Map.of("field", "name"));
        }
        if (spec.image() == null || spec.image().isBlank() || spec.image().endsWith(":latest")) {
            throw new PlatformExceptions.Validation(
                    "The image must be pinned to a tag or digest; 'latest' is not deployable",
                    Map.of("field", "image"));
        }
        if (spec.replicas() < 0 || spec.replicas() > 50) {
            throw new PlatformExceptions.Validation(
                    "replicas must be between 0 and 50", Map.of("field", "replicas"));
        }
        if (spec.containerPort() < 1 || spec.containerPort() > 65535) {
            throw new PlatformExceptions.Validation(
                    "containerPort must be between 1 and 65535", Map.of("field", "containerPort"));
        }
    }

    /**
     * Refuses to act outside a tenant namespace.
     *
     * <p>This is the guard that stops a crafted request from touching
     * {@code kube-system} or the platform's own namespace. It is checked on every
     * call, not only on creation.
     */
    private String requireNamespace(String namespace) {
        String candidate = namespace == null || namespace.isBlank() ? defaultNamespace : namespace;
        if (!isValidNamespace(candidate)) {
            throw new PlatformExceptions.Forbidden("The namespace '" + candidate + "' is not permitted");
        }
        return candidate;
    }

    /** Only platform-issued tenant namespaces, never a system or platform namespace. */
    static boolean isValidNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")) {
            return false;
        }
        return namespace.startsWith("tenant-") || namespace.startsWith("hatis-app-");
    }

    private int currentRevision(String namespace, String name) {
        try {
            Map<?, ?> body = client.get()
                    .uri(APPS_V1 + "/namespaces/{ns}/deployments/{name}", namespace, name)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (body == null) {
                return 0;
            }
            Object metadata = body.get("metadata");
            if (metadata instanceof Map<?, ?> meta && meta.get("generation") instanceof Number generation) {
                return generation.intValue();
            }
            return 1;
        } catch (WebClientResponseException.NotFound e) {
            return 0;
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }
    }

    private Status toStatus(String name, Map<?, ?> body) {
        Object statusObject = body.get("status");
        Map<?, ?> status = statusObject instanceof Map<?, ?> value ? value : Map.of();
        int ready = number(status.get("readyReplicas"));
        int desired = number(status.get("replicas"));
        boolean available = desired > 0 && ready >= desired;
        String message = available ? null : "Waiting for " + ready + "/" + desired + " replicas";
        return new Status(name, available ? "RUNNING" : "PROGRESSING", ready, desired, available,
                message, Instant.now());
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Optional<String> revisionTemplate(Map<?, ?> replicaSets, int revision) {
        Object items = replicaSets == null ? null : replicaSets.get("items");
        if (!(items instanceof List<?> list)) {
            return Optional.empty();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<?, ?>) item)
                .filter(set -> number(set.get("metadata") instanceof Map<?, ?> meta
                        ? ((Map<?, ?>) meta).get("generation") : null) == revision)
                .map(set -> set.get("spec") instanceof Map<?, ?> spec ? ((Map<?, ?>) spec).get("template") : null)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(Object::toString);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new PlatformExceptions.OperationFailed("The stored revision could not be parsed");
        }
    }

    private static String encodedSelector(String name) {
        return java.net.URLEncoder.encode("app.kubernetes.io/name=" + name, StandardCharsets.UTF_8);
    }

    private void post(String uriTemplate, String namespace, Map<String, Object> body) {
        client.post().uri(uriTemplate, namespace).bodyValue(body).retrieve().toBodilessEntity().block();
    }

    private void put(String uriTemplate, String namespace, String name, Map<String, Object> body) {
        client.put().uri(uriTemplate, namespace, name).bodyValue(body).retrieve().toBodilessEntity().block();
    }

    private void upsert(String createUri, String updateUri, String namespace, String name,
                        Map<String, Object> body) {
        try {
            post(createUri, namespace, body);
        } catch (WebClientResponseException.Conflict e) {
            put(updateUri, namespace, name, body);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("kubernetes", e.getMessage());
        }
    }

    /** Exposed for the readiness probe. */
    public Duration timeout() {
        return Duration.ofSeconds(30);
    }
}
