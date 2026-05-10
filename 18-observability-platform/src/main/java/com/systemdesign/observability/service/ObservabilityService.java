package com.systemdesign.observability.service;

// Wiring: ObservabilityService is the FACADE (GoF) for the entire observability platform.
// Dependencies injected via constructor — one for each sub-service:
//   metricService     — metric recording and querying
//   tracingService    — distributed trace lifecycle
//   logService        — structured log management
//   alertService      — alert rule evaluation
//   dashboardService  — aggregated dashboard views
//   serviceMapService — service dependency topology

import com.systemdesign.observability.model.LogLevel;
import com.systemdesign.observability.model.MetricType;
import com.systemdesign.observability.model.Span;

import java.util.Map;

/**
 * ObservabilityService — FACADE PATTERN (GoF) — single entry point for the observability platform.
 *
 * WHY A FACADE?
 *   Callers (controllers, demos, tests) interact with one class instead of six.
 *   The facade delegates each operation to the appropriate sub-service,
 *   hiding the internal wiring and making the API surface simple and cohesive.
 *
 * WHAT THIS FACADE WRAPS:
 *   - MetricService:     record and query time-series metrics
 *   - TracingService:    start/finish distributed traces and spans
 *   - LogService:        ingest and search structured logs
 *   - AlertService:      evaluate alert rules against metric data
 *   - DashboardService:  build aggregated views across all signals
 *   - ServiceMapService: track service-to-service dependencies
 *
 * CALL CHAIN EXAMPLE — recordMetric(name, value, type, tags):
 *   ObservabilityController.handleRecordMetric(...)
 *     -> ObservabilityService.recordMetric(...)
 *       -> MetricService.recordMetric(...)
 *         -> TimeSeriesStore.store(point)
 *         -> MetricRepository.save(metric)
 */
public class ObservabilityService {

    private final MetricService metricService;           // metrics pillar
    private final TracingService tracingService;         // tracing pillar
    private final LogService logService;                 // logging pillar
    private final AlertService alertService;             // alerting subsystem
    private final DashboardService dashboardService;     // dashboard aggregation
    private final ServiceMapService serviceMapService;   // service topology

    public ObservabilityService(MetricService metricService, TracingService tracingService,
                                LogService logService, AlertService alertService,
                                DashboardService dashboardService,
                                ServiceMapService serviceMapService) {
        this.metricService = metricService;
        this.tracingService = tracingService;
        this.logService = logService;
        this.alertService = alertService;
        this.dashboardService = dashboardService;
        this.serviceMapService = serviceMapService;
    }

    // ---- metrics ----

    /**
     * Records a metric data point. Delegates to {@link MetricService}.
     *
     * @param name  metric name (e.g. "http.request.duration")
     * @param value the numeric value
     * @param type  COUNTER, GAUGE, HISTOGRAM, or TIMER
     * @param tags  key-value metadata labels
     */
    public void recordMetric(String name, double value, MetricType type,
                             Map<String, String> tags) {
        metricService.recordMetric(name, value, type, tags);
    }

    // ---- tracing ----

    /**
     * Starts a new distributed trace and returns the root span.
     * Delegates to {@link TracingService}.
     *
     * @param operationName the entry-point operation name
     * @param serviceName   the service initiating the trace
     * @return the root Span
     */
    public Span startTrace(String operationName, String serviceName) {
        return tracingService.startTrace(operationName, serviceName);
    }

    /**
     * Creates a child span within an existing trace.
     * Delegates to {@link TracingService}.
     *
     * @param traceId       the parent trace's ID
     * @param parentSpanId  the parent span's ID
     * @param operationName the operation name for this span
     * @param serviceName   the service executing this span
     * @return the child Span
     */
    public Span startSpan(String traceId, String parentSpanId,
                          String operationName, String serviceName) {
        return tracingService.startSpan(traceId, parentSpanId, operationName, serviceName);
    }

    /**
     * Finishes a span and triggers trace assembly.
     * Delegates to {@link TracingService}.
     *
     * @param span the span to finish
     */
    public void finishSpan(Span span) {
        tracingService.finishSpan(span);
    }

    // ---- logging ----

    /**
     * Logs a structured entry. Delegates to {@link LogService}.
     *
     * @param level       the severity level
     * @param message     the log message
     * @param serviceName the originating service
     */
    public void log(LogLevel level, String message, String serviceName) {
        logService.log(level, message, serviceName);
    }

    /**
     * Logs a structured entry with trace correlation.
     * Delegates to {@link LogService}.
     *
     * @param level       the severity level
     * @param message     the log message
     * @param serviceName the originating service
     * @param traceId     the distributed trace ID
     * @param spanId      the span ID within the trace
     */
    public void logWithTrace(LogLevel level, String message, String serviceName,
                             String traceId, String spanId) {
        logService.logWithTrace(level, message, serviceName, traceId, spanId);
    }

    // ---- alerting ----

    /**
     * Evaluates all alert rules against recent metric data.
     * Delegates to {@link AlertService}.
     */
    public void evaluateAlerts() {
        alertService.evaluateRules();
    }

    // ---- dashboard ----

    /**
     * Returns a formatted system overview across all observability pillars.
     * Delegates to {@link DashboardService}.
     *
     * @return multi-line human-readable overview string
     */
    public String getSystemOverview() {
        return dashboardService.getSystemOverview();
    }

    // ---- service map ----

    /**
     * Registers an observed service-to-service call in the dependency graph.
     * Delegates to {@link ServiceMapService}.
     *
     * @param caller    the upstream service making the call
     * @param callee    the downstream service being called
     * @param latencyMs the call latency in milliseconds
     * @param success   true if the call succeeded
     */
    public void registerServiceCall(String caller, String callee,
                                    long latencyMs, boolean success) {
        serviceMapService.registerCall(caller, callee, latencyMs, success);
    }

    // ---- accessors for sub-services (used by controllers/demos) ----

    public MetricService getMetricService() { return metricService; }
    public TracingService getTracingService() { return tracingService; }
    public LogService getLogService() { return logService; }
    public AlertService getAlertService() { return alertService; }
    public DashboardService getDashboardService() { return dashboardService; }
    public ServiceMapService getServiceMapService() { return serviceMapService; }
}
