# Caching Strategies for the Search Autocomplete System

> Interview-ready reference for a Senior Java developer.
> Autocomplete caching is special: short prefixes ("a", "th") are HOT (millions of requests), long prefixes ("application settings") are COLD (few requests).
> Prefix popularity follows Zipf's law -- cache the short, evict the long.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| What to Cache | Prefix -> top-K suggestions (THE most important cache in the system) |
| What NOT to Cache | Full trie, user-specific suggestions (too unique) |
| Cache Key Design | prefix + language + location (personalization dimensions) |
| LRU Eviction | Short prefixes = hot, long prefixes = cold |
| Cache Warming | Pre-compute top 1000 prefixes at startup |
| Cache Invalidation | Invalidate on trie rebuild (blue-green swap) |
| Multi-Level Caching | Browser -> CDN -> App Cache -> Trie |
| Zipf Distribution | "a", "th", "wh" get most traffic |
| Cache Size Estimation | Memory budget for 100K cached prefixes |
| Interview Q&A | Ready-to-use answers |

---

## What to Cache vs What NOT to Cache

### The Caching Decision Matrix

```
  +--------------------------------------------------------------------+
  |                      CACHE DECISION MATRIX                         |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  CACHE (hot, shared)                 DON'T CACHE (unique, large)   |
  |  ============================        =============================  |
  |                                                                    |
  |  +---------------------+            +---------------------+        |
  |  | Prefix -> Top-K     |            | Full Trie Structure |        |
  |  | "app" -> [app store,|            | (already in memory) |        |
  |  |   apple, ...]       |            | Caching would be    |        |
  |  | TTL: 5-10 minutes   |            | double-buffering    |        |
  |  +---------------------+            +---------------------+        |
  |                                                                    |
  |  +---------------------+            +---------------------+        |
  |  | Popular Prefix List |            | Per-User Suggestions|        |
  |  | Top 1000 prefixes   |            | Too unique, low     |        |
  |  | for cache warming   |            | reuse across users  |        |
  |  | TTL: 1 hour         |            |                     |        |
  |  +---------------------+            +---------------------+        |
  |                                                                    |
  |  +---------------------+            +---------------------+        |
  |  | Profanity Blocklist |            | Raw Query Logs      |        |
  |  | (small, read-heavy) |            | (write-heavy, large)|        |
  |  | TTL: refresh on push|            | Stream via Kafka    |        |
  |  +---------------------+            +---------------------+        |
  |                                                                    |
  |  +---------------------+            +---------------------+        |
  |  | Language-Specific   |            | Analytics/Metrics   |        |
  |  | Suggestions         |            | (write-heavy, no    |        |
  |  | "app" in "en" vs    |            |  read in hot path)  |        |
  |  | "de" vs "ja"        |            |                     |        |
  |  | TTL: 10 minutes     |            +---------------------+        |
  |  +---------------------+                                           |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### Why Prefix -> Top-K Is THE Most Important Cache

```
  WITHOUT CACHE:
  User types "a" -> "ap" -> "app" -> "appl" -> "apple"
  That's 5 keystrokes = 5 trie searches

  Each trie search: ~10-20ms (DFS, filter, rank)
  Total: 50-100ms of CPU time per typed word

  At 10,000 concurrent users typing:
  50,000 trie searches per second = EXPENSIVE

  WITH CACHE:
  User types "a" -> cache HIT (2ms)
  User types "ap" -> cache HIT (2ms)
  User types "app" -> cache HIT (2ms)
  User types "appl" -> cache MISS -> trie search (15ms) -> cache
  User types "apple" -> cache HIT (2ms)

  Cache hit rate for popular prefixes: 95%+
  Effective load on trie: reduced 20x
```

### Detailed Cache/No-Cache Rationale

| Data | Cache? | TTL | Why |
|------|--------|-----|-----|
| Prefix -> Top-K suggestions | **YES** | 5-10 min | HIGHEST value cache. Millions of users type the same prefixes |
| Popular prefix list | **YES** | 1 hour | Zipf distribution changes slowly. Used for cache warming |
| Profanity blocklist | **YES** | Push-based | Small, read-heavy, changes rarely. Push-invalidate on update |
| Language-specific suggestions | **YES** | 10 min | "app" in English vs German vs Japanese -- different top-K |
| Location-specific suggestions | **YES** | 10 min | "pizza" in NYC vs "pizza" in London -- different restaurants |
| Full trie structure | **NO** | N/A | Already in memory. Caching it would be pointless double-buffering |
| Per-user personalized results | **NO** | N/A | Too unique -- 1M users means 1M variants per prefix. Low reuse |
| Raw query logs | **NO** | N/A | Write-heavy, no reads in serving path. Stream through Kafka |
| Analytics metrics | **NO** | N/A | Write-heavy, read-rarely. Different storage (time-series DB) |

---

## Cache Key Design

### Key Structure

```
  CACHE KEY FORMAT:
  autocomplete:{language}:{location}:{prefix}

  EXAMPLES:
  autocomplete:en:US:app          -> [app store, apple, application, ...]
  autocomplete:en:US:wea          -> [weather, weather channel, wear, ...]
  autocomplete:de:DE:app          -> [apple, app store, appetit, ...]
  autocomplete:ja:JP:app          -> [apple, app store, ...]
  autocomplete:en:US-CA:piz       -> [pizza hut, pizza near me, ...]
  autocomplete:en:US-NY:piz       -> [pizza hut, pizza rats, ...]

  WHY COMPOSITE KEYS?
  - "pizza" in New York should suggest "pizza rats" (local phenomenon)
  - "pizza" in California should suggest "pizza port" (local chain)
  - "app" in German should suggest "Appetit" (German word)
  - Same prefix, different results based on context
