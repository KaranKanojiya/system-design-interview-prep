package com.systemdesign.observability.strategy.aggregation;

import com.systemdesign.observability.model.MetricPoint;

import java.util.List;

// Strategy Pattern (GoF) — determines how metric data points are aggregated

/**
 * Defines how a window of raw metric data points is reduced to a single aggregate value.
 * Implementations provide different statistical views (percentiles, rates, etc.)
 * used by dashboards and alerting pipelines.
 */
public interface AggregationStrategy {

    /**
     * Aggregates the given data points into a single numeric value.
     *
     * @param points the metric data points within the aggregation window
     * @return the aggregated value
     */
    double aggregate(List<MetricPoint> points);

    /** Returns a human-readable name for this aggregation strategy. */
    String getStrategyName();
}
