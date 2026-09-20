package com.hatis.platform.organization.port.out;

import com.hatis.platform.organization.domain.Membership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The seam other contexts use to ask the organization context about membership.
 *
 * <p>Authorization needs to know whether the caller belongs to the organization it is
 * acting on and in what state; nothing outside this context may read
 * {@code org_memberships} directly, because that table also carries invitations and
 * suspensions that callers must not be able to infer from a missing row.
 */
public interface MembershipLookup {

    /** The membership for a single user in a single organization, in any state. */
    Optional<Membership> find(UUID organizationId, UUID userId);

    /** Every membership the user currently holds that is active, ordered by join time. */
    List<Membership> findActiveByUser(UUID userId);
}
