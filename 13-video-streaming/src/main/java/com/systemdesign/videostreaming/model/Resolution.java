package com.systemdesign.videostreaming.model;

/**
 * Resolution ladder used for adaptive bitrate streaming.
 *
 * Each resolution defines:
 *   - Pixel dimensions (width x height)
 *   - Target bitrate in Kbps (for that quality level)
 *
 * Why these specific bitrates?
 *   - They match industry-standard encoding ladders (Netflix, YouTube).
 *   - The ABR algorithm picks the highest resolution whose bitrate
 *     fits within the user's measured bandwidth.
 *
 * In production: encoding ladders are per-title optimized
 * (a cartoon needs fewer bits than a sports broadcast at the same resolution).
 */
public enum Resolution {

    P240(240, 426, 240, 300_000),
    P360(360, 640, 360, 700_000),
    P480(480, 854, 480, 1_500_000),
    P720(720, 1280, 720, 3_000_000),
    P1080(1080, 1920, 1080, 6_000_000),
    P4K(2160, 3840, 2160, 15_000_000);

    private final int height;
    private final int width;
    private final int displayHeight;
    private final int bitrateKbps;

    Resolution(int height, int width, int displayHeight, int bitrateKbps) {
        this.height = height;
        this.width = width;
        this.displayHeight = displayHeight;
        this.bitrateKbps = bitrateKbps;
    }

    public int getHeight() { return height; }
    public int getWidth() { return width; }
    public int getDisplayHeight() { return displayHeight; }
    public int getBitrateKbps() { return bitrateKbps; }

    /**
     * Human-readable label: "720p", "1080p", "4K", etc.
     * This is what users see in the quality selector dropdown.
     */
    public String getLabel() {
        if (this == P4K) {
            return "4K";
        }
        return displayHeight + "p";
    }

    @Override
    public String toString() {
        return getLabel() + " (" + width + "x" + displayHeight + ", " + bitrateKbps / 1000 + " Mbps)";
    }
}
