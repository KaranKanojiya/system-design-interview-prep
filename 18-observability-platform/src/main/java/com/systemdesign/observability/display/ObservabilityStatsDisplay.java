package com.systemdesign.observability.display;

// Wiring: Console output helper for observability data visualization.
// Reads from MetricService, TracingService, LogService, AlertService, ServiceMapService.

import com.systemdesign.observability.model.Alert;
import com.systemdesign.observability.model.LogEntry;
import com.systemdesign.observability.model.MetricPoint;
import com.systemdesign.observability.model.ServiceNode;
import com.systemdesign.observability.model.Span;
import com.systemdesign.observability.model.Trace;
import com.systemdesign.observability.engine.MetricAggregator;
import com.systemdesign.observability.service.AlertService;
import com.systemdesign.observability.service.LogService;
import com.systemdesign.observability.service.MetricService;
import com.systemdesign.observability.service.ServiceMapService;
import com.systemdesign.observability.service.TracingService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Console display helper that prints formatted observability data:
 * metric summaries, trace trees, log tables, alert status, and service maps.
 */
public class ObservabilityStatsDisplay {

    private final MetricService metricService;
    private final TracingService tracingService;
    private final LogService logService;
    private final AlertService alertService;
    private final ServiceMapService serviceMapService;

    public ObservabilityStatsDisplay(MetricService metricService,
                                     TracingService tracingService,
                                     LogService logService,
                                     AlertService alertService,
                                     ServiceMapService serviceMapService) {
        this.metricService = metricService;
        this.tracingService = tracingService;
        this.logService = logService;
        this.alertService = alertService;
        this.serviceMapService = serviceMapService;
    }

    // ========================================================================
    // Metric summary
    // ========================================================================

    /** Prints a table of metric statistics (avg, min, max, p50, p99, count). */
    public void printMetricSummary(String metricName) {
        printSeparator("METRIC SUMMARY: " + metricName);

        List<MetricPoint> points = metricService.query(metricName, Instant.EPOCH, Instant.now());
        if (points.isEmpty()) {
            System.out.println("  No data points found for metric: " + metricName);
            return;
        }

        MetricAggregator aggregator = metricService.getAggregator();
        double avg = aggregator.average(points);
        double min = aggregator.min(points);
        double max = aggregator.max(points);
        double p50 = aggregator.percentile(points, 50);
        double p99 = aggregator.percentile(points, 99);
        long count = points.size();

        System.out.printf("  %-12s %-12s %-12s %-12s %-12s %-12s%n",
                "AVG", "MIN", "MAX", "P50", "P99", "COUNT");
        System.out.printf("  %-12.2f %-12.2f %-12.2f %-12.2f %-12.2f %-12d%n",
                avg, min, max, p50, p99, count);
    }

    // ========================================================================
    // Trace tree
    // ========================================================================

    /** Prints a trace tree with span hierarchy indented by depth. */
    public void printTrace(Trace trace) {
        printSeparator("TRACE: " + truncateId(trace.getTraceId()));

        System.out.printf("  TraceId: %s | Spans: %d | Duration: %s%n",
                truncateId(trace.getTraceId()), trace.getSpanCount(),
                trace.getDuration() != null ? trace.getDuration().toMillis() + "ms" : "N/A");
        System.out.println();

        // Build the span tree and print recursively
        Map<String, List<Span>> spanTree = trace.buildSpanTree();
        printSpanTree(spanTree, "root", 0);
    }

    private void printSpanTree(Map<String, List<Span>> tree, String parentKey, int depth) {
        List<Span> children = tree.get(parentKey);
        if (children == null) return;

        for (Span span : children) {
            String indent = "  " + "  ".repeat(depth);
            String durationStr = span.getDuration() != null
                    ? span.getDuration().toMillis() + "ms" : "pending";
            String statusStr = span.getStatus() != null ? span.getStatus().name() : "ACTIVE";

            System.out.printf("%s|-- %-20s [%s] %8s  %s%n",
                    indent, span.getOperationName(), span.getServiceName(),
                    durationStr, statusStr);

            // Recurse into children of this span
            printSpanTree(tree, span.getSpanId(), depth + 1);
        }
    }

    // ========================================================================
    // Recent logs
    // ========================================================================

