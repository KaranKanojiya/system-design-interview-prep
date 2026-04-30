package com.systemdesign.filestorage.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * InMemoryBlockStore — in-memory content-addressable block storage with reference counting.
 *
 * How it works:
 *   blocks: Map<hash, byte[]> — the actual data
 *   refCounts: Map<hash, AtomicInteger> — how many files/versions reference each block
 *
 * Reference counting example:
 *   1. File A uploads chunk with hash "abc123" → refCount["abc123"] = 1
 *   2. File B uploads identical chunk (same hash) → refCount["abc123"] = 2 (no new storage!)
 *   3. File A is permanently deleted → refCount["abc123"] = 1 (block stays, B still needs it)
 *   4. File B is permanently deleted → refCount["abc123"] = 0 → block actually deleted
 *
 * This is critical for deduplication correctness: you can't delete shared blocks
 * just because one file that uses them was deleted.
 *
 * Call chain:
 *   DeduplicationService → this.storeBlock() / this.getBlock()
 *   TrashService.permanentDelete → this.deleteBlock() (ref count decrement)
 */
public class InMemoryBlockStore implements BlockStore {

    /** Content storage: hash → raw bytes */
    private final Map<String, byte[]> blocks = new ConcurrentHashMap<>();

    /** Reference counts: hash → count of files/versions referencing this block */
    private final Map<String, AtomicInteger> refCounts = new ConcurrentHashMap<>();

    @Override
    public void storeBlock(String hash, byte[] data) {
        if (blocks.containsKey(hash)) {
            // Block already exists — just increment reference count.
            // The data is identical (same hash = same content), so no need to re-store.
            refCounts.get(hash).incrementAndGet();
        } else {
            // New block — store data and initialize reference count to 1.
            blocks.put(hash, data);
            refCounts.put(hash, new AtomicInteger(1));
        }
    }

    @Override
    public byte[] getBlock(String hash) {
        return blocks.get(hash);
    }

    @Override
    public void deleteBlock(String hash) {
        AtomicInteger refCount = refCounts.get(hash);
        if (refCount == null) return;

        int remaining = refCount.decrementAndGet();
        if (remaining <= 0) {
            // No more references — safe to delete the actual bytes
            blocks.remove(hash);
            refCounts.remove(hash);
        }
        // If remaining > 0, other files still reference this block — keep it
    }

    @Override
    public boolean exists(String hash) {
        return blocks.containsKey(hash);
    }

    @Override
    public int getBlockCount() {
        return blocks.size();
    }

    @Override
    public long getTotalSizeBytes() {
        long total = 0;
        for (byte[] data : blocks.values()) {
            total += data.length;
        }
        return total;
    }

    /** Get reference count for a specific block (for debugging/display). */
    public int getRefCount(String hash) {
        AtomicInteger ref = refCounts.get(hash);
        return ref != null ? ref.get() : 0;
    }
}
