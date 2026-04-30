package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.model.FileChunk;
import com.systemdesign.filestorage.store.BlockStore;
import com.systemdesign.filestorage.strategy.dedup.DeduplicationStrategy;
import com.systemdesign.filestorage.strategy.dedup.HashBasedDedup;

/**
 * DeduplicationService — orchestrates chunk storage with deduplication.
 *
 * This service wraps a DeduplicationStrategy + BlockStore to provide a clean
 * interface for storing and retrieving chunks while transparently deduplicating.
 *
 * Flow for storeChunk():
 *   1. Check strategy.isDuplicate(chunk.hash)
 *   2. If duplicate:
 *      - Skip storing bytes (they already exist in the block store)
 *      - Record the duplicate for statistics
 *      - Still increment block store reference count (another file references this block)
 *   3. If new:
 *      - Store bytes in block store
 *      - Register hash with the strategy
 *
 * Call chain:
 *   UploadService.uploadFile → this.storeChunk(chunk, data) for each chunk
 *   DownloadService.downloadFile → this.getChunk(hash) for each chunk
 *   TrashService.permanentDelete → blockStore.deleteBlock(hash) (ref count decrement)
 */
public class DeduplicationService {

    private final DeduplicationStrategy strategy;
    private final BlockStore blockStore;

    public DeduplicationService(DeduplicationStrategy strategy, BlockStore blockStore) {
        this.strategy = strategy;
        this.blockStore = blockStore;
    }

    /**
     * Store a chunk with deduplication.
     *
     * @param chunk the chunk metadata (contains hash, size)
     * @param data  the raw bytes of the chunk
     * @return true if the chunk was a duplicate (storage was skipped), false if new
     */
    public boolean storeChunk(FileChunk chunk, byte[] data) {
        if (strategy.isDuplicate(chunk.getHash())) {
            // Chunk already exists in block store — skip storing bytes.
            // But DO increment the reference count so we know another file uses it.
            blockStore.storeBlock(chunk.getHash(), data);

            // Track dedup statistics
            if (strategy instanceof HashBasedDedup hashDedup) {
                hashDedup.recordDuplicate(chunk.getSizeBytes());
            }
            return true;   // was a duplicate
        }

        // New chunk — store in block store and register hash with strategy
        blockStore.storeBlock(chunk.getHash(), data);
        strategy.registerHash(chunk.getHash());
        return false;       // was NOT a duplicate
    }

    /** Retrieve chunk data by its content hash. */
    public byte[] getChunk(String hash) {
        return blockStore.getBlock(hash);
    }

    /** Remove a chunk reference (decrements block store ref count). */
    public void removeChunk(String hash) {
        blockStore.deleteBlock(hash);
    }

    /** Check if a chunk with this hash exists. */
    public boolean chunkExists(String hash) {
        return blockStore.exists(hash);
    }

    // ── Statistics ───────────────────────────────────────────────────

    public String getStats() {
        return String.format("Dedup Stats: %d duplicates found, %s saved, %d unique blocks, %s total stored",
                strategy.getDeduplicatedCount(),
                formatBytes(strategy.getSavedBytes()),
                blockStore.getBlockCount(),
                formatBytes(blockStore.getTotalSizeBytes()));
    }

    public int getDuplicateCount() {
        return strategy.getDeduplicatedCount();
    }

    public long getSavedBytes() {
        return strategy.getSavedBytes();
    }

    public int getBlockCount() {
        return blockStore.getBlockCount();
    }

    public long getTotalStoredBytes() {
        return blockStore.getTotalSizeBytes();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
