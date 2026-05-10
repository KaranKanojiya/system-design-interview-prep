package com.systemdesign.observability.model;

/**
 * Types of metrics collected by the observability platform.
 */
public enum MetricType {
    COUNTER("Monotonically increasing value, e.g. request count"),
    GAUGE("Point-in-time value that can go up or down, e.g. CPU usage"),
    HISTOGRAM("Distribution of values across buckets, e.g. request size"),
    TIMER("Duration measurement, e.g. response latency");

    private final String description;

    MetricType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
