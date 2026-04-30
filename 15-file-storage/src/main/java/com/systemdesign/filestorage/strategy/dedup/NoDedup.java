package com.systemdesign.filestorage.strategy.dedup;

/**
 * NoDedup — always stores every chunk, no deduplication.
 *
 * Baseline for comparison: shows how much storage dedup actually saves.
 * In demos, we compare HashBasedDedup vs NoDedup to illustrate the savings.
 *
 * Every call to isDuplicate() returns false → every chunk gets stored.
 */
public class NoDedup implements DeduplicationStrategy {

    @Override
    public boolean isDuplicate(String hash) {
        // Always returns false — no deduplication, every chunk is "new"
        return false;
    }

    @Override
    public void registerHash(String hash) {
        // No-op — we don't track anything
    }

    @Override
    public int getDeduplicatedCount() {
        return 0;
    }

    @Override
    public long getSavedBytes() {
        return 0;
    }
}
