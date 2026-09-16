package com.hatis.platform.organization.adapter.persistence;

import com.hatis.platform.organization.domain.Membership;
import com.hatis.platform.organization.port.out.MembershipLookup;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapter exposing memberships to the authorization context. */
@Component
public class MembershipLookupAdapter implements MembershipLookup {

    private final OrganizationRepositories.MembershipRepository memberships;

    public MembershipLookupAdapter(OrganizationRepositories.MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    public Optional<Membership> find(UUID organizationId, UUID userId) {
        return memberships.findByOrganizationIdAndUserId(organizationId, userId);
    }

    @Override
    public List<Membership> findActiveByUser(UUID userId) {
        return memberships.findByUserIdAndStatus(userId, Membership.Status.ACTIVE);
    }
}
