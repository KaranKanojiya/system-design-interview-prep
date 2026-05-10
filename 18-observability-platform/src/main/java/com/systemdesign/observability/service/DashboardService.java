package com.systemdesign.observability.service;

// Wiring: DashboardService aggregates data from the three pillars of observability.
// Dependencies injected via constructor:
//   metricService  — provides metric queries and aggregation
//   tracingService — provides trace and span data
//   logService     — provides structured log data

import com.systemdesign.observability.model.LogLevel;
import com.systemdesign.observability.model.MetricPoint;
import com.systemdesign.observability.model.Span;
import com.systemdesign.observability.model.SpanStatus;
import com.systemdesign.observability.model.Trace;
import com.systemdesign.observability.engine.MetricAggregator;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * DashboardService — builds dashboard views by combining metrics, traces, and logs.
 *
 * FLOW — getMetricSummary(metricName, window):
 *   1. Query MetricService for data points in the time window
 *   2. Use MetricAggregator to compute avg, min, max, p50, p95, p99, count, rate
 *   3. Return as a map of statistic name to value
 *
 * FLOW — getTraceSummary(serviceName):
 *   1. Query TracingService for traces by service
 *   2. Compute trace count, average duration, and error rate
 *   3. Return as a map
 *
 * FLOW — getSystemOverview():
 *   1. Gather high-level stats from all three observability pillars
 *   2. Format as a multi-line summary string
 */
public class DashboardService {

    private final MetricService metricService;      // metrics pillar
    private final TracingService tracingService;     // tracing pillar
    private final LogService logService;             // logging pillar

    public DashboardService(MetricService metricService, TracingService tracingService,
                            LogService logService) {
        this.metricService = metricService;
        this.tracingService = tracingService;
        this.logService = logService;
    }

    // ---- metric dashboard ----

    /**
     * Computes a statistical summary for the given metric over a time window.
     *
     * @param metricName the metric to summarize
     * @param window     the lookback duration from now
     * @return map with keys: "avg", "min", "max", "p50", "p95", "p99", "count", "rate"
     */
    public Map<String, Double> getMetricSummary(String metricName, Duration window) {
        Instant now = Instant.now();
        Instant from = now.minus(window);

        List<MetricPoint> points = metricService.query(metricName, from, now);
        MetricAggregator aggregator = metricService.getAggregator();

        Map<String, Double> summary = new LinkedHashMap<>();
        summary.put("avg", aggregator.average(points));
        summary.put("min", aggregator.min(points));
        summary.put("max", aggregator.max(points));
        summary.put("p50", aggregator.percentile(points, 50));
        summary.put("p95", aggregator.percentile(points, 95));
        summary.put("p99", aggregator.percentile(points, 99));
        summary.put("count", (double) aggregator.count(points));
        summary.put("rate", aggregator.rate(points));

        return summary;
    }

    // ---- trace dashboard ----

    /**
     * Computes a trace summary for the given service.
     *
     * @param serviceName the service to analyze
     * @return map with keys: "traceCount", "avgDurationMs", "errorRate"
     */
    public Map<String, Object> getTraceSummary(String serviceName) {
        List<Trace> traces = tracingService.getTracesByService(serviceName);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceCount", traces.size());

        if (traces.isEmpty()) {
            summary.put("avgDurationMs", 0.0);
            summary.put("errorRate", 0.0);
            return summary;
        }

        // Average duration across all traces
        double avgDuration = traces.stream()
                .filter(t -> t.getDuration() != null)
                .mapToLong(t -> t.getDuration().toMillis())
                .average()
                .orElse(0.0);
        summary.put("avgDurationMs", avgDuration);

        // Error rate: fraction of traces that contain at least one error span
        long errorTraces = traces.stream()
                .filter(t -> !t.getErrorSpans().isEmpty())
                .count();
        double errorRate = (double) errorTraces / traces.size();
        summary.put("errorRate", errorRate);

        return summary;
    }

    // ---- log dashboard ----

    /**
     * Returns log entry counts grouped by severity level for the given service.
     *
     * @param serviceName the service to analyze
     * @return map of LogLevel name to count
     */
    public Map<String, Long> getLogSummary(String serviceName) {
        Map<LogLevel, Long> countByLevel = logService.getLogCountByLevel();

        // Convert enum keys to string keys for dashboard display
        Map<String, Long> summary = new LinkedHashMap<>();
        for (LogLevel level : LogLevel.values()) {
            summary.put(level.name(), countByLevel.getOrDefault(level, 0L));
        }
        return summary;
    }

    // ---- system overview ----

    /**
     * Builds a formatted multi-line string with high-level stats across all services.
     *
     * @return a human-readable system overview
     */
    public String getSystemOverview() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== OBSERVABILITY PLATFORM — SYSTEM OVERVIEW ===\n\n");

        // Metrics overview
        Set<String> metricNames = metricService.getAllMetricNames();
        sb.append("--- Metrics ---\n");
        sb.append("  Total metric definitions: ").append(metricNames.size()).append("\n");
        for (String name : metricNames) {
            OptionalDouble latest = metricService.getLatestValue(name);
            sb.append("  ").append(name).append(" = ");
            if (latest.isPresent()) {
                sb.append(String.format("%.2f", latest.getAsDouble()));
            } else {
                sb.append("(no data)");
            }
            sb.append("\n");
        }

        // Traces overview
        sb.append("\n--- Traces ---\n");
        List<Trace> recentTraces = tracingService.getRecentTraces(5);
        sb.append("  Recent traces: ").append(recentTraces.size()).append("\n");
        for (Trace trace : recentTraces) {
            sb.append("  ").append(trace.getTraceId())
                    .append(" | spans=").append(trace.getSpanCount())
                    .append(" | duration=").append(trace.getDuration())
                    .append("\n");
        }

        // Logs overview
        sb.append("\n--- Logs ---\n");
        Map<LogLevel, Long> logCounts = logService.getLogCountByLevel();
        for (LogLevel level : LogLevel.values()) {
            long count = logCounts.getOrDefault(level, 0L);
            if (count > 0) {
                sb.append("  ").append(level.name()).append(": ").append(count).append("\n");
            }
        }

        sb.append("\n================================================");
        return sb.toString();
    }
}
