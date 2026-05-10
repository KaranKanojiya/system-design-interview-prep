package com.systemdesign.observability.strategy.sampling;

import com.systemdesign.observability.model.TraceContext;

// Wiring: Tail-based sampling collects ALL spans initially and decides AFTER the trace completes.
// The shouldSample(context) method always returns true — the real filtering happens in the
// collector/aggregator layer once the full trace is assembled and error/latency data is available.
// The shouldSample(context, operationName) overload checks baggage for post-hoc signals
// (error flags, latency values) that the collector injects before invoking this strategy.

/**
 * Collects all spans upfront, then filters after trace completion.
 * Keeps error traces and high-latency traces; drops normal ones to save storage.
 */
public class TailBasedSamplingStrategy implements SamplingStrategy {

    private final double errorSampleRate;     // fraction of error traces to keep (0.0–1.0)
    private final long latencyThresholdMs;    // keep traces slower than this threshold

    /**
     * @param errorSampleRate     fraction of error traces to retain (typically 1.0 = keep all errors)
     * @param latencyThresholdMs  latency threshold in milliseconds; traces exceeding this are kept
     */
    public TailBasedSamplingStrategy(double errorSampleRate, long latencyThresholdMs) {
        if (errorSampleRate < 0.0 || errorSampleRate > 1.0) {
            throw new IllegalArgumentException("errorSampleRate must be between 0.0 and 1.0, got: " + errorSampleRate);
        }
        if (latencyThresholdMs < 0) {
            throw new IllegalArgumentException("latencyThresholdMs must be non-negative, got: " + latencyThresholdMs);
        }
        this.errorSampleRate = errorSampleRate;
        this.latencyThresholdMs = latencyThresholdMs;
    }

    /**
     * Always returns true — tail-based sampling collects everything initially.
     * The actual keep/drop decision is deferred to the post-collection phase.
     */
    @Override
    public boolean shouldSample(TraceContext context) {
        return true;
    }

    /**
     * Post-collection decision: examines baggage items injected by the collector.
     *
     * 1. If baggage contains "error" = "true" → keep the trace (errors are always interesting)
     * 2. If baggage contains "latency_ms" exceeding the threshold → keep (slow traces matter)
     * 3. Otherwise → drop to save storage
     *
     * In a real system this would run in the tail-sampling collector after the full trace
     * has been assembled from all participating services.
     */
    @Override
    public boolean shouldSample(TraceContext context, String operationName) {
        if (context == null) {
            return false;
        }

        // Check for error flag in baggage
        String errorFlag = context.getBaggageItem("error");
        if ("true".equalsIgnoreCase(errorFlag)) {
            return true;
        }

        // Check for latency exceeding the threshold
        String latencyStr = context.getBaggageItem("latency_ms");
        if (latencyStr != null) {
            try {
                long latencyMs = Long.parseLong(latencyStr);
                if (latencyMs > latencyThresholdMs) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Malformed baggage value — ignore and fall through to drop
            }
        }

        return false;
    }

    @Override
    public String getStrategyName() {
        return "TAIL_BASED";
    }
}
