package com.systemdesign.autocomplete.trie;

import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.model.TrieNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TopKTrie — THE interview-winning optimization for autocomplete.
 *
 * THE KEY INSIGHT:
 * ----------------
 * Standard trie getSuggestions is O(P + N) — DFS under prefix, sort, return top-K.
 * For popular prefixes like "a" or "th", N can be MILLIONS of words.
 * Google handles billions of queries/day. DFS every time? Unacceptable.
 *
 * TopKTrie trades INSERT time for LOOKUP time:
 *   - At each node, maintain a pre-computed list of top-K suggestions
 *     reachable from that node
 *   - On insert: walk the path, update top-K at EVERY ancestor node → O(L * K)
 *   - On getSuggestions: just return the list at the prefix node → O(L) to reach node, O(1) to return
 *
 * COMPLEXITY COMPARISON:
 *   Operation        | StandardTrie    | TopKTrie
 *   =================|=================|================
 *   insert           | O(L)            | O(L * K)
 *   getSuggestions    | O(L + N log N)  | O(L) ← just walk to prefix, return list
 *
 *   L = word length, N = words under prefix, K = top-K per node (typically 10-20)
 *
 *   Since K is a small constant (10), O(L * K) ≈ O(L).
 *   The massive win is getSuggestions going from O(N log N) to O(1).
 *
 * HOW IT WORKS:
 * -------------
 * Insert "apple" with frequency 200, K=3:
 *
 *   root → a → p → p → l → e (terminal, freq=200)
 *
 *   At node 'e': topK = [apple(200)]
 *   At node 'l': topK = [apple(200)]
 *   At node 'p': topK = [apple(200)]  (the second 'p')
 *   At node 'p': topK = [apple(200)]  (the first 'p')
 *   At node 'a': topK = [apple(200)]
 *   At root:     topK = [apple(200)]
 *
 * Now insert "app" with frequency 500, K=3:
 *
 *   At node 'p': topK = [app(500), apple(200)]  (the second 'p')
 *   At node 'p': topK = [app(500), apple(200)]  (the first 'p')
 *   At node 'a': topK = [app(500), apple(200)]
 *   At root:     topK = [app(500), apple(200)]
 *
 * Query: getSuggestions("ap") → walk to node for 'p' → return topK = [app(500), apple(200)]
 * That's O(1) at the prefix node! No DFS needed!
 *
 * MEMORY OVERHEAD:
 *   Each node stores a list of up to K Suggestion objects.
 *   Total memory: O(N_nodes * K) where N_nodes = total nodes in trie.
 *   With K=10 and 1M nodes, that's ~10M Suggestion references — acceptable.
 *
 * WHEN TO USE:
 *   - Read-heavy workloads (autocomplete is 99.9% reads)
 *   - K is small (10-20)
 *   - Can tolerate slightly slower inserts
 *   - Need sub-millisecond suggestion latency
 *
 * Wiring:
 *   AppConfig → creates TopKTrie → injects into TrieService
 *   This is the DEFAULT trie for production use.
 */
public class TopKTrie implements Trie {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Delegate to StandardTrie for basic operations (search, delete, etc.) */
    private final StandardTrie standardTrie;

    /** Maximum number of top suggestions to maintain at each node. */
    private final int topK;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param topK number of top suggestions to pre-compute at each node.
     *             Typical values: 10-20 for production, 3-5 for demos.
     */
    public TopKTrie(int topK) {
        this.standardTrie = new StandardTrie();
        this.topK = topK;
    }

    // -----------------------------------------------------------------------
    // insert(word, frequency) — THE KEY OVERRIDE
    // -----------------------------------------------------------------------

