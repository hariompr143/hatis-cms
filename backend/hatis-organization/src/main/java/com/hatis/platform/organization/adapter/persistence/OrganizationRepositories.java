package com.hatis.platform.organization.adapter.persistence;

import com.hatis.platform.organization.domain.Environment;
import com.hatis.platform.organization.domain.Membership;
import com.hatis.platform.organization.domain.Organization;
import com.hatis.platform.organization.domain.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapters for the organization context.
 *
 * <p>Every tenant-scoped finder takes the organization id explicitly. There are no
 * unscoped {@code findById} methods: forgetting the tenant must not compile.
 */
public class OrganizationRepositories {

    public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
        Optional<Organization> findBySlug(String slug);

        boolean existsBySlug(String slug);

        Page<Organization> findByStatus(Organization.Status status, Pageable pageable);

        List<Organization> findByStatus(Organization.Status status);
    }

    public interface ProjectRepository extends JpaRepository<Project, UUID> {
        Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Optional<Project> findByOrganizationIdAndSlug(UUID organizationId, String slug);

        boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);

        Page<Project> findByOrganizationIdAndStatusNot(UUID organizationId, Project.Status status, Pageable pageable);

        long countByOrganizationIdAndStatusNot(UUID organizationId, Project.Status status);
    }

    public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
        Optional<Environment> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<Environment> findByOrganizationIdAndProjectIdOrderByOrderIndexAsc(UUID organizationId, UUID projectId);

        Optional<Environment> findByOrganizationIdAndProjectIdAndSlug(UUID organizationId,
                                                                     UUID projectId,
                                                                     String slug);

        boolean existsByOrganizationIdAndProjectIdAndSlug(UUID organizationId, UUID projectId, String slug);

        long countByOrganizationId(UUID organizationId);
    }

    public interface MembershipRepository extends JpaRepository<Membership, UUID> {
        Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

        Optional<Membership> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Page<Membership> findByOrganizationIdAndStatus(UUID organizationId,
                                                       Membership.Status status,
                                                       Pageable pageable);

        List<Membership> findByUserIdAndStatus(UUID userId, Membership.Status status);

        long countByOrganizationIdAndStatus(UUID organizationId, Membership.Status status);

        long countByOrganizationIdAndRoleAndStatus(UUID organizationId, String role, Membership.Status status);
    }
}
