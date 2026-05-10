package com.systemdesign.observability.engine;

// Wiring: SamplingEngine delegates sampling decisions to a pluggable SamplingStrategy.
// Used by TracingService -> decides whether to record a trace -> reduces storage cost.
// Strategy pattern: swap between rate-based, priority, adaptive sampling at runtime.

import com.systemdesign.observability.model.TraceContext;
import com.systemdesign.observability.strategy.sampling.SamplingStrategy;

/**
 * Decides whether to sample a trace/span based on the configured strategy.
 * Supports runtime strategy swapping for adaptive sampling.
 */
public class SamplingEngine {

    // Active sampling strategy — swappable at runtime
    private SamplingStrategy defaultStrategy;

    public SamplingEngine(SamplingStrategy defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }

    /** Delegates the sampling decision to the active strategy. */
    public boolean shouldSample(TraceContext context) {
        return defaultStrategy.shouldSample(context);
    }

    /** Delegates with an operation name hint for operation-specific decisions. */
    public boolean shouldSample(TraceContext context, String operationName) {
        return defaultStrategy.shouldSample(context, operationName);
    }

    /** Swaps the active sampling strategy at runtime. */
    public void setStrategy(SamplingStrategy strategy) {
        this.defaultStrategy = strategy;
    }

    /** Returns the name of the currently active strategy. */
    public String getStrategyName() {
        return defaultStrategy.getStrategyName();
    }
}
