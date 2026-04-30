package com.systemdesign.autocomplete;

import com.systemdesign.autocomplete.config.AppConfig;
import com.systemdesign.autocomplete.controller.AutocompleteController;
import com.systemdesign.autocomplete.display.AutocompleteStatsDisplay;
import com.systemdesign.autocomplete.model.AutocompleteConfig;
import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.service.AutocompleteService;
import com.systemdesign.autocomplete.strategy.ranking.FrequencyRankingStrategy;
import com.systemdesign.autocomplete.strategy.ranking.PersonalizedRankingStrategy;
import com.systemdesign.autocomplete.strategy.ranking.TimeDecayRankingStrategy;
import com.systemdesign.autocomplete.trie.CompressedTrie;
import com.systemdesign.autocomplete.trie.StandardTrie;
import com.systemdesign.autocomplete.trie.TopKTrie;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * SearchAutocompleteApp — Main demo application for the Search Autocomplete system.
 *
 * This application demonstrates:
 *   1. Basic Trie operations (insert, search, prefix match)
 *   2. Autocomplete suggestions with prefix matching
 *   3. Standard vs Compressed Trie (memory comparison)
 *   4. TopK Trie with O(1) lookup
 *   5. Ranking strategies comparison (frequency, time-decay, personalized)
 *   6. Trending queries detection
 *   7. Cache hit/miss performance
 *   8. Profanity filter
 *   9. Real-world simulation with Zipf distribution
 *
 * Run: javac + java (no frameworks, no build tools required)
 */
public class SearchAutocompleteApp {

    private static final String SEPARATOR = "=".repeat(70);
    private static final String THIN_SEP = "-".repeat(70);

    // -----------------------------------------------------------------------
    // Sample data
    // -----------------------------------------------------------------------

    /** Common search queries with their frequencies (simulating real-world data). */
    private static final String[][] SAMPLE_QUERIES = {
            {"apple", "5000"},
            {"apple watch", "3000"},
            {"apple music", "2500"},
            {"application", "2000"},
            {"app store", "4500"},
            {"applied mathematics", "500"},
            {"appointment", "1500"},
            {"how to cook pasta", "8000"},
            {"how to learn java", "7000"},
            {"how to invest money", "6000"},
            {"how to lose weight", "9000"},
            {"how are you", "10000"},
            {"weather today", "15000"},
            {"weather forecast", "12000"},
            {"weather tomorrow", "8000"},
            {"python tutorial", "6000"},
            {"python programming", "5000"},
            {"java tutorial", "5500"},
            {"java interview questions", "7000"},
            {"javascript", "8000"},
            {"machine learning", "9000"},
            {"machine learning tutorial", "4000"},
            {"map of the world", "3000"},
            {"amazon", "20000"},
            {"amazon prime", "15000"},
            {"android", "12000"},
            {"android studio", "6000"},
            {"best restaurants near me", "11000"},
            {"best movies 2024", "7000"},
            {"breaking news", "5000"},
            {"bitcoin price", "13000"},
    };

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   PROJECT 09: SEARCH AUTOCOMPLETE (TYPEAHEAD) SYSTEM");
        System.out.println(SEPARATOR);
        System.out.println();

        // Build the application using AppConfig (the ONLY place with new ConcreteClass())
        AutocompleteConfig config = AutocompleteConfig.builder()
                .maxResults(10)
                .topKPerNode(10)
                .cacheSize(1000)
                .cacheTtlSeconds(300)
                .decayFactor(0.01)
                .build();

        AppConfig appConfig = new AppConfig(config).build();

        // Run all demos
        demo1_BasicTrieOperations(appConfig);
        demo2_AutocompleteSuggestions(appConfig);
        demo3_StandardVsCompressedTrie(appConfig);
        demo4_TopKTrieO1Lookup(appConfig);
        demo5_RankingStrategiesComparison(appConfig);
        demo6_TrendingQueriesDetection(appConfig);
        demo7_CacheHitMissPerformance(appConfig);
        demo8_ProfanityFilter(appConfig);
        demo9_RealWorldSimulation(appConfig);

