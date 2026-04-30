# Design Patterns in the Search Autocomplete (Typeahead) System

> Interview-ready reference for a Senior Java developer.
> A search autocomplete system is a pattern-rich domain -- it uses 9 GoF patterns across all three categories.
> For each pattern: ugly anti-pattern code, clean pattern-based code, numbered call chain, and interview one-liner.

---

## Table of Contents

| # | Pattern | GoF Category | Key Class(es) | One-Liner |
|---|---------|-------------|---------------|-----------|
| 1 | Strategy (x3) | Behavioral | `RankingStrategy` (Frequency, TimeDecay, Personalized), `FilterStrategy` (Profanity), Trie interface (Standard, Compressed, TopK) | Swap ranking/filtering/trie algorithms without changing service code |
| 2 | Builder | Creational | `SearchQuery.Builder`, `AutocompleteConfig.Builder` | Many fields with optional dimensions (language, location, limit) -- Builder prevents arg confusion |
| 3 | Factory | Creational | `AppConfig` creates all objects and wires dependencies | Centralized object creation, only class that says `new ConcreteClass()` |
| 4 | Repository | Structural (DDD) | `QueryRepository` -> `InMemoryQueryRepository` | Decouple domain from storage (swap to Redis/Cassandra) |
| 5 | Facade | Structural | `AutocompleteService` orchestrates trie + ranking + cache + filter | One entry point for all autocomplete operations |
| 6 | Observer | Behavioral | `DataCollectionService` observes search events, updates frequencies | Decouple analytics collection from search serving |
| 7 | Decorator | Structural | `PersonalizedRankingStrategy` wraps base `RankingStrategy` | Add personalization transparently without modifying base ranking |
| 8 | Singleton | Creational | `AutocompleteConfig` (single config instance) | One config to rule them all -- shared across all services |
| 9 | Iterator | Behavioral | Trie traversal (DFS iterator over suggestions) | Lazily traverse trie nodes without exposing internal structure |

---

## 1. Strategy Pattern (x3)

### What

Define a family of algorithms, encapsulate each behind a common interface, and make them interchangeable at runtime. This project uses Strategy THREE times -- once for ranking suggestions, once for filtering, and once for trie implementation selection.

### ASCII Diagram -- All Three Strategy Hierarchies

```
  RANKING STRATEGY                    FILTER STRATEGY                   TRIE STRATEGY
  ================                    ===============                   =============

  +-------------------------+         +-------------------------+       +-------------------------+
  | <<interface>>           |         | <<interface>>           |       | <<interface>>           |
  | RankingStrategy         |         | FilterStrategy          |       | Trie                    |
  +-------------------------+         +-------------------------+       +-------------------------+
  | + rank(suggestions,     |         | + filter(suggestions):  |       | + insert(word, freq)    |
  |   query): List<Suggest> |         |   List<Suggestion>      |       | + search(prefix, k):    |
  +----------+--------------+         +----------+--------------+       |   List<Suggestion>      |
             |                                   |                      +----------+--------------+
       +-----+------+                     +------+                                |
       |            |                      |                              +-------+-------+
+------+------+ +---+----------+   +------+------+               +-------+-----+ +------+------+
| Frequency   | | TimeDecay    |   | Profanity   |               | Standard    | | Compressed  |
| Ranking     | | Ranking      |   | Filter      |               | Trie        | | (Radix)     |
| Strategy    | | Strategy     |   | Strategy    |               | (HashMap    | | Trie        |
| (raw count) | | (recency)    |   | (blocklist) |               |  children)  | | (shared     |
+-------------+ +--------------+   +-------------+               +-------------+ |  prefixes)  |
                                                                                  +-------------+
```

### Ugly Code -- Without Strategy

```java
// ANTI-PATTERN: if-else chain in AutocompleteService
// Every new ranking algorithm = modify this method = OCP violation
public class AutocompleteService {

    private String rankingMode = "FREQUENCY";  // magic string
    private String filterMode = "NONE";         // another magic string
    private String trieType = "STANDARD";       // yet another magic string

    public List<String> getSuggestions(String prefix) {
        // Step 1: Get raw suggestions from trie
        List<String> raw;
        if (trieType.equals("STANDARD")) {
            raw = standardTrie.search(prefix);
        } else if (trieType.equals("COMPRESSED")) {
            raw = compressedTrie.search(prefix);
        } else if (trieType.equals("TOPK")) {
            raw = topKTrie.search(prefix);
        }
        // Adding ternary search tree? -- more else-if...

        // Step 2: Rank the suggestions
        List<String> ranked;
        if (rankingMode.equals("FREQUENCY")) {
            ranked = raw.stream()
                .sorted((a, b) -> getFrequency(b) - getFrequency(a))
                .collect(Collectors.toList());
        } else if (rankingMode.equals("TIME_DECAY")) {
            long now = System.currentTimeMillis();
            ranked = raw.stream()
                .sorted((a, b) -> {
                    double scoreA = getFrequency(a) * Math.exp(-0.001 * (now - getLastSearched(a)));
                    double scoreB = getFrequency(b) * Math.exp(-0.001 * (now - getLastSearched(b)));
                    return Double.compare(scoreB, scoreA);
                })
                .collect(Collectors.toList());
        } else if (rankingMode.equals("PERSONALIZED")) {
            // 50 lines of user-history-weighted ranking inline...
            ranked = personalizedSort(raw, userId);
        }
        // Adding ML-based ranking? Trending boost? -- more else-if...

        // Step 3: Filter
        if (filterMode.equals("PROFANITY")) {
            ranked = ranked.stream()
                .filter(s -> !profanityList.contains(s.toLowerCase()))
                .collect(Collectors.toList());
        } else if (filterMode.equals("ADULT_CONTENT")) {
            // Another 20 lines...
            ranked = filterAdultContent(ranked);
        }
        // Adding region-specific filters? -- more else-if...

        return ranked.subList(0, Math.min(10, ranked.size()));
    }
}
```

**Problems with this approach:**
- `AutocompleteService` knows about every ranking algorithm's internals (SRP violation)
- Adding a new ranking/filter/trie requires modifying `AutocompleteService` (OCP violation)
- Cannot unit-test ranking, filtering, or trie in isolation
- Magic strings for mode selection -- no compile-time safety
- Ranking, filtering, and trie logic are tangled together in one giant method

### Clean Code -- With Strategy

```java
// --- Strategy 1: Ranking ---
public interface RankingStrategy {
    List<Suggestion> rank(List<Suggestion> suggestions, String query);
}

public class FrequencyRankingStrategy implements RankingStrategy {
    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, String query) {
        // (1) Sort by raw search frequency -- most popular first
        return suggestions.stream()
            .sorted(Comparator.comparingLong(Suggestion::getFrequency).reversed())
            .collect(Collectors.toList());
    }
}

public class TimeDecayRankingStrategy implements RankingStrategy {
    private static final double DECAY_FACTOR = 0.001;

    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, String query) {
        long now = System.currentTimeMillis();
        // (1) Score = frequency * e^(-decay * age)
        // Recent queries score higher even with lower raw frequency
        return suggestions.stream()
            .sorted(Comparator.comparingDouble((Suggestion s) -> {
                long ageMs = now - s.getLastSearchedAt().toEpochMilli();
                return s.getFrequency() * Math.exp(-DECAY_FACTOR * ageMs);
            }).reversed())
            .collect(Collectors.toList());
    }
}

// --- Strategy 2: Filtering ---
public interface FilterStrategy {
    List<Suggestion> filter(List<Suggestion> suggestions);
}

public class ProfanityFilterStrategy implements FilterStrategy {
    private final Set<String> blocklist;

    public ProfanityFilterStrategy(Set<String> blocklist) {
        this.blocklist = blocklist;
    }

    @Override
    public List<Suggestion> filter(List<Suggestion> suggestions) {
        return suggestions.stream()
            .filter(s -> !blocklist.contains(s.getText().toLowerCase()))
            .collect(Collectors.toList());
    }
}

// --- Strategy 3: Trie ---
public interface Trie {
    void insert(String word, long frequency);
    List<Suggestion> search(String prefix, int limit);
    int size();
}

public class StandardTrie implements Trie {
    private final TrieNode root = new TrieNode();

    @Override
    public void insert(String word, long frequency) {
        TrieNode current = root;
        for (char c : word.toCharArray()) {
            current = current.children.computeIfAbsent(c, k -> new TrieNode());
        }
        current.isEndOfWord = true;
        current.frequency = frequency;
    }

    @Override
    public List<Suggestion> search(String prefix, int limit) {
        TrieNode node = findNode(prefix);
        if (node == null) return Collections.emptyList();
        List<Suggestion> results = new ArrayList<>();
        dfs(node, new StringBuilder(prefix), results, limit);
        return results;
    }
}

public class CompressedTrie implements Trie {
    // Radix tree: merges single-child chains into one node
    // "app" + "apple" + "application" share compressed prefix "app"
    private final CompressedTrieNode root = new CompressedTrieNode("");

    @Override
    public void insert(String word, long frequency) {
        // Walk down, split edges when prefix diverges
        insertRecursive(root, word, 0, frequency);
    }

    @Override
    public List<Suggestion> search(String prefix, int limit) {
        CompressedTrieNode node = findNodeForPrefix(prefix);
        if (node == null) return Collections.emptyList();
        List<Suggestion> results = new ArrayList<>();
        collectSuggestions(node, new StringBuilder(prefix), results, limit);
        return results;
    }
}
```