```

### Key Design Tradeoffs

| Key Design | Pros | Cons | Cache Entries |
|-----------|------|------|---------------|
| `prefix` only | Simple, max sharing | No personalization | 100K |
| `lang:prefix` | Language-specific results | More entries | 500K (5 langs * 100K) |
| `lang:country:prefix` | Region-specific results | Even more entries | 5M (50 countries * 100K) |
| `lang:country:city:prefix` | City-specific results | Explosion | 500M -- TOO MANY |
| `userId:prefix` | Per-user personalization | No sharing at all | Infinite -- WRONG |

### Recommended Key Design

```
  +------------------------------------------------------------------+
  |  RECOMMENDED: Two-level key design                                |
  +------------------------------------------------------------------+
  |                                                                   |
  |  LEVEL 1: Shared cache (high hit rate)                            |
  |  Key: autocomplete:{language}:{prefix}                            |
  |  Example: autocomplete:en:app                                     |
  |  Entries: ~500K (5 languages * 100K prefixes)                     |
  |  Hit rate: 90%+                                                   |
  |                                                                   |
  |  LEVEL 2: Location-specific cache (moderate hit rate)             |
  |  Key: autocomplete:{language}:{country}:{prefix}                  |
  |  Example: autocomplete:en:US:pizza                                |
  |  Entries: ~2.5M (50 countries * 5 langs * 10K popular prefixes)   |
  |  Hit rate: 70%                                                    |
  |                                                                   |
  |  SKIP: Per-user cache                                             |
  |  Too many entries, too low reuse. Apply personalization as a      |
  |  post-processing step on the shared cache result (Decorator).     |
  +------------------------------------------------------------------+

  LOOKUP FLOW:
  (1) Check L2 (location-specific) -> HIT? Return.
  (2) Check L1 (language-specific) -> HIT? Apply location boost. Return.
  (3) MISS -> Query trie. Populate L1 + L2.
```

---

## LRU Eviction Strategy

### Why LRU for Autocomplete

```
  AUTOCOMPLETE ACCESS PATTERN:

  Prefix length distribution (Zipf):
  +----------------------------------------------------------------+
  | Prefix  | Example    | Requests/sec | Cache Value              |
  |---------|------------|-------------|---------------------------|
  | 1 char  | "a"        | 50,000      | EXTREMELY HOT             |
  | 2 chars | "ap"       | 20,000      | VERY HOT                  |
  | 3 chars | "app"      | 8,000       | HOT                       |
  | 4 chars | "appl"     | 2,000       | WARM                      |
  | 5 chars | "apple"    | 500         | COOL                      |
  | 6 chars | "apple "   | 100         | COLD                      |
  | 7+ chars| "apple mu" | 10          | ICE COLD                  |
  +----------------------------------------------------------------+

  LRU BEHAVIOR:
  - "a", "th", "wh" are accessed constantly -> NEVER evicted
  - "apple music lyrics 2024" is accessed once -> evicted quickly
  - This matches perfectly: keep hot prefixes, evict cold ones

  ALTERNATIVE EVICTION POLICIES:
  - LFU (Least Frequently Used): Also good, but requires frequency counters (overhead)
  - TTL-only: Poor -- hot entries expire unnecessarily
  - FIFO: Poor -- evicts "a" if it was inserted first, even though it's accessed constantly
  - LRU + TTL: BEST -- LRU for eviction order, TTL for staleness guarantee
```

### LRU Implementation Details

```java
public class LRUSuggestionCache implements SuggestionCache {
    private final int maxSize;
    private final long ttlMillis;
    private final Map<String, CacheEntry> cache;

    // Metrics for monitoring
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    public LRUSuggestionCache(int maxSize, int ttlSeconds) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlSeconds * 1000L;

