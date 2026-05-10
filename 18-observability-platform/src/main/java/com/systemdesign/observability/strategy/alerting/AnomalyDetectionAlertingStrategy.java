package com.systemdesign.observability.strategy.alerting;

import com.systemdesign.observability.model.AlertRule;
import com.systemdesign.observability.model.MetricPoint;

import java.util.List;

// Wiring: Anomaly detection using standard deviation — fires when the latest data point
// deviates from the mean by more than (stdDevMultiplier * stdDev). This catches unexpected
// spikes and drops that a static threshold would miss, because the "normal" range is computed
// dynamically from the recent data window.
//
// Algorithm:
//   1. Compute mean and standard deviation of all recent points
//   2. Check if the latest point's value falls outside (mean ± stdDevMultiplier * stdDev)
//   3. If yes → anomaly detected → fire alert
//   4. Require at least 3 data points for a meaningful stdDev calculation

/**
 * Fires an alert when the most recent data point is a statistical anomaly,
 * defined as deviating more than N standard deviations from the mean.
 */
public class AnomalyDetectionAlertingStrategy implements AlertingStrategy {

    private final double stdDevMultiplier;

    /**
     * @param stdDevMultiplier number of standard deviations that defines an anomaly (default: 2.0)
     */
    public AnomalyDetectionAlertingStrategy(double stdDevMultiplier) {
        if (stdDevMultiplier <= 0.0) {
            throw new IllegalArgumentException("stdDevMultiplier must be positive, got: " + stdDevMultiplier);
        }
        this.stdDevMultiplier = stdDevMultiplier;
    }

    /** Convenience constructor using the default multiplier of 2.0 (roughly 95% confidence). */
    public AnomalyDetectionAlertingStrategy() {
        this(2.0);
    }

    /**
     * Checks whether the latest data point is anomalous relative to the recent window.
     *
     * 1. Fewer than 3 points → don't alert (not enough data for meaningful statistics)
     * 2. Compute mean of all points
     * 3. Compute population standard deviation
     * 4. If latest point's value is outside (mean ± stdDevMultiplier * stdDev) → alert
     */
    @Override
    public boolean shouldAlert(AlertRule rule, List<MetricPoint> recentPoints) {
        if (rule == null || recentPoints == null || recentPoints.size() < 3) {
            return false;
        }

        // Step 1: Compute the mean
        double mean = recentPoints.stream()
                .mapToDouble(MetricPoint::getValue)
                .average()
                .orElse(0.0);

        // Step 2: Compute the population standard deviation
        double variance = recentPoints.stream()
                .mapToDouble(p -> Math.pow(p.getValue() - mean, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        // Step 3: Check if the latest point is an anomaly
        MetricPoint latest = recentPoints.get(recentPoints.size() - 1);
        double deviation = Math.abs(latest.getValue() - mean);

        return deviation > (stdDevMultiplier * stdDev);
    }

    @Override
    public String getStrategyName() {
        return "ANOMALY_DETECTION";
    }
}
