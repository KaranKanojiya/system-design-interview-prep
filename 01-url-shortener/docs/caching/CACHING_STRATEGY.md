# Caching Strategy for URL Shortener

> The redirect path is the hottest code path in the system. Caching determines whether you hit 5ms or 50ms latency.

---

## Why Caching Matters Here

```
  Without cache:  Client -> LB -> Service -> Cassandra -> Service -> Client
                  ~20-50ms round trip

  With cache:     Client -> LB -> Service -> Redis -> Service -> Client
                  ~2-5ms round trip

  With local cache: Client -> LB -> Service (Caffeine hit) -> Client
                     ~0.5-1ms round trip
```

The 80-20 rule applies strongly to URL shorteners: **20% of URLs receive 80% of traffic.** A viral tweet with a short URL may get millions of clicks in minutes. Caching the hot set dramatically reduces database load.

---

## Cache Key Design

```
  Key:    url:{shortCode}
  Value:  originalUrl (just the string -- keep it small)

  Example:
  Key:    url:abc123
  Value:  https://www.example.com/very/long/path/to/page?utm_source=twitter

  Why not cache the full Url object?
  - Redirect only needs the original URL
  - Smaller values = more entries in cache = better hit rate
  - Analytics data changes frequently (click counts) -- don't cache it
```

### Optional: Cache Metadata Separately

```
  url:abc123          -> "https://example.com/..."       (hot path, long TTL)
  url:abc123:meta     -> { userId, expiresAt, createdAt } (cold path, short TTL)
  url:abc123:clicks   -> 14523                            (counter, separate TTL)
```

---

## TTL Strategy

| URL Type | TTL | Reasoning |
|----------|-----|-----------|
| General URLs | 24 hours | Balance between freshness and cache hit rate |
| Viral/trending URLs | 1 hour | Higher churn; may be deleted or expire |
| Custom alias URLs | 24 hours | Stable; rarely change |
| Expired URLs | Cache tombstone, 5 min | Prevent repeated DB lookups for expired URLs |
| 404 (not found) | 1 minute | Negative caching; prevents DB hammering for bad codes |

### Why Not Cache Forever?

- URLs can be deleted or expire.
- Memory is finite; LRU eviction needs room to work.
- 24-hour TTL means worst-case staleness is 24 hours after deletion (acceptable with active invalidation).

---

## Invalidation Strategy

### Cache-Aside (Lazy Loading) -- Primary Pattern

```
  READ PATH (redirect):
  
  1. Check cache (Redis)
     |
     +-- HIT  --> Return cached URL --> 302 redirect
     |
     +-- MISS --> Query Cassandra
                     |
                     +-- FOUND --> Write to cache (with TTL) --> 302 redirect
                     |
                     +-- NOT FOUND --> Cache "NOT_FOUND" tombstone (short TTL) --> 404
```

```
  WRITE PATH (create URL):
  
  1. Write to Cassandra
  2. Write to Redis (write-through for new URLs)
  
  Why write-through on create?
  - New URLs are often accessed immediately after creation
  - Creator shares the link and clicks it to verify
  - Avoids a guaranteed cache miss on first access
```

```
  DELETE PATH:
  
  1. Delete from Cassandra
  2. Delete from Redis (active invalidation)
  3. If using CDN, purge the CDN cache for this short code
```

### Pattern Comparison

