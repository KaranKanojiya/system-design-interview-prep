package com.systemdesign.observability;

import com.systemdesign.observability.config.AppConfig;
import com.systemdesign.observability.display.ObservabilityStatsDisplay;
import com.systemdesign.observability.engine.MetricAggregator;
import com.systemdesign.observability.engine.TimeSeriesStore;
import com.systemdesign.observability.model.*;
import com.systemdesign.observability.service.*;
import com.systemdesign.observability.strategy.aggregation.PercentileAggregationStrategy;
import com.systemdesign.observability.strategy.aggregation.RateAggregationStrategy;
import com.systemdesign.observability.strategy.alerting.AnomalyDetectionAlertingStrategy;
import com.systemdesign.observability.strategy.alerting.ThresholdAlertingStrategy;
import com.systemdesign.observability.strategy.sampling.HeadBasedSamplingStrategy;
import com.systemdesign.observability.strategy.sampling.RateLimitedSamplingStrategy;
import com.systemdesign.observability.strategy.sampling.TailBasedSamplingStrategy;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Observability Platform — System Design Demo
 *
 * Demonstrates: Metrics (counters, gauges, histograms, timers), distributed tracing
 * (spans, traces, context propagation), structured logging with correlation,
 * time-series storage, aggregation (sum/avg/percentile), sampling strategies
 * (head-based, tail-based, rate-limited), threshold & anomaly alerting,
 * service dependency mapping, and dashboards.
 *
 * 12 demos covering all major components.
 */
