package com.systemdesign.autocomplete.trie;

import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.model.TrieNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * CompressedTrie (Radix Tree / Patricia Trie) — Memory-efficient trie that compresses
 * chains of single-child nodes into single nodes with edge labels.
 *
 * WHY compression?
 * ----------------
 * Standard trie for "application": 11 nodes (one per character)
 * But if no other word shares the "lication" suffix, those 8 nodes each have
 * exactly one child — wasted memory.
 *
 * Compressed trie merges single-child chains:
 *
 *   STANDARD TRIE:                    COMPRESSED TRIE:
 *   (root)                            (root)
 *     |                                /    \
 *     a                              "ap"    "bat"*
 *     |                              / \
 *     p                          "p"*  "t"*
 *     |  \                        |
 *     p    t*                  "le"*
 *     |                          |
 *     l                     "ication"*
 *     |
 *     e*
 *     |
 *     i
 *     |  ... (5 more nodes for "cation")
 *
 *   Standard: 15 nodes for "app", "apple", "application", "apt", "bat"
 *   Compressed: 7 nodes — each edge label stores a string, not just a char
 *
 * MEMORY SAVINGS:
 *   Standard:  N nodes where N = total characters across all words (minus shared prefixes)
 *   Compressed: N nodes where N ≈ number of "branch points" + terminals
 *   For a dictionary of 100K English words, compression can reduce node count by 60-80%.
 *
 * TRADE-OFF:
 *   - Lookup is still O(L) but with larger constant (string comparison per edge)
 *   - Insert is more complex (may need to split edges)
 *   - Code complexity is higher (edge cases with splitting)
 *
 * Implementation approach:
 *   We use TrieNode with edgeLabel field. Each node's edgeLabel is the string on
 *   the incoming edge (from parent to this node). Children are keyed by the FIRST
 *   character of their edge label.
 *
 * Wiring:
 *   AppConfig can create CompressedTrie instead of StandardTrie.
 *   Same Trie interface — all consumers are unaware of the compression.
 */
public class CompressedTrie implements Trie {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final TrieNode root;
    private int wordCount;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CompressedTrie() {
        this.root = new TrieNode();
        this.wordCount = 0;
    }

    // -----------------------------------------------------------------------
    // insert(word, frequency)
    // -----------------------------------------------------------------------

    /**
     * Insert a word into the compressed trie.
     *
     * Algorithm:
     *   1. Start at root, remaining = full word
     *   2. At each node, check if any child's edge label shares a common prefix with remaining
     *      a. If NO child matches: create a new child with edgeLabel = remaining, mark terminal
     *      b. If a child matches FULLY: consume that edge, recurse with remaining after the edge
     *      c. If a child PARTIALLY matches: SPLIT the edge at the divergence point
     *
     * SPLITTING example:
     *   Existing edge: "apple" (terminal)
     *   Inserting: "application"
     *   Common prefix: "appl"
     *   Split:
     *     Before: parent --"apple"--> terminal
     *     After:  parent --"appl"--> intermediate --"e"--> terminal(apple)
     *                                             --"ication"--> terminal(application)
     *
     * Time: O(L) where L = word length
     */
    @Override
    public void insert(String word, long frequency) {
        if (word == null || word.isBlank()) {
            return;
        }

        String normalized = word.toLowerCase().trim();
        insertHelper(root, normalized, normalized, frequency);
    }

