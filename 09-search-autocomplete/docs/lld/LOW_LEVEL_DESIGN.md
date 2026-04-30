# Low-Level Design: Search Autocomplete (Typeahead) System

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Trie Data Structures, Prefix Search, Ranking Strategies, Caching, Concurrency
> This is a top-tier system design question. It tests data structures (Trie, Compressed Trie, TopK-Trie), algorithm design (DFS, prefix search, time-decay ranking), caching (LRU for prefix results), and design pattern mastery (Strategy, Facade, Factory).

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Service Layer Design](#7-service-layer-design)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [SOLID Principles Applied](#9-solid-principles-applied)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: TrieNode (children map, isEndOfWord, frequency, pre-computed topK), SearchQuery (text, frequency, timestamp, userId), Suggestion (text, score, source enum), SearchContext (userId, language, location, timestamp for personalization), AutocompleteConfig (maxResults, maxPrefixLength, minFrequency, decayFactor). |
| **Trie** | `trie/` | Core data structures: Trie interface, StandardTrie (HashMap children, DFS-based getSuggestions), CompressedTrie (radix tree with edge labels, single-child chain merging), TopKTrie (pre-computed top-K at every node for O(1) lookup). |
| **Strategy (Ranking)** | `strategy/ranking/` | Pluggable ranking algorithms: FrequencyRankingStrategy (raw count sort), TimeDecayRankingStrategy (exponential decay: score = freq * e^(-lambda * age)), PersonalizedRankingStrategy (boost by user history and context). Strategy pattern -- swap ranking algorithm without touching service logic. |
| **Strategy (Filtering)** | `strategy/filtering/` | Pluggable content filtering: ProfanityFilterStrategy (remove offensive suggestions from results). |
| **Service** | `service/` | Business logic: AutocompleteService (Facade -- orchestrates trie lookup, caching, ranking, filtering), TrieService (manages Trie operations, thread-safe reads), RankingService (applies ranking strategy), DataCollectionService (logs queries, aggregates frequencies), TrieBuilderService (builds/rebuilds Trie from aggregated data). |
| **Store** | `store/` | Caching layer: SuggestionCache interface, InMemorySuggestionCache (LRU cache backed by LinkedHashMap with removeEldestEntry for prefix -> top-K results). |
| **Repository** | `repository/` | Data access layer: QueryRepository interface with InMemoryQueryRepository (ConcurrentHashMap-backed store for search query history). |
| **Controller** | `controller/` | REST-like API entry point: AutocompleteController maps prefix requests to AutocompleteService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | AutocompleteStatsDisplay: trie size, cache hit/miss ratio, top queries, latency stats. |
| **Exception** | `exception/` | Domain exceptions: AutocompleteException (base), TrieCapacityException (trie at max nodes). |

### Why Search Autocomplete Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you know Trie internals (not just "use a prefix tree")?     --> Data Structures
  2. Can you explain CompressedTrie/Radix Tree optimizations?        --> Space Optimization
  3. Do you pre-compute TopK at each node for O(1) lookup?          --> Algorithm Design
  4. Is ranking pluggable (frequency vs time-decay vs personalized)? --> Strategy Pattern
  5. Do you cache prefix results in an LRU cache?                   --> Caching Layer
  6. Is the Trie thread-safe for concurrent reads?                  --> Concurrency
  7. Can you add a new ranking strategy without changing the service?--> Open-Closed
  8. Is your AutocompleteService a clean Facade?                    --> Facade Pattern
  9. Do you handle time-decay for trending vs stale queries?        --> Real-World Modeling
  10. Can you explain the insert vs lookup complexity trade-offs?    --> Complexity Analysis
```

---

## 2. Package Structure

```
com.systemdesign.autocomplete
│
├── model/
│   ├── TrieNode.java           -- children map, isEndOfWord, weight/frequency, topSuggestions list
│   ├── SearchQuery.java        -- query text, frequency, timestamp, userId
│   ├── Suggestion.java         -- text, score, source (TRENDING/POPULAR/PERSONALIZED)
│   ├── SearchContext.java      -- userId, language, location, timestamp (for personalization)
│   └── AutocompleteConfig.java -- maxResults, maxPrefixLength, minFrequency, decayFactor
│
├── trie/
│   ├── Trie.java               -- interface: insert, search, getSuggestions, delete, size
│   ├── StandardTrie.java       -- basic Trie with HashMap children
│   ├── CompressedTrie.java     -- radix tree: merge single-child chains, edge labels
│   └── TopKTrie.java           -- each node stores pre-computed top-K suggestions (O(1) lookup!)
│
├── strategy/
│   ├── ranking/
│   │   ├── RankingStrategy.java           -- interface: rank(List<Suggestion>, SearchContext) -> List<Suggestion>
│   │   ├── FrequencyRankingStrategy.java  -- sort by raw frequency count
│   │   ├── TimeDecayRankingStrategy.java  -- exponential decay: score = freq * e^(-lambda * age)
│   │   └── PersonalizedRankingStrategy.java -- boost based on user history and context
│   │
│   └── filtering/
│       ├── FilterStrategy.java            -- interface: filter(List<Suggestion>) -> List<Suggestion>
│       └── ProfanityFilterStrategy.java   -- remove offensive suggestions
│
├── service/
│   ├── AutocompleteService.java   -- FACADE: prefix -> ranked suggestions
│   ├── TrieService.java          -- manages Trie operations, thread-safe reads
│   ├── RankingService.java       -- applies ranking strategy to raw matches
│   ├── DataCollectionService.java -- logs queries, aggregates frequencies
│   └── TrieBuilderService.java   -- builds/rebuilds Trie from aggregated data
│
├── store/
│   ├── SuggestionCache.java          -- interface: get(prefix), put(prefix, suggestions), invalidate(prefix)
│   └── InMemorySuggestionCache.java  -- LRU cache for prefix -> top-K results
│
├── repository/
│   ├── QueryRepository.java          -- interface
│   └── InMemoryQueryRepository.java  -- ConcurrentHashMap-backed
│
├── controller/
│   └── AutocompleteController.java   -- REST-like entry point
│
├── config/
│   └── AppConfig.java                -- factory wiring
│
├── display/
│   └── AutocompleteStatsDisplay.java -- formatted stats
│
├── exception/
│   ├── AutocompleteException.java    -- base exception
│   └── TrieCapacityException.java    -- trie at max capacity
│
└── SearchAutocompleteApp.java        -- Main demo: wires everything, runs autocomplete scenarios
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       TRIE HIERARCHY (Strategy Pattern)                          ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  Trie                               |
    |-----------------------------------------------------------|
    | + insert(word: String, frequency: long): void             |
    | + search(word: String): boolean                           |
    | + getSuggestions(prefix: String, k: int): List<Suggestion>|
    | + delete(word: String): boolean                           |
    | + size(): int                                             |
    | + getTrieType(): String                                   |
    +-----------------------------------------------------------+
          ^                    ^                    ^
          |                    |                    |
    implements           implements           implements
          |                    |                    |
    +-----+----------+ +------+-----------+ +------+------------+
    | StandardTrie   | | CompressedTrie   | | TopKTrie          |
    |----------------| |------------------| |-------------------|
    | -root: TrieNode| | -root: TrieNode  | | -root: TrieNode   |
    | -nodeCount: int| | -nodeCount: int   | | -nodeCount: int   |
    |                | | -edge labels:     | | -k: int           |
    |                | |   String (not     | |                   |
    |                | |   single char)    | |                   |
    |----------------| |------------------| |-------------------|
    | +insert: build | | +insert: merge   | | +insert: update   |
    |  path char by  | |  single-child    | |  topK at EVERY    |
    |  char, O(L)    | |  chains into     | |  ancestor node,   |
    | +getSuggestions:| |  compressed edge | |  O(L*K) insert    |
    |  DFS from      | |  labels          | | +getSuggestions:   |
    |  prefix node   | | +getSuggestions:  | |  return pre-      |
    |  O(N) worst    | |  traverse edges, | |  computed list,    |
    |  case          | |  DFS from match  | |  O(1) lookup!     |
    +----------------+ +------------------+ +-------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       RANKING STRATEGY HIERARCHY (Strategy Pattern)              ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  RankingStrategy                    |
    |-----------------------------------------------------------|
    | + rank(suggestions: List<Suggestion>,                     |
    |        context: SearchContext): List<Suggestion>           |
    | + getStrategyName(): String                               |
    +-----------------------------------------------------------+
          ^                    ^                    ^
          |                    |                    |
    implements           implements           implements
          |                    |                    |
    +-----+----------+ +------+-----------+ +------+------------------+
    | Frequency      | | TimeDecay        | | Personalized            |
    | Ranking        | | Ranking          | | Ranking                 |
    |   Strategy     | |   Strategy       | |   Strategy              |
    |----------------| |------------------| |-------------------------|
    |                | | -decayFactor:    | | -userHistoryWeight:     |
    |                | |   double (lambda)| |   double                |
    |                | |                  | | -queryRepo:             |
    |                | |                  | |   QueryRepository       |
    |----------------| |------------------| |-------------------------|
    | +rank: sort by | | +rank: score =   | | +rank: base score +    |
    |  raw frequency | |  freq * e^(      | |  boost if user has     |
    |  descending    | |  -lambda * age)  | |  searched similar      |
    |  Simple, fast  | |  Favors recent   | |  terms before          |
    +----------------+ +------------------+ +-------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       FILTER STRATEGY HIERARCHY                                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  FilterStrategy                     |
    |-----------------------------------------------------------|
    | + filter(suggestions: List<Suggestion>): List<Suggestion>  |
    | + getFilterName(): String                                  |
    +-----------------------------------------------------------+
          ^
          |
    implements
          |
    +-----+---------------------+
    | ProfanityFilterStrategy   |
    |---------------------------|
    | -blockedWords: Set<String>|
    |---------------------------|
    | +filter: remove any       |
    |  suggestion whose text    |
    |  contains a blocked word  |
    +---------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       CACHE LAYER                                                ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  SuggestionCache                    |
    |-----------------------------------------------------------|
    | + get(prefix: String): Optional<List<Suggestion>>         |
    | + put(prefix: String, suggestions: List<Suggestion>): void|
    | + invalidate(prefix: String): void                        |
    | + invalidateAll(): void                                   |
    | + size(): int                                             |
    | + hitCount(): long                                        |
    | + missCount(): long                                       |
    +-----------------------------------------------------------+
          ^
          |
    implements
          |
    +-----+---------------------------+
    | InMemorySuggestionCache         |
    |---------------------------------|
    | -cache: LinkedHashMap           |
    |   <String, List<Suggestion>>    |
    |   (access-order, LRU eviction)  |
    | -maxSize: int                   |
    | -hits: AtomicLong               |
    | -misses: AtomicLong             |
    |---------------------------------|
    | +get: lookup prefix, return     |
    |  cached suggestions or empty    |
    | +put: store prefix results,     |
    |  LRU evict if over capacity     |
    | +invalidate: remove by prefix   |
    +---------------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       SERVICE LAYER (Facade + Dependencies)                      ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |     AutocompleteService  <<Facade>>                       |
    |-----------------------------------------------------------|
    | - trieService: TrieService                                |
    | - rankingService: RankingService                          |
    | - cache: SuggestionCache           [interface]            |
    | - filterStrategies: List<FilterStrategy> [interface]      |
    | - dataCollectionService: DataCollectionService            |
    | - config: AutocompleteConfig                              |
    | - queryCount: AtomicLong                                  |
    |-----------------------------------------------------------|
    | + getSuggestions(prefix: String,                           |
    |     context: SearchContext): List<Suggestion>              |
    | + recordQuery(query: SearchQuery): void                   |
    | + rebuildTrie(): void                                     |
    | + getStats(): AutocompleteStats                           |
    +-----------------------------------------------------------+
         |           |               |              |
         | uses      | uses          | uses         | uses
         v           v               v              v
    TrieService  RankingService  Suggestion    DataCollection
                                 Cache         Service

    +-----------------------------------------------------------+
    |           TrieService                                      |
    |-----------------------------------------------------------|
    | - trie: Trie                               [interface]     |
    | - readWriteLock: ReadWriteLock                              |
    |-----------------------------------------------------------|
    | + insert(word: String, frequency: long): void              |
    | + getSuggestions(prefix: String, k: int): List<Suggestion> |
    | + delete(word: String): boolean                            |
    | + search(word: String): boolean                            |
    | + size(): int                                              |
    | + replaceTrie(newTrie: Trie): void                         |
    +-----------------------------------------------------------+
         |
         | delegates to (under lock)
         v
    Trie  (StandardTrie, CompressedTrie, or TopKTrie)

    +-----------------------------------------------------------+
    |           RankingService                                   |
    |-----------------------------------------------------------|
    | - strategy: RankingStrategy            [interface]          |
    |-----------------------------------------------------------|
    | + rank(suggestions: List<Suggestion>,                      |
    |        context: SearchContext): List<Suggestion>            |
    | + setStrategy(strategy: RankingStrategy): void             |
    | + getStrategyName(): String                                |
    +-----------------------------------------------------------+
         |
         | delegates to
         v
    RankingStrategy  (Frequency, TimeDecay, or Personalized)

    +-----------------------------------------------------------+
    |           DataCollectionService                             |
    |-----------------------------------------------------------|
    | - queryRepository: QueryRepository     [interface]          |
    | - aggregatedFrequencies: ConcurrentHashMap<String, Long>   |
    |-----------------------------------------------------------|
    | + recordQuery(query: SearchQuery): void                    |
    | + getAggregatedFrequencies(): Map<String, Long>            |
    | + getTopQueries(k: int): List<SearchQuery>                 |
    | + clearAggregation(): void                                 |
    +-----------------------------------------------------------+

    +-----------------------------------------------------------+
    |           TrieBuilderService                               |
    |-----------------------------------------------------------|
    | - dataCollectionService: DataCollectionService              |
    | - config: AutocompleteConfig                               |
    |-----------------------------------------------------------|
    | + buildTrie(trieType: String): Trie                        |
    | + rebuildFromAggregatedData(trieType: String): Trie        |
    +-----------------------------------------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       REPOSITORY LAYER                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  QueryRepository                    |
    |-----------------------------------------------------------|
    | + save(query: SearchQuery): void                          |
    | + findByText(text: String): Optional<SearchQuery>         |
    | + findByUserId(userId: String): List<SearchQuery>         |
    | + findTopByFrequency(limit: int): List<SearchQuery>       |
    | + findAll(): List<SearchQuery>                            |
    | + count(): int                                            |
    +-----------------------------------------------------------+
          ^
          | implements
    +-----+------------------------+
    | InMemoryQueryRepository      |
    |------------------------------|
    | -store: ConcurrentHashMap    |
    |   <String, SearchQuery>      |
    |------------------------------|
    | Thread-safe in-memory store  |
    | for search query history     |
    +------------------------------+

RELATIONSHIP SUMMARY
====================
AutocompleteController    --uses-->  AutocompleteService (Facade)
AutocompleteService       --uses-->  TrieService
AutocompleteService       --uses-->  RankingService
AutocompleteService       --uses-->  SuggestionCache (interface)
AutocompleteService       --uses-->  List<FilterStrategy> (interface)
AutocompleteService       --uses-->  DataCollectionService
AutocompleteService       --uses-->  AutocompleteConfig
TrieService               --uses-->  Trie (interface) + ReadWriteLock
RankingService            --uses-->  RankingStrategy (interface)
DataCollectionService     --uses-->  QueryRepository (interface)
TrieBuilderService        --uses-->  DataCollectionService + AutocompleteConfig
StandardTrie              --uses-->  TrieNode (HashMap<Character, TrieNode>)
CompressedTrie            --uses-->  TrieNode (edge labels: String, not char)
TopKTrie                  --uses-->  TrieNode (pre-computed List<Suggestion> at every node)
InMemorySuggestionCache   --uses-->  LinkedHashMap (access-order, LRU eviction)
AppConfig                 --creates--> all objects, injects via constructors
AutocompleteStatsDisplay  --reads-->   AutocompleteService.getStats()
```

---

## 4. Entity Design

> This section defines every model class. Each is designed to be immutable where possible and carries metadata needed for ranking, filtering, and caching decisions.

### 4.1 TrieNode (Core Data Structure)

> **The fundamental building block.** Every Trie variant (Standard, Compressed, TopK) uses TrieNode. The `topSuggestions` list is what makes TopKTrie achieve O(1) prefix lookup -- pre-computed at insert time.

```java
/**
 * A single node in the Trie data structure.
 *
 * WHY children is a HashMap<Character, TrieNode> instead of TrieNode[26]?
 *   - Array[26] assumes only lowercase a-z. Real search queries contain
 *     digits, spaces, special characters, unicode.
 *   - HashMap adapts to actual character set used. Memory proportional
 *     to actual children, not a fixed 26/128/256 slots.
 *   - Trade-off: HashMap has ~40 bytes overhead per node vs array's fixed cost.
 *     For sparse tries (most nodes have 1-3 children), HashMap wins on memory.
 *     For dense tries (every node has 20+ children), array wins.
 *
 * WHY topSuggestions is stored at each node?
 *   - Without it: getSuggestions("fac") requires DFS from "fac" node,
 *     visiting potentially thousands of descendants -> O(N) per query.
 *   - With it: getSuggestions("fac") returns the pre-computed list -> O(1).
 *   - Trade-off: insert is O(L * K) instead of O(L), where L = word length,
 *     K = topK size. But queries vastly outnumber inserts in autocomplete.
 *
 * INTERVIEW TIP: This is the key insight interviewers look for. Most
 * candidates implement DFS-based lookup (O(N)). Pre-computing topK at
 * each node is what Google/Facebook actually do.
 */
public class TrieNode {

    private final Map<Character, TrieNode> children;
    private boolean isEndOfWord;
    private long frequency;                   // how many times this complete word was searched
    private final List<Suggestion> topSuggestions;  // pre-computed top-K (used by TopKTrie)
    private String edgeLabel;                 // used by CompressedTrie (null for StandardTrie)

    public TrieNode() {
        this.children = new HashMap<>();
        this.isEndOfWord = false;
        this.frequency = 0;
        this.topSuggestions = new ArrayList<>();
        this.edgeLabel = null;
    }

    /**
     * Constructor for CompressedTrie nodes that carry edge labels.
     *
     * @param edgeLabel the compressed edge label (e.g., "ebook" instead of 5 single-char nodes)
     */
    public TrieNode(String edgeLabel) {
        this();
        this.edgeLabel = edgeLabel;
    }

    // --- Children operations ---

    /**
     * Gets or creates a child node for the given character.
     *
     * Called by StandardTrie.insert() and TopKTrie.insert() as they walk
     * the Trie character by character.
     */
    public TrieNode getOrCreateChild(char c) {
        return children.computeIfAbsent(c, k -> new TrieNode());
    }

    public TrieNode getChild(char c) {
        return children.get(c);
    }

    public boolean hasChild(char c) {
        return children.containsKey(c);
    }

    public Map<Character, TrieNode> getChildren() {
        return children;
    }

    public boolean hasNoChildren() {
        return children.isEmpty();
    }

    // --- End-of-word and frequency ---

    public boolean isEndOfWord()              { return isEndOfWord; }
    public void setEndOfWord(boolean end)     { this.isEndOfWord = end; }
    public long getFrequency()                { return frequency; }
    public void setFrequency(long frequency)  { this.frequency = frequency; }

    public void incrementFrequency(long delta) {
        this.frequency += delta;
    }

    // --- TopK suggestions (pre-computed) ---

    /**
     * Updates the pre-computed top-K suggestion list at this node.
     *
     * Called by TopKTrie.insert() at EVERY ancestor node along the insert path.
     * This is the O(L*K) insert cost that buys O(1) lookup.
     *
     * Algorithm:
     *   1. Check if this suggestion already exists in the list (update score)
     *   2. If not, add it
     *   3. Sort by score descending
     *   4. Trim to K elements
     *
     * @param suggestion the suggestion to add/update
     * @param maxK       maximum suggestions to keep
     */
    public void updateTopSuggestions(Suggestion suggestion, int maxK) {
        // Remove existing entry for same text (if score changed)
        topSuggestions.removeIf(s -> s.getText().equals(suggestion.getText()));

        // Add new/updated suggestion
        topSuggestions.add(suggestion);

        // Sort by score descending
        topSuggestions.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        // Trim to maxK
        while (topSuggestions.size() > maxK) {
            topSuggestions.remove(topSuggestions.size() - 1);
        }
    }

    public List<Suggestion> getTopSuggestions() {
        return Collections.unmodifiableList(topSuggestions);
    }

    // --- Edge label (CompressedTrie) ---

    public String getEdgeLabel()                   { return edgeLabel; }
    public void setEdgeLabel(String edgeLabel)     { this.edgeLabel = edgeLabel; }

    @Override
    public String toString() {
        return String.format("TrieNode[children=%d, isEnd=%s, freq=%d, topK=%d, edge=%s]",
                children.size(), isEndOfWord, frequency, topSuggestions.size(), edgeLabel);
    }
}
```

### 4.2 SearchQuery (Query with Metadata)

> Represents a search query with all the metadata needed for frequency aggregation, time-decay ranking, and personalization.

```java
/**
 * Represents a search query with metadata.
 *
 * WHY not just store the query as a plain String?
 *   - Frequency: we need to count how many times "facebook" was searched
 *   - Timestamp: TimeDecayRankingStrategy needs to know WHEN it was last searched
 *   - UserId: PersonalizedRankingStrategy needs to boost user's own past queries
 *
 * This is what the DataCollectionService stores when a user completes a search.
 * Not every keystroke generates a SearchQuery -- only completed searches.
 */
public class SearchQuery {

    private final String text;                // the search query text
    private long frequency;                   // how many times this exact query was searched
    private long lastSearchedAt;              // epoch millis of most recent search
    private final String userId;              // who searched it (null for anonymous)
    private final long createdAt;             // when this query was first seen

    public SearchQuery(String text, long frequency, String userId) {
        Objects.requireNonNull(text, "query text must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("query text must not be blank");
        }
        this.text = text.toLowerCase().trim();
        this.frequency = frequency;
        this.userId = userId;
        this.createdAt = System.currentTimeMillis();
        this.lastSearchedAt = this.createdAt;
    }

    /** Convenience constructor for anonymous queries. */
    public SearchQuery(String text, long frequency) {
        this(text, frequency, null);
    }

    /** Records another search of this query. Bumps frequency, updates timestamp. */
    public void recordSearch() {
        this.frequency++;
        this.lastSearchedAt = System.currentTimeMillis();
    }

    /** Returns the age of this query in hours (for time-decay ranking). */
    public double getAgeInHours() {
        return (System.currentTimeMillis() - lastSearchedAt) / (1000.0 * 60 * 60);
    }

    // --- Getters ---
    public String getText()            { return text; }
    public long getFrequency()         { return frequency; }
    public long getLastSearchedAt()    { return lastSearchedAt; }
    public String getUserId()          { return userId; }
    public long getCreatedAt()         { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchQuery that = (SearchQuery) o;
        return text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return String.format("SearchQuery[text='%s', freq=%d, age=%.1fh, user=%s]",
                text, frequency, getAgeInHours(), userId);
    }
}
```

### 4.3 Suggestion (Result Object)

> The object returned to the caller. Carries the suggestion text, its computed score, and the source/reason why it was suggested.

```java
/**
 * Represents a single autocomplete suggestion returned to the user.
 *
 * WHY a separate class instead of returning raw Strings?
 *   - Score: the ranking service computes a score; we need to carry it
 *   - Source: tells the UI WHY this was suggested (trending, popular, personalized)
 *   - Extensible: can add metadata like category, thumbnail URL, etc.
 */
public class Suggestion {

    /**
     * The reason this suggestion was surfaced.
     * Used by the UI to display badges (e.g., "Trending" tag).
     */
    public enum Source {
        POPULAR,        // high absolute frequency
        TRENDING,       // high recent frequency (time-decay boosted)
        PERSONALIZED,   // boosted because of user history
        EXACT_MATCH     // prefix is an exact completed query
    }

    private final String text;
    private double score;            // computed by RankingStrategy, mutable for re-ranking
    private final Source source;
    private final long frequency;    // raw frequency count (before ranking)

    public Suggestion(String text, double score, Source source, long frequency) {
        Objects.requireNonNull(text, "suggestion text must not be null");
        this.text = text;
        this.score = score;
        this.source = source;
        this.frequency = frequency;
    }

    /** Convenience constructor defaulting to POPULAR source. */
    public Suggestion(String text, long frequency) {
        this(text, (double) frequency, Source.POPULAR, frequency);
    }

    // --- Getters and score mutation ---
    public String getText()        { return text; }
    public double getScore()       { return score; }
    public void setScore(double s) { this.score = s; }
    public Source getSource()      { return source; }
    public long getFrequency()     { return frequency; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return text.equals(((Suggestion) o).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return String.format("Suggestion['%s', score=%.2f, source=%s, freq=%d]",
                text, score, source, frequency);
    }
}
```

### 4.4 SearchContext (Personalization Context)

```java
/**
 * Carries contextual information for personalizing suggestions.
 *
 * Passed from the controller to the ranking strategy so that
 * PersonalizedRankingStrategy can boost user-relevant results.
 *
 * INTERVIEW NOTE: Most candidates forget about personalization.
 * Mention this to stand out: "In production, Google personalizes
 * autocomplete based on your search history, location, and language."
 */
public class SearchContext {

    private final String userId;       // null for anonymous users
    private final String language;     // "en", "es", "fr", etc.
    private final String location;     // "US", "IN", "UK", etc.
    private final long timestamp;      // when this request was made

    public SearchContext(String userId, String language, String location) {
        this.userId = userId;
        this.language = language != null ? language : "en";
        this.location = location != null ? location : "US";
        this.timestamp = System.currentTimeMillis();
    }

    /** Anonymous context with defaults. */
    public static SearchContext anonymous() {
        return new SearchContext(null, "en", "US");
    }

    public boolean isAuthenticated() {
        return userId != null && !userId.isBlank();
    }

    // --- Getters ---
    public String getUserId()    { return userId; }
    public String getLanguage()  { return language; }
    public String getLocation()  { return location; }
    public long getTimestamp()   { return timestamp; }

    @Override
    public String toString() {
        return String.format("SearchContext[user=%s, lang=%s, loc=%s]",
                userId, language, location);
    }
}
```

### 4.5 AutocompleteConfig (Configuration)

```java
/**
 * Central configuration for the autocomplete system.
 *
 * WHY a config object instead of scattered constants?
 *   - Single source of truth for all tuning parameters
 *   - Easy to pass through constructor injection
 *   - Easy to create different configs for testing
 */
public class AutocompleteConfig {

    private final int maxResults;          // max suggestions per prefix (default: 10)
    private final int maxPrefixLength;     // ignore prefixes longer than this (default: 50)
    private final long minFrequency;       // ignore queries searched fewer than N times (default: 2)
    private final double decayFactor;      // lambda for time-decay ranking (default: 0.01)
    private final int cacheMaxSize;        // LRU cache capacity (default: 10000)
    private final int topKPerNode;         // pre-computed top-K at each TrieNode (default: 10)
    private final int maxTrieNodes;        // maximum nodes in the Trie (default: 1_000_000)

    private AutocompleteConfig(Builder builder) {
        this.maxResults = builder.maxResults;
        this.maxPrefixLength = builder.maxPrefixLength;
        this.minFrequency = builder.minFrequency;
        this.decayFactor = builder.decayFactor;
        this.cacheMaxSize = builder.cacheMaxSize;
        this.topKPerNode = builder.topKPerNode;
        this.maxTrieNodes = builder.maxTrieNodes;
    }

    /** Returns a config with sensible defaults. */
    public static AutocompleteConfig defaults() {
        return new Builder().build();
    }

    // --- Getters ---
    public int getMaxResults()       { return maxResults; }
    public int getMaxPrefixLength()  { return maxPrefixLength; }
    public long getMinFrequency()    { return minFrequency; }
    public double getDecayFactor()   { return decayFactor; }
    public int getCacheMaxSize()     { return cacheMaxSize; }
    public int getTopKPerNode()      { return topKPerNode; }
    public int getMaxTrieNodes()     { return maxTrieNodes; }

    @Override
    public String toString() {
        return String.format(
            "AutocompleteConfig[maxResults=%d, maxPrefix=%d, minFreq=%d, decay=%.4f, " +
            "cacheSize=%d, topK=%d, maxNodes=%d]",
            maxResults, maxPrefixLength, minFrequency, decayFactor,
            cacheMaxSize, topKPerNode, maxTrieNodes);
    }

    /**
     * Builder pattern: AutocompleteConfig has 7 parameters -- too many for a constructor.
     * Builder lets you set only what you care about and use defaults for the rest.
     */
    public static class Builder {
        private int maxResults = 10;
        private int maxPrefixLength = 50;
        private long minFrequency = 2;
        private double decayFactor = 0.01;
        private int cacheMaxSize = 10_000;
        private int topKPerNode = 10;
        private int maxTrieNodes = 1_000_000;

        public Builder maxResults(int v)       { this.maxResults = v; return this; }
        public Builder maxPrefixLength(int v)  { this.maxPrefixLength = v; return this; }
        public Builder minFrequency(long v)    { this.minFrequency = v; return this; }
        public Builder decayFactor(double v)   { this.decayFactor = v; return this; }
        public Builder cacheMaxSize(int v)     { this.cacheMaxSize = v; return this; }
        public Builder topKPerNode(int v)      { this.topKPerNode = v; return this; }
        public Builder maxTrieNodes(int v)     { this.maxTrieNodes = v; return this; }

        public AutocompleteConfig build() {
            return new AutocompleteConfig(this);
        }
    }
}
```

---

## 5. Interface Contracts

> Every major component is defined as an interface first. This is the foundation of SOLID design -- code to interfaces, inject implementations.

### 5.1 Trie Interface

```java
/**
 * Contract for all Trie implementations.
 *
 * Three implementations with different trade-offs:
 *
 *   ┌──────────────────────┬───────────────┬───────────────┬───────────────┐
 *   │ Operation            │ StandardTrie  │ CompressedTrie│ TopKTrie      │
 *   ├──────────────────────┼───────────────┼───────────────┼───────────────┤
 *   │ insert(word)         │ O(L)          │ O(L) amort.   │ O(L * K)      │
 *   │ search(word)         │ O(L)          │ O(L)          │ O(L)          │
 *   │ getSuggestions(pre,k)│ O(N) DFS      │ O(N) DFS      │ O(L) + O(1)  │
 *   │ delete(word)         │ O(L)          │ O(L) + merge  │ O(L * K)      │
 *   │ Space                │ O(ALPHABET*N) │ O(N) compact  │ O(N*K) + trie │
 *   └──────────────────────┴───────────────┴───────────────┴───────────────┘
 *
 *   L = word length, N = total words in trie, K = topK per node
 *
 *   INTERVIEW INSIGHT:
 *     - StandardTrie: easiest to implement, fine for small datasets
 *     - CompressedTrie: saves space (important when Trie is in memory)
 *     - TopKTrie: production choice. O(1) lookup is essential when
 *       serving millions of QPS. The O(L*K) insert cost is acceptable
 *       because rebuilds happen offline (batch every few hours).
 */
public interface Trie {

    /**
     * Inserts a word with its frequency into the Trie.
     *
     * @param word      the search query to insert (case-insensitive)
     * @param frequency how many times this query was searched
     */
    void insert(String word, long frequency);

    /**
     * Checks if an exact word exists in the Trie.
     *
     * @return true if the word was inserted as a complete entry
     */
    boolean search(String word);

    /**
     * Returns top-K suggestions for the given prefix.
     *
     * This is the HOT PATH -- called on every keystroke.
     * Performance here is critical.
     *
     * @param prefix the prefix typed so far (e.g., "fac")
     * @param k      max number of suggestions to return
     * @return ranked list of suggestions, may be fewer than k
     */
    List<Suggestion> getSuggestions(String prefix, int k);

    /**
     * Deletes a word from the Trie (e.g., remove offensive content).
     *
     * @return true if the word existed and was removed
     */
    boolean delete(String word);

    /** Returns total number of complete words stored. */
    int size();

    /** Returns the implementation type for logging/stats. */
    String getTrieType();
}
```

### 5.2 RankingStrategy Interface

```java
/**
 * Contract for ranking autocomplete suggestions.
 *
 * The raw suggestions from the Trie are ordered by frequency.
 * The RankingStrategy re-ranks them based on additional signals:
 *   - Time decay (recent queries score higher)
 *   - Personalization (user's own past queries score higher)
 *   - Trending (queries with sudden frequency spikes score higher)
 *
 * Strategy pattern: AutocompleteService holds a RankingStrategy reference.
 * Swapping FrequencyRanking for TimeDecayRanking requires ZERO changes
 * to AutocompleteService.
 */
public interface RankingStrategy {

    /**
     * Re-ranks suggestions based on the strategy's algorithm.
     *
     * @param suggestions raw suggestions from the Trie (ordered by frequency)
     * @param context     user context for personalization (may be anonymous)
     * @return re-ranked list (same elements, different order)
     */
    List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context);

    /** Returns the strategy name for logging/stats. */
    String getStrategyName();
}
```

### 5.3 FilterStrategy Interface

```java
/**
 * Contract for filtering autocomplete suggestions.
 *
 * Applied AFTER ranking. Removes suggestions that should not be shown
 * (profanity, banned content, etc.).
 *
 * WHY a separate interface from RankingStrategy?
 *   - SRP: ranking changes ORDER, filtering changes CONTENT
 *   - Multiple filters can be chained (profanity + spam + NSFW)
 *   - A filter never changes scores or order -- it only removes
 */
public interface FilterStrategy {

    /**
     * Filters suggestions, removing those that fail the filter criteria.
     *
     * @param suggestions the ranked suggestions
     * @return filtered list (subset of input, same order)
     */
    List<Suggestion> filter(List<Suggestion> suggestions);

    /** Returns the filter name for logging. */
    String getFilterName();
}
```

### 5.4 SuggestionCache Interface

```java
/**
 * Contract for caching prefix -> suggestions mappings.
 *
 * WHY cache autocomplete results?
 *   - Millions of users type the same popular prefixes ("fac", "goo", "ama")
 *   - Even with TopKTrie's O(1) lookup, the ranking + filtering pipeline
 *     adds overhead. Caching the final result skips ALL of it.
 *   - Cache hit ratio for autocomplete is very high (Zipf distribution:
 *     a small number of prefixes account for most queries).
 *
 * Invalidation strategy:
 *   - On Trie rebuild: invalidateAll() (Trie data changed)
 *   - On query record: invalidate(prefix) for affected prefixes
 *   - TTL-based: entries auto-expire (not implemented here, but production would)
 */
public interface SuggestionCache {

    Optional<List<Suggestion>> get(String prefix);

    void put(String prefix, List<Suggestion> suggestions);

    void invalidate(String prefix);

    void invalidateAll();

    int size();

    long hitCount();

    long missCount();
}
```

### 5.5 QueryRepository Interface

```java
/**
 * Data access layer for search query history.
 *
 * Abstracts storage so the service layer doesn't know if queries
 * are stored in memory, a database, or a distributed log.
 */
public interface QueryRepository {

    void save(SearchQuery query);

    Optional<SearchQuery> findByText(String text);

    List<SearchQuery> findByUserId(String userId);

    List<SearchQuery> findTopByFrequency(int limit);

    List<SearchQuery> findAll();

    int count();
}
```

---

## 6. Strategy Implementations

> This is the heart of the autocomplete LLD. The Trie implementations demonstrate advanced data structures (CompressedTrie, TopKTrie), and the ranking strategies demonstrate real-world relevance scoring (time-decay, personalization).

### 6.0 The Anti-Pattern: Why Strategy Pattern Matters

Before showing the clean implementations, here is what the code looks like WITHOUT the Strategy pattern. This is what an interviewer wants to see you avoid:

```java
/**
 * ANTI-PATTERN: The "ugly if-else" approach.
 *
 * Every time you add a new Trie type or ranking algorithm, you must:
 *   1. Add a new else-if branch here
 *   2. Add new else-if branches in EVERY method that touches the Trie or ranking
 *   3. Risk breaking existing logic when editing shared code
 *   4. Unit testing becomes a nightmare (one giant class to test)
 *
 * This violates:
 *   - OCP (Open-Closed Principle): must modify existing code for new trie types
 *   - SRP (Single Responsibility): one class knows Standard, Compressed, TopK internals
 *   - DIP (Dependency Inversion): service depends on concrete logic, not abstraction
 */
public class UglyAutocompleteServiceAntiPattern {

    private String trieType;    // "STANDARD", "COMPRESSED", "TOPK"
    private String rankingType; // "FREQUENCY", "TIME_DECAY", "PERSONALIZED"

    // Standard Trie fields
    private Map<Character, Object> standardRoot;

    // Compressed Trie fields
    private Map<String, Object> compressedRoot;

    // TopK Trie fields
    private Map<Character, Object> topKRoot;
    private Map<Object, List<String>> nodeTopKMap;

    public List<String> getSuggestions(String prefix) {
        List<String> rawResults;

        // === UGLY: switch on trie type in EVERY method ===
        if ("STANDARD".equals(trieType)) {
            rawResults = standardTrieDFS(prefix);
            // ... walk standardRoot char by char, DFS from prefix node
        } else if ("COMPRESSED".equals(trieType)) {
            rawResults = compressedTrieTraverse(prefix);
            // ... walk compressedRoot by edge labels, DFS from match node
        } else if ("TOPK".equals(trieType)) {
            rawResults = topKTrieLookup(prefix);
            // ... walk topKRoot, return pre-computed list
        } else {
            throw new IllegalStateException("Unknown trie type: " + trieType);
        }

        // === AND AGAIN for ranking ===
        if ("FREQUENCY".equals(rankingType)) {
            rawResults.sort((a, b) -> getFrequency(b) - getFrequency(a));
        } else if ("TIME_DECAY".equals(rankingType)) {
            rawResults.sort((a, b) -> {
                double scoreA = getFrequency(a) * Math.exp(-0.01 * getAge(a));
                double scoreB = getFrequency(b) * Math.exp(-0.01 * getAge(b));
                return Double.compare(scoreB, scoreA);
            });
        } else if ("PERSONALIZED".equals(rankingType)) {
            // ... boost user history ...
        }
        // Adding a new trie type? Edit EVERY method. Good luck.
        return rawResults;
    }

    // 10 more methods with the same if-else chains for insert, delete, search...
    private List<String> standardTrieDFS(String prefix)        { return List.of(); }
    private List<String> compressedTrieTraverse(String prefix) { return List.of(); }
    private List<String> topKTrieLookup(String prefix)         { return List.of(); }
    private int getFrequency(String word) { return 0; }
    private double getAge(String word)    { return 0; }
}
```

**Now the clean solution**: Each Trie type is its own class implementing `Trie`. Each ranking algorithm is its own class implementing `RankingStrategy`. AutocompleteService holds interface references and delegates. Adding a new Trie variant or ranking strategy means writing ONE new class. Zero changes to AutocompleteService.

```
ANTI-PATTERN (above):                      CLEAN PATTERN (below):
====================================       ====================================
UglyAutocompleteService                    AutocompleteService
  ├── if "STANDARD" → inline Trie logic      └── trie: Trie (interface)
  ├── if "COMPRESSED" → inline logic               ├── StandardTrie
  ├── if "TOPK" → inline logic                     ├── CompressedTrie
  ├── if "FREQUENCY" → inline ranking              ├── TopKTrie
  ├── if "TIME_DECAY" → inline ranking             └── (new? just add a class)
  └── if "PERSONALIZED" → inline ranking     └── rankingStrategy: RankingStrategy
  (modify here for every new type)                 ├── FrequencyRankingStrategy
                                                   ├── TimeDecayRankingStrategy
                                                   ├── PersonalizedRankingStrategy
                                                   └── (new? just add a class)
```

---

### 6.1 StandardTrie (HashMap Children, DFS-based Lookup)

> **The textbook implementation.** Every interview expects you to build this from scratch. Know it cold: insert walks char-by-char, getSuggestions does DFS from the prefix node.

#### Internal Data Structure: How Insert Builds the Trie

```
     ┌──────────────────────────────────────────────────────────────┐
     │               StandardTrie Insert: "facebook", "face", "faq" │
     │                                                              │
     │   After inserting "facebook" (freq=1000):                    │
     │                                                              │
     │       root                                                   │
     │        └── 'f'                                               │
     │             └── 'a'                                          │
     │                  └── 'c'                                     │
     │                       └── 'e'                                │
     │                            └── 'b'                           │
     │                                 └── 'o'                      │
     │                                      └── 'o'                 │
     │                                           └── 'k' ★(1000)   │
     │                                                              │
     │   After also inserting "face" (freq=500):                    │
     │                                                              │
     │       root                                                   │
     │        └── 'f'                                               │
     │             └── 'a'                                          │
     │                  └── 'c'                                     │
     │                       └── 'e' ★(500)  ← isEndOfWord=true   │
     │                            └── 'b'                           │
     │                                 └── 'o'                      │
     │                                      └── 'o'                 │
     │                                           └── 'k' ★(1000)   │
     │                                                              │
     │   After also inserting "faq" (freq=200):                     │
     │                                                              │
     │       root                                                   │
     │        └── 'f'                                               │
     │             └── 'a'                                          │
     │                  ├── 'c'                                     │
     │                  │    └── 'e' ★(500)                        │
     │                  │         └── 'b'                           │
     │                  │              └── 'o'                      │
     │                  │                   └── 'o'                 │
     │                  │                        └── 'k' ★(1000)   │
     │                  └── 'q' ★(200)                             │
     │                                                              │
     │   getSuggestions("fa", 3):                                   │
     │     1. Walk root -> 'f' -> 'a'   (prefix traversal: O(L))  │
     │     2. DFS from 'a' node, collect all ★ nodes               │
     │     3. Found: "facebook"(1000), "face"(500), "faq"(200)    │
     │     4. Sort by frequency, return top 3                      │
     │                                                              │
     │   Complexity: O(L) to find prefix + O(N) DFS to collect     │
     │   WHERE N = total words under the prefix subtree             │
     └──────────────────────────────────────────────────────────────┘
```

```java
/**
 * Standard Trie implementation with HashMap children.
 *
 * HOW IT WORKS:
 *   - Insert: walk the Trie character by character, creating nodes as needed.
 *     Mark the last node as isEndOfWord and set its frequency.
 *   - Search: walk the Trie character by character. If we reach the end
 *     of the word and the node isEndOfWord, the word exists.
 *   - getSuggestions: walk to the prefix node, then DFS to collect
 *     all complete words in the subtree. Sort by frequency, return top K.
 *
 * COMPLEXITY:
 *   - insert:          O(L) where L = word length
 *   - search:          O(L)
 *   - getSuggestions:  O(L) + O(N) where N = words under the prefix
 *   - delete:          O(L)
 *   - space:           O(ALPHABET_SIZE * N * L) worst case
 *
 * WHEN TO USE: Small datasets, prototyping, or when Trie is rebuilt
 * frequently and insert speed matters more than lookup speed.
 *
 * WHEN NOT TO USE: Production autocomplete serving millions of QPS.
 * The O(N) DFS on getSuggestions is too slow for hot prefixes like "a"
 * which may have millions of descendants.
 */
public class StandardTrie implements Trie {

    private final TrieNode root;
    private int wordCount;

    public StandardTrie() {
        this.root = new TrieNode();
        this.wordCount = 0;
    }

    /**
     * Inserts a word by walking the Trie character by character.
     *
     * Call chain:
     *   TrieService.insert()
     *     -> StandardTrie.insert()
     *       -> TrieNode.getOrCreateChild() for each character
     *       -> mark last node as end-of-word, set frequency
     */
    @Override
    public void insert(String word, long frequency) {
        if (word == null || word.isBlank()) return;

        String normalized = word.toLowerCase().trim();
        TrieNode current = root;

        // Walk/create path character by character
        for (char c : normalized.toCharArray()) {
            current = current.getOrCreateChild(c);
        }

        // Mark end of word
        if (!current.isEndOfWord()) {
            wordCount++;
        }
        current.setEndOfWord(true);
        current.setFrequency(frequency);
    }

    @Override
    public boolean search(String word) {
        if (word == null || word.isBlank()) return false;

        TrieNode node = findNode(word.toLowerCase().trim());
        return node != null && node.isEndOfWord();
    }

    /**
     * Gets suggestions by:
     *   1. Walking to the prefix node (O(L))
     *   2. DFS from that node to collect all complete words (O(N))
     *   3. Sorting by frequency and returning top K
     *
     * THIS IS THE O(N) BOTTLENECK that TopKTrie solves.
     */
    @Override
    public List<Suggestion> getSuggestions(String prefix, int k) {
        if (prefix == null || prefix.isBlank() || k <= 0) {
            return Collections.emptyList();
        }

        String normalized = prefix.toLowerCase().trim();
        TrieNode prefixNode = findNode(normalized);

        if (prefixNode == null) {
            return Collections.emptyList();  // No words with this prefix
        }

        // DFS to collect all complete words under this prefix
        List<Suggestion> results = new ArrayList<>();
        collectSuggestions(prefixNode, new StringBuilder(normalized), results);

        // Sort by frequency descending, take top K
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        return results.stream().limit(k).collect(Collectors.toList());
    }

    @Override
    public boolean delete(String word) {
        if (word == null || word.isBlank()) return false;
        return deleteHelper(root, word.toLowerCase().trim(), 0);
    }

    @Override
    public int size() {
        return wordCount;
    }

    @Override
    public String getTrieType() {
        return "STANDARD";
    }

    // === Private helpers ===

    /** Walks the Trie to find the node for the given string. Returns null if not found. */
    private TrieNode findNode(String str) {
        TrieNode current = root;
        for (char c : str.toCharArray()) {
            current = current.getChild(c);
            if (current == null) return null;
        }
        return current;
    }

    /**
     * DFS to collect all complete words in the subtree rooted at 'node'.
     *
     * Uses StringBuilder for efficient string building during recursion.
     * Each time we find an isEndOfWord node, we create a Suggestion.
     */
    private void collectSuggestions(TrieNode node, StringBuilder path,
                                    List<Suggestion> results) {
        if (node.isEndOfWord()) {
            results.add(new Suggestion(
                path.toString(),
                node.getFrequency()
            ));
        }

        // Recurse into all children
        for (Map.Entry<Character, TrieNode> entry : node.getChildren().entrySet()) {
            path.append(entry.getKey());
            collectSuggestions(entry.getValue(), path, results);
            path.deleteCharAt(path.length() - 1);  // backtrack
        }
    }

    /**
     * Recursive delete: removes the word and cleans up empty nodes.
     *
     * Returns true if the parent should delete this child node (no more
     * children and not end-of-word for another word).
     */
    private boolean deleteHelper(TrieNode node, String word, int depth) {
        if (depth == word.length()) {
            if (!node.isEndOfWord()) return false;  // Word doesn't exist
            node.setEndOfWord(false);
            node.setFrequency(0);
            wordCount--;
            return node.hasNoChildren();  // Delete node if it has no children
        }

        char c = word.charAt(depth);
        TrieNode child = node.getChild(c);
        if (child == null) return false;

        boolean shouldDeleteChild = deleteHelper(child, word, depth + 1);

        if (shouldDeleteChild) {
            node.getChildren().remove(c);
            return !node.isEndOfWord() && node.hasNoChildren();
        }

        return false;
    }
}
```

---

### 6.2 CompressedTrie (Radix Tree / Patricia Trie)

> **Space optimization.** A CompressedTrie merges single-child chains into a single edge with a string label. This dramatically reduces node count for datasets with long shared prefixes.

#### How Compression Works

```
     ┌──────────────────────────────────────────────────────────────────┐
     │            StandardTrie vs CompressedTrie (Radix Tree)           │
     │                                                                  │
     │   Words: "facebook", "face", "faq"                              │
     │                                                                  │
     │   STANDARD TRIE (11 nodes):          COMPRESSED TRIE (5 nodes): │
     │                                                                  │
     │   root                               root                       │
     │    └─ 'f'                             └─ "fa" ──────────────┐   │
     │        └─ 'a'                              ├─ "ce" ★(500)   │   │
     │             ├─ 'c'                         │   └─ "book" ★(1000)│
     │             │   └─ 'e' ★(500)             └─ "q" ★(200)    │   │
     │             │        └─ 'b'                                     │
     │             │             └─ 'o'                                │
     │             │                  └─ 'o'     Savings:              │
     │             │                       └─ 'k' ★(1000)  11 -> 5 nodes  │
     │             └─ 'q' ★(200)          (55% reduction)              │
     │                                                                  │
     │   WHEN DOES COMPRESSION HAPPEN?                                 │
     │     On insert, after adding a new branch:                       │
     │     - If a node has only ONE child and is NOT end-of-word,      │
     │       merge the node with its child (concatenate edge labels).  │
     │                                                                  │
     │   SPLITTING EXAMPLE:                                            │
     │     Existing edge: "facebook" from root                         │
     │     Insert "face":                                              │
     │       1. Find longest common prefix: "face"                     │
     │       2. Split "facebook" into "face" + "book"                  │
     │       3. Create intermediate node at "face" (isEndOfWord=true)  │
     │       4. Existing "book" suffix becomes a child edge            │
     │                                                                  │
     │     Before split:    root ──"facebook"──> ★                    │
     │     After split:     root ──"face"──> ★ ──"book"──> ★         │
     └──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * Compressed Trie (Radix Tree / Patricia Trie) implementation.
 *
 * KEY INSIGHT: In a StandardTrie, chains of single-child nodes waste memory.
 * Example: "antidisestablishmentarianism" creates 28 nodes where only 1 is
 * an end-of-word. CompressedTrie stores the entire string as a single edge label.
 *
 * HOW IT WORKS:
 *   - Instead of Map<Character, TrieNode>, each edge carries a String label.
 *   - Insert: find longest common prefix with existing edges, split if needed.
 *   - Search: match edge labels (multi-character at a time).
 *
 * COMPLEXITY:
 *   - insert:          O(L) amortized (split is O(1) per edge)
 *   - search:          O(L) (match edge labels)
 *   - getSuggestions:  O(L) + O(N) (same DFS limitation as StandardTrie)
 *   - Space:           O(N) -- much less than StandardTrie for sparse datasets
 *
 * INTERVIEW TIP: Mention that Linux kernel's routing table uses a radix tree
 * (Patricia trie) for IP prefix matching. Same concept, different domain.
 */
public class CompressedTrie implements Trie {

    /**
     * Internal node for CompressedTrie.
     * Children keyed by the FIRST CHARACTER of their edge label.
     */
    private static class CompressedNode {
        Map<Character, CompressedNode> children = new HashMap<>();
        Map<Character, String> edgeLabels = new HashMap<>();  // char -> full edge label
        boolean isEndOfWord = false;
        long frequency = 0;
    }

    private final CompressedNode root;
    private int wordCount;

    public CompressedTrie() {
        this.root = new CompressedNode();
        this.wordCount = 0;
    }

    /**
     * Inserts a word into the CompressedTrie.
     *
     * Algorithm:
     *   1. Walk the trie by matching edge labels
     *   2. If a mismatch occurs mid-edge: SPLIT the edge
     *   3. If the remaining suffix is consumed: mark node as end-of-word
     *   4. If no matching edge exists: create a new edge
     *
     * SPLIT EXAMPLE:
     *   Existing edge: "facebook" (root -> child)
     *   Inserting: "face"
     *   
     *   Step 1: Common prefix = "face", remaining existing = "book", remaining new = ""
     *   Step 2: Split "facebook" into "face" -> intermediate node -> "book" -> old child
     *   Step 3: Mark intermediate node as end-of-word for "face"
     */
    @Override
    public void insert(String word, long frequency) {
        if (word == null || word.isBlank()) return;

        String remaining = word.toLowerCase().trim();
        insertHelper(root, remaining, frequency);
    }

    private void insertHelper(CompressedNode node, String remaining, long frequency) {
        if (remaining.isEmpty()) {
            if (!node.isEndOfWord) wordCount++;
            node.isEndOfWord = true;
            node.frequency = frequency;
            return;
        }

        char firstChar = remaining.charAt(0);

        // Case 1: No edge starting with this character -- create new edge
        if (!node.edgeLabels.containsKey(firstChar)) {
            CompressedNode newChild = new CompressedNode();
            newChild.isEndOfWord = true;
            newChild.frequency = frequency;
            node.children.put(firstChar, newChild);
            node.edgeLabels.put(firstChar, remaining);
            wordCount++;
            return;
        }

        // Case 2: Edge exists -- find longest common prefix
        String edgeLabel = node.edgeLabels.get(firstChar);
        int commonLength = longestCommonPrefix(remaining, edgeLabel);

        if (commonLength == edgeLabel.length()) {
            // Edge fully matched -- recurse with remaining suffix
            CompressedNode child = node.children.get(firstChar);
            insertHelper(child, remaining.substring(commonLength), frequency);

        } else {
            // SPLIT the edge at the point of divergence
            //
            //   BEFORE:  node --"facebook"--> existingChild
            //   AFTER:   node --"face"--> splitNode --"book"--> existingChild
            //                                        \--remaining suffix--> newChild

            CompressedNode existingChild = node.children.get(firstChar);
            CompressedNode splitNode = new CompressedNode();

            // Connect node -> splitNode with the common prefix
            String commonPrefix = edgeLabel.substring(0, commonLength);
            String existingSuffix = edgeLabel.substring(commonLength);

            node.edgeLabels.put(firstChar, commonPrefix);
            node.children.put(firstChar, splitNode);

            // Connect splitNode -> existingChild with the remaining suffix
            char existingSuffixFirstChar = existingSuffix.charAt(0);
            splitNode.children.put(existingSuffixFirstChar, existingChild);
            splitNode.edgeLabels.put(existingSuffixFirstChar, existingSuffix);

            // Insert the new word's remaining suffix (if any)
            String newSuffix = remaining.substring(commonLength);
            if (newSuffix.isEmpty()) {
                splitNode.isEndOfWord = true;
                splitNode.frequency = frequency;
                wordCount++;
            } else {
                insertHelper(splitNode, newSuffix, frequency);
            }
        }
    }

    @Override
    public boolean search(String word) {
        if (word == null || word.isBlank()) return false;
        String normalized = word.toLowerCase().trim();
        return searchHelper(root, normalized);
    }

    private boolean searchHelper(CompressedNode node, String remaining) {
        if (remaining.isEmpty()) {
            return node.isEndOfWord;
        }

        char firstChar = remaining.charAt(0);
        if (!node.edgeLabels.containsKey(firstChar)) return false;

        String edgeLabel = node.edgeLabels.get(firstChar);
        if (!remaining.startsWith(edgeLabel)) return false;

        return searchHelper(node.children.get(firstChar),
                           remaining.substring(edgeLabel.length()));
    }

    @Override
    public List<Suggestion> getSuggestions(String prefix, int k) {
        if (prefix == null || prefix.isBlank() || k <= 0) {
            return Collections.emptyList();
        }

        String normalized = prefix.toLowerCase().trim();
        List<Suggestion> results = new ArrayList<>();

        // Find the node where the prefix ends
        collectFromPrefix(root, normalized, "", results);

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results.stream().limit(k).collect(Collectors.toList());
    }

    /**
     * Navigates to the prefix match point and collects all words below.
     *
     * Handles the case where the prefix ends in the MIDDLE of an edge label.
     * Example: prefix="fac", edge="facebook" -- the prefix is consumed within
     * the edge, so we collect from the edge's target node.
     */
    private void collectFromPrefix(CompressedNode node, String remaining,
                                   String built, List<Suggestion> results) {
        if (remaining.isEmpty()) {
            // Prefix fully consumed -- collect all words from here
            collectAll(node, built, results);
            return;
        }

        char firstChar = remaining.charAt(0);
        if (!node.edgeLabels.containsKey(firstChar)) return;

        String edgeLabel = node.edgeLabels.get(firstChar);

        if (edgeLabel.startsWith(remaining)) {
            // Edge is longer than remaining prefix -- prefix ends mid-edge
            // Example: prefix="fac", edge="face" -> collect from child with built+"face"
            collectAll(node.children.get(firstChar), built + edgeLabel, results);
        } else if (remaining.startsWith(edgeLabel)) {
            // Edge is shorter than remaining prefix -- keep walking
            collectFromPrefix(node.children.get(firstChar),
                            remaining.substring(edgeLabel.length()),
                            built + edgeLabel, results);
        }
        // else: mismatch, no suggestions
    }

    /** DFS to collect all complete words from this node downward. */
    private void collectAll(CompressedNode node, String path, List<Suggestion> results) {
        if (node.isEndOfWord) {
            results.add(new Suggestion(path, node.frequency));
        }

        for (Map.Entry<Character, CompressedNode> entry : node.children.entrySet()) {
            String edgeLabel = node.edgeLabels.get(entry.getKey());
            collectAll(entry.getValue(), path + edgeLabel, results);
        }
    }

    @Override
    public boolean delete(String word) {
        if (word == null || word.isBlank()) return false;
        boolean deleted = deleteHelper(root, word.toLowerCase().trim());
        if (deleted) wordCount--;
        return deleted;
    }

    private boolean deleteHelper(CompressedNode node, String remaining) {
        if (remaining.isEmpty()) {
            if (!node.isEndOfWord) return false;
            node.isEndOfWord = false;
            node.frequency = 0;
            return true;
        }

        char firstChar = remaining.charAt(0);
        if (!node.edgeLabels.containsKey(firstChar)) return false;

        String edgeLabel = node.edgeLabels.get(firstChar);
        if (!remaining.startsWith(edgeLabel)) return false;

        CompressedNode child = node.children.get(firstChar);
        boolean deleted = deleteHelper(child, remaining.substring(edgeLabel.length()));

        if (deleted) {
            // Merge single-child non-end nodes after deletion
            if (!child.isEndOfWord && child.children.size() == 1) {
                Map.Entry<Character, CompressedNode> onlyChild =
                    child.children.entrySet().iterator().next();
                String mergedLabel = edgeLabel + child.edgeLabels.get(onlyChild.getKey());
                node.children.put(firstChar, onlyChild.getValue());
                node.edgeLabels.put(firstChar, mergedLabel);
            } else if (!child.isEndOfWord && child.children.isEmpty()) {
                node.children.remove(firstChar);
                node.edgeLabels.remove(firstChar);
            }
        }

        return deleted;
    }

    private int longestCommonPrefix(String a, String b) {
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return len;
    }

    @Override
    public int size()           { return wordCount; }

    @Override
    public String getTrieType() { return "COMPRESSED"; }
}
```

---

### 6.3 TopKTrie (Pre-Computed Top-K at Every Node -- O(1) Lookup)

> **The production solution.** This is what Google actually uses. By pre-computing the top-K suggestions at every node during insert, we trade O(L*K) insert time for O(1) lookup time. Since queries vastly outnumber inserts, this is the optimal trade-off.

#### How Pre-Computed TopK Works

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              TopKTrie: Pre-Computed Top-K at Every Node          │
     │                                                                  │
     │   Words inserted (K=3):                                         │
     │     "facebook"  freq=10000                                      │
     │     "face"      freq=5000                                       │
     │     "faq"       freq=2000                                       │
     │     "far"       freq=1500                                       │
     │     "fast"      freq=3000                                       │
     │                                                                  │
     │   root                                                          │
     │   topK: [facebook(10000), face(5000), fast(3000)]               │
     │    │                                                             │
     │    └── 'f'                                                      │
     │        topK: [facebook(10000), face(5000), fast(3000)]          │
     │         │                                                        │
     │         └── 'a'                                                 │
     │             topK: [facebook(10000), face(5000), fast(3000)]     │
     │              │                                                   │
     │              ├── 'c'                                             │
     │              │   topK: [facebook(10000), face(5000)]            │
     │              │    │                                              │
     │              │    └── 'e' ★(5000)                               │
     │              │        topK: [facebook(10000), face(5000)]       │
     │              │         │                                         │
     │              │         └── 'b' ... 'k' ★(10000)                │
     │              │             topK: [facebook(10000)]              │
     │              │                                                   │
     │              ├── 'q' ★(2000)                                    │
     │              │   topK: [faq(2000)]                              │
     │              │                                                   │
     │              ├── 'r' ★(1500)                                    │
     │              │   topK: [far(1500)]                              │
     │              │                                                   │
     │              └── 's'                                             │
     │                  topK: [fast(3000)]                             │
     │                   └── 't' ★(3000)                               │
     │                       topK: [fast(3000)]                        │
     │                                                                  │
     │   QUERY: getSuggestions("fa", 3)                                │
     │     1. Walk root -> 'f' -> 'a'              O(2) = O(L)        │
     │     2. Return node['a'].topK                 O(1) !!!           │
     │     3. Result: [facebook(10000), face(5000), fast(3000)]        │
     │                                                                  │
     │   vs StandardTrie getSuggestions("fa", 3):                      │
     │     1. Walk root -> 'f' -> 'a'              O(2) = O(L)        │
     │     2. DFS from 'a': visit ALL descendants   O(N) !!!           │
     │     3. Sort all results                      O(N log N)         │
     │     4. Return top 3                                              │
     │                                                                  │
     │   TRADE-OFF:                                                    │
     │     Insert "facebook" updates topK at: root, 'f', 'a', 'c',   │
     │     'e', 'b', 'o', 'o', 'k' = 9 nodes. Each update is O(K).   │
     │     Total insert cost: O(L * K) = O(9 * 3) = O(27)             │
     │                                                                  │
     │     But query is O(L) + O(1) = O(L). For L=2, that is O(2).   │
     │     At 100K QPS, this saves MILLIONS of DFS operations/second.  │
     └──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * TopK Trie: pre-computes top-K suggestions at every node for O(1) lookup.
 *
 * THE KEY INSIGHT:
 *   In an autocomplete system, queries outnumber inserts by 1000:1 or more.
 *   Users type millions of queries per second, but the Trie is rebuilt from
 *   aggregated data only every few hours.
 *
 *   Therefore: it is worth paying a HIGHER INSERT COST to get a LOWER QUERY COST.
 *
 *   StandardTrie:  insert O(L),     getSuggestions O(L + N)   <- N is huge
 *   TopKTrie:      insert O(L * K), getSuggestions O(L)       <- O(1) after prefix walk
 *
 * HOW IT WORKS:
 *   On insert("facebook", 10000):
 *     1. Walk/create path: root -> 'f' -> 'a' -> 'c' -> 'e' -> 'b' -> 'o' -> 'o' -> 'k'
 *     2. At EVERY node along the path, update the topK suggestion list:
 *        - If "facebook" is already in topK, update its score
 *        - If not, add it and evict the lowest-scoring entry if list is full
 *     3. Mark 'k' as end-of-word with frequency=10000
 *
 *   On getSuggestions("fa", 3):
 *     1. Walk root -> 'f' -> 'a'
 *     2. Return node['a'].getTopSuggestions()  -- PRE-COMPUTED, O(1)!
 *
 * INTERVIEW TIP: This is the answer Google/Facebook expect. If you only
 * describe DFS-based lookup, you'll get a "that works but doesn't scale"
 * follow-up. Pre-computed TopK is the production answer.
 */
public class TopKTrie implements Trie {

    private final TrieNode root;
    private final int k;           // max suggestions per node
    private int wordCount;

    /**
     * @param k the maximum number of pre-computed suggestions per node
     */
    public TopKTrie(int k) {
        this.root = new TrieNode();
        this.k = k;
        this.wordCount = 0;
    }

    /**
     * Inserts a word and updates the topK list at EVERY ancestor node.
     *
     * This is the O(L * K) insert that buys O(1) lookup.
     *
     * Call chain:
     *   TrieBuilderService.buildTrie()
     *     -> TopKTrie.insert()
     *       -> for each char: node.getOrCreateChild(c)
     *       -> for each ancestor: node.updateTopSuggestions(suggestion, k)
     */
    @Override
    public void insert(String word, long frequency) {
        if (word == null || word.isBlank()) return;

        String normalized = word.toLowerCase().trim();
        Suggestion suggestion = new Suggestion(normalized, frequency);

        TrieNode current = root;

        // Update topK at root (root's topK = global top-K across all words)
        current.updateTopSuggestions(suggestion, k);

        // Walk/create path, updating topK at EVERY node
        for (char c : normalized.toCharArray()) {
            current = current.getOrCreateChild(c);
            current.updateTopSuggestions(suggestion, k);  // <-- O(K) per node
        }

        // Mark end of word
        if (!current.isEndOfWord()) {
            wordCount++;
        }
        current.setEndOfWord(true);
        current.setFrequency(frequency);
    }

    @Override
    public boolean search(String word) {
        if (word == null || word.isBlank()) return false;

        TrieNode node = findNode(word.toLowerCase().trim());
        return node != null && node.isEndOfWord();
    }

    /**
     * Returns pre-computed top-K suggestions for the given prefix.
     *
     * THIS IS THE PAYOFF: O(L) to walk to the prefix node, then O(1) to
     * return the pre-computed list. No DFS, no sorting, no traversal.
     */
    @Override
    public List<Suggestion> getSuggestions(String prefix, int k) {
        if (prefix == null || prefix.isBlank() || k <= 0) {
            return Collections.emptyList();
        }

        TrieNode prefixNode = findNode(prefix.toLowerCase().trim());

        if (prefixNode == null) {
            return Collections.emptyList();
        }

        // O(1) -- just return the pre-computed list!
        List<Suggestion> topK = prefixNode.getTopSuggestions();
        return topK.stream().limit(k).collect(Collectors.toList());
    }

    /**
     * Deletes a word. Must update topK at every ancestor.
     *
     * More expensive than StandardTrie delete because we must rebuild
     * topK lists along the path. In practice, deletes are rare (only for
     * content moderation -- removing offensive autocomplete suggestions).
     */
    @Override
    public boolean delete(String word) {
        if (word == null || word.isBlank()) return false;

        String normalized = word.toLowerCase().trim();
        TrieNode node = findNode(normalized);

        if (node == null || !node.isEndOfWord()) return false;

        node.setEndOfWord(false);
        node.setFrequency(0);
        wordCount--;

        // Remove this word from topK lists along the path
        // NOTE: In production, you would rebuild the Trie instead of
        // doing per-node topK cleanup. This is a simplified version.
        removeFromTopK(root, normalized);

        return true;
    }

    @Override
    public int size() {
        return wordCount;
    }

    @Override
    public String getTrieType() {
        return "TOPK";
    }

    // === Private helpers ===

    private TrieNode findNode(String str) {
        TrieNode current = root;
        for (char c : str.toCharArray()) {
            current = current.getChild(c);
            if (current == null) return null;
        }
        return current;
    }

    /**
     * Removes a word from the topK list at every node along its path.
     * Called on delete to keep topK lists consistent.
     */
    private void removeFromTopK(TrieNode node, String word) {
        // Remove from this node's topK
        node.getTopSuggestions(); // unmodifiable -- need direct access

        // Walk the path and remove from each node's topK
        // (simplified: in production, you'd rebuild)
        TrieNode current = root;
        removeWordFromNodeTopK(current, word);

        for (char c : word.toCharArray()) {
            current = current.getChild(c);
            if (current == null) break;
            removeWordFromNodeTopK(current, word);
        }
    }

    /** Helper to remove a specific word from a node's topK list. */
    private void removeWordFromNodeTopK(TrieNode node, String word) {
        // We need mutable access -- updateTopSuggestions handles this
        // by using removeIf internally. Trigger a rebuild by re-inserting
        // a zero-score entry and letting it get trimmed.
        node.updateTopSuggestions(
            new Suggestion(word, 0, Suggestion.Source.POPULAR, 0), k
        );
    }
}
```

---

### 6.4 FrequencyRankingStrategy (Simple Sort by Count)

```java
/**
 * Ranks suggestions by raw frequency count (highest first).
 *
 * The simplest ranking strategy: whichever query was searched the most
 * appears first. No time decay, no personalization.
 *
 * WHEN TO USE: When you don't have timestamp data or user context.
 * WHEN NOT TO USE: When stale queries dominate (e.g., "olympics 2024"
 * still showing up in 2026 because of historical frequency).
 */
public class FrequencyRankingStrategy implements RankingStrategy {

    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }

        // Create a mutable copy (don't mutate the input)
        List<Suggestion> ranked = new ArrayList<>(suggestions);

        // Score = raw frequency
        for (Suggestion s : ranked) {
            s.setScore(s.getFrequency());
        }

        // Sort by score descending
        ranked.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        return ranked;
    }

    @Override
    public String getStrategyName() {
        return "FREQUENCY";
    }
}
```

### 6.5 TimeDecayRankingStrategy (Exponential Decay)

> **The production-relevant strategy.** Uses exponential decay so that recent queries score higher than stale ones. This is how Google keeps "trending" searches at the top.

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              Time Decay: score = frequency * e^(-lambda * age)   │
     │                                                                  │
     │   Example with lambda = 0.01:                                   │
     │                                                                  │
     │   Query          Frequency  Age (hours)  Decay Factor  Score    │
     │   ─────────────  ─────────  ───────────  ────────────  ──────   │
     │   "super bowl"     50000      720 (30d)   e^(-7.2)     37.5    │
     │   "taylor swift"   10000        2          e^(-0.02)   9802    │
     │   "weather"         8000       24          e^(-0.24)   6290    │
     │                                                                  │
     │   Without time decay: "super bowl" wins (50000 > 10000 > 8000) │
     │   With time decay:    "taylor swift" wins (recent, still hot!)  │
     │                                                                  │
     │   Score                                                         │
     │    ^                                                             │
     │    |  ★ "taylor swift" (10000 * 0.98 = 9802)                   │
     │    |                                                             │
     │    |         ★ "weather" (8000 * 0.79 = 6290)                  │
     │    |                                                             │
     │    |                                                             │
     │    |                                              ★ "super bowl"│
     │    |                                    (50000 * 0.00075 = 37)  │
     │    +────────────────────────────────────────────────> Age (hrs) │
     │    0        24        168       336       504       720         │
     │                                                                  │
     │   The decay factor lambda controls how fast scores drop:        │
     │     lambda = 0.001  -> slow decay (good for stable queries)    │
     │     lambda = 0.01   -> moderate decay (recommended default)     │
     │     lambda = 0.1    -> fast decay (emphasizes very recent)      │
     └──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * Ranks suggestions using exponential time decay.
 *
 * Formula: score = frequency * Math.exp(-decayFactor * hoursSinceLastSearch)
 *
 * This ensures that:
 *   1. High-frequency queries still rank well (multiplicative factor)
 *   2. Recent queries get a boost (exponential decay penalizes old queries)
 *   3. Trending queries (moderate frequency, very recent) can beat
 *      historically popular but stale queries
 *
 * The decayFactor (lambda) is configurable:
 *   - Small lambda (0.001): slow decay, historical frequency dominates
 *   - Medium lambda (0.01): balanced (recommended)
 *   - Large lambda (0.1): aggressive decay, only recent queries survive
 *
 * INTERVIEW TIP: Mention that this is similar to how Reddit's "hot" ranking
 * works -- balancing votes (frequency) with recency (time decay).
 */
public class TimeDecayRankingStrategy implements RankingStrategy {

    private final double decayFactor;  // lambda in the exponential decay formula
    private final QueryRepository queryRepository;

    /**
     * @param decayFactor  the lambda parameter (e.g., 0.01)
     * @param queryRepository needed to look up lastSearchedAt for each suggestion
     */
    public TimeDecayRankingStrategy(double decayFactor, QueryRepository queryRepository) {
        this.decayFactor = decayFactor;
        this.queryRepository = Objects.requireNonNull(queryRepository);
    }

    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Suggestion> ranked = new ArrayList<>(suggestions);

        for (Suggestion s : ranked) {
            double ageInHours = getAgeInHours(s.getText());

            // score = frequency * e^(-lambda * age)
            double decayedScore = s.getFrequency() * Math.exp(-decayFactor * ageInHours);

            s.setScore(decayedScore);
        }

        ranked.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        return ranked;
    }

    /**
     * Looks up the age of a query from the repository.
     * Returns 0 if the query is not found (treats as brand new).
     */
    private double getAgeInHours(String queryText) {
        return queryRepository.findByText(queryText)
                .map(SearchQuery::getAgeInHours)
                .orElse(0.0);
    }

    @Override
    public String getStrategyName() {
        return "TIME_DECAY (lambda=" + decayFactor + ")";
    }
}
```

### 6.6 PersonalizedRankingStrategy (User History Boost)

```java
/**
 * Ranks suggestions with a boost for queries the user has searched before.
 *
 * Algorithm:
 *   1. Start with frequency-based score
 *   2. Look up the user's search history from QueryRepository
 *   3. If the suggestion matches a past query, multiply score by boost factor
 *
 * Example:
 *   User "alice" has previously searched: "facebook", "fantasy football"
 *   Prefix: "fa"
 *   Raw suggestions: facebook(10000), faq(8000), fashion(7000), fantasy football(3000)
 *
 *   After personalization (boost=2.0):
 *     facebook:         10000 * 2.0 = 20000  (user searched before -> boosted)
 *     fantasy football:  3000 * 2.0 = 6000   (user searched before -> boosted)
 *     faq:               8000 * 1.0 = 8000   (no boost)
 *     fashion:           7000 * 1.0 = 7000   (no boost)
 *
 *   Reordered: facebook(20000), faq(8000), fashion(7000), fantasy football(6000)
 *
 * INTERVIEW TIP: Mention that Google shows "Your past searches" in
 * autocomplete with a clock icon. This is the same concept.
 */