### AutocompleteService -- Uses Strategies (Doesn't Know the Algorithm)

```java
public class AutocompleteService {
    private final Trie trie;                         // injected
    private final RankingStrategy rankingStrategy;   // injected
    private final FilterStrategy filterStrategy;     // injected
    private final SuggestionCache cache;

    public List<Suggestion> getSuggestions(SearchQuery query) {
        // (1) Check cache first
        Optional<List<Suggestion>> cached = cache.get(query.getPrefix());
        if (cached.isPresent()) {
            return cached.get();
        }

        // (2) Search trie for raw candidates -- we don't know WHICH trie
        List<Suggestion> raw = trie.search(query.getPrefix(), query.getLimit() * 3);

        // (3) Filter -- we don't know WHICH filter
        List<Suggestion> filtered = filterStrategy.filter(raw);

        // (4) Rank -- we don't know WHICH ranking
        List<Suggestion> ranked = rankingStrategy.rank(filtered, query.getPrefix());

        // (5) Trim to requested limit
        List<Suggestion> result = ranked.stream()
            .limit(query.getLimit())
            .collect(Collectors.toList());

        // (6) Cache the result
        cache.put(query.getPrefix(), result);

        return result;
    }
}
```

### Numbered Call Chain -- getSuggestions("app") with Frequency Ranking

```
  Client         AutocompleteService    SuggestionCache    CompressedTrie      ProfanityFilter    FrequencyRanking
    |                   |                    |                   |                   |                   |
    | (1) getSuggestions|                    |                   |                   |                   |
    |   ("app", k=5)   |                    |                   |                   |                   |
    |------------------>|                    |                   |                   |                   |
    |                   | (2) cache.get      |                   |                   |                   |
    |                   |   ("app")          |                   |                   |                   |
    |                   |------------------->|                   |                   |                   |
    |                   |   MISS             |                   |                   |                   |
    |                   |<-------------------|                   |                   |                   |
    |                   |                    |                   |                   |                   |
    |                   | (3) trie.search    |                   |                   |                   |
    |                   |   ("app", 15)      |                   |                   |                   |
    |                   |---------------------------------------->|                   |                   |
    |                   |                    |                   |                   |                   |
    |                   |                    |  (4) walk to node |                   |                   |
    |                   |                    |    "app", DFS     |                   |                   |
    |                   |                    |    collect words  |                   |                   |
    |                   |                    |                   |                   |                   |
    |                   |  [apple, app store,|application,       |                   |                   |
    |                   |   applebees, ...]  |                   |                   |                   |
    |                   |<----------------------------------------|                   |                   |
    |                   |                    |                   |                   |                   |
    |                   | (5) filter.filter  |                   |                   |                   |
    |                   |   (raw suggestions)|                   |                   |                   |
    |                   |-------------------------------------------------------------->|                   |
    |                   |   [apple, app store, application, ...] (blocklist removed) |                   |
    |                   |<--------------------------------------------------------------|                   |
    |                   |                    |                   |                   |                   |
    |                   | (6) ranking.rank   |                   |                   |                   |
    |                   |   (filtered, "app")|                   |                   |                   |
    |                   |---------------------------------------------------------------------------->|
    |                   |   [app store(9M), apple(7M), application(5M), applebees(3M), apple music(2M)]|
    |                   |<----------------------------------------------------------------------------|
    |                   |                    |                   |                   |                   |
    |                   | (7) cache.put      |                   |                   |                   |
    |                   |   ("app", top5)    |                   |                   |                   |
    |                   |------------------->|                   |                   |                   |
    |                   |                    |                   |                   |                   |
    |  [app store,      |                    |                   |                   |                   |
    |   apple, ...]     |                    |                   |                   |                   |
    |<------------------|                    |                   |                   |                   |
```

### Interview One-Liner

> "We use Strategy three times: RankingStrategy lets us swap Frequency/TimeDecay/Personalized ranking without touching AutocompleteService, FilterStrategy lets us plug in ProfanityFilter or region-specific filters, and the Trie interface lets us swap Standard/Compressed/TopK tries. Each axis of variation is independently swappable -- classic OCP."

### Cross-Reference

| Project | Strategy Used For |
|---------|------------------|
| 01 - URL Shortener | `EncodingStrategy` (Base62, MD5) |
| 02 - Rate Limiter | `RateLimitStrategy` (Fixed Window, Sliding Window, Token Bucket) |
| 06 - Parking Lot | `ParkingStrategy`, `PricingStrategy`, `PaymentProcessor` (x3) |
| 07 - Distributed Cache | `EvictionStrategy` (LRU, LFU, TTL), `HashingStrategy` (Consistent, Mod) |
| 08 - Ride Sharing | `MatchingStrategy` (Nearest, ETA), `PricingStrategy` (Standard, Surge) |
| **09 - Search Autocomplete** | **`RankingStrategy` (Frequency, TimeDecay), `FilterStrategy` (Profanity), `Trie` (Standard, Compressed)** |

---

## 2. Builder Pattern

### What

Separate the construction of a complex object from its representation. A `SearchQuery` has 6+ fields including optional personalization dimensions (language, location, user context). `AutocompleteConfig` has 10+ tuning parameters. Builder prevents telescoping constructors and makes construction self-documenting.

### ASCII Diagram

```
  +----------------------------+            +----------------------------+
  | SearchQuery                |            | AutocompleteConfig         |
  +----------------------------+            +----------------------------+
  | - prefix: String           |            | - maxSuggestions: int      |
  | - limit: int               |            | - trieType: TrieType       |
  | - language: String         |            | - rankingMode: RankingMode |
  | - location: Location       |            | - cacheTTLSeconds: int     |
  | - userId: String           |            | - cacheMaxSize: int        |
  | - includePersonalized: bool|            | - profanityFilterEnabled:  |
  | - timestamp: Instant       |            |     boolean                |
  +----------------------------+            | - minPrefixLength: int     |
            |                               | - decayFactor: double      |
            | uses                          | - trieRebuildIntervalMins: |
            v                               |     int                    |
  +----------------------------+            | - cacheWarmingEnabled: bool|
  | SearchQuery.Builder        |            +----------------------------+
  +----------------------------+                       |
  | + prefix(String): Builder  |                       | uses
  | + limit(int): Builder      |                       v
  | + language(String): Builder|            +----------------------------+
  | + location(Loc): Builder   |            | AutocompleteConfig.Builder |
  | + userId(String): Builder  |            +----------------------------+
  | + personalized(bool): Bldr |            | + maxSuggestions(int): Bldr|
  | + build(): SearchQuery     |            | + trieType(TrieType): Bldr|
  +----------------------------+            | + cacheTTL(int): Builder   |
                                            | + build(): Config          |
                                            +----------------------------+
```

