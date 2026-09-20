package com.hatis.platform.deployment.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runtime a tenant application is deployed onto.
 *
 * <p>Kubernetes is the only implementation today; the seam exists because private
 * deployments may run the same model against a different substrate without the
 * deployment context learning about it.
 *
 * <p>Every method takes a namespace supplied by {@code NamespaceStrategy}, never one
 * derived from tenant input, and implementations must reject a namespace that is not
 * theirs before touching the cluster. That check is what stops one tenant's
 * deployment from being addressed through another's request.
 *
 * <p>Unreachable clusters must surface as
 * {@code PlatformExceptions.DependencyUnavailable} so the caller can mark the
 * deployment unhealthy rather than leave it "in progress" forever.
 */
public interface DeploymentProvider {

    /** Stable identifier for this target, recorded on the deployment row. */
    String name();

    /** Creates or updates the workload, its service and, when a host is given, its ingress. */
    Applied apply(DeploymentSpec spec);

    /** Observed rollout state; never throws for a workload that simply does not exist. */
    Status status(String namespace, String name);

    /** Reverts to the template of a previously recorded revision. */
    void rollback(String namespace, String name, int revision);

    /** Sets the replica count. */
    void scale(String namespace, String name, int replicas);

    /** Removes the workload and the service and ingress that go with it. */
    void delete(String namespace, String name);

    /** Trailing container log lines, bounded by the implementation. */
    List<String> logs(String namespace, String name, int maxLines);

    /** Creates or replaces an opaque secret, so values never appear in a manifest. */
    void applySecret(String namespace, String name, Map<String, String> values);

    /** Creates or replaces a TLS secret. Key material stays inside the cluster. */
    void applyTlsSecret(String namespace, String name, String certificatePem, String privateKeyPem);

    /** The namespace, if it already exists; used before provisioning one. */
    Optional<String> existingNamespace(String namespace);

    /** What was applied, and the revision number the provider assigned. */
    record Applied(String namespace, String name, int revision, Instant appliedAt) {
    }

    /**
     * Rollout state as observed by the provider.
     *
     * @param available true only when every desired replica is ready
     */
    record Status(String name, String phase, int readyReplicas, int desiredReplicas,
                  boolean available, String message, Instant observedAt) {

        /** State for a workload the provider cannot see yet, which is not a failure. */
        public static Status unavailable(String name, String message) {
            return new Status(name, "UNAVAILABLE", 0, 0, false, message, Instant.now());
        }
    }

    /**
     * A complete description of the workload, so an adapter never has to call back
     * into the platform to fill in a manifest.
     *
     * @param env           plain environment entries; secrets arrive through
     *                      {@link #applySecret(String, String, Map)} instead
     * @param ingressHost   when set, an ingress for this host is created
     * @param tlsSecretName secret the ingress terminates with, if any
     */
    record DeploymentSpec(String namespace, String name, String image, int replicas, int containerPort,
                          Map<String, String> env, Map<String, String> labels,
                          Map<String, String> annotations, Resources resources, Probes probes,
                          String ingressHost, String tlsSecretName) {
    }

    /** Requests and limits, in the strings Kubernetes itself accepts. */
    record Resources(String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit) {
    }

    /** HTTP health probes. */
    record Probes(String livenessPath, String readinessPath, Duration initialDelay, Duration period,
                  int failureThreshold) {

        /** Conservative defaults for applications that declare no probe paths of their own. */
        public static Probes defaults() {
            return new Probes("/healthz", "/readyz", Duration.ofSeconds(15), Duration.ofSeconds(10), 3);
        }
    }
}
