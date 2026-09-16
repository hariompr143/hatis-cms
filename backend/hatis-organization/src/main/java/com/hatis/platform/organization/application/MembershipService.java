package com.hatis.platform.organization.application;

import com.hatis.platform.organization.adapter.persistence.OrganizationRepositories;
import com.hatis.platform.organization.domain.Membership;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Membership lifecycle.
 *
 * <p>Two invariants are enforced here because they are security invariants, not
 * preferences: an organization always has at least one active owner, and removing
 * the last owner is refused rather than silently allowed.
 */
@Service
public class MembershipService {

    public static final String OWNER_ROLE = "OWNER";

    private final OrganizationRepositories.MembershipRepository memberships;
    private final AuditRecorder audit;

    public MembershipService(OrganizationRepositories.MembershipRepository memberships, AuditRecorder audit) {
        this.memberships = memberships;
        this.audit = audit;
    }

    /** Adds the founding owner. Called from the organization creation transaction. */
    public void addOwner(UUID organizationId, UUID userId) {
        if (userId == null) {
            throw new PlatformExceptions.Validation("ownerUserId is required to create an organization",
                    java.util.Map.of());
        }
        memberships.findByOrganizationIdAndUserId(organizationId, userId).ifPresent(existing -> {
            throw new PlatformExceptions.AlreadyExists("The user is already a member of this organization");
        });
        Membership membership = new Membership(organizationId, userId, OWNER_ROLE, null);
        membership.accept();
        memberships.save(membership);
    }

    @TenantTransactional
    public MembershipView invite(UUID organizationId, UUID userId, String role) {
        TenantContextHolder.require().requireOrganization(organizationId);
        memberships.findByOrganizationIdAndUserId(organizationId, userId).ifPresent(existing -> {
            throw new PlatformExceptions.AlreadyExists("The user is already a member of this organization");
        });
        Membership membership = memberships.save(
                new Membership(organizationId, userId, role, TenantContextHolder.require().principalId()));
        audit.record(AuditRecord.builder("membership.invited")
                .resource("membership", membership.getId())
                .metadata(java.util.Map.of("userId", userId.toString(), "role", membership.getRole()))
                .build());
        return MembershipView.from(membership);
    }

    @TenantTransactional
    public MembershipView changeRole(UUID organizationId, UUID membershipId, String role) {
        TenantContextHolder.require().requireOrganization(organizationId);
        Membership membership = load(organizationId, membershipId);
        boolean demotingOwner = OWNER_ROLE.equals(membership.getRole()) && !OWNER_ROLE.equals(role);
        if (demotingOwner && activeOwnerCount(organizationId) <= 1) {
            throw new PlatformExceptions.StateConflict(
                    "An organization must keep at least one active owner. Transfer ownership first.");
        }
        membership.changeRole(role);
        memberships.save(membership);
        audit.record(AuditRecord.builder("membership.role_changed")
                .resource("membership", membershipId)
                .metadata(java.util.Map.of("role", role))
                .build());
        return MembershipView.from(membership);
    }

    @TenantTransactional
    public void remove(UUID organizationId, UUID membershipId) {
        TenantContextHolder.require().requireOrganization(organizationId);
        Membership membership = load(organizationId, membershipId);
        if (OWNER_ROLE.equals(membership.getRole()) && membership.isActive() && activeOwnerCount(organizationId) <= 1) {
            throw new PlatformExceptions.StateConflict(
                    "The last active owner cannot be removed. Transfer ownership first.");
        }
        membership.remove();
        memberships.save(membership);
        audit.record(AuditRecord.builder("membership.removed")
                .resource("membership", membershipId)
                .build());
    }

    @TenantTransactional(readOnly = true)
    public List<MembershipView> listActive(UUID organizationId) {
        TenantContextHolder.require().requireOrganization(organizationId);
        return memberships.findByOrganizationIdAndStatus(organizationId, Membership.Status.ACTIVE,
                        org.springframework.data.domain.Pageable.unpaged())
                .stream().map(MembershipView::from).toList();
    }

    private long activeOwnerCount(UUID organizationId) {
        return memberships.countByOrganizationIdAndRoleAndStatus(organizationId, OWNER_ROLE, Membership.Status.ACTIVE);
    }

    private Membership load(UUID organizationId, UUID membershipId) {
        return memberships.findByIdAndOrganizationId(membershipId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Membership", membershipId));
    }

    public record MembershipView(UUID id, UUID userId, String role, String status,
                                 java.time.Instant invitedAt, java.time.Instant joinedAt) {

        public static MembershipView from(Membership m) {
            return new MembershipView(m.getId(), m.getUserId(), m.getRole(), m.getStatus().name(),
                    m.getInvitedAt(), m.getJoinedAt());
        }
    }
}
