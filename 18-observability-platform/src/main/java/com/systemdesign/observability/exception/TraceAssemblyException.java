package com.systemdesign.observability.exception;

// Wiring: Thrown when trace assembly fails (missing root span, orphaned spans, corrupt data).
// Caught by TracingService -> logged and surfaced to the caller.

/**
 * Thrown when a distributed trace cannot be assembled from its spans.
 */
public class TraceAssemblyException extends ObservabilityException {

    private final String traceId;

    public TraceAssemblyException(String traceId, String message) {
        super("Trace assembly failed for traceId='" + traceId + "': " + message);
        this.traceId = traceId;
    }

    public String getTraceId() {
        return traceId;
    }
}