    /**
     * Recursive helper for insertion.
     *
     * @param node       current node
     * @param remaining  the portion of the word not yet consumed
     * @param fullWord   the complete original word (for storing at terminal)
     * @param frequency  the frequency to assign
     */
    private void insertHelper(TrieNode node, String remaining, String fullWord, long frequency) {
        if (remaining.isEmpty()) {
            // We've consumed the entire word — mark this node as terminal
            if (!node.isEndOfWord()) {
                node.setEndOfWord(true);
                node.setWord(fullWord);
                node.setFrequency(frequency);
                wordCount++;
            } else {
                node.incrementFrequency(frequency);
            }
            return;
        }

        char firstChar = remaining.charAt(0);
        TrieNode child = node.getChild(firstChar);

        if (child == null) {
            // Case A: No child starts with this character — create new edge
            TrieNode newNode = node.addChild(firstChar);
            newNode.setEdgeLabel(remaining);
            newNode.setEndOfWord(true);
            newNode.setWord(fullWord);
            newNode.setFrequency(frequency);
            wordCount++;
            return;
        }

        // A child exists whose edge starts with firstChar
        String edgeLabel = child.getEdgeLabel();
        if (edgeLabel == null) {
            edgeLabel = String.valueOf(firstChar);
        }

        int commonLen = commonPrefixLength(remaining, edgeLabel);

        if (commonLen == edgeLabel.length()) {
            // Case B: The entire edge label is consumed — recurse with the rest
            // Example: edge = "app", remaining = "apple" → consume "app", recurse with "le"
            insertHelper(child, remaining.substring(commonLen), fullWord, frequency);
        } else {
            // Case C: Partial match — need to SPLIT the edge
            // Example: edge = "apple", remaining = "application"
            //   commonLen = 4 ("appl")
            //   Split: create intermediate node for "appl"
            //          old child becomes child of intermediate with edge "e"
            //          new word gets new child of intermediate with edge "ication"

            String commonPrefix = edgeLabel.substring(0, commonLen);
            String oldSuffix = edgeLabel.substring(commonLen);
            String newSuffix = remaining.substring(commonLen);

            // Create an intermediate node
            TrieNode intermediate = new TrieNode();
            intermediate.setEdgeLabel(commonPrefix);

            // Re-parent: the old child becomes a child of intermediate
            // Key it by the first char of oldSuffix
            child.setEdgeLabel(oldSuffix);
            intermediate.getChildren().put(oldSuffix.charAt(0), child);

            // Replace the old child in parent with the intermediate
            node.getChildren().put(firstChar, intermediate);

            if (newSuffix.isEmpty()) {
                // The new word ends exactly at the split point
                intermediate.setEndOfWord(true);
                intermediate.setWord(fullWord);
                intermediate.setFrequency(frequency);
                wordCount++;
            } else {
                // Add the new suffix as a child of intermediate
                TrieNode newChild = intermediate.addChild(newSuffix.charAt(0));
                newChild.setEdgeLabel(newSuffix);
                newChild.setEndOfWord(true);
                newChild.setWord(fullWord);
                newChild.setFrequency(frequency);
                wordCount++;
            }
        }
    }

