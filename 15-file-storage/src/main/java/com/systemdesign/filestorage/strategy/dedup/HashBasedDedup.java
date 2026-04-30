package com.systemdesign.filestorage.strategy.dedup;

import java.util.HashSet;
import java.util.Set;

/**
 * HashBasedDedup — content-addressable deduplication using SHA-256 hashes.
 *
 * Content-addressable storage: same data stored once regardless of how many
 * files reference it.
 *
 * How it works:
 *   We maintain a Set of known hashes. When a new chunk arrives:
 *   1. Compute its SHA-256 hash (already done by ChunkingStrategy).
 *   2. Check if hash is in our known set → isDuplicate().
 *   3. If duplicate: skip storing the bytes, increment dedupCount, add to savedBytes.
 *   4. If new: store the bytes, add hash to known set → registerHash().
 *
 * In a real system:
 * - The hash set would be a distributed database (e.g., Cassandra, DynamoDB).
 * - Hash collisions (two different chunks producing the same SHA-256) are
 *   astronomically unlikely (1 in 2^256), so we trust hash equality = content equality.
 *
 * Call chain:
 *   DeduplicationService → this.isDuplicate(hash) / this.registerHash(hash)
 */
public class HashBasedDedup implements DeduplicationStrategy {

    /** Set of SHA-256 hashes for all chunks we've already stored. */
    private final Set<String> knownHashes = new HashSet<>();

    /** Count of chunks that were detected as duplicates (storage skipped). */
    private int deduplicatedCount = 0;

    /** Total bytes saved by not storing duplicate chunks. */
    private long savedBytes = 0;

    @Override
    public boolean isDuplicate(String hash) {
        return knownHashes.contains(hash);
    }

    @Override
    public void registerHash(String hash) {
        knownHashes.add(hash);
    }

    /**
     * Record that a duplicate was found, tracking the bytes we saved.
     * Called by DeduplicationService when isDuplicate() returns true.
     */
    public void recordDuplicate(long chunkSizeBytes) {
        deduplicatedCount++;
        savedBytes += chunkSizeBytes;
    }

    @Override
    public int getDeduplicatedCount() {
        return deduplicatedCount;
    }

    @Override
    public long getSavedBytes() {
        return savedBytes;
    }
}
