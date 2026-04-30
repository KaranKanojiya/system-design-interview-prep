package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.model.*;
import com.systemdesign.videostreaming.strategy.streaming.ABRStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates streaming manifests and simulates adaptive bitrate playback.
 *
 * Streaming flow:
 *   1. Player requests manifest (HLS .m3u8 or DASH .mpd)
 *   2. Player picks initial quality based on current bandwidth
 *   3. For each segment (every 4s): measure bandwidth → ABR selects resolution → fetch segment
 *   4. If bandwidth drops: ABR downgrades quality (avoid rebuffer)
 *   5. If bandwidth rises: ABR upgrades quality (improve experience)
 *
 * In production:
 *   - Manifests are pre-generated during transcoding and cached at CDN edge
 *   - Segments are stored on S3, cached at CDN edge locations worldwide
 *   - ABR runs entirely on the client (player logic, not server)
 *   - Server just serves static segment files (no per-user server logic during playback)
 */
public class StreamingService {

    private final ABRStrategy abrStrategy;

    public StreamingService(ABRStrategy abrStrategy) {
        this.abrStrategy = abrStrategy;
    }

    /**
     * Generate a streaming manifest for a video.
     * The manifest lists all available quality variants and their segment URLs.
     */
    public StreamManifest generateManifest(Video video, String protocol) {
        List<StreamVariant> variants = new ArrayList<>();

        int segmentDuration = 4; // seconds per segment
        int totalSegments = (video.getDurationSeconds() + segmentDuration - 1) / segmentDuration;

        for (Resolution res : video.getAvailableResolutions()) {
            // Generate segment URLs for this variant
            // In production: these are CDN URLs like https://cdn.example.com/vid123/1080p/seg_001.ts
            List<String> segmentUrls = new ArrayList<>();
            for (int i = 0; i < totalSegments; i++) {
                String url = "https://cdn.example.com/" + video.getVideoId()
                        + "/" + res.getLabel() + "/segment_" + i + (protocol.equals("HLS") ? ".ts" : ".m4s");
                segmentUrls.add(url);
            }

            // Apply codec compression ratio to bitrate
            int adjustedBitrate = (int) (res.getBitrateKbps() * video.getCodec().getCompressionRatio());

            StreamVariant variant = new StreamVariant(
                    res, video.getCodec(), adjustedBitrate,
                    segmentDuration, totalSegments, segmentUrls
            );
            variants.add(variant);
        }

        return new StreamManifest(video.getVideoId(), protocol, variants);
    }

    /**
     * Simulate an adaptive bitrate streaming session.
     *
     * Simulates bandwidth fluctuations over the watch duration and shows
     * how the ABR algorithm switches between resolutions in response.
     *
     * Returns a list of strings describing each segment's resolution choice.
     */
    public List<String> simulateStreaming(Video video, String userId,
                                          long baseBandwidthKbps, int watchDurationSeconds) {
        List<String> log = new ArrayList<>();
        List<Resolution> available = new ArrayList<>(video.getAvailableResolutions());

        // Sort resolutions by height for ABR (lowest first)
        available.sort((a, b) -> Integer.compare(a.getHeight(), b.getHeight()));

        if (available.isEmpty()) {
            log.add("ERROR: No resolutions available for video " + video.getVideoId());
            return log;
        }

        int segmentDuration = 4; // seconds
        int totalSegments = (watchDurationSeconds + segmentDuration - 1) / segmentDuration;
        double buffer = 0.0; // Buffer starts empty

        Resolution previousResolution = null;
        int switchCount = 0;

        for (int seg = 0; seg < totalSegments; seg++) {
            int currentTime = seg * segmentDuration;

            // Simulate bandwidth fluctuation (sine wave + noise)
            // This models real-world bandwidth: it varies over time
            double fluctuation = Math.sin(seg * 0.5) * 0.3 + (Math.random() * 0.2 - 0.1);
            long currentBandwidth = (long) (baseBandwidthKbps * (1.0 + fluctuation));
            currentBandwidth = Math.max(200_000, currentBandwidth); // Floor at 200 Kbps

            // ABR selects resolution based on current bandwidth and buffer
            Resolution selected = abrStrategy.selectResolution(currentBandwidth, buffer, available);

            // Update buffer: gained time from segment, lost time if bitrate > bandwidth
            double downloadTimeSeconds = (double) selected.getBitrateKbps() / currentBandwidth * segmentDuration;
            buffer = buffer + segmentDuration - downloadTimeSeconds;
            buffer = Math.max(0, Math.min(60, buffer)); // Clamp 0-60s

            // Track resolution switches
            if (previousResolution != null && previousResolution != selected) {
                switchCount++;
            }

            String entry = String.format("  [%3ds] BW: %,d Kbps | Buffer: %4.1fs | Quality: %-5s%s",
                    currentTime, currentBandwidth, buffer, selected.getLabel(),
                    (previousResolution != null && previousResolution != selected)
                            ? " (SWITCH from " + previousResolution.getLabel() + ")" : "");
            log.add(entry);

            previousResolution = selected;
        }

        log.add("  --- Session summary: " + totalSegments + " segments, "
                + switchCount + " quality switches ---");

        return log;
    }

    public ABRStrategy getAbrStrategy() {
        return abrStrategy;
    }
}
