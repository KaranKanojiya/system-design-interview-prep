package com.systemdesign.observability.strategy.aggregation;

import com.systemdesign.observability.model.MetricPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Wiring: Computes the rate of change (delta value / delta time) across a window of metric points.
// Used for counter-type metrics (e.g., requests_total) where the raw value is monotonically
// increasing and we want "requests per second" on the dashboard.
// Algorithm:
//   1. Sort points by timestamp (ascending)
//   2. rate = (last.value - first.value) / (last.timestamp - first.timestamp) in seconds
//   3. Return rate (units: value-change per second)

/**
 * Computes the per-second rate of change between the first and last data points
 * in the aggregation window.
 */
public class RateAggregationStrategy implements AggregationStrategy {

    /**
     * Computes rate = (last.value - first.value) / timeDeltaSeconds.
     *
     * 1. Fewer than 2 points → return 0.0 (can't compute a rate from a single point)
     * 2. Sort by timestamp ascending
     * 3. Compute value delta and time delta
     * 4. Guard against zero time delta (simultaneous points)
     */
    @Override
    public double aggregate(List<MetricPoint> points) {
        if (points == null || points.size() < 2) {
            return 0.0;
        }

        // Sort a copy by timestamp
        List<MetricPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(MetricPoint::getTimestamp));

        MetricPoint first = sorted.get(0);
        MetricPoint last = sorted.get(sorted.size() - 1);

        double valueDelta = last.getValue() - first.getValue();
        double timeDeltaSeconds = Duration.between(first.getTimestamp(), last.getTimestamp()).toMillis() / 1000.0;

        if (timeDeltaSeconds <= 0.0) {
            return 0.0;  // all points at the same instant — rate is undefined
        }

        return valueDelta / timeDeltaSeconds;
    }

    @Override
    public String getStrategyName() {
        return "RATE";
    }
}
