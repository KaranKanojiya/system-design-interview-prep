package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.exception.FileNotFoundException;
import com.systemdesign.filestorage.exception.FileStorageException;
import com.systemdesign.filestorage.model.FileVersion;
import com.systemdesign.filestorage.repository.VersionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * VersionService — manages file version history, rollback, and diff.
 *
 * Every upload or edit creates a new version. Each version stores a list of chunk
 * hashes, NOT the actual data. The chunk data lives in the BlockStore. This means
 * versions that share chunks don't duplicate storage.
 *
 * Max versions: 30 per file. When exceeded, the oldest version is removed.
 * (Google Drive keeps 100 versions for 30 days; we keep 30 indefinitely for simplicity.)
 *
 * Rollback strategy:
 *   Rolling back to version N does NOT delete versions N+1..current.
 *   Instead, it creates a NEW version (current+1) with version N's chunk hashes.
 *   This preserves the full audit trail. (Same as git revert vs git reset.)
 *
 * Call chain:
 *   UploadService.uploadFile → this.createVersion(fileId, chunkHashes, ...)
 *   FileStorageService.rollbackVersion → this.rollbackToVersion(fileId, versionNumber)
 *   FileStorageService.getVersions → this.getVersions(fileId)
 */
public class VersionService {

    private static final int MAX_VERSIONS = 30;

    private final VersionRepository versionRepository;

    public VersionService(VersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    /**
     * Create a new version for a file.
     * Automatically assigns the next version number and trims old versions.
     */
    public FileVersion createVersion(String fileId, List<String> chunkHashes, long sizeBytes,
                                     String userId, String comment) {
        // Determine next version number
        List<FileVersion> existing = versionRepository.findByFileId(fileId);
        int nextVersion = existing.isEmpty() ? 1 :
                existing.get(existing.size() - 1).getVersionNumber() + 1;

        FileVersion version = new FileVersion(
                UUID.randomUUID().toString(),
                fileId,
                nextVersion,
                chunkHashes,
                sizeBytes,
                comment,
                Instant.now(),
                userId
        );

        versionRepository.save(version);

        // Trim: if we exceed MAX_VERSIONS, remove the oldest
        trimVersions(fileId);

        return version;
    }

    /** Get all versions for a file, ordered by version number. */
    public List<FileVersion> getVersions(String fileId) {
        return versionRepository.findByFileId(fileId);
    }

    /** Get the latest version of a file. */
    public FileVersion getLatestVersion(String fileId) {
        return versionRepository.findLatestVersion(fileId)
                .orElseThrow(() -> new FileNotFoundException("No versions found for file: " + fileId));
    }

    /**
     * Rollback to a previous version.
     *
     * Creates a NEW version with the old version's chunk hashes — does NOT delete
     * intermediate versions. This preserves the full history.
     *
     * Example: versions [1, 2, 3, 4, 5], rollback to 2 → creates version 6 with v2's chunks.
     *          Result: [1, 2, 3, 4, 5, 6] where v6 has same content as v2.
     */
    public FileVersion rollbackToVersion(String fileId, int versionNumber) {
        FileVersion targetVersion = versionRepository.findByFileIdAndVersion(fileId, versionNumber)
                .orElseThrow(() -> new FileStorageException(
                        "Version " + versionNumber + " not found for file " + fileId));

        // Create a new version that has the same chunk hashes as the target version
        return createVersion(fileId, targetVersion.getChunkHashes(), targetVersion.getSizeBytes(),
                targetVersion.getCreatedBy(),
                "Rollback to version " + versionNumber);
    }

    /**
     * Show which chunks changed between two versions.
     * Returns a human-readable diff summary.
     */
    public String getVersionDiff(String fileId, int v1, int v2) {
        FileVersion version1 = versionRepository.findByFileIdAndVersion(fileId, v1)
                .orElseThrow(() -> new FileStorageException("Version " + v1 + " not found"));
        FileVersion version2 = versionRepository.findByFileIdAndVersion(fileId, v2)
                .orElseThrow(() -> new FileStorageException("Version " + v2 + " not found"));

        List<String> hashes1 = version1.getChunkHashes();
        List<String> hashes2 = version2.getChunkHashes();

        // Find chunks that are in v2 but not in v1 (added/changed)
        Set<String> set1 = new HashSet<>(hashes1);
        Set<String> set2 = new HashSet<>(hashes2);

        Set<String> added = new HashSet<>(set2);
        added.removeAll(set1);

        Set<String> removed = new HashSet<>(set1);
        removed.removeAll(set2);

        Set<String> unchanged = new HashSet<>(set1);
        unchanged.retainAll(set2);

        StringBuilder diff = new StringBuilder();
        diff.append(String.format("Version Diff: v%d → v%d\n", v1, v2));
        diff.append(String.format("  Chunks in v%d: %d\n", v1, hashes1.size()));
        diff.append(String.format("  Chunks in v%d: %d\n", v2, hashes2.size()));
        diff.append(String.format("  Unchanged chunks: %d\n", unchanged.size()));
        diff.append(String.format("  New/changed chunks: %d\n", added.size()));
        diff.append(String.format("  Removed chunks: %d\n", removed.size()));

        return diff.toString();
    }

    /**
     * Trim versions to MAX_VERSIONS, removing the oldest.
     */
    private void trimVersions(String fileId) {
        List<FileVersion> versions = versionRepository.findByFileId(fileId);
        while (versions.size() > MAX_VERSIONS) {
            FileVersion oldest = versions.get(0);
            versionRepository.delete(oldest.getVersionId());
            versions.remove(0);
        }
    }
}
