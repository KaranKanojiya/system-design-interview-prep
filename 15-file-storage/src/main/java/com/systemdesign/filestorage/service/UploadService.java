package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.exception.FileNotFoundException;
import com.systemdesign.filestorage.exception.StorageQuotaExceededException;
import com.systemdesign.filestorage.model.FileChunk;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.FileVersion;
import com.systemdesign.filestorage.model.SyncEventType;
import com.systemdesign.filestorage.model.User;
import com.systemdesign.filestorage.repository.UserRepository;
import com.systemdesign.filestorage.strategy.chunking.ChunkingStrategy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UploadService — orchestrates the file upload pipeline.
 *
 * Upload flow (complete file):
 *   1. Check user's storage quota
 *   2. Compute SHA-256 hash of entire file (for integrity + file-level dedup check)
 *   3. Split file into chunks using ChunkingStrategy
 *   4. For each chunk: deduplicate + store via DeduplicationService
 *   5. Create FileMetadata via MetadataService
 *   6. Create version 1 via VersionService
 *   7. Update user quota
 *   8. Record sync event
 *
 * Resumable upload flow:
 *   1. initResumableUpload(fileId) → tracks which chunks are uploaded
 *   2. uploadChunk(fileId, chunkIndex, data) → stores individual chunk
 *   3. getUploadProgress(fileId) → returns completion percentage
 *   4. When all chunks uploaded → finalize (create metadata + version)
 *
 * Call chain:
 *   Controller.handleUpload → FileStorageService.uploadFile → this.uploadFile(...)
 *   Controller.handleResumableUpload → this.uploadChunk(fileId, chunkIndex, data)
 */
public class UploadService {

    private final ChunkingStrategy chunkingStrategy;
    private final DeduplicationService deduplicationService;
    private final MetadataService metadataService;
    private final VersionService versionService;
    private final UserRepository userRepository;
    private final SyncService syncService;

    /**
     * Tracks resumable uploads: fileId → Map<chunkIndex, chunkHash>.
     * When all expected chunks are present, the upload is complete.
     */
    private final Map<String, ResumableUpload> resumableUploads = new ConcurrentHashMap<>();

    public UploadService(ChunkingStrategy chunkingStrategy,
                         DeduplicationService deduplicationService,
                         MetadataService metadataService,
                         VersionService versionService,
                         UserRepository userRepository,
                         SyncService syncService) {
        this.chunkingStrategy = chunkingStrategy;
        this.deduplicationService = deduplicationService;
        this.metadataService = metadataService;
        this.versionService = versionService;
        this.userRepository = userRepository;
        this.syncService = syncService;
    }

    /**
     * Upload a complete file in one shot.
     *
     * @return the FileMetadata for the uploaded file
     */
    public FileMetadata uploadFile(String userId, String fileName, String path, byte[] data) {
        return uploadFile(userId, fileName, path, data, null);
    }

    /**
     * Upload a complete file with an optional parent folder.
     */
    public FileMetadata uploadFile(String userId, String fileName, String path, byte[] data,
                                   String parentFolderId) {
        // 1. Check quota
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new FileNotFoundException("User not found: " + userId));
        if (!user.getStorageQuota().canStore(data.length)) {
            throw new StorageQuotaExceededException(userId, data.length,
                    user.getStorageQuota().getRemainingBytes());
        }

        // 2. Compute SHA-256 hash of the entire file
        String fileHash = sha256(data);

        // 3. Generate file ID
        String fileId = UUID.randomUUID().toString();

        // 4. Chunk the data
        List<FileChunk> chunks = chunkingStrategy.chunk(fileId, data);

        // 5. Store each chunk with deduplication
        List<String> chunkHashes = new ArrayList<>();
        int duplicateChunks = 0;
        int offset = 0;

