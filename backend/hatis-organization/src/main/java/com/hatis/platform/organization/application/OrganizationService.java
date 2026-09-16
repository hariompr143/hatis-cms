package com.hatis.platform.organization.application;

import com.hatis.platform.organization.adapter.persistence.OrganizationRepositories;
import com.hatis.platform.organization.domain.Organization;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Organization lifecycle.
 *
 * <p>Creation is the only tenant-scoped operation that legitimately runs without a
 * pre-existing tenant context, because the tenant does not exist yet. Everything
 * else is bound by {@link TenantTransactional}.
 */
@Service
public class OrganizationService {

    private final OrganizationRepositories.OrganizationRepository organizations;
    private final MembershipService memberships;
    private final QuotaService quotas;
    private final AuditRecorder audit;
    private final EventPublisher events;

    public OrganizationService(OrganizationRepositories.OrganizationRepository organizations,
                               MembershipService memberships,
                               QuotaService quotas,
                               AuditRecorder audit,
                               EventPublisher events) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.quotas = quotas;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public OrganizationView create(@Valid CreateOrganizationCommand command) {
        if (organizations.existsBySlug(command.slug())) {
            throw new PlatformExceptions.AlreadyExists("An organization with slug '" + command.slug() + "' exists");
        }
        Organization organization = new Organization(
                command.name(), command.slug(), command.planCode(), command.region());
        organizations.save(organization);

        // The creating user becomes the owner. This happens in the same
        // transaction so a tenant can never exist without an owner.
        TenantContextHolder.set(TenantContext.of(organization.getId(), command.ownerUserId(),
                TenantContext.PrincipalType.USER));
        try {
            memberships.addOwner(organization.getId(), command.ownerUserId());
        } finally {
            TenantContextHolder.clear();
        }

        audit.record(AuditRecord.builder("organization.created")
                .organization(organization.getId())
                .actor(AuditRecord.ActorType.USER, command.ownerUserId(), null)
                .resource("organization", organization.getId())
                .metadata(Map.of("slug", organization.getSlug(), "plan", organization.getPlanCode()))
                .build());
        events.publish(PlatformEvent.of("organization.created", organization.getId())
                .resource("organization", organization.getId())
                .data(Map.of("slug", organization.getSlug(), "planCode", organization.getPlanCode()))
                .build());
        return OrganizationView.from(organization);
    }

    @TenantTransactional
    public OrganizationView get(UUID organizationId) {
        TenantContextHolder.require().requireOrganization(organizationId);
        return OrganizationView.from(load(organizationId));
    }

    @TenantTransactional
    public OrganizationView update(UUID organizationId, @Valid UpdateOrganizationCommand command) {
        TenantContextHolder.require().requireOrganization(organizationId);
        Organization organization = load(organizationId);
        organization.rename(command.name());
        organizations.save(organization);
        audit.record(AuditRecord.builder("organization.updated")
                .resource("organization", organizationId)
                .metadata(Map.of("name", organization.getName()))
                .build());
        return OrganizationView.from(organization);
    }

    /**
     * Closes the organization.
     *
     * <p>This never deletes data. Content stays exportable for the retention
     * window, and the export API is the customer's escape hatch — a customer must
     * never be locked into the platform by deletion.
     */
    @TenantTransactional
    public void close(UUID organizationId) {
        TenantContextHolder.require().requireOrganization(organizationId);
        Organization organization = load(organizationId);
        organization.close();
        organizations.save(organization);
        audit.record(AuditRecord.builder("organization.closed")
                .resource("organization", organizationId)
                .result(AuditRecord.Result.SUCCESS)
                .build());
        events.publish(PlatformEvent.of("organization.closed", organizationId)
                .resource("organization", organizationId).build());
    }

    @TenantTransactional(readOnly = true)
    public Organization load(UUID organizationId) {
        // The tenant root is the only entity whose id is also its organization id,
        // so a plain primary key lookup is already tenant-scoped here.
        return organizations.findById(organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Organization", organizationId));
    }

    /** Records usage so plan limits are enforced against real numbers. */
    @TenantTransactional
    public void recordUsage(UUID organizationId, QuotaKey key, long delta) {
        quotas.record(organizationId, key, delta);
    }

    public record CreateOrganizationCommand(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 63) String slug,
            @Size(max = 64) String planCode,
            @Size(max = 32) String region,
            UUID ownerUserId) {
    }

    public record UpdateOrganizationCommand(@NotBlank @Size(max = 200) String name) {
    }

    public record OrganizationView(
            UUID id,
            String name,
            String slug,
            String status,
            String planCode,
            String region,
            String isolationMode,
            java.time.Instant createdAt) {

        public static OrganizationView from(Organization o) {
            return new OrganizationView(o.getId(), o.getName(), o.getSlug(), o.getStatus().name(),
                    o.getPlanCode(), o.getRegion(), o.getIsolationMode().name(), o.getCreatedAt());
        }
    }
}
