package com.hatis.platform.analytics.adapter.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for dashboards and alerts.
 *
 * <p>Metrics are absent on purpose: they are written and aggregated in SQL through
 * {@link MetricStore}, so there is no aggregate to load.
 */
public interface AnalyticsRepositories {

    interface DashboardRepository extends JpaRepository<Dashboard, UUID> {

        Optional<Dashboard> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Page<Dashboard> findByOrganizationId(UUID organizationId, Pageable pageable);

        long countByOrganizationId(UUID organizationId);
    }

    interface AlertRepository extends JpaRepository<Alert, UUID> {

        Optional<Alert> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Page<Alert> findByOrganizationId(UUID organizationId, Pageable pageable);

        Page<Alert> findByOrganizationIdAndStatus(UUID organizationId, Alert.Status status, Pageable pageable);

        /**
         * Alerts that may still fire: everything that has not been paused.
         *
         * <p>{@code TRIGGERED} is included because it is a live state — the condition was met
         * once and the alert keeps watching until somebody pauses it. Excluding it would make
         * every alert fire exactly once and then go quiet, which is the opposite of what a
         * threshold is for.
         */
        List<Alert> findByOrganizationIdAndStatusIn(UUID organizationId, List<Alert.Status> statuses);
    }
}
