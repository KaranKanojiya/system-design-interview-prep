package com.systemdesign.observability.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * A single metric data point — immutable snapshot of a metric value at a point in time.
 */
public class MetricPoint {

    private final String name;
    private final double value;
    private final Instant timestamp;
    private final MetricType metricType;
    private final Map<String, String> tags;

    public MetricPoint(String name, double value, Instant timestamp,
                       MetricType metricType, Map<String, String> tags) {
        this.name = name;
        this.value = value;
        this.timestamp = timestamp;
        this.metricType = metricType;
        this.tags = tags != null ? Map.copyOf(tags) : Map.of();
    }

    /** Factory: creates a MetricPoint stamped at Instant.now(). */
    public static MetricPoint of(String name, double value,
                                 MetricType type, Map<String, String> tags) {
        return new MetricPoint(name, value, Instant.now(), type, tags);
    }

    // ---- getters (immutable, no setters) ----

    public String getName() {
        return name;
    }

    public double getValue() {
        return value;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public MetricType getMetricType() {
        return metricType;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    @Override
    public String toString() {
        return "MetricPoint{name='" + name + "', value=" + value
                + ", type=" + metricType + ", timestamp=" + timestamp + "}";
    }
}
