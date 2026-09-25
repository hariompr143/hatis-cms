package com.hatis.platform.analytics.adapter.rest;

import com.hatis.platform.analytics.application.AnalyticsService;
import com.hatis.platform.analytics.application.AlertWork;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.analytics.domain.MetricPoint;
import com.hatis.platform.analytics.domain.MetricBucket;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Metrics, dashboards and alerts.
 *
 * <p>Everything here goes through {@link AnalyticsService}, which checks
 * {@code analytics:read} or {@code analytics:write} at the scope the request belongs to and, for
 * dashboards, enforces the plan's {@code dashboards} quota. The one exception is
 * {@link #evaluateAlerts}, which calls {@link AlertWork} directly: evaluation is the same code
 * the scheduled sweep runs, and duplicating it in the service so that it could carry its own
 * permission check would mean two implementations of the rule a customer is alerted by.
 */
@RestController
@RequestMapping("/v1/analytics")
@Tag(name = "Analytics", description = "Metrics, dashboards and alerts")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final AlertWork alertWork;

    public AnalyticsController(AnalyticsService analytics, AlertWork alertWork) {
        this.analytics = analytics;
        this.alertWork = alertWork;
    }

    // ---------------------------------------------------------------- metrics

    @PostMapping("/metrics")
    @Operation(summary = "Record a metric sample")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void record(@Valid @RequestBody RecordMetricRequest request) {
        analytics.record(new AnalyticsService.RecordMetricCommand(request.key(), request.name(),
                request.unit(), request.aggregation(), request.value(), request.dimension(),
                request.bucketStart()));
    }

    @GetMapping("/metrics/{key}/series")
    @Operation(summary = "Read a bucketed series for one metric")
    public List<MetricPoint> series(
            @PathVariable String key,
            @RequestParam(required = false) MetricBucket bucket,
            @RequestParam(required = false) MetricAggregation aggregation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return analytics.series(new AnalyticsService.SeriesQuery(key, aggregation, bucket, from, to, null));
    }

    // ------------------------------------------------------------- dashboards

    @GetMapping("/dashboards")
    @Operation(summary = "List dashboards")
    public PageResponse<AnalyticsService.DashboardView> dashboards(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return analytics.listDashboards(page, size);
    }

    @GetMapping("/dashboards/{dashboardId}")
    @Operation(summary = "Read one dashboard")
    public AnalyticsService.DashboardView dashboard(@PathVariable UUID dashboardId) {
        return analytics.dashboard(dashboardId);
    }

    @PostMapping("/dashboards")
    @Operation(summary = "Create a dashboard")
    public ResponseEntity<AnalyticsService.DashboardView> createDashboard(
            @Valid @RequestBody SaveDashboardRequest request) {
        var created = analytics.createDashboard(request.projectId(), request.name(), request.layout(),
                request.visibility());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/dashboards/{dashboardId}")
    @Operation(summary = "Update a dashboard")
    public AnalyticsService.DashboardView updateDashboard(@PathVariable UUID dashboardId,
                                                          @Valid @RequestBody SaveDashboardRequest request) {
        return analytics.updateDashboard(dashboardId, request.name(), request.layout(),
                request.visibility());
    }

    @DeleteMapping("/dashboards/{dashboardId}")
    @Operation(summary = "Delete a dashboard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDashboard(@PathVariable UUID dashboardId) {
        analytics.deleteDashboard(dashboardId);
    }

    // ----------------------------------------------------------------- alerts

    @GetMapping("/alerts")
    @Operation(summary = "List alerts")
    public PageResponse<AnalyticsService.AlertView> alerts(@RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "25") int size) {
        return analytics.listAlerts(status, page, size);
    }

    @GetMapping("/alerts/{alertId}")
    @Operation(summary = "Read one alert")
    public AnalyticsService.AlertView alert(@PathVariable UUID alertId) {
        return analytics.alert(alertId);
    }

    @PostMapping("/alerts")
    @Operation(summary = "Create an alert")
    public ResponseEntity<AnalyticsService.AlertView> createAlert(@Valid @RequestBody SaveAlertRequest request) {
        var created = analytics.createAlert(new AnalyticsService.SaveAlertCommand(request.metricKey(),
                request.condition(), request.threshold(), request.windowMinutes(),
                request.notifyChannels(), request.notifyUserIds()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/alerts/{alertId}")
    @Operation(summary = "Update an alert")
    public AnalyticsService.AlertView updateAlert(@PathVariable UUID alertId,
                                                  @Valid @RequestBody SaveAlertRequest request) {
        return analytics.updateAlert(alertId, new AnalyticsService.SaveAlertCommand(request.metricKey(),
                request.condition(), request.threshold(), request.windowMinutes(),
                request.notifyChannels(), request.notifyUserIds()));
    }

    @PostMapping("/alerts/{alertId}/pause")
    @Operation(summary = "Pause an alert")
    public AnalyticsService.AlertView pauseAlert(@PathVariable UUID alertId) {
        return analytics.pauseAlert(alertId);
    }

    @PostMapping("/alerts/{alertId}/resume")
    @Operation(summary = "Resume an alert")
    public AnalyticsService.AlertView resumeAlert(@PathVariable UUID alertId) {
        return analytics.resumeAlert(alertId);
    }

    @DeleteMapping("/alerts/{alertId}")
    @Operation(summary = "Delete an alert")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlert(@PathVariable UUID alertId) {
        analytics.deleteAlert(alertId);
    }

    /**
     * Evaluates this organization's alerts now.
     *
     * <p>Useful after loading history, and useful in an incident: an operator can ask "would
     * this fire" without waiting for the sweep. It runs the identical code path, so the answer
     * is the answer the sweep would give — including the window during which an alert that
     * already fired will not fire again.
     */
    @PostMapping("/alerts/evaluate")
    @Operation(summary = "Evaluate this organization's alerts now")
    public Map<String, Integer> evaluateAlerts() {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return Map.of("fired", alertWork.evaluate(organizationId));
    }

    // ---------------------------------------------------------------- records

    public record RecordMetricRequest(
            @NotBlank @Size(max = 120) String key,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 32) String unit,
            @NotNull MetricAggregation aggregation,
            @NotNull @Digits(integer = 18, fraction = 6) BigDecimal value,
            Map<String, Object> dimension,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant bucketStart) {
    }

    public record SaveDashboardRequest(
            UUID projectId,
            @NotBlank @Size(max = 200) String name,
            @NotBlank String layout,
            @Size(max = 20) String visibility) {
    }

    public record SaveAlertRequest(
            @NotBlank @Size(max = 120) String metricKey,
            @NotBlank @Size(max = 10) String condition,
            @NotNull @Digits(integer = 18, fraction = 6) BigDecimal threshold,
            @Min(1) @Max(43_200) Integer windowMinutes,
            List<String> notifyChannels,
            List<UUID> notifyUserIds) {
    }
}
