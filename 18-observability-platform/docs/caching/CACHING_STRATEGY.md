# Caching Strategy: Observability Platform (Datadog/Grafana-like)

## Table of Contents

1. [Overview](#1-overview)
2. [What to Cache](#2-what-to-cache)
3. [What NOT to Cache](#3-what-not-to-cache)
4. [Multi-Level Caching Architecture](#4-multi-level-caching-architecture)
5. [Query Result Caching](#5-query-result-caching)
6. [Service Map Caching](#6-service-map-caching)
7. [Dashboard Panel Caching](#7-dashboard-panel-caching)
8. [Downsampled Data as Cache](#8-downsampled-data-as-cache)
9. [Alert State Caching](#9-alert-state-caching)
10. [TTL Strategies](#10-ttl-strategies)
11. [Cache Failure Handling](#11-cache-failure-handling)
12. [Cache Decision Matrix](#12-cache-decision-matrix)
13. [Simulation-to-Production Cache Mapping](#13-simulation-to-production-cache-mapping)

---

## 1. Overview

An observability platform has a unique caching challenge: the write path is
extraordinarily heavy (millions of metric points, spans, and log lines per second),
while the read path is comparatively light (dashboards refreshed by hundreds of
users, not millions). This asymmetry means we cache READ-SIDE artifacts
(aggregations, query results, service maps), NOT raw write-path data.

### Design Principles

1. **Cache the aggregated, not the raw** -- pre-computed aggregations (5-minute
   averages, p99 values, service maps) are expensive to compute and change slowly.
   Raw metric points are too numerous and too transient to cache.

2. **Downsampled data IS a cache** -- a 5-minute average stored in a continuous
   aggregate is effectively a cached version of the raw 15-second data. This is
   the most important "cache" in the entire system.

3. **Dashboard panels are the hottest cache** -- the same PromQL query is executed
   by every user viewing the same dashboard. Cache the result, not the individual
   samples.

4. **Stale reads are acceptable for most data** -- a dashboard showing data that
   is 30 seconds old is perfectly fine. Alerting data must be fresh.

5. **Fail open** -- a cache miss degrades latency, not correctness. The source
   of truth is always the time-series database.

### Access Pattern Summary

```
+---------------------------+----------+----------+-----------+------------------+
| Data                      | Reads/s  | Writes/s | Staleness | Cache Strategy   |
+---------------------------+----------+----------+-----------+------------------+
| Metric aggregations       | 500+     | 1/min    | Seconds   | L1+L2, 30s TTL   |
| Service map               | 100+     | 1/5min   | Minutes   | L2, 5min TTL     |
| Dashboard panel results   | 1000+    | varies   | Seconds   | L2, refresh-aligned|
| Alert rule evaluation     | 10       | N/A      | 0 (none)  | Do NOT cache query|
| Raw metric points         | N/A      | 100K+    | N/A       | Do NOT cache     |
| Raw spans                 | N/A      | 50K+     | N/A       | Do NOT cache     |
| Raw log lines             | N/A      | 100K+    | N/A       | Do NOT cache     |
| Trace search results      | 50       | N/A      | Seconds   | L2, 30s TTL      |
| Log search results        | 50       | N/A      | Seconds   | L2, 30s TTL      |
| Metric metadata (names)   | 200      | <1       | Minutes   | L1+L2, 10min TTL |
| Downsampled data (5m avg) | 100      | 1/5min   | 5 minutes | Persistent (TSDB)|
| Alert state               | 20       | 1/15s    | 0 (none)  | L2, write-through|
+---------------------------+----------+----------+-----------+------------------+
```

---

## 2. What to Cache

### 2.1 Metric Aggregations (Pre-Computed Results)

**Why cache:** PromQL queries like `histogram_quantile(0.99, ...)` scan thousands
of time-series, decompress chunks, and perform floating-point aggregations. This
is CPU-intensive and produces a result that changes only slightly every 15 seconds
(the scrape interval).

**Access pattern:** The same aggregation query runs every dashboard refresh (5s-30s)
for every viewer. 10 users viewing the same dashboard = 10 identical PromQL
evaluations without caching.

**Cache location:** L2 (Redis)

**TTL:** 30 seconds (aligned with scrape interval -- result won't change between scrapes)

**Cache key:** hash of normalized PromQL expression + time range + step

```java
// Query result caching for metric aggregations
public class MetricAggregationCache {

    private final RedisTemplate<String, byte[]> redis;
    private final Duration ttl = Duration.ofSeconds(30);

    // Cache key: deterministic hash of query parameters
    public String cacheKey(String promqlExpr, Instant start, Instant end, Duration step) {
        // 1. Normalize the PromQL expression (remove whitespace, sort labels)
        String normalized = PromQLNormalizer.normalize(promqlExpr);

        // 2. Align start/end to step boundaries (avoid near-miss cache misses)
        long alignedStart = alignToStep(start, step);
        long alignedEnd = alignToStep(end, step);

        // 3. Hash for compact key
        return "agg:" + Hashing.murmur3_128().hashString(
            normalized + ":" + alignedStart + ":" + alignedEnd + ":" + step.toMillis(),
            StandardCharsets.UTF_8
        );
    }

    // Read path: cache -> TSDB
    public QueryResult getAggregation(String promqlExpr, Instant start,
                                       Instant end, Duration step) {
        String key = cacheKey(promqlExpr, start, end, step);

        // 1. Check L2 cache (Redis, ~1ms)
        byte[] cached = redis.opsForValue().get(key);
        if (cached != null) {
            return deserialize(cached);  // cache HIT
        }

        // 2. Execute PromQL query against TSDB (~50-500ms)
        QueryResult result = tsdbEngine.query(promqlExpr, start, end, step);

        // 3. Cache the result (async, don't block the response)
        redis.opsForValue().set(key, serialize(result), ttl);

        return result;
    }
}
```

**Why 30-second TTL:** Prometheus scrapes every 15 seconds. A PromQL result over a
5-minute range changes at most once per scrape. A 30s TTL means we re-evaluate at
most every other scrape -- acceptable staleness for dashboards.

### 2.2 Service Map (Dependency Graph)

**Why cache:** Building a service map requires scanning recent traces, extracting
parent-child span relationships, deduplicating edges, and computing edge weights
(call counts, error rates). This is an expensive operation that scans millions
of spans.

**Access pattern:** The service map page is loaded frequently but the topology
changes rarely (new edges appear when new service dependencies are introduced,
which happens at deployment time, not at runtime).

**Cache location:** L2 (Redis hash)

**TTL:** 5 minutes

```java
// Service map cache -- the most cache-friendly data in observability
public class ServiceMapCache {

    private final RedisTemplate<String, String> redis;
    private final Duration ttl = Duration.ofMinutes(5);
    private final String CACHE_KEY = "servicemap:v1";

    // Service map structure
    public static class ServiceMap {
        List<ServiceNode> nodes;        // {name, type, health, requestRate}
        List<ServiceEdge> edges;        // {source, target, callCount, errorRate, avgLatency}
        Instant computedAt;
    }

    // Read path: cache -> expensive trace scan
    public ServiceMap getServiceMap() {
        // 1. Check cache
        String cached = redis.opsForValue().get(CACHE_KEY);
        if (cached != null) {
            return deserialize(cached);
        }

        // 2. Build from recent traces (EXPENSIVE: scans last 15 minutes of spans)
        // Production: Jaeger Spark job or ClickHouse aggregation query
        //   SELECT source_service, dest_service, count(*), avg(duration)
        //   FROM spans
        //   WHERE timestamp > now() - INTERVAL 15 MINUTE
        //   GROUP BY source_service, dest_service
        ServiceMap map = serviceMapBuilder.buildFromRecentTraces(Duration.ofMinutes(15));

        // 3. Cache (async)
        redis.opsForValue().set(CACHE_KEY, serialize(map), ttl);

        return map;
    }

    // Invalidate on deployment event (new service/edge might appear)
    public void onDeploymentEvent(DeploymentEvent event) {
        redis.delete(CACHE_KEY);
    }
}
```

### 2.3 Dashboard Panel Results

**Why cache:** A dashboard with 20 panels makes 20 queries on every refresh. If
50 users are viewing the same dashboard with a 10-second refresh interval, that's
50 * 20 / 10 = 100 queries per second, all returning nearly identical results.

**Cache location:** L2 (Redis)

**TTL:** Aligned with dashboard refresh interval

See detailed section below (Section 7).

### 2.4 Metric Metadata

**Why cache:** Metric name autocomplete, label value enumeration, and metric type
lookups are frequent during dashboard editing and ad-hoc queries. The metadata
changes only when new metrics are instrumented.

**Cache location:** L1 (JVM local) + L2 (Redis)

**TTL:** 10 minutes (L1), 1 hour (L2)

```java
// Metric metadata cache
Cache<String, MetricMetadata> metadataCache = Caffeine.newBuilder()
    .maximumSize(50_000)              // all known metric names
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .recordStats()
    .build();

// Autocomplete: "http_req" -> ["http_requests_total", "http_request_duration_seconds"]
public List<String> autocompleteMetricName(String prefix) {
    // L1 cache contains all metric names (small, ~50KB for 50K names)
    return metadataCache.asMap().keySet().stream()
        .filter(name -> name.startsWith(prefix))
        .sorted()
        .limit(20)
        .collect(Collectors.toList());
}
```

### 2.5 Alert Rule Definitions (NOT Alert Evaluation Results)

**Why cache:** Alert rules (the configuration: "alert when error rate > 5% for 5
minutes") change infrequently (operator edits them). The rule definitions are read
every evaluation cycle (every 15 seconds).

**What to cache:** The rule definition itself (threshold, condition, duration).
**What NOT to cache:** The evaluated result of the query (must be fresh for alerting correctness).

**Cache location:** L1 (JVM local)

**TTL:** Event-driven invalidation (reload on rule change API call)

```java
// Alert rule definition cache (NOT alert evaluation results)
public class AlertRuleCache {

    // L1 cache: all alert rules loaded in memory
    private volatile List<AlertRule> cachedRules = Collections.emptyList();
    private volatile Instant lastReload = Instant.EPOCH;

    // Called every 15 seconds by alert evaluator
    public List<AlertRule> getActiveRules() {
        // Rules change rarely -- return cached unless explicitly invalidated
        return cachedRules;
    }

    // Called when operator creates/updates/deletes an alert rule via API
    public void reloadRules() {
        // Fetch from PostgreSQL (source of truth for rule definitions)
        cachedRules = alertRuleRepository.findAllActive();
        lastReload = Instant.now();
    }

    // Alert EVALUATION (the PromQL query) is NEVER cached
    // Reason: stale alert evaluation = missed critical alert = production incident
    public AlertState evaluateRule(AlertRule rule) {
        // ALWAYS query TSDB directly, NO cache
        double currentValue = tsdbEngine.queryInstant(rule.getPromqlExpression());
        return rule.evaluate(currentValue);
    }
}
```

---

## 3. What NOT to Cache

### 3.1 Raw Metric Points

```
  +-----------------------------------------------------------------------+
  |  WHY NOT CACHE RAW METRIC POINTS                                      |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Volume: 100,000 metric points per second                            |
  |    -> 8.64 BILLION points per day                                    |
  |    -> Each point: ~50 bytes (metric name, labels, timestamp, value)  |
  |    -> 432 GB per day of raw data                                     |
  |                                                                       |
  |  Caching raw points would require:                                    |
  |    - Redis memory: even 1 hour = 360M points = 18 GB in Redis        |
  |    - Write amplification: every point written to TSDB AND Redis      |
  |    - Cache invalidation: points are never "updated", only appended   |
  |    - Read pattern: queries always aggregate (rate, avg, percentile)  |
  |      so raw points are NEVER returned to the user as-is              |
  |                                                                       |
  |  Correct approach:                                                    |
  |    - Write raw points directly to TSDB (optimized for this)          |
  |    - Cache the AGGREGATED result of PromQL queries                   |
  |    - Use downsampling (continuous aggregates) as long-term "cache"   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 3.2 Raw Spans (Trace Data)

```
  +-----------------------------------------------------------------------+
  |  WHY NOT CACHE RAW SPANS                                              |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Volume: 50,000 spans per second (after sampling)                    |
  |    -> Each span: ~500 bytes (traceId, spanId, tags, logs, timing)    |
  |    -> 25 MB/sec = 2.16 TB per day                                   |
  |                                                                       |
  |  Why caching doesn't help:                                            |
  |    - Spans are write-once, read-rarely                               |
  |    - Trace search is ad-hoc (different traceId, service, duration)   |
  |    - No repeated reads of the same span (unlike metric queries)      |
  |    - Trace storage (Cassandra/ES) already has read caching built in  |
  |                                                                       |
  |  Exception: trace search RESULTS can be cached                       |
  |    - "Last 10 error traces for payment-service" (30s TTL)           |
  |    - This caches the search result, not individual spans             |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 3.3 Raw Log Lines

```
  +-----------------------------------------------------------------------+
  |  WHY NOT CACHE RAW LOG LINES                                          |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Same reasoning as raw spans, but even higher volume:                |
  |    - 100,000+ log lines per second                                   |
  |    - Each log: ~200 bytes                                            |
  |    - 20 MB/sec = 1.73 TB per day                                    |
  |                                                                       |
  |  Log queries are ad-hoc full-text searches:                          |
  |    - {service="payment"} |= "timeout" (different search terms)      |
  |    - Elasticsearch/Loki already cache frequent queries internally    |
  |                                                                       |
  |  Exception: log search RESULTS for common queries can be cached      |
  |    - "Recent errors for payment-service" (dashboard panel, 30s TTL) |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 3.4 Alert Evaluation Query Results

```
  +-----------------------------------------------------------------------+
  |  WHY NOT CACHE ALERT EVALUATION RESULTS                               |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Alert evaluator runs: "Is error rate > 5% right now?"               |
  |    -> This query MUST hit the TSDB directly                          |
  |    -> A cached result that is 30 seconds stale could mean:           |
  |       - Error rate was 3% (cached) but is now 15% (actual)           |
  |       - Alert doesn't fire for 30 seconds                            |
  |       - 30 seconds of undetected production incident                 |
  |                                                                       |
  |  For CRITICAL alerts:                                                 |
  |    - Alert evaluation: NEVER cached                                  |
  |    - Alert state (is it currently firing?): cached in Redis           |
  |      (write-through, updated on every evaluation cycle)              |
  |    - Alert notification dedup: cached in Redis                       |
  |      (don't re-notify if already notified in last 5 minutes)         |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## 4. Multi-Level Caching Architecture

### 4.1 Architecture Overview

```
  +-----------------------------------------------------------------------+
  |  THREE-LEVEL CACHING IN OBSERVABILITY                                 |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  L0: DOWNSAMPLED DATA (persistent, in TSDB)                          |
  |      - 5-minute averages, 1-hour aggregates                          |
  |      - Stored in continuous aggregates (TimescaleDB) or              |
  |        materialized views (ClickHouse)                               |
  |      - TTL: indefinite (retained for months/years)                   |
  |      - Not a "cache" in traditional sense, but serves the same       |
  |        purpose: avoid re-computing aggregations from raw data        |
  |                                                                       |
  |  L1: JVM LOCAL CACHE (Caffeine)                                       |
  |      - Metric metadata, alert rule definitions, autocomplete data    |
  |      - TTL: 1-10 minutes                                             |
  |      - Size: < 100MB per JVM                                         |
  |      - Latency: ~100 nanoseconds                                     |
  |      - Per-instance (not shared across replicas)                     |
  |                                                                       |
  |  L2: REDIS CLUSTER                                                    |
  |      - Dashboard query results, service map, alert state             |
  |      - TTL: 30 seconds - 5 minutes                                  |
  |      - Size: 2-8 GB cluster                                          |
  |      - Latency: ~1 millisecond                                       |
  |      - Shared across all Grafana/query instances                     |
  |                                                                       |
  |  L3: TSDB (source of truth)                                           |
  |      - Raw metric points, aggregation computation                    |
  |      - Latency: 10-500ms depending on query complexity               |
  |      - Always correct, always available                              |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 4.2 Read Path Through Cache Layers

```
  +-----------------------------------------------------------------------+
  |  QUERY: "p99 latency of payment-service over last 1 hour, 1m step"  |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Step 1: Grafana panel executes PromQL query                         |
  |          histogram_quantile(0.99, sum(rate(                           |
  |            http_request_duration_seconds_bucket{service="payment"}    |
  |            [5m])) by (le))                                            |
  |                                                                       |
  |  Step 2: Check L2 (Redis)                                            |
  |          Key: "query:a3f2b1..." (hash of normalized query + range)   |
  |          HIT -> return cached DataFrame (1ms)                        |
  |          MISS -> continue to L0/L3                                   |
  |                                                                       |
  |  Step 3: Check L0 (downsampled data)                                 |
  |          If query range > 6 hours:                                    |
  |            Use 5-minute downsampled table (much less data to scan)   |
  |          If query range > 7 days:                                     |
  |            Use 1-hour downsampled table                              |
  |          If query range < 6 hours:                                    |
  |            Use raw data (most recent, highest resolution)            |
  |                                                                       |
  |  Step 4: Execute query on TSDB (L3)                                  |
  |          Prometheus/Mimir evaluates PromQL on raw or downsampled data|
  |          Returns DataFrame with 60 data points (1 per minute)        |
  |          Latency: 50-200ms                                           |
  |                                                                       |
  |  Step 5: Populate L2 cache                                           |
  |          Store result in Redis with 30s TTL                          |
  |          Next request for same query within 30s = cache HIT          |
  |                                                                       |
  +-----------------------------------------------------------------------+

  Response time comparison:
    L2 HIT:  ~1ms   (Redis lookup + deserialization)
    L0 HIT:  ~20ms  (downsampled table query, fewer rows to scan)
    L3 MISS: ~200ms (raw data query, full PromQL evaluation)
```

### 4.3 Cache Consistency Model

```
  +-----------------------------------------------------------------------+
  |  CONSISTENCY: EVENTUAL IS FINE (EXCEPT FOR ALERTS)                    |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Dashboard queries:                                                   |
  |    Cache hit returns data up to 30s stale.                           |
  |    This is acceptable because:                                        |
  |      - Prometheus scrapes every 15s (data is already 0-15s old)     |
  |      - Dashboard refresh interval is 10-30s                          |
  |      - Users expect a few seconds of delay                           |
  |      - Total staleness: scrape_interval + cache_TTL = 15s + 30s = 45s|
  |                                                                       |
  |  Alert evaluations:                                                   |
  |    BYPASS all caches. Query TSDB directly.                           |
  |    Total staleness: scrape_interval only (15s)                       |
  |    This ensures alerts fire within one evaluation cycle of the event |
  |                                                                       |
  |  Service map:                                                         |
  |    Cache hit returns topology up to 5 minutes old.                   |
  |    Service topology changes on deployment, not at runtime.           |
  |    5-minute staleness is acceptable.                                  |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## 5. Query Result Caching

### 5.1 Cache Key Design

The cache key must be deterministic so that semantically identical queries hit
the same cache entry, even if written differently.

```java
public class QueryCacheKeyBuilder {

    // Two PromQL queries that are logically identical but syntactically different:
    //   sum(rate(http_requests_total{service="payment",method="GET"}[5m]))
    //   sum(rate(http_requests_total{method="GET",service="payment"}[5m]))
    //
    // Must produce the SAME cache key.

    public String buildKey(String promqlExpr, Instant start, Instant end, Duration step) {
        // 1. Normalize: sort label matchers alphabetically within each selector
        String normalized = PromQLNormalizer.normalize(promqlExpr);
        // Result: sum(rate(http_requests_total{method="GET",service="payment"}[5m]))

        // 2. Align time boundaries to step
        // If step=1m, start=14:32:17, align to 14:32:00
        // This prevents cache misses due to slight timestamp differences
        long alignedStart = (start.toEpochMilli() / step.toMillis()) * step.toMillis();
        long alignedEnd = (end.toEpochMilli() / step.toMillis()) * step.toMillis();

        // 3. Combine and hash
        String rawKey = normalized + "|" + alignedStart + "|" + alignedEnd + "|" + step.toMillis();
        return "qrc:" + Hashing.murmur3_128()
            .hashString(rawKey, StandardCharsets.UTF_8)
            .toString();
    }
}
```

### 5.2 Cache Stampede Prevention

```
  +-----------------------------------------------------------------------+
  |  CACHE STAMPEDE PROBLEM                                               |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Scenario: 50 users viewing the same dashboard                       |
  |  Cache TTL expires.                                                   |
  |  All 50 users' next refresh triggers a cache MISS simultaneously.    |
  |  50 identical PromQL queries hit the TSDB at once.                   |
  |  TSDB load spikes, query latency increases for everyone.             |
  |                                                                       |
  |  Solution: SINGLE-FLIGHT pattern (request coalescing)                |
  |                                                                       |
  |  1. First request detects cache miss                                 |
  |  2. First request acquires a lock (Redis SETNX or JVM lock)         |
  |  3. First request executes PromQL query                              |
  |  4. Other 49 requests detect the lock and WAIT for the result       |
  |  5. First request populates cache and releases lock                  |
  |  6. Other 49 requests read from freshly populated cache              |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

```java
// Single-flight cache access (request coalescing)
public class SingleFlightQueryCache {

    private final ConcurrentMap<String, CompletableFuture<QueryResult>> inFlight
        = new ConcurrentHashMap<>();

    public QueryResult query(String promqlExpr, Instant start, Instant end, Duration step) {
        String key = keyBuilder.buildKey(promqlExpr, start, end, step);

        // 1. Check cache
        QueryResult cached = redis.get(key);
        if (cached != null) return cached;

        // 2. Single-flight: only one thread executes the query
        CompletableFuture<QueryResult> future = inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    // Execute the expensive PromQL query
                    QueryResult result = tsdbEngine.query(promqlExpr, start, end, step);
                    // Populate cache
                    redis.set(key, result, Duration.ofSeconds(30));
                    return result;
                } finally {
                    // Remove from in-flight map
                    inFlight.remove(k);
                }
            })
        );

        // 3. All concurrent callers wait on the same Future
        return future.join();  // blocks until query completes
    }
}
```

### 5.3 Stale-While-Revalidate

```
  +-----------------------------------------------------------------------+
  |  STALE-WHILE-REVALIDATE PATTERN                                       |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Instead of: cache MISS -> wait for query (200ms) -> return          |
  |  Do this:    cache STALE -> return stale immediately -> async refresh |
  |                                                                       |
  |  Cache entry:                                                         |
  |    {                                                                  |
  |      data: <query result>,                                           |
  |      createdAt: 1715270000,                                           |
  |      softTTL: 30s,     // return immediately but trigger refresh     |
  |      hardTTL: 120s     // actually evict from cache                  |
  |    }                                                                  |
  |                                                                       |
  |  Read logic:                                                          |
  |    if (age < softTTL): return cached (100% fresh)                    |
  |    if (softTTL < age < hardTTL): return cached + async refresh       |
  |    if (age > hardTTL): cache miss, synchronous query                 |
  |                                                                       |
  |  Benefit: users almost never experience a cache miss latency spike   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

```java
public class StaleWhileRevalidateCache {

    private final Duration softTtl = Duration.ofSeconds(30);
    private final Duration hardTtl = Duration.ofSeconds(120);

    public QueryResult get(String key, Supplier<QueryResult> queryFn) {
        CacheEntry entry = redis.get(key);

        if (entry == null) {
            // Hard miss: synchronous query
            QueryResult result = queryFn.get();
            redis.set(key, new CacheEntry(result, Instant.now()), hardTtl);
            return result;
        }

        Duration age = Duration.between(entry.createdAt, Instant.now());

        if (age.compareTo(softTtl) <= 0) {
            // Fresh: return cached
            return entry.data;
        }

        if (age.compareTo(hardTtl) <= 0) {
            // Stale but usable: return cached, trigger async refresh
            CompletableFuture.runAsync(() -> {
                QueryResult fresh = queryFn.get();
                redis.set(key, new CacheEntry(fresh, Instant.now()), hardTtl);
            });
            return entry.data;  // return stale data immediately
        }

        // Should not reach here (hardTtl eviction handles it)
        QueryResult result = queryFn.get();
        redis.set(key, new CacheEntry(result, Instant.now()), hardTtl);
        return result;
    }
}
```

---

## 6. Service Map Caching

### 6.1 Why Service Map is the Best Cache Candidate

```
  +-----------------------------------------------------------------------+
  |  SERVICE MAP CACHING ANALYSIS                                         |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Computation cost:                                                    |
  |    Building a service map requires:                                   |
  |    1. Scan all spans from last 15 minutes (~45M spans)               |
  |    2. Extract parent-child relationships (source service -> dest)    |
  |    3. Aggregate edge weights (call count, error rate, avg latency)   |
  |    4. Deduplicate and merge                                          |
  |    -> Total: 5-30 seconds of computation                             |
  |                                                                       |
  |  Change frequency:                                                    |
  |    - New nodes: only when new services are deployed (rare)           |
  |    - New edges: only when new service-to-service calls added (rare)  |
  |    - Edge weights: change continuously (but direction doesn't)       |
  |    - Topology is STABLE, weights are DYNAMIC                         |
  |                                                                       |
  |  Read frequency:                                                      |
  |    - Service map page: loaded by 50-100 engineers daily              |
  |    - Each page load: 1 request for the map                           |
  |    - ~100 requests per hour during business hours                    |
  |                                                                       |
  |  Cache benefit:                                                       |
  |    Without cache: 100 requests * 10s computation = 1000s of TSDB load|
  |    With cache:    1 computation per 5 minutes = 12 computations/hour |
  |    Reduction: 98.8% less computation                                 |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 6.2 Two-Layer Service Map Cache

```java
// Service map: topology (slow-changing) + weights (fast-changing)
public class ServiceMapCacheStrategy {

    // LAYER 1: Topology cache (nodes and edges, no weights)
    // TTL: 10 minutes (topology changes on deployment only)
    private static final String TOPOLOGY_KEY = "svcmap:topology:v1";
    private static final Duration TOPOLOGY_TTL = Duration.ofMinutes(10);

    // LAYER 2: Weight cache (call counts, error rates, latencies per edge)
    // TTL: 1 minute (weights change with traffic patterns)
    private static final String WEIGHTS_KEY = "svcmap:weights:v1";
    private static final Duration WEIGHTS_TTL = Duration.ofMinutes(1);

    public ServiceMap getServiceMap() {
        // 1. Get topology (slow path: scan traces for unique service pairs)
        ServiceTopology topology = getOrComputeTopology();

        // 2. Get weights (faster path: aggregate recent span stats)
        EdgeWeights weights = getOrComputeWeights(topology);

        // 3. Merge topology + weights into full service map
        return ServiceMap.merge(topology, weights);
    }

    private ServiceTopology getOrComputeTopology() {
        String cached = redis.get(TOPOLOGY_KEY);
        if (cached != null) return deserialize(cached);

        // Expensive: scan all recent traces for unique edges
        ServiceTopology topology = traceAnalyzer.extractTopology(Duration.ofMinutes(15));
        redis.set(TOPOLOGY_KEY, serialize(topology), TOPOLOGY_TTL);
        return topology;
    }

    private EdgeWeights getOrComputeWeights(ServiceTopology topology) {
        String cached = redis.get(WEIGHTS_KEY);
        if (cached != null) return deserialize(cached);

        // Moderate cost: aggregate metrics for known edges only
        EdgeWeights weights = metricAggregator.computeEdgeWeights(
            topology.getEdges(), Duration.ofMinutes(5));
        redis.set(WEIGHTS_KEY, serialize(weights), WEIGHTS_TTL);
        return weights;
    }
}
```

---

## 7. Dashboard Panel Caching

### 7.1 Panel-Level vs Query-Level Caching

```
  +-----------------------------------------------------------------------+
  |  TWO CACHING GRANULARITIES                                            |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  QUERY-LEVEL CACHE (what we described in Section 5)                  |
  |    Key: hash(promql_expr + time_range + step)                        |
  |    Value: raw DataFrame (time-series data)                           |
  |    Shared across dashboards that use the same query                  |
  |    Grafana must still render the panel from the DataFrame            |
  |                                                                       |
  |  PANEL-LEVEL CACHE (frontend/CDN)                                    |
  |    Key: hash(panel_id + dashboard_id + template_vars + time_range)   |
  |    Value: rendered panel image (PNG) or serialized panel state       |
  |    Served directly to browser without any backend processing         |
  |    Used by Grafana Cloud for public dashboards / TV displays         |
  |                                                                       |
  |  Recommendation:                                                      |
  |    - Use QUERY-LEVEL cache for most cases (flexible, composable)    |
  |    - Use PANEL-LEVEL cache for TV/wallboard displays (read-only,    |
  |      many viewers, no interaction)                                   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 7.2 Dashboard Refresh Alignment

```
  +-----------------------------------------------------------------------+
  |  REFRESH ALIGNMENT FOR CACHE EFFICIENCY                               |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Problem: 50 users with 10s refresh, each at a random offset         |
  |           User A refreshes at :00, :10, :20, :30...                  |
  |           User B refreshes at :03, :13, :23, :33...                  |
  |           User C refreshes at :07, :17, :27, :37...                  |
  |           Each refresh has slightly different time range,             |
  |           producing different cache keys -> cache MISSES             |
  |                                                                       |
  |  Solution: align query time ranges to step boundaries                |
  |           All users query :00-:10, then :10-:20                      |
  |           Same time range -> same cache key -> cache HITS            |
  |                                                                       |
  |  Implementation:                                                      |
  |    Grafana "Max data points" setting:                                 |
  |      Panel width = 1000px, max data points = 1000                    |
  |      Time range = 1 hour = 3600s                                     |
  |      Step = ceil(3600/1000) = 4s, round up to 5s                    |
  |      Start time aligned to 5s boundary                               |
  |      All users get the same aligned query                            |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## 8. Downsampled Data as Cache

### 8.1 The Most Important "Cache" in Observability

```
  +-----------------------------------------------------------------------+
  |  DOWNSAMPLING = THE LONG-TERM CACHE                                   |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Raw data: 15-second resolution, retained for 15 days                |
  |  5-min downsample: avg, min, max, count per 5-minute bucket          |
  |    -> 20x fewer data points, retained for 6 months                  |
  |  1-hour downsample: avg, min, max, count per 1-hour bucket          |
  |    -> 240x fewer data points, retained for 2 years                  |
  |                                                                       |
  |  Query routing based on time range:                                   |
  |    Last 6 hours:   use raw data (15s resolution)                     |
  |    Last 7 days:    use 5-min downsample (300s resolution)            |
  |    Last 30 days:   use 1-hour downsample (3600s resolution)          |
  |    Last 1 year:    use 1-hour downsample (3600s resolution)          |
  |                                                                       |
  |  Why this IS a cache:                                                 |
  |    - It stores pre-computed results (avg, min, max, count)           |
  |    - It avoids re-scanning raw data for historical queries           |
  |    - It trades precision for speed (acceptable for dashboards)       |
  |    - It has a "TTL" (retention policy)                               |
  |                                                                       |
  |  Why this is BETTER than Redis for historical queries:               |
  |    - Persisted on disk (survives Redis restart)                      |
  |    - Queryable with PromQL/SQL (not just key-value lookup)           |
  |    - Automatically maintained (continuous aggregate refresh)         |
  |    - No memory pressure (stored in TSDB, not RAM)                   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 8.2 Continuous Aggregate Implementation

```sql
-- TimescaleDB: automatic downsampling with continuous aggregates

-- 5-minute aggregate
CREATE MATERIALIZED VIEW metrics_5min
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('5 minutes', time) AS bucket,
    metric_name,
    tags->>'service' AS service,
    avg(value) AS avg_val,
    min(value) AS min_val,
    max(value) AS max_val,
    count(*) AS sample_count,
    -- For counters: store the last value for rate calculation
    last(value, time) AS last_val,
    first(value, time) AS first_val
FROM metrics
GROUP BY bucket, metric_name, service
WITH NO DATA;

-- Refresh policy: update every 5 minutes, with 10-minute lag
-- (lag ensures all raw data for the bucket has arrived)
SELECT add_continuous_aggregate_policy('metrics_5min',
    start_offset => INTERVAL '1 hour',   -- recompute last hour
    end_offset => INTERVAL '10 minutes', -- lag for late arrivals
    schedule_interval => INTERVAL '5 minutes'
);

-- 1-hour aggregate (built on top of 5-minute aggregate for efficiency)
CREATE MATERIALIZED VIEW metrics_1hour
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', bucket) AS bucket,
    metric_name,
    service,
    avg(avg_val) AS avg_val,      -- average of averages (weighted by count)
    min(min_val) AS min_val,
    max(max_val) AS max_val,
    sum(sample_count) AS sample_count
FROM metrics_5min
GROUP BY time_bucket('1 hour', bucket), metric_name, service
WITH NO DATA;

SELECT add_continuous_aggregate_policy('metrics_1hour',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
);
```

### 8.3 Query Router (Automatic Downsampling Selection)

```java
// Automatically select the best resolution for a query
public class DownsampleRouter {

    // Resolution tiers
    private static final Duration RAW_THRESHOLD = Duration.ofHours(6);
    private static final Duration FIVE_MIN_THRESHOLD = Duration.ofDays(7);

    public String routeToTable(Duration queryRange) {
        if (queryRange.compareTo(RAW_THRESHOLD) <= 0) {
            return "metrics";           // raw 15s data
        } else if (queryRange.compareTo(FIVE_MIN_THRESHOLD) <= 0) {
            return "metrics_5min";      // 5-minute aggregates
        } else {
            return "metrics_1hour";     // 1-hour aggregates
        }
    }

    // Example: "Show me CPU usage for last 30 days"
    // routeToTable(Duration.ofDays(30)) -> "metrics_1hour"
    // This query scans 30 * 24 = 720 rows instead of 30 * 24 * 60 * 4 = 172,800 rows
    // 240x less data to read -> 240x faster query

    // Thanos/Mimir equivalent:
    //   Thanos compactor creates 5-minute and 1-hour downsampled blocks
    //   Thanos query frontend automatically selects the appropriate resolution
    //   Configuration: --query.auto-downsampling
}
```

---

## 9. Alert State Caching

### 9.1 Write-Through Alert State

```
  +-----------------------------------------------------------------------+
  |  ALERT STATE: WRITE-THROUGH TO REDIS                                  |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Alert evaluator runs every 15 seconds:                              |
  |    1. Load alert rules (from L1 cache -- rule definitions)           |
  |    2. Evaluate each rule's PromQL expression (NO cache -- fresh data)|
  |    3. Compare result to threshold                                    |
  |    4. Update alert state in Redis (write-through)                    |
  |    5. If state changed: trigger notification                         |
  |                                                                       |
  |  Redis stores current state for fast reads:                          |
  |    Key: "alert:state:{ruleId}"                                       |
  |    Value: {                                                           |
  |      state: "FIRING",                                                |
  |      since: "2026-05-09T14:32:00Z",                                 |
  |      value: 0.087,    // current error rate                          |
  |      threshold: 0.05, // configured threshold                        |
  |      lastNotified: "2026-05-09T14:32:15Z",                          |
  |      consecutiveFires: 3                                              |
  |    }                                                                  |
  |                                                                       |
  |  Why write-through (not write-behind):                               |
  |    - Alert state must be immediately visible to all components       |
  |    - Dashboard shows "FIRING" badge in real time                     |
  |    - Notification dedup relies on current state                      |
  |    - Write latency (1ms to Redis) is negligible vs 15s eval cycle   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 9.2 Notification Deduplication Cache

```java
// Prevent alert notification storms
public class AlertNotificationDedup {

    private final RedisTemplate<String, String> redis;

    // Minimum interval between notifications for the same alert
    private static final Duration RENOTIFY_INTERVAL = Duration.ofMinutes(5);

    public boolean shouldNotify(String ruleId, AlertSeverity severity) {
        String key = "alert:lastnotify:" + ruleId;

        // Check when we last notified for this alert
        String lastNotified = redis.opsForValue().get(key);
        if (lastNotified != null) {
            Instant last = Instant.parse(lastNotified);
            Duration sinceLast = Duration.between(last, Instant.now());

            // Critical alerts: re-notify every 1 minute
            // Warning alerts: re-notify every 5 minutes
            // Info alerts: re-notify every 15 minutes
            Duration interval = switch (severity) {
                case CRITICAL -> Duration.ofMinutes(1);
                case WARNING  -> Duration.ofMinutes(5);
                case INFO     -> Duration.ofMinutes(15);
            };

            if (sinceLast.compareTo(interval) < 0) {
                return false;  // too soon, suppress notification
            }
        }

        // Record notification timestamp
        redis.opsForValue().set(key, Instant.now().toString(), RENOTIFY_INTERVAL);
        return true;
    }
}
```

---

## 10. TTL Strategies

### 10.1 TTL Decision Framework

```
  +-----------------------------------------------------------------------+
  |  TTL SELECTION CRITERIA                                               |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Factor 1: Data freshness requirement                                |
  |    - Alert evaluation: 0 TTL (never cache)                           |
  |    - Dashboard metrics: 15-30s TTL (1-2 scrape intervals)           |
  |    - Service map: 5 min TTL (topology is stable)                    |
  |    - Metric metadata: 10 min TTL (changes on deploy only)           |
  |                                                                       |
  |  Factor 2: Computation cost                                           |
  |    - Simple PromQL (single metric): short TTL (15s) or no cache     |
  |    - Complex PromQL (joins, histograms): longer TTL (30-60s)        |
  |    - Service map: long TTL (5-10 min, expensive to compute)         |
  |    - Downsampled aggregates: persistent (not a TTL, just retention) |
  |                                                                       |
  |  Factor 3: Request volume                                             |
  |    - TV dashboard (100 viewers): longer TTL, reduce backend load    |
  |    - Ad-hoc query (1 user): no caching needed                       |
  |    - API-driven queries (automation): moderate TTL                   |
  |                                                                       |
  |  Factor 4: Scrape interval alignment                                  |
  |    - Prometheus scrapes every 15s                                    |
  |    - Caching for < 15s is pointless (data won't change)             |
  |    - Optimal TTL = 1-2 * scrape_interval = 15-30s                   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 10.2 TTL Summary Table

```
  +-----------------------------------------------------------------------+
  |  COMPLETE TTL REFERENCE                                               |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Cache Item                    | L1 TTL   | L2 TTL    | Strategy     |
  |  ------------------------------+----------+-----------+--------------+
  |  Dashboard PromQL result       | -        | 30s       | Time-aligned |
  |  Service map topology          | -        | 10 min    | Event-inv    |
  |  Service map weights           | -        | 1 min     | Fixed TTL    |
  |  Metric metadata (names/types) | 10 min   | 1 hour    | Event-inv    |
  |  Label values (autocomplete)   | 5 min    | 30 min    | Fixed TTL    |
  |  Alert rule definitions        | reload   | -         | Event-inv    |
  |  Alert state (current)         | -        | no TTL    | Write-through|
  |  Alert notification dedup      | -        | 5-15 min  | Fixed TTL    |
  |  Trace search results          | -        | 30s       | Fixed TTL    |
  |  Log search results            | -        | 30s       | Fixed TTL    |
  |  Downsampled 5-min             | -        | persistent| Retention    |
  |  Downsampled 1-hour            | -        | persistent| Retention    |
  |  Raw metric points             | -        | NEVER     | -            |
  |  Raw spans                     | -        | NEVER     | -            |
  |  Raw log lines                 | -        | NEVER     | -            |
  |  Alert evaluation query result | -        | NEVER     | -            |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## 11. Cache Failure Handling

### 11.1 Graceful Degradation

```
  +-----------------------------------------------------------------------+
  |  CACHE FAILURE SCENARIOS AND RESPONSES                                |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Scenario 1: Redis cluster is down                                    |
  |  Response:                                                            |
  |    - All queries fall through to TSDB directly                       |
  |    - Dashboard latency increases from 1ms to 50-200ms               |
  |    - Alert evaluation is UNAFFECTED (never cached anyway)            |
  |    - Service map computation increases (every request recalculates)  |
  |    - Log message: "Redis unavailable, bypassing query cache"         |
  |    - Monitor: cache_bypass_total counter increments                  |
  |                                                                       |
  |  Scenario 2: Redis is slow (>10ms response time)                     |
  |  Response:                                                            |
  |    - Circuit breaker triggers after 5 consecutive slow responses     |
  |    - Cache reads bypassed for 30 seconds (half-open retry)          |
  |    - Cache writes continue async (don't slow down the read path)    |
  |                                                                       |
  |  Scenario 3: Redis returns stale data (clock skew, replication lag)  |
  |  Response:                                                            |
  |    - Dashboard data is slightly stale (acceptable for most use cases)|
  |    - Alerting is unaffected (does not use cache)                    |
  |    - Stale-while-revalidate pattern handles this automatically      |
  |                                                                       |
  |  Scenario 4: Redis OOM (out of memory)                               |
  |  Response:                                                            |
  |    - Redis eviction policy: allkeys-lru (least recently used)       |
  |    - Hot queries stay cached, cold queries evicted                  |
  |    - Monitor: redis_evicted_keys_total counter                      |
  |    - Action: increase Redis memory or reduce TTLs                    |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 11.2 Circuit Breaker for Cache

```java
// Circuit breaker for Redis cache access
public class CacheCircuitBreaker {

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant circuitOpenedAt = null;
    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration HALF_OPEN_DELAY = Duration.ofSeconds(30);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public State getState() {
        if (consecutiveFailures.get() < FAILURE_THRESHOLD) {
            return State.CLOSED;  // normal operation, use cache
        }
        if (circuitOpenedAt != null &&
            Duration.between(circuitOpenedAt, Instant.now()).compareTo(HALF_OPEN_DELAY) > 0) {
            return State.HALF_OPEN;  // try one request to see if Redis recovered
        }
        return State.OPEN;  // bypass cache entirely
    }

    public <T> T executeWithFallback(Supplier<T> cacheOp, Supplier<T> fallback) {
        if (getState() == State.OPEN) {
            return fallback.get();  // bypass cache
        }

        try {
            T result = cacheOp.get();
            consecutiveFailures.set(0);   // reset on success
            circuitOpenedAt = null;
            return result;
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= FAILURE_THRESHOLD && circuitOpenedAt == null) {
                circuitOpenedAt = Instant.now();
            }
            return fallback.get();  // fall through to TSDB
        }
    }
}
```

---

## 12. Cache Decision Matrix

```
  +-----------------------------------------------------------------------+
  |  CACHE DECISION MATRIX                                                |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Question                         | Yes               | No            |
  |  ----------------------------------+-------------------+---------------+
  |  Is it a read-heavy, stable query? | CACHE (L2)       | Don't cache   |
  |  Is computation expensive (>50ms)? | CACHE (L2)       | Maybe L1 only |
  |  Does staleness cause correctness  | Do NOT cache     | CACHE okay    |
  |    issues (alerting, billing)?     |                   |               |
  |  Is it shared across users         | CACHE (L2 Redis) | L1 JVM only   |
  |    (same dashboard)?               |                   |               |
  |  Is data volume too large for      | Use downsampling  | CACHE in Redis|
  |    Redis (>10GB)?                  | (L0) instead      |               |
  |  Does data change every second?    | Very short TTL    | Longer TTL    |
  |                                    | (15s) or skip     | (minutes)     |
  |  Is it write-path data (ingestion)?| NEVER cache       | -             |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## 13. Simulation-to-Production Cache Mapping

```
  +-----------------------------------------------------------------------+
  |  SIMULATION -> PRODUCTION CACHE MAPPING                               |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Simulation                          | Production                     |
  |  ------------------------------------+--------------------------------+
  |  ConcurrentHashMap in services       | Redis cluster (L2 cache)       |
  |  InMemoryMetricRepository (all data  | TSDB (raw) + continuous        |
  |    in memory = instant "cache")      |   aggregates (L0 downsample)   |
  |  MetricAggregator.aggregate()        | PromQL recording rules +       |
  |    (computed on demand)              |   Redis query result cache     |
  |  ServiceMapService.getMap()          | Redis-cached service map with  |
  |    (scans all traces each time)      |   5-min TTL + event invalidation|
  |  DashboardService.getStats()         | Grafana + Redis panel cache    |
  |  AlertService.evaluate()             | Alertmanager (NO cache, fresh  |
  |    (reads from repository directly)  |   TSDB query every eval cycle) |
  |  ObservabilityStatsDisplay           | Grafana frontend rendering     |
  |    (formats and prints)              |   + browser cache              |
  |                                                                       |
  |  Key insight: the simulation keeps ALL data in memory (Java heap),   |
  |  which is effectively a perfect L1 cache with no eviction. In        |
  |  production, data lives in TSDB/Cassandra/S3, and we use Redis       |
  |  and downsampling to approximate the speed of in-memory access.      |
  |                                                                       |
  +-----------------------------------------------------------------------+
```
