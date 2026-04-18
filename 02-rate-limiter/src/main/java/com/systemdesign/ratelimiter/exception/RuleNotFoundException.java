package com.systemdesign.ratelimiter.exception;

/**
 * Thrown when a requested rate limit rule does not exist.
 */
public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(String message) {
        super(message);
    }
}
