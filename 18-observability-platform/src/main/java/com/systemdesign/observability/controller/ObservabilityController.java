package com.systemdesign.observability.controller;

// Wiring: REST-like facade that simulates HTTP endpoints for the observability platform.
// Delegates to ObservabilityService (unified), AlertService, and DashboardService.

import com.systemdesign.observability.model.Alert;
import com.systemdesign.observability.model.LogLevel;
import com.systemdesign.observability.model.MetricType;
import com.systemdesign.observability.model.Span;
import com.systemdesign.observability.service.AlertService;
import com.systemdesign.observability.service.DashboardService;
import com.systemdesign.observability.service.ObservabilityService;

import java.util.List;
import java.util.Map;

/**
 * Simulated REST controller that logs each request with HTTP method and path,
 * then delegates to the appropriate service layer.
 */
public class ObservabilityController {

    private final ObservabilityService observabilityService;
    private final AlertService alertService;
    private final DashboardService dashboardService;

    public ObservabilityController(ObservabilityService observabilityService,
                                   AlertService alertService,
                                   DashboardService dashboardService) {
        this.observabilityService = observabilityService;
        this.alertService = alertService;
        this.dashboardService = dashboardService;
    }

    // ---- metric ingestion ----

    /** POST /metrics — ingest a single metric data point. */
    public void ingestMetric(String name, double value, MetricType type, Map<String, String> tags) {
        System.out.println("[CONTROLLER] POST /metrics — name=" + name + ", value=" + value + ", type=" + type);
        observabilityService.recordMetric(name, value, type, tags);
    }

    // ---- tracing ----

    /** POST /traces — start a new trace and return the root span. */
    public Span startTrace(String operationName, String serviceName) {
        System.out.println("[CONTROLLER] POST /traces — op=" + operationName + ", service=" + serviceName);
        return observabilityService.startTrace(operationName, serviceName);
    }

    /** PUT /traces/spans/{id}/finish — finish an active span. */
    public void finishSpan(Span span) {
        System.out.println("[CONTROLLER] PUT /traces/spans/" + span.getSpanId().substring(0, 8) + "/finish");
        observabilityService.finishSpan(span);
    }

    // ---- log ingestion ----

    /** POST /logs — ingest a structured log entry. */
    public void ingestLog(LogLevel level, String message, String serviceName) {
        System.out.println("[CONTROLLER] POST /logs — level=" + level + ", service=" + serviceName);
        observabilityService.log(level, message, serviceName);
    }

    // ---- alerting ----

    /** POST /alerts/evaluate — trigger alert rule evaluation against current metrics. */
    public void evaluateAlerts() {
        System.out.println("[CONTROLLER] POST /alerts/evaluate");
        alertService.evaluateRules();
    }

    /** GET /alerts — retrieve all fired alerts. */
    public List<Alert> getAlerts() {
        System.out.println("[CONTROLLER] GET /alerts");
        return alertService.getAllAlerts();
    }

    // ---- dashboard ----

    /** GET /dashboard — print a system overview to the console. */
    public void getDashboard() {
        System.out.println("[CONTROLLER] GET /dashboard");
        System.out.println(dashboardService.getSystemOverview());
    }
}