### Ugly Code -- Without Builder

```java
// ANTI-PATTERN: telescoping constructor with 7+ parameters
// Which parameter is which? What's the order? What's optional?
public class SearchQuery {
    public SearchQuery(String prefix, int limit, String language,
                       double latitude, double longitude, String userId,
                       boolean includePersonalized, long timestamp) {
        this.prefix = prefix;
        this.limit = limit;
        this.language = language;
        this.latitude = latitude;
        this.longitude = longitude;
        this.userId = userId;
        this.includePersonalized = includePersonalized;
        this.timestamp = timestamp;
    }
}

// Callers have no idea what the arguments mean:
SearchQuery q1 = new SearchQuery("app", 10, "en", 37.77, -122.41, "user123", true, 1700000000L);
SearchQuery q2 = new SearchQuery("app", 10, null, 0.0, 0.0, null, false, 0L); // which are defaults?

// Config is even worse -- 10+ parameters
AutocompleteConfig cfg = new AutocompleteConfig(
    10, "COMPRESSED", "FREQUENCY", 300, 100000,
    true, 1, 0.001, 60, true    // What do these mean?!
);
```

**Problems with this approach:**
- Caller must memorize parameter order -- latitude vs longitude swap = silent bug
- No way to distinguish required from optional fields
- Defaults are scattered across call sites
- Adding a new field breaks all existing callers

### Clean Code -- With Builder

```java
public class SearchQuery {
    private final String prefix;          // required
    private final int limit;              // default 10
    private final String language;        // optional
    private final Location location;      // optional
    private final String userId;          // optional
    private final boolean personalized;   // default false
    private final Instant timestamp;      // default now

    private SearchQuery(Builder builder) {
        this.prefix = Objects.requireNonNull(builder.prefix, "prefix is required");
        this.limit = builder.limit;
        this.language = builder.language;
        this.location = builder.location;
        this.userId = builder.userId;
        this.personalized = builder.personalized;
        this.timestamp = builder.timestamp;
    }

    public static Builder builder(String prefix) {
        return new Builder(prefix);
    }

    public static class Builder {
        private final String prefix;                        // required
        private int limit = 10;                             // sensible default
        private String language = "en";                     // sensible default
        private Location location;                          // optional
        private String userId;                              // optional
        private boolean personalized = false;               // default off
        private Instant timestamp = Instant.now();          // default now

        private Builder(String prefix) {
            this.prefix = prefix;
        }

        public Builder limit(int limit) {
            if (limit <= 0 || limit > 25) throw new IllegalArgumentException("limit must be 1-25");
            this.limit = limit;
            return this;
        }

        public Builder language(String language) { this.language = language; return this; }
        public Builder location(Location location) { this.location = location; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder personalized(boolean personalized) { this.personalized = personalized; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }

        public SearchQuery build() {
            return new SearchQuery(this);
        }
    }

    // Getters omitted for brevity
}

// Usage -- reads like English:
SearchQuery query = SearchQuery.builder("app")
    .limit(5)
    .language("en")
    .location(Location.of(37.77, -122.41))
    .userId("user123")
    .personalized(true)
    .build();

// Simple query -- only required fields + defaults:
SearchQuery simple = SearchQuery.builder("weather").build();
```

### AutocompleteConfig.Builder

```java
public class AutocompleteConfig {
    private final int maxSuggestions;
    private final TrieType trieType;
    private final int cacheTTLSeconds;
    private final int cacheMaxSize;
    private final boolean profanityFilterEnabled;
    private final int minPrefixLength;
    private final double decayFactor;
    private final int trieRebuildIntervalMinutes;
    private final boolean cacheWarmingEnabled;

    private AutocompleteConfig(Builder builder) { /* assign all fields */ }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int maxSuggestions = 10;
        private TrieType trieType = TrieType.COMPRESSED;
        private int cacheTTLSeconds = 300;
        private int cacheMaxSize = 100_000;
        private boolean profanityFilterEnabled = true;
        private int minPrefixLength = 1;
        private double decayFactor = 0.001;
        private int trieRebuildIntervalMinutes = 60;
        private boolean cacheWarmingEnabled = true;

        public Builder maxSuggestions(int v) { this.maxSuggestions = v; return this; }
        public Builder trieType(TrieType v) { this.trieType = v; return this; }
        public Builder cacheTTLSeconds(int v) { this.cacheTTLSeconds = v; return this; }
        // ... rest follow same pattern
        public AutocompleteConfig build() { return new AutocompleteConfig(this); }
    }
}

// Usage:
AutocompleteConfig config = AutocompleteConfig.builder()
    .maxSuggestions(5)
    .trieType(TrieType.COMPRESSED)
    .cacheTTLSeconds(600)
    .profanityFilterEnabled(true)
    .cacheWarmingEnabled(true)
    .build();
```

### Numbered Call Chain -- Building a Personalized Query

```
  API Controller          SearchQuery.Builder         SearchQuery (immutable)
       |                        |                          |
       | (1) SearchQuery        |                          |
       |   .builder("app")     |                          |
       |----------------------->|                          |
       |   returns Builder      |                          |
       |<-----------------------|                          |
       |                        |                          |
       | (2) .limit(5)          |                          |
       |----------------------->|  limit = 5               |
       |                        |                          |
       | (3) .language("en")    |                          |
       |----------------------->|  language = "en"         |
       |                        |                          |
       | (4) .userId("u123")    |                          |
       |----------------------->|  userId = "u123"         |
       |                        |                          |
       | (5) .personalized(true)|                          |
       |----------------------->|  personalized = true     |
       |                        |                          |
       | (6) .build()           |                          |
       |----------------------->|                          |
       |                        | (7) new SearchQuery(this)|
       |                        |------------------------->|
       |                        |                          |
       |   immutable            |                          |
       |   SearchQuery          |                          |
       |<---------------------------------------------------
```

### Interview One-Liner

> "SearchQuery has 7 fields -- prefix is required, but language, location, and userId are optional personalization dimensions. Builder makes construction self-documenting and lets us add new dimensions (like device type or A/B experiment ID) without breaking any caller."

### Cross-Reference

| Project | Builder Used For |
|---------|-----------------|
| 01 - URL Shortener | `ShortenedUrl.Builder` (URL + metadata) |
| 04 - Chat System | `Message.Builder` (content + metadata + attachments) |
| 08 - Ride Sharing | `Ride.Builder` (12+ fields, lifecycle states) |
| **09 - Search Autocomplete** | **`SearchQuery.Builder` (prefix + personalization), `AutocompleteConfig.Builder` (10+ tuning params)** |

---

## 3. Factory Pattern

### What

Centralize object creation in one place. Only `AppConfig` knows which concrete classes to instantiate and how to wire them together. No other class uses `new ConcreteClass()` for service objects.

### ASCII Diagram

```
  +------------------------------------------------------------------+
  |                        AppConfig (FACTORY)                        |
  +------------------------------------------------------------------+
  | + createTrie(): Trie                                              |
  |     -> new CompressedTrie()                                       |
  |                                                                   |
  | + createRankingStrategy(): RankingStrategy                        |
  |     -> new FrequencyRankingStrategy()                             |
  |                                                                   |
  | + createFilterStrategy(): FilterStrategy                          |
  |     -> new ProfanityFilterStrategy(loadBlocklist())               |
  |                                                                   |
  | + createCache(): SuggestionCache                                  |
  |     -> new LRUSuggestionCache(config.getCacheMaxSize())           |
  |                                                                   |
  | + createQueryRepository(): QueryRepository                        |
  |     -> new InMemoryQueryRepository()                              |
  |                                                                   |
  | + createDataCollectionService(): DataCollectionService            |
  |     -> new DataCollectionService(queryRepository)                 |
  |                                                                   |
  | + createAutocompleteService(): AutocompleteService                |
  |     -> new AutocompleteService(trie, ranking, filter, cache)      |
  +------------------------------------------------------------------+
                                 |
                    creates all these objects:
                                 |
       +----------+---------+--------+----------+-----------+
       |          |         |        |          |           |
       v          v         v        v          v           v
   Compressed  Frequency  Profanity  LRU     InMemory   DataCollection
   Trie        Ranking    Filter     Cache   QueryRepo  Service
```

