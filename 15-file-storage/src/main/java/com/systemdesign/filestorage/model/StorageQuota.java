package com.systemdesign.filestorage.model;

/**
 * StorageQuota — tracks storage usage per user with thread-safe mutations.
 *
 * Design decisions:
 * - synchronized methods: multiple uploads/deletes may happen concurrently.
 *   In a real system this would be an atomic DB operation, but for in-memory
 *   simulation we use Java's intrinsic locks.
 * - fileCount tracks number of files (not chunks) — used for display stats.
 * - totalBytes is the user's storage limit (e.g., 15GB for Google Drive free tier).
 *
 * Call chain:
 *   UploadService.uploadFile → quota.canStore(size) → if true, quota.addUsage(size)
 *   TrashService.permanentDelete → quota.removeUsage(size)
 */
public class StorageQuota {

    private final String userId;
    private long usedBytes;
    private final long totalBytes;
    private int fileCount;

    public StorageQuota(String userId, long totalBytes) {
        this.userId = userId;
        this.usedBytes = 0;
        this.totalBytes = totalBytes;
        this.fileCount = 0;
    }

    /** Percentage of quota used (0.0 to 100.0). */
    public synchronized double getUsedPercent() {
        if (totalBytes == 0) return 100.0;
        return (double) usedBytes / totalBytes * 100.0;
    }

    /** How many bytes remain before hitting the quota limit. */
    public synchronized long getRemainingBytes() {
        return Math.max(0, totalBytes - usedBytes);
    }

    /** Check if there's enough room to store additional bytes. */
    public synchronized boolean canStore(long sizeBytes) {
        return (usedBytes + sizeBytes) <= totalBytes;
    }

    /** Add usage after a successful upload. */
    public synchronized void addUsage(long bytes) {
        this.usedBytes += bytes;
        this.fileCount++;
    }

    /** Remove usage after a permanent delete. */
    public synchronized void removeUsage(long bytes) {
        this.usedBytes = Math.max(0, this.usedBytes - bytes);
        this.fileCount = Math.max(0, this.fileCount - 1);
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getUserId() { return userId; }
    public synchronized long getUsedBytes() { return usedBytes; }
    public long getTotalBytes() { return totalBytes; }
    public synchronized int getFileCount() { return fileCount; }

    @Override
    public String toString() {
        return String.format("StorageQuota{user='%s', used=%d/%d bytes (%.1f%%), files=%d}",
                userId, usedBytes, totalBytes, getUsedPercent(), fileCount);
    }
}
