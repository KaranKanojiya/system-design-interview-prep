package com.systemdesign.observability.strategy.alerting;

import com.systemdesign.observability.model.AlertRule;
import com.systemdesign.observability.model.MetricPoint;

import java.util.List;

// Wiring: Simple threshold alerting — computes the average of recent points and compares
// against the rule's threshold using the rule's condition operator (">", "<").
// This is the most common alerting strategy in production: "alert if CPU > 80%",
// "alert if free disk < 10 GB", etc.

/**
 * Fires an alert when the average of recent metric values crosses a static threshold.
 */
public class ThresholdAlertingStrategy implements AlertingStrategy {

    /**
     * Evaluates whether the average of recent data points crosses the threshold.
     *
     * 1. Compute the arithmetic mean of all recentPoints
     * 2. Parse the condition from the rule (starts with ">" or "<")
     * 3. Compare average against the rule's threshold
     * 4. Default to ">" comparison if condition format is unrecognized
     */
    @Override
    public boolean shouldAlert(AlertRule rule, List<MetricPoint> recentPoints) {
        if (rule == null || recentPoints == null || recentPoints.isEmpty()) {
            return false;
        }

        double average = recentPoints.stream()
                .mapToDouble(MetricPoint::getValue)
                .average()
                .orElse(0.0);

        double threshold = rule.getThreshold();
        String condition = rule.getCondition();

        if (condition != null && condition.trim().startsWith("<")) {
            return average < threshold;
        }

        // Default: ">" comparison
        return average > threshold;
    }

    @Override
    public String getStrategyName() {
        return "THRESHOLD";
    }
}