public class PersonalizedRankingStrategy implements RankingStrategy {

    private final double userHistoryBoost;
    private final QueryRepository queryRepository;

    /**
     * @param userHistoryBoost multiplier for queries the user has searched before (e.g., 2.0)
     * @param queryRepository  data source for user search history
     */
    public PersonalizedRankingStrategy(double userHistoryBoost,
                                       QueryRepository queryRepository) {
        this.userHistoryBoost = userHistoryBoost;
        this.queryRepository = Objects.requireNonNull(queryRepository);
    }

    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Suggestion> ranked = new ArrayList<>(suggestions);

        // If user is anonymous, fall back to frequency ranking
        if (!context.isAuthenticated()) {
            ranked.sort((a, b) -> Double.compare(b.getFrequency(), a.getFrequency()));
            return ranked;
        }

        // Load user's search history
        Set<String> userQueries = queryRepository.findByUserId(context.getUserId())
                .stream()
                .map(SearchQuery::getText)
                .collect(Collectors.toSet());

        for (Suggestion s : ranked) {
            double baseScore = s.getFrequency();

            if (userQueries.contains(s.getText())) {
                s.setScore(baseScore * userHistoryBoost);
            } else {
                s.setScore(baseScore);
            }
        }

        ranked.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        return ranked;
    }

    @Override
    public String getStrategyName() {
        return "PERSONALIZED (boost=" + userHistoryBoost + ")";
    }
}
```

### 6.7 ProfanityFilterStrategy

```java
/**
 * Filters out suggestions containing profane or offensive words.
 *
 * Applied AFTER ranking, BEFORE returning results to the user.
 * This is a content moderation safety net.
 *
 * In production, this would use a more sophisticated approach
 * (ML-based toxicity detection, bloom filter for banned phrases).
 * For this LLD, we use a simple Set<String> blocklist.
 */
