package com.systemdesign.observability.repository;

// Wiring: InMemoryTraceRepository stores Traces in a ConcurrentHashMap.
// Implements TraceRepository for the in-memory demo/testing path.

import com.systemdesign.observability.model.Trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link TraceRepository} backed by ConcurrentHashMap.
 */
public class InMemoryTraceRepository implements TraceRepository {

    private final Map<String, Trace> store = new ConcurrentHashMap<>();

    @Override
    public void save(Trace trace) {
        store.put(trace.getTraceId(), trace);
    }

    @Override
    public Optional<Trace> findById(String traceId) {
        return Optional.ofNullable(store.get(traceId));
    }

    @Override
    public List<Trace> findByServiceName(String serviceName) {
        return store.values().stream()
                .filter(t -> t.getServiceName().equals(serviceName))
                .collect(Collectors.toList());
    }

    @Override
    public List<Trace> findByTimeRange(Instant from, Instant to) {
        return store.values().stream()
                .filter(t -> !t.getStartTime().isBefore(from) && !t.getStartTime().isAfter(to))
                .collect(Collectors.toList());
    }

    @Override
    public List<Trace> findAll() {
        return List.copyOf(store.values());
    }
}