        // LinkedHashMap with accessOrder=true -> LRU
        // removeEldestEntry auto-evicts when size exceeds max
        this.cache = Collections.synchronizedMap(
            new LinkedHashMap<>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    boolean shouldEvict = size() > maxSize;
                    if (shouldEvict) evictions.incrementAndGet();
                    return shouldEvict;
                }
            }
        );
    }

    @Override
    public Optional<List<Suggestion>> get(String key) {
        CacheEntry entry = cache.get(key);   // moves to tail (most recent)
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (System.currentTimeMillis() - entry.createdAt > ttlMillis) {
            cache.remove(key);               // TTL expired
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry.suggestions);
    }

    @Override
    public void put(String key, List<Suggestion> suggestions) {
        cache.put(key, new CacheEntry(suggestions, System.currentTimeMillis()));
    }

    @Override
    public void invalidateAll() {
        cache.clear();
    }

    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }
}
```

### Eviction Behavior Visualization

```
  Cache capacity: 5 entries (simplified for illustration)

  TIME   OPERATION                CACHE STATE (LRU order: left=oldest)
  ====   =========                ==========================================
  t=1    GET "a"    (miss)        []
         -> trie search
         PUT "a"                  [a]

  t=2    GET "ap"   (miss)        [a, ap]
         PUT "ap"                 [a, ap]

  t=3    GET "app"  (miss)        [a, ap, app]
         PUT "app"                [a, ap, app]

  t=4    GET "b"    (miss)        [a, ap, app, b]
         PUT "b"                  [a, ap, app, b]

  t=5    GET "c"    (miss)        [a, ap, app, b, c]     <- FULL
         PUT "c"                  [a, ap, app, b, c]

  t=6    GET "a"    (HIT!)        [ap, app, b, c, a]     <- "a" moves to tail (most recent)

  t=7    GET "d"    (miss)        [app, b, c, a, d]      <- "ap" EVICTED (least recently used)
         PUT "d"

  t=8    GET "a"    (HIT!)        [app, b, c, d, a]      <- "a" stays (frequently accessed)

  t=9    GET "e"    (miss)        [b, c, d, a, e]        <- "app" EVICTED
         PUT "e"

  OBSERVATION:
  - "a" is accessed frequently -> survives eviction
  - "ap" and "app" are accessed once -> evicted when space needed
  - This mirrors real autocomplete: "a" is typed by EVERYONE
```

---

## Cache Warming

### Why Cache Warming?

```
  COLD START PROBLEM:

  Server restarts at 3:00 AM.
  Cache is empty.
  First 1000 users all get cache misses.
  Each miss = trie search (10-20ms instead of 2ms).
  
  Latency spike:
  Before restart:  p50=2ms,  p99=5ms  (cache hot)
  After restart:   p50=15ms, p99=25ms (cache cold)
  5 minutes later: p50=2ms,  p99=5ms  (cache warm again)

  Cache warming eliminates the cold start spike.
```

### Cache Warming Strategy

```
  +------------------------------------------------------------------+
  |                    CACHE WARMING FLOW                              |
  +------------------------------------------------------------------+
  |                                                                   |
  |  STEP 1: Maintain list of top 1000 prefixes (offline)             |
  |  =====================================================            |
  |  These are computed from query logs:                              |
  |  Rank 1:  "a"          (50,000 req/s)                            |
  |  Rank 2:  "t"          (45,000 req/s)                            |
  |  Rank 3:  "w"          (40,000 req/s)                            |
  |  Rank 4:  "s"          (38,000 req/s)                            |
  |  ...                                                              |
  |  Rank 10: "th"         (30,000 req/s)                            |
  |  Rank 11: "wh"         (28,000 req/s)                            |
  |  ...                                                              |
  |  Rank 100: "app"       (8,000 req/s)                             |
  |  ...                                                              |
  |  Rank 1000: "faceb"    (200 req/s)                               |
  |                                                                   |
  |  STEP 2: On server startup, pre-populate cache                   |
  |  =====================================================            |
  |  for (String prefix : top1000Prefixes) {                         |
  |      List<Suggestion> results = trie.search(prefix, 10);         |
  |      cache.put(prefix, results);                                  |
  |  }                                                                |
  |                                                                   |
  |  Time: ~1000 * 15ms = 15 seconds                                 |
  |  After: 90%+ of traffic hits cached prefixes immediately          |
  |                                                                   |
  +------------------------------------------------------------------+
```

### Java Implementation

```java
public class CacheWarmingService {
    private final Trie trie;
    private final SuggestionCache cache;
    private final RankingStrategy rankingStrategy;
    private final FilterStrategy filterStrategy;
    private final List<String> topPrefixes;

    public CacheWarmingService(Trie trie, SuggestionCache cache,
                                RankingStrategy rankingStrategy,
                                FilterStrategy filterStrategy,
                                List<String> topPrefixes) {
        this.trie = trie;
        this.cache = cache;
        this.rankingStrategy = rankingStrategy;
        this.filterStrategy = filterStrategy;
        this.topPrefixes = topPrefixes;
    }

