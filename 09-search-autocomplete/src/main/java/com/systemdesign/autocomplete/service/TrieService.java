package com.systemdesign.autocomplete.service;

import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.trie.Trie;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * TrieService — Manages all Trie operations with thread safety.
 *
 * WHY a service layer over Trie?
 * ------------------------------
 * The raw Trie implementations are NOT thread-safe. In a real autocomplete system:
 *   - Hundreds of concurrent READERS (users typing queries)
 *   - Occasional WRITERS (inserting new queries, rebuilding trie)
 *
 * ReadWriteLock provides the optimal concurrency model:
 *   - Multiple readers can access the trie simultaneously (no contention)
 *   - Writers get exclusive access (blocks all readers until done)
 *   - This is perfect for read-heavy workloads like autocomplete
 *
 * Without TrieService, the Trie would need to be thread-safe internally,
 * which mixes concerns (data structure shouldn't manage its own threading).
 *
 * Wiring:
 *   AppConfig → creates TrieService(trie) → injects into AutocompleteService
 *   AutocompleteService.getSuggestions() → TrieService.getSuggestions() [READ lock]
 *   DataCollectionService.recordQuery() → TrieService.insertQuery() [WRITE lock]
 *   TrieBuilderService.rebuildTrie() → TrieService.rebuildTrie() [WRITE lock]
 */
public class TrieService {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** The underlying trie (StandardTrie, CompressedTrie, or TopKTrie). */
    private Trie trie;

    /**
     * ReadWriteLock for concurrent access control.
     *
     * WHY ReadWriteLock over synchronized?
     *   synchronized: only one thread at a time (even for reads). Bottleneck.
     *   ReadWriteLock: multiple concurrent reads, exclusive writes. Perfect for autocomplete.
     *
     * WHY ReentrantReadWriteLock?
     *   Reentrant = same thread can acquire the lock multiple times without deadlock.
     *   Useful when internal methods call other locked methods.
     */
    private final ReadWriteLock lock;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param trie the Trie implementation to manage (injected by AppConfig)
     */
    public TrieService(Trie trie) {
        this.trie = trie;
        this.lock = new ReentrantReadWriteLock();
    }

    // -----------------------------------------------------------------------
    // READ operations (acquire read lock)
    // -----------------------------------------------------------------------

    /**
     * Get autocomplete suggestions for a prefix.
     * Multiple threads can call this simultaneously (read lock allows concurrency).
     *
     * @param prefix     the prefix to autocomplete
     * @param maxResults max suggestions to return
     * @return list of suggestions sorted by score
     */
    public List<Suggestion> getSuggestions(String prefix, int maxResults) {
        lock.readLock().lock();
        try {
            return trie.getSuggestions(prefix, maxResults);
        } finally {
            // ALWAYS release in finally — even if an exception occurs
            lock.readLock().unlock();
        }
    }

    /**
     * Check if a word exists in the trie.
     */
    public boolean search(String word) {
        lock.readLock().lock();
        try {
            return trie.search(word);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if any word starts with the prefix.
     */
    public boolean startsWith(String prefix) {
        lock.readLock().lock();
        try {
            return trie.startsWith(prefix);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the trie size (number of words).
     */
    public int size() {
        lock.readLock().lock();
        try {
            return trie.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the frequency of a word.
     */
    public long getFrequency(String word) {
        lock.readLock().lock();
        try {
            return trie.getFrequency(word);
        } finally {
            lock.readLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // WRITE operations (acquire write lock)
    // -----------------------------------------------------------------------

    /**
     * Insert a query into the trie.
     * Acquires WRITE lock — blocks all readers and other writers.
     *
     * @param word      the word to insert
     * @param frequency the frequency to assign/add
     */
    public void insertQuery(String word, long frequency) {
        lock.writeLock().lock();
        try {
            trie.insert(word, frequency);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Delete a query from the trie.
     */
    public boolean deleteQuery(String word) {
        lock.writeLock().lock();
        try {
            return trie.delete(word);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Replace the current trie with a newly built one.
     *
     * This is the atomic swap for trie rebuild:
     *   1. TrieBuilderService builds a new trie (offline, no lock needed)
     *   2. TrieService.rebuildTrie(newTrie) — acquires write lock, swaps reference
     *   3. Old trie becomes garbage (GC will collect it)
     *
     * WHY atomic swap?
     *   - During rebuild, the old trie continues serving queries (no downtime)
     *   - The swap is a single reference assignment (effectively O(1))
     *   - This is the same pattern used in production systems (double buffering)
     *
     * @param newTrie the freshly built trie to replace the current one
     */
    public void rebuildTrie(Trie newTrie) {
        lock.writeLock().lock();
        try {
            this.trie = newTrie;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the underlying trie reference (for diagnostics/display).
     * Use with caution — returned trie is not thread-safe without the lock.
     */
    public Trie getTrie() {
        return trie;
    }
}
