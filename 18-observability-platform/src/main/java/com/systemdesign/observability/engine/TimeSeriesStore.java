package com.systemdesign.observability.engine;

// Wiring: TimeSeriesStore provides in-memory bucketed storage for MetricPoints.
// Used by MetricIngestionService -> stores points -> queried by QueryService for aggregation.

import com.systemdesign.observability.model.MetricPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory time-series storage with a bucketed TreeMap structure.
 * Uses metricName -> (epochSecond -> list of points) for efficient range queries.
 */
public class TimeSeriesStore {

    // metricName -> (epochSecond -> points recorded in that second)
    private final Map<String, TreeMap<Long, List<MetricPoint>>> store = new ConcurrentHashMap<>();

    /** Stores a metric data point in the bucketed structure. */
    public void store(MetricPoint point) {
        store.computeIfAbsent(point.getName(), k -> new TreeMap<>())
                .computeIfAbsent(point.getTimestamp().getEpochSecond(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(point);
    }

    /**
     * Queries all points for a metric within the given time range [from, to].
     * Leverages TreeMap.subMap for efficient range lookup.
     */
    public List<MetricPoint> query(String metricName, Instant from, Instant to) {
        TreeMap<Long, List<MetricPoint>> timeBuckets = store.get(metricName);
        if (timeBuckets == null) return List.of();

        // subMap is inclusive on fromKey, exclusive on toKey — add 1 to include 'to' second
        return timeBuckets.subMap(from.getEpochSecond(), to.getEpochSecond() + 1)
                .values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /** Returns the last N points for a metric, ordered by timestamp descending then reversed. */
    public List<MetricPoint> queryLatest(String metricName, int count) {
        TreeMap<Long, List<MetricPoint>> timeBuckets = store.get(metricName);
        if (timeBuckets == null) return List.of();

        // Walk backwards through buckets, collecting points
        List<MetricPoint> result = new ArrayList<>();
        for (Map.Entry<Long, List<MetricPoint>> entry : timeBuckets.descendingMap().entrySet()) {
            List<MetricPoint> bucketPoints = entry.getValue();
            for (int i = bucketPoints.size() - 1; i >= 0 && result.size() < count; i--) {
                result.add(bucketPoints.get(i));
            }
            if (result.size() >= count) break;
        }

        // Reverse so the result is in chronological order
        Collections.reverse(result);
        return result;
    }

    /** Returns all stored metric names. */
    public Set<String> getMetricNames() {
        return Collections.unmodifiableSet(store.keySet());
    }

    /** Returns the total number of points stored for a given metric. */
    public long getPointCount(String metricName) {
        TreeMap<Long, List<MetricPoint>> timeBuckets = store.get(metricName);
        if (timeBuckets == null) return 0L;

        return timeBuckets.values().stream()
                .mapToLong(List::size)
                .sum();
    }

    /** Returns the total number of points across all metrics. */
    public long getTotalPointCount() {
        return store.values().stream()
                .flatMap(tree -> tree.values().stream())
                .mapToLong(List::size)
                .sum();
    }

    /**
     * Downsamples a metric by averaging all points within each bucket of the given size.
     * Returns one averaged MetricPoint per bucket (using bucket start time as timestamp).
     */
    public List<MetricPoint> downsample(String metricName, Duration bucketSize) {
        TreeMap<Long, List<MetricPoint>> timeBuckets = store.get(metricName);
        if (timeBuckets == null) return List.of();

        long bucketSeconds = bucketSize.toSeconds();
        if (bucketSeconds <= 0) return List.of();

        // Collect all points, group by downsample bucket
        Map<Long, List<MetricPoint>> downsampledBuckets = new TreeMap<>();

        for (List<MetricPoint> points : timeBuckets.values()) {
            for (MetricPoint point : points) {
                long bucketKey = (point.getTimestamp().getEpochSecond() / bucketSeconds) * bucketSeconds;
                downsampledBuckets.computeIfAbsent(bucketKey, k -> new ArrayList<>())
                        .add(point);
            }
        }

        // Average each bucket into a single representative point
        List<MetricPoint> result = new ArrayList<>();
        for (Map.Entry<Long, List<MetricPoint>> entry : downsampledBuckets.entrySet()) {
            List<MetricPoint> bucketPoints = entry.getValue();
            double avgValue = bucketPoints.stream()
                    .mapToDouble(MetricPoint::getValue)
                    .average()
                    .orElse(0.0);

            MetricPoint representative = new MetricPoint(
                    metricName,
                    avgValue,
                    Instant.ofEpochSecond(entry.getKey()),
                    bucketPoints.getFirst().getMetricType(),
                    bucketPoints.getFirst().getTags()
            );
            result.add(representative);
        }

        return result;
    }
}
