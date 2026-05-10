package com.systemdesign.observability.model;

// Wiring: Metric aggregates MetricPoints over time. Built via Builder pattern.
// Used by MetricIngestionService -> stored in MetricRepository -> queried by QueryEngine.

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregated metric definition with its collected data points.
 * Constructed via the Builder pattern for flexible initialization.
 */
public class Metric {

    private final String id;
    private final String name;
    private final MetricType metricType;
    private final String description;
    private final String unit;
    private final Map<String, String> tags;
    private final List<MetricPoint> dataPoints;
    private final Instant createdAt;

    private Metric(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.metricType = builder.metricType;
        this.description = builder.description;
        this.unit = builder.unit;
        this.tags = builder.tags != null ? new HashMap<>(builder.tags) : new HashMap<>();
        this.dataPoints = builder.dataPoints != null ? new ArrayList<>(builder.dataPoints) : new ArrayList<>();
        this.createdAt = builder.createdAt;
    }

    // ---- mutations ----

    public void addDataPoint(MetricPoint point) {
        dataPoints.add(point);
    }

    // ---- queries ----

    /** Returns the value of the most recent data point, if any exist. */
    public Optional<Double> getLatestValue() {
        return dataPoints.stream()
                .max(Comparator.comparing(MetricPoint::getTimestamp))
                .map(MetricPoint::getValue);
    }

    /** Returns all data points whose timestamp falls within [from, to]. */
    public List<MetricPoint> getPointsInRange(Instant from, Instant to) {
        return dataPoints.stream()
                .filter(p -> !p.getTimestamp().isBefore(from) && !p.getTimestamp().isAfter(to))
                .collect(Collectors.toList());
    }

    // ---- getters ----

    public String getId() { return id; }
    public String getName() { return name; }
    public MetricType getMetricType() { return metricType; }
    public String getDescription() { return description; }
    public String getUnit() { return unit; }
    public Map<String, String> getTags() { return Collections.unmodifiableMap(tags); }
    public List<MetricPoint> getDataPoints() { return Collections.unmodifiableList(dataPoints); }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "Metric{name='" + name + "', type=" + metricType
                + ", points=" + dataPoints.size() + "}";
    }

    // ---- Builder ----

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private String name;
        private MetricType metricType;
        private String description;
        private String unit;
        private Map<String, String> tags;
        private List<MetricPoint> dataPoints;
        private Instant createdAt = Instant.now();

        public Builder(String name, MetricType metricType) {
            this.name = name;
            this.metricType = metricType;
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder unit(String unit) { this.unit = unit; return this; }
        public Builder tags(Map<String, String> tags) { this.tags = tags; return this; }
        public Builder dataPoints(List<MetricPoint> dataPoints) { this.dataPoints = dataPoints; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public Metric build() {
            return new Metric(this);
        }
    }
}
