package com.systemdesign.filestorage.strategy.chunking;

import com.systemdesign.filestorage.model.FileChunk;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * FixedSizeChunking — splits files into fixed 4MB chunks.
 *
 * Simple, predictable. Used by Google Drive.
 *
 * How it works:
 *   file bytes: [====4MB====][====4MB====][==2MB==]
 *                 chunk 0       chunk 1     chunk 2 (last chunk smaller)
 *
 * Pros:
 * - Simple implementation, easy to reason about chunk boundaries.
 * - Predictable chunk count: ceil(fileSize / chunkSize).
 *
 * Cons:
 * - Inserting data at the beginning shifts ALL chunk boundaries, making every
 *   chunk different even though most of the file is unchanged. This kills dedup.
 *   Example: inserting 1 byte at offset 0 of a 100MB file → all 25 chunks change.
 *
 * Call chain:
 *   UploadService → this.chunk(fileId, data) → returns List<FileChunk>
 *   Each FileChunk has a SHA-256 hash used as the storage key in BlockStore.
 */
public class FixedSizeChunking implements ChunkingStrategy {

    /** 4MB chunk size — matches common cloud storage chunk sizes. */
    private static final int CHUNK_SIZE = 4 * 1024 * 1024;  // 4MB

    @Override
    public List<FileChunk> chunk(String fileId, byte[] data) {
        List<FileChunk> chunks = new ArrayList<>();
        int offset = 0;
        int chunkIndex = 0;

        while (offset < data.length) {
            // Calculate how many bytes this chunk contains.
            // Last chunk may be smaller than CHUNK_SIZE.
            int end = Math.min(offset + CHUNK_SIZE, data.length);
            byte[] chunkData = Arrays.copyOfRange(data, offset, end);

            // SHA-256 hash of the chunk content — this becomes the storage key
            // in our content-addressable block store.
            String hash = sha256(chunkData);

            FileChunk chunk = new FileChunk(
                    UUID.randomUUID().toString(),
                    fileId,
                    chunkIndex,
                    hash,
                    chunkData.length
            );

            chunks.add(chunk);
            offset = end;
            chunkIndex++;
        }

        return chunks;
    }

    /**
     * Compute SHA-256 hash of data bytes.
     * Uses java.security.MessageDigest — real cryptographic hashing.
     */
    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in every Java implementation
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