    /**
     * Pre-populate cache with suggestions for the top 1000 prefixes.
     * Called on server startup and after trie rebuild.
     */
    public void warmCache() {
        long start = System.currentTimeMillis();
        int warmed = 0;

        for (String prefix : topPrefixes) {
            // (1) Search trie
            List<Suggestion> raw = trie.search(prefix, 30);

            // (2) Filter
            List<Suggestion> filtered = filterStrategy.filter(raw);

            // (3) Rank
            List<Suggestion> ranked = rankingStrategy.rank(filtered, prefix);

            // (4) Trim and cache
            List<Suggestion> topK = ranked.stream()
                .limit(10)
                .collect(Collectors.toList());
            cache.put(prefix, topK);
            warmed++;
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Cache warming complete: {} prefixes in {}ms", warmed, elapsed);
    }
}
```

### Numbered Call Chain -- Cache Warming on Startup

```
  Main            AppConfig           CacheWarming         Trie          SuggestionCache
    |                |                    |                  |                |
    | (1) bootstrap  |                    |                  |                |
    |   application  |                    |                  |                |
    |--------------->|                    |                  |                |
    |                | (2) create         |                  |                |
    |                |   services         |                  |                |
    |                |                    |                  |                |
    |                | (3) warmCache()    |                  |                |
    |                |------------------->|                  |                |
    |                |                    |                  |                |
    |                |                    | (4) for each     |                |
    |                |                    |   top prefix:    |                |
    |                |                    |                  |                |
    |                |                    | (4a) trie.search |                |
    |                |                    |   ("a", 30)      |                |
    |                |                    |----------------->|                |
    |                |                    |   [suggestions]  |                |
    |                |                    |<-----------------|                |
    |                |                    |                  |                |
    |                |                    | (4b) filter+rank |                |
    |                |                    |   (internal)     |                |
    |                |                    |                  |                |
    |                |                    | (4c) cache.put   |                |
    |                |                    |   ("a", topK)    |                |
    |                |                    |------------------------------>|
    |                |                    |                  |                |
    |                |                    | ... repeat for   |                |
    |                |                    |   999 more       |                |
    |                |                    |   prefixes ...   |                |
    |                |                    |                  |                |
    |                |  warming done      |                  |                |
    |                |  (1000 prefixes    |                  |                |
    |                |   in ~15 seconds)  |                  |                |
    |                |<-------------------|                  |                |
    |                |                    |                  |                |
    |  server ready  |                    |                  |                |
    |  (cache hot!)  |                    |                  |                |
    |<---------------|                    |                  |                |
```

---

## Cache Invalidation on Trie Rebuild

### The Invalidation Problem

```
  SCENARIO: Trie rebuilds with updated frequencies.
  Cache still has OLD top-K for prefix "app".

  Before rebuild:
  Trie v42: "app" -> [app store(9M), apple(7M), application(5M)]
  Cache:    "app" -> [app store(9M), apple(7M), application(5M)]  <- matches

  After rebuild (trie v43):
  Trie v43: "app" -> [apple(12M), app store(9M), application(5M)]  <- apple surpassed!
  Cache:    "app" -> [app store(9M), apple(7M), application(5M)]  <- STALE!

  If we don't invalidate, users see stale rankings for up to TTL (5-10 minutes).
  For autocomplete, this is ACCEPTABLE but not ideal.
```

### Invalidation Strategies

| Strategy | How | Latency | Complexity |
|----------|-----|---------|-----------|
| Invalidate ALL on rebuild | `cache.clear()` after trie swap | 0ms (immediate) | Trivial |
| TTL-based expiry | Let entries expire naturally | 0-10 min (TTL) | Zero effort |
| Selective invalidation | Invalidate only changed prefixes | 0ms (immediate) | HIGH (diff old vs new trie) |
| Cache warming after clear | Clear + re-warm top 1000 | 15 seconds | Moderate |

### Recommended: Clear + Re-Warm

```
  +------------------------------------------------------------------+
  |  RECOMMENDED INVALIDATION FLOW                                    |
  +------------------------------------------------------------------+
  |                                                                   |
  |  (1) Build new trie (v43) in background  [5-30 minutes]          |
  |       |                                                           |
  |       v                                                           |
  |  (2) Atomic trie swap (AtomicReference)  [instant]               |
  |       |                                                           |
  |       v                                                           |
  |  (3) Clear entire suggestion cache       [instant]               |
  |       |                                                           |
  |       v                                                           |
  |  (4) Re-warm top 1000 prefixes           [~15 seconds]           |
  |       |                                                           |
  |       v                                                           |
  |  (5) Remaining cache entries fill organically from traffic        |
  |       |                                                           |
  |       v                                                           |
  |  Cache fully warm again in ~2 minutes (from traffic)              |
  |                                                                   |
  +------------------------------------------------------------------+

  DURING THE 15-SECOND WARMING WINDOW:
  - Top 1000 prefixes: served from cache (warming in progress)
  - Other prefixes: cache miss -> trie search -> populate cache
  - Latency impact: p50 stays 2ms (top 1000 covers 90% of traffic)
  -                 p99 spikes to 15ms for ~2 minutes (cold entries)
```

### Java Implementation

```java
public class TrieManager {
    private final AtomicReference<Trie> currentTrie;
    private final SuggestionCache cache;
    private final CacheWarmingService warmingService;

