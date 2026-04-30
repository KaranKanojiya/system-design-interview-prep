package com.systemdesign.filestorage.model;

/**
 * ConflictResolution — outcome of a sync conflict between local and remote versions.
 *
 * - KEEP_LOCAL: discard remote changes, keep what's on this device.
 * - KEEP_REMOTE: discard local changes, accept the server version.
 * - KEEP_BOTH: create a conflict copy so no data is lost.
 *
 * Real-world:
 * - Google Drive → KEEP_REMOTE (last-writer-wins, server always wins for web clients)
 * - Dropbox → KEEP_BOTH (creates "conflicted copy" files)
 */
public enum ConflictResolution {
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_BOTH
}
