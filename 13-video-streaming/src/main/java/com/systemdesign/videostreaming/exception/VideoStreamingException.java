package com.systemdesign.videostreaming.exception;

/**
 * Base exception for all video streaming platform errors.
 *
 * Why a custom exception hierarchy?
 *   - Distinguishes platform errors from JDK exceptions
 *   - Allows catch blocks to handle platform errors uniformly
 *   - In production: maps to specific HTTP status codes in the controller layer
 */
public class VideoStreamingException extends RuntimeException {

    public VideoStreamingException(String message) {
        super(message);
    }

    public VideoStreamingException(String message, Throwable cause) {
        super(message, cause);
    }
}
