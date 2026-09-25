package com.hatis.platform.analytics.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.analytics.adapter.persistence.Alert;
import com.hatis.platform.analytics.adapter.persistence.AnalyticsRepositories;
import com.hatis.platform.analytics.adapter.persistence.Dashboard;
import com.hatis.platform.analytics.adapter.persistence.MetricStore;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.analytics.domain.MetricBucket;
import com.hatis.platform.analytics.domain.MetricPoint;
import com.hatis.platform.analytics.domain.MetricSample;
import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The analytics service: what it refuses, and in what order it does things.
 *
 * <p>Three refusals carry the weight. A series window wider than {@code MAX_WINDOW} is refused
 * because it is the difference between reading an index and aggregating a table. A dashboard
 * layout that is not a JSON object is refused before the insert, because a {@code jsonb} column
 * would reject it anyway and the message should be one a customer can act on. And the quota is
 * consulted before the write and recorded after it, which is the only order that cannot leave a
 * customer over quota with nothing to show for it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Analytics service")
class AnalyticsServiceTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();

    @Mock
    private MetricStore metrics;
    @Mock
    private AnalyticsRepositories.DashboardRepository dashboards;
    @Mock
    private AnalyticsRepositories.AlertRepository alerts;
    @Mock
    private AuthorizationService authorization;
    @Mock
    private QuotaService quotas;
    @Mock
    private AuditRecorder audit;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(metrics, dashboards, alerts, authorization, quotas, audit,
                new ObjectMapper());
        TenantContextHolder.set(TenantContext.of(organizationId, principalId,
                TenantContext.PrincipalType.USER));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    // ---------------------------------------------------------------- metrics

    @Test
    @DisplayName("recording a metric checks the write permission and stamps the bucket")
    void recordingAMetricChecksThePermission() {
        service.record(new AnalyticsService.RecordMetricCommand("cms.pages.published", "Pages published",
                "count", MetricAggregation.SUM, new BigDecimal("3"), Map.of("project", "web"), null));

        verify(authorization).require(AnalyticsService.WRITE_PERMISSION, ScopeType.ORGANIZATION,
                organizationId);

        ArgumentCaptor<MetricSample> stored = ArgumentCaptor.forClass(MetricSample.class);
        verify(metrics).append(stored.capture());
        assertThat(stored.getValue().organizationId()).isEqualTo(organizationId);
        assertThat(stored.getValue().key()).isEqualTo("cms.pages.published");
        assertThat(stored.getValue().dimension()).containsEntry("project", "web");
        assertThat(stored.getValue().bucketStart())
                .as("a caller that names no bucket means now, truncated to the minute")
                .isNotNull();
    }

    @Test
    @DisplayName("a meter that cannot be formed is refused before anything is written")
    void asampleThatDoesNotValidateIsNotStored() {
        assertThatThrownBy(() -> service.record(new AnalyticsService.RecordMetricCommand("  ",
                "Pages published", "count", MetricAggregation.SUM, new BigDecimal("3"), Map.of(), null)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("key");

        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("a series window is bounded to ninety days and must move forward")
    void aSeriesWindowIsBounded() {
        Instant to = Instant.parse("2026-09-25T00:00:00Z");

        assertThatThrownBy(() -> service.series(new AnalyticsService.SeriesQuery("cms.pages.published",
                MetricAggregation.SUM, MetricBucket.DAY, to.minus(Duration.ofDays(91)), to, null)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("90 days");

        assertThatThrownBy(() -> service.series(new AnalyticsService.SeriesQuery("cms.pages.published",
                MetricAggregation.SUM, MetricBucket.DAY, to, to, null)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("after it starts");

        assertThatThrownBy(() -> service.series(new AnalyticsService.SeriesQuery("  ",
                MetricAggregation.SUM, MetricBucket.DAY, null, null, null)))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("key");

        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("a series with no aggregation takes the one the metric declares")
    void aSeriesUsesTheMetricsOwnAggregation() {
        when(metrics.preferredAggregation(organizationId, "storage.bytes"))
                .thenReturn(MetricAggregation.LAST);
        when(metrics.series(eq(organizationId), eq("storage.bytes"), eq(MetricAggregation.LAST),
                eq(MetricBucket.HOUR), any(Instant.class), any(Instant.class), isNull()))
                .thenReturn(List.of(new MetricPoint(Instant.parse("2026-09-25T00:00:00Z"),
                        new BigDecimal("42"))));

        List<MetricPoint> points = service.series(new AnalyticsService.SeriesQuery("storage.bytes",
                null, null, null, null, null));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).isEqualByComparingTo("42");
    }

    @Test
    @DisplayName("a metric nobody has ever reported falls back to summing")
    void aSeriesFallsBackToSum() {
        when(metrics.preferredAggregation(organizationId, "cms.pages.published")).thenReturn(null);
        when(metrics.series(eq(organizationId), eq("cms.pages.published"), eq(MetricAggregation.SUM),
                eq(MetricBucket.HOUR), any(Instant.class), any(Instant.class), isNull()))
                .thenReturn(List.of());

        assertThat(service.series(new AnalyticsService.SeriesQuery("cms.pages.published", null, null,
                null, null, null))).isEmpty();
    }

    // ------------------------------------------------------------- dashboards

    @Test
    @DisplayName("a dashboard is quota-checked before it is saved and recorded after")
    void aDashboardIsQuotaCheckedAndRecorded() {
        when(dashboards.save(any(Dashboard.class))).thenAnswer(call -> call.getArgument(0));

        AnalyticsService.DashboardView created = service.createDashboard(null, "Operations",
                "{\"widgets\":[]}", "PRIVATE");

        InOrder order = inOrder(quotas, dashboards);
        order.verify(quotas).check(organizationId, QuotaKey.DASHBOARDS, 1);
        order.verify(dashboards).save(any(Dashboard.class));
        order.verify(quotas).record(organizationId, QuotaKey.DASHBOARDS, 1);

        assertThat(created.name()).isEqualTo("Operations");
        assertThat(created.visibility()).isEqualTo("PRIVATE");

        ArgumentCaptor<AuditRecord> audited = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().action()).isEqualTo("analytics.dashboard.created");
    }

    @Test
    @DisplayName("a layout has to be a JSON object, and the reason says which way it failed")
    void aLayoutHasToBeAJsonObject() {
        assertThatThrownBy(() -> service.createDashboard(null, "Operations", "[1,2]", null))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> service.createDashboard(null, "Operations", "not json", null))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> service.createDashboard(null, "Operations", "  ", null))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("layout");
        assertThatThrownBy(() -> service.createDashboard(null, "Operations", "{\"a\":1}", "PUBLIC"))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("visibility");

        verify(dashboards, never()).save(any(Dashboard.class));
    }

    @Test
    @DisplayName("an unknown visibility is refused, and a known one is stored by name")
    void aVisibilityIsParsed() {
        when(dashboards.save(any(Dashboard.class))).thenAnswer(call -> call.getArgument(0));

        AnalyticsService.DashboardView created = service.createDashboard(null, "Operations",
                "{\"widgets\":[]}", "organization");

        ArgumentCaptor<Dashboard> saved = ArgumentCaptor.forClass(Dashboard.class);
        verify(dashboards).save(saved.capture());
        assertThat(saved.getValue().getVisibility()).isEqualTo(Dashboard.Visibility.ORGANIZATION);
        assertThat(created.visibility()).isEqualTo("ORGANIZATION");
    }

    @Test
    @DisplayName("a dashboard of another tenant is not found, and the query says so")
    void anotherTenantsDashboardIsNotFound() {
        UUID dashboardId = UUID.randomUUID();
        when(dashboards.findByIdAndOrganizationId(dashboardId, organizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dashboard(dashboardId))
                .isInstanceOf(PlatformExceptions.NotFound.class);
    }

    // ----------------------------------------------------------------- alerts

    @Test
    @DisplayName("an alert created without a window watches an hour")
    void anAlertDefaultsToAnHour() {
        when(alerts.save(any(Alert.class))).thenAnswer(call -> call.getArgument(0));

        AnalyticsService.AlertView created = service.createAlert(new AnalyticsService.SaveAlertCommand(
                "storage.bytes", "GT", new BigDecimal("100"), null, List.of("IN_APP"),
                List.of(principalId)));

        assertThat(created.windowMinutes()).isEqualTo(60);
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.notifyChannels()).containsExactly("IN_APP");
        assertThat(created.notifyUserIds()).containsExactly(principalId);

        ArgumentCaptor<AuditRecord> audited = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().action()).isEqualTo("analytics.alert.created");
    }

    @Test
    @DisplayName("conditions and channels arrive as text and are refused when unknown")
    void alertVocabularyIsClosed() {
        assertThatThrownBy(() -> service.createAlert(new AnalyticsService.SaveAlertCommand(
                "storage.bytes", " ", new BigDecimal("100"), 60, List.of("IN_APP"), List.of(principalId))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("condition");

        assertThatThrownBy(() -> service.createAlert(new AnalyticsService.SaveAlertCommand(
                "storage.bytes", "ABOVE", new BigDecimal("100"), 60, List.of("IN_APP"), List.of(principalId))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("Unknown alert condition");

        assertThatThrownBy(() -> service.createAlert(new AnalyticsService.SaveAlertCommand(
                "storage.bytes", "GT", new BigDecimal("100"), 60, List.of("CARRIER_PIGEON"),
                List.of(principalId))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("Unknown notification channel");

        assertThatThrownBy(() -> service.createAlert(new AnalyticsService.SaveAlertCommand(
                "storage.bytes", "GT", new BigDecimal("100"), 60, List.of(Notification.Channel.IN_APP.name()),
                List.of())))
                .as("an in-app alert nobody receives is worse than no alert")
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("recipient");

        verify(alerts, never()).save(any(Alert.class));
    }

    @Test
    @DisplayName("listing alerts filters by status only when one is named")
    void alertListingFiltersByStatus() {
        // Every argument is a matcher: Mockito refuses to mix a raw value with matchers.
        when(alerts.findByOrganizationId(eq(organizationId), any(Pageable.class)))
                .thenReturn(Page.empty());
        assertThat(service.listAlerts(null, 0, 25).items()).isEmpty();

        when(alerts.findByOrganizationIdAndStatus(eq(organizationId), eq(Alert.Status.PAUSED),
                any(Pageable.class))).thenReturn(Page.empty());
        assertThat(service.listAlerts("paused", 0, 25).items()).isEmpty();

        assertThatThrownBy(() -> service.listAlerts("SLEEPING", 0, 25))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("Unknown alert status");
    }
}