    public void swapTrie(Trie newTrie) {
        // (1) Atomic swap
        Trie old = currentTrie.getAndSet(newTrie);

        // (2) Clear cache (old results no longer valid)
        cache.invalidateAll();

        // (3) Re-warm cache with new trie's results
        // Run async so swap returns immediately
        CompletableFuture.runAsync(() -> warmingService.warmCache());

        log.info("Trie swapped: v{} -> v{}. Cache cleared + warming started.",
            old.getVersion(), newTrie.getVersion());
    }
}
```

---

## Multi-Level Caching

### The Four Cache Layers

```
  +------------------------------------------------------------------------+
  |                    MULTI-LEVEL CACHE ARCHITECTURE                       |
  +------------------------------------------------------------------------+
  |                                                                        |
  |  USER'S BROWSER                                                        |
  |  +-------------------------------------------------------------+      |
  |  | L1: Browser/Client Cache                                     |      |
  |  | - JavaScript stores recent suggestions in localStorage       |      |
  |  | - If user typed "app" 30 seconds ago, don't re-fetch         |      |
  |  | - TTL: 60 seconds                                            |      |
  |  | - Size: last 50 prefixes                                     |      |
  |  | - HIT RATE: 30-40% (same prefixes typed within session)      |      |
  |  +-------------------------------------------------------------+      |
  |              | MISS                                                    |
  |              v                                                         |
  |  CDN (CLOUDFLARE / CLOUDFRONT)                                         |
  |  +-------------------------------------------------------------+      |
  |  | L2: CDN Edge Cache                                           |      |
  |  | - Cache autocomplete API responses at edge locations          |      |
  |  | - Key: URL path + query string (/api/suggest?q=app&lang=en)  |      |
  |  | - TTL: 5 minutes                                             |      |
  |  | - HIT RATE: 60-70% (popular prefixes cached globally)        |      |
  |  | - Geo-aware: different edge locations, different results      |      |
  |  +-------------------------------------------------------------+      |
  |              | MISS                                                    |
  |              v                                                         |
  |  APPLICATION SERVER                                                    |
  |  +-------------------------------------------------------------+      |
  |  | L3: Application Cache (LRU in-process)                       |      |
  |  | - LinkedHashMap-based LRU cache                              |      |
  |  | - Key: autocomplete:en:app                                   |      |
  |  | - TTL: 5-10 minutes                                          |      |
  |  | - Size: 100K entries (~50 MB)                                |      |
  |  | - HIT RATE: 90%+ (covers Zipf distribution well)             |      |
  |  +-------------------------------------------------------------+      |
  |              | MISS                                                    |
  |              v                                                         |
  |  TRIE (IN-MEMORY DATA STRUCTURE)                                       |
  |  +-------------------------------------------------------------+      |
  |  | L4: Trie Lookup                                              |      |
  |  | - CompressedTrie.search(prefix, limit)                       |      |
  |  | - DFS traversal, filter, rank                                |      |
  |  | - Latency: 10-20ms                                           |      |
  |  | - Always available (the source of truth)                     |      |
  |  +-------------------------------------------------------------+      |
  |                                                                        |
  +------------------------------------------------------------------------+
```

### Effective Latency with Multi-Level Cache

```
  REQUEST FLOW FOR "app" (popular prefix):

  CASE 1: Browser cache HIT (30-40% of requests)
  User types "app" -> JavaScript checks localStorage -> found!
  Latency: 0ms (no network request at all)

  CASE 2: CDN cache HIT (another 30-40% of requests)
  User types "app" -> JS miss -> HTTP to CDN -> edge cache HIT
  Latency: 5-20ms (network to nearest CDN edge)

  CASE 3: App cache HIT (another 20-25% of requests)
  User types "app" -> JS miss -> CDN miss -> origin server -> LRU cache HIT
  Latency: 30-50ms (network to origin + 2ms cache lookup)

  CASE 4: Trie lookup (only 5-10% of requests)
  User types "app" -> JS miss -> CDN miss -> origin miss -> trie search
  Latency: 40-70ms (network to origin + 15ms trie search)

  +-----------------------------------------------------------+
  |            EFFECTIVE LATENCY BREAKDOWN                     |
  +-----------------------------------------------------------+
  |                                                           |
  |  Layer          | Hit Rate | Latency  | Traffic %         |
  |  ===============|==========|==========|==================|
  |  Browser cache  | 35%      | 0ms      | 35% of requests  |
  |  CDN cache      | 50%*     | 10ms     | 32% of requests  |
  |  App cache      | 90%*     | 35ms     | 30% of requests  |
  |  Trie lookup    | 100%     | 55ms     | 3% of requests   |
  |  ===============|==========|==========|==================|
  |  * of remaining traffic after higher-level hits            |
  |                                                           |
  |  Weighted average latency:                                |
  |  0.35*0 + 0.32*10 + 0.30*35 + 0.03*55 = ~14ms           |
  |                                                           |
  |  Without caching: 55ms for every request                  |
  |  With caching: 14ms average (4x improvement)              |
  +-----------------------------------------------------------+
```

### CDN Caching Details

```
  CDN CONFIGURATION:

