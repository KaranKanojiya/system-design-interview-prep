package com.systemdesign.observability.repository;

// Wiring: InMemoryLogRepository stores LogEntries in a ConcurrentHashMap.
// Implements LogRepository for the in-memory demo/testing path.

import com.systemdesign.observability.model.LogEntry;
import com.systemdesign.observability.model.LogLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link LogRepository} backed by ConcurrentHashMap.
 * Uses a monotonic counter as key since LogEntry may not have a unique ID field.
 */
public class InMemoryLogRepository implements LogRepository {

    private final Map<Long, LogEntry> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public void save(LogEntry entry) {
        store.put(idGenerator.incrementAndGet(), entry);
    }

    @Override
    public List<LogEntry> findByLevel(LogLevel level) {
        // Returns entries at this level or higher severity
        return store.values().stream()
                .filter(e -> e.getLevel().isAtLeast(level))
                .collect(Collectors.toList());
    }

    @Override
    public List<LogEntry> findByServiceName(String serviceName) {
        return store.values().stream()
                .filter(e -> serviceName.equals(e.getServiceName()))
                .collect(Collectors.toList());
    }

    @Override
    public List<LogEntry> findByTraceId(String traceId) {
        return store.values().stream()
                .filter(e -> traceId.equals(e.getTraceId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<LogEntry> findByTimeRange(Instant from, Instant to) {
        return store.values().stream()
                .filter(e -> !e.getTimestamp().isBefore(from) && !e.getTimestamp().isAfter(to))
                .collect(Collectors.toList());
    }

    @Override
    public List<LogEntry> findAll() {
        return List.copyOf(store.values());
    }
}
