package com.systemdesign.cache.exception;

/**
 * CacheFullException — Thrown when the cache is at maximum capacity and no key could be evicted.
 *
 * This should be rare. Normally, eviction always succeeds (there's always a key to remove).
 * This exception indicates a bug in the eviction strategy or a misconfiguration.
 */
public class CacheFullException extends CacheException {

    public CacheFullException(String message) {
        super(message);
    }
}