public class ObservabilityPlatformApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   OBSERVABILITY PLATFORM — System Design Demo");
        System.out.println("   Staff Engineer Interview Prep: Metrics, Tracing, Logs");
        System.out.println(SEPARATOR);
        System.out.println();

        AppConfig config = new AppConfig();

        demo1_MetricCollection(config);
        demo2_DistributedTracing(config);
        demo3_LogAggregation(config);
        demo4_TimeSeriesStorage(config);
        demo5_MetricAggregation(config);
        demo6_SamplingStrategies(config);
        demo7_ThresholdAlerting(config);
        demo8_AnomalyDetection(config);
        demo9_ServiceDependencyMap(config);
        demo10_Dashboard(config);
        demo11_HighCardinalityMetrics(config);
        demo12_ObservabilityOverview(config);

        printDesignSummary();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 1: Metric Collection (Counter, Gauge, Histogram, Timer)
    // ─────────────────────────────────────────────────────────────────
    private static void demo1_MetricCollection(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Metric Collection (Counter, Gauge, Histogram, Timer)");
        System.out.println(SEPARATOR);

        MetricService metricService = config.getMetricService();

        // Counter: request count
        metricService.recordCounter("http.requests.total", 1, Map.of("method", "GET", "path", "/api/users"));
        metricService.recordCounter("http.requests.total", 1, Map.of("method", "GET", "path", "/api/users"));
        metricService.recordCounter("http.requests.total", 1, Map.of("method", "POST", "path", "/api/orders"));

        // Gauge: active connections
        metricService.recordGauge("connections.active", 42, Map.of("service", "api-gateway"));
        metricService.recordGauge("connections.active", 38, Map.of("service", "api-gateway"));

        // Histogram: response sizes
        metricService.recordHistogram("http.response.size_bytes", 1024, Map.of("endpoint", "/api/users"));
        metricService.recordHistogram("http.response.size_bytes", 2048, Map.of("endpoint", "/api/users"));
        metricService.recordHistogram("http.response.size_bytes", 512, Map.of("endpoint", "/api/orders"));
        metricService.recordHistogram("http.response.size_bytes", 4096, Map.of("endpoint", "/api/users"));

        // Timer: request latency
        metricService.recordTimer("http.request.duration_ms", 45.2, Map.of("method", "GET"));
        metricService.recordTimer("http.request.duration_ms", 120.5, Map.of("method", "POST"));
        metricService.recordTimer("http.request.duration_ms", 23.1, Map.of("method", "GET"));
        metricService.recordTimer("http.request.duration_ms", 89.7, Map.of("method", "GET"));

        System.out.println();
        System.out.println("[DEMO] Metric types collected:");
        System.out.println("  COUNTER  → http.requests.total (3 increments)");
        System.out.println("  GAUGE    → connections.active (2 readings)");
        System.out.println("  HISTOGRAM→ http.response.size_bytes (4 observations)");
        System.out.println("  TIMER    → http.request.duration_ms (4 measurements)");
        System.out.println();
        System.out.println("  KEY INSIGHT: Four metric types cover all observability needs.");
        System.out.println("  Counters only go up, Gauges go up/down, Histograms track");
        System.out.println("  distributions, Timers are histograms specialized for latency.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 2: Distributed Tracing (Spans & Context Propagation)
    // ─────────────────────────────────────────────────────────────────
    private static void demo2_DistributedTracing(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Distributed Tracing (Spans & Context Propagation)");
        System.out.println(SEPARATOR);

        TracingService tracingService = config.getTracingService();

        // Simulate: API Gateway → Order Service → Payment Service → DB
        Span rootSpan = tracingService.startTrace("POST /api/orders", "api-gateway");
        String traceId = rootSpan.getTraceId();

        // Child span: Order Service
        Span orderSpan = tracingService.startSpan(traceId, rootSpan.getSpanId(),
                "createOrder", "order-service");
        sleep(15);

        // Grandchild span: Payment Service
        Span paymentSpan = tracingService.startSpan(traceId, orderSpan.getSpanId(),
                "processPayment", "payment-service");
        sleep(25);
        tracingService.finishSpan(paymentSpan);

        // Grandchild span: DB write
        Span dbSpan = tracingService.startSpan(traceId, orderSpan.getSpanId(),
                "INSERT orders", "postgres");
        sleep(10);
        tracingService.finishSpan(dbSpan);

        tracingService.finishSpan(orderSpan);
        tracingService.finishSpan(rootSpan);

        System.out.println();
        System.out.println("[DEMO] Trace tree:");
        System.out.println("  POST /api/orders (api-gateway)");
        System.out.println("  └── createOrder (order-service)");
        System.out.println("      ├── processPayment (payment-service)");
        System.out.println("      └── INSERT orders (postgres)");
        System.out.println();
        System.out.println("  KEY INSIGHT: Traces are trees of spans. Context propagation");
        System.out.println("  (traceId + parentSpanId) links spans across service boundaries.");
        System.out.println("  W3C TraceContext header: traceparent: 00-{traceId}-{spanId}-01");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 3: Log Aggregation with Correlation IDs
    // ─────────────────────────────────────────────────────────────────
    private static void demo3_LogAggregation(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Log Aggregation with Correlation IDs");
        System.out.println(SEPARATOR);

        LogService logService = config.getLogService();
        TracingService tracingService = config.getTracingService();

        // Start a trace for correlation
        Span span = tracingService.startTrace("GET /api/users/123", "user-service");
        String traceId = span.getTraceId();
        String spanId = span.getSpanId();

        // Log with trace correlation
        logService.logWithTrace(LogLevel.INFO, "Received request for user 123",
                "user-service", traceId, spanId);
        logService.logWithTrace(LogLevel.DEBUG, "Cache miss for user 123",
                "user-service", traceId, spanId);
        logService.logWithTrace(LogLevel.INFO, "Querying database for user 123",
                "user-service", traceId, spanId);
        logService.logWithTrace(LogLevel.WARN, "Slow query: 250ms for user lookup",
                "user-service", traceId, spanId);
        logService.logWithTrace(LogLevel.INFO, "Returning user 123 data",
                "user-service", traceId, spanId);

        // Unrelated log from another service
        logService.log(LogLevel.ERROR, "Connection pool exhausted", "payment-service");

        tracingService.finishSpan(span);

        System.out.println();
        System.out.println("[DEMO] Logs correlated by traceId: " + traceId.substring(0, 8) + "...");
        List<LogEntry> correlated = logService.getLogsByTrace(traceId);
        System.out.println("  Found " + correlated.size() + " log entries for this trace");
        System.out.println();
        System.out.println("  KEY INSIGHT: Correlation IDs (traceId, spanId) in logs enable");
        System.out.println("  jumping from a log line → full distributed trace → related logs.");
        System.out.println("  This is the 'glue' between the three observability pillars.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 4: Time-Series Storage and Querying
    // ─────────────────────────────────────────────────────────────────
    private static void demo4_TimeSeriesStorage(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: Time-Series Storage and Querying");
        System.out.println(SEPARATOR);

        MetricService metricService = config.getMetricService();
        TimeSeriesStore tsStore = config.getTimeSeriesStore();

        // Generate time-series data: CPU usage over time
        Random rng = new Random(42);
        Instant base = Instant.now().minus(Duration.ofMinutes(10));
        for (int i = 0; i < 60; i++) {
            double cpuUsage = 40 + rng.nextGaussian() * 10; // ~40% ± 10%
            cpuUsage = Math.max(0, Math.min(100, cpuUsage));
            metricService.recordGauge("system.cpu.usage_percent", cpuUsage,
                    Map.of("host", "prod-server-1"));
        }

        System.out.println("[DEMO] Stored 60 CPU usage points");
        System.out.println("  Total points in store: " + tsStore.getTotalPointCount());
        System.out.println("  Metric names: " + tsStore.getMetricNames());

        // Query time range
        Instant from = Instant.now().minus(Duration.ofMinutes(15));
        Instant to = Instant.now();
        List<MetricPoint> cpuPoints = metricService.query("system.cpu.usage_percent", from, to);
        System.out.println("  Query result: " + cpuPoints.size() + " points in last 15 minutes");

        // Downsample
        List<MetricPoint> downsampled = tsStore.downsample("system.cpu.usage_percent",
                Duration.ofMinutes(5));
        System.out.println("  Downsampled (5-min buckets): " + downsampled.size() + " points");

        System.out.println();
        System.out.println("  KEY INSIGHT: Time-series data is write-heavy. Bucketed storage");
        System.out.println("  (TreeMap<epochSecond, List<points>>) enables O(log n) range queries.");
        System.out.println("  Downsampling reduces storage: keep raw for 1h, 1-min avg for 24h,");
        System.out.println("  5-min avg for 7d, 1-hour avg for 1y.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 5: Metric Aggregation (Sum, Avg, Percentile)
    // ─────────────────────────────────────────────────────────────────
    private static void demo5_MetricAggregation(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Metric Aggregation (Sum, Avg, Percentile)");
        System.out.println(SEPARATOR);

        MetricService metricService = config.getMetricService();
        MetricAggregator aggregator = config.getMetricAggregator();

        Instant from = Instant.now().minus(Duration.ofMinutes(15));
        Instant to = Instant.now();

        // Query latency data
        List<MetricPoint> latencyPoints = metricService.query("http.request.duration_ms", from, to);
        List<MetricPoint> cpuPoints = metricService.query("system.cpu.usage_percent", from, to);

        System.out.println("[DEMO] Latency aggregations (" + latencyPoints.size() + " points):");
        if (!latencyPoints.isEmpty()) {
            System.out.printf("  Average : %.2f ms%n", aggregator.average(latencyPoints));
            System.out.printf("  Min     : %.2f ms%n", aggregator.min(latencyPoints));
            System.out.printf("  Max     : %.2f ms%n", aggregator.max(latencyPoints));
            System.out.printf("  P50     : %.2f ms%n", aggregator.percentile(latencyPoints, 50));
            System.out.printf("  P95     : %.2f ms%n", aggregator.percentile(latencyPoints, 95));
            System.out.printf("  P99     : %.2f ms%n", aggregator.percentile(latencyPoints, 99));
        }

        System.out.println();
        System.out.println("[DEMO] CPU aggregations (" + cpuPoints.size() + " points):");
        if (!cpuPoints.isEmpty()) {
            System.out.printf("  Average : %.2f %%%n", aggregator.average(cpuPoints));
            System.out.printf("  Min     : %.2f %%%n", aggregator.min(cpuPoints));
            System.out.printf("  Max     : %.2f %%%n", aggregator.max(cpuPoints));
            System.out.printf("  P50     : %.2f %%%n", aggregator.percentile(cpuPoints, 50));
            System.out.printf("  P99     : %.2f %%%n", aggregator.percentile(cpuPoints, 99));
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: P99 latency is more important than average for SLOs.");
        System.out.println("  Average hides tail latency. P99 = 'the slowest 1% of requests'.");
        System.out.println("  In production, use t-digest or DDSketch for streaming percentiles.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 6: Head-Based vs Tail-Based Sampling
    // ─────────────────────────────────────────────────────────────────
    private static void demo6_SamplingStrategies(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Sampling Strategies (Head, Tail, Rate-Limited)");
        System.out.println(SEPARATOR);

        // Head-based sampling at 50%
        HeadBasedSamplingStrategy headBased = new HeadBasedSamplingStrategy(0.5);
        int headSampled = 0;
        for (int i = 0; i < 100; i++) {
            TraceContext ctx = TraceContext.newTrace();
            if (headBased.shouldSample(ctx)) headSampled++;
        }
        System.out.println("[DEMO] Head-based sampling (50% rate):");
        System.out.println("  100 traces → " + headSampled + " sampled (~50 expected)");

        // Tail-based sampling
        TailBasedSamplingStrategy tailBased = new TailBasedSamplingStrategy(1.0, 100);
        TraceContext errorCtx = TraceContext.newTrace();
        errorCtx.setBaggage("error", "true");
        TraceContext normalCtx = TraceContext.newTrace();
        System.out.println();
        System.out.println("[DEMO] Tail-based sampling (error=true, latency>100ms):");
        System.out.println("  Error trace sampled:  " + tailBased.shouldSample(errorCtx, "checkout"));
        System.out.println("  Normal trace sampled: " + tailBased.shouldSample(normalCtx, "healthcheck"));

        // Rate-limited sampling
        RateLimitedSamplingStrategy rateLimited = new RateLimitedSamplingStrategy(5);
        int rateSampled = 0;
        for (int i = 0; i < 20; i++) {
            TraceContext ctx = TraceContext.newTrace();
            if (rateLimited.shouldSample(ctx)) rateSampled++;
        }
        System.out.println();
        System.out.println("[DEMO] Rate-limited sampling (5/second):");
        System.out.println("  20 traces → " + rateSampled + " sampled (max 5 expected)");

        System.out.println();
        System.out.println("  KEY INSIGHT: Head-based is simple but loses interesting traces.");
        System.out.println("  Tail-based keeps errors/slow traces but requires buffering ALL");
        System.out.println("  spans initially. Rate-limited provides predictable cost control.");
        System.out.println("  Production: combine all three (head-based baseline + tail-based");
        System.out.println("  for errors + rate-limit as safety valve).");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 7: Threshold Alerting
    // ─────────────────────────────────────────────────────────────────
    private static void demo7_ThresholdAlerting(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Threshold Alerting");
        System.out.println(SEPARATOR);

        AlertService alertService = config.getAlertService();
        MetricService metricService = config.getMetricService();

        // Create alert rules
        AlertRule cpuRule = new AlertRule.Builder("High CPU Alert", "system.cpu.usage_percent")
                .condition(">")
                .threshold(80.0)
                .durationSeconds(60)
                .severity(AlertSeverity.CRITICAL)
                .build();

        AlertRule latencyRule = new AlertRule.Builder("High Latency Alert", "http.request.duration_ms")
                .condition(">")
                .threshold(100.0)
                .durationSeconds(30)
                .severity(AlertSeverity.WARNING)
                .build();

        alertService.createRule(cpuRule);
        alertService.createRule(latencyRule);

        // Inject some high CPU values to trigger the alert
        for (int i = 0; i < 10; i++) {
            metricService.recordGauge("system.cpu.usage_percent", 85 + i,
                    Map.of("host", "prod-server-1"));
        }

        // Evaluate rules
        alertService.evaluateRules();

        // Show alerts
        List<Alert> firing = alertService.getFiringAlerts();
        System.out.println();
        System.out.println("[DEMO] Firing alerts: " + firing.size());
        for (Alert alert : firing) {
            System.out.println("  🔴 " + alert.getMessage() + " (value=" + alert.getCurrentValue() + ")");
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Alert rules define: metric name, condition (> or <),");
        System.out.println("  threshold, duration (how long condition must hold), and severity.");
        System.out.println("  Avoid alert fatigue: use WARNING for investigation, CRITICAL for");
        System.out.println("  pages, and always define runbooks.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 8: Anomaly Detection Alerting
    // ─────────────────────────────────────────────────────────────────
    private static void demo8_AnomalyDetection(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Anomaly Detection (Standard Deviation Based)");
        System.out.println(SEPARATOR);

        config.setAlertingStrategy(new AnomalyDetectionAlertingStrategy(2.0));
        AlertService alertService = config.getAlertService();
        MetricService metricService = config.getMetricService();

        // Create a metric with normal values then inject an anomaly
        for (int i = 0; i < 20; i++) {
            metricService.recordGauge("order.processing_time_ms", 50 + (i % 5),
                    Map.of("service", "order-service"));
        }
        // Inject anomaly: sudden spike
        metricService.recordGauge("order.processing_time_ms", 500,
                Map.of("service", "order-service"));

        AlertRule anomalyRule = new AlertRule.Builder("Order Processing Anomaly",
                "order.processing_time_ms")
                .condition(">")
                .threshold(100.0)
                .severity(AlertSeverity.WARNING)
                .build();
        alertService.createRule(anomalyRule);

        alertService.evaluateRules();

        System.out.println();
        System.out.println("[DEMO] Anomaly detection with 2σ threshold:");
        System.out.println("  Normal values: ~50-55 ms");
        System.out.println("  Anomaly value: 500 ms");
        System.out.println("  Firing alerts: " + alertService.getFiringAlerts().size());

        System.out.println();
        System.out.println("  KEY INSIGHT: Anomaly detection uses statistical methods (σ-based,");
        System.out.println("  EWMA, seasonal decomposition) to detect deviations from baseline");
        System.out.println("  without hardcoded thresholds. Better for dynamic workloads.");
        System.out.println("  In production: use Prophet, Holt-Winters, or ML-based models.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 9: Service Dependency Map
    // ─────────────────────────────────────────────────────────────────
    private static void demo9_ServiceDependencyMap(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Service Dependency Map");
        System.out.println(SEPARATOR);

        ServiceMapService serviceMap = config.getServiceMapService();

        // Register service-to-service calls
        serviceMap.registerCall("api-gateway", "user-service", 25, true);
        serviceMap.registerCall("api-gateway", "order-service", 45, true);
        serviceMap.registerCall("api-gateway", "order-service", 120, false);
        serviceMap.registerCall("order-service", "payment-service", 80, true);
        serviceMap.registerCall("order-service", "inventory-service", 35, true);
        serviceMap.registerCall("order-service", "postgres", 12, true);
        serviceMap.registerCall("payment-service", "stripe-api", 200, true);
        serviceMap.registerCall("payment-service", "redis", 5, true);
        serviceMap.registerCall("user-service", "postgres", 15, true);
        serviceMap.registerCall("user-service", "redis", 3, true);

        System.out.println();
        serviceMap.printServiceMap();

        System.out.println();
        System.out.println("[DEMO] Service topology:");
        System.out.println("  api-gateway → user-service → postgres, redis");
        System.out.println("             → order-service → payment-service → stripe-api, redis");
        System.out.println("                             → inventory-service");
        System.out.println("                             → postgres");

        System.out.println();
        System.out.println("  KEY INSIGHT: Service maps are built from trace data (who calls whom).");
        System.out.println("  Critical for: blast radius analysis, dependency cycle detection,");
        System.out.println("  and understanding cascading failure paths.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 10: Dashboard with Multiple Panels
    // ─────────────────────────────────────────────────────────────────
    private static void demo10_Dashboard(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 10: Dashboard (Combined View)");
        System.out.println(SEPARATOR);

        DashboardService dashboard = config.getDashboardService();

        System.out.println("[DEMO] System Overview:");
        System.out.println(dashboard.getSystemOverview());

        System.out.println();
        System.out.println("  KEY INSIGHT: Dashboards combine the three pillars (metrics, traces,");
        System.out.println("  logs) into a single view. RED method: Rate, Errors, Duration.");
        System.out.println("  USE method: Utilization, Saturation, Errors (for resources).");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 11: High-Cardinality Metrics
    // ─────────────────────────────────────────────────────────────────
    private static void demo11_HighCardinalityMetrics(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 11: High-Cardinality Metric Handling");
        System.out.println(SEPARATOR);

        MetricService metricService = config.getMetricService();
        TimeSeriesStore tsStore = config.getTimeSeriesStore();

        long before = tsStore.getTotalPointCount();

        // Simulate high-cardinality: unique user IDs as tags
        Random rng = new Random(123);
        for (int i = 0; i < 50; i++) {
            String userId = "user-" + rng.nextInt(10000);
            metricService.recordCounter("api.requests.by_user", 1,
                    Map.of("user_id", userId, "endpoint", "/api/data"));
        }

        long after = tsStore.getTotalPointCount();

        System.out.println("[DEMO] High-cardinality metric: api.requests.by_user");
        System.out.println("  50 data points with unique user_id tags");
        System.out.println("  Points stored: " + (after - before));
        System.out.println();
        System.out.println("[DEMO] High-cardinality mitigation strategies:");
        System.out.println("  1. Drop high-cardinality tags at ingestion (user_id → drop)");
        System.out.println("  2. Hash/bucket: user_id → user_bucket (mod 100)");
        System.out.println("  3. Separate storage: high-card metrics → columnar store (ClickHouse)");
        System.out.println("  4. Adaptive sampling: sample 1% of unique label combinations");
        System.out.println("  5. Cardinality limits: reject metrics exceeding N unique label combos");

        System.out.println();
        System.out.println("  KEY INSIGHT: High cardinality is the #1 cost driver in observability.");
        System.out.println("  Each unique label combination = a new time series. 10K users ×");
        System.out.println("  100 endpoints = 1M time series. Control at ingestion, not query.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 12: Full Observability Overview
    // ─────────────────────────────────────────────────────────────────
    private static void demo12_ObservabilityOverview(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 12: Observability Overview & Stats");
        System.out.println(SEPARATOR);

        ObservabilityStatsDisplay display = config.getStatsDisplay();

        display.printAlertStatus();
        display.printStats();
    }

    // ─────────────────────────────────────────────────────────────────
    // Design Summary
    // ─────────────────────────────────────────────────────────────────
    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Observability Platform");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  Three Pillars of Observability:");
        System.out.println("    • Metrics — counters, gauges, histograms, timers");
        System.out.println("    • Traces  — distributed tracing with span trees");
        System.out.println("    • Logs    — structured logging with correlation IDs");
        System.out.println();
        System.out.println("  Core Data Structures:");
        System.out.println("    • TreeMap<epochSec, List<MetricPoint>> — time-series storage");
        System.out.println("    • Span tree (parent→children) — trace assembly");
        System.out.println("    • Adjacency list — service dependency graph");
        System.out.println("    • Sorted array — streaming percentile computation");
        System.out.println();
        System.out.println("  Key Algorithms:");
        System.out.println("    • Percentile (P50/P95/P99) — nearest-rank method");
        System.out.println("    • Anomaly detection — mean ± k*stddev");
        System.out.println("    • Head-based sampling — deterministic hash of traceId");
        System.out.println("    • Rate-limited sampling — token bucket per second");
        System.out.println("    • Downsampling — average within time buckets");
        System.out.println();
        System.out.println("  Design Patterns (GoF):");
        System.out.println("    • Strategy — sampling, aggregation, alerting algorithms");
        System.out.println("    • Builder — Metric, Span, AlertRule construction");
        System.out.println("    • Factory — AppConfig as composition root");
        System.out.println("    • Repository — data access abstraction (4 repos)");
        System.out.println("    • Facade — ObservabilityService orchestrates all services");
        System.out.println("    • Observer — alert triggers on metric thresholds");
        System.out.println("    • Decorator — span enrichment with tags/logs");
        System.out.println("    • Chain of Responsibility — log processing pipeline");
        System.out.println("    • Template Method — metric aggregation");
        System.out.println("    • Singleton — AppConfig lazy initialization");
        System.out.println();
        System.out.println("  Staff-Level Topics Covered:");
        System.out.println("    • Time-series at scale (write-heavy, downsampling)");
        System.out.println("    • Distributed tracing (context propagation, sampling)");
        System.out.println("    • High-cardinality metric management");
        System.out.println("    • Alert fatigue prevention (anomaly detection)");
        System.out.println("    • Service dependency mapping");
        System.out.println("    • RED/USE method for dashboards");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  End of Observability Platform Demo");
        System.out.println(SEPARATOR);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
