package com.systemdesign.observability.strategy.sampling;

import com.systemdesign.observability.model.TraceContext;

// Strategy Pattern (GoF) — determines whether a trace should be sampled

/**
 * Defines sampling behavior for distributed traces.
 * Implementations decide which traces to keep and which to drop,
 * balancing observability coverage against storage/network cost.
 */
public interface SamplingStrategy {

    /**
     * Decides whether the given trace context should be sampled.
     *
     * @param context the trace context carrying traceId and baggage
     * @return true if the trace should be collected, false to drop it
     */
    boolean shouldSample(TraceContext context);

    /**
     * Decides whether to sample, taking the operation name into account.
     * Implementations may apply operation-specific rules (e.g., always sample
     * health-check endpoints at a lower rate).
     *
     * @param context       the trace context
     * @param operationName the name of the operation being traced
     * @return true if the trace should be collected
     */
    boolean shouldSample(TraceContext context, String operationName);

    /** Returns a human-readable name for this sampling strategy. */
    String getStrategyName();
}
