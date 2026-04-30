package com.systemdesign.collaboration.exception;

/**
 * Thrown when an operation conflict cannot be resolved.
 *
 * In practice this is rare with OT (the whole point is to resolve conflicts),
 * but can happen if the operation references an impossible state (e.g.,
 * base version is from the future).
 */
public class ConflictException extends CollaborationException {

    public ConflictException(String message) {
        super(message);
    }
}