    /**
     * Insert a word and update top-K at EVERY ancestor node on the path.
     *
     * Algorithm:
     *   1. Walk char by char from root (same as StandardTrie)
     *   2. At EACH node along the path: update the topK list
     *   3. Mark the final node as terminal
     *
     * Updating topK at a node:
     *   - Create a Suggestion for the new word
     *   - Check if this word already exists in the topK list (update score if so)
     *   - If not, add it if there's room or if it beats the worst entry
     *   - Re-sort and cap at K entries
     *
     * Time: O(L * K) — L nodes on the path, each update is O(K) for sort/insertion
     *
     * WHY update ancestors?
     *   Because a query for "a" should return the top words starting with "a",
     *   which includes "apple", "amazon", "android", etc.
     *   If we only update the terminal node, ancestor nodes wouldn't know about it.
     */
    @Override
    public void insert(String word, long frequency) {
        if (word == null || word.isBlank()) {
            return;
        }

        String normalized = word.toLowerCase().trim();

        // First, do the standard insert to set up nodes and mark terminal
        standardTrie.insert(normalized, frequency);

        // Now walk the path AGAIN to update topK at every ancestor
        // WHY walk twice? We need the terminal node's final frequency (which may have
        // been accumulated if the word was inserted before). Walking once for insert
        // and once for topK update keeps the code clean.
        TrieNode terminalNode = standardTrie.findNode(normalized);
        long finalFrequency = terminalNode.getFrequency();
        Suggestion suggestion = new Suggestion(normalized, finalFrequency);

        // Update topK at the root
        updateTopK(standardTrie.getRoot(), suggestion);

        // Walk the path and update topK at each node
        TrieNode current = standardTrie.getRoot();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            current = current.getChild(c);
            updateTopK(current, suggestion);
        }
    }

    /**
     * Update the top-K suggestions list at a single node.
     *
     * Algorithm:
     *   1. Check if the suggestion's word already exists in the list
     *      - If yes: update its score (it may have changed due to frequency accumulation)
     *      - If no: add it
     *   2. Re-sort the list (descending by score)
     *   3. If list size > K: remove the last entry (lowest score)
     *
     * Time: O(K) — scan list + sort of at most K+1 elements
     *
     * WHY not use a PriorityQueue?
     *   PQ would give O(log K) insert, but we need to:
     *   - Check for duplicates (O(K) scan anyway)
     *   - Return sorted list (PQ doesn't maintain sorted order for iteration)
     *   Since K is small (10-20), a sorted ArrayList is simpler and just as fast.
     */
    private void updateTopK(TrieNode node, Suggestion suggestion) {
        List<Suggestion> topSuggestions = node.getTopSuggestions();

        // Check if this word already exists in the topK list
        boolean found = false;
        for (int i = 0; i < topSuggestions.size(); i++) {
            if (topSuggestions.get(i).getText().equals(suggestion.getText())) {
                // Update the score (frequency may have increased)
                topSuggestions.get(i).setScore(suggestion.getScore());
                found = true;
                break;
            }
        }

        if (!found) {
            if (topSuggestions.size() < topK) {
                // Room available — just add
                topSuggestions.add(new Suggestion(suggestion.getText(), suggestion.getScore()));
            } else {
                // List is full — check if new suggestion beats the worst
                // The list is sorted descending, so the worst is the last element
                Suggestion worst = topSuggestions.get(topSuggestions.size() - 1);
                if (suggestion.getScore() > worst.getScore()) {
                    // Replace the worst with the new suggestion
                    topSuggestions.remove(topSuggestions.size() - 1);
                    topSuggestions.add(new Suggestion(suggestion.getText(), suggestion.getScore()));
                }
                // If new suggestion is worse than the worst, don't add (list stays at K)
            }
        }

        // Re-sort: Suggestion.compareTo sorts by score descending
        Collections.sort(topSuggestions);
    }

    // -----------------------------------------------------------------------
    // getSuggestions(prefix, maxResults) — THE O(1) MAGIC
    // -----------------------------------------------------------------------

    /**
     * Get suggestions in O(L) time — O(L) to walk to prefix node, O(1) to return the list.
     *
     * Compare with StandardTrie.getSuggestions which is O(L + N + N*log(N)):
     *   Standard: find prefix (O(L)) + DFS all words (O(N)) + sort (O(N log N))
     *   TopK:     find prefix (O(L)) + return pre-computed list (O(1))
     *
     * For prefix "a" with 100,000 words starting with "a":
     *   Standard: visits 100,000 nodes, sorts 100,000 entries → slow
     *   TopK: walks 1 node, returns cached list of 10 → instant
     *
     * This is THE optimization that makes autocomplete work at scale.
     */
    @Override
    public List<Suggestion> getSuggestions(String prefix, int maxResults) {
        if (prefix == null || prefix.isBlank()) {
            // Return top-K from root
            List<Suggestion> rootTopK = standardTrie.getRoot().getTopSuggestions();
            return new ArrayList<>(rootTopK.subList(0, Math.min(maxResults, rootTopK.size())));
        }

        String normalized = prefix.toLowerCase().trim();
        TrieNode prefixNode = standardTrie.findNode(normalized);

        if (prefixNode == null) {
            return Collections.emptyList();
        }

        // THE KEY: just return the pre-computed topK list — no DFS needed!
        List<Suggestion> topSuggestions = prefixNode.getTopSuggestions();
        int resultCount = Math.min(maxResults, topSuggestions.size());
        return new ArrayList<>(topSuggestions.subList(0, resultCount));
    }

    // -----------------------------------------------------------------------
    // Delegate all other operations to StandardTrie
    // -----------------------------------------------------------------------

    @Override
    public boolean search(String word) {
        return standardTrie.search(word);
    }

    @Override
    public boolean delete(String word) {
        // Note: delete doesn't remove from topK lists.
        // In production, you'd need to rebuild topK lists periodically
        // or use a more sophisticated removal strategy.
        // For interview purposes, lazy deletion + periodic rebuild is acceptable.
        return standardTrie.delete(word);
    }

    @Override
    public boolean startsWith(String prefix) {
        return standardTrie.startsWith(prefix);
    }

    @Override
    public int size() {
        return standardTrie.size();
    }

    @Override
    public long getFrequency(String word) {
        return standardTrie.getFrequency(word);
    }

    @Override
    public void clear() {
        standardTrie.clear();
    }

    /**
     * Get the underlying StandardTrie (for testing/debugging).
     */
    public StandardTrie getStandardTrie() {
        return standardTrie;
    }

    /**
     * Get the configured K value.
     */
    public int getTopK() {
        return topK;
    }
}
