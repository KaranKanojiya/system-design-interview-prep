package com.systemdesign.autocomplete.exception;

/**
 * TrieCapacityException — Thrown when the trie exceeds its configured capacity.
 *
 * WHY a specific exception?
 * -------------------------
 * In production, tries can grow unbounded if not controlled:
 *   - Malicious users could insert millions of unique queries
 *   - Spelling variants create exponential growth
 *   - Without a cap, the trie consumes all available memory → OOM
 *
 * TrieCapacityException signals that the system has reached its limit and
 * the caller should take corrective action:
 *   - Trigger a trie rebuild with only top-K queries
 *   - Alert operations team
 *   - Reject the insert (graceful degradation)
 *
 * Example usage:
 *   if (trie.size() >= MAX_TRIE_SIZE) {
 *       throw new TrieCapacityException(
 *           "Trie has reached maximum capacity of " + MAX_TRIE_SIZE + " words"
 *       );
 *   }
 */
public class TrieCapacityException extends AutocompleteException {

    private final int currentSize;
    private final int maxCapacity;

    public TrieCapacityException(String message, int currentSize, int maxCapacity) {
        super(message);
        this.currentSize = currentSize;
        this.maxCapacity = maxCapacity;
    }

    public TrieCapacityException(int currentSize, int maxCapacity) {
        super(String.format("Trie capacity exceeded: current=%d, max=%d", currentSize, maxCapacity));
        this.currentSize = currentSize;
        this.maxCapacity = maxCapacity;
    }

    public int getCurrentSize() {
        return currentSize;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }
}
