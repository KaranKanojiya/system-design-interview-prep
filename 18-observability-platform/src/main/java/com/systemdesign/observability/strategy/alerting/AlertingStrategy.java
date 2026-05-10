package com.systemdesign.observability.strategy.alerting;

import com.systemdesign.observability.model.AlertRule;
import com.systemdesign.observability.model.MetricPoint;

import java.util.List;

// Strategy Pattern (GoF) — determines when to fire an alert

/**
 * Defines how metric data is evaluated against an alert rule to decide
 * whether a notification should be triggered. Implementations provide
 * different detection approaches (static thresholds, anomaly detection, etc.).
 */
public interface AlertingStrategy {

    /**
     * Evaluates recent metric data against the given rule.
     *
     * @param rule         the alert rule containing condition and threshold
     * @param recentPoints the most recent metric data points in the evaluation window
     * @return true if the alert should fire
     */
    boolean shouldAlert(AlertRule rule, List<MetricPoint> recentPoints);

    /** Returns a human-readable name for this alerting strategy. */
    String getStrategyName();
}
