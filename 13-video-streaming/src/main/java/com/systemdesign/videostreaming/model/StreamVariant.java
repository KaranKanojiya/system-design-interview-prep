package com.systemdesign.videostreaming.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single variant (quality level) in an HLS/DASH manifest.
 *
 * Each variant represents one resolution + codec + bitrate combination.
 * The player picks one variant at a time based on bandwidth and buffer.
 *
 * Segment model:
 *   - The video is split into small segments (default 4 seconds each).
 *   - Each segment has its own URL (CDN-cacheable independently).
 *   - The player fetches segments sequentially, can switch variants between segments.
 *
 * Why 4-second segments?
 *   - Short enough to allow quick quality switching (low latency ABR)
 *   - Long enough to be efficiently encoded (keyframe every 4s)
 *   - Industry standard: Apple recommends 6s for HLS, Netflix uses 4s
 */
public class StreamVariant {

    private final Resolution resolution;
    private final Codec codec;
    private final int bitrateKbps;
    private final int segmentDurationSeconds;
    private final int segmentCount;
    private final List<String> segmentUrls;

    public StreamVariant(Resolution resolution, Codec codec, int bitrateKbps,
                         int segmentDurationSeconds, int segmentCount, List<String> segmentUrls) {
        this.resolution = resolution;
        this.codec = codec;
        this.bitrateKbps = bitrateKbps;
        this.segmentDurationSeconds = segmentDurationSeconds;
        this.segmentCount = segmentCount;
        this.segmentUrls = new ArrayList<>(segmentUrls);
    }

    public Resolution getResolution() { return resolution; }
    public Codec getCodec() { return codec; }
    public int getBitrateKbps() { return bitrateKbps; }
    public int getSegmentDurationSeconds() { return segmentDurationSeconds; }
    public int getSegmentCount() { return segmentCount; }
    public List<String> getSegmentUrls() { return Collections.unmodifiableList(segmentUrls); }

    @Override
    public String toString() {
        return "StreamVariant{" + resolution.getLabel() + ", " + codec.name()
                + ", " + bitrateKbps + " Kbps, " + segmentCount + " segments}";
    }
}