### Ugly Code -- Without Factory

```java
// ANTI-PATTERN: new ConcreteClass() scattered across the codebase
// Changing the trie type requires finding and changing 5 different files
public class Main {
    public static void main(String[] args) {
        // Who decides which trie to use? Main? A controller? A config file?
        StandardTrie trie = new StandardTrie();  // hardcoded concrete type

        // Ranking strategy created inline with hardcoded blocklist path
        FrequencyRankingStrategy ranking = new FrequencyRankingStrategy();

        // Filter created with hardcoded blocklist
        Set<String> blocklist = new HashSet<>(Arrays.asList("badword1", "badword2"));
        ProfanityFilterStrategy filter = new ProfanityFilterStrategy(blocklist);

        // Cache created with hardcoded size
        LRUSuggestionCache cache = new LRUSuggestionCache(10000);

        // Everything wired manually -- miss one dependency = NPE at runtime
        AutocompleteService service = new AutocompleteService(
            trie, ranking, filter, cache
        );
    }
}

// Problems:
// 1. Concrete types everywhere -- changing Trie implementation touches multiple files
// 2. No central place to see what's wired to what
// 3. Test setup duplicates all this wiring
// 4. Can't swap implementations for different environments
```

### Clean Code -- With Factory

```java
public class AppConfig {
    private final AutocompleteConfig config;

    public AppConfig(AutocompleteConfig config) {
        this.config = config;
    }

    public Trie createTrie() {
        return switch (config.getTrieType()) {
            case STANDARD -> new StandardTrie();
            case COMPRESSED -> new CompressedTrie();
        };
    }

    public RankingStrategy createRankingStrategy() {
        return switch (config.getRankingMode()) {
            case FREQUENCY -> new FrequencyRankingStrategy();
            case TIME_DECAY -> new TimeDecayRankingStrategy(config.getDecayFactor());
        };
    }

    public FilterStrategy createFilterStrategy() {
        if (!config.isProfanityFilterEnabled()) {
            return suggestions -> suggestions;  // no-op filter (lambda)
        }
        Set<String> blocklist = loadBlocklist();
        return new ProfanityFilterStrategy(blocklist);
    }

    public SuggestionCache createCache() {
        return new LRUSuggestionCache(
            config.getCacheMaxSize(),
            config.getCacheTTLSeconds()
        );
    }

    public QueryRepository createQueryRepository() {
        return new InMemoryQueryRepository();
    }

    public DataCollectionService createDataCollectionService(QueryRepository repo) {
        return new DataCollectionService(repo);
    }

    public AutocompleteService createAutocompleteService() {
        Trie trie = createTrie();
        RankingStrategy ranking = createRankingStrategy();
        FilterStrategy filter = createFilterStrategy();
        SuggestionCache cache = createCache();

        return new AutocompleteService(trie, ranking, filter, cache);
    }

    private Set<String> loadBlocklist() {
        // Load from file or remote config
        return Set.of("badword1", "badword2", "offensive_term");
    }
}
```

### Numbered Call Chain -- Application Bootstrap

```
  Main             AppConfig          AutocompleteConfig.Builder       Service Objects
    |                  |                       |                            |
    | (1) new          |                       |                            |
    |   AppConfig(cfg) |                       |                            |
    |----------------->|                       |                            |
    |                  |                       |                            |
    | (2) create       |                       |                            |
    |   Autocomplete   |                       |                            |
    |   Service()      |                       |                            |
    |----------------->|                       |                            |
    |                  | (3) createTrie()       |                            |
    |                  |   -> CompressedTrie   |                            |
    |                  |                       |                            |
    |                  | (4) createRanking()    |                            |
    |                  |   -> FrequencyRanking  |                            |
    |                  |                       |                            |
    |                  | (5) createFilter()     |                            |
    |                  |   -> ProfanityFilter   |                            |
    |                  |                       |                            |
    |                  | (6) createCache()      |                            |
    |                  |   -> LRUCache(100K)    |                            |
    |                  |                       |                            |
    |                  | (7) new Autocomplete   |                            |
    |                  |   Service(trie,        |                            |
    |                  |   ranking, filter,     |                            |
    |                  |   cache)               |                            |
    |                  |-------------------------------------------------->|
    |                  |                       |                            |
    |  service ready   |                       |                            |
    |<-----------------|                       |                            |
```

### Interview One-Liner

> "AppConfig is the only class that says `new CompressedTrie()` or `new FrequencyRankingStrategy()`. To switch from Standard to Compressed trie, we change one line in AppConfig -- zero changes in AutocompleteService or any consumer."

### Cross-Reference

| Project | Factory Used For |
|---------|-----------------|
| 01 - URL Shortener | `AppConfig` wires encoding + repository + cache |
| 02 - Rate Limiter | `AppConfig` wires rate limit strategies + storage |
| 07 - Distributed Cache | `AppConfig` wires eviction + hashing + storage |
| **09 - Search Autocomplete** | **`AppConfig` wires trie + ranking + filter + cache** |

---

## 4. Repository Pattern

### What

Encapsulate data access behind a collection-like interface. The domain (`AutocompleteService`, `DataCollectionService`) works with `QueryRepository` -- it has no idea if data lives in a HashMap, Redis, Cassandra, or a file.

### ASCII Diagram

```
  +---------------------------+
  | <<interface>>             |
  | QueryRepository           |
  +---------------------------+
  | + save(QueryRecord)       |
  | + findByPrefix(prefix):   |
  |     List<QueryRecord>     |
  | + getFrequency(query):    |
  |     long                  |
  | + incrementFrequency(     |
  |     query)                |
  | + getTopQueries(k):       |
  |     List<QueryRecord>     |
  +-------------+-------------+
                |
                | implements
                |
  +-------------v-------------+
  | InMemoryQueryRepository   |
  +---------------------------+
  | - queries: Map<String,    |
  |     QueryRecord>          |
  | - frequencyIndex:         |
  |     TreeMap<Long, Set>    |
  +---------------------------+
  | + save(QueryRecord)       |
  | + findByPrefix(prefix):   |
  |     List<QueryRecord>     |
  | + getFrequency(query):    |
  |     long                  |
  | + incrementFrequency(q)   |
  | + getTopQueries(k):       |
  |     List<QueryRecord>     |
  +---------------------------+
```

### Ugly Code -- Without Repository

```java
// ANTI-PATTERN: Data access logic scattered across multiple services
public class AutocompleteService {
    private final Map<String, Long> queryFrequencies = new ConcurrentHashMap<>(); // storage leaked

    public void recordSearch(String query) {
        // Direct map manipulation in the service layer
        queryFrequencies.merge(query, 1L, Long::sum);

        // Now we need top-K -- sorting logic in the service
        List<Map.Entry<String, Long>> sorted = queryFrequencies.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(1000)
            .collect(Collectors.toList());
        // What if we want to persist to disk? Change this method.
        // What if we want Redis? Change this method.
        // What if we want Cassandra? Change this method. In 5 places.
    }
}
```

### Clean Code -- With Repository

```java
public interface QueryRepository {
    void save(QueryRecord record);
    List<QueryRecord> findByPrefix(String prefix);
    long getFrequency(String query);
    void incrementFrequency(String query);
    List<QueryRecord> getTopQueries(int limit);
}

public class InMemoryQueryRepository implements QueryRepository {
    private final Map<String, QueryRecord> queries = new ConcurrentHashMap<>();

    @Override
    public void save(QueryRecord record) {
        queries.put(record.getQuery(), record);
    }

    @Override
    public List<QueryRecord> findByPrefix(String prefix) {
        return queries.values().stream()
            .filter(r -> r.getQuery().startsWith(prefix))
            .collect(Collectors.toList());
    }

    @Override
    public long getFrequency(String query) {
        QueryRecord record = queries.get(query);
        return record != null ? record.getFrequency() : 0;
    }

    @Override
    public void incrementFrequency(String query) {
        queries.compute(query, (k, existing) -> {
            if (existing == null) {
                return new QueryRecord(query, 1, Instant.now());
            }
            return existing.withFrequency(existing.getFrequency() + 1);
        });
    }

    @Override
    public List<QueryRecord> getTopQueries(int limit) {
        return queries.values().stream()
            .sorted(Comparator.comparingLong(QueryRecord::getFrequency).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }
}
```

