package com.systemdesign.filestorage.strategy.dedup;

/**
 * DeduplicationStrategy — Strategy Pattern for chunk-level deduplication.
 *
 * Why deduplicate?
 *   If 100 users upload the same 10MB PDF, naive storage uses 1GB.
 *   With dedup, we store the PDF's chunks ONCE and reference them 100 times.
 *   Savings: 990MB (99%).
 *
 * How it works:
 *   Each chunk is hashed (SHA-256). Before storing, we check if a chunk with that
 *   hash already exists. If yes → skip storage, just add a reference. If no → store it.
 *
 * Implementations:
 * - HashBasedDedup: content-addressable, real dedup using a hash set.
 * - NoDedup: always stores, used as a baseline for comparison.
 *
 * Call chain:
 *   DeduplicationService.storeChunk(chunk, data) → strategy.isDuplicate(hash)
 *     → if false: BlockStore.storeBlock(hash, data) + strategy.registerHash(hash)
 *     → if true: skip store (data already exists)
 */
public interface DeduplicationStrategy {

    /** Check if a chunk with this hash has already been stored. */
    boolean isDuplicate(String hash);

    /** Register a hash as stored (call after successfully storing the block). */
    void registerHash(String hash);

    /** How many duplicate chunks were detected (skipped stores). */
    int getDeduplicatedCount();

    /** Total bytes saved by deduplication. */
    long getSavedBytes();
}
