package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.exception.ChunkMismatchException;
import com.systemdesign.filestorage.exception.FileNotFoundException;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.FileVersion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * DownloadService — reassembles files from chunks for download.
 *
 * Download flow:
 *   1. Get FileMetadata from MetadataService
 *   2. Get the latest version's chunk hashes from VersionService
 *   3. For each chunk hash: fetch raw bytes from DeduplicationService (→ BlockStore)
 *   4. Reassemble chunks in order into a single byte[]
 *   5. Verify SHA-256 checksum of reassembled data matches stored fileHash
 *   6. Return the byte[]
 *
 * Integrity verification:
 *   After reassembly, we compute SHA-256 of the complete file and compare with
 *   the stored fileHash. If they don't match, data corruption has occurred
 *   (e.g., a block was modified or a chunk was fetched out of order).
 *
 * Call chain:
 *   Controller.handleDownload → FileStorageService.downloadFile → this.downloadFile(fileId)
 */
public class DownloadService {

    private final MetadataService metadataService;
    private final VersionService versionService;
    private final DeduplicationService deduplicationService;

    public DownloadService(MetadataService metadataService,
                           VersionService versionService,
                           DeduplicationService deduplicationService) {
        this.metadataService = metadataService;
        this.versionService = versionService;
        this.deduplicationService = deduplicationService;
    }

    /**
     * Download a file by reassembling its chunks.
     *
     * @param fileId the file to download
     * @return the complete file contents as a byte array
     */
    public byte[] downloadFile(String fileId) {
        // 1. Get metadata
        FileMetadata metadata = metadataService.getFile(fileId);
        if (metadata.isDeleted()) {
            throw new FileNotFoundException("File is in trash: " + fileId);
        }

        // 2. Get latest version's chunk hashes
        FileVersion latestVersion = versionService.getLatestVersion(fileId);
        List<String> chunkHashes = latestVersion.getChunkHashes();

        // 3. Fetch and reassemble chunks
        byte[] result = new byte[(int) latestVersion.getSizeBytes()];
        int offset = 0;

        for (String hash : chunkHashes) {
            byte[] chunkData = deduplicationService.getChunk(hash);
            if (chunkData == null) {
                throw new FileNotFoundException("Chunk data not found for hash: " + hash);
            }
            System.arraycopy(chunkData, 0, result, offset, chunkData.length);
            offset += chunkData.length;
        }

        // 4. Verify integrity: compare SHA-256 of reassembled data with stored hash
        String actualHash = sha256(result);
        if (metadata.getFileHash() != null
                && !metadata.getFileHash().startsWith("resumable-")  // skip for resumable uploads
                && !actualHash.equals(metadata.getFileHash())) {
            throw new ChunkMismatchException(fileId, metadata.getFileHash(), actualHash);
        }

        return result;
    }

    /**
     * Download a specific version of a file.
     */
    public byte[] downloadVersion(String fileId, int versionNumber) {
        List<FileVersion> versions = versionService.getVersions(fileId);
        FileVersion targetVersion = versions.stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new FileNotFoundException(
                        "Version " + versionNumber + " not found for file " + fileId));

        byte[] result = new byte[(int) targetVersion.getSizeBytes()];
        int offset = 0;

        for (String hash : targetVersion.getChunkHashes()) {
            byte[] chunkData = deduplicationService.getChunk(hash);
            if (chunkData == null) {
                throw new FileNotFoundException("Chunk data not found for hash: " + hash);
            }
            System.arraycopy(chunkData, 0, result, offset, chunkData.length);
            offset += chunkData.length;
        }

        return result;
    }

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
