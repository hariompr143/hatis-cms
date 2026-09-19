package com.hatis.platform.organization.adapter.rest;

import com.hatis.platform.organization.application.MembershipService;
import com.hatis.platform.organization.application.OrganizationService;
import com.hatis.platform.organization.application.ProjectService;
import com.hatis.platform.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Organization, project, environment and membership APIs.
 *
 * <p>Authorization is declared here for fast failure and re-checked inside the
 * application service, because the service is the authoritative enforcement point
 * and is also reachable from other contexts and from background jobs.
 */
@RestController
@RequestMapping("/v1/organizations")
@Tag(name = "Organizations", description = "Tenants, projects, environments and members")
public class OrganizationController {

    private final OrganizationService organizations;
    private final ProjectService projects;
    private final MembershipService memberships;

    public OrganizationController(OrganizationService organizations,
                                  ProjectService projects,
                                  MembershipService memberships) {
        this.organizations = organizations;
        this.projects = projects;
        this.memberships = memberships;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create an organization",
            description = "Creates a tenant and makes the calling user its owner.")
    public OrganizationService.OrganizationView create(
            @Valid @RequestBody OrganizationService.CreateOrganizationCommand command) {
        return organizations.create(command);
    }

    @GetMapping("/{organizationId}")
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'organization:read')")
    public OrganizationService.OrganizationView get(@PathVariable UUID organizationId) {
        verifyTenant(organizationId);
        return organizations.get(organizationId);
    }

    @PatchMapping("/{organizationId}")
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'organization:write')")
    public OrganizationService.OrganizationView update(
            @PathVariable UUID organizationId,
            @Valid @RequestBody OrganizationService.UpdateOrganizationCommand command) {
        verifyTenant(organizationId);
        return organizations.update(organizationId, command);
    }

    @DeleteMapping("/{organizationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'organization:delete')")
    @Operation(summary = "Close an organization",
            description = "Closes the tenant. Data is retained and exportable for the retention window.")
    public void close(@PathVariable UUID organizationId) {
        verifyTenant(organizationId);
        organizations.close(organizationId);
    }

    // --- Projects -----------------------------------------------------------

    @GetMapping("/{organizationId}/projects")
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'project:read')")
    public PageResponse<ProjectService.ProjectView> listProjects(
            @PathVariable UUID organizationId,
            @PageableDefault(size = 25) Pageable pageable) {
        verifyTenant(organizationId);
        return projects.list(pageable);
    }

    @PostMapping("/{organizationId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'project:write')")
    public ProjectService.ProjectView createProject(
            @PathVariable UUID organizationId,
            @Valid @RequestBody ProjectService.CreateProjectCommand command) {
        verifyTenant(organizationId);
        return projects.create(command);
    }

    @GetMapping("/{organizationId}/projects/{projectId}")
    @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'project:read')")
    public ProjectService.ProjectView getProject(@PathVariable UUID organizationId, @PathVariable UUID projectId) {
        verifyTenant(organizationId);
        return projects.get(projectId);
    }

    @PatchMapping("/{organizationId}/projects/{projectId}")
    @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'project:write')")
    public ProjectService.ProjectView updateProject(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectService.UpdateProjectCommand command) {
        verifyTenant(organizationId);
        return projects.update(projectId, command);
    }

    // --- Environments -------------------------------------------------------

    @GetMapping("/{organizationId}/projects/{projectId}/environments")
    @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'environment:read')")
    public List<ProjectService.EnvironmentView> listEnvironments(@PathVariable UUID organizationId,
                                                                 @PathVariable UUID projectId) {
        verifyTenant(organizationId);
        return projects.listEnvironments(projectId);
    }

    @PostMapping("/{organizationId}/projects/{projectId}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#projectId, 'PROJECT', 'environment:write')")
    public ProjectService.EnvironmentView createEnvironment(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectService.CreateEnvironmentCommand command) {
        verifyTenant(organizationId);
        return projects.createEnvironment(projectId, command);
    }

    // --- Members ------------------------------------------------------------

    @GetMapping("/{organizationId}/members")
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'member:read')")
    public List<MembershipService.MembershipView> listMembers(@PathVariable UUID organizationId) {
        verifyTenant(organizationId);
        return memberships.listActive(organizationId);
    }

    @PostMapping("/{organizationId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'member:write')")
    public MembershipService.MembershipView invite(
            @PathVariable UUID organizationId,
            @Valid @RequestBody InviteMemberRequest request) {
        verifyTenant(organizationId);
        return memberships.invite(organizationId, request.userId(), request.role());
    }

    @PatchMapping("/{organizationId}/members/{membershipId}")
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'member:write')")
    public MembershipService.MembershipView changeRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody ChangeRoleRequest request) {
        verifyTenant(organizationId);
        return memberships.changeRole(organizationId, membershipId, request.role());
    }

    @DeleteMapping("/{organizationId}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#organizationId, 'ORGANIZATION', 'member:write')")
    public void removeMember(@PathVariable UUID organizationId, @PathVariable UUID membershipId) {
        verifyTenant(organizationId);
        memberships.remove(organizationId, membershipId);
    }

    public record InviteMemberRequest(UUID userId, String role) {
    }

    public record ChangeRoleRequest(String role) {
    }

    /**
     * Verifies the organization in the path matches the authenticated tenant.
     *
     * <p>The path value is never trusted: it is compared against the context that
     * was derived from the verified credential, and a mismatch is a 403.
     */
    private static void verifyTenant(UUID organizationId) {
        com.hatis.platform.shared.tenant.TenantContextHolder.require().requireOrganization(organizationId);
    }
}
