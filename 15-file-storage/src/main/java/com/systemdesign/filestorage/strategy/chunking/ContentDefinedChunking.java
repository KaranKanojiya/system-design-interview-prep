package com.systemdesign.filestorage.strategy.chunking;

import com.systemdesign.filestorage.model.FileChunk;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * ContentDefinedChunking — simplified Rabin fingerprint chunking.
 *
 * Better for dedup because edits don't shift all chunk boundaries. Used by Dropbox.
 *
 * How it works:
 *   Instead of splitting at fixed offsets, we scan the bytes and create a chunk
 *   boundary whenever a rolling hash of a sliding window hits a "magic" value.
 *   Because boundaries are determined by content (not position), inserting bytes
 *   only affects the chunks near the insertion point — other chunk boundaries
 *   remain stable.
 *
 * Example: inserting 1 byte at offset 0 of a 100MB file:
 * - Fixed chunking: all 25 chunks change (boundaries shifted)
 * - Content-defined: only 1-2 chunks near the edit point change
 *
 * Parameters:
 * - WINDOW_SIZE: sliding window for rolling hash (48 bytes, like Rabin fingerprint).
 * - BOUNDARY_MASK: when (rollingHash & mask) == 0, we cut. The mask determines average
 *   chunk size: mask 0x1FFFFF → avg ~2MB, we target ~4MB with 0x3FFFFF.
 * - MIN_CHUNK / MAX_CHUNK: prevent pathologically small/large chunks.
 *
 * Call chain:
 *   UploadService → this.chunk(fileId, data) → returns List<FileChunk>
 */
public class ContentDefinedChunking implements ChunkingStrategy {

    private static final int WINDOW_SIZE = 48;           // sliding window size (bytes)
    private static final int MIN_CHUNK = 1 * 1024 * 1024;  // 1MB minimum
    private static final int MAX_CHUNK = 8 * 1024 * 1024;  // 8MB maximum
    private static final long BOUNDARY_MASK = 0x3FFFFF;     // avg ~4MB chunks

    // Gear table: pre-computed random values for each byte value (0-255).
    // This is a simplified version of the Gear hash used in FastCDC.
    // In production, Rabin fingerprinting or Gear hashing is used; we use a
    // deterministic pseudo-random table so results are reproducible.
    private static final long[] GEAR_TABLE = new long[256];

    static {
        // Deterministic seed-based initialization for reproducible chunk boundaries.
        // Uses a simple LCG (linear congruential generator) for determinism.
        long seed = 0xABCDEF0123456789L;
        for (int i = 0; i < 256; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            GEAR_TABLE[i] = seed;
        }
    }

    @Override
    public List<FileChunk> chunk(String fileId, byte[] data) {
        List<FileChunk> chunks = new ArrayList<>();

        if (data.length == 0) {
            return chunks;
        }

        // If file is smaller than min chunk, return as single chunk
        if (data.length <= MIN_CHUNK) {
            String hash = sha256(data);
            chunks.add(new FileChunk(UUID.randomUUID().toString(), fileId, 0, hash, data.length));
            return chunks;
        }

        int chunkStart = 0;
        int chunkIndex = 0;

        while (chunkStart < data.length) {
            // Determine where this chunk ends.
            // Start scanning after MIN_CHUNK bytes (no point cutting earlier).
            int scanStart = Math.min(chunkStart + MIN_CHUNK, data.length);
            int chunkEnd = Math.min(chunkStart + MAX_CHUNK, data.length);

            // If we're near the end, just take whatever's left
            if (data.length - chunkStart <= MAX_CHUNK) {
                chunkEnd = data.length;
            } else {
                // Rolling hash scan: look for a boundary between scanStart and chunkEnd
                long fingerprint = 0;
                boolean found = false;

                for (int i = scanStart; i < chunkEnd; i++) {
                    // Gear hash: shift left and XOR with table entry for current byte
                    fingerprint = (fingerprint << 1) + GEAR_TABLE[data[i] & 0xFF];

                    // Check if the fingerprint hits our boundary condition
                    if ((fingerprint & BOUNDARY_MASK) == 0) {
                        chunkEnd = i + 1;  // cut here (inclusive of this byte)
                        found = true;
                        break;
                    }
                }

                // If no boundary found, chunkEnd stays at MAX_CHUNK (forced cut)
            }

            // Extract chunk bytes and compute hash
            byte[] chunkData = Arrays.copyOfRange(data, chunkStart, chunkEnd);
            String hash = sha256(chunkData);

            chunks.add(new FileChunk(
                    UUID.randomUUID().toString(),
                    fileId,
                    chunkIndex,
                    hash,
                    chunkData.length
            ));

            chunkStart = chunkEnd;
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
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
