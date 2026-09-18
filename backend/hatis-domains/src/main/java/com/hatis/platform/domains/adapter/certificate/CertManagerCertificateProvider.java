package com.hatis.platform.domains.adapter.certificate;

import com.hatis.platform.domains.port.out.CertificateProvider;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.hatis.platform.infrastructure.adapter.kubernetes.KubernetesApiClient;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * cert-manager adapter.
 *
 * <p>Creates a {@code Certificate} custom resource and lets the cluster's ACME
 * issuer obtain and renew it. Renewal continues without the platform running, which
 * is the point: a control-plane outage must not cause customer certificates to
 * lapse.
 *
 * <p>Talks to the API server through the in-cluster service account rather than a
 * kubeconfig, so no cluster credential is stored anywhere the platform can leak.
 * The account is bound by RBAC to only the cert-manager resource types in the
 * platform's namespaces.
 */
@Component
@ConditionalOnProperty(name = "hatis.domains.certificates.provider", havingValue = "cert-manager",
        matchIfMissing = true)
public class CertManagerCertificateProvider implements CertificateProvider {

    private static final Logger log = LoggerFactory.getLogger(CertManagerCertificateProvider.class);
    private static final String API_VERSION = "cert-manager.io/v1";
    private static final String KIND = "Certificate";
    private static final String PLURAL = "certificates";

    private final WebClient client;
    private final String namespace;
    private final String defaultIssuerKind;
    private final String defaultIssuerName;

    public CertManagerCertificateProvider(Environment environment, KubernetesApiClient api) {
        this.client = api.webClient();
        this.namespace = environment.getProperty("hatis.domains.certificates.namespace", "hatis-tls");
        this.defaultIssuerKind = environment.getProperty("hatis.domains.certificates.issuer-kind",
                "ClusterIssuer");
        this.defaultIssuerName = environment.getProperty("hatis.domains.certificates.issuer-name",
                "letsencrypt-prod");
    }

    @Override
    public String name() {
        return "cert-manager";
    }

    @Override
    public IssuedCertificate issue(String hostname, List<String> additionalNames,
                                   CertificateRequest request) {
        String secretName = request.secretName() == null || request.secretName().isBlank()
                ? defaultSecretName(hostname)
                : request.secretName();
        String issuerKind = request.issuerKind() == null ? defaultIssuerKind : request.issuerKind();
        String issuerName = request.issuerName() == null ? defaultIssuerName : request.issuerName();

        java.util.List<String> dnsNames = new java.util.ArrayList<>();
        dnsNames.add(hostname);
        if (additionalNames != null) {
            additionalNames.stream().filter(name -> name != null && !name.isBlank()).forEach(dnsNames::add);
        }

        Map<String, Object> body = Map.of(
                "apiVersion", API_VERSION,
                "kind", KIND,
                "metadata", Map.of("name", resourceName(hostname), "namespace", namespace),
                "spec", Map.of(
                        "secretName", secretName,
                        "duration", "2160h",
                        "renewBefore", "720h",
                        "privateKey", Map.of("algorithm", "ECDSA", "size", 256, "rotationPolicy", "Always"),
                        "dnsNames", dnsNames,
                        "issuerRef", Map.of("kind", issuerKind, "name", issuerName)));

        try {
            client.post()
                    .uri("/apis/{group}/{version}/namespaces/{namespace}/{plural}",
                            "cert-manager.io", "v1", namespace, PLURAL)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.Conflict e) {
            // Already requested: cert-manager will converge on the desired state.
            update(hostname, body);
        } catch (WebClientResponseException e) {
            log.warn("Certificate request failed for {}: {}", hostname, e.getStatusCode());
            throw new PlatformExceptions.DependencyUnavailable("certificate-authority",
                    "The certificate could not be requested for " + hostname);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("certificate-authority",
                    "The certificate could not be requested for " + hostname);
        }
        return new IssuedCertificate(hostname, secretName, issuerName, Instant.now());
    }

    @Override
    public Optional<CertificateStatus> status(String hostname) {
        try {
            Map<?, ?> body = client.get()
                    .uri("/apis/{group}/{version}/namespaces/{namespace}/{plural}/{name}",
                            "cert-manager.io", "v1", namespace, PLURAL, resourceName(hostname))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (body == null) {
                return Optional.empty();
            }
            Object statusObject = body.get("status");
            if (!(statusObject instanceof Map<?, ?> status)) {
                return Optional.of(new CertificateStatus(hostname, false, null, "Pending issuance"));
            }
            boolean ready = isReady(status);
            Instant notAfter = parseInstant(status.get("notAfter"));
            String message = conditionMessage(status);
            return Optional.of(new CertificateStatus(hostname, ready, notAfter, message));
        } catch (WebClientResponseException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("certificate-authority",
                    "The certificate status could not be read for " + hostname);
        }
    }

    @Override
    public void revoke(String hostname) {
        try {
            client.delete()
                    .uri("/apis/{group}/{version}/namespaces/{namespace}/{plural}/{name}",
                            "cert-manager.io", "v1", namespace, PLURAL, resourceName(hostname))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            return;
        } catch (Exception e) {
            log.warn("Certificate removal failed for {}: {}", hostname, e.getClass().getSimpleName());
        }
    }

    private void update(String hostname, Map<String, Object> body) {
        client.put()
                .uri("/apis/{group}/{version}/namespaces/{namespace}/{plural}/{name}",
                        "cert-manager.io", "v1", namespace, PLURAL, resourceName(hostname))
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private static boolean isReady(Map<?, ?> status) {
        Object conditions = status.get("conditions");
        if (!(conditions instanceof List<?> list)) {
            return false;
        }
        return list.stream()
                .filter(element -> element instanceof Map)
                .map(element -> (Map<?, ?>) element)
                .anyMatch(condition -> "Ready".equals(condition.get("type"))
                        && "True".equals(condition.get("status")));
    }

    private static String conditionMessage(Map<?, ?> status) {
        Object conditions = status.get("conditions");
        if (!(conditions instanceof List<?> list)) {
            return null;
        }
        return list.stream()
                .filter(element -> element instanceof Map)
                .map(element -> (Map<?, ?>) element)
                .filter(condition -> "Ready".equals(condition.get("type")))
                .map(condition -> String.valueOf(condition.get("message")))
                .findFirst()
                .orElse(null);
    }

    private static Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value)).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Kubernetes resource names must be DNS-1123 subdomains and at most 253 chars. */
    static String resourceName(String hostname) {
        String name = "tls-" + hostname.toLowerCase(java.util.Locale.ROOT)
                .replace("*", "wildcard")
                .replaceAll("[^a-z0-9.-]", "-");
        return name.length() > 253 ? name.substring(0, 253) : name;
    }

    static String defaultSecretName(String hostname) {
        return resourceName(hostname);
    }
}
