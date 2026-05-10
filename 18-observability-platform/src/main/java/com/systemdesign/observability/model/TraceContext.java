package com.systemdesign.observability.model;

// Wiring: TraceContext propagates trace identity across service boundaries.
// Injected into HTTP headers / gRPC metadata by TracingService.

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carries trace identity and baggage across service boundaries for distributed context propagation.
 */
public class TraceContext {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final boolean sampled;
    private final Map<String, String> baggage;

    public TraceContext(String traceId, String spanId, String parentSpanId,
                        boolean sampled, Map<String, String> baggage) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.sampled = sampled;
        this.baggage = baggage != null ? new HashMap<>(baggage) : new HashMap<>();
    }

    // ---- factories ----

    /** Creates a brand-new trace context with a fresh traceId and spanId, no parent. */
    public static TraceContext newTrace() {
        return new TraceContext(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                null,
                true,
                new HashMap<>()
        );
    }

    // ---- propagation ----

    /**
     * Creates a child context: the current spanId becomes the parent,
     * and {@code newSpanId} becomes the active span.
     */
    public TraceContext createChild(String newSpanId) {
        return new TraceContext(
                this.traceId,
                newSpanId,
                this.spanId,
                this.sampled,
                new HashMap<>(this.baggage)
        );
    }

    // ---- baggage helpers ----

    public void setBaggage(String key, String value) {
        baggage.put(key, value);
    }

    public String getBaggageItem(String key) {
        return baggage.get(key);
    }

    // ---- getters ----

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public boolean isSampled() { return sampled; }
    public Map<String, String> getBaggage() { return Map.copyOf(baggage); }

    @Override
    public String toString() {
        return "TraceContext{traceId='" + traceId + "', spanId='" + spanId
                + "', parentSpanId='" + parentSpanId + "', sampled=" + sampled + "}";
    }
}
