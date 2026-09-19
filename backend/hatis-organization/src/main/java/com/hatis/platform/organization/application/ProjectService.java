package com.hatis.platform.organization.application;

import com.hatis.platform.organization.adapter.persistence.OrganizationRepositories;
import com.hatis.platform.organization.domain.Environment;
import com.hatis.platform.organization.domain.Project;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Project and environment lifecycle.
 *
 * <p>Creating a project also creates the standard environment ladder so that a new
 * project is immediately deployable and cannot accidentally target production.
 */
@Service
public class ProjectService {

    private static final List<Environment.Kind> DEFAULT_LADDER = List.of(
            Environment.Kind.DEVELOPMENT, Environment.Kind.STAGING, Environment.Kind.PRODUCTION);

    private final OrganizationRepositories.ProjectRepository projects;
    private final OrganizationRepositories.EnvironmentRepository environments;
    private final QuotaService quotas;
    private final AuditRecorder audit;
    private final EventPublisher events;

    public ProjectService(OrganizationRepositories.ProjectRepository projects,
                          OrganizationRepositories.EnvironmentRepository environments,
                          QuotaService quotas,
                          AuditRecorder audit,
                          EventPublisher events) {
        this.projects = projects;
        this.environments = environments;
        this.quotas = quotas;
        this.audit = audit;
        this.events = events;
    }

    @TenantTransactional
    public ProjectView create(@Valid CreateProjectCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        quotas.check(organizationId, QuotaKey.PROJECTS, 1);
        if (projects.existsByOrganizationIdAndSlug(organizationId, command.slug())) {
            throw new PlatformExceptions.AlreadyExists("A project with slug '" + command.slug() + "' already exists");
        }
        Project project = projects.save(new Project(organizationId, command.name(), command.slug(), command.description()));

        int order = 0;
        for (Environment.Kind kind : DEFAULT_LADDER) {
            environments.save(new Environment(organizationId, project.getId(),
                    kind.name().charAt(0) + kind.name().substring(1).toLowerCase(java.util.Locale.ROOT),
                    kind.name().toLowerCase(java.util.Locale.ROOT), kind, order++));
        }

        quotas.record(organizationId, QuotaKey.PROJECTS, 1);
        quotas.record(organizationId, QuotaKey.ENVIRONMENTS, DEFAULT_LADDER.size());
        audit.record(AuditRecord.builder("project.created")
                .resource("project", project.getId())
                .metadata(Map.of("slug", project.getSlug()))
                .build());
        events.publish(PlatformEvent.of("project.created", organizationId)
                .resource("project", project.getId())
                .data(Map.of("slug", project.getSlug())).build());
        return ProjectView.from(project);
    }

    @TenantTransactional(readOnly = true)
    public PageResponse<ProjectView> list(Pageable pageable) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return PageResponse.from(
                projects.findByOrganizationIdAndStatusNot(organizationId, Project.Status.DELETED, pageable),
                ProjectView::from);
    }

    @TenantTransactional(readOnly = true)
    public ProjectView get(UUID projectId) {
        return ProjectView.from(load(TenantContextHolder.require().requireOrganizationId(), projectId));
    }

    @TenantTransactional
    public ProjectView update(UUID projectId, @Valid UpdateProjectCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Project project = load(organizationId, projectId);
        project.update(command.name(), command.description());
        projects.save(project);
        audit.record(AuditRecord.builder("project.updated").resource("project", projectId).build());
        return ProjectView.from(project);
    }

    @TenantTransactional
    public void archive(UUID projectId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Project project = load(organizationId, projectId);
        project.archive();
        projects.save(project);
        audit.record(AuditRecord.builder("project.archived").resource("project", projectId).build());
    }

    @TenantTransactional
    public EnvironmentView createEnvironment(UUID projectId, @Valid CreateEnvironmentCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        quotas.check(organizationId, QuotaKey.ENVIRONMENTS, 1);
        load(organizationId, projectId).requireUsable();
        if (environments.existsByOrganizationIdAndProjectIdAndSlug(organizationId, projectId, command.slug())) {
            throw new PlatformExceptions.AlreadyExists("An environment with that slug already exists");
        }
        int order = environments.findByOrganizationIdAndProjectIdOrderByOrderIndexAsc(organizationId, projectId).size();
        Environment environment = environments.save(new Environment(
                organizationId, projectId, command.name(), command.slug(), command.kind(), order));
        quotas.record(organizationId, QuotaKey.ENVIRONMENTS, 1);
        audit.record(AuditRecord.builder("environment.created")
                .resource("environment", environment.getId())
                .metadata(Map.of("slug", environment.getSlug(), "kind", environment.getKind().name()))
                .build());
        return EnvironmentView.from(environment);
    }

    @TenantTransactional(readOnly = true)
    public List<EnvironmentView> listEnvironments(UUID projectId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return environments.findByOrganizationIdAndProjectIdOrderByOrderIndexAsc(organizationId, projectId)
                .stream().map(EnvironmentView::from).toList();
    }

    /** Loads a project and asserts it belongs to the caller's tenant. */
    @TenantTransactional(readOnly = true)
    public Project load(UUID organizationId, UUID projectId) {
        return projects.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Project", projectId));
    }

    @TenantTransactional(readOnly = true)
    public Environment loadEnvironment(UUID organizationId, UUID environmentId) {
        return environments.findByIdAndOrganizationId(environmentId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Environment", environmentId));
    }

    public record CreateProjectCommand(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 63) String slug,
            @Size(max = 2000) String description) {
    }

    public record UpdateProjectCommand(@NotBlank @Size(max = 200) String name,
                                       @Size(max = 2000) String description) {
    }

    public record CreateEnvironmentCommand(@NotBlank @Size(max = 100) String name,
                                           @NotBlank @Size(max = 63) String slug,
                                           Environment.Kind kind) {
    }

    public record ProjectView(UUID id, String name, String slug, String description,
                              String status, java.time.Instant createdAt) {
        public static ProjectView from(Project p) {
            return new ProjectView(p.getId(), p.getName(), p.getSlug(), p.getDescription(),
                    p.getStatus().name(), p.getCreatedAt());
        }
    }

    public record EnvironmentView(UUID id, UUID projectId, String name, String slug,
                                  String kind, boolean production) {
        public static EnvironmentView from(Environment e) {
            return new EnvironmentView(e.getId(), e.getProjectId(), e.getName(), e.getSlug(),
                    e.getKind().name(), e.isProduction());
        }
    }
}
