package com.systemdesign.filestorage.store;

/**
 * BlockStore — interface for content-addressable block storage.
 *
 * This is the lowest layer of the storage system. It stores raw byte arrays
 * keyed by their SHA-256 hash (content-addressable). Two blocks with identical
 * content produce the same hash → same key → stored once.
 *
 * In a real system:
 * - Google Drive: Google Colossus (distributed file system)
 * - Dropbox: Amazon S3 (object storage)
 * - The key is the content hash, making it inherently deduplicated at the storage layer.
 *
 * Reference counting:
 *   Multiple files/versions may reference the same block. We track how many
 *   references each block has. Only delete the actual bytes when refCount reaches 0.
 *
 * Call chain:
 *   DeduplicationService.storeChunk → blockStore.storeBlock(hash, data)
 *   DownloadService.downloadFile → blockStore.getBlock(hash) for each chunk
 *   TrashService.permanentDelete → blockStore.deleteBlock(hash) (decrements ref count)
 */
public interface BlockStore {

    /**
     * Store a block. If the hash already exists, increment reference count
     * (content-addressable: same hash = same data, no need to re-store bytes).
     */
    void storeBlock(String hash, byte[] data);

    /** Retrieve a block by its hash. Returns null if not found. */
    byte[] getBlock(String hash);

    /**
     * Decrement reference count for a block. Only delete the actual bytes
     * when the reference count reaches zero.
     */
    void deleteBlock(String hash);

    /** Check if a block with this hash exists. */
    boolean exists(String hash);

    /** Total number of unique blocks stored. */
    int getBlockCount();

    /** Total bytes across all stored blocks. */
    long getTotalSizeBytes();
}
