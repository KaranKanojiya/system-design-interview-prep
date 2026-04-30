package com.systemdesign.filestorage.exception;

/**
 * ChunkMismatchException — thrown when downloaded data doesn't match expected checksum.
 *
 * This is a data integrity error. During download, after reassembling all chunks,
 * we verify the SHA-256 hash of the complete file matches the stored fileHash.
 * If not, data corruption has occurred.
 */
public class ChunkMismatchException extends FileStorageException {

    public ChunkMismatchException(String fileId, String expected, String actual) {
        super(String.format("Chunk integrity mismatch for file '%s': expected hash '%s', got '%s'",
                fileId, expected, actual));
    }
}
