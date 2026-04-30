package com.systemdesign.filestorage.display;

import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.FileVersion;
import com.systemdesign.filestorage.model.User;
import com.systemdesign.filestorage.repository.UserRepository;
import com.systemdesign.filestorage.repository.VersionRepository;
import com.systemdesign.filestorage.service.DeduplicationService;
import com.systemdesign.filestorage.service.MetadataService;
import com.systemdesign.filestorage.service.SharingService;

import java.util.List;

/**
 * StorageStatsDisplay — prints comprehensive storage system statistics.
 *
 * Displays:
 * - Total files and total storage used
 * - Deduplication savings (bytes saved, percentage)
 * - Average file size
 * - Total version count across all files
 * - Number of active share links
 * - Per-user quota usage
 *
 * Call chain:
 *   FileStorageApp → this.printStats() at the end of all demos
 */
public class StorageStatsDisplay {

    private final MetadataService metadataService;
    private final DeduplicationService deduplicationService;
    private final VersionRepository versionRepository;
    private final SharingService sharingService;
    private final UserRepository userRepository;

    public StorageStatsDisplay(MetadataService metadataService,
                               DeduplicationService deduplicationService,
                               VersionRepository versionRepository,
                               SharingService sharingService,
                               UserRepository userRepository) {
        this.metadataService = metadataService;
        this.deduplicationService = deduplicationService;
        this.versionRepository = versionRepository;
        this.sharingService = sharingService;
        this.userRepository = userRepository;
    }

    public void printStats() {
        String separator = "=".repeat(70);
        System.out.println(separator);
        System.out.println("                    STORAGE SYSTEM STATISTICS");
        System.out.println(separator);

        // Total files
        List<FileMetadata> allFiles = metadataService.getAllFiles();
        long activeFiles = allFiles.stream().filter(f -> !f.isDeleted()).count();
        long trashedFiles = allFiles.stream().filter(FileMetadata::isDeleted).count();
        System.out.printf("  Total files:            %d (active: %d, trashed: %d)%n",
                allFiles.size(), activeFiles, trashedFiles);

        // Total logical storage (sum of all file sizes)
        long totalLogicalBytes = allFiles.stream().mapToLong(FileMetadata::getSizeBytes).sum();
        System.out.printf("  Total logical storage:  %s%n", formatBytes(totalLogicalBytes));

        // Actual storage (in block store, after dedup)
        long actualStoredBytes = deduplicationService.getTotalStoredBytes();
        System.out.printf("  Actual stored (dedup):  %s%n", formatBytes(actualStoredBytes));

        // Dedup savings
        long savedBytes = deduplicationService.getSavedBytes();
        int dupCount = deduplicationService.getDuplicateCount();
        double savingsPercent = totalLogicalBytes > 0 ?
                (double) savedBytes / (totalLogicalBytes + savedBytes) * 100.0 : 0;
        System.out.printf("  Dedup savings:          %s (%d duplicate chunks, %.1f%% saved)%n",
                formatBytes(savedBytes), dupCount, savingsPercent);

        // Unique blocks
        System.out.printf("  Unique blocks:          %d%n", deduplicationService.getBlockCount());

        // Average file size
        double avgSize = allFiles.isEmpty() ? 0 : (double) totalLogicalBytes / allFiles.size();
        System.out.printf("  Avg file size:          %s%n", formatBytes((long) avgSize));

        // Version count
        List<FileVersion> allVersions = versionRepository.findAll();
        System.out.printf("  Total versions:         %d%n", allVersions.size());

        // Share link count
        int shareLinkCount = sharingService.getAllShareLinks().size();
        System.out.printf("  Active share links:     %d%n", shareLinkCount);

        // Per-user quota
        System.out.println();
        System.out.println("  Per-User Quota Usage:");
        for (User user : userRepository.findAll()) {
            System.out.printf("    %-15s  %s / %s  (%.1f%%)  [%d files]%n",
                    user.getName(),
                    formatBytes(user.getStorageQuota().getUsedBytes()),
                    formatBytes(user.getStorageQuota().getTotalBytes()),
                    user.getStorageQuota().getUsedPercent(),
                    user.getStorageQuota().getFileCount());
        }

        System.out.println(separator);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
