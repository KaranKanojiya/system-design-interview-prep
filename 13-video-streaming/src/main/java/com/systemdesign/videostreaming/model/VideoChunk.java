package com.systemdesign.videostreaming.model;

/**
 * Represents a single chunk of a video file.
 *
 * Why chunked uploads?
 *   - Large video files (GBs) can't be uploaded in a single HTTP request reliably.
 *   - Chunks enable resume: if the connection drops at chunk 47/100,
 *     the client resumes from chunk 48 instead of starting over.
 *   - Each chunk can be uploaded in parallel to different upload endpoints.
 *   - Chunk size is typically 5MB (matching S3 multipart upload minimum).
 *
 * In production: each chunk maps to an S3 multipart upload part.
 * The storageUrl would be an S3 key like "uploads/{videoId}/chunk_{index}".
 */
public class VideoChunk {

    private final String chunkId;
    private final String videoId;
    private final int chunkIndex;
    private final long sizeBytes;
    private final Resolution resolution;
    private final String storageUrl;
    private final String checksum;

    public VideoChunk(String chunkId, String videoId, int chunkIndex,
                      long sizeBytes, Resolution resolution, String storageUrl, String checksum) {
        this.chunkId = chunkId;
        this.videoId = videoId;
        this.chunkIndex = chunkIndex;
        this.sizeBytes = sizeBytes;
        this.resolution = resolution;
        this.storageUrl = storageUrl;
        this.checksum = checksum;
    }

    public String getChunkId() { return chunkId; }
    public String getVideoId() { return videoId; }
    public int getChunkIndex() { return chunkIndex; }
    public long getSizeBytes() { return sizeBytes; }
    public Resolution getResolution() { return resolution; }
    public String getStorageUrl() { return storageUrl; }
    public String getChecksum() { return checksum; }

    @Override
    public String toString() {
        return "VideoChunk{videoId='" + videoId + "', index=" + chunkIndex
                + ", size=" + sizeBytes + "B, resolution=" + resolution + "}";
    }
}
