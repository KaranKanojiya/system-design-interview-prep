package com.systemdesign.filestorage.model;

import java.time.Instant;

/**
 * FileMetadata — core entity representing a file or directory in the storage system.
 *
 * Design decisions:
 * - Builder pattern: many optional fields (mimeType, parentFolderId, deletedAt).
 * - Soft delete via isDeleted + deletedAt: allows trash/recovery without losing data.
 * - fileHash is SHA-256 of the ENTIRE file content (not individual chunks). Used to
 *   detect identical uploads at the file level before chunking even begins.
 * - filePath is a logical path like "/docs/report.pdf" — derived from folder hierarchy,
 *   not the physical storage location (blocks are content-addressed by hash).
 *
 * Call chain:
 *   UploadService.uploadFile → MetadataService.createFile(FileMetadata) → FileRepository.save()
 *   DownloadService.downloadFile → MetadataService.getFile(fileId) → returns FileMetadata
 */
public class FileMetadata {

    private final String fileId;
    private String fileName;
    private String filePath;          // logical path, e.g. "/docs/report.pdf"
    private String mimeType;
    private long sizeBytes;
    private String fileHash;          // SHA-256 of entire file content
    private String ownerId;
    private boolean isDirectory;
    private String parentFolderId;    // null for root-level items
    private Instant createdAt;
    private Instant updatedAt;
    private boolean isDeleted;        // soft-delete flag for trash feature
    private Instant deletedAt;        // when it was moved to trash

    // ── Builder ──────────────────────────────────────────────────────

    private FileMetadata(Builder builder) {
        this.fileId = builder.fileId;
        this.fileName = builder.fileName;
        this.filePath = builder.filePath;
        this.mimeType = builder.mimeType;
        this.sizeBytes = builder.sizeBytes;
        this.fileHash = builder.fileHash;
        this.ownerId = builder.ownerId;
        this.isDirectory = builder.isDirectory;
        this.parentFolderId = builder.parentFolderId;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.isDeleted = builder.isDeleted;
        this.deletedAt = builder.deletedAt;
    }

    /**
     * Extract file extension from fileName.
     * Returns empty string if no extension found (e.g., directories or extensionless files).
     */
    public String getExtension() {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getFileId() { return fileId; }
    public String getFileName() { return fileName; }
    public String getFilePath() { return filePath; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getFileHash() { return fileHash; }
    public String getOwnerId() { return ownerId; }
    public boolean isDirectory() { return isDirectory; }
    public String getParentFolderId() { return parentFolderId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isDeleted() { return isDeleted; }
    public Instant getDeletedAt() { return deletedAt; }

    // ── Setters for mutable state ────────────────────────────────────

    public void setFileName(String fileName) {
        this.fileName = fileName;
        this.updatedAt = Instant.now();
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
        this.updatedAt = Instant.now();
    }

    public void setParentFolderId(String parentFolderId) {
        this.parentFolderId = parentFolderId;
        this.updatedAt = Instant.now();
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
        this.updatedAt = Instant.now();
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
        this.updatedAt = Instant.now();
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setDeleted(boolean deleted) {
        this.isDeleted = deleted;
        if (deleted) {
            this.deletedAt = Instant.now();
        } else {
            this.deletedAt = null;
        }
    }

    @Override
    public String toString() {
        return String.format("FileMetadata{id='%s', name='%s', path='%s', size=%d, hash='%s', dir=%b, deleted=%b}",
                fileId, fileName, filePath, sizeBytes,
                fileHash != null ? fileHash.substring(0, Math.min(8, fileHash.length())) + "..." : "null",
                isDirectory, isDeleted);
    }

    // ── Builder class ────────────────────────────────────────────────

    public static class Builder {
        private String fileId;
        private String fileName;
        private String filePath;
        private String mimeType;
        private long sizeBytes;
        private String fileHash;
        private String ownerId;
        private boolean isDirectory = false;
        private String parentFolderId;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        private boolean isDeleted = false;
        private Instant deletedAt;

        public Builder(String fileId, String fileName) {
            this.fileId = fileId;
            this.fileName = fileName;
        }

        public Builder filePath(String filePath) { this.filePath = filePath; return this; }
        public Builder mimeType(String mimeType) { this.mimeType = mimeType; return this; }
        public Builder sizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; return this; }
        public Builder fileHash(String fileHash) { this.fileHash = fileHash; return this; }
        public Builder ownerId(String ownerId) { this.ownerId = ownerId; return this; }
        public Builder isDirectory(boolean isDirectory) { this.isDirectory = isDirectory; return this; }
        public Builder parentFolderId(String parentFolderId) { this.parentFolderId = parentFolderId; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder isDeleted(boolean isDeleted) { this.isDeleted = isDeleted; return this; }
        public Builder deletedAt(Instant deletedAt) { this.deletedAt = deletedAt; return this; }

        public FileMetadata build() {
            return new FileMetadata(this);
        }
    }
}