  Cache-Control header from origin:
  Cache-Control: public, max-age=300, s-maxage=600
  
  - max-age=300: browser can cache for 5 minutes
  - s-maxage=600: CDN can cache for 10 minutes
  - public: shared cache (CDN) is allowed

  Vary header:
  Vary: Accept-Language
  
  - CDN caches separate versions for each language
  - en:app and de:app are different cache entries

  URL structure for CDN-friendly caching:
  GET /api/v1/suggest/en/US/app
  (path-based, not query params, for better CDN cache key generation)

  Stale-while-revalidate:
  Cache-Control: max-age=300, stale-while-revalidate=60
  
  - After 5 minutes: CDN serves stale AND fetches fresh in background
  - User gets instant response (stale but usable)
  - Next user gets fresh response
```

### Browser Cache Implementation

```javascript
// Client-side suggestion cache (JavaScript)
class SuggestionCache {
    constructor(maxEntries = 50, ttlMs = 60000) {
        this.maxEntries = maxEntries;
        this.ttlMs = ttlMs;
        this.cache = new Map(); // Map preserves insertion order
    }

    get(prefix) {
        const entry = this.cache.get(prefix);
        if (!entry) return null;
        if (Date.now() - entry.timestamp > this.ttlMs) {
            this.cache.delete(prefix);
            return null; // expired
        }
        // Move to end (most recently used)
        this.cache.delete(prefix);
        this.cache.set(prefix, entry);
        return entry.suggestions;
    }

    set(prefix, suggestions) {
        if (this.cache.size >= this.maxEntries) {
            // Delete oldest (first entry in Map)
            const firstKey = this.cache.keys().next().value;
            this.cache.delete(firstKey);
        }
        this.cache.set(prefix, { suggestions, timestamp: Date.now() });
    }
}

// Usage in autocomplete handler:
const cache = new SuggestionCache();

async function onInput(prefix) {
    // L1: Check browser cache
    const cached = cache.get(prefix);
    if (cached) {
        renderSuggestions(cached);
        return;
    }

    // L2+L3+L4: Fetch from server (CDN -> App -> Trie)
    const response = await fetch(`/api/v1/suggest/en/US/${prefix}`);
    const suggestions = await response.json();

    cache.set(prefix, suggestions);
    renderSuggestions(suggestions);
}
```

---

## Prefix Popularity and Zipf's Law

### The Zipf Distribution

```
  ZIPF'S LAW FOR SEARCH PREFIXES:

  The frequency of a prefix is inversely proportional to its rank.
  If rank-1 prefix gets 50,000 req/s, rank-2 gets ~25,000, rank-3 gets ~16,667.

  f(r) = C / r    where r = rank, C = constant

