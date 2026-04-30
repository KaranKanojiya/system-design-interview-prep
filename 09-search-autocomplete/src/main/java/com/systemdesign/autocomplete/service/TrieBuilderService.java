package com.systemdesign.autocomplete.service;

import com.systemdesign.autocomplete.model.SearchQuery;
import com.systemdesign.autocomplete.repository.QueryRepository;
import com.systemdesign.autocomplete.trie.Trie;
import com.systemdesign.autocomplete.trie.TopKTrie;

import java.util.List;

/**
 * TrieBuilderService — Builds/rebuilds the Trie from query data.
 *
 * WHY a separate builder service?
 * --------------------------------
 * In production, the trie is rebuilt OFFLINE and then swapped in:
 *
 *   Production architecture:
 *   ========================
 *   1. Queries flow into a logging pipeline (Kafka, Kinesis)
 *   2. A batch job (hourly/daily) aggregates query frequencies
 *   3. TrieBuilderService builds a new trie from aggregated data
 *   4. New trie is serialized and distributed to all serving nodes
 *   5. Each node atomically swaps the old trie for the new one
 *
 *   This ensures:
 *     - No downtime during rebuild
 *     - Consistent trie across all nodes
 *     - Old data (stale queries) is cleaned up
 *
 * For this demo, we build in-memory from the QueryRepository.
 *
 * Wiring:
 *   AppConfig → new TrieBuilderService(queryRepository, topKPerNode) → available for rebuild
 *   TrieBuilderService.buildTrie(queries) → creates new Trie → TrieService.rebuildTrie(newTrie)
 */
public class TrieBuilderService {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Repository to read query data from. */
    private final QueryRepository queryRepository;

    /** Top-K parameter for TopKTrie (how many suggestions per node). */
    private final int topKPerNode;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param queryRepository the repository containing all search queries
     * @param topKPerNode     top-K value for the trie (e.g., 10)
     */
    public TrieBuilderService(QueryRepository queryRepository, int topKPerNode) {
        this.queryRepository = queryRepository;
        this.topKPerNode = topKPerNode;
    }

    // -----------------------------------------------------------------------
    // Build methods
    // -----------------------------------------------------------------------

    /**
     * Build a new Trie from a list of search queries.
     *
     * Algorithm:
     *   1. Create a new TopKTrie
     *   2. For each query: insert(queryText, frequency)
     *   3. Return the populated trie
     *
     * Time: O(Q * L * K) where Q = number of queries, L = avg query length, K = topK
     * For 1M queries with avg length 20 and K=10: ~200M operations
     * This takes a few seconds — acceptable for an offline build.
     *
     * @param queries the list of queries to insert
     * @return a new, populated Trie
     */
    public Trie buildTrie(List<SearchQuery> queries) {
        TopKTrie trie = new TopKTrie(topKPerNode);

        if (queries == null || queries.isEmpty()) {
            return trie;
        }

        // Insert each query into the trie
        // Sort by frequency first so higher-frequency words are inserted first.
        // This doesn't affect correctness (TopKTrie handles any order) but
        // it's slightly more efficient because early inserts fill the topK lists
        // with good candidates, reducing churn from later low-frequency inserts.
        queries.stream()
                .sorted((a, b) -> Long.compare(b.getFrequency(), a.getFrequency()))
                .forEach(query -> trie.insert(query.getQueryText(), query.getFrequency()));

        return trie;
    }

    /**
     * Rebuild the trie from the current repository state.
     *
     * Flow:
     *   1. Read ALL queries from repository
     *   2. Build a new trie from them
     *   3. Return the new trie (caller will swap it into TrieService)
     *
     * This is the "periodic rebuild" that runs in production:
     *   - Every hour, rebuild from fresh data
     *   - Removes deleted/stale queries
     *   - Ensures topK lists are accurate
     */
    public Trie rebuildFromRepository() {
        List<SearchQuery> allQueries = queryRepository.getAll();
        return buildTrie(allQueries);
    }

    /**
     * Get the top-K parameter.
     */
    public int getTopKPerNode() {
        return topKPerNode;
    }
}
