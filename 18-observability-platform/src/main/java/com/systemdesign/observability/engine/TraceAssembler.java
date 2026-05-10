package com.systemdesign.observability.engine;

// Wiring: TraceAssembler collects Spans and assembles them into complete Traces.
// Used by TracingService -> spans arrive individually -> assembled into Trace -> stored in TraceRepository.

import com.systemdesign.observability.model.Span;
import com.systemdesign.observability.model.Trace;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles individual spans into complete distributed traces.
 * Spans arrive out of order; the assembler buffers them until a root span
 * appears and then builds the full Trace object.
 */
public class TraceAssembler {

    // traceId -> list of spans waiting for assembly
    private final Map<String, List<Span>> pendingSpans = new ConcurrentHashMap<>();

    /**
     * Adds a span to the pending buffer for its trace.
     * If the span is a root span (no parent), attempts assembly.
     */
    public void addSpan(Span span) {
        pendingSpans.computeIfAbsent(span.getTraceId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(span);
    }

    /**
     * Assembles all pending spans for the given traceId into a Trace.
     * Finds the root span (parentSpanId == null), builds the Trace, and removes
     * all pending spans for that traceId.
     *
     * @return the assembled Trace, or empty if no root span is found
     */
    public Optional<Trace> assembleTrace(String traceId) {
        List<Span> spans = pendingSpans.get(traceId);
        if (spans == null || spans.isEmpty()) {
            return Optional.empty();
        }

        // Find the root span — the one with no parent
        Optional<Span> rootSpan = spans.stream()
                .filter(s -> s.getParentSpanId() == null)
                .findFirst();

        if (rootSpan.isEmpty()) {
            return Optional.empty();
        }

        // Build Trace from the root span and all collected spans
        Span root = rootSpan.get();
        Trace trace = new Trace(traceId, root.getServiceName());
        for (Span span : spans) {
            trace.addSpan(span);
        }

        // Remove assembled spans from the pending buffer
        pendingSpans.remove(traceId);

        return Optional.of(trace);
    }

    /** Returns all trace IDs that have pending spans. */
    public Set<String> getTraceIds() {
        return Collections.unmodifiableSet(pendingSpans.keySet());
    }

    /** Returns the number of pending spans for a given trace. */
    public int getPendingSpanCount(String traceId) {
        List<Span> spans = pendingSpans.get(traceId);
        return spans != null ? spans.size() : 0;
    }
}
