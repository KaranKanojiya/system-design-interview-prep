package com.systemdesign.observability.exception;

// Wiring: Thrown when metric ingestion fails (invalid name, type mismatch, storage error).
// Caught by MetricService -> logged and surfaced to the caller.

/**
 * Thrown when a metric cannot be ingested into the platform.
 */
public class MetricIngestionException extends ObservabilityException {

    private final String metricName;

    public MetricIngestionException(String metricName, String message) {
        super("Metric ingestion failed for '" + metricName + "': " + message);
        this.metricName = metricName;
    }

    public String getMetricName() {
        return metricName;
    }
}
