package com.systemdesign.newsfeed.exception;

/**
 * FeedException — Base exception for news feed system errors.
 */
public class FeedException extends RuntimeException {

    public FeedException(String message) {
        super(message);
    }

    public FeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