    /**
     * Find the length of the common prefix between two strings.
     * Example: commonPrefixLength("apple", "application") = 4 ("appl")
     */
    private int commonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return len;
    }

    // -----------------------------------------------------------------------
    // search(word)
    // -----------------------------------------------------------------------

    /**
     * Search for an exact word in the compressed trie.
     *
     * Algorithm: Walk edges, consuming the word piece by piece.
     * At each node, find the child whose edge label matches the next portion of the word.
     *
     * Time: O(L) where L = word length
     */
    @Override
    public boolean search(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }

        TrieNode node = findNode(word.toLowerCase().trim());
        return node != null && node.isEndOfWord();
    }

    // -----------------------------------------------------------------------
    // getSuggestions(prefix, maxResults)
    // -----------------------------------------------------------------------

    /**
     * Get suggestions for a prefix from the compressed trie.
     *
     * Algorithm:
     *   1. Navigate to the node representing the end of the prefix
     *      (may land in the middle of an edge — handled by partial matching)
     *   2. DFS to collect all terminal nodes below
     *   3. Sort by frequency, return top-K
     *
     * Time: O(P + N) where P = prefix length, N = words under prefix
     */
    @Override
    public List<Suggestion> getSuggestions(String prefix, int maxResults) {
        if (prefix == null || prefix.isBlank()) {
            List<Suggestion> all = new ArrayList<>();
            collectAllWords(root, all);
            Collections.sort(all);
            return all.subList(0, Math.min(maxResults, all.size()));
        }

        String normalized = prefix.toLowerCase().trim();
        TrieNode node = findPrefixNode(root, normalized);

        if (node == null) {
            return Collections.emptyList();
        }

        List<Suggestion> suggestions = new ArrayList<>();
        collectAllWords(node, suggestions);
        Collections.sort(suggestions);
        return suggestions.subList(0, Math.min(maxResults, suggestions.size()));
    }

    /**
     * Find the node representing the end of a prefix.
     * In a compressed trie, the prefix might end in the MIDDLE of an edge label.
     * In that case, we return the child node (the one whose edge contains the prefix ending).
     */
    private TrieNode findPrefixNode(TrieNode node, String remaining) {
        if (remaining.isEmpty()) {
            return node;
        }

        char firstChar = remaining.charAt(0);
        TrieNode child = node.getChild(firstChar);
        if (child == null) {
            return null;
        }

        String edgeLabel = child.getEdgeLabel();
        if (edgeLabel == null) {
            edgeLabel = String.valueOf(firstChar);
        }

        if (remaining.length() <= edgeLabel.length()) {
            // Prefix might end within this edge
            if (edgeLabel.startsWith(remaining)) {
                return child; // Prefix is fully contained within this edge
            } else {
                return null; // Mismatch
            }
        } else {
            // Prefix extends beyond this edge
            if (remaining.startsWith(edgeLabel)) {
                return findPrefixNode(child, remaining.substring(edgeLabel.length()));
            } else {
                return null; // Mismatch
            }
        }
    }

    // -----------------------------------------------------------------------
    // delete(word)
    // -----------------------------------------------------------------------

    /**
     * Lazy delete — just unmark the terminal node.
     * Same rationale as StandardTrie: trie is periodically rebuilt anyway.
     */
    @Override
    public boolean delete(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }

        TrieNode node = findNode(word.toLowerCase().trim());
        if (node == null || !node.isEndOfWord()) {
            return false;
        }

        node.setEndOfWord(false);
        node.setWord(null);
        node.setFrequency(0);
        wordCount--;
        return true;
    }

    // -----------------------------------------------------------------------
    // startsWith(prefix)
    // -----------------------------------------------------------------------

    @Override
    public boolean startsWith(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return wordCount > 0;
        }
        return findPrefixNode(root, prefix.toLowerCase().trim()) != null;
    }

    // -----------------------------------------------------------------------
    // Utility
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

    /**
     * Find the node for an exact word by walking edges.
     */
    private TrieNode findNode(String word) {
        TrieNode current = root;
        String remaining = word;

        while (!remaining.isEmpty()) {
            char firstChar = remaining.charAt(0);
            TrieNode child = current.getChild(firstChar);
            if (child == null) {
                return null;
            }

            String edgeLabel = child.getEdgeLabel();
            if (edgeLabel == null) {
                edgeLabel = String.valueOf(firstChar);
            }

            if (!remaining.startsWith(edgeLabel)) {
                return null; // Mismatch within edge
            }

            remaining = remaining.substring(edgeLabel.length());
            current = child;
        }

        return current;
    }

    /**
     * DFS to collect all terminal words from a node.
     */
    private void collectAllWords(TrieNode node, List<Suggestion> results) {
        if (node == null) {
            return;
        }

        if (node.isEndOfWord()) {
            results.add(new Suggestion(node.getWord(), node.getFrequency()));
        }

        for (TrieNode child : node.getChildren().values()) {
            collectAllWords(child, results);
        }
    }

    /**
     * Count total nodes in the trie (for memory comparison with StandardTrie).
     */
    public int countNodes() {
        return countNodesHelper(root);
    }

    private int countNodesHelper(TrieNode node) {
        int count = 1; // this node
        for (TrieNode child : node.getChildren().values()) {
            count += countNodesHelper(child);
        }
        return count;
    }

    /**
     * Get the root (for display/debugging).
     */
    public TrieNode getRoot() {
        return root;
    }
}
