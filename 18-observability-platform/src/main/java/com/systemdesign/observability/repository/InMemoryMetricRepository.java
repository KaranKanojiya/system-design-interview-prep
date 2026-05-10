package com.systemdesign.observability.repository;

// Wiring: InMemoryMetricRepository stores Metrics in a ConcurrentHashMap.
// Implements MetricRepository for the in-memory demo/testing path.

import com.systemdesign.observability.model.Metric;
import com.systemdesign.observability.model.MetricType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link MetricRepository} backed by ConcurrentHashMap.
 */
public class InMemoryMetricRepository implements MetricRepository {

    private final Map<String, Metric> store = new ConcurrentHashMap<>();

    @Override
    public void save(Metric metric) {
        store.put(metric.getId(), metric);
    }

    @Override
    public Optional<Metric> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Metric> findByName(String name) {
        return store.values().stream()
                .filter(m -> m.getName().equals(name))
                .collect(Collectors.toList());
    }

    @Override
    public List<Metric> findByType(MetricType type) {
        return store.values().stream()
                .filter(m -> m.getMetricType() == type)
                .collect(Collectors.toList());
    }

    @Override
    public List<Metric> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }
}
