package com.hatis.platform.deployment.adapter.persistence;

import com.hatis.platform.deployment.domain.Application;
import com.hatis.platform.deployment.domain.Deployment;
import com.hatis.platform.deployment.domain.Release;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepositories {

    interface ApplicationRepository extends JpaRepository<Application, UUID> {

        Optional<Application> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<Application> findByOrganizationIdAndProjectIdAndArchivedAtIsNull(UUID organizationId,
                                                                              UUID projectId);

        boolean existsByOrganizationIdAndProjectIdAndSlug(UUID organizationId, UUID projectId, String slug);
    }

    interface ReleaseRepository extends JpaRepository<Release, UUID> {

        Optional<Release> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Page<Release> findByOrganizationIdAndApplicationId(UUID organizationId,
                                                           UUID applicationId,
                                                           Pageable pageable);

        boolean existsByOrganizationIdAndApplicationIdAndVersion(UUID organizationId,
                                                                 UUID applicationId,
                                                                 String version);

        List<Release> findByOrganizationIdAndScanStatus(UUID organizationId, Release.ScanStatus scanStatus);
    }

    interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

        Optional<Deployment> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<Deployment> findByOrganizationIdAndApplicationIdAndEnvironmentIdOrderByCreatedAtDesc(
                UUID organizationId, UUID applicationId, UUID environmentId);

        List<Deployment> findByOrganizationIdAndApplicationIdOrderByCreatedAtDesc(UUID organizationId,
                                                                                  UUID applicationId);

        List<Deployment> findByOrganizationIdAndStatus(UUID organizationId, Deployment.Status status);
    }
}