### Interview One-Liner

> "QueryRepository abstracts away storage. Our in-memory implementation is perfect for interviews and testing. In production, swap to RedisQueryRepository or CassandraQueryRepository -- zero changes in AutocompleteService or DataCollectionService."

### Cross-Reference

| Project | Repository Used For |
|---------|-------------------|
| 01 - URL Shortener | `UrlRepository` -> `InMemoryUrlRepository` |
| 06 - Parking Lot | `ParkingSpotRepository`, `TicketRepository` |
| 08 - Ride Sharing | `RideRepository`, `DriverRepository`, `RiderRepository` |
| **09 - Search Autocomplete** | **`QueryRepository` -> `InMemoryQueryRepository`** |

---

## 5. Facade Pattern

### What

Provide a unified interface to a set of interfaces in a subsystem. `AutocompleteService` is the facade -- it orchestrates Trie lookup, ranking, filtering, caching, and data collection. Callers interact with ONE method: `getSuggestions()`.

### ASCII Diagram

```
  +---------------------------------------------------------------------+
  |                     AutocompleteService (FACADE)                     |
  +---------------------------------------------------------------------+
  | + getSuggestions(query): List<Suggestion>                            |
  | + recordSearch(query): void                                         |
  | + rebuildTrie(): void                                               |
  +---------------------------------------------------------------------+
       |              |              |              |              |
       v              v              v              v              v
  +----------+  +----------+  +----------+  +----------+  +----------+
  |   Trie   |  | Ranking  |  | Filter   |  | Cache    |  | DataColl |
  | (search) |  | Strategy |  | Strategy |  | (LRU)    |  | Service  |
  +----------+  +----------+  +----------+  +----------+  +----------+
```

### Ugly Code -- Without Facade

```java
// ANTI-PATTERN: Client must orchestrate 5 subsystems manually
// Every API endpoint duplicates this dance
public class SearchController {
    private final CompressedTrie trie;
    private final FrequencyRankingStrategy ranking;
    private final ProfanityFilterStrategy filter;
    private final LRUSuggestionCache cache;
    private final DataCollectionService dataCollection;

    public List<String> handleAutocomplete(String prefix) {
        // Client knows the entire flow -- too much coupling
        List<String> cached = cache.get(prefix);
        if (cached != null) return cached;

        List<Suggestion> raw = trie.search(prefix, 30);
        List<Suggestion> filtered = filter.filter(raw);
        List<Suggestion> ranked = ranking.rank(filtered, prefix);
        List<Suggestion> top = ranked.subList(0, Math.min(10, ranked.size()));

        cache.put(prefix, top);
        dataCollection.recordPrefixSearch(prefix);  // analytics

        return top.stream().map(Suggestion::getText).collect(Collectors.toList());
    }

    // DUPLICATED in MobileController, DesktopController, InternalToolController...
}
```

### Clean Code -- With Facade

```java
public class AutocompleteService {
    private final Trie trie;
    private final RankingStrategy rankingStrategy;
    private final FilterStrategy filterStrategy;
    private final SuggestionCache cache;

    // FACADE: one method hides 5 subsystems
    public List<Suggestion> getSuggestions(SearchQuery query) {
        // (1) Check cache
        Optional<List<Suggestion>> cached = cache.get(query.getPrefix());
        if (cached.isPresent()) return cached.get();

        // (2) Search trie
        List<Suggestion> raw = trie.search(query.getPrefix(), query.getLimit() * 3);

        // (3) Filter
        List<Suggestion> filtered = filterStrategy.filter(raw);

        // (4) Rank
        List<Suggestion> ranked = rankingStrategy.rank(filtered, query.getPrefix());

        // (5) Trim + cache
        List<Suggestion> result = ranked.stream()
            .limit(query.getLimit())
            .collect(Collectors.toList());
        cache.put(query.getPrefix(), result);

        return result;
    }

    public void recordSearch(String query) {
        // Facade delegates to data collection
        dataCollectionService.recordSearch(query);
    }
}

// Controller is now trivially simple:
public class SearchController {
    private final AutocompleteService autocompleteService;  // ONLY dependency

    public List<String> handleAutocomplete(String prefix) {
        SearchQuery query = SearchQuery.builder(prefix).build();
        return autocompleteService.getSuggestions(query)
            .stream().map(Suggestion::getText).collect(Collectors.toList());
    }
}
```

### Interview One-Liner

> "AutocompleteService is a Facade -- it hides the complexity of trie lookup, ranking, filtering, and caching behind a single `getSuggestions()` call. Controllers never know about tries or ranking algorithms. Adding a new subsystem (like A/B testing) means changing only the Facade."

### Cross-Reference

| Project | Facade Used For |
|---------|----------------|
| 01 - URL Shortener | `UrlShorteningService` (encode + persist + cache) |
| 06 - Parking Lot | `ParkingLotService` (assign + ticket + payment) |
| 08 - Ride Sharing | `RideService` (match + price + payment + notify) |
| **09 - Search Autocomplete** | **`AutocompleteService` (trie + rank + filter + cache)** |

---

## 6. Observer Pattern

### What

Define a one-to-many dependency so that when one object changes state, all dependents are notified automatically. `DataCollectionService` observes search events to update query frequencies and analytics without coupling the serving path.

### ASCII Diagram

```
  +---------------------------+           +---------------------------+
  | AutocompleteService       |           | <<interface>>             |
  | (Subject / Event Source)  |           | SearchEventListener       |
  +---------------------------+           +---------------------------+
  | - listeners: List<>       |           | + onSearchCompleted(      |
  | + addListener(listener)   |---------->|     SearchEvent)          |
  | + getSuggestions(query)   |           +-------------+-------------+
  |   -> fires event after    |                         |
  |      returning results    |               +---------+---------+
  +---------------------------+               |                   |
                                              v                   v
                                +-------------+---+   +-----------+-------+
                                | DataCollection  |   | Analytics         |
                                | Service         |   | Service           |
                                +-----------------+   +-------------------+
                                | increments query|   | logs search       |
                                | frequency in    |   | latency, prefix   |
                                | repository      |   | distribution      |
                                +-----------------+   +-------------------+
```

### Ugly Code -- Without Observer

```java
// ANTI-PATTERN: AutocompleteService directly calls analytics and data collection
// Adding a new listener = modifying AutocompleteService = OCP violation
public class AutocompleteService {
    private final DataCollectionService dataService;   // tight coupling
    private final AnalyticsService analytics;           // tight coupling
    private final ABTestingService abTesting;           // tight coupling

    public List<Suggestion> getSuggestions(SearchQuery query) {
        List<Suggestion> results = // ... search logic ...

        // EMBEDDED: data collection in the hot path
        dataService.recordSearch(query.getPrefix());

        // EMBEDDED: analytics in the hot path
        analytics.logSearchLatency(query.getPrefix(), latencyMs);

        // EMBEDDED: A/B testing in the hot path
        abTesting.recordVariant(query.getUserId(), results);

        // Adding click-through tracking? Modify this method. Again.
        return results;
    }
}
```

### Clean Code -- With Observer

