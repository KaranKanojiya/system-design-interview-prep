package com.systemdesign.observability.strategy.aggregation;

import com.systemdesign.observability.model.MetricPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Wiring: Computes a percentile value (e.g., P50, P95, P99) from a window of metric data points.
// Algorithm:
//   1. Sort the points by value in ascending order
//   2. Compute the target index = ceil(percentile / 100.0 * size) - 1
//   3. Return the value at that index
// This gives the "nearest-rank" percentile, which is the most common implementation
// in observability systems (Prometheus, Datadog, etc.).

/**
 * Computes a configurable percentile (P50, P95, P99, etc.) from a set of metric data points.
 */
public class PercentileAggregationStrategy implements AggregationStrategy {

    private final double percentile;  // e.g. 99.0 for P99

    /**
     * @param percentile the target percentile, in range (0.0, 100.0]
     */
    public PercentileAggregationStrategy(double percentile) {
        if (percentile <= 0.0 || percentile > 100.0) {
            throw new IllegalArgumentException("percentile must be in (0.0, 100.0], got: " + percentile);
        }
        this.percentile = percentile;
    }

    /**
     * Sorts the points by value and returns the value at the percentile rank.
     *
     * 1. Empty list → return 0.0
     * 2. Single point → return that point's value
     * 3. Otherwise → sort ascending, compute index via nearest-rank method
     */
    @Override
    public double aggregate(List<MetricPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0.0;
        }
        if (points.size() == 1) {
            return points.get(0).getValue();
        }

        // Sort a copy to avoid mutating the caller's list
        List<MetricPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(MetricPoint::getValue));

        // Nearest-rank percentile: index = ceil(P/100 * N) - 1
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index).getValue();
    }

    @Override
    public String getStrategyName() {
        // Formats as "P99", "P95", "P50", etc.
        if (percentile == Math.floor(percentile)) {
            return "P" + (int) percentile;
        }
        return "P" + percentile;
    }
}
