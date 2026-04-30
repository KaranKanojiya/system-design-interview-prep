package com.systemdesign.filestorage.strategy.sync;

import com.systemdesign.filestorage.model.ConflictResolution;
import com.systemdesign.filestorage.model.FileMetadata;

/**
 * ConflictStrategy — Strategy Pattern for resolving sync conflicts.
 *
 * A conflict occurs when:
 *   Device A modifies file X offline, and Device B also modifies file X offline.
 *   When both sync, the server sees two divergent versions.
 *
 * Implementations:
 * - LastWriterWinsStrategy: most recent timestamp wins. Simple, but data may be lost.
 * - KeepBothStrategy: create a conflict copy. No data loss, but clutters the folder.
 *
 * Call chain:
 *   SyncService.resolveConflict(localFile, remoteFile) → strategy.resolve(local, remote)
 */
public interface ConflictStrategy {

    /**
     * Resolve a conflict between two versions of the same file.
     *
     * @param localFile   the version on the local device
     * @param remoteFile  the version on the server / other device
     * @return resolution decision (KEEP_LOCAL, KEEP_REMOTE, or KEEP_BOTH)
     */
    ConflictResolution resolve(FileMetadata localFile, FileMetadata remoteFile);
}
