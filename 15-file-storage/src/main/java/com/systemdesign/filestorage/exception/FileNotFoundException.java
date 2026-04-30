package com.systemdesign.filestorage.exception;

/**
 * FileNotFoundException — thrown when a requested file does not exist.
 *
 * Custom exception (not java.io.FileNotFoundException) because:
 * 1. Our "files" are virtual metadata entries, not OS files.
 * 2. We want it to extend our FileStorageException hierarchy.
 */
public class FileNotFoundException extends FileStorageException {

    public FileNotFoundException(String fileId) {
        super("File not found: " + fileId);
    }

    public FileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
