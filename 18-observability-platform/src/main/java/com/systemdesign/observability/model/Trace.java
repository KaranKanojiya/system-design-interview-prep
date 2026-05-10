package com.systemdesign.observability.model;

// Wiring: Trace is a tree of Spans representing one end-to-end request.
// Assembled by TracingService -> stored in TraceRepository -> visualized by UI.

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A complete distributed trace — a directed tree of {@link Span}s rooted at a single entry point.
 */
public class Trace {

    private final String traceId;
    private Span rootSpan;
    private final List<Span> spans;
    private final String serviceName;
    private final Instant startTime;
    private Duration duration;

    public Trace(String traceId, String serviceName) {
        this.traceId = traceId;
        this.serviceName = serviceName;
        this.spans = new ArrayList<>();
        this.startTime = Instant.now();
    }

    // ---- mutations ----

    public void addSpan(Span span) {
        spans.add(span);
        // first span with no parent becomes the root
        if (span.getParentSpanId() == null && rootSpan == null) {
            rootSpan = span;
        }
        recalculateDuration();
    }

    // ---- queries ----

    public int getSpanCount() {
        return spans.size();
    }

    /** Returns all spans belonging to the given service. */
    public List<Span> getSpansByService(String service) {
        return spans.stream()
                .filter(s -> s.getServiceName().equals(service))
                .collect(Collectors.toList());
    }

    /** Returns all spans that ended with ERROR or TIMEOUT status. */
    public List<Span> getErrorSpans() {
        return spans.stream()
                .filter(s -> s.getStatus() == SpanStatus.ERROR
                        || s.getStatus() == SpanStatus.TIMEOUT)
                .collect(Collectors.toList());
    }

    /**
     * Builds a parent-to-children adjacency map for the span tree.
     * Key = parentSpanId (or "root" for top-level spans), Value = child spans.
     */
    public Map<String, List<Span>> buildSpanTree() {
        Map<String, List<Span>> tree = new HashMap<>();
        for (Span span : spans) {
            String parentKey = span.getParentSpanId() != null
                    ? span.getParentSpanId() : "root";
            tree.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(span);
        }
        return tree;
    }

    // ---- internal ----

    private void recalculateDuration() {
        if (spans.isEmpty()) return;
        Instant earliest = spans.stream()
                .map(Span::getStartTime)
                .min(Comparator.naturalOrder()).orElse(startTime);
        Instant latest = spans.stream()
                .map(s -> s.getEndTime() != null ? s.getEndTime() : s.getStartTime())
                .max(Comparator.naturalOrder()).orElse(startTime);
        this.duration = Duration.between(earliest, latest);
    }

    // ---- getters ----

    public String getTraceId() { return traceId; }
    public Span getRootSpan() { return rootSpan; }
    public List<Span> getSpans() { return Collections.unmodifiableList(spans); }
    public String getServiceName() { return serviceName; }
    public Instant getStartTime() { return startTime; }
    public Duration getDuration() { return duration; }

    @Override
    public String toString() {
        return "Trace{traceId='" + traceId + "', spans=" + spans.size()
                + ", duration=" + duration + "}";
    }
}
