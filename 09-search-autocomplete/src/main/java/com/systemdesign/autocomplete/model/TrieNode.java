package com.systemdesign.autocomplete.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TrieNode — The fundamental building block of our Trie data structure.
 *
 * WHY this design?
 * ----------------
 * Interview Insight: Interviewers want to see you think about what to store at each node.
 *
 * Naive approach (ugly code):
 *   // Just store children and a flag... then getSuggestions does full DFS every time
 *   class TrieNode {
 *       TrieNode[] children = new TrieNode[26]; // fixed array, wastes memory for sparse tries
 *       boolean isEnd;
 *   }
 *
 * Problems with naive:
 *   1. Fixed array of 26 wastes memory — most nodes only have 1-3 children
 *   2. No frequency tracking — can't rank suggestions
 *   3. No pre-computed top-K — every getSuggestions() triggers full DFS (O(N))
 *   4. Only supports lowercase a-z
 *
 * Clean approach (this class):
 *   - HashMap<Character, TrieNode> children → only allocates for actual children
 *   - Stores frequency at terminal nodes → enables ranking
 *   - Pre-computed topSuggestions → O(1) lookup at query time (the TopKTrie optimization)
 *   - Stores the full word at terminal nodes → avoids reconstructing from path
 *
 * Memory trade-off:
 *   Array[26]:  26 * 8 bytes = 208 bytes per node (even if empty)
 *   HashMap:    ~48 bytes base + 32 bytes per entry → better when < 5 children
 *   For autocomplete (sparse trie), HashMap wins.
 */
public class TrieNode {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /**
     * Children map: character → child node.
     * WHY HashMap over array?
     *   - Autocomplete deals with full character set (not just a-z)
     *   - Most nodes in a real trie are sparse (1-2 children on average)
     *   - HashMap gives O(1) lookup with lower memory for sparse nodes
     */
    private final Map<Character, TrieNode> children;

    /**
     * Marks whether this node represents the end of a complete word.
     * Example: inserting "app" and "apple"
     *   a → p → p (isEndOfWord=true, word="app")
     *              → l → e (isEndOfWord=true, word="apple")
     */
    private boolean isEndOfWord;

    /**
     * The full word stored at terminal nodes.
     * WHY store the whole word?
     *   Without it, to get the word you'd have to walk back up the trie
     *   (which requires parent pointers) or pass a StringBuilder through DFS.
     *   Storing the word here trades O(L) memory per word for O(1) retrieval.
     */
    private String word;

    /**
     * How often this word has been searched/queried.
     * This is the raw popularity signal used for ranking.
     * Only meaningful at terminal nodes (isEndOfWord == true).
     */
    private long frequency;

    /**
     * Pre-computed top-K suggestions reachable from this node.
     * THIS IS THE KEY OPTIMIZATION for TopKTrie:
     *   - Updated on every insert (O(K) per ancestor node)
     *   - Read on every getSuggestions call (O(1) — just return this list!)
     *   - Trade-off: slower inserts for instant lookups
     *
     * For StandardTrie, this list stays empty (not used).
     * For TopKTrie, this list is maintained at EVERY node on the insert path.
     */
    private final List<Suggestion> topSuggestions;

    // -----------------------------------------------------------------------
    // Edge label — used by CompressedTrie (Radix Tree)
    // -----------------------------------------------------------------------

    /**
     * Edge label for compressed (radix) trie.
     * In a standard trie, each edge represents a single character.
     * In a compressed trie, an edge can represent a string (e.g., "ple" instead of p→l→e).
     * This field is null/empty for standard trie nodes.
     */
    private String edgeLabel;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public TrieNode() {
        this.children = new HashMap<>();
        this.isEndOfWord = false;
        this.word = null;
        this.frequency = 0;
        this.topSuggestions = new ArrayList<>();
        this.edgeLabel = null;
    }

    // -----------------------------------------------------------------------
    // Child management methods
    // -----------------------------------------------------------------------

    /**
     * Get the child node for a given character.
     * Returns null if no child exists for that character.
     *
     * Call chain: Trie.search("apple") → for each char → node.getChild(c)
     */
    public TrieNode getChild(char c) {
        return children.get(c);
    }

    /**
     * Add a child node for a given character.
     * Creates a new TrieNode and maps it to the character.
     * If a child already exists for this character, it is replaced (shouldn't happen in normal flow).
     *
     * Call chain: Trie.insert("apple") → for each new char → node.addChild(c)
     *
     * @return the newly created child node (for method chaining)
     */
    public TrieNode addChild(char c) {
        TrieNode child = new TrieNode();
        children.put(c, child);
        return child;
    }

    /**
     * Check if a child exists for the given character.
     * Used to decide between "follow existing path" vs "create new node" during insert.
     */
    public boolean hasChild(char c) {
        return children.containsKey(c);
    }

    /**
     * A leaf node has no children.
     * Useful for CompressedTrie to decide whether to merge nodes.
     */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Map<Character, TrieNode> getChildren() {
        return children;
    }

    public boolean isEndOfWord() {
        return isEndOfWord;
    }

    public void setEndOfWord(boolean endOfWord) {
        this.isEndOfWord = endOfWord;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public long getFrequency() {
        return frequency;
    }

    public void setFrequency(long frequency) {
        this.frequency = frequency;
    }

    /**
     * Increment frequency by delta. Used when the same word is inserted again
     * (e.g., user searches "apple" multiple times).
     */
    public void incrementFrequency(long delta) {
        this.frequency += delta;
    }

    public List<Suggestion> getTopSuggestions() {
        return topSuggestions;
    }

    public String getEdgeLabel() {
        return edgeLabel;
    }

    public void setEdgeLabel(String edgeLabel) {
        this.edgeLabel = edgeLabel;
    }

    @Override
    public String toString() {
        return "TrieNode{" +
                "children=" + children.size() +
                ", isEndOfWord=" + isEndOfWord +
                ", word='" + word + '\'' +
                ", frequency=" + frequency +
                ", topK=" + topSuggestions.size() +
                '}';
    }
}