```java
public interface SearchEventListener {
    void onSearchCompleted(SearchEvent event);
}

public class SearchEvent {
    private final String prefix;
    private final String selectedSuggestion;
    private final int resultCount;
    private final long latencyMs;
    private final Instant timestamp;

    // Builder or constructor...
}

public class DataCollectionService implements SearchEventListener {
    private final QueryRepository queryRepository;

    @Override
    public void onSearchCompleted(SearchEvent event) {
        // (1) Increment frequency for the selected suggestion
        if (event.getSelectedSuggestion() != null) {
            queryRepository.incrementFrequency(event.getSelectedSuggestion());
        }
    }
}

public class AnalyticsService implements SearchEventListener {
    @Override
    public void onSearchCompleted(SearchEvent event) {
        // (1) Log latency for monitoring
        metrics.recordLatency("autocomplete.latency", event.getLatencyMs());
        // (2) Track prefix distribution (Zipf analysis)
        metrics.incrementCounter("autocomplete.prefix." + event.getPrefix().length());
    }
}

// AutocompleteService fires events -- doesn't know who listens
public class AutocompleteService {
    private final List<SearchEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(SearchEventListener listener) {
        listeners.add(listener);
    }

    public List<Suggestion> getSuggestions(SearchQuery query) {
        long start = System.nanoTime();

        List<Suggestion> results = // ... search logic (trie + filter + rank) ...

        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Fire event -- async, non-blocking
        SearchEvent event = new SearchEvent(query.getPrefix(), null,
            results.size(), latencyMs, Instant.now());
        listeners.forEach(l -> l.onSearchCompleted(event));

        return results;
    }
}
```

### Numbered Call Chain -- Search with Observer Notification

```
  Client       AutocompleteService     Trie     DataCollectionService   AnalyticsService
    |                |                   |               |                     |
    | (1) get        |                   |               |                     |
    |   Suggestions  |                   |               |                     |
    |   ("app")      |                   |               |                     |
    |--------------->|                   |               |                     |
    |                | (2) trie.search   |               |                     |
    |                |   ("app", 15)     |               |                     |
    |                |------------------>|               |                     |
    |                |   [suggestions]   |               |                     |
    |                |<------------------|               |                     |
    |                |                   |               |                     |
    |                | (3) filter + rank |               |                     |
    |                |   (internal)      |               |                     |
    |                |                   |               |                     |
    |                | (4) fire SearchEvent              |                     |
    |                |   (prefix="app",  |               |                     |
    |                |    latency=2ms)   |               |                     |
    |                |---------------------------------->|                     |
    |                |                   |               | (5) increment       |
    |                |                   |               |   frequency("app")  |
    |                |                   |               |                     |
    |                |------------------------------------------------------>|
    |                |                   |               |      (6) record     |
    |                |                   |               |      latency(2ms)   |
    |                |                   |               |                     |
    |  [results]     |                   |               |                     |
    |<---------------|                   |               |                     |
```

### Interview One-Liner

> "DataCollectionService observes search events via the Observer pattern. When a user searches, AutocompleteService fires a SearchEvent. DataCollectionService increments query frequency, AnalyticsService logs latency -- both without AutocompleteService knowing they exist. Adding click-through tracking means adding a new listener, zero changes to serving."

### Cross-Reference

| Project | Observer Used For |
|---------|------------------|
| 03 - Notification System | `NotificationListener` observes domain events |
| 08 - Ride Sharing | `NotificationService` observes ride state changes |
| **09 - Search Autocomplete** | **`DataCollectionService` observes search events, updates frequencies** |

---

## 7. Decorator Pattern

### What

Attach additional responsibilities to an object dynamically. `PersonalizedRankingStrategy` wraps any base `RankingStrategy`, boosting suggestions that match the user's search history. The base strategy does not know it's being decorated.

### ASCII Diagram

```
  +---------------------------+
  | <<interface>>             |
  | RankingStrategy           |
  +---------------------------+
  | + rank(suggestions,       |
  |   query): List<Suggestion>|
  +----------+----------------+
             |
       +-----+------+
       |             |
+------+------+ +---+------------------+
| Frequency   | | PersonalizedRanking  |
| Ranking     | | Strategy (DECORATOR) |
| Strategy    | +----------------------+
| (concrete)  | | - base: Ranking      |
|             | |     Strategy         |  <-- wraps any base
+-------------+ | - userHistory:       |
                |     UserHistoryCache |
                +----------------------+
                | + rank(suggestions,  |
                |   query):            |
                |   List<Suggestion>   |
                +----------------------+
                         |
                         | (1) calls base.rank()
                         | (2) boosts user-relevant results
                         | (3) re-sorts
```

### Ugly Code -- Without Decorator

```java
// ANTI-PATTERN: Personalization logic INSIDE FrequencyRankingStrategy
// Now FrequencyRankingStrategy has two responsibilities
public class FrequencyRankingStrategy implements RankingStrategy {
    private final UserHistoryCache userHistoryCache;  // leaked dependency

    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, String query) {
        // Step 1: sort by frequency (this is the base behavior)
        List<Suggestion> ranked = suggestions.stream()
            .sorted(Comparator.comparingLong(Suggestion::getFrequency).reversed())
            .collect(Collectors.toList());

        // Step 2: IF personalized, boost user's past queries (TANGLED!)
        if (userHistoryCache != null) {
            String userId = ThreadLocal.get(); // gross -- userId via ThreadLocal!?
            if (userId != null) {
                Set<String> userHistory = userHistoryCache.getHistory(userId);
                ranked.sort((a, b) -> {
                    boolean aInHistory = userHistory.contains(a.getText());
                    boolean bInHistory = userHistory.contains(b.getText());
                    if (aInHistory && !bInHistory) return -1;
                    if (!aInHistory && bInHistory) return 1;
                    return Long.compare(b.getFrequency(), a.getFrequency());
                });
            }
        }
        return ranked;
    }
}

// Problems:
// 1. FrequencyRankingStrategy now knows about user history (SRP violation)
// 2. Cannot use personalization with TimeDecayRankingStrategy
// 3. ThreadLocal hack for userId is fragile
// 4. Cannot disable personalization without a code change
```

### Clean Code -- With Decorator

```java
public class PersonalizedRankingStrategy implements RankingStrategy {
    private final RankingStrategy base;              // wraps ANY ranking strategy
    private final UserHistoryCache userHistoryCache;
    private static final double PERSONALIZATION_BOOST = 2.0;

    public PersonalizedRankingStrategy(RankingStrategy base,
                                        UserHistoryCache userHistoryCache) {
        this.base = base;
        this.userHistoryCache = userHistoryCache;
    }

    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, String query) {
        // (1) Delegate to base strategy first (Frequency, TimeDecay, etc.)
        List<Suggestion> baseRanked = base.rank(suggestions, query);

        // (2) Get user's search history
        String userId = query.getUserId();
        if (userId == null) {
            return baseRanked;  // no personalization, return base ranking
        }
        Set<String> userHistory = userHistoryCache.getHistory(userId);
        if (userHistory.isEmpty()) {
            return baseRanked;
        }

        // (3) Boost suggestions that appear in user's history
        return baseRanked.stream()
            .sorted(Comparator.comparingDouble((Suggestion s) -> {
                double baseScore = baseRanked.indexOf(s);  // original rank
                if (userHistory.contains(s.getText())) {
                    return baseScore / PERSONALIZATION_BOOST; // boost by 2x
                }
                return baseScore;
            }))
            .collect(Collectors.toList());
    }
}

// Usage in AppConfig -- decoration is transparent:
public RankingStrategy createRankingStrategy() {
    RankingStrategy base = new FrequencyRankingStrategy();

    if (config.isPersonalizationEnabled()) {
        return new PersonalizedRankingStrategy(base, userHistoryCache);
        // Can also wrap TimeDecayRankingStrategy!
    }
    return base;
}
```

### Numbered Call Chain -- Personalized Ranking

```
  AutocompleteService     PersonalizedRanking      FrequencyRanking      UserHistoryCache
         |                        |                       |                     |
         | (1) rank(suggestions,  |                       |                     |
         |   query)               |                       |                     |
         |----------------------->|                       |                     |
         |                        | (2) base.rank         |                     |
         |                        |   (suggestions, query)|                     |
         |                        |---------------------->|                     |
         |                        |   [app store(9M),     |                     |
         |                        |    apple(7M),         |                     |
         |                        |    applebees(3M)]     |                     |
         |                        |<----------------------|                     |
         |                        |                       |                     |
         |                        | (3) getHistory        |                     |
         |                        |   (userId="u123")     |                     |
         |                        |---------------------------------------------->|
         |                        |   {"applebees",       |                     |
         |                        |    "apple music"}     |                     |
         |                        |<----------------------------------------------|
         |                        |                       |                     |
         |                        | (4) boost "applebees" |                     |
         |                        |   by 2x (user         |                     |
         |                        |   searched it before) |                     |
         |                        |                       |                     |
         |   [app store(9M),      |                       |                     |
         |    applebees(3M*BOOST),|                       |                     |
         |    apple(7M)]          |                       |                     |
         |<-----------------------|                       |                     |
```