  +-----------------------------------------------------------+
  |  PREFIX FREQUENCY DISTRIBUTION                             |
  +-----------------------------------------------------------+
  |                                                           |
  |  Req/s                                                    |
  |  ^                                                        |
  |  |                                                        |
  |50K|*                                                      |
  |  | *                                                      |
  |  |  *                                                     |
  |25K|   *           Caching THESE gives 90%+ hit rate       |
  |  |    **          (top 1000 prefixes)                     |
  |  |      ***                                               |
  |10K|         ****                                          |
  |  |             ********                                   |
  | 5K|                    **************                     |
  |  |                                   ******************** |
  |  |                                                        |
  |  +----------------------------------------------------->  |
  |  1    10    100    1K     10K    100K   1M    10M   200M   |
  |  "a"  "th"  "app"  ...    ...    ...    ...   ...   tail   |
  |                                                           |
  +-----------------------------------------------------------+
```

### Top Prefixes by Request Volume

| Rank | Prefix | Why Popular | Approx Req/s |
|------|--------|------------|-------------|
| 1 | "a" | Most common first character in English | 50,000 |
| 2 | "t" | "the", "to", "twitter", "target" | 45,000 |
| 3 | "w" | "weather", "walmart", "what" | 40,000 |
| 4 | "s" | "spotify", "samsung", "snapchat" | 38,000 |
| 5 | "f" | "facebook", "flights", "food" | 35,000 |
| 6 | "h" | "hotels", "how", "home depot" | 33,000 |
| 7 | "m" | "maps", "mcdonalds", "movies" | 30,000 |
| 8 | "b" | "bank of america", "best buy" | 28,000 |
| 9 | "c" | "calculator", "craigslist" | 26,000 |
| 10 | "th" | "the", "things", "thanksgiving" | 25,000 |
| 50 | "wea" | "weather" (dominant completion) | 8,000 |
| 100 | "app" | "apple", "app store" | 5,000 |
| 500 | "faceb" | "facebook" (single completion dominates) | 500 |
| 1000 | "apple m" | "apple music" | 200 |

### Cache Efficiency by Prefix Length

```
  +-----------------------------------------------------------+
  |  CACHE EFFICIENCY: SHORT PREFIX = HIGH VALUE               |
  +-----------------------------------------------------------+
  |                                                           |
  |  Prefix   | Unique | Shared by  | Cache    | Worth       |
  |  Length    | Values | N Users    | Hit Rate | Caching?    |
  |  =========|========|============|==========|============|
  |  1 char   | 26     | ~2K users  | 99.9%    | ABSOLUTELY  |
  |  2 chars  | 676    | ~500 users | 99%      | YES         |
  |  3 chars  | ~5K    | ~100 users | 95%      | YES         |
  |  4 chars  | ~20K   | ~25 users  | 85%      | YES         |
  |  5 chars  | ~50K   | ~5 users   | 60%      | MAYBE       |
  |  6 chars  | ~100K  | ~1 user    | 20%      | UNLIKELY    |
  |  7+ chars | ~500K+ | <1 user    | 5%       | NO          |
  |  =========|========|============|==========|============|
  |                                                           |
  |  INSIGHT: Cache entries for 1-4 character prefixes        |
  |  give 95%+ cache hit rate with only ~26K entries.         |
  |  Beyond 5 characters, cache hit rate drops rapidly.       |
  +-----------------------------------------------------------+
```

---

## Cache Size Estimation

### Memory Budget Calculation

```
  GIVEN:
  - Cache 100K prefix entries
  - Each entry: prefix (string) + top-10 suggestions (list of strings)
  - Average suggestion: 15 characters
  - 10 suggestions per entry

  PER CACHE ENTRY:
  +-----------------------------------------------------------+
  | Component                | Size                            |
  |--------------------------|--------------------------------|
  | Key (prefix string)      | 40 bytes (header) + 10 chars   |
  |                          | = ~60 bytes                     |
  | Value: List<Suggestion>  | 10 suggestions *                |
  |                          |   (40 bytes header + 30 chars   |
  |                          |    + 8 bytes frequency)         |
  |                          | = 10 * ~78 = ~780 bytes         |
  | CacheEntry wrapper       | ~32 bytes (object + timestamp)  |
  | Map.Entry overhead       | ~48 bytes (LinkedHashMap node)  |
  |--------------------------|--------------------------------|
  | TOTAL PER ENTRY          | ~920 bytes (~1 KB)              |
  +-----------------------------------------------------------+

  TOTAL CACHE MEMORY:
  100,000 entries * 1 KB = ~100 MB

  WITH JVM OVERHEAD (GC, alignment):
  ~130 MB

  +-----------------------------------------------------------+
  |  CACHE SIZING RECOMMENDATIONS                             |
  +-----------------------------------------------------------+
  |                                                           |
  |  Scenario       | Entries | Memory  | Hit Rate            |
  |  ===============|=========|=========|====================|
  |  Minimal        | 10K     | 13 MB   | 85%                |
  |  Standard       | 100K    | 130 MB  | 95%                |
  |  Aggressive     | 500K    | 650 MB  | 98%                |
  |  Overkill       | 1M      | 1.3 GB  | 99%                |
  |  ===============|=========|=========|====================|
  |                                                           |
  |  RECOMMENDED: 100K entries (~130 MB)                      |
  |  Reason: 95% hit rate covers Zipf distribution well.      |
  |  Going from 95% to 98% requires 5x more memory (500K     |
  |  entries) -- diminishing returns.                         |
  +-----------------------------------------------------------+
```

### Redis vs In-Process Cache Comparison

```
  +-----------------------------------------------------------+
  |  IN-PROCESS (LinkedHashMap)    vs    REDIS                 |
  +-----------------------------------------------------------+
  |                                                           |
  |  In-Process:                   Redis:                     |
  |  + 0.01ms lookup               + 0.5ms lookup (network)  |
  |  + No serialization            + Shared across servers    |
  |  + No network hop              + Survives server restart  |
  |  - Per-server (no sharing)     + Can store more (64GB+)   |
  |  - Lost on restart             - Serialization overhead   |
  |  - Limited by JVM heap         - Network latency          |
  |                                                           |
  |  RECOMMENDED: BOTH                                        |
  |  L3a: In-process LRU (100K entries, 130MB)               |
  |  L3b: Redis (500K entries, shared across servers)          |
  |                                                           |
  |  Lookup order:                                            |
  |  (1) In-process cache -> HIT? Return (0.01ms)            |
  |  (2) Redis cache -> HIT? Return + populate L3a (0.5ms)   |
  |  (3) Trie search -> Return + populate L3a + L3b (15ms)   |
  +-----------------------------------------------------------+
```

---

## Cache Behavior During Trie Lifecycle

### Full Lifecycle: Build -> Deploy -> Warm -> Serve -> Rebuild

```
  +------------------------------------------------------------------------+
  |                    FULL TRIE + CACHE LIFECYCLE                          |
  +------------------------------------------------------------------------+
  |                                                                        |
  |  t=0 min   TRIE BUILD STARTS (v43)                                     |
  |            Cache serving from trie v42 -- business as usual             |
  |            Cache hit rate: 95%                                          |
  |                                                                        |
  |  t=30 min  TRIE BUILD COMPLETE (v43 ready)                             |
  |            |                                                           |
  |            +-- (1) AtomicReference swap: v42 -> v43                    |
  |            +-- (2) cache.invalidateAll()                               |
  |            +-- (3) Start async cache warming                           |
  |            |                                                           |
  |            Cache hit rate: 0% (just cleared!)                          |
  |                                                                        |
  |  t=30:15   CACHE WARMING COMPLETE (top 1000 prefixes)                  |
  |            Cache hit rate: 90% (top 1000 covers 90% of traffic)        |
  |                                                                        |
  |  t=32 min  ORGANIC FILL (traffic populates remaining entries)          |
  |            Cache hit rate: 93%                                          |
  |                                                                        |
  |  t=35 min  CACHE FULLY WARM (back to steady state)                     |
  |            Cache hit rate: 95%                                          |
  |                                                                        |
  |  t=60 min  NEXT TRIE BUILD STARTS (v44)                               |
  |            Cycle repeats...                                            |
  |                                                                        |
  +------------------------------------------------------------------------+

