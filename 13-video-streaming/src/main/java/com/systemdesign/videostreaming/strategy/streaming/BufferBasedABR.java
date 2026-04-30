package com.systemdesign.videostreaming.strategy.streaming;

import com.systemdesign.videostreaming.model.Resolution;

import java.util.List;

/**
 * Buffer-based ABR (BBA): uses buffer level as the primary signal for quality selection.
 *
 * Algorithm:
 *   - Buffer < 5 seconds  → LOWEST resolution (emergency: prevent rebuffer!)
 *   - Buffer > 30 seconds → HIGHEST resolution (plenty of runway, go for quality)
 *   - Buffer 5-30 seconds → proportional mapping between lowest and highest
 *
 * Why buffer-based?
 *   - Bandwidth measurements are noisy and delayed
 *   - Buffer level is a DIRECT measure of "how safe are we from stalling?"
 *   - More conservative: sacrifices peak quality for playback smoothness
 *   - Fewer resolution switches (more stable viewing experience)
 *
 * Netflix uses buffer-based approach (BBA). Their research paper:
 * "A Buffer-Based Approach to Rate Adaptation" (Huang et al., SIGCOMM 2014)
 * showed BBA reduces rebuffering by 10-20% compared to throughput-based.
 */
public class BufferBasedABR implements ABRStrategy {

    /** Below this buffer level, switch to lowest quality to prevent stall. */
    private static final double LOW_BUFFER_THRESHOLD = 5.0;

    /** Above this buffer level, we can afford the highest quality. */
    private static final double HIGH_BUFFER_THRESHOLD = 30.0;

    @Override
    public Resolution selectResolution(long bandwidthKbps, double bufferSeconds, List<Resolution> available) {
        if (available == null || available.isEmpty()) {
            throw new IllegalArgumentException("Available resolutions list cannot be empty");
        }

        int numResolutions = available.size();

        // Emergency mode: buffer is critically low → lowest quality
        if (bufferSeconds < LOW_BUFFER_THRESHOLD) {
            return available.get(0);
        }

        // Comfort mode: buffer is full → highest quality
        if (bufferSeconds > HIGH_BUFFER_THRESHOLD) {
            // Still check bandwidth — don't pick a resolution that exceeds bandwidth
            // even with a full buffer (it would drain the buffer eventually)
            Resolution highest = available.get(0);
            for (Resolution res : available) {
                if (res.getBitrateKbps() <= bandwidthKbps) {
                    highest = res;
                }
            }
            return highest;
        }

        // Proportional zone: map buffer level to resolution index
        // buffer=5s → index=0 (lowest), buffer=30s → index=N-1 (highest)
        double fraction = (bufferSeconds - LOW_BUFFER_THRESHOLD)
                / (HIGH_BUFFER_THRESHOLD - LOW_BUFFER_THRESHOLD);
        int index = (int) (fraction * (numResolutions - 1));
        index = Math.max(0, Math.min(numResolutions - 1, index));

        // Additional bandwidth check: don't exceed available bandwidth
        Resolution candidate = available.get(index);
        if (candidate.getBitrateKbps() > bandwidthKbps) {
            // Fall back to the highest resolution that fits bandwidth
            for (int i = index - 1; i >= 0; i--) {
                if (available.get(i).getBitrateKbps() <= bandwidthKbps) {
                    return available.get(i);
                }
            }
            return available.get(0);
        }

        return candidate;
    }

    @Override
    public String toString() {
        return "BufferBasedABR (Netflix BBA approach)";
    }
}
