package com.hatis.platform.deployment.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.deployment.adapter.persistence.DeploymentRepositories;
import com.hatis.platform.deployment.domain.Application;
import com.hatis.platform.deployment.domain.Deployment;
import com.hatis.platform.deployment.domain.Release;
import com.hatis.platform.deployment.port.out.DeploymentProvider;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.operation.Operation;
import com.hatis.platform.shared.operation.OperationService;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Release registration and deployment.
 *
 * <p>The API returns {@code 202 Accepted} with an operation id: a rollout outlives
 * an HTTP request, and pretending otherwise is how platforms end up lying about
 * state. The caller polls the operation.
 *
 * <p>Two invariants hold on every path:
 * <ul>
 *   <li>a release can only be deployed if its scan passed — enforced here, in the
 *       service, so no client can skip it;</li>
 *   <li>the environment's namespace is derived from the tenant, never supplied by
 *       the caller.</li>
 * </ul>
 */
@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);
    private static final Duration ROLLOUT_TIMEOUT = Duration.ofMinutes(10);

    private final DeploymentRepositories.ApplicationRepository applications;
    private final DeploymentRepositories.ReleaseRepository releases;
    private final DeploymentRepositories.DeploymentRepository deployments;
    private final DeploymentProvider provider;
    private final OperationService operations;
    private final AuthorizationService authorization;
    private final QuotaService quotas;
    private final EventPublisher events;
    private final NamespaceStrategy namespaces;

    public DeploymentService(DeploymentRepositories.ApplicationRepository applications,
                             DeploymentRepositories.ReleaseRepository releases,
                             DeploymentRepositories.DeploymentRepository deployments,
                             DeploymentProvider provider,
                             OperationService operations,
                             AuthorizationService authorization,
                             QuotaService quotas,
                             EventPublisher events,
                             NamespaceStrategy namespaces) {
        this.applications = applications;
        this.releases = releases;
        this.deployments = deployments;
        this.provider = provider;
        this.operations = operations;
        this.authorization = authorization;
        this.quotas = quotas;
        this.events = events;
        this.namespaces = namespaces;
    }

    // ------------------------------------------------------------ applications

    @TenantTransactional(readOnly = true)
    public List<ApplicationView> listApplications(UUID projectId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("application:read", ScopeType.PROJECT, projectId);
        return applications.findByOrganizationIdAndProjectIdAndArchivedAtIsNull(organizationId, projectId)
                .stream().map(ApplicationView::from).toList();
    }

    @TenantTransactional
    public ApplicationView createApplication(UUID projectId, String name, String slug, String description) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("application:write", ScopeType.PROJECT, projectId);
        quotas.check(organizationId, QuotaKey.APPLICATIONS, 1);
        if (applications.existsByOrganizationIdAndProjectIdAndSlug(organizationId, projectId, slug)) {
            throw new PlatformExceptions.AlreadyExists(
                    "An application with slug '" + slug + "' already exists in this project");
        }
        Application application = applications.save(new Application(organizationId, projectId, name, slug,
                description, TenantContextHolder.require().principalId()));
        quotas.record(organizationId, QuotaKey.APPLICATIONS, 1);
        return ApplicationView.from(application);
    }

    // ---------------------------------------------------------------- releases

    @TenantTransactional
    public ReleaseView createRelease(UUID applicationId, String version, String image, String digest,
                                     Release.SourceType sourceType, String sourceRef, String gitCommit) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Application application = requireApplication(applicationId, organizationId);
        authorization.require("release:write", ScopeType.PROJECT,
                application.getProjectId());
        if (releases.existsByOrganizationIdAndApplicationIdAndVersion(organizationId, applicationId,
                version.toLowerCase(java.util.Locale.ROOT))) {
            throw new PlatformExceptions.AlreadyExists("Release " + version + " already exists");
        }
        Release release = releases.save(new Release(organizationId, applicationId, version, image, digest,
                sourceType, sourceRef, gitCommit, TenantContextHolder.require().principalId()));
        return ReleaseView.from(release);
    }

    /**
     * Records the outcome of an image scan.
     *
     * <p>Called by the scan worker. The result determines whether the release can
     * ever be deployed, so it is written here rather than left to the caller to
     * decide.
     */
    @TenantTransactional
    public ReleaseView recordScan(UUID releaseId, Release.ScanStatus result, String summary) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Release release = releases.findByIdAndOrganizationId(releaseId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Release", releaseId));
        release.recordScan(result, summary);
        return ReleaseView.from(releases.save(release));
    }

    @TenantTransactional(readOnly = true)
    public PageResponse<ReleaseView> listReleases(UUID applicationId, int page, int size) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Application application = requireApplication(applicationId, organizationId);
        authorization.require("release:read", ScopeType.PROJECT,
                application.getProjectId());
        var pageable = PageResponse.pageable(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(
                releases.findByOrganizationIdAndApplicationId(organizationId, applicationId, pageable),
                ReleaseView::from);
    }

    // -------------------------------------------------------------- deployment

    /**
     * Starts a deployment and returns the operation to poll.
     *
     * <p>The operation row is written in the same transaction as the deployment
     * record, so a caller who gets a {@code 202} can always find out what happened —
     * including when the worker dies mid-rollout.
     */
    @TenantTransactional
    public DeploymentAccepted deploy(UUID environmentId, UUID applicationId, UUID releaseId,
                                     Deployment.Strategy strategy, int replicas, Map<String, String> env) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        quotas.check(organizationId, QuotaKey.DEPLOYMENTS, 1);

        Application application = requireApplication(applicationId, organizationId);
        authorization.require("deployment:write", ScopeType.PROJECT,
                application.getProjectId());

        Release release = releases.findByIdAndOrganizationId(releaseId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Release", releaseId));
        if (!release.getApplicationId().equals(applicationId)) {
            throw new PlatformExceptions.Validation("The release belongs to another application",
                    Map.of("field", "releaseId"));
        }
        release.requireDeployable();

        Deployment deployment = deployments.save(new Deployment(organizationId, environmentId, applicationId,
                releaseId, strategy, replicas, TenantContextHolder.require().principalId()));
        Operation operation = operations.start(organizationId, "deployment.rollout", "deployment",
                deployment.getId());
        deployment.associateOperation(operation.getId());
        deployments.save(deployment);
        quotas.record(organizationId, QuotaKey.DEPLOYMENTS, 1);

        events.publish(PlatformEvent.of("deployment.started", organizationId)
                .resource("deployment", deployment.getId())
                .data(Map.of("applicationId", applicationId.toString(),
                        "environmentId", environmentId.toString(),
                        "releaseVersion", release.getVersion()))
                .build());

        return new DeploymentAccepted(deployment.getId(), operation.getId(), application.getSlug(),
                release.getVersion());
    }

    /**
     * Performs the rollout.
     *
     * <p>Runs on the worker under the tenant context of the request that created it,
     * so the same authorization and RLS rules apply as for a synchronous call.
     */
    public void execute(UUID deploymentId, TenantContext tenantContext) {
        TenantContextHolder.runAs(tenantContext, () -> executeAsTenant(deploymentId));
    }

    private void executeAsTenant(UUID deploymentId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Deployment deployment = deployments.findByIdAndOrganizationId(deploymentId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Deployment", deploymentId));
        if (deployment.isTerminal()) {
            return;
        }

        Application application = requireApplication(deployment.getApplicationId(), organizationId);
        Release release = releases.findByIdAndOrganizationId(deployment.getReleaseId(), organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Release", deployment.getReleaseId()));

        operations.markRunning(deployment.getOperationId(), 10);
        deployment.markRunning();
        deployments.save(deployment);

        String namespace = namespaces.namespaceFor(organizationId, deployment.getEnvironmentId());
        String name = "app-" + application.getSlug();
        try {
            if (release.getSourceType() != Release.SourceType.EXTERNAL_PIPELINE) {
                // Environment values are applied as a Secret rather than as plain
                // env entries so they never appear in the deployment manifest.
                provider.applySecret(namespace, name + "-env", Map.of());
            }
            operations.updateProgress(deployment.getOperationId(), 40);

            provider.apply(new DeploymentProvider.DeploymentSpec(
                    namespace, name, release.getImage(), deployment.getReplicas(),
                    application.getDefaultPort(), Map.of(), Map.of(), Map.of(),
                    new DeploymentProvider.Resources(application.getCpuRequest(), application.getCpuLimit(),
                            application.getMemoryRequest(), application.getMemoryLimit()),
                    new DeploymentProvider.Probes(application.getHealthPath(),
                            application.getReadinessPath(), Duration.ofSeconds(10),
                            Duration.ofSeconds(10), 3),
                    null, null));
            operations.updateProgress(deployment.getOperationId(), 70);

            DeploymentProvider.Status status = awaitRollout(namespace, name, deployment.getReplicas());
            if (status.available()) {
                deployment.markCompleted(Deployment.Health.HEALTHY);
                operations.markSucceeded(deployment.getOperationId());
            } else {
                deployment.markFailed(Deployment.Health.DEGRADED);
                operations.markFailed(deployment.getOperationId(), "rollout_incomplete",
                        status.message() == null ? "The rollout did not complete" : status.message());
            }
        } catch (PlatformExceptions.DependencyUnavailable e) {
            deployment.markFailed(Deployment.Health.UNHEALTHY);
            operations.markFailed(deployment.getOperationId(), "dependency_unavailable",
                    "The deployment target could not be reached");
            log.warn("Rollout {} failed: cluster unreachable", deploymentId);
        } catch (Exception e) {
            deployment.markFailed(Deployment.Health.UNHEALTHY);
            operations.markFailed(deployment.getOperationId(), "operation_failed", e.getMessage());
            log.warn("Rollout {} failed", deploymentId, e);
        }
        deployments.save(deployment);

        events.publish(PlatformEvent.of("deployment.finished", organizationId)
                .resource("deployment", deployment.getId())
                .data(Map.of("status", deployment.getStatus().name(),
                        "health", deployment.getHealthStatus().name()))
                .build());
    }

    private DeploymentProvider.Status awaitRollout(String namespace, String name, int replicas) {
        long deadline = System.currentTimeMillis() + ROLLOUT_TIMEOUT.toMillis();
        DeploymentProvider.Status status = DeploymentProvider.Status.unavailable(name, "Not observed yet");
        while (System.currentTimeMillis() < deadline) {
            status = provider.status(namespace, name);
            if (status.available() && status.readyReplicas() >= replicas) {
                return status;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
        }
        return status;
    }

    /** Reverts an environment to the release it ran before. */
    @TenantTransactional
    public DeploymentAccepted rollback(UUID environmentId, UUID applicationId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Application application = requireApplication(applicationId, organizationId);
        authorization.require("deployment:write", ScopeType.PROJECT,
                application.getProjectId());

        List<Deployment> history = deployments
                .findByOrganizationIdAndApplicationIdAndEnvironmentIdOrderByCreatedAtDesc(
                        organizationId, applicationId, environmentId);
        Deployment current = history.stream()
                .filter(candidate -> candidate.getStatus() == Deployment.Status.COMPLETED)
                .findFirst()
                .orElseThrow(() -> new PlatformExceptions.StateConflict(
                        "There is no completed deployment to roll back from"));
        Release previous = history.stream()
                .map(candidate -> releases.findByIdAndOrganizationId(candidate.getReleaseId(), organizationId)
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(Release::isDeployable)
                .filter(candidate -> !candidate.getId().equals(current.getReleaseId()))
                .findFirst()
                .orElseThrow(() -> new PlatformExceptions.StateConflict(
                        "There is no earlier deployable release to roll back to"));

        Deployment rollback = deployments.save(new Deployment(organizationId, environmentId, applicationId,
                previous.getId(), current.getStrategy(), current.getReplicas(),
                TenantContextHolder.require().principalId()));
        Operation operation = operations.start(organizationId, "deployment.rollback", "deployment",
                rollback.getId());
        rollback.associateOperation(operation.getId());
        deployments.save(rollback);

        events.publish(PlatformEvent.of("deployment.rollback_started", organizationId)
                .resource("deployment", rollback.getId())
                .data(Map.of("releaseVersion", previous.getVersion()))
                .build());
        return new DeploymentAccepted(rollback.getId(), operation.getId(), application.getSlug(),
                previous.getVersion());
    }

    @TenantTransactional(readOnly = true)
    public DeploymentView status(UUID deploymentId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("deployment:read", ScopeType.ORGANIZATION, organizationId);
        Deployment deployment = deployments.findByIdAndOrganizationId(deploymentId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Deployment", deploymentId));
        return DeploymentView.from(deployment);
    }

    @TenantTransactional(readOnly = true)
    public List<String> logs(UUID applicationId, int maxLines) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Application application = requireApplication(applicationId, organizationId);
        authorization.require("deployment:read", ScopeType.PROJECT,
                application.getProjectId());
        Deployment latest = deployments
                .findByOrganizationIdAndApplicationIdOrderByCreatedAtDesc(organizationId, applicationId)
                .stream().findFirst()
                .orElseThrow(() -> new PlatformExceptions.NotFound("Deployment", applicationId));
        String namespace = namespaces.namespaceFor(organizationId, latest.getEnvironmentId());
        return provider.logs(namespace, "app-" + application.getSlug(), maxLines);
    }

    private Application requireApplication(UUID applicationId, UUID organizationId) {
        return applications.findByIdAndOrganizationId(applicationId, organizationId)
                .filter(application -> !application.isArchived())
                .orElseThrow(() -> new PlatformExceptions.NotFound("Application", applicationId));
    }

    // ------------------------------------------------------------------ views

    public record DeploymentAccepted(UUID deploymentId, UUID operationId, String applicationSlug,
                                     String releaseVersion) {
    }

    public record ApplicationView(UUID id, UUID projectId, String name, String slug, String description,
                                  String runtime, int defaultPort) {

        public static ApplicationView from(Application application) {
            return new ApplicationView(application.getId(), application.getProjectId(),
                    application.getName(), application.getSlug(), application.getDescription(),
                    application.getRuntime().name(), application.getDefaultPort());
        }
    }

    public record ReleaseView(UUID id, UUID applicationId, String version, String image, String digest,
                              String sourceType, String scanStatus, String status,
                              java.time.Instant createdAt) {

        public static ReleaseView from(Release release) {
            return new ReleaseView(release.getId(), release.getApplicationId(), release.getVersion(),
                    release.getImage(), release.getDigest(), release.getSourceType().name(),
                    release.getScanStatus().name(), release.getStatus().name(), release.getCreatedAt());
        }
    }

    public record DeploymentView(UUID id, UUID environmentId, UUID applicationId, UUID releaseId,
                                 String status, String strategy, int replicas, String healthStatus,
                                 UUID operationId, java.time.Instant startedAt,
                                 java.time.Instant finishedAt) {

        public static DeploymentView from(Deployment deployment) {
            return new DeploymentView(deployment.getId(), deployment.getEnvironmentId(),
                    deployment.getApplicationId(), deployment.getReleaseId(), deployment.getStatus().name(),
                    deployment.getStrategy().name(), deployment.getReplicas(),
                    deployment.getHealthStatus().name(), deployment.getOperationId(),
                    deployment.getStartedAt(), deployment.getFinishedAt());
        }
    }
}
