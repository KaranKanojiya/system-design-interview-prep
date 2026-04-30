package com.systemdesign.filestorage.exception;

/**
 * FileStorageException — base exception for all file storage errors.
 * All other custom exceptions in this package extend this.
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
