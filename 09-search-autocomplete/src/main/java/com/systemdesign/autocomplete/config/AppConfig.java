package com.systemdesign.autocomplete.config;

import com.systemdesign.autocomplete.controller.AutocompleteController;
import com.systemdesign.autocomplete.model.AutocompleteConfig;
import com.systemdesign.autocomplete.repository.InMemoryQueryRepository;
import com.systemdesign.autocomplete.repository.QueryRepository;
import com.systemdesign.autocomplete.service.AutocompleteService;
import com.systemdesign.autocomplete.service.DataCollectionService;
import com.systemdesign.autocomplete.service.RankingService;
import com.systemdesign.autocomplete.service.TrieBuilderService;
import com.systemdesign.autocomplete.service.TrieService;
import com.systemdesign.autocomplete.store.InMemorySuggestionCache;
import com.systemdesign.autocomplete.store.SuggestionCache;
import com.systemdesign.autocomplete.strategy.filtering.FilterStrategy;
import com.systemdesign.autocomplete.strategy.filtering.ProfanityFilterStrategy;
import com.systemdesign.autocomplete.strategy.ranking.FrequencyRankingStrategy;
import com.systemdesign.autocomplete.strategy.ranking.PersonalizedRankingStrategy;
import com.systemdesign.autocomplete.strategy.ranking.RankingStrategy;
import com.systemdesign.autocomplete.strategy.ranking.TimeDecayRankingStrategy;
import com.systemdesign.autocomplete.trie.CompressedTrie;
import com.systemdesign.autocomplete.trie.StandardTrie;
import com.systemdesign.autocomplete.trie.TopKTrie;
import com.systemdesign.autocomplete.trie.Trie;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AppConfig — FACTORY / Composition Root for the entire autocomplete system.
 *
 * THIS IS THE ONLY PLACE WHERE "new ConcreteClass()" APPEARS.
 *
 * WHY centralize construction?
 * ----------------------------
 * In a framework-free Java application, somebody has to create the objects and wire
 * them together. Rather than scattering "new" calls throughout the codebase, we
 * concentrate them here. This is the "Poor Man's Dependency Injection" pattern.
 *
 * Benefits:
 *   1. Single place to see the entire dependency graph
 *   2. Easy to swap implementations (e.g., change TopKTrie → CompressedTrie)
 *   3. Configuration is centralized (AutocompleteConfig)
 *   4. Testing: create AppConfig with test doubles
 *
 * DEPENDENCY GRAPH:
 * =================
 *
 *   AutocompleteConfig (configuration)
 *        │
 *        ├──→ TopKTrie (data structure)
 *        │       │
 *        │       └──→ TrieService (thread-safe wrapper)
 *        │                │
 *        ├──→ InMemorySuggestionCache (caching)
 *        │       │
 *        ├──→ FrequencyRankingStrategy (or TimeDecay or Personalized)
 *        │       │
 *        │       └──→ RankingService (strategy holder)
 *        │                │
 *        ├──→ ProfanityFilterStrategy
 *        │       │
 *        ├──→ InMemoryQueryRepository
 *        │       │
 *        │       ├──→ DataCollectionService
 *        │       │       │
 *        │       └──→ TrieBuilderService
 *        │                │
 *        └──→ AutocompleteService (FACADE — holds all of the above)
 *                │
 *                └──→ AutocompleteController
 *
 * In Spring, this would be @Configuration with @Bean methods.
 * Here, each create*() method is effectively a @Bean factory.
 */
public class AppConfig {

    // -----------------------------------------------------------------------
    // Core configuration
    // -----------------------------------------------------------------------

    private final AutocompleteConfig config;

    // -----------------------------------------------------------------------
    // All components — created lazily or eagerly in build()
    // -----------------------------------------------------------------------

    private Trie trie;
    private TrieService trieService;
    private SuggestionCache cache;
    private RankingStrategy rankingStrategy;
    private RankingService rankingService;
    private List<FilterStrategy> filters;
    private ProfanityFilterStrategy profanityFilter;
    private QueryRepository queryRepository;
    private DataCollectionService dataCollectionService;
    private TrieBuilderService trieBuilderService;
    private AutocompleteService autocompleteService;
    private AutocompleteController controller;

    // Additional strategies kept for direct access in demos
    private FrequencyRankingStrategy frequencyRankingStrategy;
    private TimeDecayRankingStrategy timeDecayRankingStrategy;
    private PersonalizedRankingStrategy personalizedRankingStrategy;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Create AppConfig with custom configuration.
     */
    public AppConfig(AutocompleteConfig config) {
        this.config = config;
    }

    /**
     * Create AppConfig with default configuration.
     */
    public AppConfig() {
        this(AutocompleteConfig.defaultConfig());
    }

    // -----------------------------------------------------------------------
    // build() — Wire everything together
    // -----------------------------------------------------------------------

