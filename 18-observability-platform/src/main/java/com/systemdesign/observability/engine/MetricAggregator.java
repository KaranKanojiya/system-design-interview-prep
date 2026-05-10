package com.systemdesign.observability.engine;

// Wiring: MetricAggregator performs math over MetricPoint lists.
// Used by QueryService to compute aggregations (sum, avg, p99, histograms) for dashboards.

import com.systemdesign.observability.model.MetricPoint;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates metric data points over time windows.
 * Provides standard statistical functions used by the query layer.
 */
public class MetricAggregator {

    /** Sum of all point values. Returns 0 for empty list. */
    public double sum(List<MetricPoint> points) {
        if (points.isEmpty()) return 0.0;
        return points.stream()
                .mapToDouble(MetricPoint::getValue)
                .sum();
    }

    /** Arithmetic mean. Returns 0 for empty list. */
    public double average(List<MetricPoint> points) {
        if (points.isEmpty()) return 0.0;
        return points.stream()
                .mapToDouble(MetricPoint::getValue)
                .average()
                .orElse(0.0);
    }

    /** Minimum value. Returns 0 for empty list. */
    public double min(List<MetricPoint> points) {
        if (points.isEmpty()) return 0.0;
        return points.stream()
                .mapToDouble(MetricPoint::getValue)
                .min()
                .orElse(0.0);
    }

    /** Maximum value. Returns 0 for empty list. */
    public double max(List<MetricPoint> points) {
        if (points.isEmpty()) return 0.0;
        return points.stream()
                .mapToDouble(MetricPoint::getValue)
                .max()
                .orElse(0.0);
    }

    /**
     * Percentile calculation (0-100 scale, e.g. 99 for p99).
     * Sorts values ascending, returns value at index = ceil(p/100 * size) - 1.
     * Returns 0 for empty list.
     */
    public double percentile(List<MetricPoint> points, double p) {
        if (points.isEmpty()) return 0.0;

        List<Double> sorted = points.stream()
                .mapToDouble(MetricPoint::getValue)
                .sorted()
                .boxed()
                .collect(Collectors.toList());

        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /**
     * Rate of change: (last value - first value) / time difference in seconds.
     * Points are sorted by timestamp before calculation.
     * Returns 0 for empty list or single-point list.
     */
    public double rate(List<MetricPoint> points) {
        if (points.size() < 2) return 0.0;

        List<MetricPoint> sorted = points.stream()
                .sorted(Comparator.comparing(MetricPoint::getTimestamp))
                .collect(Collectors.toList());

        MetricPoint first = sorted.getFirst();
        MetricPoint last = sorted.getLast();

        double valueDiff = last.getValue() - first.getValue();
        double timeDiffSeconds = Duration.between(first.getTimestamp(), last.getTimestamp()).toMillis() / 1000.0;

        if (timeDiffSeconds == 0.0) return 0.0;
        return valueDiff / timeDiffSeconds;
    }

    /** Count of data points. Returns 0 for empty list. */
    public long count(List<MetricPoint> points) {
        return points.size();
    }

    /**
     * Distributes point values into buckets defined by the given boundaries.
     * Bucket labels: "0-10", "10-50", "50-100", "100+" (using boundary values).
     * Returns a map of bucket label to count of points in that bucket.
     */
    public Map<String, Long> histogram(List<MetricPoint> points, double[] bucketBoundaries) {
        if (points.isEmpty()) return Map.of();

        // Sort boundaries to ensure ascending order
        double[] boundaries = Arrays.copyOf(bucketBoundaries, bucketBoundaries.length);
        Arrays.sort(boundaries);

        // Build ordered bucket map: preserve insertion order for display
        Map<String, Long> buckets = new LinkedHashMap<>();

        // Initialize all buckets with zero counts
        for (int i = 0; i < boundaries.length; i++) {
            if (i == 0) {
                buckets.put("0-" + formatBoundary(boundaries[i]), 0L);
            } else {
                buckets.put(formatBoundary(boundaries[i - 1]) + "-" + formatBoundary(boundaries[i]), 0L);
            }
        }
        // Overflow bucket
        buckets.put(formatBoundary(boundaries[boundaries.length - 1]) + "+", 0L);

        // Distribute points into buckets
        for (MetricPoint point : points) {
            double value = point.getValue();
            String bucket = findBucket(value, boundaries);
            buckets.merge(bucket, 1L, Long::sum);
        }

        return buckets;
    }

    // ---- private helpers ----

    private String findBucket(double value, double[] boundaries) {
        for (int i = 0; i < boundaries.length; i++) {
            if (value < boundaries[i]) {
                if (i == 0) {
                    return "0-" + formatBoundary(boundaries[i]);
                }
                return formatBoundary(boundaries[i - 1]) + "-" + formatBoundary(boundaries[i]);
            }
        }
        // Value exceeds all boundaries — overflow bucket
        return formatBoundary(boundaries[boundaries.length - 1]) + "+";
    }

    private String formatBoundary(double boundary) {
        if (boundary == (long) boundary) {
            return String.valueOf((long) boundary);
        }
        return String.valueOf(boundary);
    }
}