        for (FileChunk chunk : chunks) {
            // Extract this chunk's bytes from the original data
            int end = Math.min(offset + (int) chunk.getSizeBytes(), data.length);
            byte[] chunkData = Arrays.copyOfRange(data, offset, end);

            boolean wasDuplicate = deduplicationService.storeChunk(chunk, chunkData);
            if (wasDuplicate) duplicateChunks++;

            chunkHashes.add(chunk.getHash());
            offset = end;
        }

        // 6. Create file metadata
        String mimeType = guessMimeType(fileName);
        FileMetadata metadata = new FileMetadata.Builder(fileId, fileName)
                .filePath(path)
                .mimeType(mimeType)
                .sizeBytes(data.length)
                .fileHash(fileHash)
                .ownerId(userId)
                .isDirectory(false)
                .parentFolderId(parentFolderId)
                .build();

        metadataService.createFile(metadata);

        // 7. Create version 1
        versionService.createVersion(fileId, chunkHashes, data.length, userId, "Initial upload");

        // 8. Update quota
        user.getStorageQuota().addUsage(data.length);

        // 9. Record sync event
        syncService.recordChange(userId, fileId, fileName, SyncEventType.CREATED, "upload-service");

        return metadata;
    }

    /**
     * Upload a new version of an existing file.
     */
    public FileVersion uploadNewVersion(String fileId, String userId, byte[] data, String comment) {
        FileMetadata file = metadataService.getFile(fileId);

        // Check quota for the size difference
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new FileNotFoundException("User not found: " + userId));

        long sizeDiff = data.length - file.getSizeBytes();
        if (sizeDiff > 0 && !user.getStorageQuota().canStore(sizeDiff)) {
            throw new StorageQuotaExceededException(userId, sizeDiff,
                    user.getStorageQuota().getRemainingBytes());
        }

        // Chunk and store
        List<FileChunk> chunks = chunkingStrategy.chunk(fileId, data);
        List<String> chunkHashes = new ArrayList<>();
        int offset = 0;

        for (FileChunk chunk : chunks) {
            int end = Math.min(offset + (int) chunk.getSizeBytes(), data.length);
            byte[] chunkData = Arrays.copyOfRange(data, offset, end);
            deduplicationService.storeChunk(chunk, chunkData);
            chunkHashes.add(chunk.getHash());
            offset = end;
        }

        // Update metadata
        file.setSizeBytes(data.length);
        file.setFileHash(sha256(data));

        // Create new version
        FileVersion version = versionService.createVersion(fileId, chunkHashes, data.length, userId, comment);

        // Update quota
        if (sizeDiff > 0) {
            user.getStorageQuota().addUsage(sizeDiff);
        } else if (sizeDiff < 0) {
            user.getStorageQuota().removeUsage(-sizeDiff);
        }

        // Record sync event
        syncService.recordChange(userId, fileId, file.getFileName(), SyncEventType.MODIFIED, "upload-service");

        return version;
    }

    // ── Resumable Upload ─────────────────────────────────────────────

    /**
     * Initialize a resumable upload.
     * Returns a fileId that can be used to upload chunks individually.
     */
    public String initResumableUpload(String userId, String fileName, String path,
                                      int totalChunks, long totalSize) {
        String fileId = UUID.randomUUID().toString();
        resumableUploads.put(fileId, new ResumableUpload(
                fileId, userId, fileName, path, totalChunks, totalSize));
        return fileId;
    }

    /**
     * Upload a single chunk for a resumable upload.
     * Can be called out of order — chunks are indexed.
     */
    public void uploadChunk(String fileId, int chunkIndex, byte[] chunkData) {
        ResumableUpload upload = resumableUploads.get(fileId);
        if (upload == null) {
            throw new FileNotFoundException("No resumable upload found for: " + fileId);
        }

        // Hash the chunk
        String chunkHash = sha256(chunkData);

        // Create a FileChunk for dedup tracking
        FileChunk chunk = new FileChunk(
                UUID.randomUUID().toString(),
                fileId,
                chunkIndex,
                chunkHash,
                chunkData.length
        );

        // Store with dedup
        deduplicationService.storeChunk(chunk, chunkData);

        // Track this chunk as uploaded
        upload.addChunk(chunkIndex, chunkHash, chunkData.length);
    }

    /**
     * Get upload progress as a percentage (0-100).
     */
    public double getUploadProgress(String fileId) {
        ResumableUpload upload = resumableUploads.get(fileId);
        if (upload == null) return 0;
        return upload.getProgressPercent();
    }

    /**
     * Get the number of uploaded chunks.
     */
    public int getUploadedChunkCount(String fileId) {
        ResumableUpload upload = resumableUploads.get(fileId);
        if (upload == null) return 0;
        return upload.uploadedChunks.size();
    }

    /**
     * Finalize a resumable upload — creates metadata and version.
     */
    public FileMetadata finalizeResumableUpload(String fileId) {
        ResumableUpload upload = resumableUploads.get(fileId);
        if (upload == null) {
            throw new FileNotFoundException("No resumable upload found for: " + fileId);
        }

        // Check quota
        User user = userRepository.findById(upload.userId)
                .orElseThrow(() -> new FileNotFoundException("User not found: " + upload.userId));
        if (!user.getStorageQuota().canStore(upload.uploadedBytes)) {
            throw new StorageQuotaExceededException(upload.userId, upload.uploadedBytes,
                    user.getStorageQuota().getRemainingBytes());
        }

        // Collect chunk hashes in order
        List<String> chunkHashes = new ArrayList<>();
        for (int i = 0; i < upload.totalChunks; i++) {
            String hash = upload.uploadedChunks.get(i);
            if (hash == null) {
                throw new FileNotFoundException("Missing chunk " + i + " for upload " + fileId);
            }
            chunkHashes.add(hash);
        }

        // Create metadata
        String mimeType = guessMimeType(upload.fileName);
        FileMetadata metadata = new FileMetadata.Builder(fileId, upload.fileName)
                .filePath(upload.path)
                .mimeType(mimeType)
                .sizeBytes(upload.uploadedBytes)
                .fileHash("resumable-" + fileId) // Simplified: true hash requires reassembly
                .ownerId(upload.userId)
                .build();

        metadataService.createFile(metadata);
        versionService.createVersion(fileId, chunkHashes, upload.uploadedBytes, upload.userId, "Resumable upload");
        user.getStorageQuota().addUsage(upload.uploadedBytes);
        syncService.recordChange(upload.userId, fileId, upload.fileName, SyncEventType.CREATED, "upload-service");

        // Clean up
        resumableUploads.remove(fileId);

        return metadata;
    }

    // ── Helper: MIME type guessing ───────────────────────────────────

    private String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "application/vnd.ms-excel";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".java")) return "text/x-java-source";
        return "application/octet-stream";
    }

    // ── Helper: SHA-256 ──────────────────────────────────────────────

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

    // ── Inner class for resumable upload tracking ────────────────────

    /**
     * Tracks state of an in-progress resumable upload.
     * Maps chunkIndex → hash for each uploaded chunk.
     */
    private static class ResumableUpload {
        final String fileId;
        final String userId;
        final String fileName;
        final String path;
        final int totalChunks;
        final long totalSize;
        final Map<Integer, String> uploadedChunks = new HashMap<>();
        long uploadedBytes = 0;

        ResumableUpload(String fileId, String userId, String fileName, String path,
                        int totalChunks, long totalSize) {
            this.fileId = fileId;
            this.userId = userId;
            this.fileName = fileName;
            this.path = path;
            this.totalChunks = totalChunks;
            this.totalSize = totalSize;
        }

        void addChunk(int index, String hash, long sizeBytes) {
            uploadedChunks.put(index, hash);
            uploadedBytes += sizeBytes;
        }

        double getProgressPercent() {
            if (totalChunks == 0) return 100.0;
            return (double) uploadedChunks.size() / totalChunks * 100.0;
        }
    }
}
