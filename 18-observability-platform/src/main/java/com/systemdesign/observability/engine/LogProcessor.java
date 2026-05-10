package com.systemdesign.observability.engine;

// Wiring: LogProcessor filters and enriches LogEntry objects through a pipeline.
// Used by LogIngestionService -> filters by level/custom predicates -> stores survivors in LogRepository.

import com.systemdesign.observability.model.LogEntry;
import com.systemdesign.observability.model.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Processes and filters log entries through a configurable pipeline.
 * Supports minimum-level filtering, custom predicates, and trace correlation enrichment.
 */
public class LogProcessor {

    // Minimum severity level — entries below this are dropped
    private LogLevel minLevel = LogLevel.INFO;

    // Custom filter predicates — entry must pass all to survive
    private final List<Predicate<LogEntry>> filters = new ArrayList<>();

    /** Sets the minimum log level; entries below this severity are filtered out. */
    public void setMinLevel(LogLevel level) {
        this.minLevel = level;
    }

    /** Adds a custom filter predicate to the processing pipeline. */
    public void addFilter(Predicate<LogEntry> filter) {
        filters.add(filter);
    }

    /**
     * Processes a single log entry through the pipeline.
     * Applies the level filter first, then all custom filters.
     *
     * @return the entry if it passes all filters, or empty if filtered out
     */
    public Optional<LogEntry> process(LogEntry entry) {
        // 1. Level gate — entry must be at or above the minimum level
        if (!entry.getLevel().isAtLeast(minLevel)) {
            return Optional.empty();
        }

        // 2. Custom filters — entry must pass every predicate
        for (Predicate<LogEntry> filter : filters) {
            if (!filter.test(entry)) {
                return Optional.empty();
            }
        }

        return Optional.of(entry);
    }

    /**
     * Processes a batch of log entries, returning only those that pass all filters.
     */
    public List<LogEntry> processBatch(List<LogEntry> entries) {
        return entries.stream()
                .map(this::process)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Enriches a log entry with distributed tracing correlation IDs.
     * Links the log to a specific trace and span for cross-signal correlation.
     */
    public void enrichWithCorrelation(LogEntry entry, String traceId, String spanId) {
        entry.setTraceId(traceId);
        entry.setSpanId(spanId);
    }
}