        // Final summary
        printDesignSummary();
    }

    // =======================================================================
    // DEMO 1: Basic Trie Operations
    // =======================================================================

    private static void demo1_BasicTrieOperations(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Basic Trie Operations");
        System.out.println(SEPARATOR);
        System.out.println();

        // Create a fresh StandardTrie for this demo
        StandardTrie trie = appConfig.createStandardTrie();

        // Insert words
        System.out.println("  Inserting words: apple(100), app(200), application(50), bat(150), ban(80)");
        trie.insert("apple", 100);
        trie.insert("app", 200);
        trie.insert("application", 50);
        trie.insert("bat", 150);
        trie.insert("ban", 80);
        System.out.println();

        // Search
        System.out.println("  Search operations:");
        System.out.printf("    search(\"apple\")       = %s%n", trie.search("apple"));
        System.out.printf("    search(\"app\")         = %s%n", trie.search("app"));
        System.out.printf("    search(\"ap\")          = %s  (prefix exists but not a word)%n", trie.search("ap"));
        System.out.printf("    search(\"xyz\")         = %s  (not in trie)%n", trie.search("xyz"));
        System.out.println();

        // Prefix check
        System.out.println("  Prefix operations:");
        System.out.printf("    startsWith(\"app\")     = %s  (apple, app, application)%n", trie.startsWith("app"));
        System.out.printf("    startsWith(\"bat\")     = %s  (bat)%n", trie.startsWith("bat"));
        System.out.printf("    startsWith(\"xyz\")     = %s  (nothing)%n", trie.startsWith("xyz"));
        System.out.println();

        // Frequency
        System.out.println("  Frequency lookup:");
        System.out.printf("    getFrequency(\"app\")   = %d%n", trie.getFrequency("app"));
        System.out.printf("    getFrequency(\"apple\") = %d%n", trie.getFrequency("apple"));
        System.out.printf("    getFrequency(\"xyz\")   = %d  (not found)%n", trie.getFrequency("xyz"));
        System.out.println();

        // Size and delete
        System.out.printf("  Trie size: %d words%n", trie.size());
        System.out.printf("  Delete \"bat\": %s%n", trie.delete("bat"));
        System.out.printf("  Trie size after delete: %d words%n", trie.size());
        System.out.printf("  search(\"bat\") after delete: %s%n", trie.search("bat"));
        System.out.println();

        // Edge case: empty prefix
        System.out.println("  Edge case — getSuggestions with empty prefix:");
        List<Suggestion> allSuggestions = trie.getSuggestions("", 3);
        for (Suggestion s : allSuggestions) {
            System.out.printf("    %s (score: %.0f)%n", s.getText(), s.getScore());
        }
        System.out.println();
    }

    // =======================================================================
    // DEMO 2: Autocomplete Suggestions
    // =======================================================================

    private static void demo2_AutocompleteSuggestions(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Autocomplete Suggestions");
        System.out.println(SEPARATOR);
        System.out.println();

        StandardTrie trie = appConfig.createStandardTrie();

        // Insert sample data
        for (String[] entry : SAMPLE_QUERIES) {
            trie.insert(entry[0], Long.parseLong(entry[1]));
        }

        // Simulate typing "app" character by character
        System.out.println("  Simulating user typing \"app\" one character at a time:");
        System.out.println();

        String[] prefixes = {"a", "ap", "app"};
        for (String prefix : prefixes) {
            System.out.printf("  User types: \"%s\"%n", prefix);
            List<Suggestion> suggestions = trie.getSuggestions(prefix, 5);
            for (int i = 0; i < suggestions.size(); i++) {
                System.out.printf("    %d. %-30s (score: %.0f)%n",
                        i + 1, suggestions.get(i).getText(), suggestions.get(i).getScore());
            }
            System.out.println();
        }

        // More prefix examples
        System.out.println("  More examples:");
        System.out.println(THIN_SEP);

        String[] morePrefixes = {"how", "weather", "java", "mac"};
        for (String prefix : morePrefixes) {
            System.out.printf("  Prefix: \"%s\"%n", prefix);
            List<Suggestion> suggestions = trie.getSuggestions(prefix, 3);
            if (suggestions.isEmpty()) {
                System.out.println("    (no results)");
            } else {
                for (Suggestion s : suggestions) {
                    System.out.printf("    -> %-35s (score: %.0f)%n", s.getText(), s.getScore());
                }
            }
            System.out.println();
        }

        // Edge case: no results
        System.out.println("  Edge case — prefix with no matches:");
        List<Suggestion> noResults = trie.getSuggestions("zzz", 5);
        System.out.printf("    getSuggestions(\"zzz\"): %d results%n", noResults.size());
        System.out.println();
    }

    // =======================================================================
    // DEMO 3: Standard vs Compressed Trie
    // =======================================================================

    private static void demo3_StandardVsCompressedTrie(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Standard vs Compressed Trie (Memory Comparison)");
        System.out.println(SEPARATOR);
        System.out.println();

        StandardTrie standardTrie = appConfig.createStandardTrie();
        CompressedTrie compressedTrie = appConfig.createCompressedTrie();

        // Insert the same data into both
        for (String[] entry : SAMPLE_QUERIES) {
            standardTrie.insert(entry[0], Long.parseLong(entry[1]));
            compressedTrie.insert(entry[0], Long.parseLong(entry[1]));
        }

        System.out.println("  Inserted " + SAMPLE_QUERIES.length + " queries into both tries.");
        System.out.println();

        // Compare word counts
        System.out.println("  Word count comparison:");
        System.out.printf("    Standard Trie:   %d words%n", standardTrie.size());
        System.out.printf("    Compressed Trie: %d words%n", compressedTrie.size());
        System.out.println();

        // Compare node counts (compressed should have fewer)
        System.out.println("  Node count comparison:");
        System.out.printf("    Compressed Trie nodes: %d%n", compressedTrie.countNodes());
        System.out.println("    (Standard Trie has ~1 node per character, compressed merges chains)");
        System.out.println();

        // Verify same results
        System.out.println("  Verification — same suggestions from both tries:");
        String[] testPrefixes = {"app", "how", "weath"};
        for (String prefix : testPrefixes) {
            List<Suggestion> stdResults = standardTrie.getSuggestions(prefix, 3);
            List<Suggestion> cmpResults = compressedTrie.getSuggestions(prefix, 3);
            System.out.printf("    Prefix \"%s\":%n", prefix);
            System.out.printf("      Standard:   %s%n", formatSuggestions(stdResults));
            System.out.printf("      Compressed: %s%n", formatSuggestions(cmpResults));

            // Check match
            boolean match = stdResults.size() == cmpResults.size();
            if (match) {
                for (int i = 0; i < stdResults.size(); i++) {
                    if (!stdResults.get(i).getText().equals(cmpResults.get(i).getText())) {
                        match = false;
                        break;
                    }
                }
            }
            System.out.printf("      Match: %s%n", match ? "YES" : "NO (order may differ)");
        }
        System.out.println();

        // Visual compression example
        System.out.println("  Compression visualization:");
        System.out.println("    Standard Trie for 'application':");
        System.out.println("      a -> p -> p -> l -> i -> c -> a -> t -> i -> o -> n  (11 nodes)");
        System.out.println();
        System.out.println("    Compressed Trie (after 'app', 'apple', 'application' inserted):");
        System.out.println("      'app'* -> 'l' -> 'e'*");
        System.out.println("                     -> 'ication'*");
        System.out.println("      (* = end of word, edges are strings not single chars)");
        System.out.println();
    }

    // =======================================================================
    // DEMO 4: TopK Trie — O(1) Lookup
    // =======================================================================

    private static void demo4_TopKTrieO1Lookup(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: TopK Trie -- O(1) Lookup (The Interview Star)");
        System.out.println(SEPARATOR);
        System.out.println();

        TopKTrie topKTrie = appConfig.createTopKTrie();

        // Insert words and show how topK is maintained at each node
        System.out.println("  Inserting words with frequencies:");
        String[][] words = {
                {"apple", "200"},
                {"app", "500"},
                {"application", "100"},
                {"app store", "450"},
                {"appointment", "150"},
        };

        for (String[] entry : words) {
            topKTrie.insert(entry[0], Long.parseLong(entry[1]));
            System.out.printf("    Inserted \"%s\" (freq=%s)%n", entry[0], entry[1]);
        }
        System.out.println();

        // Show pre-computed top-K at various prefix nodes
        System.out.println("  Pre-computed top-K suggestions at each prefix node:");
        System.out.println("  (These are computed DURING INSERT, not during query!)");
        System.out.println(THIN_SEP);

        String[] prefixes = {"a", "ap", "app", "appl", "apple"};
        for (String prefix : prefixes) {
            List<Suggestion> suggestions = topKTrie.getSuggestions(prefix, 5);
            System.out.printf("  Prefix \"%s\" -> [%s]%n", prefix, formatSuggestions(suggestions));
        }
        System.out.println();

        // Performance comparison: StandardTrie vs TopKTrie
        System.out.println("  Performance comparison (conceptual):");
        System.out.println(THIN_SEP);
        System.out.println("    StandardTrie.getSuggestions(\"a\"):");
        System.out.println("      1. Walk to node 'a'             -> O(1)");
        System.out.println("      2. DFS all words starting with 'a' -> O(N) where N = all 'a' words");
        System.out.println("      3. Sort by frequency             -> O(N log N)");
        System.out.println("      4. Return top K                  -> O(K)");
        System.out.println("      TOTAL: O(N log N) -- for prefix 'a', N could be millions!");
        System.out.println();
        System.out.println("    TopKTrie.getSuggestions(\"a\"):");
        System.out.println("      1. Walk to node 'a'             -> O(1)");
        System.out.println("      2. Return pre-computed topK list -> O(1)");
        System.out.println("      TOTAL: O(1) -- instant, regardless of how many words start with 'a'!");
        System.out.println();
        System.out.println("    Trade-off: Insert is O(L*K) instead of O(L), but K is small (10-20).");
        System.out.println("    For read-heavy autocomplete (99.9% reads), this is a massive win.");
        System.out.println();
    }

    // =======================================================================
    // DEMO 5: Ranking Strategies Comparison
    // =======================================================================

    private static void demo5_RankingStrategiesComparison(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Ranking Strategies Comparison");
        System.out.println(SEPARATOR);
        System.out.println();

        // Set up data
        AutocompleteService service = appConfig.getAutocompleteService();
        FrequencyRankingStrategy freqStrategy = appConfig.getFrequencyRankingStrategy();
        TimeDecayRankingStrategy decayStrategy = appConfig.getTimeDecayRankingStrategy();
        PersonalizedRankingStrategy personalizedStrategy = appConfig.getPersonalizedRankingStrategy();

        // Insert sample data
        for (String[] entry : SAMPLE_QUERIES) {
            appConfig.getTrieService().insertQuery(entry[0], Long.parseLong(entry[1]));
        }

        // Set up time-decay data: some queries are old, some are recent
        decayStrategy.recordSearch("java tutorial", LocalDateTime.now().minusHours(72));
        decayStrategy.recordSearch("java interview questions", LocalDateTime.now().minusHours(1));
        decayStrategy.recordSearch("javascript", LocalDateTime.now().minusHours(48));

        // Set up personalization data
        String userId = "alice";
        personalizedStrategy.recordUserSearch(userId, "java interview questions");
        personalizedStrategy.setUserLanguage(userId, "en");
        personalizedStrategy.setUserLocation(userId, "US");

        String prefix = "java";
        SearchContext anonContext = new SearchContext();
        SearchContext aliceContext = new SearchContext(userId, "en", "US", LocalDateTime.now());

        // Get raw suggestions from trie
        List<Suggestion> rawSuggestions = appConfig.getTrieService().getSuggestions(prefix, 5);

        System.out.printf("  Prefix: \"%s\"%n", prefix);
        System.out.printf("  Raw suggestions from trie (sorted by frequency):%n");
        for (Suggestion s : rawSuggestions) {
            System.out.printf("    %-35s score=%.0f%n", s.getText(), s.getScore());
        }
        System.out.println();

        // Strategy 1: Frequency
        System.out.println("  Strategy 1: FrequencyRanking (pure popularity):");
        System.out.println(THIN_SEP);
        appConfig.getRankingService().setStrategy(freqStrategy);
        List<Suggestion> freqResults = appConfig.getRankingService().rankSuggestions(
                cloneSuggestions(rawSuggestions), anonContext);
        for (int i = 0; i < freqResults.size(); i++) {
            System.out.printf("    %d. %-35s score=%.2f%n",
                    i + 1, freqResults.get(i).getText(), freqResults.get(i).getScore());
        }
        System.out.println();

        // Strategy 2: Time Decay
        System.out.println("  Strategy 2: TimeDecayRanking (freshness matters):");
        System.out.printf("    decay factor = %.4f, half-life = %.1f hours%n",
                decayStrategy.getDecayFactor(), decayStrategy.getHalfLifeHours());
        System.out.println(THIN_SEP);
        appConfig.getRankingService().setStrategy(decayStrategy);
        List<Suggestion> decayResults = appConfig.getRankingService().rankSuggestions(
                cloneSuggestions(rawSuggestions), anonContext);
        for (int i = 0; i < decayResults.size(); i++) {
            System.out.printf("    %d. %-35s score=%.2f%n",
                    i + 1, decayResults.get(i).getText(), decayResults.get(i).getScore());
        }
        System.out.println();

        // Strategy 3: Personalized (for user "alice")
        System.out.println("  Strategy 3: PersonalizedRanking (for user 'alice'):");
        System.out.println("    alice previously searched: 'java interview questions'");
        System.out.println("    boost = 1.5x for user history, 1.2x for locale match");
        System.out.println(THIN_SEP);
        appConfig.getRankingService().setStrategy(personalizedStrategy);
        List<Suggestion> personalResults = appConfig.getRankingService().rankSuggestions(
                cloneSuggestions(rawSuggestions), aliceContext);
        for (int i = 0; i < personalResults.size(); i++) {
            System.out.printf("    %d. %-35s score=%.2f%n",
                    i + 1, personalResults.get(i).getText(), personalResults.get(i).getScore());
        }
        System.out.println();

        // Reset to default strategy
        appConfig.getRankingService().setStrategy(personalizedStrategy);
    }

    // =======================================================================
    // DEMO 6: Trending Queries Detection
    // =======================================================================

    private static void demo6_TrendingQueriesDetection(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Trending Queries Detection");
        System.out.println(SEPARATOR);
        System.out.println();

        AutocompleteController controller = appConfig.getController();

        System.out.println("  Simulating a spike in 'breaking news' queries...");
        System.out.println("  (Normal rate: ~5 queries/hour. Spike: 20+ queries in minutes)");
        System.out.println();

        // Simulate normal traffic first
        String[] normalQueries = {"apple", "weather today", "how to cook pasta", "java tutorial"};
        for (String q : normalQueries) {
            controller.handleRecordQuery(q, null);
        }

        // Simulate a trending spike for "breaking news"
        for (int i = 0; i < 15; i++) {
            controller.handleRecordQuery("breaking news", "user" + i);
        }

        // Also spike "earthquake"
        for (int i = 0; i < 10; i++) {
            controller.handleRecordQuery("earthquake today", "user" + i);
        }

        System.out.println("  After simulating spike:");
        System.out.println(controller.handleGetTrending());

        // Show that the system now suggests trending queries
        System.out.println("  Autocomplete for 'break' now includes the trending query:");
        System.out.println(controller.handleSearch("break", null));
    }

    // =======================================================================
    // DEMO 7: Cache Hit/Miss Performance
    // =======================================================================

    private static void demo7_CacheHitMissPerformance(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Cache Hit/Miss Performance");
        System.out.println(SEPARATOR);
        System.out.println();

        AutocompleteService service = appConfig.getAutocompleteService();
        SearchContext context = new SearchContext();

        // Clear cache for clean demo
        service.getCache().invalidateAll();

        String prefix = "how";

        // First call — CACHE MISS
        long start = System.nanoTime();
        List<Suggestion> firstCall = service.getSuggestions(prefix, context);
        long firstDuration = System.nanoTime() - start;

        System.out.printf("  First call getSuggestions(\"%s\"): CACHE MISS%n", prefix);
        System.out.printf("    Time: %d ns (%.3f ms)%n", firstDuration, firstDuration / 1_000_000.0);
        System.out.printf("    Results: %d suggestions%n", firstCall.size());
        for (Suggestion s : firstCall) {
            System.out.printf("      -> %s (score: %.2f)%n", s.getText(), s.getScore());
        }
        System.out.println();

        // Second call — CACHE HIT
        start = System.nanoTime();
        List<Suggestion> secondCall = service.getSuggestions(prefix, context);
        long secondDuration = System.nanoTime() - start;

        System.out.printf("  Second call getSuggestions(\"%s\"): CACHE HIT%n", prefix);
        System.out.printf("    Time: %d ns (%.3f ms)%n", secondDuration, secondDuration / 1_000_000.0);
        System.out.printf("    Results: %d suggestions%n", secondCall.size());
        System.out.println();

        // Speedup
        if (secondDuration > 0) {
            double speedup = (double) firstDuration / secondDuration;
            System.out.printf("  Speedup: %.1fx faster on cache hit%n", speedup);
        }

        System.out.printf("  Cache stats: hit rate = %.2f%%, size = %d entries%n",
                service.getCache().getHitRate() * 100, service.getCache().size());
        System.out.println();
    }

    // =======================================================================
    // DEMO 8: Profanity Filter
    // =======================================================================

    private static void demo8_ProfanityFilter(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Profanity Filter");
        System.out.println(SEPARATOR);
        System.out.println();

        // Insert some offensive terms into the trie
        appConfig.getTrieService().insertQuery("how to be badword", 100);
        appConfig.getTrieService().insertQuery("offensive content", 200);
        appConfig.getTrieService().insertQuery("how to be kind", 300);
        appConfig.getTrieService().insertQuery("how to be successful", 500);
        appConfig.getTrieService().insertQuery("how to be offensive", 150);

        // Clear cache so we get fresh results through the filter pipeline
        appConfig.getAutocompleteService().getCache().invalidateAll();

        // Unfiltered results (direct trie query)
        System.out.println("  Unfiltered trie results for \"how to be\":");
        List<Suggestion> unfiltered = appConfig.getTrieService().getSuggestions("how to be", 10);
        for (Suggestion s : unfiltered) {
            System.out.printf("    -> %-35s (score: %.0f)%n", s.getText(), s.getScore());
        }
        System.out.println();

        // Filtered results (through the full pipeline)
        System.out.println("  Filtered results (through AutocompleteService pipeline):");
        System.out.printf("  Banned words: %d configured%n", appConfig.getProfanityFilter().getBannedWordCount());
        SearchContext context = new SearchContext();
        List<Suggestion> filtered = appConfig.getAutocompleteService().getSuggestions("how to be", context);
        if (filtered.isEmpty()) {
            System.out.println("    (all suggestions were filtered out or no results)");
        } else {
            for (Suggestion s : filtered) {
                System.out.printf("    -> %-35s (score: %.2f)%n", s.getText(), s.getScore());
            }
        }
        System.out.println();
        System.out.println("  Notice: 'how to be badword' and 'how to be offensive' are filtered out!");
        System.out.println();
    }

    // =======================================================================
    // DEMO 9: Real-World Simulation
    // =======================================================================

    private static void demo9_RealWorldSimulation(AppConfig appConfig) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Real-World Simulation (Zipf Distribution)");
        System.out.println(SEPARATOR);
        System.out.println();

        AutocompleteService service = appConfig.getAutocompleteService();
        service.getCache().invalidateAll();

        // Simulate 1000 search queries with Zipf-like distribution
        // Zipf's law: in natural language, the nth most common word has frequency ~ 1/n
        // A few queries are VERY popular, the long tail has many rare queries
        Random random = new Random(42); // deterministic seed for reproducible results

        System.out.println("  Simulating 1000 queries with Zipf distribution...");
        System.out.println("  (A few popular queries, many rare ones)");
        System.out.println();

        int totalSimulatedQueries = 1000;
        int queryIndex = 0;
        for (int i = 0; i < totalSimulatedQueries; i++) {
            // Zipf: pick query index with probability proportional to 1/(rank+1)
            // Lower-ranked (more popular) queries are picked more often
            double zipfRank = Math.pow(random.nextDouble(), 2) * SAMPLE_QUERIES.length;
            queryIndex = Math.min((int) zipfRank, SAMPLE_QUERIES.length - 1);
            String query = SAMPLE_QUERIES[queryIndex][0];

            service.recordQuery(query, "user" + (i % 50));
        }

        System.out.printf("  Simulated %d queries across %d unique query types%n",
                totalSimulatedQueries, SAMPLE_QUERIES.length);
        System.out.println();

        // Now test autocomplete with the populated system
        service.getCache().invalidateAll(); // clear cache for clean test

        System.out.println("  Testing autocomplete after simulation:");
        System.out.println(THIN_SEP);

        String[] testPrefixes = {"a", "ho", "wea", "java", "b"};
        int cacheHits = 0;
        int totalLookups = 0;

        for (String prefix : testPrefixes) {
            SearchContext ctx = new SearchContext("user1", "en", "US", LocalDateTime.now());

            // First call (miss)
            List<Suggestion> results = service.getSuggestions(prefix, ctx);
            totalLookups++;

            System.out.printf("  \"%s\" -> %d results: ", prefix, results.size());
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                if (i > 0) System.out.print(", ");
                System.out.printf("%s(%.0f)", results.get(i).getText(), results.get(i).getScore());
            }
            if (results.size() > 3) System.out.print(", ...");
            System.out.println();

            // Second call (should be cache hit)
            service.getSuggestions(prefix, ctx);
            totalLookups++;
        }

        System.out.println();

        // Final stats
        System.out.println("  System stats after simulation:");
        System.out.println(THIN_SEP);
        System.out.printf("  Trie size:              %d words%n", service.getTrieService().size());
        System.out.printf("  Cache size:             %d entries%n", service.getCache().size());
        System.out.printf("  Cache hit rate:         %.2f%%%n", service.getCache().getHitRate() * 100);
        System.out.printf("  Total queries recorded: %d%n",
                service.getDataCollectionService().getTotalQueriesRecorded());
        System.out.printf("  Unique queries:         %d%n",
                service.getDataCollectionService().getUniqueQueryCount());
        System.out.println();

        // Show top queries from data collection
        System.out.println("  Top 5 queries by frequency:");
        var topQueries = service.getDataCollectionService().getTopQueries(5);
        for (int i = 0; i < topQueries.size(); i++) {
            var q = topQueries.get(i);
            System.out.printf("    %d. %-35s freq=%d%n", i + 1, q.getQueryText(), q.getFrequency());
        }
        System.out.println();

        // Display full stats using the display component
        AutocompleteStatsDisplay display = new AutocompleteStatsDisplay(service);
        display.showStats();
    }

    // =======================================================================
    // DESIGN SUMMARY
    // =======================================================================

    private static void printDesignSummary() {
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("   SYSTEM DESIGN SUMMARY: SEARCH AUTOCOMPLETE");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  KEY DATA STRUCTURE:");
        System.out.println("    Trie (Prefix Tree) — stores words character by character");
        System.out.println("    - StandardTrie: O(L) insert, O(L+N) getSuggestions");
        System.out.println("    - CompressedTrie: same complexity, fewer nodes (memory)");
        System.out.println("    - TopKTrie: O(L*K) insert, O(1) getSuggestions (interview star!)");
        System.out.println();
        System.out.println("  KEY OPTIMIZATION (TopKTrie):");
        System.out.println("    Pre-compute top-K suggestions at EVERY node during insert.");
        System.out.println("    getSuggestions = just return the pre-computed list. No DFS!");
        System.out.println("    Trade-off: slower inserts for instant lookups.");
        System.out.println("    This is what interviewers want to hear.");
        System.out.println();
        System.out.println("  DESIGN PATTERNS USED:");
        System.out.println("    - Strategy: RankingStrategy (Frequency, TimeDecay, Personalized)");
        System.out.println("    - Decorator: PersonalizedRankingStrategy wraps base strategy");
        System.out.println("    - Facade: AutocompleteService hides subsystem complexity");
        System.out.println("    - Builder: SearchQuery, AutocompleteConfig");
        System.out.println("    - Factory: AppConfig (composition root)");
        System.out.println("    - Repository: QueryRepository abstracts data access");
        System.out.println("    - Chain of Responsibility: FilterStrategy chain");
        System.out.println();
        System.out.println("  PRODUCTION CONSIDERATIONS:");
        System.out.println("    - Trie sharding: partition by first char (26 shards for a-z)");
        System.out.println("    - Distributed cache: Redis/Memcached with prefix-based keys");
        System.out.println("    - Offline rebuild: periodic batch job rebuilds trie from query logs");
        System.out.println("    - Replication: read replicas for trie serving, write to primary");
        System.out.println("    - Data pipeline: Kafka -> Aggregator -> TrieBuilder -> Distribution");
        System.out.println("    - Zookeeper: coordinate trie version across cluster");
        System.out.println();
        System.out.println("  SCALE NUMBERS:");
        System.out.println("    - Google: 8.5B searches/day, ~100K queries/second");
        System.out.println("    - Trie size: ~5B unique queries, ~100GB in memory");
        System.out.println("    - Latency target: <100ms end-to-end (autocomplete needs <50ms)");
        System.out.println("    - Cache hit rate: 70-90% (Zipf distribution of queries)");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("   END OF SEARCH AUTOCOMPLETE DEMO");
        System.out.println(SEPARATOR);
    }

    // =======================================================================
    // Utility methods
    // =======================================================================

    /**
     * Format a list of suggestions as a compact string.
     */
    private static String formatSuggestions(List<Suggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%s(%.0f)", suggestions.get(i).getText(), suggestions.get(i).getScore()));
        }
        return sb.toString();
    }

    /**
     * Create a deep copy of suggestions (so ranking doesn't modify the originals).
     * WHY? Ranking strategies modify scores in-place. Without cloning, comparing
     * strategies would corrupt results.
     */
    private static List<Suggestion> cloneSuggestions(List<Suggestion> originals) {
        List<Suggestion> clones = new java.util.ArrayList<>();
        for (Suggestion s : originals) {
            clones.add(new Suggestion(s.getText(), s.getScore(), s.getSource()));
        }
        return clones;
    }
}
