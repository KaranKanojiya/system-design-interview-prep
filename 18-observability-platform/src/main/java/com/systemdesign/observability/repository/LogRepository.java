package com.systemdesign.observability.repository;

// Wiring: LogRepository persists LogEntry objects.
// Used by LogIngestionService (write) and QueryService (read).

import com.systemdesign.observability.model.LogEntry;
import com.systemdesign.observability.model.LogLevel;

import java.time.Instant;
import java.util.List;

/**
 * Repository interface for storing and querying log entries.
 */
public interface LogRepository {

    void save(LogEntry entry);

    /** Finds entries at this level or higher severity. */
    List<LogEntry> findByLevel(LogLevel level);

    List<LogEntry> findByServiceName(String serviceName);

    List<LogEntry> findByTraceId(String traceId);

    List<LogEntry> findByTimeRange(Instant from, Instant to);

    List<LogEntry> findAll();
}
