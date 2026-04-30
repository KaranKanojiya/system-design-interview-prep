package com.systemdesign.videostreaming.strategy.streaming;

import com.systemdesign.videostreaming.model.Resolution;

import java.util.List;

/**
 * Adaptive Bitrate (ABR) strategy: decides which resolution to use for the next segment.
 *
 * ABR is the key algorithm in video streaming. It balances:
 *   - Quality: viewers want the highest resolution possible
 *   - Smoothness: rebuffering (stalls) are the #1 cause of viewer drop-off
 *   - Stability: constantly switching resolutions is jarring
 *
 * The strategy is called for EACH segment (every 4 seconds).
 * Inputs: current measured bandwidth + current buffer level.
 * Output: which resolution to request for the next segment.
 *
 * Two main approaches:
 *   - ThroughputBasedABR: react to bandwidth changes (simple, reactive)
 *   - BufferBasedABR: use buffer level as a stability signal (Netflix approach)
 */
public interface ABRStrategy {

    /**
     * Select the best resolution for the next video segment.
     *
     * @param bandwidthKbps current measured downstream bandwidth in Kbps
     * @param bufferSeconds number of seconds of video buffered ahead
     * @param available     list of available resolutions (sorted low to high)
     * @return the resolution to request for the next segment
     */
    Resolution selectResolution(long bandwidthKbps, double bufferSeconds, List<Resolution> available);
}
