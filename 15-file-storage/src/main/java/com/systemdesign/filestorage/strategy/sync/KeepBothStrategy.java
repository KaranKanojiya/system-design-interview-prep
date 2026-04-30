package com.systemdesign.filestorage.strategy.sync;

import com.systemdesign.filestorage.model.ConflictResolution;
import com.systemdesign.filestorage.model.FileMetadata;

/**
 * KeepBothStrategy — always keeps both versions, creating a conflict copy.
 *
 * No data loss. Dropbox uses this.
 *
 * How it works:
 *   Always returns KEEP_BOTH. The caller (SyncService) then renames the conflicting
 *   file with a "(conflict copy - username - date)" suffix.
 *
 * Example:
 *   Original: "report.pdf"
 *   Conflict copy: "report (conflict copy - alice - 2024-01-15).pdf"
 *
 * Pros:
 * - Zero data loss — both versions are preserved.
 * - User can manually merge or choose which version to keep.
 *
 * Cons:
 * - Clutters the folder with conflict copies.
 * - User must manually resolve — automated merge is not attempted.
 *
 * Call chain:
 *   SyncService.resolveConflict → this.resolve(local, remote) → always KEEP_BOTH
 */
public class KeepBothStrategy implements ConflictStrategy {

    @Override
    public ConflictResolution resolve(FileMetadata localFile, FileMetadata remoteFile) {
        // Always keep both — no data loss, user resolves manually
        return ConflictResolution.KEEP_BOTH;
    }
}
