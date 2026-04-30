package com.systemdesign.collaboration.exception;

/**
 * Base exception for all collaboration-related errors.
 * Specific sub-types provide more precise error classification.
 */
public class CollaborationException extends RuntimeException {

    public CollaborationException(String message) {
        super(message);
    }

    public CollaborationException(String message, Throwable cause) {
        super(message, cause);
    }
}