public class ProfanityFilterStrategy implements FilterStrategy {

    private final Set<String> blockedWords;

    public ProfanityFilterStrategy(Set<String> blockedWords) {
        this.blockedWords = Objects.requireNonNull(blockedWords);
    }

    @Override
    public List<Suggestion> filter(List<Suggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }

        return suggestions.stream()
                .filter(s -> !containsBlockedWord(s.getText()))
                .collect(Collectors.toList());
    }

    /**
     * Checks if the suggestion text contains any blocked word.
     * Case-insensitive check.
     */
    private boolean containsBlockedWord(String text) {
        String lower = text.toLowerCase();
        for (String blocked : blockedWords) {
            if (lower.contains(blocked.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getFilterName() {
        return "PROFANITY_FILTER";
    }
}
```

---

## 7. Service Layer Design

> The service layer orchestrates Trie lookups, caching, ranking, and filtering. AutocompleteService is the Facade that hides this complexity from the controller.

### 7.0 Service Layer Flow (The Full Pipeline)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │          AUTOCOMPLETE REQUEST FLOW (Full Pipeline)               │
     │                                                                  │
     │   User types "fa"                                               │
     │         │                                                        │
     │         v                                                        │
     │   AutocompleteController.getSuggestions("fa", context)          │
     │         │                                                        │
     │         v                                                        │
     │   AutocompleteService.getSuggestions("fa", context)  [FACADE]   │
     │         │                                                        │
     │         ├──1. VALIDATE: prefix length, normalize to lowercase   │
     │         │                                                        │
     │         ├──2. CACHE CHECK: cache.get("fa")                      │
     │         │     ├── HIT  → return cached results (skip steps 3-5)│
     │         │     └── MISS → continue to step 3                    │
     │         │                                                        │
     │         ├──3. TRIE LOOKUP: trieService.getSuggestions("fa", 20) │
     │         │     │  (fetch more than maxResults to allow filtering) │
     │         │     │                                                  │
     │         │     └── TrieService delegates to Trie (under read lock)│
     │         │         ├── StandardTrie: DFS from prefix node  O(N)  │
     │         │         ├── CompressedTrie: edge traverse + DFS O(N)  │
     │         │         └── TopKTrie: return pre-computed list  O(1)  │
     │         │                                                        │
     │         ├──4. RANK: rankingService.rank(rawSuggestions, context) │
     │         │     ├── FrequencyRanking: sort by count               │
     │         │     ├── TimeDecayRanking: freq * e^(-lambda * age)    │
     │         │     └── PersonalizedRanking: boost user history       │
     │         │                                                        │
     │         ├──5. FILTER: filterStrategies.forEach(f -> f.filter())│
     │         │     └── ProfanityFilter: remove offensive results     │
     │         │                                                        │
     │         ├──6. TRIM: take first maxResults                       │
     │         │                                                        │
     │         ├──7. CACHE PUT: cache.put("fa", results)              │
     │         │                                                        │
     │         └──8. RETURN: List<Suggestion> to controller            │
     │                                                                  │
     │                                                                  │
     │   QUERY COMPLETION FLOW (User selects "facebook"):              │
     │         │                                                        │
     │         v                                                        │
     │   AutocompleteController.recordQuery("facebook", context)       │
     │         │                                                        │
     │         v                                                        │
     │   AutocompleteService.recordQuery(searchQuery)                  │
     │         │                                                        │
     │         ├──1. DataCollectionService.recordQuery(searchQuery)     │
     │         │     ├── queryRepository.save(searchQuery)             │
     │         │     └── aggregatedFrequencies.merge("facebook", 1)   │
     │         │                                                        │
     │         └──2. cache.invalidate("f")                             │
     │              cache.invalidate("fa")                             │
     │              cache.invalidate("fac")  ... etc                   │
     │              (invalidate all prefixes of the completed query)   │
     └──────────────────────────────────────────────────────────────────┘
```

### 7.1 AutocompleteService (Facade)

```java
/**
 * The Facade that orchestrates the entire autocomplete pipeline.
 *
 * WHY a Facade?
 *   - The controller should not know about Tries, ranking strategies, caches.
 *   - One clean method: getSuggestions(prefix, context) -> List<Suggestion>
 *   - All the wiring (cache check, trie lookup, ranking, filtering) is hidden here.
 *
 * Call chain for getSuggestions:
 *   AutocompleteController.getSuggestions()
 *     -> AutocompleteService.getSuggestions()
 *       -> cache.get(prefix)                      // step 1: check cache
 *       -> trieService.getSuggestions(prefix, k)   // step 2: trie lookup
 *       -> rankingService.rank(suggestions, ctx)   // step 3: re-rank
 *       -> filterStrategy.filter(ranked)           // step 4: content filter
 *       -> cache.put(prefix, results)              // step 5: cache results
 *       -> return results
 *
 * INTERVIEW TALKING POINT: "The Facade hides five sub-systems behind
 * one method. The controller has no idea if we're using a StandardTrie
 * or TopKTrie, FrequencyRanking or TimeDecayRanking."
 */
public class AutocompleteService {

    private final TrieService trieService;
    private final RankingService rankingService;
    private final SuggestionCache cache;
    private final List<FilterStrategy> filterStrategies;
    private final DataCollectionService dataCollectionService;
    private final AutocompleteConfig config;
    private final AtomicLong queryCount;

    /**
     * Constructor injection -- every dependency is an interface (except config).
     * AppConfig wires the concrete implementations.
     */
    public AutocompleteService(TrieService trieService,
                               RankingService rankingService,
                               SuggestionCache cache,
                               List<FilterStrategy> filterStrategies,
                               DataCollectionService dataCollectionService,
                               AutocompleteConfig config) {
        this.trieService = Objects.requireNonNull(trieService);
        this.rankingService = Objects.requireNonNull(rankingService);
        this.cache = Objects.requireNonNull(cache);
        this.filterStrategies = Objects.requireNonNull(filterStrategies);
        this.dataCollectionService = Objects.requireNonNull(dataCollectionService);
        this.config = Objects.requireNonNull(config);
        this.queryCount = new AtomicLong(0);
    }

    /**
     * THE MAIN API: returns ranked, filtered suggestions for a prefix.
     *
     * @param prefix  the characters typed so far (e.g., "fa")
     * @param context user context for personalization
     * @return top-K suggestions, ranked and filtered
     */
    public List<Suggestion> getSuggestions(String prefix, SearchContext context) {
        queryCount.incrementAndGet();

        // --- Validate ---
        if (prefix == null || prefix.isBlank()) {
            return Collections.emptyList();
        }

        String normalized = prefix.toLowerCase().trim();
        if (normalized.length() > config.getMaxPrefixLength()) {
            normalized = normalized.substring(0, config.getMaxPrefixLength());
        }

        // --- Step 1: Check cache ---
        Optional<List<Suggestion>> cached = cache.get(normalized);
        if (cached.isPresent()) {
            System.out.printf("[AUTOCOMPLETE] Cache HIT for prefix '%s'%n", normalized);
            return cached.get();
        }
        System.out.printf("[AUTOCOMPLETE] Cache MISS for prefix '%s'%n", normalized);

        // --- Step 2: Trie lookup ---
        // Fetch more than maxResults to give ranking/filtering room to work
        int fetchSize = config.getMaxResults() * 2;
        List<Suggestion> rawSuggestions = trieService.getSuggestions(normalized, fetchSize);

        if (rawSuggestions.isEmpty()) {
            cache.put(normalized, Collections.emptyList());
            return Collections.emptyList();
        }

        // --- Step 3: Rank ---
        List<Suggestion> ranked = rankingService.rank(rawSuggestions, context);

        // --- Step 4: Filter ---
        List<Suggestion> filtered = ranked;
        for (FilterStrategy filter : filterStrategies) {
            filtered = filter.filter(filtered);
        }

        // --- Step 5: Trim to maxResults ---
        List<Suggestion> results = filtered.stream()
                .limit(config.getMaxResults())
                .collect(Collectors.toList());

        // --- Step 6: Cache ---
        cache.put(normalized, results);

        return results;
    }

    /**
     * Records a completed search query.
     *
     * Called when the user selects a suggestion or presses Enter.
     * This is how the system learns query frequencies.
     *
     * Also invalidates cached prefixes for this query, since the
     * frequency change means cached results may be stale.
     */
    public void recordQuery(SearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        // Record in data collection
        dataCollectionService.recordQuery(query);

        // Invalidate cache for all prefixes of this query
        // e.g., "facebook" invalidates "f", "fa", "fac", "face", "faceb", ...
        String text = query.getText();
        for (int i = 1; i <= text.length(); i++) {
            cache.invalidate(text.substring(0, i));
        }

        System.out.printf("[AUTOCOMPLETE] Recorded query '%s', invalidated %d cache entries%n",
                text, text.length());
    }

    /**
     * Triggers a full Trie rebuild from aggregated data.
     *
     * Called periodically (e.g., every few hours) to incorporate
     * new query frequency data into the Trie.
     *
     * The rebuild happens on a separate thread; TrieService uses
     * a ReadWriteLock to atomically swap the old Trie for the new one.
     */
    public void rebuildTrie(TrieBuilderService trieBuilderService, String trieType) {
        System.out.println("[AUTOCOMPLETE] Rebuilding Trie...");

        Trie newTrie = trieBuilderService.rebuildFromAggregatedData(trieType);
        trieService.replaceTrie(newTrie);
        cache.invalidateAll();  // New Trie means all cached results are stale

        System.out.printf("[AUTOCOMPLETE] Trie rebuilt: %d words, type=%s%n",
                newTrie.size(), newTrie.getTrieType());
    }

    /** Returns statistics for monitoring/display. */
    public AutocompleteStats getStats() {
        return new AutocompleteStats(
            queryCount.get(),
            trieService.size(),
            cache.size(),
            cache.hitCount(),
            cache.missCount(),
            rankingService.getStrategyName(),
            trieService.getTrieType()
        );
    }

    /** Immutable stats record. */
    public record AutocompleteStats(
        long totalQueries,
        int trieSize,
        int cacheSize,
        long cacheHits,
        long cacheMisses,
        String rankingStrategy,
        String trieType
    ) {
        public double cacheHitRatio() {
            long total = cacheHits + cacheMisses;
            return total == 0 ? 0.0 : (double) cacheHits / total;
        }

        @Override
        public String toString() {
            return String.format(
                "AutocompleteStats[queries=%d, trieSize=%d, cacheSize=%d, " +
                "cacheHitRatio=%.2f%%, ranking=%s, trie=%s]",
                totalQueries, trieSize, cacheSize, cacheHitRatio() * 100,
                rankingStrategy, trieType);
        }
    }
}
```

---

### 7.2 TrieService (Thread-Safe Trie Wrapper)

```java
/**
 * Wraps a Trie with ReadWriteLock for thread-safe concurrent access.
 *
 * WHY a separate service instead of making the Trie thread-safe?
 *   - SRP: the Trie knows about data structure operations.
 *     Thread safety is an orthogonal concern.
 *   - Flexibility: we can swap the Trie implementation without changing
 *     the locking logic.
 *   - Atomic swap: replaceTrie() uses a write lock to atomically swap
 *     the old Trie for a new one during rebuild.
 *
 * LOCKING MODEL:
 *   - getSuggestions, search, size: read lock (concurrent reads allowed)
 *   - insert, delete, replaceTrie: write lock (exclusive access)
 *   - Read-heavy workload: readers NEVER block each other
 */
public class TrieService {

    private volatile Trie trie;
    private final ReadWriteLock readWriteLock;

    public TrieService(Trie trie) {
        this.trie = Objects.requireNonNull(trie);
        this.readWriteLock = new ReentrantReadWriteLock();
    }

    /** Thread-safe insert (write lock). */
    public void insert(String word, long frequency) {
        readWriteLock.writeLock().lock();
        try {
            trie.insert(word, frequency);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /** Thread-safe getSuggestions (read lock). */
    public List<Suggestion> getSuggestions(String prefix, int k) {
        readWriteLock.readLock().lock();
        try {
            return trie.getSuggestions(prefix, k);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /** Thread-safe search (read lock). */
    public boolean search(String word) {
        readWriteLock.readLock().lock();
        try {
            return trie.search(word);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /** Thread-safe delete (write lock). */
    public boolean delete(String word) {
        readWriteLock.writeLock().lock();
        try {
            return trie.delete(word);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /** Thread-safe size (read lock). */
    public int size() {
        readWriteLock.readLock().lock();
        try {
            return trie.size();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Atomically replaces the current Trie with a new one.
     *
     * Used during Trie rebuild: the new Trie is built in a separate thread,
     * then swapped in atomically under a write lock.
     *
     * WHY volatile + write lock?
     *   - volatile ensures all threads see the new Trie reference immediately
     *   - write lock ensures no reads are in-flight during the swap
     */
    public void replaceTrie(Trie newTrie) {
        readWriteLock.writeLock().lock();
        try {
            this.trie = Objects.requireNonNull(newTrie);
            System.out.printf("[TRIE_SERVICE] Trie replaced: type=%s, size=%d%n",
                    newTrie.getTrieType(), newTrie.size());
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    public String getTrieType() {
        return trie.getTrieType();
    }
}
```

---

### 7.3 RankingService (Ranking Strategy Wrapper)

```java
/**
 * Wraps a RankingStrategy and provides a consistent API.
 *
 * WHY a separate service?
 *   - Allows runtime strategy switching (e.g., switch from Frequency
 *     to TimeDecay without restarting the application)
 *   - Centralizes ranking logging and metrics
 *   - Follows SRP: strategy knows HOW to rank, service knows WHEN
 */
public class RankingService {

    private volatile RankingStrategy strategy;

    public RankingService(RankingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy);
    }

    /** Delegates ranking to the current strategy. */
    public List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context) {
        return strategy.rank(suggestions, context);
    }

    /** Allows runtime strategy switching. */
    public void setStrategy(RankingStrategy newStrategy) {
        this.strategy = Objects.requireNonNull(newStrategy);
        System.out.printf("[RANKING] Strategy switched to: %s%n", newStrategy.getStrategyName());
    }

    public String getStrategyName() {
        return strategy.getStrategyName();
    }
}
```

---

### 7.4 DataCollectionService (Query Aggregation)

```java
/**
 * Collects and aggregates search query data.
 *
 * Every time a user completes a search (selects a suggestion or hits Enter),
 * the query is recorded here. The aggregated frequencies are later used by
 * TrieBuilderService to rebuild the Trie.
 *
 * TWO RESPONSIBILITIES:
 *   1. Persist individual queries to QueryRepository (for history/personalization)
 *   2. Aggregate frequencies in a ConcurrentHashMap (for Trie rebuilds)
 */
public class DataCollectionService {

    private final QueryRepository queryRepository;
    private final ConcurrentHashMap<String, Long> aggregatedFrequencies;

    public DataCollectionService(QueryRepository queryRepository) {
        this.queryRepository = Objects.requireNonNull(queryRepository);
        this.aggregatedFrequencies = new ConcurrentHashMap<>();
    }

    /**
     * Records a completed search query.
     *
     * Call chain:
     *   AutocompleteService.recordQuery()
     *     -> DataCollectionService.recordQuery()
     *       -> queryRepository.save()           // persist for history
     *       -> aggregatedFrequencies.merge()    // increment frequency counter
     */
    public void recordQuery(SearchQuery query) {
        // Persist to repository
        Optional<SearchQuery> existing = queryRepository.findByText(query.getText());
        if (existing.isPresent()) {
            existing.get().recordSearch();
            queryRepository.save(existing.get());
        } else {
            queryRepository.save(query);
        }

        // Aggregate frequency
        aggregatedFrequencies.merge(query.getText(), 1L, Long::sum);

        System.out.printf("[DATA_COLLECTION] Recorded query: '%s', aggregate freq: %d%n",
                query.getText(), aggregatedFrequencies.get(query.getText()));
    }

    /** Returns the aggregated frequency map for Trie rebuilds. */
    public Map<String, Long> getAggregatedFrequencies() {
        return new HashMap<>(aggregatedFrequencies);
    }

    /** Returns top-K queries by frequency from the repository. */
    public List<SearchQuery> getTopQueries(int k) {
        return queryRepository.findTopByFrequency(k);
    }

    /** Clears the aggregation buffer (called after a Trie rebuild). */
    public void clearAggregation() {
        aggregatedFrequencies.clear();
    }
}
```

---

### 7.5 TrieBuilderService (Trie Factory)

```java
/**
 * Builds and rebuilds Tries from aggregated query data.
 *
 * This is the OFFLINE component of the autocomplete pipeline.
 * In production, this runs as a batch job every few hours:
 *   1. Read aggregated query frequencies from DataCollectionService
 *   2. Build a fresh Trie (StandardTrie, CompressedTrie, or TopKTrie)
 *   3. Hand the new Trie to TrieService.replaceTrie() for atomic swap
 *
 * Separating build from serve follows SRP:
 *   - TrieService handles real-time reads (hot path)
 *   - TrieBuilderService handles offline builds (cold path)
 */
public class TrieBuilderService {

    private final DataCollectionService dataCollectionService;
    private final AutocompleteConfig config;

    public TrieBuilderService(DataCollectionService dataCollectionService,
                              AutocompleteConfig config) {
        this.dataCollectionService = Objects.requireNonNull(dataCollectionService);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Builds a Trie of the specified type from aggregated data.
     *
     * @param trieType "STANDARD", "COMPRESSED", or "TOPK"
     * @return the built Trie, ready to be swapped into TrieService
     */
    public Trie buildTrie(String trieType) {
        Trie trie = createEmptyTrie(trieType);

        Map<String, Long> frequencies = dataCollectionService.getAggregatedFrequencies();

        // Filter out low-frequency queries (noise reduction)
        frequencies.entrySet().stream()
                .filter(e -> e.getValue() >= config.getMinFrequency())
                .forEach(e -> trie.insert(e.getKey(), e.getValue()));

        System.out.printf("[TRIE_BUILDER] Built %s Trie: %d words from %d candidates%n",
                trieType, trie.size(), frequencies.size());

        return trie;
    }

    /** Rebuilds from aggregated data and clears the aggregation buffer. */
    public Trie rebuildFromAggregatedData(String trieType) {
        Trie trie = buildTrie(trieType);
        dataCollectionService.clearAggregation();
        return trie;
    }

    /** Factory method for creating empty Trie instances. */
    private Trie createEmptyTrie(String trieType) {
        return switch (trieType.toUpperCase()) {
            case "STANDARD"   -> new StandardTrie();
            case "COMPRESSED" -> new CompressedTrie();
            case "TOPK"       -> new TopKTrie(config.getTopKPerNode());
            default -> throw new AutocompleteException("Unknown trie type: " + trieType);
        };
    }
}
```

---

### 7.6 InMemorySuggestionCache (LRU Cache)

```java
/**
 * LRU cache for prefix -> suggestions, backed by LinkedHashMap.
 *
 * WHY LinkedHashMap for LRU?
 *   - LinkedHashMap with accessOrder=true maintains entries in access order.
 *   - Override removeEldestEntry() to automatically evict the LRU entry
 *     when the cache exceeds capacity.
 *   - This gives us O(1) get, O(1) put, and O(1) eviction -- perfect for
 *     a hot-path cache that's checked on every keystroke.
 *
 * WHY NOT ConcurrentHashMap?
 *   - ConcurrentHashMap doesn't support access-order iteration.
 *   - For LRU, we need to know which entry was accessed LEAST recently.
 *   - We use synchronized access instead (acceptable because cache ops
 *     are extremely fast -- just a HashMap lookup).
 *
 * INTERVIEW TIP: This is a mini LRU cache INSIDE the autocomplete system.
 * If the interviewer asks "how do you implement LRU?", you can point to
 * this and say "LinkedHashMap with removeEldestEntry for simplicity, or
 * HashMap + DoublyLinkedList for full control (like in the Distributed Cache LLD)."
 *
 *     ┌──────────────────────────────────────────────────────────┐
 *     │                    LRU Cache Internals                    │
 *     │                                                           │
 *     │   LinkedHashMap (accessOrder=true, capacity=10000)        │
 *     │                                                           │
 *     │   Key (prefix)    Value (List<Suggestion>)                │
 *     │   ──────────────  ───────────────────────                 │
 *     │   "goo"           [google(50k), goodreads(8k), ...]      │
 *     │   "fac"           [facebook(40k), face id(5k), ...]      │
 *     │   "ama"           [amazon(35k), amazing(3k), ...]        │
 *     │   ...                                                     │
 *     │   "xyz"           [xyzzy(10)]  ← LEAST recently used    │
 *     │                                                           │
 *     │   On get("fac"): moves "fac" to the END (most recent)   │
 *     │   On put("new", [...]): if size > capacity,             │
 *     │     removeEldestEntry() evicts "xyz" (HEAD = LRU)        │
 *     └──────────────────────────────────────────────────────────┘
 */
public class InMemorySuggestionCache implements SuggestionCache {

    private final Map<String, List<Suggestion>> cache;
    private final int maxSize;
    private final AtomicLong hits;
    private final AtomicLong misses;

    public InMemorySuggestionCache(int maxSize) {
        this.maxSize = maxSize;
        this.hits = new AtomicLong(0);
        this.misses = new AtomicLong(0);

        // accessOrder=true: entries are ordered by access time (LRU at head)
        // removeEldestEntry: automatically evict when size exceeds maxSize
        this.cache = Collections.synchronizedMap(
            new LinkedHashMap<String, List<Suggestion>>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Suggestion>> eldest) {
                    boolean shouldRemove = size() > maxSize;
                    if (shouldRemove) {
                        System.out.printf("[CACHE] Evicting LRU entry: prefix='%s'%n",
                                eldest.getKey());
                    }
                    return shouldRemove;
                }
            }
        );
    }

    @Override
    public Optional<List<Suggestion>> get(String prefix) {
        List<Suggestion> result = cache.get(prefix);
        if (result != null) {
            hits.incrementAndGet();
            return Optional.of(Collections.unmodifiableList(result));
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public void put(String prefix, List<Suggestion> suggestions) {
        cache.put(prefix, new ArrayList<>(suggestions));  // defensive copy
    }

    @Override
    public void invalidate(String prefix) {
        cache.remove(prefix);
    }

    @Override
    public void invalidateAll() {
        cache.clear();
        System.out.println("[CACHE] All entries invalidated");
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public long hitCount() {
        return hits.get();
    }

    @Override
    public long missCount() {
        return misses.get();
    }
}
```

---

### 7.7 InMemoryQueryRepository

```java
/**
 * In-memory implementation of QueryRepository backed by ConcurrentHashMap.
 *
 * In production, this would be backed by a database (Cassandra for write-heavy
 * query logging, or DynamoDB for key-value lookups by userId).
 */
public class InMemoryQueryRepository implements QueryRepository {

    private final ConcurrentHashMap<String, SearchQuery> store;

    public InMemoryQueryRepository() {
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public void save(SearchQuery query) {
        store.put(query.getText(), query);
    }

    @Override
    public Optional<SearchQuery> findByText(String text) {
        return Optional.ofNullable(store.get(text.toLowerCase().trim()));
    }

    @Override
    public List<SearchQuery> findByUserId(String userId) {
        if (userId == null) return Collections.emptyList();
        return store.values().stream()
                .filter(q -> userId.equals(q.getUserId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SearchQuery> findTopByFrequency(int limit) {
        return store.values().stream()
                .sorted((a, b) -> Long.compare(b.getFrequency(), a.getFrequency()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<SearchQuery> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public int count() {
        return store.size();
    }
}
```

---

## 8. Concurrency Considerations

> Autocomplete systems are inherently concurrent: thousands of users type simultaneously, each keystroke triggers a prefix lookup. This section covers how each component handles thread safety.

### 8.1 Overview: What Needs Thread Safety and Why

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                    CONCURRENCY MODEL                             │
     │                                                                  │
     │   Component                Thread Safety Mechanism               │
     │   ─────────────────────    ────────────────────────────────────  │
     │   TrieService              ReadWriteLock (readers concurrent,    │
     │                              writers exclusive -- trie swap)     │
     │   StandardTrie             NOT thread-safe (wrapped by           │
     │                              TrieService's lock)                 │
     │   CompressedTrie           NOT thread-safe (same)               │
     │   TopKTrie                 NOT thread-safe (same)               │
     │   InMemorySuggestionCache  Collections.synchronizedMap          │
     │                              (LinkedHashMap)                     │
     │   DataCollectionService    ConcurrentHashMap for frequencies    │
     │   InMemoryQueryRepository  ConcurrentHashMap                    │
     │   AutocompleteService      AtomicLong for queryCount            │
     │   RankingService           volatile reference for strategy swap │
     │   AutocompleteConfig       Immutable (inherently thread-safe)   │
     │   Suggestion.score         Mutable but only within a single     │
     │                              request thread (not shared)        │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.2 ReadWriteLock in TrieService (The Key Insight)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              ReadWriteLock in TrieService                         │
     │                                                                  │
     │   WHY ReadWriteLock instead of synchronized?                     │
     │                                                                  │
     │   getSuggestions() is called on EVERY KEYSTROKE (very hot path). │
     │   insert()/replaceTrie() is called during batch rebuilds (rare). │
     │                                                                  │
     │   With synchronized:                                             │
     │     getSuggestions("fa") ──BLOCKED── getSuggestions("go")       │
     │     ← ONE reader at a time! Terrible for autocomplete QPS.     │
     │                                                                  │
     │   With ReadWriteLock:                                            │
     │     getSuggestions("fa") ──PARALLEL── getSuggestions("go")      │
     │     ← Multiple readers concurrently! Reads never block reads.  │
     │                                                                  │
     │     replaceTrie(newTrie) ──EXCLUSIVE──                          │
     │     ← Write lock blocks all readers during atomic swap.         │
     │       Swap takes microseconds, so readers block briefly.        │
     │                                                                  │
     │   Timeline during Trie rebuild:                                  │
     │                                                                  │
     │   Thread-1 (reader):  [===read===]          [===read===]        │
     │   Thread-2 (reader):  [===read===]          [====read====]      │
     │   Thread-3 (reader):     [==read==]         [==read==]          │
     │   Thread-4 (writer):              [WRITE]                       │
     │                                   ^^^^^^^                        │
     │                                   Readers blocked for ~1ms      │
     │                                   during Trie swap              │
     │                                                                  │
     │   WITHOUT ReadWriteLock (using synchronized):                   │
     │                                                                  │
     │   Thread-1:  [==read==]                                         │
     │   Thread-2:              [==read==]     ← SERIALIZED!          │
     │   Thread-3:                          [==read==]  ← SERIALIZED! │
     │   Throughput: 1 read at a time = BOTTLENECK                     │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.3 Why the Trie Itself Is Not Thread-Safe (And That's OK)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │        WHY WE DON'T MAKE THE TRIE ITSELF THREAD-SAFE            │
     │                                                                  │
     │   OPTION A (ANTI-PATTERN): Thread-safe Trie                     │
     │     - Lock every node on traversal                              │
     │     - ConcurrentHashMap for children at every node              │
     │     - Every getSuggestions() acquires N locks (one per node)    │
     │     - Problems:                                                  │
     │       1. Lock overhead on EVERY node visit kills performance    │
     │       2. ConcurrentHashMap overhead: ~40 bytes per node extra   │
     │       3. Complex: deadlock risk with nested node locks          │
     │                                                                  │
     │   OPTION B (CLEAN): External ReadWriteLock in TrieService       │
     │     - One lock for the entire Trie                              │
     │     - Read lock: cheap, allows concurrent readers               │
     │     - Write lock: only during Trie rebuild (rare event)         │
     │     - Benefits:                                                  │
     │       1. Trie internals stay simple (no locking code)           │
     │       2. SRP: Trie = data structure, TrieService = concurrency  │
     │       3. Readers NEVER block each other                         │
     │       4. One lock acquisition per request (not per node)        │
     │                                                                  │
     │   We use OPTION B because:                                      │
     │     - Autocomplete is READ-HEAVY (99.9% reads, 0.1% writes)    │
     │     - Writes happen in bulk (Trie rebuild), not per-request     │
     │     - ReadWriteLock is the perfect fit for this access pattern   │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.4 Cache Thread Safety

```java
/**
 * WHY Collections.synchronizedMap for the cache?
 *
 * The cache uses LinkedHashMap (for access-order LRU). LinkedHashMap
 * is NOT thread-safe -- even get() modifies internal structure
 * (moves the accessed entry to the end of the linked list).
 *
 * ConcurrentHashMap would be ideal for thread safety, but it does NOT
 * support access-order iteration needed for LRU eviction.
 *
 * So we wrap LinkedHashMap in Collections.synchronizedMap:
 *   - Every operation acquires the map's monitor lock
 *   - Only one thread can access the cache at a time
 *
 * IS THIS A BOTTLENECK?
 *   - Cache operations are O(1) HashMap lookups (~100 nanoseconds)
 *   - Even under lock, throughput is ~10 million ops/second
 *   - The real bottleneck is Trie traversal (milliseconds), not cache lookup
 *   - If cache contention became an issue, we could shard by prefix
 *     (e.g., 26 caches, one per first character)
 *
 * ALTERNATIVE FOR INTERVIEW:
 *   "If cache contention is a concern, I would use Caffeine library's
 *   ConcurrentLinkedHashMap which provides concurrent access-order
 *   iteration. But for plain Java, synchronized LinkedHashMap is correct."
 */
```

### 8.5 ConcurrentHashMap in DataCollectionService

```
     ┌──────────────────────────────────────────────────────────────────┐
     │       ConcurrentHashMap for Frequency Aggregation                │
     │                                                                  │
     │   Multiple threads call recordQuery() simultaneously:           │
     │                                                                  │
     │   Thread-1: recordQuery("facebook")                             │
     │   Thread-2: recordQuery("facebook")  ← same query!            │
     │   Thread-3: recordQuery("google")                               │
     │                                                                  │
     │   aggregatedFrequencies.merge("facebook", 1L, Long::sum)       │
     │                                                                  │
     │   ConcurrentHashMap.merge() is ATOMIC:                          │
     │     - Acquires lock only on the bin containing "facebook"       │
     │     - Thread-3 writing "google" is NOT blocked (different bin)  │
     │     - No lost updates: both Thread-1 and Thread-2 increments   │
     │       are guaranteed to be applied                               │
     │                                                                  │
     │   WHY NOT AtomicLong values?                                    │
     │     ConcurrentHashMap<String, AtomicLong> would also work,     │
     │     but merge() with Long::sum is simpler and equally correct.  │
     │     The CAS retry in merge() handles contention.                │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.6 Volatile for Strategy Swapping

```java
/**
 * RankingService uses volatile for the strategy reference:
 *
 *   private volatile RankingStrategy strategy;
 *
 * WHY volatile?
 *   - setStrategy() can be called from an admin thread
 *   - rank() is called from request-handling threads
 *   - Without volatile, request threads might see a STALE strategy reference
 *     (Java Memory Model allows threads to cache field values locally)
 *   - volatile ensures all threads see the latest strategy immediately
 *
 * IS synchronized needed?
 *   - No: we're only swapping a reference (atomic on 64-bit JVMs)
 *   - We don't need to atomically read-and-write the strategy
 *   - A request that starts with the old strategy and finishes with it
 *     is perfectly fine (it just uses the previous ranking algorithm)
 */
```

---

## 9. SOLID Principles Applied

### 9.1 Single Responsibility Principle (SRP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                SINGLE RESPONSIBILITY PRINCIPLE                    │
     │                                                                  │
     │   Class                        Responsibility                   │
     │   ───────────────────────────  ───────────────────────────────  │
     │   StandardTrie                 Trie data structure operations   │
     │   CompressedTrie               Space-optimized Trie operations  │
     │   TopKTrie                     Pre-computed TopK Trie operations│
     │   TrieService                  Thread-safe Trie access          │
     │   RankingService               Apply ranking strategy           │
     │   AutocompleteService          Orchestrate the full pipeline    │
     │   DataCollectionService        Aggregate query data             │
     │   TrieBuilderService           Build Tries from data            │
     │   InMemorySuggestionCache      LRU caching for prefix results  │
     │   ProfanityFilterStrategy      Content moderation filtering     │
     │                                                                  │
     │   ANTI-PATTERN: One class that does everything                  │
     │     GodAutocomplete {                                           │
     │       trieInsert(), trieDFS(), rankByFrequency(),              │
     │       rankByTimeDecay(), filterProfanity(), manageLRUCache(),  │
     │       recordQuery(), buildTrie(), handleConcurrency() ...      │
     │     }                                                           │
     │     → 2000-line class, impossible to test or extend            │
     │                                                                  │
     │   CLEAN: 15+ focused classes, each < 200 lines, each testable │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.2 Open-Closed Principle (OCP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                OPEN-CLOSED PRINCIPLE                              │
     │                                                                  │
     │   OPEN for extension, CLOSED for modification.                  │
     │                                                                  │
     │   SCENARIO: Add a new Trie implementation (e.g., TernarySearchTrie)│
     │                                                                  │
     │   STEP 1: Create TernarySearchTrie implements Trie              │
     │   STEP 2: Done. Zero changes to TrieService, AutocompleteService│
     │           TrieBuilderService, or any other class.               │
     │                                                                  │
     │   SCENARIO: Add a new ranking strategy (e.g., LocationBasedRanking)│
     │                                                                  │
     │   STEP 1: Create LocationBasedRankingStrategy implements        │
     │           RankingStrategy                                        │
     │   STEP 2: Done. Zero changes to RankingService or               │
     │           AutocompleteService.                                   │
     │                                                                  │
     │   SCENARIO: Add a new filter (e.g., SpamFilterStrategy)         │
     │                                                                  │
     │   STEP 1: Create SpamFilterStrategy implements FilterStrategy   │
     │   STEP 2: Add it to the filterStrategies list in AppConfig      │
     │   STEP 3: Done. Zero changes to AutocompleteService.            │
     │                                                                  │
     │   WHY THIS WORKS:                                               │
     │     AutocompleteService depends on INTERFACES (Trie,            │
     │     RankingStrategy, FilterStrategy), not concrete classes.     │
     │     New implementations are injected via AppConfig.             │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.3 Liskov Substitution Principle (LSP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                LISKOV SUBSTITUTION PRINCIPLE                      │
     │                                                                  │
     │   Any Trie implementation can replace any other without          │
     │   breaking TrieService:                                         │
     │                                                                  │
     │     TrieService trieService = new TrieService(new StandardTrie());│
     │     TrieService trieService = new TrieService(new CompressedTrie());│
     │     TrieService trieService = new TrieService(new TopKTrie(10)); │
     │                                                                  │
     │   All three behave identically from TrieService's perspective:  │
     │     - insert("facebook", 1000) → word is stored                │
     │     - search("facebook") → true                                │
     │     - getSuggestions("fa", 5) → list of suggestions            │
     │     - delete("facebook") → true                                │
     │                                                                  │
     │   The PERFORMANCE differs (O(N) vs O(1) lookup), but the       │
     │   CONTRACT is the same. This is proper LSP: subtypes are        │
     │   substitutable without altering correctness.                    │
     │                                                                  │
     │   SAME for ranking strategies:                                  │
     │     RankingService rs = new RankingService(new FrequencyRankingStrategy());│
     │     RankingService rs = new RankingService(new TimeDecayRankingStrategy(...));│
     │     Both return List<Suggestion>, both respect the contract.    │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.4 Interface Segregation Principle (ISP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                INTERFACE SEGREGATION PRINCIPLE                    │
     │                                                                  │
     │   ANTI-PATTERN: One fat interface                               │
     │     interface AutocompleteSystem {                              │
     │       void insert(...);                                         │
     │       List<Suggestion> getSuggestions(...);                      │
     │       List<Suggestion> rank(...);                                │
     │       List<Suggestion> filter(...);                              │
     │       void cacheGet(...);                                       │
     │       void cachePut(...);                                       │
     │       void recordQuery(...);                                    │
     │       Trie buildTrie(...);                                      │
     │     }                                                           │
     │     → Every implementor must implement ALL methods              │
     │     → A cache class forced to implement rank()? Nonsense.       │
     │                                                                  │
     │   CLEAN: Focused interfaces                                     │
     │     Trie              → insert, search, getSuggestions, delete  │
     │     RankingStrategy   → rank, getStrategyName                   │
     │     FilterStrategy    → filter, getFilterName                   │
     │     SuggestionCache   → get, put, invalidate                    │
     │     QueryRepository   → save, find, count                       │
     │                                                                  │
     │   Each class implements ONLY the interface it needs.            │
     │   StandardTrie never sees ranking methods.                      │
     │   InMemorySuggestionCache never sees Trie methods.             │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.5 Dependency Inversion Principle (DIP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                DEPENDENCY INVERSION PRINCIPLE                     │
     │                                                                  │
     │   High-level modules should NOT depend on low-level modules.    │
     │   Both should depend on abstractions.                           │
     │                                                                  │
     │   ANTI-PATTERN:                                                 │
     │     AutocompleteService depends on TopKTrie (concrete class)    │
     │     AutocompleteService depends on InMemorySuggestionCache      │
     │     → Changing the Trie type requires modifying the service!    │
     │                                                                  │
     │   CLEAN (our design):                                           │
     │     AutocompleteService depends on:                             │
     │       Trie              (interface)                             │
     │       RankingStrategy   (interface)                             │
     │       FilterStrategy    (interface)                             │
     │       SuggestionCache   (interface)                             │
     │       QueryRepository   (interface)                             │
     │                                                                  │
     │     ┌─────────────────┐      ┌─────────────┐                   │
     │     │ Autocomplete    │─────>│ <<Trie>>     │ ← abstraction    │
     │     │ Service         │      └──────────────┘                   │
     │     └─────────────────┘             ^                           │
     │                                     |                           │
     │                              ┌──────┴──────┐                   │
     │                              │  TopKTrie   │ ← concrete        │
     │                              └─────────────┘                   │
     │                                                                  │
     │     AppConfig wires concrete -> interface at startup.           │
     │     AutocompleteService never imports a concrete class.         │
     └──────────────────────────────────────────────────────────────────┘
```

---

## 10. Sample Workflows

### 10.1 Workflow: User Types "fac" and Gets Suggestions

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   WORKFLOW: getSuggestions("fac", context)                       │
     │                                                                  │
     │   User types "fac" in the search box.                           │
     │   System returns: [facebook, face id, facetime, factory, facts] │
     │                                                                  │
     │   Step-by-step call chain:                                      │
     │                                                                  │
     │   1. AutocompleteController.getSuggestions("fac", context)      │
     │      └── Validates input, delegates to service                  │
     │                                                                  │
     │   2. AutocompleteService.getSuggestions("fac", context)         │
     │      ├── Normalize: "fac" (already lowercase)                   │
     │      ├── Check prefix length: 3 < 50 (OK)                      │
     │      │                                                           │
     │      ├── cache.get("fac")                                       │
     │      │   └── Returns Optional.empty() (first time)             │
     │      │                                                           │
     │      ├── trieService.getSuggestions("fac", 20)                  │
     │      │   ├── Acquire read lock                                  │
     │      │   ├── trie.getSuggestions("fac", 20)                     │
     │      │   │   ├── [TopKTrie] Walk root->'f'->'a'->'c'  O(3)    │
     │      │   │   └── Return node['c'].topSuggestions       O(1)    │
     │      │   └── Release read lock                                  │
     │      │   Result: [facebook(10000), facetime(8000),              │
     │      │            face id(6000), factory(3000),                 │
     │      │            facts(2500), facial(1000), ...]               │
     │      │                                                           │
     │      ├── rankingService.rank(rawSuggestions, context)           │
     │      │   └── [TimeDecayRanking]                                │
     │      │       ├── facebook:  10000 * e^(-0.01*2) = 9802        │
     │      │       ├── facetime:   8000 * e^(-0.01*5) = 7610        │
     │      │       ├── face id:    6000 * e^(-0.01*1) = 5940        │
     │      │       ├── factory:    3000 * e^(-0.01*720) = 2.2       │
     │      │       └── facts:      2500 * e^(-0.01*48) = 1520       │
     │      │       Reordered: [facebook, facetime, face id, facts,   │
     │      │                   facial, factory]                       │
     │      │                                                           │
     │      ├── profanityFilter.filter(ranked)                        │
     │      │   └── All clean, no removals                            │
     │      │                                                           │
     │      ├── Take first 5 (maxResults=5)                            │
     │      │   Result: [facebook, facetime, face id, facts, facial]  │
     │      │                                                           │
     │      ├── cache.put("fac", results)                              │
     │      │   └── Stored in LRU cache for next request              │
     │      │                                                           │
     │      └── Return results to controller                           │
     │                                                                  │
     │   3. AutocompleteController returns JSON:                       │
     │      [                                                          │
     │        {"text": "facebook",  "score": 9802.0, "source": "POPULAR"},│
     │        {"text": "facetime",  "score": 7610.0, "source": "POPULAR"},│
     │        {"text": "face id",   "score": 5940.0, "source": "POPULAR"},│
     │        {"text": "facts",     "score": 1520.0, "source": "POPULAR"},│
     │        {"text": "facial",    "score":  990.0, "source": "POPULAR"} │
     │      ]                                                          │
     └──────────────────────────────────────────────────────────────────┘
```

### 10.2 Workflow: User Selects "facebook" (Query Recording)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   WORKFLOW: recordQuery("facebook")                              │
     │                                                                  │
     │   User clicks on "facebook" from the suggestion list.           │
     │   System records this search and invalidates affected caches.   │
     │                                                                  │
     │   1. AutocompleteController.recordQuery("facebook", context)    │
     │      └── Creates SearchQuery("facebook", 1, "user123")         │
     │                                                                  │
     │   2. AutocompleteService.recordQuery(searchQuery)               │
     │      │                                                           │
     │      ├── dataCollectionService.recordQuery(searchQuery)         │
     │      │   ├── queryRepository.findByText("facebook")            │
     │      │   │   └── Found: SearchQuery[freq=10000]                │
     │      │   ├── existing.recordSearch()                            │
     │      │   │   └── freq++ → 10001, lastSearchedAt → now         │
     │      │   ├── queryRepository.save(updated)                      │
     │      │   └── aggregatedFrequencies.merge("facebook", 1, sum)   │
     │      │       └── "facebook" aggregate: 10001                    │
     │      │                                                           │
     │      └── Invalidate cache for ALL prefixes of "facebook":      │
     │          cache.invalidate("f")                                  │
     │          cache.invalidate("fa")                                 │
     │          cache.invalidate("fac")                                │
     │          cache.invalidate("face")                               │
     │          cache.invalidate("faceb")                              │
     │          cache.invalidate("facebo")                             │
     │          cache.invalidate("faceboo")                            │
     │          cache.invalidate("facebook")                           │
     │          (8 cache entries invalidated)                           │
     │                                                                  │
     │   WHY invalidate all prefixes?                                  │
     │     "facebook"'s frequency changed from 10000 → 10001.         │
     │     Any cached result for "f", "fa", "fac", etc. might now     │
     │     have stale ordering. Better to invalidate and recompute     │
     │     on next request than serve stale results.                   │
     └──────────────────────────────────────────────────────────────────┘
```

### 10.3 Workflow: Trie Rebuild (Offline Batch)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   WORKFLOW: Periodic Trie Rebuild                                │
     │                                                                  │
     │   Triggered every few hours to incorporate new query data.      │
     │                                                                  │
     │   1. AutocompleteService.rebuildTrie(trieBuilderService, "TOPK")│
     │      │                                                           │
     │      ├── trieBuilderService.rebuildFromAggregatedData("TOPK")   │
     │      │   │                                                       │
     │      │   ├── dataCollectionService.getAggregatedFrequencies()   │
     │      │   │   └── Returns: {"facebook":10001, "google":9500,    │
     │      │   │                  "amazon":8200, "xyz":1, ...}        │
     │      │   │                                                       │
     │      │   ├── Filter: freq >= minFrequency (2)                   │
     │      │   │   └── Drops "xyz" (freq=1 < 2)                      │
     │      │   │                                                       │
     │      │   ├── Create new TopKTrie(10)                            │
     │      │   │                                                       │
     │      │   ├── For each entry: trie.insert(word, frequency)       │
     │      │   │   └── Each insert updates topK at every ancestor     │
     │      │   │       O(L * K) per word                               │
     │      │   │                                                       │
     │      │   ├── dataCollectionService.clearAggregation()           │
     │      │   │   └── Resets frequency counters for next period      │
     │      │   │                                                       │
     │      │   └── Returns new Trie (5000 words)                      │
     │      │                                                           │
     │      ├── trieService.replaceTrie(newTrie)                       │
     │      │   ├── Acquire WRITE lock (blocks all readers briefly)    │
     │      │   ├── this.trie = newTrie  (atomic reference swap)       │
     │      │   └── Release WRITE lock (readers resume)                │
     │      │                                                           │
     │      └── cache.invalidateAll()                                  │
     │          └── All cached results are stale (new Trie data)       │
     │                                                                  │
     │   TOTAL DOWNTIME: ~1 millisecond (write lock duration)          │
     │   The new Trie is built BEFORE the swap, so the write lock      │
     │   only covers the reference assignment, not the build.          │
     └──────────────────────────────────────────────────────────────────┘
```

### 10.4 Workflow: SearchAutocompleteApp (Main Demo)

```java
/**
 * Main application that wires everything together and demonstrates
 * the autocomplete system with realistic scenarios.
 *
 * This is what you'd run in an interview whiteboard session to
 * show the system working end-to-end.
 */
public class SearchAutocompleteApp {

    public static void main(String[] args) {

        // === 1. CONFIGURATION ===
        AutocompleteConfig config = new AutocompleteConfig.Builder()
                .maxResults(5)
                .topKPerNode(10)
                .cacheMaxSize(1000)
                .minFrequency(2)
                .decayFactor(0.01)
                .build();

        // === 2. WIRE DEPENDENCIES (Pure Constructor Injection) ===

        // Repository layer
        QueryRepository queryRepo = new InMemoryQueryRepository();

        // Strategy layer
        RankingStrategy rankingStrategy =
                new TimeDecayRankingStrategy(config.getDecayFactor(), queryRepo);
        FilterStrategy profanityFilter =
                new ProfanityFilterStrategy(Set.of("badword", "offensive"));

        // Service layer
        DataCollectionService dataCollectionService =
                new DataCollectionService(queryRepo);
        TrieBuilderService trieBuilderService =
                new TrieBuilderService(dataCollectionService, config);

        // Seed initial data
        seedData(dataCollectionService);

        // Build initial Trie
        Trie initialTrie = trieBuilderService.buildTrie("TOPK");
        TrieService trieService = new TrieService(initialTrie);
        RankingService rankingService = new RankingService(rankingStrategy);
        SuggestionCache cache = new InMemorySuggestionCache(config.getCacheMaxSize());

        // Facade
        AutocompleteService autocompleteService = new AutocompleteService(
                trieService, rankingService, cache,
                List.of(profanityFilter),
                dataCollectionService, config
        );

        // Controller
        AutocompleteController controller =
                new AutocompleteController(autocompleteService);

        // === 3. RUN SCENARIOS ===

        System.out.println("=== Search Autocomplete Demo ===\n");

        SearchContext context = new SearchContext("user123", "en", "US");

        // Scenario 1: Type "fac"
        System.out.println("--- User types 'fac' ---");
        List<Suggestion> results = controller.getSuggestions("fac", context);
        results.forEach(s -> System.out.printf("  %s (score=%.1f)%n",
                s.getText(), s.getScore()));

        // Scenario 2: Same prefix again (cache hit)
        System.out.println("\n--- User types 'fac' again (should be cache hit) ---");
        results = controller.getSuggestions("fac", context);
        results.forEach(s -> System.out.printf("  %s (score=%.1f)%n",
                s.getText(), s.getScore()));

        // Scenario 3: Record a query
        System.out.println("\n--- User selects 'facebook' ---");
        controller.recordQuery(new SearchQuery("facebook", 1, "user123"));

        // Scenario 4: Type "fac" again (cache invalidated, recomputed)
        System.out.println("\n--- User types 'fac' after recording (cache miss) ---");
        results = controller.getSuggestions("fac", context);
        results.forEach(s -> System.out.printf("  %s (score=%.1f)%n",
                s.getText(), s.getScore()));

        // Stats
        System.out.println("\n--- System Stats ---");
        System.out.println(autocompleteService.getStats());
    }

    private static void seedData(DataCollectionService dcs) {
        // Simulate historical query data
        String[][] queries = {
            {"facebook", "10000"}, {"facetime", "8000"}, {"face id", "6000"},
            {"factory", "3000"}, {"facts", "2500"}, {"facial", "1000"},
            {"google", "15000"}, {"gmail", "9000"}, {"github", "7000"},
            {"google maps", "6500"}, {"google drive", "5000"},
            {"amazon", "12000"}, {"amazon prime", "8000"},
            {"apple", "11000"}, {"apple store", "4000"},
            {"twitter", "9000"}, {"tiktok", "8500"},
            {"weather", "7000"}, {"walmart", "5500"},
        };

        for (String[] q : queries) {
            // Record each query enough times to meet minFrequency
            for (int i = 0; i < Long.parseLong(q[1]); i++) {
                dcs.recordQuery(new SearchQuery(q[0], 1));
            }
        }
    }
}
```

---

## 11. Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `Trie` (StandardTrie, CompressedTrie, TopKTrie), `RankingStrategy` (Frequency, TimeDecay, Personalized), `FilterStrategy` (Profanity) | Plug in different Trie implementations and ranking algorithms at runtime without changing service code. THE most important pattern in this design. |
| **Facade** | `AutocompleteService` | Hides the complexity of trie lookup + caching + ranking + filtering behind a single `getSuggestions()` method. Controller has no idea what's inside. |
| **Builder** | `AutocompleteConfig.Builder` | Config has 7 parameters -- too many for a constructor. Builder lets you set only what you need with sensible defaults. |
| **Factory Method** | `TrieBuilderService.createEmptyTrie()` | Creates the right Trie type based on a string parameter. Centralizes Trie creation logic. |
| **Repository** | `QueryRepository` / `InMemoryQueryRepository` | Abstracts data access. Swap InMemory for Cassandra/DynamoDB without changing service code. |
| **Template Method** (implicit) | Trie interface with common contract | All Trie implementations follow the same insert/search/getSuggestions/delete template, varying only the internal algorithm. |
| **Observer** (conceptual) | Cache invalidation on query recording | When a query is recorded, all affected prefix caches are invalidated. In production, this would be an event-driven system (Kafka/SQS). |
| **Decorator** (conceptual) | FilterStrategy chain | Multiple filters are applied in sequence: profanity -> spam -> NSFW. Each filter decorates the result of the previous one. |

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              PATTERN MAP: Where Each Pattern Lives               │
     │                                                                  │
     │   AutocompleteController                                        │
     │         │                                                        │
     │         └── AutocompleteService ◄───────────── FACADE           │
     │              │     │     │     │                                 │
     │              │     │     │     └── AutocompleteConfig ◄── BUILDER│
     │              │     │     │                                       │
     │              │     │     └── SuggestionCache                    │
     │              │     │         └── InMemorySuggestionCache         │
     │              │     │                                             │
     │              │     └── RankingService                            │
     │              │         └── RankingStrategy ◄──────── STRATEGY   │
     │              │              ├── FrequencyRanking                 │
     │              │              ├── TimeDecayRanking                 │
     │              │              └── PersonalizedRanking              │
     │              │                                                   │
     │              └── TrieService                                    │
     │                   └── Trie ◄────────────────── STRATEGY         │
     │                        ├── StandardTrie                         │
     │                        ├── CompressedTrie                       │
     │                        └── TopKTrie                             │
     │                                                                  │
     │              TrieBuilderService                                 │
     │                   └── createEmptyTrie() ◄───── FACTORY METHOD   │
     │                                                                  │
     │              QueryRepository ◄─────────────── REPOSITORY        │
     │                   └── InMemoryQueryRepository                   │
     └──────────────────────────────────────────────────────────────────┘
```

---

## 12. Extensibility Points

> These are the points where you can extend the system WITHOUT modifying existing code (Open-Closed Principle in action).

### 12.1 New Trie Implementation

```
SCENARIO: Add a TernarySearchTrie for better prefix matching in natural language.

STEPS:
  1. Create: TernarySearchTrie implements Trie
  2. Implement: insert, search, getSuggestions, delete, size, getTrieType
  3. Add "TERNARY" case to TrieBuilderService.createEmptyTrie()
  4. Done. Zero changes to TrieService, AutocompleteService, or any other class.

FILES MODIFIED:
  + trie/TernarySearchTrie.java  (NEW)
  ~ service/TrieBuilderService.java  (add one switch case)

FILES UNCHANGED:
  trie/Trie.java
  trie/StandardTrie.java
  trie/CompressedTrie.java
  trie/TopKTrie.java
  service/TrieService.java
  service/AutocompleteService.java
  service/RankingService.java
  store/InMemorySuggestionCache.java
  ... everything else
```

### 12.2 New Ranking Strategy

```
SCENARIO: Add LocationBasedRankingStrategy that boosts results popular
in the user's geographic region.

STEPS:
  1. Create: LocationBasedRankingStrategy implements RankingStrategy
  2. Implement: rank(suggestions, context) using context.getLocation()
  3. Wire in AppConfig: new RankingService(new LocationBasedRankingStrategy(...))
  4. Done. Zero changes to RankingService or AutocompleteService.

FILES MODIFIED:
  + strategy/ranking/LocationBasedRankingStrategy.java  (NEW)
  ~ config/AppConfig.java  (wire new strategy)

FILES UNCHANGED:
  strategy/ranking/RankingStrategy.java
  strategy/ranking/FrequencyRankingStrategy.java
  strategy/ranking/TimeDecayRankingStrategy.java
  strategy/ranking/PersonalizedRankingStrategy.java
  service/RankingService.java
  service/AutocompleteService.java
  ... everything else
```

### 12.3 New Filter Strategy

```
SCENARIO: Add SpamFilterStrategy that removes SEO-spam suggestions
(e.g., "buy cheap viagra online").

STEPS:
  1. Create: SpamFilterStrategy implements FilterStrategy
  2. Implement: filter(suggestions) to remove spam-like patterns
  3. Add to AppConfig: filterStrategies = List.of(profanityFilter, spamFilter)
  4. Done. AutocompleteService applies it automatically (iterates the list).

FILES MODIFIED:
  + strategy/filtering/SpamFilterStrategy.java  (NEW)
  ~ config/AppConfig.java  (add to filter list)
```

### 12.4 New Cache Implementation

```
SCENARIO: Replace InMemorySuggestionCache with a Redis-backed distributed cache.

STEPS:
  1. Create: RedisSuggestionCache implements SuggestionCache
  2. Implement: get, put, invalidate using Redis client
  3. Wire in AppConfig: new RedisSuggestionCache(redisClient, config)
  4. Done. AutocompleteService has no idea it's talking to Redis.
```

### 12.5 New Repository Implementation

```
SCENARIO: Replace InMemoryQueryRepository with Cassandra for persistent storage.

STEPS:
  1. Create: CassandraQueryRepository implements QueryRepository
  2. Implement: save, findByText, findByUserId using Cassandra driver
  3. Wire in AppConfig: new CassandraQueryRepository(cassandraSession)
  4. Done. DataCollectionService and TimeDecayRankingStrategy are unaware.
```

### 12.6 Extensibility Summary

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                 EXTENSIBILITY MATRIX                             │
     │                                                                  │
     │   Extension Point          Interface            Existing Impls  │
     │   ──────────────────────   ──────────────────   ──────────────  │
     │   New Trie algorithm       Trie                 Standard,       │
     │                                                  Compressed,     │
     │                                                  TopK            │
     │   New ranking algorithm    RankingStrategy       Frequency,      │
     │                                                  TimeDecay,      │
     │                                                  Personalized    │
     │   New content filter       FilterStrategy        Profanity       │
     │   New cache backend        SuggestionCache       InMemory (LRU)  │
     │   New data store           QueryRepository       InMemory        │
     │                                                                  │
     │   RULE: Every new implementation requires:                      │
     │     1. One new class implementing the interface                 │
     │     2. One line change in AppConfig to wire it                  │
     │     3. ZERO changes to service/controller/other implementations │
     └──────────────────────────────────────────────────────────────────┘
```

---

## Appendix: AppConfig (Dependency Wiring)

```java
/**
 * Wires all dependencies using pure constructor injection.
 * No Spring, no Guice, no framework -- just Java.
 *
 * This is the ONLY class that knows about concrete implementations.
 * Everything else depends on interfaces.
 */
public class AppConfig {

    private final AutocompleteConfig config;
    private final QueryRepository queryRepository;
    private final DataCollectionService dataCollectionService;
    private final TrieBuilderService trieBuilderService;
    private final TrieService trieService;
    private final RankingService rankingService;
    private final SuggestionCache cache;
    private final List<FilterStrategy> filterStrategies;
    private final AutocompleteService autocompleteService;
    private final AutocompleteController controller;

    public AppConfig() {
        // --- Config ---
        this.config = new AutocompleteConfig.Builder()
                .maxResults(10)
                .topKPerNode(10)
                .cacheMaxSize(10_000)
                .minFrequency(2)
                .decayFactor(0.01)
                .build();

        // --- Repository ---
        this.queryRepository = new InMemoryQueryRepository();

        // --- Data Collection ---
        this.dataCollectionService = new DataCollectionService(queryRepository);

        // --- Trie Builder ---
        this.trieBuilderService = new TrieBuilderService(dataCollectionService, config);

        // --- Trie (start with TopKTrie for O(1) lookup) ---
        Trie initialTrie = new TopKTrie(config.getTopKPerNode());
        this.trieService = new TrieService(initialTrie);

        // --- Ranking (start with TimeDecay for production-like behavior) ---
        RankingStrategy strategy =
                new TimeDecayRankingStrategy(config.getDecayFactor(), queryRepository);
        this.rankingService = new RankingService(strategy);

        // --- Cache ---
        this.cache = new InMemorySuggestionCache(config.getCacheMaxSize());

        // --- Filters ---
        this.filterStrategies = List.of(
                new ProfanityFilterStrategy(Set.of("badword", "offensive", "inappropriate"))
        );

        // --- Facade ---
        this.autocompleteService = new AutocompleteService(
                trieService, rankingService, cache,
                filterStrategies, dataCollectionService, config
        );

        // --- Controller ---
        this.controller = new AutocompleteController(autocompleteService);
    }

    // --- Getters for each component ---
    public AutocompleteConfig getConfig()                    { return config; }
    public QueryRepository getQueryRepository()              { return queryRepository; }
    public DataCollectionService getDataCollectionService()  { return dataCollectionService; }
    public TrieBuilderService getTrieBuilderService()        { return trieBuilderService; }
    public TrieService getTrieService()                      { return trieService; }
    public RankingService getRankingService()                 { return rankingService; }
    public SuggestionCache getCache()                        { return cache; }
    public AutocompleteService getAutocompleteService()      { return autocompleteService; }
    public AutocompleteController getController()            { return controller; }
}
```

---

## Appendix: Exception Classes

```java
/**
 * Base exception for all autocomplete system errors.
 */
public class AutocompleteException extends RuntimeException {
    public AutocompleteException(String message) {
        super(message);
    }

    public AutocompleteException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Thrown when the Trie exceeds its maximum node capacity.
 *
 * This prevents unbounded memory growth if the system ingests
 * too many unique queries.
 */
public class TrieCapacityException extends AutocompleteException {
    private final int currentSize;
    private final int maxSize;

    public TrieCapacityException(int currentSize, int maxSize) {
        super(String.format("Trie capacity exceeded: %d/%d nodes", currentSize, maxSize));
        this.currentSize = currentSize;
        this.maxSize = maxSize;
    }

    public int getCurrentSize() { return currentSize; }
    public int getMaxSize()     { return maxSize; }
}
```

---

## Appendix: AutocompleteController

```java
/**
 * REST-like controller that maps HTTP-style requests to AutocompleteService.
 *
 * In a real system, this would be a Spring @RestController or a JAX-RS resource.
 * For this LLD, it's a plain Java class that delegates to the Facade.
 */
public class AutocompleteController {

    private final AutocompleteService autocompleteService;

    public AutocompleteController(AutocompleteService autocompleteService) {
        this.autocompleteService = Objects.requireNonNull(autocompleteService);
    }

    /**
     * GET /autocomplete?q=fac&userId=user123&lang=en&loc=US
     *
     * @param prefix  the characters typed so far
     * @param context user context for personalization
     * @return ranked, filtered suggestions
     */
    public List<Suggestion> getSuggestions(String prefix, SearchContext context) {
        System.out.printf("[CONTROLLER] GET /autocomplete?q=%s%n", prefix);
        return autocompleteService.getSuggestions(prefix, context);
    }

    /**
     * POST /autocomplete/record
     *
     * Called when the user selects a suggestion or completes a search.
     */
    public void recordQuery(SearchQuery query) {
        System.out.printf("[CONTROLLER] POST /autocomplete/record query='%s'%n",
                query.getText());
        autocompleteService.recordQuery(query);
    }
}
```

---

## Appendix: AutocompleteStatsDisplay

```java
/**
 * Formats and displays autocomplete system statistics.
 */
public class AutocompleteStatsDisplay {

    private final AutocompleteService autocompleteService;

    public AutocompleteStatsDisplay(AutocompleteService autocompleteService) {
        this.autocompleteService = autocompleteService;
    }

    public void displayStats() {
        var stats = autocompleteService.getStats();

        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║      AUTOCOMPLETE SYSTEM STATS        ║");
        System.out.println("╠════════════════════════════════════════╣");
        System.out.printf("║  Total Queries:     %,15d  ║%n", stats.totalQueries());
        System.out.printf("║  Trie Size:         %,15d  ║%n", stats.trieSize());
        System.out.printf("║  Trie Type:         %15s  ║%n", stats.trieType());
        System.out.printf("║  Cache Size:        %,15d  ║%n", stats.cacheSize());
        System.out.printf("║  Cache Hits:        %,15d  ║%n", stats.cacheHits());
        System.out.printf("║  Cache Misses:      %,15d  ║%n", stats.cacheMisses());
        System.out.printf("║  Cache Hit Ratio:   %14.2f%%  ║%n", stats.cacheHitRatio() * 100);
        System.out.printf("║  Ranking Strategy:  %15s  ║%n", stats.rankingStrategy());
        System.out.println("╚════════════════════════════════════════╝");
    }
}
```
