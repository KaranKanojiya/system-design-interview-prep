package com.systemdesign.filestorage.model;

/**
 * FileChunk — represents a single chunk of a file after splitting.
 *
 * Design decisions:
 * - storageKey == hash: content-addressable storage. Two chunks with identical bytes
 *   produce the same hash → same storageKey → stored once in the block store.
 * - chunkIndex preserves ordering so we can reassemble the file correctly.
 * - hash is SHA-256 of the chunk's byte content (NOT the entire file hash).
 *
 * Call chain:
 *   ChunkingStrategy.chunk() → returns List<FileChunk>
 *   UploadService → DeduplicationService.storeChunk(chunk, data) → BlockStore.storeBlock(hash, data)
 *   DownloadService → BlockStore.getBlock(hash) for each chunk in order
 */
public class FileChunk {

    private final String chunkId;
    private final String fileId;
    private final int chunkIndex;     // 0-based order within the file
    private final String hash;        // SHA-256 of this chunk's bytes
    private final long sizeBytes;
    private final String storageKey;  // == hash for content-addressable storage

    public FileChunk(String chunkId, String fileId, int chunkIndex, String hash, long sizeBytes) {
        this.chunkId = chunkId;
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.hash = hash;
        this.sizeBytes = sizeBytes;
        // Content-addressable: the storage key IS the hash. If two different files
        // produce a chunk with the same bytes, they share the same block in storage.
        this.storageKey = hash;
    }

    public String getChunkId() { return chunkId; }
    public String getFileId() { return fileId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getHash() { return hash; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }

    @Override
    public String toString() {
        return String.format("FileChunk{id='%s', fileId='%s', index=%d, hash='%s', size=%d}",
                chunkId, fileId, chunkIndex,
                hash.substring(0, Math.min(8, hash.length())) + "...", sizeBytes);
    }
}
