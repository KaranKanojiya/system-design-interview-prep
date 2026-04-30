package com.systemdesign.cache.exception;

/**
 * CacheException — Base exception for all cache-related errors.
 *
 * WHY a custom exception hierarchy?
 *   - Callers can catch CacheException to handle ALL cache errors uniformly
 *   - Or catch specific subclasses (CacheFullException, KeyNotFoundException) for fine-grained handling
 *   - In an interview, this shows you think about error handling, not just the happy path
 *
 * Exception hierarchy:
 *   CacheException (base)
 *     ├── CacheFullException       — cache is at max capacity and eviction failed
 *     ├── KeyNotFoundException     — requested key doesn't exist
 *     └── NodeUnavailableException — target node is down (distributed mode)
 */
public class CacheException extends RuntimeException {

    public CacheException(String message) {
        super(message);
    }

    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
