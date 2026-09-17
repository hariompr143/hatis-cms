package com.hatis.platform.deployment.adapter.rest;

import com.hatis.platform.deployment.application.DeploymentService;
import com.hatis.platform.deployment.domain.Deployment;
import com.hatis.platform.deployment.domain.Release;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.error.PlatformExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deployment API.
 *
 * <p>Deployments and rollbacks return {@code 202 Accepted} with a
 * {@code Location} header pointing at the operation. Nothing here blocks for the
 * duration of a rollout, and nothing here reports success before the cluster does.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Deployment", description = "Applications, releases and rollouts")
public class DeploymentController {

    private final DeploymentService deployments;

    public DeploymentController(DeploymentService deployments) {
        this.deployments = deployments;
    }

    @GetMapping("/applications")
    @Operation(summary = "List the applications in a project")
    public List<DeploymentService.ApplicationView> listApplications(@RequestParam UUID projectId) {
        return deployments.listApplications(projectId);
    }

    @PostMapping("/applications")
    @Operation(summary = "Create an application")
    public ResponseEntity<DeploymentService.ApplicationView> createApplication(
            @Valid @RequestBody CreateApplicationRequest request) {
        var created = deployments.createApplication(request.projectId(), request.name(), request.slug(),
                request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/releases")
    @Operation(summary = "Register a release built by an external pipeline")
    public ResponseEntity<DeploymentService.ReleaseView> createRelease(
            @Valid @RequestBody CreateReleaseRequest request) {
        var created = deployments.createRelease(request.applicationId(), request.version(), request.image(),
                request.digest(), request.sourceType(), request.sourceRef(), request.gitCommit());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/releases")
    @Operation(summary = "List the releases of an application")
    public PageResponse<DeploymentService.ReleaseView> listReleases(@RequestParam UUID applicationId,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "25") int size) {
        return deployments.listReleases(applicationId, page, size);
    }

    @PostMapping("/deployments")
    @Operation(summary = "Start a deployment; returns 202 with the operation to poll")
    public ResponseEntity<DeploymentService.DeploymentAccepted> deploy(
            @Valid @RequestBody DeployRequest request) {
        var accepted = deployments.deploy(request.environmentId(), request.applicationId(),
                request.releaseId(), request.strategy(), request.replicas() == null ? 1 : request.replicas(),
                request.env());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/v1/operations/" + accepted.operationId()))
                .body(accepted);
    }

    @PostMapping("/deployments/rollback")
    @Operation(summary = "Roll an environment back to its previous release")
    public ResponseEntity<DeploymentService.DeploymentAccepted> rollback(
            @Valid @RequestBody RollbackRequest request) {
        var accepted = deployments.rollback(request.environmentId(), request.applicationId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/v1/operations/" + accepted.operationId()))
                .body(accepted);
    }

    @GetMapping("/deployments/{deploymentId}")
    @Operation(summary = "Get deployment status")
    public DeploymentService.DeploymentView status(@PathVariable UUID deploymentId) {
        return deployments.status(deploymentId);
    }

    @GetMapping("/applications/{applicationId}/logs")
    @Operation(summary = "Read the recent logs of an application")
    public Map<String, Object> logs(@PathVariable UUID applicationId,
                                    @RequestParam(defaultValue = "200") int maxLines) {
        if (maxLines < 1 || maxLines > 1000) {
            throw new PlatformExceptions.Validation("maxLines must be between 1 and 1000",
                    Map.of("field", "maxLines"));
        }
        return Map.of("lines", deployments.logs(applicationId, maxLines));
    }

    public record CreateApplicationRequest(
            @NotNull UUID projectId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,62}") String slug,
            @Size(max = 2000) String description) {
    }

    public record CreateReleaseRequest(
            @NotNull UUID applicationId,
            @NotBlank @Size(max = 120) String version,
            @NotBlank @Size(max = 512) String image,
            @Size(max = 128) String digest,
            Release.SourceType sourceType,
            @Size(max = 512) String sourceRef,
            @Size(max = 64) String gitCommit) {
    }

    public record DeployRequest(
            @NotNull UUID environmentId,
            @NotNull UUID applicationId,
            @NotNull UUID releaseId,
            Deployment.Strategy strategy,
            Integer replicas,
            Map<String, String> env) {
    }

    public record RollbackRequest(@NotNull UUID environmentId, @NotNull UUID applicationId) {
    }
}
