package com.hatis.platform.analytics.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.analytics.adapter.persistence.Alert;
import com.hatis.platform.analytics.adapter.persistence.AnalyticsRepositories;
import com.hatis.platform.analytics.adapter.persistence.Dashboard;
import com.hatis.platform.analytics.adapter.persistence.MetricStore;
import com.hatis.platform.analytics.domain.MetricPoint;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.analytics.domain.MetricBucket;
import com.hatis.platform.analytics.domain.MetricSample;
import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Metrics, dashboards and alerts.
 *
 * <h2>What Phase 1 analytics is</h2>
 *
 * Numbers go in through the API, are stored bucketed, and come back either as a chart series or
 * as the input to an alert. That is a complete loop and it is deliberately narrow: the sources
 * and datasets in {@code anl_data_sources} and {@code anl_datasets} describe connections to
 * systems the platform does not have connectors for yet, and a connector that returned invented
 * rows would be worse than its absence. Bringing the platform's own events into metrics is the
 * same work as the rollup job, and it is Phase 2.
 *
 * <h2>Why queries are bounded</h2>
 *
 * {@link #series} refuses a window wider than {@link #MAX_WINDOW} and a bucket narrower than a
 * minute. Both are the difference between a query that reads an index and one that aggregates
 * a table: without the bound, a single request can be made to scan a year of samples, and the
 * first time that happens it happens to a customer.
 */
@Service
public class AnalyticsService {

    public static final Duration MAX_WINDOW = Duration.ofDays(90);

    public static final String READ_PERMISSION = "analytics:read";
    public static final String WRITE_PERMISSION = "analytics:write";

    private final MetricStore metrics;
    private final AnalyticsRepositories.DashboardRepository dashboards;
    private final AnalyticsRepositories.AlertRepository alerts;
    private final AuthorizationService authorization;
    private final QuotaService quotas;
    private final AuditRecorder audit;
    private final ObjectMapper mapper;

    public AnalyticsService(MetricStore metrics,
                            AnalyticsRepositories.DashboardRepository dashboards,
                            AnalyticsRepositories.AlertRepository alerts,
                            AuthorizationService authorization,
                            QuotaService quotas,
                            AuditRecorder audit,
                            ObjectMapper mapper) {
        this.metrics = metrics;
        this.dashboards = dashboards;
        this.alerts = alerts;
        this.authorization = authorization;
        this.quotas = quotas;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- metrics

    public record RecordMetricCommand(String key, String name, String unit, MetricAggregation aggregation,
                                      BigDecimal value, Map<String, Object> dimension, Instant bucketStart) {
    }

    /** Records one measurement. */
    @TenantTransactional
    public void record(RecordMetricCommand command) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        MetricSample sample = new MetricSample(organizationId, command.key(), command.name(),
                command.unit(), command.aggregation(), command.value(), command.dimension(),
                command.bucketStart() == null ? Instant.now() : command.bucketStart());
        metrics.append(sample);
    }

    public record SeriesQuery(String key, MetricAggregation aggregation, MetricBucket bucket,
                              Instant from, Instant to, Map<String, Object> dimension) {
    }

    /** A bucketed series, ready to chart. */
    @TenantTransactional(readOnly = true)
    public List<MetricPoint> series(SeriesQuery query) {
        UUID organizationId = requireTenant(READ_PERMISSION, ScopeType.ORGANIZATION, null);
        if (query.key() == null || query.key().isBlank()) {
            throw new PlatformExceptions.Validation("A metric key is required", Map.of("field", "key"));
        }
        Instant to = query.to() == null ? Instant.now() : query.to();
        Instant from = query.from() == null ? to.minus(Duration.ofDays(1)) : query.from();
        if (!from.isBefore(to)) {
            throw new PlatformExceptions.Validation("The series window must end after it starts",
                    Map.of("field", "from"));
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new PlatformExceptions.Validation(
                    "A series window may not exceed " + MAX_WINDOW.toDays() + " days",
                    Map.of("field", "from", "maxDays", String.valueOf(MAX_WINDOW.toDays())));
        }
        MetricAggregation aggregation = query.aggregation() == null
                ? defaultAggregation(organizationId, query.key())
                : query.aggregation();
        MetricBucket bucket = query.bucket() == null ? MetricBucket.HOUR : query.bucket();
        return metrics.series(organizationId, query.key().trim(), aggregation, bucket, from, to,
                query.dimension());
    }

    private MetricAggregation defaultAggregation(UUID organizationId, String key) {
        MetricAggregation stored = metrics.preferredAggregation(organizationId, key.trim());
        return stored == null ? MetricAggregation.SUM : stored;
    }

    // ------------------------------------------------------------- dashboards

    public record DashboardView(UUID id, UUID projectId, String name, String layout, String visibility,
                                UUID createdBy, Instant createdAt, Instant updatedAt) {

        public static DashboardView from(Dashboard dashboard) {
            return new DashboardView(dashboard.getId(), dashboard.getProjectId(), dashboard.getName(),
                    dashboard.getLayout(), dashboard.getVisibility().name(), dashboard.getCreatedBy(),
                    dashboard.getCreatedAt(), dashboard.getUpdatedAt());
        }
    }

    @TenantTransactional(readOnly = true)
    public PageResponse<DashboardView> listDashboards(int page, int size) {
        UUID organizationId = requireTenant(READ_PERMISSION, ScopeType.ORGANIZATION, null);
        var pageable = PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponse.from(dashboards.findByOrganizationId(organizationId, pageable),
                DashboardView::from);
    }

    @TenantTransactional(readOnly = true)
    public DashboardView dashboard(UUID dashboardId) {
        UUID organizationId = requireTenant(READ_PERMISSION, ScopeType.ORGANIZATION, null);
        return DashboardView.from(requireDashboard(organizationId, dashboardId));
    }

    @TenantTransactional
    public DashboardView createDashboard(UUID projectId, String name, String layout, String visibility) {
        UUID organizationId = requireTenant(WRITE_PERMISSION,
                projectId == null ? ScopeType.ORGANIZATION : ScopeType.PROJECT, projectId);
        quotas.check(organizationId, QuotaKey.DASHBOARDS, 1);
        Dashboard dashboard = dashboards.save(new Dashboard(organizationId, projectId, name,
                validatedLayout(layout), parseVisibility(visibility),
                TenantContextHolder.require().principalId()));
        quotas.record(organizationId, QuotaKey.DASHBOARDS, 1);
        audit.record(AuditRecord.builder("analytics.dashboard.created")
                .resource("dashboard", dashboard.getId())
                .metadata(Map.of("name", dashboard.getName()))
                .build());
        return DashboardView.from(dashboard);
    }

    @TenantTransactional
    public DashboardView updateDashboard(UUID dashboardId, String name, String layout, String visibility) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        Dashboard dashboard = requireDashboard(organizationId, dashboardId);
        if (name != null) {
            dashboard.rename(name);
        }
        if (layout != null) {
            dashboard.changeLayout(validatedLayout(layout));
        }
        if (visibility != null) {
            dashboard.changeVisibility(parseVisibility(visibility));
        }
        return DashboardView.from(dashboards.save(dashboard));
    }

    @TenantTransactional
    public void deleteDashboard(UUID dashboardId) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        Dashboard dashboard = requireDashboard(organizationId, dashboardId);
        dashboards.delete(dashboard);
        quotas.record(organizationId, QuotaKey.DASHBOARDS, -1);
        audit.record(AuditRecord.builder("analytics.dashboard.deleted")
                .resource("dashboard", dashboard.getId())
                .build());
    }

    // ----------------------------------------------------------------- alerts

    public record AlertView(UUID id, String metricKey, String condition, BigDecimal threshold,
                            int windowMinutes, List<String> notifyChannels, List<UUID> notifyUserIds,
                            String status, Instant lastTriggeredAt, Instant createdAt) {

        public static AlertView from(Alert alert) {
            return new AlertView(alert.getId(), alert.getMetricKey(), alert.getCondition().name(),
                    alert.getThreshold(), alert.getWindowMinutes(),
                    alert.channels().stream().map(Enum::name).toList(),
                    alert.recipients(), alert.getStatus().name(), alert.getLastTriggeredAt(),
                    alert.getCreatedAt());
        }
    }

    public record SaveAlertCommand(String metricKey, String condition, BigDecimal threshold,
                                   Integer windowMinutes, List<String> notifyChannels,
                                   List<UUID> notifyUserIds) {
    }

    @TenantTransactional(readOnly = true)
    public PageResponse<AlertView> listAlerts(String status, int page, int size) {
        UUID organizationId = requireTenant(READ_PERMISSION, ScopeType.ORGANIZATION, null);
        var pageable = PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = status == null || status.isBlank()
                ? alerts.findByOrganizationId(organizationId, pageable)
                : alerts.findByOrganizationIdAndStatus(organizationId, parseAlertStatus(status), pageable);
        return PageResponse.from(result, AlertView::from);
    }

    @TenantTransactional(readOnly = true)
    public AlertView alert(UUID alertId) {
        UUID organizationId = requireTenant(READ_PERMISSION, ScopeType.ORGANIZATION, null);
        return AlertView.from(requireAlert(organizationId, alertId));
    }

    @TenantTransactional
    public AlertView createAlert(SaveAlertCommand command) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        Alert alert = alerts.save(new Alert(organizationId, command.metricKey(),
                parseCondition(command.condition()), command.threshold(),
                command.windowMinutes() == null ? 60 : command.windowMinutes(),
                parseChannels(command.notifyChannels()), command.notifyUserIds()));
        audit.record(AuditRecord.builder("analytics.alert.created")
                .resource("alert", alert.getId())
                .metadata(Map.of("metricKey", alert.getMetricKey(), "condition", alert.getCondition().name(),
                        "threshold", alert.getThreshold().toPlainString()))
                .build());
        return AlertView.from(alert);
    }

    @TenantTransactional
    public AlertView updateAlert(UUID alertId, SaveAlertCommand command) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        Alert alert = requireAlert(organizationId, alertId);
        alert.changeCondition(command.condition() == null ? null : parseCondition(command.condition()),
                command.threshold(), command.windowMinutes());
        if (command.notifyChannels() != null || command.notifyUserIds() != null) {
            alert.changeDelivery(command.notifyChannels() == null
                            ? alert.channels()
                            : parseChannels(command.notifyChannels()),
                    command.notifyUserIds() == null ? alert.recipients() : command.notifyUserIds());
        }
        return AlertView.from(alerts.save(alert));
    }

    @TenantTransactional
    public AlertView pauseAlert(UUID alertId) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        Alert alert = requireAlert(organizationId, alertId);
        alert.pause();
        return AlertView.from(alerts.save(alert));
    }

    @TenantTransactional
    public AlertView resumeAlert(UUID alertId) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        Alert alert = requireAlert(organizationId, alertId);
        alert.resume();
        return AlertView.from(alerts.save(alert));
    }

    @TenantTransactional
    public void deleteAlert(UUID alertId) {
        UUID organizationId = requireTenant(WRITE_PERMISSION, ScopeType.ORGANIZATION, null);
        alerts.delete(requireAlert(organizationId, alertId));
    }

    // --------------------------------------------------------------- helpers

    private UUID requireTenant(String permission, ScopeType scopeType, UUID scopeId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require(permission, scopeType, scopeId == null ? organizationId : scopeId);
        return organizationId;
    }

    private Dashboard requireDashboard(UUID organizationId, UUID dashboardId) {
        return dashboards.findByIdAndOrganizationId(dashboardId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Dashboard", dashboardId));
    }

    private Alert requireAlert(UUID organizationId, UUID alertId) {
        return alerts.findByIdAndOrganizationId(alertId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Alert", alertId));
    }

    private String validatedLayout(String layout) {
        if (layout == null || layout.isBlank()) {
            throw new PlatformExceptions.Validation("A dashboard layout is required",
                    Map.of("field", "layout"));
        }
        try {
            JsonNode parsed = mapper.readTree(layout);
            if (!parsed.isObject()) {
                throw new PlatformExceptions.Validation("A dashboard layout must be a JSON object",
                        Map.of("field", "layout"));
            }
            // Re-serialised so what is stored is the parsed document, not the caller's spacing.
            return mapper.writeValueAsString(parsed);
        } catch (PlatformExceptions.Validation e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformExceptions.Validation("A dashboard layout must be valid JSON",
                    Map.of("field", "layout"));
        }
    }

    private static Dashboard.Visibility parseVisibility(String value) {
        if (value == null || value.isBlank()) {
            return Dashboard.Visibility.PRIVATE;
        }
        try {
            return Dashboard.Visibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PlatformExceptions.Validation("Unknown dashboard visibility '" + value + "'",
                    Map.of("field", "visibility"));
        }
    }

    private static Alert.Status parseAlertStatus(String value) {
        try {
            return Alert.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PlatformExceptions.Validation("Unknown alert status '" + value + "'",
                    Map.of("field", "status"));
        }
    }

    private static com.hatis.platform.analytics.domain.AlertCondition parseCondition(String value) {
        if (value == null || value.isBlank()) {
            throw new PlatformExceptions.Validation("An alert needs a condition",
                    Map.of("field", "condition"));
        }
        try {
            return com.hatis.platform.analytics.domain.AlertCondition
                    .valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PlatformExceptions.Validation("Unknown alert condition '" + value + "'",
                    Map.of("field", "condition"));
        }
    }

    private static List<Notification.Channel> parseChannels(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> {
            try {
                return Notification.Channel.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new PlatformExceptions.Validation("Unknown notification channel '" + value + "'",
                        Map.of("field", "notifyChannels"));
            }
        }).toList();
    }
}
