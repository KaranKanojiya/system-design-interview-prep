package com.systemdesign.cache.exception;

/**
 * KeyNotFoundException — Thrown when an operation requires an existing key that isn't found.
 *
 * Note: CacheService.get() returns null on miss (not an exception), because misses are normal.
 * This exception is for cases where a key MUST exist (e.g., update operations).
 */
public class KeyNotFoundException extends CacheException {

    public KeyNotFoundException(String key) {
        super("Key not found in cache: '" + key + "'");
    }
}
