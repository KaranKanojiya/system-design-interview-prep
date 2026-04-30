package com.systemdesign.videostreaming.strategy.streaming;

import com.systemdesign.videostreaming.model.Resolution;

import java.util.List;

/**
 * Throughput-based ABR: pick the highest resolution that fits within 80% of bandwidth.
 *
 * Algorithm:
 *   1. Measure current bandwidth (e.g., 5 Mbps)
 *   2. Apply safety margin: usable = 80% of measured (4 Mbps)
 *   3. Pick highest resolution where bitrate <= usable bandwidth
 *
 * Why 80% safety margin?
 *   - Bandwidth measurements are noisy (TCP throughput fluctuates)
 *   - Without margin, you'd pick a resolution that BARELY fits,
 *     and the slightest bandwidth dip causes rebuffering
 *   - 80% is conservative enough to avoid most stalls
 *
 * Pros: Simple, reactive, good for stable connections
 * Cons: Oscillates on unstable connections (keeps switching up/down)
 *
 * Used by early HLS players. Apple's original HLS spec recommended this approach.
 */
public class ThroughputBasedABR implements ABRStrategy {

    /** Safety margin: only use 80% of measured bandwidth to avoid rebuffering. */
    private static final double BANDWIDTH_SAFETY_FACTOR = 0.8;

    @Override
    public Resolution selectResolution(long bandwidthKbps, double bufferSeconds, List<Resolution> available) {
        if (available == null || available.isEmpty()) {
            throw new IllegalArgumentException("Available resolutions list cannot be empty");
        }

        // Usable bandwidth = measured * safety factor
        long usableBandwidth = (long) (bandwidthKbps * BANDWIDTH_SAFETY_FACTOR);

        // Start from the highest resolution, work down
        // Pick the first one where bitrate fits within usable bandwidth
        Resolution selected = available.get(0); // Default: lowest resolution
        for (Resolution res : available) {
            if (res.getBitrateKbps() <= usableBandwidth) {
                selected = res; // Keep going — we want the HIGHEST that fits
            }
        }

        return selected;
    }

    @Override
    public String toString() {
        return "ThroughputBasedABR (80% bandwidth utilization)";
    }
}
