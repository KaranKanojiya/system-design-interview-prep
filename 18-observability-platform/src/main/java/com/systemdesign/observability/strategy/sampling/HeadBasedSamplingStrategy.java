package com.systemdesign.observability.strategy.sampling;

import com.systemdesign.observability.model.TraceContext;

// Wiring: Head-based sampling decides at trace creation time using a deterministic hash of the traceId.
// Because the same traceId always produces the same hash, every span within a trace gets the same
// sample/no-sample decision — no coordination needed between services.

/**
 * Samples traces at creation time by hashing the traceId.
 * A consistent hash ensures all spans sharing a traceId are either all kept or all dropped.
 */
public class HeadBasedSamplingStrategy implements SamplingStrategy {

    private final double sampleRate;  // 0.0 = drop all, 1.0 = keep all

    /**
     * @param sampleRate fraction of traces to keep, in range [0.0, 1.0]
     */
    public HeadBasedSamplingStrategy(double sampleRate) {
        if (sampleRate < 0.0 || sampleRate > 1.0) {
            throw new IllegalArgumentException("sampleRate must be between 0.0 and 1.0, got: " + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    /**
     * Hash the traceId and compare against the sample rate threshold.
     * Using Math.abs guards against Integer.MIN_VALUE edge case.
     *
     * 1. Compute hash = Math.abs(traceId.hashCode())
     * 2. Normalize to 0–99 range via modulo 100
     * 3. If normalized hash < (sampleRate * 100) → sample
     */
    @Override
    public boolean shouldSample(TraceContext context) {
        if (context == null || context.getTraceId() == null) {
            return false;
        }
        int hash = Math.abs(context.getTraceId().hashCode());
        int bucket = hash % 100;
        return bucket < (int) (sampleRate * 100);
    }

    /**
     * Operation-specific overload delegates to the traceId-based decision.
     * Head-based sampling is operation-agnostic — the decision is made once
     * at the trace root and propagated to all downstream spans.
     */
    @Override
    public boolean shouldSample(TraceContext context, String operationName) {
        return shouldSample(context);
    }

    @Override
    public String getStrategyName() {
        return "HEAD_BASED";
    }
}
