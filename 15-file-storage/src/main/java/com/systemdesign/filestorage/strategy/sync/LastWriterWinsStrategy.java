package com.systemdesign.filestorage.strategy.sync;

import com.systemdesign.filestorage.model.ConflictResolution;
import com.systemdesign.filestorage.model.FileMetadata;

/**
 * LastWriterWinsStrategy — the most recently updated file wins.
 *
 * Simple, data may be lost. Google Drive uses this.
 *
 * How it works:
 *   Compare updatedAt timestamps of local and remote versions.
 *   Whichever was modified more recently is kept; the other is discarded.
 *
 * Pros:
 * - Simple, deterministic, no user intervention needed.
 * - Clean — no conflict copies cluttering the folder.
 *
 * Cons:
 * - The "losing" version's changes are silently discarded.
 * - If two users edit different parts of a document, the slower user loses ALL their changes.
 *
 * Call chain:
 *   SyncService.resolveConflict → this.resolve(local, remote) → KEEP_LOCAL or KEEP_REMOTE
 */
public class LastWriterWinsStrategy implements ConflictStrategy {

    @Override
    public ConflictResolution resolve(FileMetadata localFile, FileMetadata remoteFile) {
        if (localFile.getUpdatedAt() == null && remoteFile.getUpdatedAt() == null) {
            return ConflictResolution.KEEP_REMOTE;  // tie-break: server wins
        }
        if (localFile.getUpdatedAt() == null) {
            return ConflictResolution.KEEP_REMOTE;
        }
        if (remoteFile.getUpdatedAt() == null) {
            return ConflictResolution.KEEP_LOCAL;
        }

        // Compare timestamps: most recent wins
        if (localFile.getUpdatedAt().isAfter(remoteFile.getUpdatedAt())) {
            return ConflictResolution.KEEP_LOCAL;
        } else {
            // If timestamps are equal, server (remote) wins as tie-breaker
            return ConflictResolution.KEEP_REMOTE;
        }
    }
}
