package com.systemdesign.filestorage.exception;

/**
 * StorageQuotaExceededException — thrown when an upload would exceed the user's quota.
 *
 * Real-world: Google Drive shows "Storage is full" and blocks uploads.
 * We throw this before storing any chunks so the operation is atomic — either
 * the entire upload succeeds or nothing is stored.
 */
public class StorageQuotaExceededException extends FileStorageException {

    public StorageQuotaExceededException(String userId, long requested, long remaining) {
        super(String.format("Storage quota exceeded for user '%s': requested %d bytes, only %d bytes remaining",
                userId, requested, remaining));
    }
}
