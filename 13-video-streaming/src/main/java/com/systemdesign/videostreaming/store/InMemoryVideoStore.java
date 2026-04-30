package com.systemdesign.videostreaming.store;

import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.VideoChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of VideoStore — simulates S3 object storage.
 *
 * Key format: "videoId/resolution/chunkIndex"
 * This mimics S3 key naming conventions (prefix-based partitioning).
 *
 * Why ConcurrentHashMap?
 *   - Multiple upload threads may store chunks concurrently
 *   - ConcurrentHashMap is lock-striped (high throughput for concurrent writes)
 *   - In production: S3 handles concurrency natively (each PUT is atomic)
 */
public class InMemoryVideoStore implements VideoStore {

    // Key: "videoId/resolution/chunkIndex" → VideoChunk
    // Mimics S3's flat key namespace with prefix-based organization
    private final Map<String, VideoChunk> chunks = new ConcurrentHashMap<>();

    @Override
    public void storeChunk(VideoChunk chunk) {
        String key = buildKey(chunk.getVideoId(), chunk.getResolution(), chunk.getChunkIndex());
        chunks.put(key, chunk);
    }

    @Override
    public VideoChunk getChunk(String videoId, Resolution resolution, int chunkIndex) {
        String key = buildKey(videoId, resolution, chunkIndex);
        return chunks.get(key);
    }

    @Override
    public void deleteChunks(String videoId) {
        // Remove all chunks with the video's prefix
        // In S3: this would be a ListObjects + DeleteObjects batch operation
        chunks.entrySet().removeIf(entry -> entry.getKey().startsWith(videoId + "/"));
    }

    @Override
    public int getStoredChunkCount(String videoId) {
        return (int) chunks.keySet().stream()
                .filter(key -> key.startsWith(videoId + "/"))
                .count();
    }

    /**
     * Build a storage key mimicking S3 key structure.
     * Example: "vid_123/P1080/7" → video vid_123, 1080p, chunk index 7
     */
    private String buildKey(String videoId, Resolution resolution, int chunkIndex) {
        String resLabel = (resolution != null) ? resolution.name() : "SOURCE";
        return videoId + "/" + resLabel + "/" + chunkIndex;
    }
}