### Interview One-Liner

> "PersonalizedRankingStrategy wraps any base RankingStrategy (Frequency, TimeDecay) and boosts suggestions from the user's search history. The base strategy does not know it's being decorated. We can stack decorators: Personalized wraps TimeDecay wraps Frequency -- each adds a layer without modifying the others."

### Cross-Reference

| Project | Decorator Used For |
|---------|-------------------|
| 07 - Distributed Cache | `LoggingCache` wraps `DistributedCache` (logging layer) |
| 08 - Ride Sharing | `SurgePricingStrategy` wraps `StandardPricingStrategy` |
| **09 - Search Autocomplete** | **`PersonalizedRankingStrategy` wraps base `RankingStrategy`** |

---

## 8. Singleton Pattern

### What

Ensure a class has only one instance and provide a global point of access. `AutocompleteConfig` is a Singleton -- there's exactly one configuration object shared across all services. No two services should have different config values for `maxSuggestions` or `cacheTTL`.

### ASCII Diagram

```
  +-----------------------------------+
  | AutocompleteConfig (SINGLETON)    |
  +-----------------------------------+
  | - INSTANCE: AutocompleteConfig    |  <-- single instance
  | - maxSuggestions: int             |
  | - trieType: TrieType             |
  | - cacheTTLSeconds: int            |
  | - cacheMaxSize: int               |
  | - profanityFilterEnabled: boolean |
  | - minPrefixLength: int            |
  | - decayFactor: double             |
  +-----------------------------------+
  | + getInstance(): AutocompleteConfig|
  +-----------------------------------+
           |             |            |
           v             v            v
     Autocomplete    DataCollection  AppConfig
     Service         Service         (Factory)
     (reads config)  (reads config)  (reads config)
```

### Ugly Code -- Without Singleton

```java
// ANTI-PATTERN: Multiple config objects with potentially different values
public class AutocompleteService {
    private final int maxSuggestions = 10;       // hardcoded here
    private final int cacheTTL = 300;            // hardcoded here
}

public class DataCollectionService {
    private final int maxSuggestions = 15;       // DIFFERENT VALUE! Bug!
    private final int cacheTTL = 600;            // DIFFERENT VALUE! Bug!
}

// Or worse: each service reads the config file independently
// and they might read it at different times, getting different values
// during a rolling config update
```

### Clean Code -- With Singleton

```java
public class AutocompleteConfig {
    private static volatile AutocompleteConfig INSTANCE;

    private final int maxSuggestions;
    private final TrieType trieType;
    private final int cacheTTLSeconds;
    private final int cacheMaxSize;
    private final boolean profanityFilterEnabled;
    private final int minPrefixLength;
    private final double decayFactor;
    private final int trieRebuildIntervalMinutes;
    private final boolean cacheWarmingEnabled;

    // Private constructor -- only accessible via Builder
    private AutocompleteConfig(Builder builder) {
        this.maxSuggestions = builder.maxSuggestions;
        this.trieType = builder.trieType;
        this.cacheTTLSeconds = builder.cacheTTLSeconds;
        this.cacheMaxSize = builder.cacheMaxSize;
        this.profanityFilterEnabled = builder.profanityFilterEnabled;
        this.minPrefixLength = builder.minPrefixLength;
        this.decayFactor = builder.decayFactor;
        this.trieRebuildIntervalMinutes = builder.trieRebuildIntervalMinutes;
        this.cacheWarmingEnabled = builder.cacheWarmingEnabled;
    }

    public static void initialize(Builder builder) {
        if (INSTANCE != null) {
            throw new IllegalStateException("Config already initialized");
        }
        synchronized (AutocompleteConfig.class) {
            if (INSTANCE == null) {
                INSTANCE = builder.build();
            }
        }
    }

    public static AutocompleteConfig getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("Config not yet initialized. Call initialize() first.");
        }
        return INSTANCE;
    }

    // All getters are read-only -- config is immutable after creation
    public int getMaxSuggestions() { return maxSuggestions; }
    public TrieType getTrieType() { return trieType; }
    public int getCacheTTLSeconds() { return cacheTTLSeconds; }
    // ... etc

    public static class Builder {
        // Builder fields with defaults -- see Builder Pattern section above
        private AutocompleteConfig build() { return new AutocompleteConfig(this); }
    }
}

// Bootstrap:
AutocompleteConfig.initialize(
    AutocompleteConfig.builder()
        .maxSuggestions(10)
        .trieType(TrieType.COMPRESSED)
        .cacheTTLSeconds(300)
);

// Any service reads the same config:
int max = AutocompleteConfig.getInstance().getMaxSuggestions(); // always 10
```

### Interview One-Liner

> "AutocompleteConfig is a Singleton initialized at startup via Builder, then shared read-only across all services. This guarantees every service sees the same `maxSuggestions=10` and `cacheTTL=300`. The Singleton is immutable after creation -- no synchronization needed for reads."

### Cross-Reference

| Project | Singleton Used For |
|---------|-------------------|
| 07 - Distributed Cache | `CacheConfig` (single config for cluster) |
| 08 - Ride Sharing | `QuadTree` (one spatial index per LocationService) |
| **09 - Search Autocomplete** | **`AutocompleteConfig` (single config for all services)** |

---

## 9. Iterator Pattern

### What

Provide a way to access elements of an aggregate object sequentially without exposing its underlying representation. The Trie traversal uses a DFS iterator that lazily yields suggestions without materializing the entire subtree. Callers iterate with `hasNext()`/`next()` -- they don't know about nodes, children maps, or recursion.

### ASCII Diagram

```
  +---------------------------+          +---------------------------+
  | <<interface>>             |          | <<interface>>             |
  | Iterable<Suggestion>      |          | Iterator<Suggestion>      |
  +---------------------------+          +---------------------------+
  | + iterator():             |          | + hasNext(): boolean      |
  |     Iterator<Suggestion>  |          | + next(): Suggestion      |
  +----------+----------------+          +----------+----------------+
             |                                      |
             |                                      |
  +----------v----------------+          +----------v----------------+
  | TriePrefixIterable        |          | TrieDFSIterator           |
  +---------------------------+          +---------------------------+
  | - trie: Trie              |          | - stack: Deque<Frame>     |
  | - prefix: String          |          | - currentSuggestion:      |
  +---------------------------+          |     Suggestion             |
  | + iterator():             |          +---------------------------+
  |     new TrieDFSIterator() |          | + hasNext(): boolean      |
  +---------------------------+          | + next(): Suggestion      |
                                         +---------------------------+

  Stack-based DFS traversal:
  +---------+
  | Frame   |
  +---------+
  | node    |
  | prefix  |
  | childIx |  (index into sorted children)
  +---------+
```

### Ugly Code -- Without Iterator

```java
// ANTI-PATTERN: Trie.search() materializes ALL suggestions into a list
// For prefix "a", this could be 50,000+ words -- OOM risk
public class StandardTrie {
    public List<Suggestion> search(String prefix) {
        TrieNode node = findNode(prefix);
        if (node == null) return Collections.emptyList();

        // Collect EVERYTHING under this node into memory
        List<Suggestion> allSuggestions = new ArrayList<>();
        collectAll(node, new StringBuilder(prefix), allSuggestions);
        // If prefix is "a", allSuggestions could have 50,000 entries
        // We only need top 10!

        // Sort ALL of them...
        allSuggestions.sort(Comparator.comparingLong(Suggestion::getFrequency).reversed());

        // ...just to take the first 10
        return allSuggestions.subList(0, Math.min(10, allSuggestions.size()));
    }

    private void collectAll(TrieNode node, StringBuilder prefix,
                             List<Suggestion> results) {
        if (node.isEndOfWord) {
            results.add(new Suggestion(prefix.toString(), node.frequency));
        }
        for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
            prefix.append(entry.getKey());
            collectAll(entry.getValue(), prefix, results);  // recursive!
            prefix.deleteCharAt(prefix.length() - 1);
        }
    }
}

// Problems:
// 1. For short prefixes, materializes thousands of suggestions
// 2. Memory usage proportional to all words with the prefix
// 3. Cannot lazily stop after finding K results
// 4. Exposes internal trie structure (TrieNode, children map)
```

