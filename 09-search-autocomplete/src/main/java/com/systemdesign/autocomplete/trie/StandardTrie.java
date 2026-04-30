package com.systemdesign.autocomplete.trie;

import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.model.TrieNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * StandardTrie — Basic trie implementation, the foundation for autocomplete.
 *
 * HOW IT WORKS:
 * -------------
 * A trie (prefix tree) stores words character by character. Each path from root
 * to a terminal node represents a word.
 *
 * Example: inserting "app", "apple", "apt", "bat"
 *
 *          (root)
 *         /      \
 *        a        b
 *        |        |
 *        p        a
 *       / \       |
 *      p   t*     t*
 *      |
 *      l
 *      |
 *      e*
 *
 *   * = isEndOfWord (terminal node)
 *
 * TIME COMPLEXITY:
 *   insert(word)           → O(L) where L = word length
 *     Walk char by char, create nodes as needed. Each step = O(1) HashMap lookup.
 *
 *   search(word)           → O(L)
 *     Walk char by char, check isEndOfWord at the end.
 *
 *   getSuggestions(prefix)  → O(P + N) where P = prefix length, N = words under prefix
 *     Step 1: Walk to prefix node → O(P)
 *     Step 2: DFS to collect all words under that node → O(N)
 *     Step 3: Sort by frequency → O(N log N)
 *     This is the BOTTLENECK that TopKTrie solves.
 *
 *   delete(word)           → O(L)
 *     Walk to terminal, unmark isEndOfWord. Optionally prune empty branches.
 *
 * SPACE COMPLEXITY: O(ALPHABET_SIZE * L * W) where W = number of words
 *   In practice, much less due to shared prefixes.
 *
 * Wiring:
 *   AppConfig → creates StandardTrie → injects into TrieService
 *   TrieService.getSuggestions() → StandardTrie.getSuggestions()
 *   TrieBuilderService.buildTrie() → StandardTrie.insert() for each query
 */
public class StandardTrie implements Trie {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Root node of the trie. Has no character — it's the entry point. */
    protected final TrieNode root;

    /** Total number of distinct words in the trie. */
    protected int wordCount;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public StandardTrie() {
        this.root = new TrieNode();
        this.wordCount = 0;
    }

    // -----------------------------------------------------------------------
    // insert(word, frequency)
    // -----------------------------------------------------------------------

    /**
     * Insert a word into the trie with the given frequency.
     *
     * Algorithm:
     *   1. Normalize: lowercase, trim
     *   2. Walk char by char from root
     *   3. At each char: if child exists → follow it; else → create new child
     *   4. At the final node: mark as terminal, store the word and frequency
     *
     * If the word already exists, ADD the new frequency to the existing one.
     * This handles the case where the same word is inserted from multiple data sources.
     *
     * Time: O(L) where L = word.length()
     * Space: O(L) in the worst case (all new nodes), O(1) if prefix already exists
     */
    @Override
    public void insert(String word, long frequency) {
        // Edge case: null or empty word
        if (word == null || word.isBlank()) {
            return;
        }

        String normalized = word.toLowerCase().trim();
        TrieNode current = root;

        // Walk char by char, creating nodes as needed
        // Example: inserting "apple"
        //   'a' → create/follow → 'p' → create/follow → 'p' → ... → 'e'
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);

