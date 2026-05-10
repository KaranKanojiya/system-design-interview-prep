package com.systemdesign.observability.service;

// Wiring: MetricService orchestrates metric recording, storage, and querying.
// Dependencies injected via constructor:
//   metricRepo     — persists Metric definitions
//   timeSeriesStore — stores MetricPoints in time-ordered fashion
//   aggregator     — computes statistical aggregations over data points

import com.systemdesign.observability.model.Metric;
import com.systemdesign.observability.model.MetricPoint;
import com.systemdesign.observability.model.MetricType;
import com.systemdesign.observability.repository.MetricRepository;
import com.systemdesign.observability.engine.TimeSeriesStore;
import com.systemdesign.observability.engine.MetricAggregator;
import com.systemdesign.observability.strategy.aggregation.AggregationStrategy;

import java.time.Instant;
import java.util.*;

/**
 * MetricService — business logic for metric lifecycle management.
 *
 * FLOW — recordMetric(name, value, type, tags):
 *   1. Create MetricPoint with current timestamp
 *   2. Store the point in TimeSeriesStore
 *   3. If Metric definition doesn't exist in repo, create and save it
 *   4. Print [METRIC] log line
 *
 * FLOW — query(metricName, from, to):
 *   1. Delegate to TimeSeriesStore.query(metricName, from, to)
 *   2. Return ordered list of MetricPoints
 *
 * FLOW — aggregate(metricName, from, to, strategy):
 *   1. Query points from TimeSeriesStore
 *   2. Delegate aggregation to AggregationStrategy
 *   3. Return computed value
 */
public class MetricService {

    private final MetricRepository metricRepo;       // persists Metric definitions
    private final TimeSeriesStore timeSeriesStore;    // stores MetricPoints in time order
    private final MetricAggregator aggregator;        // computes statistical aggregations

    public MetricService(MetricRepository metricRepo, TimeSeriesStore timeSeriesStore,
                         MetricAggregator aggregator) {
        this.metricRepo = metricRepo;
        this.timeSeriesStore = timeSeriesStore;
        this.aggregator = aggregator;
    }

    // ---- recording ----

    /**
     * Records a metric data point and ensures the Metric definition exists.
     *
     * @param name  metric name (e.g. "http.request.duration")
     * @param value the numeric value
     * @param type  COUNTER, GAUGE, HISTOGRAM, or TIMER
     * @param tags  key-value metadata labels
     */
    public void recordMetric(String name, double value, MetricType type, Map<String, String> tags) {
        // 1. Create the data point
        MetricPoint point = MetricPoint.of(name, value, type, tags);

        // 2. Store in time-series store
        timeSeriesStore.store(point);

        // 3. Create Metric definition if it doesn't exist
        if (metricRepo.findByName(name).isEmpty()) {
            Metric metric = new Metric.Builder(name, type)
                    .tags(tags)
                    .build();
            metricRepo.save(metric);
        }

        System.out.println("[METRIC] Recorded " + type + " '" + name + "' = " + value);
    }

    /** Convenience — records a COUNTER metric (monotonically increasing). */
    public void recordCounter(String name, double increment, Map<String, String> tags) {
        recordMetric(name, increment, MetricType.COUNTER, tags);
    }

    /** Convenience — records a GAUGE metric (point-in-time value). */
    public void recordGauge(String name, double value, Map<String, String> tags) {
        recordMetric(name, value, MetricType.GAUGE, tags);
    }

    /** Convenience — records a HISTOGRAM metric (value distribution). */
    public void recordHistogram(String name, double value, Map<String, String> tags) {
        recordMetric(name, value, MetricType.HISTOGRAM, tags);
    }

    /** Convenience — records a TIMER metric (duration measurement). */
    public void recordTimer(String name, double durationMs, Map<String, String> tags) {
        recordMetric(name, durationMs, MetricType.TIMER, tags);
    }

    // ---- querying ----

    /**
     * Queries metric data points within a time range.
     *
     * @param metricName the metric name to query
     * @param from       start of time window (inclusive)
     * @param to         end of time window (inclusive)
     * @return list of MetricPoints in the range
     */
    public List<MetricPoint> query(String metricName, Instant from, Instant to) {
        return timeSeriesStore.query(metricName, from, to);
    }

    /**
     * Returns the latest recorded value for the given metric.
     *
     * @return the most recent value, or empty if no data points exist
     */
    public OptionalDouble getLatestValue(String metricName) {
        List<Metric> metrics = metricRepo.findByName(metricName);
        if (!metrics.isEmpty()) {
            Optional<Double> latest = metrics.get(0).getLatestValue();
            if (latest.isPresent()) {
                return OptionalDouble.of(latest.get());
            }
        }

        // Fallback: query the time-series store for the most recent point
        List<MetricPoint> recent = timeSeriesStore.query(
                metricName, Instant.EPOCH, Instant.now());
        if (recent.isEmpty()) {
            return OptionalDouble.empty();
        }

        return recent.stream()
                .max(Comparator.comparing(MetricPoint::getTimestamp))
                .map(p -> OptionalDouble.of(p.getValue()))
                .orElse(OptionalDouble.empty());
    }

    /**
     * Aggregates metric data points in a time range using the given strategy.
     *
     * @param metricName the metric name
     * @param from       start of time window
     * @param to         end of time window
     * @param strategy   the aggregation strategy (SUM, AVG, MIN, MAX, P99, etc.)
     * @return the aggregated value
     */
    public double aggregate(String metricName, Instant from, Instant to,
                            AggregationStrategy strategy) {
        List<MetricPoint> points = timeSeriesStore.query(metricName, from, to);
        return strategy.aggregate(points);
    }

    /**
     * Retrieves the Metric definition by name.
     */
    public Optional<Metric> getMetric(String name) {
        List<Metric> metrics = metricRepo.findByName(name);
        return metrics.isEmpty() ? Optional.empty() : Optional.of(metrics.get(0));
    }

    /**
     * Returns all known metric names.
     */
    public Set<String> getAllMetricNames() {
        return timeSeriesStore.getMetricNames();
    }

    /**
     * Exposes the aggregator for callers that need direct statistical access.
     */
    public MetricAggregator getAggregator() {
        return aggregator;
    }
}
