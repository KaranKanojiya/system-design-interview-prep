package com.systemdesign.videostreaming.store;

import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.VideoChunk;

/**
 * Abstraction for video chunk storage (binary data layer).
 *
 * Separate from VideoRepository (metadata) because:
 *   - Video metadata goes in a database (PostgreSQL/DynamoDB) — small, structured
 *   - Video chunks go in object storage (S3/GCS) — large, binary blobs
 *   - Different storage backends, different scaling characteristics
 *
 * In production: S3 with server-side encryption, lifecycle policies
 * (move to Glacier after 90 days of no access), cross-region replication.
 */
public interface VideoStore {

    /** Store a video chunk in the object store. */
    void storeChunk(VideoChunk chunk);

    /** Retrieve a specific chunk by video ID, resolution, and index. */
    VideoChunk getChunk(String videoId, Resolution resolution, int chunkIndex);

    /** Delete all chunks for a video (called on video deletion). */
    void deleteChunks(String videoId);

    /** Count stored chunks for a video (used for upload progress). */
    int getStoredChunkCount(String videoId);
}
