package com.systemdesign.observability.service;

// Wiring: LogService manages structured log ingestion and querying.
// Dependencies injected via constructor:
//   logRepo       — persists LogEntry objects
//   logProcessor  — filters and enriches log entries through a pipeline

import com.systemdesign.observability.model.LogEntry;
import com.systemdesign.observability.model.LogLevel;
import com.systemdesign.observability.engine.LogProcessor;
import com.systemdesign.observability.repository.LogRepository;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LogService — business logic for structured log management.
 *
 * FLOW — log(level, message, serviceName):
 *   1. Create a LogEntry with the given fields
 *   2. Pass through LogProcessor pipeline (level filter + custom predicates)
 *   3. If the entry survives, save to LogRepository
 *   4. Print [LOG] with the level
 *   5. Return the entry (or empty if filtered out)
 *
 * FLOW — logWithTrace(level, message, serviceName, traceId, spanId):
 *   1. Create a LogEntry
 *   2. Set traceId and spanId for distributed trace correlation
 *   3. Process and persist as above
 *
 * FLOW — search(serviceName, minLevel, from, to):
 *   1. Query LogRepository for entries matching service and time range
 *   2. Filter by minimum log level
 *   3. Return sorted results
 */
public class LogService {

    private final LogRepository logRepo;          // persists log entries
    private final LogProcessor logProcessor;      // filters and enriches entries

    public LogService(LogRepository logRepo, LogProcessor logProcessor) {
        this.logRepo = logRepo;
        this.logProcessor = logProcessor;
    }

    // ---- ingestion ----

    /**
     * Creates, processes, and stores a structured log entry.
     *
     * @param level       the severity level (TRACE, DEBUG, INFO, WARN, ERROR, FATAL)
     * @param message     the log message
     * @param serviceName the originating service
     * @return the persisted entry, or empty if filtered out by the processor
     */
    public Optional<LogEntry> log(LogLevel level, String message, String serviceName) {
        // 1. Create the entry
        LogEntry entry = new LogEntry(level, message, serviceName);

        // 2. Process through the pipeline (level gate + custom filters)
        Optional<LogEntry> processed = logProcessor.process(entry);

        // 3. Persist if it survived
        if (processed.isPresent()) {
            logRepo.save(processed.get());
            System.out.println("[LOG] [" + level + "] " + serviceName + ": " + message);
        }

        return processed;
    }

    /**
     * Creates a log entry correlated with a distributed trace.
     * Links the log to a specific trace and span for cross-signal correlation.
     *
     * @param level       the severity level
     * @param message     the log message
     * @param serviceName the originating service
     * @param traceId     the distributed trace ID
     * @param spanId      the span ID within the trace
     * @return the persisted entry, or empty if filtered out
     */
    public Optional<LogEntry> logWithTrace(LogLevel level, String message,
                                           String serviceName, String traceId, String spanId) {
        // 1. Create entry with correlation IDs
        LogEntry entry = new LogEntry(level, message, serviceName);
        entry.setTraceId(traceId);
        entry.setSpanId(spanId);

        // 2. Process through pipeline
        Optional<LogEntry> processed = logProcessor.process(entry);

        // 3. Persist if it survived
        if (processed.isPresent()) {
            logRepo.save(processed.get());
            System.out.println("[LOG] [" + level + "] " + serviceName
                    + ": " + message + " (traceId=" + traceId + ")");
        }

        return processed;
    }

    // ---- querying ----

    /**
     * Searches logs by service, minimum severity level, and time range.
     *
     * @param serviceName the service to filter by
     * @param minLevel    minimum severity (inclusive)
     * @param from        start of time window (inclusive)
     * @param to          end of time window (inclusive)
     * @return matching log entries, sorted by timestamp ascending
     */
    public List<LogEntry> search(String serviceName, LogLevel minLevel,
                                 Instant from, Instant to) {
        return logRepo.findAll().stream()
                .filter(e -> e.getServiceName().equals(serviceName))
                .filter(e -> e.getLevel().isAtLeast(minLevel))
                .filter(e -> !e.getTimestamp().isBefore(from) && !e.getTimestamp().isAfter(to))
                .sorted(Comparator.comparing(LogEntry::getTimestamp))
                .collect(Collectors.toList());
    }

    /**
     * Returns all log entries correlated with the given trace ID.
     *
     * @param traceId the distributed trace ID
     * @return log entries linked to this trace, sorted by timestamp
     */
    public List<LogEntry> getLogsByTrace(String traceId) {
        return logRepo.findAll().stream()
                .filter(e -> traceId.equals(e.getTraceId()))
                .sorted(Comparator.comparing(LogEntry::getTimestamp))
                .collect(Collectors.toList());
    }

    /**
     * Returns the most recent log entries, sorted by timestamp descending.
     *
     * @param limit maximum number of entries to return
     */
    public List<LogEntry> getRecentLogs(int limit) {
        return logRepo.findAll().stream()
                .sorted(Comparator.comparing(LogEntry::getTimestamp).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Returns the count of log entries grouped by severity level.
     *
     * @return map of LogLevel to count
     */
    public Map<LogLevel, Long> getLogCountByLevel() {
        return logRepo.findAll().stream()
                .collect(Collectors.groupingBy(LogEntry::getLevel, Collectors.counting()));
    }
}
