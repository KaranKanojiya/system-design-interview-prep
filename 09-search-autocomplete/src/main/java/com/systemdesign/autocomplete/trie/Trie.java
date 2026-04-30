package com.systemdesign.autocomplete.trie;

import com.systemdesign.autocomplete.model.Suggestion;

import java.util.List;

/**
 * Trie — Interface for all Trie implementations.
 *
 * WHY an interface?
 * -----------------
 * Interview Insight: Program to an interface, not an implementation.
 * This lets us swap StandardTrie, CompressedTrie, or TopKTrie without changing
 * any consumer code (AutocompleteService, TrieService, etc.).
 *
 * Strategy Pattern at the data structure level:
 *   - StandardTrie: simple, easy to implement, O(L+N) getSuggestions
 *   - CompressedTrie: memory-efficient for sparse tries
 *   - TopKTrie: O(1) getSuggestions, the interview-winning optimization
 *
 * Wiring:
 *   TrieService holds a Trie (this interface).
 *   AppConfig decides which implementation to create.
 *   TrieBuilderService builds a Trie from query data.
 *
 * Complexity Summary:
 *   Operation       | StandardTrie  | CompressedTrie | TopKTrie
 *   ================|===============|================|=============
 *   insert(w)       | O(L)          | O(L)           | O(L * K)
 *   search(w)       | O(L)          | O(L)           | O(L)
 *   getSuggestions   | O(L + N)      | O(L + N)       | O(L) → O(1)*
 *   delete(w)       | O(L)          | O(L)           | O(L * K)
 *   startsWith(p)   | O(P)          | O(P)           | O(P)
 *
 *   L = word length, N = total words under prefix, K = top-K per node, P = prefix length
 *   *O(1) at the prefix node, O(L) to reach the prefix node
 */
public interface Trie {

    /**
     * Insert a word with its frequency into the trie.
     * If the word already exists, update its frequency.
     *
     * @param word      the word to insert (case-insensitive, will be lowercased)
     * @param frequency the search frequency for this word
     */
    void insert(String word, long frequency);

    /**
     * Check if an exact word exists in the trie.
     *
     * @param word the word to search for
     * @return true if the word exists as a complete word (not just a prefix)
     */
    boolean search(String word);

    /**
     * Get autocomplete suggestions for a given prefix.
     * Returns suggestions sorted by relevance (score descending).
     *
     * @param prefix     the prefix to autocomplete
     * @param maxResults maximum number of suggestions to return
     * @return sorted list of suggestions, or empty list if no matches
     */
    List<Suggestion> getSuggestions(String prefix, int maxResults);

    /**
     * Delete a word from the trie.
     *
     * @param word the word to delete
     * @return true if the word was found and deleted, false if it didn't exist
     */
    boolean delete(String word);

    /**
     * Check if any word in the trie starts with the given prefix.
     *
     * @param prefix the prefix to check
     * @return true if at least one word starts with this prefix
     */
    boolean startsWith(String prefix);

    /**
     * Get the total number of words stored in the trie.
     *
     * @return number of distinct words
     */
    int size();

    /**
     * Get the frequency of a specific word.
     *
     * @param word the word to look up
     * @return the frequency, or 0 if the word doesn't exist
     */
    long getFrequency(String word);

    /**
     * Remove all words from the trie.
     */
    void clear();
}