    /**
     * Build the entire dependency graph.
     * Call this ONCE at application startup.
     *
     * Order matters! Dependencies must be created before their dependents.
     * The order below follows the dependency graph bottom-up.
     *
     * @return this AppConfig (for method chaining)
     */
    public AppConfig build() {
        // 1. Data layer — no dependencies
        queryRepository = createQueryRepository();

        // 2. Trie — depends on config
        trie = createTrie();
        trieService = createTrieService(trie);

        // 3. Cache — depends on config
        cache = createCache();

        // 4. Ranking strategies — depends on config
        frequencyRankingStrategy = createFrequencyRankingStrategy();
        timeDecayRankingStrategy = createTimeDecayRankingStrategy();
        personalizedRankingStrategy = createPersonalizedRankingStrategy(timeDecayRankingStrategy);

        // Default ranking strategy: personalized (wraps time-decay)
        rankingStrategy = personalizedRankingStrategy;
        rankingService = createRankingService(rankingStrategy);

        // 5. Filters — no dependencies
        profanityFilter = createProfanityFilter();
        filters = createFilterChain(profanityFilter);

        // 6. Services — depends on above
        dataCollectionService = createDataCollectionService(queryRepository);
        trieBuilderService = createTrieBuilderService(queryRepository);

        // 7. Facade — depends on all services
        autocompleteService = createAutocompleteService(
                trieService, rankingService, filters, cache, dataCollectionService, config);

        // 8. Controller — depends on facade
        controller = createController(autocompleteService);

        return this;
    }

    // -----------------------------------------------------------------------
    // Factory methods — each creates ONE component
    // -----------------------------------------------------------------------

    /**
     * Create the primary Trie. TopKTrie is the default for production use.
     * Change this to StandardTrie or CompressedTrie for comparison demos.
     */
    private Trie createTrie() {
        // TopKTrie: O(1) getSuggestions — the interview-winning optimization
        return new TopKTrie(config.getTopKPerNode());
    }

    /**
     * Create a StandardTrie (for demo comparisons).
     */
    public StandardTrie createStandardTrie() {
        return new StandardTrie();
    }

    /**
     * Create a CompressedTrie (for demo comparisons).
     */
    public CompressedTrie createCompressedTrie() {
        return new CompressedTrie();
    }

    /**
     * Create a TopKTrie (for demo comparisons).
     */
    public TopKTrie createTopKTrie() {
        return new TopKTrie(config.getTopKPerNode());
    }

    private TrieService createTrieService(Trie trie) {
        return new TrieService(trie);
    }

    private SuggestionCache createCache() {
        return new InMemorySuggestionCache(config.getCacheSize(), config.getCacheTtlSeconds());
    }

    private FrequencyRankingStrategy createFrequencyRankingStrategy() {
        return new FrequencyRankingStrategy();
    }

    private TimeDecayRankingStrategy createTimeDecayRankingStrategy() {
        return new TimeDecayRankingStrategy(config.getDecayFactor());
    }

    private PersonalizedRankingStrategy createPersonalizedRankingStrategy(RankingStrategy base) {
        return new PersonalizedRankingStrategy(base);
    }

    private RankingService createRankingService(RankingStrategy strategy) {
        return new RankingService(strategy);
    }

    private ProfanityFilterStrategy createProfanityFilter() {
        // Default banned words for demo
        Set<String> bannedWords = new HashSet<>();
        bannedWords.add("badword");
        bannedWords.add("offensive");
        bannedWords.add("inappropriate");
        bannedWords.add("vulgar");
        return new ProfanityFilterStrategy(bannedWords);
    }

    private List<FilterStrategy> createFilterChain(FilterStrategy... strategies) {
        List<FilterStrategy> chain = new ArrayList<>();
        for (FilterStrategy strategy : strategies) {
            chain.add(strategy);
        }
        return chain;
    }

    private QueryRepository createQueryRepository() {
        return new InMemoryQueryRepository();
    }

    private DataCollectionService createDataCollectionService(QueryRepository repo) {
        return new DataCollectionService(repo);
    }

    private TrieBuilderService createTrieBuilderService(QueryRepository repo) {
        return new TrieBuilderService(repo, config.getTopKPerNode());
    }

    private AutocompleteService createAutocompleteService(
            TrieService trieService,
            RankingService rankingService,
            List<FilterStrategy> filters,
            SuggestionCache cache,
            DataCollectionService dataCollectionService,
            AutocompleteConfig config) {
        return new AutocompleteService(
                trieService, rankingService, filters, cache, dataCollectionService, config);
    }

    private AutocompleteController createController(AutocompleteService service) {
        return new AutocompleteController(service);
    }

    // -----------------------------------------------------------------------
    // Accessors — for the main app and tests
    // -----------------------------------------------------------------------

    public AutocompleteConfig getConfig() { return config; }
    public Trie getTrie() { return trie; }
    public TrieService getTrieService() { return trieService; }
    public SuggestionCache getCache() { return cache; }
    public RankingService getRankingService() { return rankingService; }
    public FrequencyRankingStrategy getFrequencyRankingStrategy() { return frequencyRankingStrategy; }
    public TimeDecayRankingStrategy getTimeDecayRankingStrategy() { return timeDecayRankingStrategy; }
    public PersonalizedRankingStrategy getPersonalizedRankingStrategy() { return personalizedRankingStrategy; }
    public ProfanityFilterStrategy getProfanityFilter() { return profanityFilter; }
    public List<FilterStrategy> getFilters() { return filters; }
    public QueryRepository getQueryRepository() { return queryRepository; }
    public DataCollectionService getDataCollectionService() { return dataCollectionService; }
    public TrieBuilderService getTrieBuilderService() { return trieBuilderService; }
    public AutocompleteService getAutocompleteService() { return autocompleteService; }
    public AutocompleteController getController() { return controller; }
}
