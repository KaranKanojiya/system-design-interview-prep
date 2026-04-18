package com.systemdesign.urlshortener.strategy;

/**
 * Strategy interface for URL encoding/short code generation.
 * Demonstrates the Strategy Pattern — allows swapping algorithms at runtime.
 */
public interface EncodingStrategy {

    /** Generate a short code from the given input. */
    String encode(String input);

    /** Human-readable name of this strategy. */
    String name();
}
