package com.systemdesign.filestorage.strategy.chunking;

import com.systemdesign.filestorage.model.FileChunk;

import java.util.List;

/**
 * ChunkingStrategy — Strategy Pattern for file splitting.
 *
 * Why chunk files?
 * 1. Resumable uploads: if a 1GB upload fails at 800MB, only re-upload the missing chunks.
 * 2. Deduplication: identical chunks across files are stored once.
 * 3. Parallel transfer: chunks can be uploaded/downloaded concurrently.
 * 4. Delta sync: only changed chunks need to be transferred on file update.
 *
 * Implementations:
 * - FixedSizeChunking: simple, predictable. Used by Google Drive.
 * - ContentDefinedChunking: better dedup. Used by Dropbox.
 *
 * Call chain:
 *   UploadService.uploadFile → chunkingStrategy.chunk(fileId, data) → List<FileChunk>
 */
public interface ChunkingStrategy {

    /**
     * Split file data into chunks.
     *
     * @param fileId  the file these chunks belong to
     * @param data    the raw file bytes to split
     * @return ordered list of FileChunk objects (index 0, 1, 2, ...)
     */
    List<FileChunk> chunk(String fileId, byte[] data);
}