  IMPACT WINDOW: ~5 minutes of slightly elevated latency
  - During minutes 30-35, some requests hit trie instead of cache
  - p50: 2ms -> 5ms (still well under 100ms SLA)
  - p99: 5ms -> 20ms (still acceptable)
  - After minute 35: back to normal
```

---

## Interview Q&A

| Question | Answer |
|----------|--------|
| "What's the most important thing to cache?" | "Prefix to top-K suggestion mappings. For example, 'app' -> [app store, apple, application]. This is the single highest-value cache because millions of users type the same short prefixes. Short prefixes like 'a', 'th', 'wh' are typed by everyone -- caching these gives 90%+ hit rate." |
| "How do you design the cache key?" | "Composite key: language + prefix. For example, 'autocomplete:en:app'. We avoid per-user keys because they destroy sharing. Personalization is applied as a post-processing step (Decorator pattern) on the shared cached result." |
| "What eviction policy?" | "LRU with TTL. Short prefixes like 'a' are accessed constantly and never evicted. Long prefixes like 'apple music lyrics' are accessed once and evicted quickly. This matches Zipf's law perfectly -- hot entries stay, cold entries go." |
| "How do you handle cold start?" | "Cache warming. On startup, we pre-compute suggestions for the top 1000 prefixes (identified from query logs). Takes about 15 seconds. After warming, 90%+ of traffic hits cache immediately -- no cold start latency spike." |
| "How do you invalidate?" | "On trie rebuild: clear entire cache and re-warm top 1000 prefixes. The cache is fully warm again in about 5 minutes from a combination of warming (15s) and organic traffic. The 5-minute impact window is acceptable because stale suggestions would have been fine too." |
| "Multi-level caching?" | "Four levels: (1) Browser localStorage (0ms, 30-40% hit), (2) CDN edge (10ms, 50% of remaining), (3) In-process LRU (0.01ms, 90% of remaining), (4) Trie lookup (15ms, the rest). Effective average latency: ~14ms. Without caching: 55ms for every request." |
| "Cache size?" | "100K entries at ~1KB each = ~130MB. This gives 95% hit rate. Going to 500K entries (650MB) only improves to 98% -- diminishing returns. The Zipf distribution means a small cache covers most traffic." |
| "Why not cache per-user results?" | "Cache sharing is destroyed. 1M users * 100K prefixes = 100 billion entries -- impossible. Instead, cache the shared top-K and apply personalization as a Decorator on the cached result. The base cached suggestions are 80%+ correct even without personalization." |

---

## Cross-Reference: Caching Across Projects

| Project | What's Cached | Eviction | Special Consideration |
|---------|--------------|----------|---------------------|
| 01 - URL Shortener | shortCode -> longURL | TTL (24h) | Read-heavy, rarely changes |
| 02 - Rate Limiter | clientId -> counter | TTL (window size) | Write-heavy, accuracy matters |
| 04 - Chat System | userId -> presence | TTL (30s) | Stale "online" OK |
| 07 - Distributed Cache | arbitrary K/V | LRU/LFU/TTL | IS the cache |
| 08 - Ride Sharing | driver locations | TTL (3-5s) | Geo-based keys, inherently stale |
| **09 - Autocomplete** | **prefix -> top-K** | **LRU + TTL** | **Zipf distribution, cache warming, multi-level** |
