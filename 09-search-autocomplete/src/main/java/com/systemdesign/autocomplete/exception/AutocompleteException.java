package com.systemdesign.autocomplete.exception;

/**
 * AutocompleteException — Base exception for all autocomplete system errors.
 *
 * WHY a custom exception hierarchy?
 * ----------------------------------
 * Generic exceptions (RuntimeException, IllegalStateException) lose context.
 * Custom exceptions let callers catch specific error types:
 *
 *   try {
 *       trieService.insertQuery(word, freq);
 *   } catch (TrieCapacityException e) {
 *       // Handle specifically: maybe trigger a trie rebuild or alert ops
 *   } catch (AutocompleteException e) {
 *       // Handle generically: log and return empty results
 *   }
 *
 * Extends RuntimeException (unchecked) because:
 *   - Autocomplete errors are typically not recoverable by the caller
 *   - Forces every method in the chain to declare "throws" is noisy
 *   - Consistent with modern Java style (Spring, etc.)
 */
public class AutocompleteException extends RuntimeException {

    public AutocompleteException(String message) {
        super(message);
    }

    public AutocompleteException(String message, Throwable cause) {
        super(message, cause);
    }
}