### Clean Code -- With Iterator

```java
public class TriePrefixIterable implements Iterable<Suggestion> {
    private final TrieNode startNode;
    private final String prefix;

    public TriePrefixIterable(TrieNode startNode, String prefix) {
        this.startNode = startNode;
        this.prefix = prefix;
    }

    @Override
    public Iterator<Suggestion> iterator() {
        return new TrieDFSIterator(startNode, prefix);
    }
}

public class TrieDFSIterator implements Iterator<Suggestion> {
    private final Deque<Frame> stack = new ArrayDeque<>();
    private Suggestion next;

    private static class Frame {
        final TrieNode node;
        final String prefix;
        final Iterator<Map.Entry<Character, TrieNode>> childIterator;

        Frame(TrieNode node, String prefix) {
            this.node = node;
            this.prefix = prefix;
            this.childIterator = new TreeMap<>(node.children).entrySet().iterator();
        }
    }

    public TrieDFSIterator(TrieNode startNode, String prefix) {
        stack.push(new Frame(startNode, prefix));
        advance(); // find first suggestion
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public Suggestion next() {
        if (next == null) throw new NoSuchElementException();
        Suggestion current = next;
        advance(); // find next suggestion lazily
        return current;
    }

    private void advance() {
        next = null;
        while (!stack.isEmpty() && next == null) {
            Frame frame = stack.peek();

            // Check if current node is a word endpoint (first visit)
            if (frame.node.isEndOfWord && !frame.visited) {
                frame.visited = true;
                next = new Suggestion(frame.prefix, frame.node.frequency);
                return;
            }

            // Try next child
            if (frame.childIterator.hasNext()) {
                Map.Entry<Character, TrieNode> child = frame.childIterator.next();
                stack.push(new Frame(child.getValue(),
                    frame.prefix + child.getKey()));
            } else {
                stack.pop(); // backtrack
            }
        }
    }
}

// Usage -- lazily iterate, stop after K suggestions:
public List<Suggestion> search(String prefix, int limit) {
    TrieNode node = findNode(prefix);
    if (node == null) return Collections.emptyList();

    List<Suggestion> results = new ArrayList<>();
    for (Suggestion s : new TriePrefixIterable(node, prefix)) {
        results.add(s);
        if (results.size() >= limit) break;  // STOP EARLY -- no wasted work
    }
    return results;
}
```

### Numbered Call Chain -- Lazy Trie Iteration

```
  AutocompleteService    TriePrefixIterable    TrieDFSIterator    Trie Internal Nodes
       |                       |                    |                    |
       | (1) search("app", 3)  |                    |                    |
       |   -> new Iterable     |                    |                    |
       |---------------------->|                    |                    |
       |                       | (2) iterator()     |                    |
       |                       |------------------->|                    |
       |                       |                    | (3) push Frame     |
       |                       |                    |   (node="app",     |
       |                       |                    |    prefix="app")   |
       |                       |                    |------------------->|
       |                       |                    |                    |
       | (4) hasNext()         |                    |                    |
       |-------------------------------------->|                    |
       |   true (found "app")  |                    |                    |
       |<--------------------------------------|                    |
       |                       |                    |                    |
       | (5) next()            |                    |                    |
       |-------------------------------------->|                    |
       |   Suggestion("app",   |                    |                    |
       |     freq=5000)        |                    |                    |
       |<--------------------------------------|                    |
       |                       |                    | (6) advance() ->  |
       |                       |                    |   push child "l"  |
       |                       |                    |   -> "appl"       |
       |                       |                    |                    |
       | (7) next()            |                    |                    |
       |-------------------------------------->|                    |
       |   Suggestion("apple", |                    |                    |
       |     freq=8000)        |                    | (8) advance() ->  |
       |<--------------------------------------|   push child "e"  |
       |                       |                    |                    |
       | (9) next()            |                    |                    |
       |-------------------------------------->|                    |
       |   Suggestion("apple   |                    |                    |
       |     music", freq=2000)|                    |                    |
       |<--------------------------------------|                    |
       |                       |                    |                    |
       | (10) STOP -- got 3    |                    |                    |
       |   results, break loop |                    |                    |
       |   (remaining nodes    |                    |                    |
       |    never visited!)    |                    |                    |
```

### Interview One-Liner

> "The Trie uses a stack-based DFS Iterator so we can lazily yield suggestions one at a time. For prefix 'a' with 50,000 matching words, we only visit nodes until we find K=10 suggestions, then stop. This avoids materializing the entire subtree into memory."

### Cross-Reference

| Project | Iterator Used For |
|---------|------------------|
| 07 - Distributed Cache | Iterating over cache entries for eviction scan |
| **09 - Search Autocomplete** | **DFS iterator over Trie nodes -- lazy suggestion traversal** |

---

## Pattern Interaction Map

How do all 9 patterns work together in a single `getSuggestions()` call:

```
  +------------------------------------------------------------------------+
  |  A SINGLE getSuggestions("app") CALL TOUCHES ALL 9 PATTERNS            |
  +------------------------------------------------------------------------+
  |                                                                        |
  |  (SINGLETON) AutocompleteConfig.getInstance()                          |
  |       |                                                                |
  |       v                                                                |
  |  (FACTORY) AppConfig created Trie, Ranking, Filter, Cache at startup   |
  |       |                                                                |
  |       v                                                                |
  |  (BUILDER) SearchQuery.builder("app").limit(5).build()                 |
  |       |                                                                |
  |       v                                                                |
  |  (FACADE) AutocompleteService.getSuggestions(query)                    |
  |       |                                                                |
  |       +----> (STRATEGY) trie.search("app", 15)                        |
  |       |          |                                                     |
  |       |          +----> (ITERATOR) TrieDFSIterator lazily yields       |
  |       |                  suggestions from CompressedTrie               |
  |       |                                                                |
  |       +----> (STRATEGY) filterStrategy.filter(raw)                     |
  |       |        ProfanityFilterStrategy removes blocked terms           |
  |       |                                                                |
  |       +----> (STRATEGY + DECORATOR) rankingStrategy.rank(filtered)     |
  |       |        PersonalizedRanking wraps FrequencyRanking              |
  |       |                                                                |
  |       +----> (OBSERVER) fire SearchEvent                               |
  |       |        DataCollectionService.onSearchCompleted()               |
  |       |            |                                                   |
  |       |            +----> (REPOSITORY) queryRepository.increment()     |
  |       |                                                                |
  |       +----> return results                                            |
  |                                                                        |
  +------------------------------------------------------------------------+
```

---

## Quick Interview Cheat Sheet

| Question | Answer |
|----------|--------|
| "How do you rank suggestions?" | Strategy pattern -- swap Frequency, TimeDecay, or Personalized at runtime |
| "How do you add personalization?" | Decorator -- PersonalizedRanking wraps any base RankingStrategy |
| "How do you build search queries?" | Builder -- required prefix + optional language/location/userId |
| "How do you wire everything together?" | Factory -- AppConfig creates and wires all objects |
| "Where is the data stored?" | Repository -- interface decouples from storage (in-memory, Redis, Cassandra) |
| "How do you simplify the API?" | Facade -- AutocompleteService hides trie + rank + filter + cache behind `getSuggestions()` |
| "How do you track search analytics?" | Observer -- DataCollectionService listens for SearchEvents |
| "How is config shared?" | Singleton -- immutable AutocompleteConfig.getInstance() |
| "How do you traverse the trie efficiently?" | Iterator -- stack-based DFS, lazily yields K suggestions without materializing subtree |
| "Which patterns interact?" | Builder creates query -> Facade routes to Strategy (trie) -> Iterator traverses -> Strategy (filter) -> Decorator (ranking) -> Observer (analytics) -> Repository (persist) |
