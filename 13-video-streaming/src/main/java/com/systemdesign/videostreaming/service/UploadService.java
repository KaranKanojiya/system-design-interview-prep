package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.exception.UploadException;
import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.model.VideoChunk;
import com.systemdesign.videostreaming.store.VideoStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chunked video uploads with resume support.
 *
 * Upload flow:
 *   1. Client calls initiateUpload(video) → gets chunk plan (N chunks of 5MB each)
 *   2. Client uploads chunks one-by-one or in parallel: uploadChunk(videoId, index, data)
 *   3. Client checks progress: getUploadProgress(videoId) → percent
 *   4. If connection drops: client calls getUploadedChunkIndexes(videoId) to find
 *      which chunks are already stored, then resumes from missing chunks
 *   5. When isUploadComplete(videoId) returns true → video.markUploaded()
 *
 * Why chunked upload?
 *   - A 2GB video can't be uploaded in one HTTP request (timeout, memory)
 *   - 5MB chunks match S3 multipart upload minimum part size
 *   - Chunks can be uploaded in parallel across multiple connections
 *   - Resume: only re-upload failed chunks, not the entire file
 *
 * In production: presigned S3 URLs for direct-to-S3 upload (bypass application server).
 */
public class UploadService {

    /** Chunk size: 5MB. Matches S3 multipart upload minimum part size. */
    private static final long CHUNK_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final VideoStore videoStore;

    // Tracks the expected chunk count per video
    // Key: videoId → total number of chunks expected
    private final Map<String, Integer> expectedChunkCounts = new ConcurrentHashMap<>();

    // Tracks which chunks have been uploaded for each video
    // Key: videoId → set of uploaded chunk indexes
    private final Map<String, Set<Integer>> uploadedChunks = new ConcurrentHashMap<>();

    public UploadService(VideoStore videoStore) {
        this.videoStore = videoStore;
    }

    /**
     * Initiate a chunked upload: calculate how many chunks are needed.
     * Returns the total number of chunks the client should upload.
     */
    public int initiateUpload(Video video) {
        long fileSize = video.getFileSizeBytes();
        if (fileSize <= 0) {
            throw new UploadException("File size must be positive, got: " + fileSize);
        }

        // Calculate chunk count: ceiling division (last chunk may be smaller)
        int chunkCount = (int) Math.ceil((double) fileSize / CHUNK_SIZE_BYTES);

        expectedChunkCounts.put(video.getVideoId(), chunkCount);
        uploadedChunks.put(video.getVideoId(), Collections.synchronizedSet(new HashSet<>()));

        return chunkCount;
    }

    /**
     * Upload a single chunk. Idempotent: re-uploading the same chunk index is safe.
     * In production: each chunk would have a checksum for integrity verification.
     */
    public void uploadChunk(String videoId, int chunkIndex, byte[] data) {
        Integer expectedCount = expectedChunkCounts.get(videoId);
        if (expectedCount == null) {
            throw new UploadException("No upload initiated for video: " + videoId);
        }
        if (chunkIndex < 0 || chunkIndex >= expectedCount) {
            throw new UploadException("Invalid chunk index " + chunkIndex
                    + " for video " + videoId + " (expected 0-" + (expectedCount - 1) + ")");
        }

        // Create and store the chunk
        String chunkId = videoId + "_chunk_" + chunkIndex;
        String checksum = Integer.toHexString(Arrays.hashCode(data));
        String storageUrl = "s3://video-chunks/" + videoId + "/chunk_" + chunkIndex;

        VideoChunk chunk = new VideoChunk(
                chunkId, videoId, chunkIndex,
                data != null ? data.length : 0,
                null, // Resolution is null for source upload (pre-transcode)
                storageUrl, checksum
        );

        videoStore.storeChunk(chunk);
        uploadedChunks.get(videoId).add(chunkIndex);
    }

    /**
     * Check if all chunks have been uploaded for a video.
     */
    public boolean isUploadComplete(String videoId) {
        Integer expected = expectedChunkCounts.get(videoId);
        Set<Integer> uploaded = uploadedChunks.get(videoId);
        if (expected == null || uploaded == null) return false;
        return uploaded.size() >= expected;
    }

    /**
     * Get upload progress as a percentage (0-100).
     */
    public double getUploadProgress(String videoId) {
        Integer expected = expectedChunkCounts.get(videoId);
        Set<Integer> uploaded = uploadedChunks.get(videoId);
        if (expected == null || expected == 0 || uploaded == null) return 0.0;
        return (uploaded.size() * 100.0) / expected;
    }

    /**
     * Get the set of chunk indexes that have already been uploaded.
     * Used for resume: client compares this with the full set to find missing chunks.
     */
    public Set<Integer> getUploadedChunkIndexes(String videoId) {
        Set<Integer> uploaded = uploadedChunks.get(videoId);
        return uploaded != null ? Collections.unmodifiableSet(uploaded) : Collections.emptySet();
    }

    /**
     * Get the expected total number of chunks for this video.
     */
    public int getExpectedChunkCount(String videoId) {
        Integer count = expectedChunkCounts.get(videoId);
        return count != null ? count : 0;
    }

    /**
     * Find missing chunk indexes (for resume support).
     */
    public List<Integer> getMissingChunks(String videoId) {
        Integer expected = expectedChunkCounts.get(videoId);
        Set<Integer> uploaded = uploadedChunks.get(videoId);
        if (expected == null) return Collections.emptyList();

        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < expected; i++) {
            if (uploaded == null || !uploaded.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }
}