| Pattern | How It Works | Pros | Cons | Use Here? |
|---------|-------------|------|------|-----------|
| **Cache-Aside** | App checks cache, falls back to DB, populates cache | Simple, only caches hot data | Cache miss on first access | **Yes (reads)** |
| **Write-Through** | Every write goes to cache AND DB | Cache always fresh | Writes are slower; caches cold data | **Yes (new URLs only)** |
| **Write-Behind** | Write to cache, async flush to DB | Fastest writes | Risk of data loss if cache crashes | No (durability risk) |
| **Read-Through** | Cache itself fetches from DB on miss | Cleaner app code | Requires cache-DB integration | Optional (Redis doesn't natively support) |
| **Refresh-Ahead** | Proactively refresh before TTL expires | No miss after first load | Wastes resources on cold URLs | No (overkill for key-value) |

---

## Multi-Layer Caching

```
  Layer 1: Browser Cache
  +---------------------------+
  | 302 with Cache-Control    |    0ms latency (browser doesn't even call us)
  | max-age=300 (5 minutes)   |    Risk: User sees stale redirect for 5 min
  +---------------------------+    Use for: Stable, non-expiring URLs
            |
            | MISS (first visit or cache expired)
            v
  Layer 2: CDN Edge Cache
  +---------------------------+
  | CloudFront / Cloudflare   |    1-5ms latency (nearest edge POP)
  | Cache 302 for 60-300s     |    Handles viral URL traffic spikes
  +---------------------------+    Invalidation: API call to purge
            |
            | MISS
            v
  Layer 3: Local App Cache (Caffeine/Guava)
  +---------------------------+
  | In-process JVM cache      |    <1ms latency (no network hop)
  | Max 10,000 entries, LRU   |    Per-node; not shared across instances
  +---------------------------+    Best for: Top 10K hottest URLs
            |
            | MISS
            v
  Layer 4: Distributed Cache (Redis)
  +---------------------------+
  | Redis Cluster             |    1-3ms latency (network hop)
  | Shared across all nodes   |    Single source of truth for cache
  +---------------------------+    Handles cache misses from any node
            |
            | MISS
            v
  Layer 5: Primary Database (Cassandra)
  +---------------------------+
  | Persistent storage        |    5-20ms latency
  | Source of truth            |    Populates Redis on read
  +---------------------------+
```

### 301 vs 302 and Caching

```
  301 (Moved Permanently):
  - Browser caches FOREVER (until user clears cache)
  - You LOSE the click in analytics
  - You CANNOT change the target URL for that user
  - CDN caches aggressively
  
  302 (Found / Temporary Redirect):
  - Browser does NOT cache by default
  - You see EVERY click
  - You CAN change the target URL anytime
  - CDN caches only if you add Cache-Control headers
  
  Recommendation: Use 302 + explicit Cache-Control header
  
  HTTP/1.1 302 Found
  Location: https://example.com/original-url
  Cache-Control: public, max-age=300
  
  This gives us: analytics visibility + 5-minute CDN/browser caching
```

---

## Local Cache: Caffeine vs Guava

| Feature | Caffeine | Guava Cache |
|---------|----------|-------------|
| Performance | 2-3x faster (W-TinyLFU eviction) | Good (LRU-based) |
| Eviction policy | Size, time, reference; W-TinyLFU | Size, time, reference; LRU |
| Async refresh | Yes (`refreshAfterWrite`) | Yes |
| Statistics | Built-in hit/miss/eviction counters | Built-in |
| Maintenance | Active development | Part of Guava (stable, slower updates) |
| Recommendation | **Use Caffeine** (modern standard) | Legacy projects already on Guava |

### Caffeine Configuration

```java
Cache<String, String> localCache = Caffeine.newBuilder()
    .maximumSize(10_000)              // Top 10K URLs in memory
    .expireAfterWrite(5, TimeUnit.MINUTES)  // Short TTL (local cache is per-node)
    .refreshAfterWrite(3, TimeUnit.MINUTES) // Async refresh before expiry
    .recordStats()                    // Expose hit/miss metrics
    .build();
```

### Why Both Local and Distributed Cache?

```
  Local (Caffeine):
  + Zero network latency
  + No Redis dependency on hot path
  - Per-node (inconsistent across nodes)
  - Limited size (JVM heap)
  
  Distributed (Redis):
  + Shared across all nodes
  + Much larger capacity
  - Network hop (~1-3ms)
  - External dependency
  
  Together:
  Caffeine catches the hottest 10K URLs with zero latency.
  Redis catches the next tier.
  DB is the last resort.
```

---

## Cache Risks and Mitigations

### 1. Cache Stampede (Thundering Herd)

**Problem**: A popular URL's cache entry expires. 1,000 concurrent requests all miss the cache simultaneously and hit the database.

```
  Cache expires for "abc123" (viral URL)
       |
  +----+----+----+----+----+
  | R1 | R2 | R3 | R4 |...| R1000    (all miss cache)
  +----+----+----+----+----+
       |
       v
  +----------+
  | Cassandra |  <-- 1000 identical queries at once
  +----------+
       |
       OVERLOADED
```

**Solutions**:

| Solution | How | Tradeoff |
|----------|-----|----------|
| **Mutex/Lock** | First request acquires lock, fetches from DB, populates cache. Others wait. | Added latency for waiting requests |
| **Singleflight** | Deduplicate concurrent requests for the same key. Only one DB call. | Requires in-process coordination |
| **Stale-while-revalidate** | Serve stale data while refreshing in background | Users may see stale data for a few seconds |
| **Early expiration jitter** | Add random offset to TTL (e.g., 24h +/- 1h) | Doesn't fully prevent stampede for single key |

**Recommended**: Singleflight pattern (or Redis distributed lock with `SET key value NX EX 5`).

```java
// Pseudocode: Singleflight pattern
String getUrl(String shortCode) {
    String cached = redis.get("url:" + shortCode);
    if (cached != null) return cached;

    // Try to acquire lock
    boolean acquired = redis.set("lock:" + shortCode, "1", SetParams.nx().ex(5));
    if (acquired) {
        // Winner: fetch from DB and populate cache
        String url = cassandra.get(shortCode);
        redis.setex("url:" + shortCode, 86400, url);
        redis.del("lock:" + shortCode);
        return url;
    } else {
        // Loser: wait briefly and retry cache
        Thread.sleep(50);
        return redis.get("url:" + shortCode);  // should be populated by winner
    }
}
```

### 2. Stale Data

**Problem**: URL is deleted or updated, but cache still serves the old value.

**Mitigation**:
- Active invalidation on delete/update (delete from Redis immediately).
- TTL as a safety net (max 24h staleness if invalidation fails).
- For URL shortener, staleness is low-risk: URLs rarely change after creation.

### 3. Memory Pressure

**Problem**: Cache grows unbounded and causes OOM or eviction storms.

**Mitigation**:
- **LRU eviction**: Least Recently Used entries are evicted first.
- **Max memory policy**: Redis `maxmemory-policy allkeys-lru`.
- **Size limits**: Caffeine `maximumSize(10_000)`.
- **Monitor**: Alert when eviction rate exceeds threshold.

### 4. Cache Penetration

**Problem**: Repeated requests for non-existent short codes bypass cache and hit DB every time.

**Mitigation**:
- **Negative caching**: Cache "NOT_FOUND" with a short TTL (1-5 minutes).
- **Bloom filter**: Check a Bloom filter before querying DB. If the key is definitely not in the set, return 404 immediately.

```java
// Bloom filter approach
BloomFilter<String> urlBloomFilter; // populated on startup and writes

String getUrl(String shortCode) {
    if (!urlBloomFilter.mightContain(shortCode)) {
        return null;  // Definitely not in DB, skip everything
    }
    // Proceed with normal cache-aside logic
}
```

---

## Cache Warming

### Strategy

Pre-load the top N most-accessed URLs into cache on application startup.

```
  Application Startup
       |
       v
  Query: SELECT short_code, original_url
         FROM urls
         ORDER BY click_count DESC
         LIMIT 1000
       |
       v
  Bulk load into Redis:
  MSET url:abc123 "https://..." url:xyz789 "https://..." ...
       |
       v
  Application ready to serve traffic (cache is warm)
```

### When to Warm

| Trigger | Action |
|---------|--------|
| Application startup | Load top 1,000 URLs |
| After deployment | Same as startup |
| Cache flush (Redis restart) | Automatic warm from DB |
| New region/datacenter | Warm from analytics data |

### What to Warm

- Top 1,000 URLs by click count (covers most traffic).
- URLs created in the last hour (likely to be accessed soon).
- Do NOT warm everything -- defeats the purpose of caching.

---

## Tradeoffs Summary Table

| Decision | Choice | Alternative | Why This Choice |
|----------|--------|-------------|-----------------|
| Cache layer | Redis | Memcached | Need TTL, data structures, persistence |
| Local cache | Caffeine | Guava | Better performance, active development |
| Eviction policy | LRU | LFU, FIFO | LRU is simple and effective for access patterns |
| Invalidation | Cache-aside + write-through on create | Write-behind | Durability over write speed |
| TTL (general) | 24 hours | 1 hour / forever | Balance between freshness and hit rate |
| Redirect code | 302 + Cache-Control | 301 | Preserve analytics while enabling edge caching |
| Stampede protection | Singleflight / mutex | None | Critical for viral URLs |
| Negative caching | 1-min TTL tombstone | Bloom filter | Simple; Bloom filter for extreme scale |
| Warming | Top 1,000 on startup | None | Prevents cold-start latency spike |

---

## Interview Talking Points

### Opening Statement

> "For caching, I'd use a multi-layer approach. Caffeine as an in-process L1 cache for the top 10K URLs with sub-millisecond access. Redis as a distributed L2 cache shared across all nodes. And the CDN as an edge cache for globally popular URLs. With the 80-20 rule, this setup handles the vast majority of traffic without touching the database."

### Follow-Up: "What about cache invalidation?"

> "Cache-aside with TTL-based expiration. On URL creation, I write-through to Redis so the creator doesn't hit a cache miss. On deletion, I actively invalidate both Caffeine and Redis. TTL acts as a safety net -- even if invalidation fails, stale data expires within 24 hours. For a URL shortener, brief staleness after deletion is acceptable."

### Follow-Up: "How do you handle a viral URL?"

> "A viral URL going from zero to millions of requests is exactly why we have multi-layer caching. The CDN absorbs the bulk of traffic at the edge. Redis handles misses from the CDN. And for cache stampede protection, I use a singleflight pattern -- when the cache entry expires, only one request fetches from the database while others wait. This prevents a thundering herd on the database."

### Follow-Up: "Why not just use 301 and let the browser cache everything?"

> "301 means the browser caches the redirect permanently. We lose analytics -- we can't count repeat clicks. And if we ever need to change or delete the target URL, users with cached 301s will never see the update. I'd use 302 with an explicit Cache-Control header (e.g., max-age=300) to get the best of both worlds: edge caching for performance and visibility into every click after the cache window."
