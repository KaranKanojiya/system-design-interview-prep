package com.systemdesign.videostreaming.exception;

/**
 * Thrown when a video upload operation fails.
 *
 * Scenarios: chunk checksum mismatch, storage write failure, upload timeout.
 * In production: maps to HTTP 500 with a retry-after header.
 */
public class UploadException extends VideoStreamingException {

    public UploadException(String message) {
        super(message);
    }

    public UploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
