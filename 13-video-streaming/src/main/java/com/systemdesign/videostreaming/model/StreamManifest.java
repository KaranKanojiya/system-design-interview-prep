package com.systemdesign.videostreaming.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HLS/DASH manifest containing all available quality variants for a video.
 *
 * The manifest is the first thing a player downloads when starting playback.
 * It tells the player: "Here are all the quality levels you can choose from,
 * and here are the segment URLs for each quality level."
 *
 * HLS (Apple): .m3u8 playlist → each variant is a sub-playlist of .ts segments
 * DASH (MPEG):  .mpd XML → each AdaptationSet contains segment templates
 *
 * In production: manifests are generated once (after transcoding) and cached at CDN edge.
 * They're tiny (~1KB) but requested for every playback session.
 */
public class StreamManifest {

    private final String videoId;
    private final String protocol;  // "HLS" or "DASH"
    private final List<StreamVariant> variants;
    private final LocalDateTime createdAt;

    public StreamManifest(String videoId, String protocol, List<StreamVariant> variants) {
        this.videoId = videoId;
        this.protocol = protocol;
        this.variants = new ArrayList<>(variants);
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Find the variant matching a specific resolution.
     * Returns null if no variant exists for that resolution.
     * Used by the ABR algorithm after it decides which resolution to use.
     */
    public StreamVariant getVariantForResolution(Resolution resolution) {
        for (StreamVariant variant : variants) {
            if (variant.getResolution() == resolution) {
                return variant;
            }
        }
        return null;
    }

    public String getVideoId() { return videoId; }
    public String getProtocol() { return protocol; }
    public List<StreamVariant> getVariants() { return Collections.unmodifiableList(variants); }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "StreamManifest{videoId='" + videoId + "', protocol='" + protocol
                + "', variants=" + variants.size() + "}";
    }
}