            if (!current.hasChild(c)) {
                // This character doesn't exist yet — create a new branch
                current.addChild(c);
            }
            // Follow the child node
            current = current.getChild(c);
        }

        // Mark the final node as a terminal (end of word)
        if (!current.isEndOfWord()) {
            // New word — increment count
            current.setEndOfWord(true);
            current.setWord(normalized);
            current.setFrequency(frequency);
            wordCount++;
        } else {
            // Word already exists — accumulate frequency
            // WHY accumulate? If "apple" is inserted with freq 100 from source A
            // and freq 50 from source B, total should be 150.
            current.incrementFrequency(frequency);
        }
    }

    // -----------------------------------------------------------------------
    // search(word)
    // -----------------------------------------------------------------------

    /**
     * Check if a word exists in the trie as a complete word.
     *
     * Algorithm:
     *   1. Walk char by char from root
     *   2. If any char is missing → word doesn't exist
     *   3. If we reach the end → check isEndOfWord
     *
     * Note: "app" returns true only if "app" was explicitly inserted.
     * If only "apple" was inserted, search("app") returns false
     * (but startsWith("app") returns true).
     *
     * Time: O(L)
     */
    @Override
    public boolean search(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }

        TrieNode node = findNode(word.toLowerCase().trim());
        // Node must exist AND be marked as end of word
        return node != null && node.isEndOfWord();
    }

    // -----------------------------------------------------------------------
    // getSuggestions(prefix, maxResults)
    // -----------------------------------------------------------------------

    /**
     * Get autocomplete suggestions for a prefix.
     *
     * THIS IS THE CORE AUTOCOMPLETE OPERATION.
     *
     * Algorithm:
     *   Step 1: Find the prefix node — walk to the end of the prefix
     *   Step 2: DFS from prefix node — collect ALL terminal nodes below it
     *   Step 3: Sort by frequency (descending)
     *   Step 4: Return top maxResults
     *
     * Example: prefix = "app", trie contains "app"(100), "apple"(200), "application"(50)
     *   Step 1: Walk a→p→p → found prefix node
     *   Step 2: DFS finds: "app"(100), "apple"(200), "application"(50)
     *   Step 3: Sort: "apple"(200), "app"(100), "application"(50)
     *   Step 4: Return top 2 (if maxResults=2): ["apple", "app"]
     *
     * Time: O(P + N + N*log(N)) where P = prefix length, N = words under prefix
     *   - O(P) to find prefix node
     *   - O(N) for DFS
     *   - O(N log N) for sort
     *   In the worst case (prefix = ""), N = ALL words in the trie.
     *   This is why TopKTrie is needed for production systems.
     *
     * Edge cases:
     *   - Empty prefix → return top-K across entire trie (expensive!)
     *   - Prefix not found → return empty list
     *   - No words under prefix → return empty list
     */
    @Override
    public List<Suggestion> getSuggestions(String prefix, int maxResults) {
        // Edge case: empty or null prefix
        if (prefix == null || prefix.isBlank()) {
            // Return top words across the entire trie
            // In production, you'd probably reject this or use a cached "popular" list
            List<Suggestion> allSuggestions = new ArrayList<>();
            collectAllWords(root, allSuggestions);
            Collections.sort(allSuggestions); // Uses Comparable (score desc)
            return allSuggestions.subList(0, Math.min(maxResults, allSuggestions.size()));
        }

        String normalizedPrefix = prefix.toLowerCase().trim();

        // Step 1: Find the prefix node
        TrieNode prefixNode = findNode(normalizedPrefix);
        if (prefixNode == null) {
            // Prefix doesn't exist in the trie — no suggestions
            return Collections.emptyList();
        }

        // Step 2: DFS to collect all words under the prefix node
        List<Suggestion> suggestions = new ArrayList<>();
        collectAllWords(prefixNode, suggestions);

        // Step 3: Sort by score (descending) — Suggestion implements Comparable
        Collections.sort(suggestions);

        // Step 4: Return top maxResults
        return suggestions.subList(0, Math.min(maxResults, suggestions.size()));
    }

    // -----------------------------------------------------------------------
    // delete(word)
    // -----------------------------------------------------------------------

    /**
     * Delete a word from the trie.
     *
     * Approach: "Lazy delete" — just unmark isEndOfWord.
     * WHY lazy? Proper deletion (pruning empty branches) requires parent pointers
     * or recursive backtracking. For autocomplete, lazy delete is fine because:
     *   1. The trie is periodically rebuilt from the data source anyway
     *   2. Unused branches don't affect correctness, only waste some memory
     *   3. Much simpler to implement (interview-friendly)
     *
     * Time: O(L)
     */
    @Override
    public boolean delete(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }

        TrieNode node = findNode(word.toLowerCase().trim());
        if (node == null || !node.isEndOfWord()) {
            return false; // Word doesn't exist
        }

        // Lazy delete: unmark terminal, clear word and frequency
        node.setEndOfWord(false);
        node.setWord(null);
        node.setFrequency(0);
        wordCount--;
        return true;
    }

    // -----------------------------------------------------------------------
    // startsWith(prefix)
    // -----------------------------------------------------------------------

    /**
     * Check if any word starts with the given prefix.
     * Unlike search(), this returns true even if the prefix itself isn't a complete word.
     *
     * Example: trie has "apple"
     *   startsWith("app") → true (apple starts with app)
     *   search("app") → false (app is not a complete word)
     *
     * Time: O(P)
     */
    @Override
    public boolean startsWith(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return wordCount > 0; // empty prefix matches everything
        }
        return findNode(prefix.toLowerCase().trim()) != null;
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    @Override
    public int size() {
        return wordCount;
    }

    @Override
    public long getFrequency(String word) {
        if (word == null || word.isBlank()) {
            return 0;
        }
        TrieNode node = findNode(word.toLowerCase().trim());
        return (node != null && node.isEndOfWord()) ? node.getFrequency() : 0;
    }

    @Override
    public void clear() {
        root.getChildren().clear();
        wordCount = 0;
    }

    // -----------------------------------------------------------------------
    // Protected helper: findNode — used by subclasses too
    // -----------------------------------------------------------------------

    /**
     * Walk the trie to find the node corresponding to the last character of the given string.
     * Returns null if the path doesn't exist.
     *
     * This is the fundamental trie traversal — used by search, getSuggestions, startsWith, delete.
     *
     * Time: O(L) where L = word.length()
     */
    protected TrieNode findNode(String word) {
        TrieNode current = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            current = current.getChild(c);
            if (current == null) {
                return null; // Path doesn't exist
            }
        }
        return current;
    }

    /**
     * DFS to collect all terminal words reachable from the given node.
     *
     * WHY DFS over BFS?
     *   Both work. DFS uses less memory (O(depth) stack vs O(breadth) queue).
     *   For tries with high branching factor, BFS would use more memory.
     *   DFS also naturally gives us depth-first ordering, though we sort anyway.
     *
     * Time: O(N) where N = total nodes in the subtree
     */
    protected void collectAllWords(TrieNode node, List<Suggestion> results) {
        if (node == null) {
            return;
        }

        // If this node is a terminal, add it as a suggestion
        if (node.isEndOfWord()) {
            results.add(new Suggestion(node.getWord(), node.getFrequency()));
        }

        // Recurse into all children
        // Note: HashMap iteration order is not guaranteed, but we sort afterward anyway
        for (TrieNode child : node.getChildren().values()) {
            collectAllWords(child, results);
        }
    }

    /**
     * Get the root node. Used by TopKTrie to access internal structure.
     */
    protected TrieNode getRoot() {
        return root;
    }
}
