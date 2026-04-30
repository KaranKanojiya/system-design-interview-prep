package com.systemdesign.filestorage.model;

/**
 * SyncEventType — types of file change events tracked for device synchronization.
 *
 * These events form a change log (similar to Dropbox's /list_folder/continue endpoint).
 * Clients poll for events since their last cursor to stay in sync.
 */
public enum SyncEventType {
    CREATED,
    MODIFIED,
    DELETED,
    MOVED,
    RENAMED
}
