package com.systemdesign.filestorage.exception;

/**
 * PermissionDeniedException — thrown when a user lacks permission for an operation.
 *
 * Examples:
 * - Trying to delete a file you don't own.
 * - Accessing a share link with wrong password.
 * - Trying to edit a file through a VIEW-only share link.
 */
public class PermissionDeniedException extends FileStorageException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
