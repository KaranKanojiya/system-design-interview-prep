package com.systemdesign.videostreaming.model;

/**
 * Video codecs with compression characteristics.
 *
 * Compression ratio is relative to H264 (baseline = 1.0).
 * A ratio of 0.5 means the codec produces files ~50% the size of H264 at equivalent quality.
 *
 * Why this matters for system design:
 *   - Codec choice affects storage cost (S3 bills per GB)
 *   - Codec choice affects bandwidth cost (CDN bills per GB transferred)
 *   - Newer codecs (AV1) save bandwidth but cost more CPU to encode
 *   - Trade-off: encoding cost (one-time) vs bandwidth savings (per-view)
 *   - For popular videos, AV1 encoding pays for itself quickly
 */
public enum Codec {

    H264(1.0, "Most compatible"),
    H265(0.5, "50% smaller than H264"),
    VP9(0.55, "Google's codec"),
    AV1(0.4, "Best compression, newest");

    private final double compressionRatio;
    private final String description;

    Codec(double compressionRatio, String description) {
        this.compressionRatio = compressionRatio;
        this.description = description;
    }

    /**
     * Returns the compression ratio relative to H264.
     * Lower = better compression = smaller file sizes.
     */
    public double getCompressionRatio() { return compressionRatio; }
    public String getDescription() { return description; }

    /**
     * Estimate file size for a given base size (H264 baseline).
     * E.g., if H264 produces a 100MB file, AV1 produces ~40MB.
     */
    public long estimateFileSize(long h264BaselineBytes) {
        return (long) (h264BaselineBytes * compressionRatio);
    }

    @Override
    public String toString() {
        return name() + " (ratio=" + compressionRatio + ", " + description + ")";
    }
}
