package com.systemdesign.observability.model;

// Wiring: AlertRule defines when an Alert should fire.
// Evaluated by AlertEvaluationEngine against incoming Metrics.

import java.util.UUID;

/**
 * Declarative alert rule — specifies which metric to watch, the threshold condition,
 * and how long the condition must hold before firing.
 * Constructed via Builder pattern.
 */
public class AlertRule {

    private final String id;
    private final String name;
    private final String metricName;
    private final String condition;        // e.g. "> 90", "< 10"
    private final double threshold;
    private final int durationSeconds;     // how long condition must hold
    private final AlertSeverity severity;
    private final boolean enabled;

    private AlertRule(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.metricName = builder.metricName;
        this.condition = builder.condition;
        this.threshold = builder.threshold;
        this.durationSeconds = builder.durationSeconds;
        this.severity = builder.severity;
        this.enabled = builder.enabled;
    }

    // ---- getters ----

    public String getId() { return id; }
    public String getName() { return name; }
    public String getMetricName() { return metricName; }
    public String getCondition() { return condition; }
    public double getThreshold() { return threshold; }
    public int getDurationSeconds() { return durationSeconds; }
    public AlertSeverity getSeverity() { return severity; }
    public boolean isEnabled() { return enabled; }

    @Override
    public String toString() {
        return "AlertRule{name='" + name + "', metric='" + metricName
                + "', condition='" + condition + " " + threshold
                + "', severity=" + severity + "}";
    }

    // ---- Builder ----

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private String name;
        private String metricName;
        private String condition;
        private double threshold;
        private int durationSeconds = 60;
        private AlertSeverity severity = AlertSeverity.WARNING;
        private boolean enabled = true;

        public Builder(String name, String metricName) {
            this.name = name;
            this.metricName = metricName;
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder condition(String condition) { this.condition = condition; return this; }
        public Builder threshold(double threshold) { this.threshold = threshold; return this; }
        public Builder durationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; return this; }
        public Builder severity(AlertSeverity severity) { this.severity = severity; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }

        public AlertRule build() {
            return new AlertRule(this);
        }
    }
}