    /** Prints recent log entries in a formatted table. */
    public void printRecentLogs(int count) {
        printSeparator("RECENT LOGS (last " + count + ")");

        List<LogEntry> logs = logService.getRecentLogs(count);
        if (logs.isEmpty()) {
            System.out.println("  No log entries found.");
            return;
        }

        System.out.printf("  %-24s %-8s %-15s %-30s %-10s%n",
                "TIMESTAMP", "LEVEL", "SERVICE", "MESSAGE", "TRACE_ID");
        System.out.printf("  %-24s %-8s %-15s %-30s %-10s%n",
                "-".repeat(24), "-".repeat(8), "-".repeat(15), "-".repeat(30), "-".repeat(10));

        for (LogEntry entry : logs) {
            String traceId = entry.getTraceId() != null ? truncateId(entry.getTraceId()) : "-";
            String message = entry.getMessage().length() > 30
                    ? entry.getMessage().substring(0, 27) + "..."
                    : entry.getMessage();

            System.out.printf("  %-24s %-8s %-15s %-30s %-10s%n",
                    entry.getTimestamp().toString().substring(0, Math.min(24, entry.getTimestamp().toString().length())),
                    entry.getLevel(),
                    entry.getServiceName(),
                    message,
                    traceId);
        }
    }

    // ========================================================================
    // Alert status
    // ========================================================================

    /** Prints all alerts (firing and recent) with rule name, status, value, severity. */
    public void printAlertStatus() {
        printSeparator("ALERT STATUS");

        List<Alert> alerts = alertService.getAllAlerts();
        if (alerts.isEmpty()) {
            System.out.println("  No alerts found.");
            return;
        }

        System.out.printf("  %-25s %-14s %-12s %-10s %-24s%n",
                "RULE", "STATUS", "VALUE", "SEVERITY", "TRIGGERED_AT");
        System.out.printf("  %-25s %-14s %-12s %-10s %-24s%n",
                "-".repeat(25), "-".repeat(14), "-".repeat(12), "-".repeat(10), "-".repeat(24));

        for (Alert alert : alerts) {
            System.out.printf("  %-25s %-14s %-12.2f %-10s %-24s%n",
                    alert.getRule().getName(),
                    alert.getStatus(),
                    alert.getCurrentValue(),
                    alert.getRule().getSeverity(),
                    alert.getTriggeredAt().toString().substring(0, Math.min(24, alert.getTriggeredAt().toString().length())));
        }
    }

    // ========================================================================
    // Service map
    // ========================================================================

    /** Prints an ASCII service dependency graph. */
    public void printServiceMap() {
        printSeparator("SERVICE MAP");

        List<ServiceNode> serviceList = serviceMapService.getAllServices();
        if (serviceList.isEmpty()) {
            System.out.println("  No service data available.");
            return;
        }

        for (ServiceNode node : serviceList) {
            System.out.printf("  [%s] (requests=%d, errorRate=%.2f%%, avgLatency=%.1fms)%n",
                    node.getServiceName(), node.getRequestCount(),
                    node.getErrorRate() * 100, node.getAvgLatencyMs());

            for (String dep : node.getDependencies()) {
                System.out.printf("    └──> %s%n", dep);
            }

            for (String dependent : node.getDependents()) {
                System.out.printf("    <──┘ %s%n", dependent);
            }
        }
    }

    // ========================================================================
    // Summary stats
    // ========================================================================

    /** Prints final summary stats (total metrics, traces, logs, alerts). */
    public void printStats() {
        printSeparator("PLATFORM STATS");

        System.out.printf("  %-20s %d%n", "Total Metrics:", metricService.getAllMetricNames().size());
        System.out.printf("  %-20s %d%n", "Total Traces:", tracingService.getRecentTraces(Integer.MAX_VALUE).size());
        System.out.printf("  %-20s %d%n", "Total Logs:", logService.getRecentLogs(Integer.MAX_VALUE).size());
        System.out.printf("  %-20s %d%n", "Total Alerts:", alertService.getAllAlerts().size());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Prints a separator banner with the given title. */
    public void printSeparator(String title) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70));
    }

    /** Truncates a UUID or ID string to the first 8 characters. */
    private String truncateId(String id) {
        if (id == null) return "null";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
