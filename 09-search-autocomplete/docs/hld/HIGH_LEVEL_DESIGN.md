# High-Level Design: Search Autocomplete (Typeahead) System

> Interview-optimized system design document.
> Target: 30-45 minute system design discussion.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Data Model](#7-data-model)
8. [High-Level Architecture](#8-high-level-architecture)
9. [Component Deep Dive](#9-component-deep-dive)
10. [Trie Deep Dive](#10-trie-deep-dive)
11. [Ranking Algorithms](#11-ranking-algorithms)
12. [Data Collection & Aggregation](#12-data-collection--aggregation)
13. [Concurrency](#13-concurrency)
14. [Scaling](#14-scaling)
15. [Database Choice](#15-database-choice)
16. [CAP Theorem](#16-cap-theorem)
17. [Cloud Services](#17-cloud-services)
18. [Tradeoffs Summary](#18-tradeoffs-summary)
19. [Interview Talking Points](#19-interview-talking-points)

---

## 1. Problem Statement

Design a **Search Autocomplete (Typeahead) System** that provides real-time search suggestions as the user types each character. When a user types "how to le", the system should instantly return a ranked list of suggestions like "how to learn java", "how to learn guitar", "how to learn python". The system must handle billions of queries per day, return suggestions within 100 milliseconds, and continuously learn from new search patterns.

**Why is it needed?**

- Users expect instant feedback while typing -- every major search engine, e-commerce site, and app provides typeahead.
- Autocomplete reduces keystrokes by 25-50%, improving user experience and reducing mobile typing friction.
- It guides users toward popular, well-indexed queries, improving search result quality.
- Trending topics and personalized suggestions increase engagement and session duration.
- At scale (5B searches/day), the system must serve suggestions for every keystroke across hundreds of millions of concurrent users with sub-100ms latency.

**Core Workflow:**

```
User types "how to le" in the search box

(1) Client (Browser/App) --GET /suggestions?prefix=how+to+le&limit=10-->  API Gateway
(2) API Gateway --> Rate Limiter: check request quota
(3) API Gateway --> Autocomplete Service: lookup prefix
(4) Autocomplete Service --> Local Cache (L1): check prefix "how to le"
(5) Cache MISS --> Distributed Cache (Redis): check prefix "how to le"
(6) Cache MISS --> Trie Service: prefix search in compressed Trie
(7) Trie Service --> Trie Node for "how to le": retrieve pre-computed top-K
(8) Trie Service --> Ranking Service: re-rank by recency, personalization, trending
(9) Ranking Service --> Autocomplete Service: return ranked top-10
(10) Autocomplete Service --> Cache (Redis + L1): store result with TTL
(11) Autocomplete Service --> API Gateway: return suggestions
(12) API Gateway --> Client: display dropdown with 10 suggestions

Meanwhile (async, on search submit):
(13) User selects "how to learn java" and submits search
(14) Client --POST /search--> Search Service (actual search)
(15) Search Service --> Kafka: publish search event {query, userId, timestamp}
(16) Data Collection Service --> Kafka: consume search events
(17) Data Collection Service --> Query Log Store: append raw log
(18) Aggregation Job (hourly) --> Query Log Store: read logs, aggregate frequencies
(19) Trie Builder --> Aggregated Data: build new compressed Trie
(20) Trie Builder --> Trie Service: hot-swap new Trie into production
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Medium-Hard**. It appears frequently at Google, Meta, Amazon, Microsoft, and LinkedIn because it tests a unique combination of data structures, caching, and real-time systems:

| Skill Tested                    | What Interviewers Look For                                        |
|---------------------------------|-------------------------------------------------------------------|
| **Trie Data Structure**         | Can you explain Trie, compressed Trie, and Ternary Search Tree?   |
| **Top-K Problem**               | Pre-computing vs on-the-fly ranking at each Trie node             |
| **Ranking / Relevance**         | Frequency, recency, personalization, trending -- how to combine?  |
| **Caching Strategy**            | Multi-level cache: local (L1) + distributed (Redis) + CDN         |
| **Real-Time vs Batch**          | Streaming new queries vs periodic Trie rebuilds                   |
| **Latency Optimization**        | Sub-100ms p99 at billions of QPS -- every millisecond matters     |
| **Sharding Strategy**           | How to shard a Trie by prefix range across machines               |
| **Concurrency**                 | Read-heavy workload, copy-on-write for Trie updates               |
| **Data Pipeline**               | Kafka -> aggregation -> Trie build -> hot-swap deployment         |
| **Space-Time Tradeoffs**        | Pre-compute top-K at every node (space) vs compute on-the-fly (time) |

> **Interview tip**: Start by clarifying the scale (how many queries/day, how many unique queries, latency target). Then draw the high-level flow. Interviewers typically deep-dive into one of: Trie internals, ranking algorithm, or the data pipeline. Be ready to pivot. The Trie + top-K pre-computation is the "aha moment" that separates strong candidates.

---

## 2. Scope

### In Scope

| Feature                          | Description                                                          |
|----------------------------------|----------------------------------------------------------------------|
| Prefix-Based Suggestions         | Return top-K suggestions matching the user's typed prefix            |
| Frequency-Based Ranking          | Rank suggestions by historical search frequency                      |
| Recency-Weighted Ranking         | Recent queries rank higher via exponential decay                     |
| Personalized Suggestions         | Factor in user's search history, location, and language              |
| Trending Suggestions             | Detect and boost queries with sudden frequency spikes                |
| Spell Correction Hints           | Suggest corrected queries for common misspellings                    |
| Data Collection Pipeline         | Log every search query, aggregate frequencies for Trie building      |
| Trie Build & Hot-Swap            | Periodically rebuild Trie from aggregated data, swap without downtime|
| Multi-Level Caching              | Local cache (in-process) + Redis + optional CDN for hot prefixes     |
| Deletion / Filtering             | Remove offensive, dangerous, or legally problematic suggestions      |

### Out of Scope

| Feature                          | Reason                                                               |
|----------------------------------|----------------------------------------------------------------------|
| Full-Text Search Engine          | Separate system (Elasticsearch/Solr); we only do prefix suggestions  |
| Search Results Rendering         | We return suggestions, not the actual search results page            |
| Image / Video Suggestions        | Rich media previews are a UI concern, not core autocomplete          |
| Natural Language Understanding   | Semantic search, intent detection -- separate NLP service            |
| Voice-to-Text Autocomplete       | Speech recognition is a different pipeline                           |
| Ad-Sponsored Suggestions         | Business/monetization layer, not core system design                  |
| Multi-Language Tokenization      | Assume UTF-8 strings; CJK tokenization is a deep NLP topic          |

---

## 3. Assumptions

### Platform Scale

| Parameter                        | Value                              |
|----------------------------------|------------------------------------|
| Total searches per day           | 5 billion                          |
| Unique queries in corpus         | 200 million                        |
| Suggestions per request          | 10 (top-K = 10)                    |
| Average query length             | 20 characters                      |
| Average keystrokes before select | 8 keystrokes (then user picks)     |
| Autocomplete requests per search | ~8 (one per keystroke)             |
| Total autocomplete requests/day  | 5B * 8 = 40 billion               |
| Peak QPS (autocomplete)          | 40B / 86400 * 3 (peak factor) = ~1.4M QPS |
| Daily active users               | 500 million                        |
| Concurrent users (peak)          | 50 million                         |

### Data Volume

| Parameter                        | Value                              |
|----------------------------------|------------------------------------|
| Unique queries in Trie           | 200 million                        |
| Average query size               | 20 bytes (ASCII)                   |
| Raw Trie size (uncompressed)     | ~15 GB (with all node metadata)    |
| Compressed Trie size             | ~5 GB (radix tree compression)     |
| Top-K stored per node            | 10 suggestions * 20 bytes = 200 bytes/node |
| Trie + top-K total               | ~8-10 GB (fits in memory)          |
| Daily query logs                 | 5B * 50 bytes avg = ~250 GB/day   |
| Aggregated frequency table       | 200M entries * 50 bytes = ~10 GB   |

### Back-of-the-Envelope: Latency Budget

```
Total target:  p99 < 100 ms (from keystroke to suggestions rendered)

Breakdown:
  (1) Client-side debounce:          50 ms (wait for next keystroke)
  (2) Network RTT (client -> CDN):   10-30 ms
  (3) API Gateway + auth:             5 ms
  (4) L1 Cache lookup (in-process):   0.1 ms  (cache HIT -> done)
  (5) Redis Cache lookup:             1-2 ms  (cache HIT -> done)
  (6) Trie prefix traversal:          0.5 ms  (O(L) where L = prefix length)
  (7) Top-K retrieval at node:        0.1 ms  (pre-computed, just read)
  (8) Ranking re-score:               1-2 ms  (lightweight re-rank)
  (9) Serialize response:             0.5 ms
  (10) Network RTT (server -> client): 10-30 ms
  ------------------------------------------
  Cache HIT path:                    ~20-40 ms (step 1+2+3+4+10)
  Cache MISS path:                   ~25-70 ms (all steps)

Cache hit ratio target: 90%+ (popular prefixes are highly cacheable)
  - "how to" is typed millions of times/day -> always cached
  - Long-tail prefixes like "obscure_query_xyz" -> cache miss, Trie lookup

QPS capacity per server:
  Single Trie server (in-memory): ~50K lookups/sec
  28 Trie servers needed for 1.4M QPS (with cache absorbing 90%)
  Actual Trie QPS after cache: 1.4M * 0.1 = 140K QPS -> ~3 servers (with replication, use 9-12)
```

---

## 4. Functional Requirements

### FR-1: Prefix Search
Given a prefix string typed by the user, return the top-K (default 10) matching query suggestions that start with that prefix. The prefix match must be case-insensitive.

### FR-2: Top-K Ranked Results
Suggestions must be ranked by a composite score combining historical frequency, recency, and relevance. The highest-scored suggestions appear first.

### FR-3: Personalized Suggestions
Factor in the user's personal search history. If user "U1" frequently searches "java streams", then when U1 types "ja", "java streams" should rank higher than the global default "japan travel".

### FR-4: Trending Suggestions
Detect queries with sudden frequency spikes (e.g., breaking news, viral events). Boost these queries temporarily so they appear in suggestions even if their all-time frequency is low.

### FR-5: Spell Correction Hints
If the typed prefix closely matches a popular query but with a common misspelling (e.g., "amazn" -> "amazon"), include the corrected suggestion with an indicator: "Did you mean: amazon?"

### FR-6: Offensive Content Filtering
Suggestions must be filtered against a blocklist. Offensive, dangerous, or legally sensitive queries must never appear as suggestions, even if they are frequently searched.

### FR-7: Real-Time Data Ingestion
Every completed search query must be captured and fed into the data pipeline. New trending queries should surface in suggestions within minutes (not hours).

### FR-8: Multi-Language Support
Support autocomplete for multiple languages. The Trie must handle UTF-8 encoded strings for non-ASCII alphabets (accented characters, CJK approximation via prefix).

### FR-9: Query Deletion
Users can request deletion of their personal search history from suggestions (GDPR/CCPA compliance). Admin can remove specific queries from the global suggestion pool.

### FR-10: Graceful Degradation
If the Trie service is unavailable, fall back to cached results (even if stale). If cache is also unavailable, return an empty suggestion list -- never block the user's typing.

---

## 5. Non-Functional Requirements

| Requirement              | Target                           | Rationale                                                      |
|--------------------------|----------------------------------|----------------------------------------------------------------|
| **Suggestion Latency**   | p99 < 100 ms (end-to-end)       | Users perceive > 100ms as laggy; breaks "instant" feel         |
| **Trie Lookup Latency**  | p99 < 5 ms                       | In-memory Trie traversal must be sub-millisecond               |
| **Availability**         | 99.99% (52 min/year downtime)    | Autocomplete is critical UX; degraded search without it        |
| **Throughput**           | 1.4M QPS (peak) for suggestions  | 5B searches/day * 8 keystrokes * peak factor                   |
| **Cache Hit Ratio**      | > 90%                            | Most queries share popular prefixes; cache absorbs the load    |
| **Freshness**            | Trending queries within 5 min    | Breaking news must surface quickly in suggestions              |
| **Trie Update Latency**  | < 1 hour for full rebuild        | Hourly batch rebuild with hot-swap is acceptable               |
| **Data Durability**      | No query log loss                | Kafka with replication ensures all search events are captured  |
| **Scalability**          | Linear horizontal scaling         | Adding Trie shards proportionally increases capacity           |
| **Fault Tolerance**      | Single node failure = no impact   | Replication ensures every shard has 2-3 replicas               |

---

## 6. API Design

### 6.1 Get Suggestions (Primary API)

```
GET /api/v1/suggestions?prefix=how+to+le&limit=10&userId=user_abc123
Authorization: Bearer <token>
Accept: application/json
```

**Query Parameters:**

| Parameter  | Type     | Required | Default | Description                                     |
|------------|----------|----------|---------|-------------------------------------------------|
| `prefix`   | String   | Yes      | --      | The prefix typed by the user (URL-encoded)      |
| `limit`    | Integer  | No       | 10      | Number of suggestions to return (max 25)        |
| `userId`   | String   | No       | null    | User ID for personalized ranking                |
| `language` | String   | No       | "en"    | Language code for multi-language support         |
| `location` | String   | No       | null    | Geo coordinates for location-aware suggestions  |

**Response (200 OK):**

```json
{
  "prefix": "how to le",
  "suggestions": [
    {
      "query": "how to learn java",
      "score": 0.95,
      "type": "POPULAR",
      "metadata": {
        "frequency": 2450000,
        "trending": false,
        "personalized": false
      }
    },
    {
      "query": "how to learn python",
      "score": 0.92,
      "type": "POPULAR",
      "metadata": {
        "frequency": 2200000,
        "trending": false,
        "personalized": false
      }
    },
    {
      "query": "how to learn guitar",
      "score": 0.88,
      "type": "POPULAR",
      "metadata": {
        "frequency": 1800000,
        "trending": false,
        "personalized": false
      }
    },
    {
      "query": "how to learn ai",
      "score": 0.85,
      "type": "TRENDING",
      "metadata": {
        "frequency": 450000,
        "trending": true,
        "personalized": false
      }
    },
    {
      "query": "how to learn spring boot",
      "score": 0.82,
      "type": "PERSONALIZED",
      "metadata": {
        "frequency": 320000,
        "trending": false,
        "personalized": true
      }
    }
  ],
  "took_ms": 12,
  "from_cache": true
}
```

**Response (400 Bad Request -- empty prefix):**

```json
{
  "error": "INVALID_PREFIX",
  "message": "Prefix must be at least 1 character long.",
  "code": 400
}
```

### 6.2 Record Search Event (Internal)

```
POST /api/v1/internal/search-events
Content-Type: application/json
X-Internal-Auth: <service-token>
```

**Request:**

```json
{
  "query": "how to learn java",
  "user_id": "user_abc123",
  "timestamp": "2026-04-26T14:30:00Z",
  "session_id": "sess_xyz789",
  "location": {
    "country": "US",
    "region": "CA",
    "city": "San Francisco"
  },
  "device_type": "MOBILE",
  "selected_position": 2
}
```

**Response (202 Accepted):**

```json
{
  "status": "ACCEPTED",
  "event_id": "evt_abc123"
}
```

### 6.3 Delete User Search History (GDPR)

```
DELETE /api/v1/users/{userId}/search-history
Authorization: Bearer <user_token>
```

**Response (200 OK):**

```json
{
  "user_id": "user_abc123",
  "deleted_count": 1247,
  "message": "Search history queued for deletion. Suggestions will be updated within 1 hour."
}
```

### 6.4 Admin: Block Query from Suggestions

```
POST /api/v1/admin/blocklist
Authorization: Bearer <admin_token>
Content-Type: application/json
```

**Request:**

```json
{
  "query": "offensive_query_example",
  "reason": "HATE_SPEECH",
  "blocked_by": "admin_001",
  "effective_immediately": true
}
```

**Response (201 Created):**

```json
{
  "query": "offensive_query_example",
  "status": "BLOCKED",
  "effective_at": "2026-04-26T14:30:00Z"
}
```

### API Design Decisions

```
Why GET instead of POST for suggestions?
(1) GET is idempotent and cacheable -- CDN/proxy can cache popular prefixes
(2) Browsers and HTTP clients can cache GET responses natively
(3) No request body needed -- prefix fits in query string (max 200 chars)
(4) POST would defeat HTTP caching and add unnecessary overhead

Why userId as a query parameter (not header)?
(1) Makes cache key explicit: prefix + userId = unique cache entry
(2) Anonymous users (no userId) share the global cache -> higher hit ratio
(3) Personalized cache entries have shorter TTL (user behavior changes)

Why limit defaults to 10?
(1) UX research shows 5-10 suggestions is optimal -- more causes decision fatigue
(2) Limits response size to ~2 KB -> fast serialization and transfer
(3) Mobile screens physically cannot display more than 7-8 suggestions
```

---

## 7. Data Model

### 7.1 Query Frequency Table (Primary Data Source for Trie)

```
Table: query_frequencies
Purpose: Aggregated search frequencies, source of truth for Trie building

+-------------------+--------+-----------------------------------------------+
| Column            | Type   | Description                                   |
+-------------------+--------+-----------------------------------------------+
| query             | String | The normalized search query (PK)              |
| frequency         | Long   | Total search count (all time)                 |
| recent_frequency  | Long   | Search count in last 7 days                   |
| trending_score    | Double | Spike detection score (0.0 - 1.0)             |
| last_searched_at  | Long   | Epoch millis of most recent search             |
| created_at        | Long   | Epoch millis when first seen                   |
| language          | String | Language code ("en", "es", "fr")               |
| is_blocked        | Bool   | True if query is on the blocklist              |
+-------------------+--------+-----------------------------------------------+

Example rows:
| query                  | frequency  | recent_freq | trending | last_searched  |
|------------------------|------------|-------------|----------|----------------|
| how to learn java      | 2,450,000  | 45,000      | 0.02     | 1745675400000  |
| how to learn python    | 2,200,000  | 42,000      | 0.01     | 1745675380000  |
| how to learn ai        | 450,000    | 120,000     | 0.85     | 1745675395000  |
| amazon prime           | 8,500,000  | 150,000     | 0.05     | 1745675399000  |
```

### 7.2 Raw Search Event Log

```
Table: search_events (append-only log, stored in Kafka -> HDFS/S3)
Purpose: Raw event stream for aggregation and analytics

+--------------------+--------+-----------------------------------------------+
| Column             | Type   | Description                                   |
+--------------------+--------+-----------------------------------------------+
| event_id           | String | Unique event ID (UUID)                        |
| query              | String | The search query submitted by the user        |
| user_id            | String | User identifier (nullable for anonymous)      |
| session_id         | String | Session identifier                            |
| timestamp          | Long   | Epoch millis when search was submitted        |
| country            | String | ISO country code from IP geolocation          |
| device_type        | String | MOBILE / DESKTOP / TABLET                     |
| selected_position  | Int    | Which suggestion position was clicked (0-9)   |
| was_suggestion     | Bool   | True if user clicked a suggestion vs typed fully |
+--------------------+--------+-----------------------------------------------+

Volume: ~5 billion rows/day, ~250 GB/day
Retention: 30 days hot (Kafka), 1 year cold (S3/HDFS)
```

### 7.3 User Search History (Personalization)

```
Table: user_search_history
Purpose: Per-user search history for personalized ranking

+--------------------+--------+-----------------------------------------------+
| Column             | Type   | Description                                   |
+--------------------+--------+-----------------------------------------------+
| user_id            | String | User identifier (partition key)               |
| query              | String | The search query                              |
| search_count       | Int    | How many times this user searched this query   |
| last_searched_at   | Long   | Epoch millis of most recent search             |
+--------------------+--------+-----------------------------------------------+

Storage: Redis hash per user (small footprint, fast lookup)
  Key:   user_history:{user_id}
  Field: {query}
  Value: {count}:{last_timestamp}

Example:
  HSET user_history:user_abc123 "java streams" "15:1745675400000"
  HSET user_history:user_abc123 "spring boot tutorial" "8:1745675300000"

Retention: Last 100 queries per user (capped, LRU eviction)
```

### 7.4 Blocklist

```
Table: blocked_queries
Purpose: Queries that must never appear as suggestions

+--------------------+--------+-----------------------------------------------+
| Column             | Type   | Description                                   |
+--------------------+--------+-----------------------------------------------+
| query              | String | The blocked query (PK)                        |
| reason             | String | HATE_SPEECH / ILLEGAL / PRIVACY / OTHER       |
| blocked_by         | String | Admin ID who blocked it                       |
| blocked_at         | Long   | Epoch millis                                  |
+--------------------+--------+-----------------------------------------------+

Storage: HashSet in memory on every Trie server (small -- typically < 100K entries)
         Backed by a database table for persistence.
```

### 7.5 Trie Node (In-Memory Data Structure)

```
TrieNode (conceptual schema -- not a DB table, lives in JVM heap)

+--------------------+-----------------+------------------------------------+
| Field              | Type            | Description                        |
+--------------------+-----------------+------------------------------------+
| children           | Map<Char, Node> | Child nodes (one per character)    |
| isEndOfWord        | boolean         | True if this node ends a valid query|
| topSuggestions     | List<Suggestion>| Pre-computed top-K suggestions     |
| frequency          | long            | Frequency if isEndOfWord = true    |
+--------------------+-----------------+------------------------------------+

Suggestion:
+--------------------+-----------------+------------------------------------+
| Field              | Type            | Description                        |
+--------------------+-----------------+------------------------------------+
| query              | String          | Full query text                    |
| score              | double          | Composite ranking score            |
| frequency          | long            | Raw search frequency               |
+--------------------+-----------------+------------------------------------+

Memory layout (per node):
  - children map: ~16 bytes (HashMap overhead) + 48 bytes per entry
  - isEndOfWord: 1 byte (padded to 8)
  - topSuggestions: ~200 bytes (10 suggestions * 20 bytes reference)
  - frequency: 8 bytes
  Total per node: ~50-250 bytes depending on children count
```

### Entity Relationship Diagram

```
+-------------------+       logs to       +-------------------+
|   User / Client   | -----------------> |  Search Event Log  |
+-------------------+                    +-------------------+
        |                                        |
        | types prefix                           | aggregated by
        v                                        v
+-------------------+                    +-------------------+
| Autocomplete API  |                    | Query Frequencies  |
+-------------------+                    +-------------------+
        |                                        |
        | looks up                               | builds
        v                                        v
+-------------------+     hot-swap       +-------------------+
|   Trie (Memory)   | <---------------- |   Trie Builder     |
+-------------------+                    +-------------------+
        |
        | filters against
        v
+-------------------+
|    Blocklist      |
+-------------------+
```

---

## 8. High-Level Architecture

### System Architecture Diagram

```
+------------------------------------------------------------------+
|                          CLIENTS                                  |
|  +------------+  +------------+  +------------+  +------------+  |
|  | Web Browser|  | Mobile App |  | Desktop App|  | Voice Asst |  |
|  | (debounce  |  | (debounce  |  |            |  |            |  |
|  |  50-100ms) |  |  100-200ms)|  |            |  |            |  |
|  +-----+------+  +-----+------+  +-----+------+  +-----+------+ |
+--------|-----------------|-----------------|-----------------+---+
         |                 |                 |                 |
         +--------+--------+--------+--------+
                  |
                  v
+------------------------------------------------------------------+
|                      CDN / EDGE CACHE                             |
|                                                                   |
|  Cache popular prefixes at edge locations                         |
|  "how to" -> cached at CDN, never hits origin                    |
|  TTL: 5-15 minutes                                                |
+------------------------------------------------------------------+
                  |
                  | (1) Cache MISS -> forward to origin
                  v
+------------------------------------------------------------------+
|                       LOAD BALANCER                               |
|                                                                   |
|  Round-robin / least-connections across API Gateway instances     |
+------------------------------------------------------------------+
                  |
                  | (2) Route request
                  v
+------------------------------------------------------------------+
|                       API GATEWAY                                 |
|                                                                   |
|  - Authentication (verify Bearer token)                          |
|  - Rate limiting (per user: 20 req/sec, per IP: 50 req/sec)     |
|  - Request validation (prefix length 1-200, limit 1-25)         |
|  - Request routing                                                |
|  - Response compression (gzip)                                    |
+------------------------------------------------------------------+
                  |
                  | (3) Forward to Autocomplete Service
                  v
+------------------------------------------------------------------+
|                  AUTOCOMPLETE SERVICE                             |
|                                                                   |
|  +------------------+     +------------------+                    |
|  | L1 In-Process    |     | Request Handler  |                    |
|  | Cache (Caffeine) | <-- | (Java 21 Thread) |                    |
|  | TTL: 60 sec      |     |                  |                    |
|  | Max: 100K entries|     +--------+---------+                    |
|  +------------------+              |                              |
|         |                          | (4) L1 MISS                  |
|         | HIT? Return              v                              |
|         |              +-----------------------+                   |
|         |              | Distributed Cache     |                   |
|         |              | (Redis Cluster)       |                   |
|         |              | TTL: 5 min            |                   |
|         |              | Prefix -> Top-K JSON  |                   |
|         |              +-----------+-----------+                   |
|         |                          |                              |
|         |                          | (5) Redis MISS               |
|         |                          v                              |
|         |              +-----------------------+                   |
|         |              |    TRIE SERVICE       |                   |
|         |              |  (In-Memory Trie)     |                   |
|         |              |                       |                   |
|         |              | - Traverse prefix     |                   |
|         |              | - Read top-K at node  |                   |
|         |              | - Filter blocklist    |                   |
|         |              +-----------+-----------+                   |
|         |                          |                              |
|         |                          | (6) Raw top-K candidates     |
|         |                          v                              |
|         |              +-----------------------+                   |
|         |              |  RANKING SERVICE      |                   |
|         |              |                       |                   |
|         |              | - Frequency score     |                   |
|         |              | - Recency decay       |                   |
|         |              | - User personalization|                   |
|         |              | - Trending boost      |                   |
|         |              +-----------+-----------+                   |
|         |                          |                              |
|         |                          | (7) Final ranked top-K       |
|         |                          v                              |
|         +<-------------------------+                              |
|         |  (8) Cache result in L1 + Redis                         |
+------------------------------------------------------------------+
                  |
                  | (9) Return suggestions to client
                  v
            +------------+
            |   CLIENT   |
            | Display    |
            | dropdown   |
            +------------+


=== ASYNC DATA PIPELINE (Offline / Near-Real-Time) ===

+------------------------------------------------------------------+
|                      DATA INGESTION                               |
|                                                                   |
|  User submits search                                              |
|       |                                                           |
|       | (10) POST /search                                         |
|       v                                                           |
|  +-----------+     (11)      +-----------+                        |
|  |  Search   | -----------> |   Kafka   |                         |
|  |  Service  |   publish    |  (search  |                         |
|  |           |   event      |   events  |                         |
|  +-----------+              |   topic)  |                         |
|                              +-----+-----+                        |
|                                    |                              |
|                    +---------------+---------------+               |
|                    |                               |               |
|                    v                               v               |
|  +---------------------------+   +---------------------------+    |
|  | Data Collection Service   |   | Trending Detection        |    |
|  | (Kafka Consumer)          |   | (Kafka Streams / Flink)   |    |
|  |                           |   |                           |    |
|  | (12) Append to raw log    |   | (13) Sliding window count |    |
|  |      (S3 / HDFS)         |   |      Spike detection      |    |
|  |                           |   |      Update trending flag |    |
|  +-------------+-------------+   +---------------------------+    |
|                |                                                  |
|                | (14) Raw logs accumulated                        |
|                v                                                  |
|  +---------------------------+                                    |
|  | Aggregation Job           |                                    |
|  | (Spark / MapReduce)       |                                    |
|  |                           |                                    |
|  | (15) Hourly: count freq   |                                    |
|  |      per query, per day   |                                    |
|  | Output: query_frequencies |                                    |
|  +-------------+-------------+                                    |
|                |                                                  |
|                | (16) Updated frequency table                     |
|                v                                                  |
|  +---------------------------+                                    |
|  |     TRIE BUILDER          |                                    |
|  |                           |                                    |
|  | (17) Read aggregated data |                                    |
|  | (18) Build compressed Trie|                                    |
|  | (19) Compute top-K at     |                                    |
|  |      each node            |                                    |
|  | (20) Serialize Trie       |                                    |
|  | (21) Hot-swap into prod   |                                    |
|  +---------------------------+                                    |
+------------------------------------------------------------------+
```

### Request Flow (Happy Path -- Cache HIT)

```
(1) User types "ama" in search box
(2) Client debounce timer fires after 50ms of no new keystrokes
(3) Client sends: GET /api/v1/suggestions?prefix=ama&limit=10&userId=user_123
(4) CDN edge: cache MISS (first request from this edge for "ama" + user_123)
(5) Load Balancer routes to API Gateway instance #3
(6) API Gateway: validates token, checks rate limit (OK), forwards request
(7) Autocomplete Service: checks L1 cache for key "ama:user_123"
(8) L1 Cache HIT! Returns cached result from 30 seconds ago
(9) Response sent: 10 suggestions including "amazon", "amazon prime", "amc stock"
(10) Total latency: 18 ms

No Trie lookup, no Redis call, no ranking computation.
This is the 90%+ common case for popular prefixes.
```

### Request Flow (Cache MISS Path)

```
(1) User types "how to bui" in search box
(2) Client debounce fires, sends GET /suggestions?prefix=how+to+bui&limit=10
(3) CDN MISS, Load Balancer -> API Gateway -> Autocomplete Service
(4) L1 Cache: MISS for "how to bui" (long-tail prefix, not recently seen)
(5) Redis Cache: MISS (prefix not in distributed cache either)
(6) Trie Service: traverse Trie -- h -> o -> w -> ' ' -> t -> o -> ' ' -> b -> u -> i
(7) At node 'i' (end of "how to bui"): read pre-computed topSuggestions
    Candidates: [how to build a website, how to build muscle, how to build credit,
                 how to build a pc, how to build a resume, ...]
(8) Ranking Service: re-score candidates
    - "how to build a website" -> freq: 1.2M, recent: high, no personalization
    - "how to build a pc" -> freq: 800K, trending: true (new GPU launch)
    - Personalization: user searched "java" a lot -> boost "how to build a java app"
(9) Final ranked list returned to Autocomplete Service
(10) Store in L1 Cache (TTL 60s) and Redis Cache (TTL 5min)
(11) Return to client
(12) Total latency: 45 ms
```

---

## 9. Component Deep Dive

### 9.1 Trie Service

The Trie Service is the **heart of the autocomplete system**. It holds the entire Trie data structure in JVM memory and serves prefix lookup requests.

**Responsibilities:**

```
+---------------------------+
|       TRIE SERVICE        |
|                           |
| (1) Hold Trie in memory   |
| (2) Prefix traversal      |
| (3) Return top-K at node  |
| (4) Blocklist filtering   |
| (5) Accept hot-swap Trie  |
+---------------------------+
        |           ^
        |           |
  (read path)   (write path -- hot swap)
        |           |
        v           |
  +----------+  +----------+
  | Request  |  |  Trie    |
  | Handlers |  |  Builder |
  +----------+  +----------+
```

**Prefix Lookup Algorithm:**

```
Input: prefix = "how to le", K = 10

(1) Start at root node of Trie
(2) Traverse: root -> 'h' -> 'o' -> 'w' -> ' ' -> 't' -> 'o' -> ' ' -> 'l' -> 'e'
(3) Arrive at node representing "how to le"
(4) Read node.topSuggestions (pre-computed list of top-K queries under this prefix)
(5) Filter against blocklist HashSet
(6) Return remaining top-K suggestions

Time complexity: O(P + K) where P = prefix length, K = suggestions count
Space complexity: O(1) -- just pointer traversal, no allocation

Why pre-compute top-K at every node?
- Without pre-computation: must DFS the entire subtree to find top queries
  For "h" prefix: subtree has millions of nodes -> takes seconds
- With pre-computation: just read the list -> O(1) after traversal
- Tradeoff: extra memory (200 bytes/node * millions of nodes = ~1 GB)
  but lookup goes from O(subtree_size) to O(prefix_length)
```

**Hot-Swap Mechanism:**

```
Current state: TrieService holds reference -> Trie_V1 (serving live traffic)

(1) Trie Builder constructs Trie_V2 offline (takes 10-30 minutes)
(2) Trie Builder serializes Trie_V2, ships to Trie Service machines
(3) Trie Service deserializes Trie_V2 in background thread
(4) Atomic reference swap:
    
    // Java 21 -- using volatile reference for visibility
    private volatile TrieNode currentRoot;
    
    // Called by background thread when new Trie is ready
    public void swapTrie(TrieNode newRoot) {
        this.currentRoot = newRoot;  // Single volatile write -- atomic
        // Old Trie becomes eligible for GC once all in-flight reads complete
    }
    
    // Called by request threads
    public List<Suggestion> lookup(String prefix) {
        TrieNode root = this.currentRoot;  // Single volatile read
        // Traverse using this local reference -- safe even if swap happens mid-read
        return traverseAndReturn(root, prefix);
    }

(5) Old Trie_V1 is garbage collected after all in-flight reads finish
(6) Zero downtime, zero read blocking

Why this works:
- volatile ensures new Trie is visible to all threads immediately
- Local reference in lookup() means an in-flight read will complete on the old Trie
  (no mixed-version reads)
- No locks needed -- reads are naturally safe on an immutable Trie
```

**Memory Layout:**

```
JVM Heap for one Trie Service instance:

+----------------------------------------------+
|  JVM Heap: 16 GB                             |
|                                              |
|  +-----------+  Current Trie: ~5 GB          |
|  | Trie_V2   |  (compressed, with top-K)     |
|  | (current) |                               |
|  +-----------+                               |
|                                              |
|  +-----------+  During swap: ~5 GB           |
|  | Trie_V3   |  (new version being loaded)   |
|  | (loading) |                               |
|  +-----------+                               |
|                                              |
|  +----------+  Blocklist: ~10 MB             |
|  | Blocklist|  (HashSet<String>)             |
|  +----------+                                |
|                                              |
|  +----------+  L1 Cache: ~1 GB              |
|  | Caffeine |  (100K prefix -> results)      |
|  +----------+                                |
|                                              |
|  Remaining: ~5 GB (GC headroom, JVM overhead)|
+----------------------------------------------+

Total per instance: 16 GB heap recommended
During hot-swap: briefly holds 2 Tries (10 GB) -- need GC headroom
After swap: old Trie is GC'd, back to 5 GB steady state
```

### 9.2 Ranking Service

The Ranking Service takes raw candidate suggestions from the Trie and re-ranks them using multiple signals.

**Architecture:**

```
+------------------------------------------------------+
|                  RANKING SERVICE                      |
|                                                       |
|  Input: List<Suggestion> candidates (from Trie)       |
|         String userId (optional)                      |
|         String location (optional)                    |
|                                                       |
|  +-------------------------------------------------+ |
|  |            SCORING PIPELINE                      | |
|  |                                                  | |
|  |  (1) Frequency Score                             | |
|  |      score_freq = log10(frequency) / max_log     | |
|  |      Normalized to [0, 1]                        | |
|  |                                                  | |
|  |  (2) Recency Score                               | |
|  |      hours_ago = (now - last_searched) / 3600000 | |
|  |      score_recency = e^(-decay * hours_ago)      | |
|  |      decay = 0.01 -> half-life ~70 hours         | |
|  |                                                  | |
|  |  (3) Trending Score                              | |
|  |      score_trending = trending_score (0.0 - 1.0) | |
|  |      (pre-computed by trending detection)         | |
|  |                                                  | |
|  |  (4) Personalization Score                       | |
|  |      IF userId provided:                          | |
|  |        user_freq = user_history.get(query)        | |
|  |        score_personal = min(user_freq / 10, 1.0)  | |
|  |      ELSE:                                        | |
|  |        score_personal = 0.0                        | |
|  |                                                  | |
|  |  (5) Composite Score                             | |
|  |      final = w1*score_freq                        | |
|  |            + w2*score_recency                      | |
|  |            + w3*score_trending                     | |
|  |            + w4*score_personal                     | |
|  |                                                  | |
|  |      Weights (tunable):                           | |
|  |        w1 = 0.50 (frequency dominates)            | |
|  |        w2 = 0.15 (recency matters)                | |
|  |        w3 = 0.20 (trending boost significant)     | |
|  |        w4 = 0.15 (personalization)                | |
|  |                                                  | |
|  |  (6) Sort by final score descending               | |
|  |  (7) Return top-K                                 | |
|  +-------------------------------------------------+ |
+------------------------------------------------------+
```

**Ranking Example:**

```
Prefix: "app"
Candidates from Trie: [apple, apple stock, app store, apple music, applebees]

Scoring (for user who frequently searches tech topics):

| Query          | Freq Score | Recency | Trending | Personal | Final |
|----------------|-----------|---------|----------|----------|-------|
| apple          | 0.95      | 0.80    | 0.10     | 0.30     | 0.69  |
| app store      | 0.88      | 0.75    | 0.05     | 0.60     | 0.68  |
| apple stock    | 0.82      | 0.90    | 0.70     | 0.20     | 0.69  |
| apple music    | 0.78      | 0.70    | 0.02     | 0.10     | 0.52  |
| applebees      | 0.72      | 0.65    | 0.01     | 0.00     | 0.46  |

Weights: freq=0.50, recency=0.15, trending=0.20, personal=0.15

apple:       0.50*0.95 + 0.15*0.80 + 0.20*0.10 + 0.15*0.30 = 0.475+0.12+0.02+0.045 = 0.660
apple stock: 0.50*0.82 + 0.15*0.90 + 0.20*0.70 + 0.15*0.20 = 0.41+0.135+0.14+0.03  = 0.715
app store:   0.50*0.88 + 0.15*0.75 + 0.20*0.05 + 0.15*0.60 = 0.44+0.1125+0.01+0.09 = 0.653

Result order: apple stock, apple, app store, apple music, applebees
(Trending boost pushed "apple stock" to #1 despite lower base frequency)
```

### 9.3 Data Collection Service

The Data Collection Service captures every search query and feeds the data pipeline.

**Architecture:**

```
                      Search Queries from Users
                               |
                               v
+------------------------------------------------------------------+
|                   DATA COLLECTION SERVICE                         |
|                                                                   |
|  (1) Receive search event from Search Service                    |
|      {query, userId, timestamp, location, device, position}      |
|                                                                   |
|  (2) Normalize query:                                             |
|      - Lowercase                                                  |
|      - Trim whitespace                                            |
|      - Remove special characters (except hyphens, apostrophes)   |
|      - Collapse multiple spaces                                   |
|      Example: "  How To Learn   JAVA?! " -> "how to learn java"  |
|                                                                   |
|  (3) Filter:                                                      |
|      - Drop queries < 2 characters (noise)                        |
|      - Drop queries > 200 characters (abuse)                      |
|      - Drop queries matching blocklist patterns                   |
|      - Drop bot/scraper traffic (heuristic detection)            |
|                                                                   |
|  (4) Publish to Kafka:                                            |
|      Topic: search-events                                         |
|      Key: hash(normalized_query) % num_partitions                 |
|      Value: SearchEvent proto                                     |
|                                                                   |
|  (5) Update real-time counters (optional):                        |
|      Redis: INCR query_count:{normalized_query}                   |
|      Used for near-real-time trending detection                   |
+------------------------------------------------------------------+
         |
         v
+------------------------------------------------------------------+
|                        KAFKA CLUSTER                              |
|                                                                   |
|  Topic: search-events                                             |
|  Partitions: 64                                                   |
|  Replication factor: 3                                            |
|  Retention: 30 days                                               |
|  Throughput: ~60K events/sec (5B/day / 86400)                     |
|                                                                   |
|  Consumers:                                                       |
|    (a) Data Collection Writer -> S3/HDFS (raw logs)               |
|    (b) Trending Detection -> Flink (real-time aggregation)        |
|    (c) User History Updater -> Redis (per-user history)           |
+------------------------------------------------------------------+
```

**Aggregation Flow:**

```
(1) Raw logs land in S3/HDFS (partitioned by date/hour)
    s3://search-logs/2026/04/26/14/part-00001.parquet

(2) Hourly Spark job reads last hour's logs:
    SELECT query, COUNT(*) as freq, MAX(timestamp) as last_seen
    FROM search_events
    WHERE timestamp >= current_hour_start
    GROUP BY query

(3) Merge with existing frequency table:
    query_frequencies[query].frequency += hourly_freq
    query_frequencies[query].recent_frequency = rolling_7_day_sum
    query_frequencies[query].last_searched_at = max(last_seen)

(4) Daily Spark job computes trending scores:
    trending_score = (frequency_today / avg_frequency_last_30_days) - 1.0
    Clamped to [0, 1] -- high score = unusual spike

(5) Output: updated query_frequencies table (stored in HDFS + synced to DB)
```

### 9.4 Trie Builder

The Trie Builder is an **offline batch process** that reads the aggregated frequency data and constructs a new compressed Trie with pre-computed top-K at every node.

**Build Pipeline:**

```
+------------------------------------------------------------------+
|                       TRIE BUILDER                                |
|                                                                   |
|  Input: query_frequencies table (200M rows)                      |
|  Output: serialized Trie binary (5 GB compressed)                |
|  Frequency: every 1-4 hours                                       |
|  Duration: 10-30 minutes per build                                |
|                                                                   |
|  Step (1): Read all non-blocked queries with frequency > threshold|
|            threshold = 10 (ignore ultra-long-tail queries)        |
|            Result: ~50M queries (after filtering)                 |
|                                                                   |
|  Step (2): Sort queries alphabetically                            |
|            (enables sequential Trie construction)                 |
|                                                                   |
|  Step (3): Insert each query into Trie                            |
|            For each query "how to learn java" with freq 2,450,000:|
|            - Traverse/create nodes: h->o->w-> ->t->o-> ->l->...   |
|            - Mark final node: isEndOfWord = true, frequency = 2.4M|
|                                                                   |
|  Step (4): Compress Trie into Radix Tree                          |
|            Merge single-child chains:                              |
|            Before: h -> o -> w -> ' ' -> t -> o                   |
|            After:  "how to" (single node with label "how to")     |
|            Saves ~60% of nodes                                    |
|                                                                   |
|  Step (5): Compute top-K at every internal node (bottom-up)       |
|            - Leaf nodes: top-K = [(self.query, self.frequency)]   |
|            - Internal nodes: merge children's top-K lists,        |
|              keep only top-K by score                              |
|            - This is a post-order traversal of the Trie           |
|                                                                   |
|  Step (6): Serialize Trie to binary format                        |
|            Custom serialization (not Java Serializable -- too slow)|
|            Write to: s3://trie-builds/2026-04-26-14-00/trie.bin   |
|                                                                   |
|  Step (7): Distribute to Trie Service instances                   |
|            Each instance downloads trie.bin from S3                |
|            Deserialize into JVM heap                               |
|            Atomic hot-swap via volatile reference                  |
+------------------------------------------------------------------+
```

**Top-K Propagation (Bottom-Up):**

```
Example Trie fragment for prefix "app":

                    "app"
                   /  |  \
                 "le" "s" "l"
                /       \     \
             "apple"  "store" "lication"
             freq:9M  freq:5M  freq:3M
              /    \
         " stock" " music"
         freq:4M   freq:2M

Top-K computation (K=3, bottom-up):

(1) Leaf "apple stock" (freq 4M) -> topK = [("apple stock", 4M)]
(2) Leaf "apple music" (freq 2M) -> topK = [("apple music", 2M)]
(3) Node "apple" (freq 9M):
    Own: ("apple", 9M)
    Children: ("apple stock", 4M), ("apple music", 2M)
    Merged & sorted: [("apple", 9M), ("apple stock", 4M), ("apple music", 2M)]
    topK = top-3 = [("apple", 9M), ("apple stock", 4M), ("apple music", 2M)]

(4) Leaf "app store" (freq 5M) -> topK = [("app store", 5M)]
(5) Leaf "application" (freq 3M) -> topK = [("application", 3M)]

(6) Node "app" (no own frequency -- not a complete query by itself):
    Children's topK: 
      From "apple": [("apple", 9M), ("apple stock", 4M), ("apple music", 2M)]
      From "app store": [("app store", 5M)]
      From "application": [("application", 3M)]
    Merged & sorted: [("apple", 9M), ("app store", 5M), ("apple stock", 4M),
                       ("application", 3M), ("apple music", 2M)]
    topK = top-3 = [("apple", 9M), ("app store", 5M), ("apple stock", 4M)]

Now when user types "app", we instantly return top-3 from this node.
No DFS traversal needed at query time!
```

### 9.5 Cache Layer

The Cache Layer is a **multi-tier caching strategy** that absorbs 90%+ of all requests before they hit the Trie.

**Cache Architecture:**

```
+------------------------------------------------------------------+
|                     CACHE HIERARCHY                               |
|                                                                   |
|  TIER 0: CDN Edge Cache                                           |
|  +-------------------------------+                                |
|  | Location: 200+ edge POPs      |                                |
|  | Key: prefix (no userId)       |  <- Only global (non-personal) |
|  | TTL: 5-15 minutes             |     suggestions cached at CDN  |
|  | Hit ratio: ~30% of all reqs   |                                |
|  | Best for: "how to", "amazon"  |  <- Extremely popular prefixes |
|  +-------------------------------+                                |
|                                                                   |
|  TIER 1: In-Process Cache (L1)                                    |
|  +-------------------------------+                                |
|  | Location: JVM heap on each    |                                |
|  |   Autocomplete Service node   |                                |
|  | Implementation: Caffeine      |                                |
|  | Key: prefix + userId          |                                |
|  | TTL: 60 seconds               |                                |
|  | Max entries: 100K             |                                |
|  | Eviction: W-TinyLFU           |                                |
|  | Hit ratio: ~40% (of non-CDN)  |                                |
|  | Latency: < 0.1 ms             |                                |
|  +-------------------------------+                                |
|                                                                   |
|  TIER 2: Distributed Cache (Redis)                                |
|  +-------------------------------+                                |
|  | Location: Redis Cluster        |                                |
|  |   (6 nodes, 3 primary + 3 rep)|                                |
|  | Key: ac:{prefix}:{userId}     |                                |
|  | Value: JSON array of top-K    |                                |
|  | TTL: 5 minutes                 |                                |
|  | Max memory: 50 GB cluster      |                                |
|  | Hit ratio: ~50% (of non-L1)   |                                |
|  | Latency: 1-2 ms               |                                |
|  +-------------------------------+                                |
|                                                                   |
|  TIER 3: Trie Service (origin)                                    |
|  +-------------------------------+                                |
|  | In-memory Trie lookup          |                                |
|  | Only hit on complete cache miss|                                |
|  | ~10% of all requests           |                                |
|  | Latency: 1-5 ms               |                                |
|  +-------------------------------+                                |
+------------------------------------------------------------------+

Effective hit ratios (cumulative):
  CDN:              30% of requests served
  L1 (of remaining): 40% of 70% = 28%
  Redis (of remaining): 50% of 42% = 21%
  Trie (of remaining): 100% of 21% = 21%

  => Only 21% of total requests hit the Trie!
  => At 1.4M QPS peak: only ~294K QPS reach Trie
```

**Cache Invalidation Strategy:**

```
(1) TTL-based expiry (primary mechanism):
    - L1: 60 seconds -> naturally refreshes from Redis
    - Redis: 5 minutes -> naturally refreshes from Trie
    - CDN: 5-15 minutes -> stale is OK for global suggestions
    
    Why TTL is sufficient:
    - Suggestions don't need to be real-time accurate
    - A 5-minute-old suggestion list is perfectly acceptable
    - Simplicity >> precision for this use case

(2) Trie hot-swap triggers cache flush:
    - When Trie Builder deploys new Trie version:
      (a) Trie Service atomically swaps to new Trie
      (b) L1 cache is cleared (Caffeine.invalidateAll())
      (c) Redis keys are NOT flushed (they expire naturally)
    - This ensures new data surfaces within 60 seconds (L1 TTL)

(3) Blocklist update triggers targeted invalidation:
    - When a query is blocked:
      (a) All Redis keys containing that query are deleted (scan + delete)
      (b) L1 caches across all instances are notified via pub/sub
      (c) Blocked query will never appear again (filtered at Trie level too)

(4) Emergency cache purge (manual):
    - Admin API to flush all caches immediately
    - Used for: PR crisis, legal requirement, content policy emergency
```

**Cache Key Design:**

```
Global suggestions (no userId):
  CDN:   /suggestions?prefix=how+to&limit=10
  L1:    "how to|10|null"
  Redis: "ac:how to:10:global"

Personalized suggestions (with userId):
  CDN:   NOT cached (too many variants)
  L1:    "how to|10|user_abc123"
  Redis: "ac:how to:10:user_abc123"

Why separate global vs personalized?
  - Global: shared across all users -> extremely high cache hit ratio
  - Personalized: unique per user -> low hit ratio, shorter TTL
  - If personalization is disabled or userId missing, use global cache
  - Global cache hit ratio: ~95%
  - Personalized cache hit ratio: ~40%
```

---

## 10. Trie Deep Dive

### 10.1 Basic Trie: Insert, Search, Prefix Match

A Trie (prefix tree) is a tree-like data structure where each node represents a single character. Paths from root to nodes spell out prefixes, and paths to marked nodes spell out complete words.

**Structure:**

```
Inserting: "cat", "car", "card", "care", "do", "dog"

            root
           /    \
          c      d
          |      |
          a      o
         / \     |  \
        t   r    g   (end: "do")
       (end) |   (end)
            / \
           d   e
         (end) (end)
        "card" "care"

Each node:
  - children: Map<Character, TrieNode>
  - isEndOfWord: boolean
  - (optional) topSuggestions: List<String>
```

**Operations:**

```
INSERT("care"):
  (1) root -> 'c' (exists) -> 'a' (exists) -> 'r' (exists) -> 'e' (create new)
  (2) Mark 'e' node as isEndOfWord = true
  Time: O(L) where L = length of word

PREFIX SEARCH("car"):
  (1) root -> 'c' (exists) -> 'a' (exists) -> 'r' (exists)
  (2) Node found! Return all words in subtree rooted at 'r'
  (3) DFS from 'r': finds "car", "card", "care"
  Time: O(P + N) where P = prefix length, N = nodes in subtree

  Problem: subtree can be HUGE for short prefixes
  "a" has millions of words underneath it
  Solution: pre-compute top-K at each node (see Section 9.4)

EXACT SEARCH("card"):
  (1) root -> 'c' -> 'a' -> 'r' -> 'd'
  (2) Node found and isEndOfWord = true -> "card" exists
  Time: O(L)

DELETE("car"):
  (1) Traverse to 'r' node
  (2) Set isEndOfWord = false
  (3) If 'r' has children ("card", "care"), do NOT remove the node
  (4) If 'r' had no children, prune upward (remove 'r', check 'a', etc.)
  Time: O(L)
```

**Basic Trie Node in Java 21:**

```java
class TrieNode {
    private final Map<Character, TrieNode> children = new HashMap<>();
    private boolean endOfWord;
    private long frequency;
    private List<Suggestion> topSuggestions;  // pre-computed top-K

    // O(L) insert
    void insert(TrieNode root, String word, long freq) {
        TrieNode current = root;
        for (char c : word.toCharArray()) {
            current = current.children.computeIfAbsent(c, k -> new TrieNode());
        }
        current.endOfWord = true;
        current.frequency = freq;
    }

    // O(P) prefix lookup -> returns top-K at that prefix node
    List<Suggestion> prefixSearch(TrieNode root, String prefix) {
        TrieNode current = root;
        for (char c : prefix.toCharArray()) {
            current = current.children.get(c);
            if (current == null) return Collections.emptyList();  // no match
        }
        return current.topSuggestions;  // O(1) -- pre-computed!
    }
}
```

### 10.2 Compressed Trie (Radix Tree)

A Radix Tree (Patricia Trie) compresses chains of single-child nodes into a single node with a multi-character label. This dramatically reduces node count and memory usage.

**Compression:**

```
Basic Trie for "how to learn java", "how to learn python", "how to learn guitar":

h-o-w- -t-o- -l-e-a-r-n- -j-a-v-a       (14 nodes just for shared prefix!)
                           \-p-y-t-h-o-n
                           \-g-u-i-t-a-r

Compressed Trie (Radix Tree):

"how to learn " ─── "java"    (2 nodes for shared prefix!)
                 ├── "python"
                 └── "guitar"

Node count: 14 single-char nodes -> 4 multi-char nodes (71% reduction)
```

**Detailed Compression Process:**

```
Step (1): Build standard Trie with all queries

Step (2): Identify single-child chains
    A chain exists when a node has exactly 1 child AND is not end-of-word
    
    Example chain: 'h' -> 'o' -> 'w' -> ' ' -> 't' -> 'o'
    Each node has exactly 1 child, none are end-of-word
    Compress to: "how to" (single node)

Step (3): Merge chains
    Create new node with label = concatenated characters
    Children of merged node = children of the last node in the chain
    
    Before:  [h] -> [o] -> [w] -> [ ] -> [t] -> [o] -> [branching point]
    After:   ["how to"] -> [branching point]

Step (4): Update top-K propagation
    Compressed nodes inherit top-K from their original terminal node
    (the last node in the chain before branching)

Memory savings:
  Basic Trie: 200M queries * avg 20 chars = ~4 billion nodes (worst case)
  Radix Tree: ~500M-800M nodes (60-80% reduction)
  With top-K metadata: 5 GB (Radix) vs 15 GB (Basic Trie)
```

**Radix Tree Node in Java 21:**

```java
class RadixNode {
    private String label;               // multi-character edge label (e.g., "how to learn ")
    private Map<Character, RadixNode> children;  // key = first char of child label
    private boolean endOfWord;
    private long frequency;
    private List<Suggestion> topSuggestions;

    // Prefix search in Radix Tree
    List<Suggestion> search(RadixNode root, String prefix) {
        RadixNode current = root;
        int i = 0;
        while (i < prefix.length()) {
            char c = prefix.charAt(i);
            RadixNode child = current.children.get(c);
            if (child == null) return Collections.emptyList();

            String label = child.label;
            int j = 0;
            // Match prefix against edge label character by character
            while (j < label.length() && i < prefix.length() 
                   && label.charAt(j) == prefix.charAt(i)) {
                i++;
                j++;
            }
            if (i == prefix.length()) {
                // Prefix exhausted mid-label or at label end -- node found
                return child.topSuggestions;
            }
            if (j < label.length()) {
                // Label has remaining chars but prefix diverges -- no match
                return Collections.emptyList();
            }
            current = child;  // Label fully matched, continue with next prefix char
        }
        return current.topSuggestions;
    }
}
```

### 10.3 Ternary Search Tree (TST)

A Ternary Search Tree is a memory-efficient alternative to a Trie. Each node has exactly three children: left (less than), middle (equal), and right (greater than).

**Structure:**

```
Inserting: "cat", "car", "cup", "do"

TST (using middle child for character match):

            c
          / | \
        (L) a (R) d
            |      |
            t/r    o
           / | \  (end: "do")
          r  (end: "cat")
         (end: "car")
         
Wait -- let me draw this more precisely:

         'c' (root)
        / | \
       /  |  \
     <   =    >
    null  'a'  'd'
          |     |
         = 'a' = 'o'
        / | \   (end: "do")
       /  |  \
      <   =    >
    null  't'  'u'
         (end) = 'p'
        "cat"  (end: "cup")
        
   For "car": from 'a' node, we go = to 't', then < to 'r'
   
   Actually TST is typically:
   
   Node for 'c':
     left: null (nothing < 'c' at root level)
     eq:   Node for 'a'  (second char of words starting with 'c')
     right: Node for 'd' (words starting with 'd')
   
   Node for 'a' (second position):
     left: null
     eq:   Node for 't'  (third char: "cat", "car")
     right: Node for 'u' (second char: "cup")
   
   Node for 't' (third position):
     left: Node for 'r'  (third char < 't': "car")
     eq:   null (end of "cat")  isEnd=true
     right: null
```

**Key Properties:**

```
- Each node stores ONE character and has 3 pointers (left, eq, right)
- Left/right pointers act like a BST for characters at the same position
- Eq pointer advances to the next character in the string
- Memory per node: ~40 bytes (char + 3 pointers + metadata)
- No wasted space for unused characters (unlike basic Trie with 26/128/256 array)

Memory comparison for 200M queries:
  Basic Trie (array children[26]):  ~4B nodes * 26 * 8 bytes = ~832 GB (absurd!)
  Basic Trie (HashMap children):    ~4B nodes * ~100 bytes = ~400 GB
  Radix Tree (HashMap children):    ~800M nodes * ~120 bytes = ~96 GB
  TST:                              ~4B nodes * ~40 bytes = ~160 GB

Note: Radix Tree is usually the winner for autocomplete because:
  - Compression reduces node count dramatically
  - Each node can store a multi-char label
  - At ~5-10 GB with top-K, it fits comfortably in memory
```

### 10.4 Top-K at Each Node

Storing pre-computed top-K suggestions at every Trie node is the **key optimization** that makes autocomplete sub-millisecond.

**Without Top-K (Naive Approach):**

```
User types "h"

(1) Traverse Trie to node 'h'
(2) DFS entire subtree under 'h'
(3) Subtree contains: "how to...", "hello", "home...", "hotel...", ... (millions of queries)
(4) Collect all queries with their frequencies
(5) Sort by frequency
(6) Return top-10

Time: O(subtree_size) = O(millions) -> SECONDS for short prefixes!
Completely unacceptable for real-time autocomplete.
```

**With Top-K (Pre-Computed):**

```
User types "h"

(1) Traverse Trie to node 'h'  -> O(1)
(2) Read node.topSuggestions    -> O(1)
(3) Return immediately

Time: O(prefix_length) = O(1) for reading the list
The top-K was computed during Trie build time, stored at the node.
```

**How Top-K is Computed (Build Time):**

```
Post-order traversal (bottom-up):

(1) Visit all leaf nodes first:
    Leaf "java" (freq 5M): topK = [("java", 5M)]
    Leaf "javascript" (freq 3M): topK = [("javascript", 3M)]

(2) Visit parent "jav":
    Own: not end-of-word, no own entry
    Children's topK: [("java", 5M)], [("javascript", 3M)]
    Merge: [("java", 5M), ("javascript", 3M)]
    If K=10 and we have < 10, keep all.

(3) Visit parent "ja":
    Own: not end-of-word
    Children's topK (from "jav"): [("java", 5M), ("javascript", 3M)]
    Other children (e.g., "jan"): [("january", 500K), ...]
    Merge all, sort by frequency, keep top-K

(4) Continue up to root.
    Root's topK = the top-K most popular queries globally.

Build time for this step: O(N * K * log K) where N = total nodes
  - Each node merges its children's lists (at most 26 children * K entries)
  - Merge and select top-K: O(26K * log K) per node with a min-heap
  - Total: O(N * 26K * log K) -- dominated by the Trie build itself
```

**Space Overhead:**

```
Each node stores: List<Suggestion> of size K=10
Each Suggestion: query (String reference, ~8 bytes) + score (8 bytes) = ~16 bytes
Per node: 10 * 16 = 160 bytes

Total nodes in compressed Trie: ~500M
Top-K overhead: 500M * 160 bytes = ~80 GB

Wait, that's too much! Optimization:

(1) Only store top-K at nodes with branching (2+ children)
    Single-child chains (compressed in Radix Tree) inherit parent's top-K
    Branching nodes: ~50M (10% of total) -> 50M * 160 = 8 GB

(2) Store String references, not copies
    All query strings stored in a single String pool
    Nodes reference the same String objects -> massive deduplication
    
(3) Pruning: don't store top-K at nodes deeper than prefix length 15
    Very few users type more than 15 characters before selecting
    Savings: ~30% of branching nodes

Final top-K overhead: ~2-3 GB on top of base Trie size
Total in-memory: ~5-8 GB for compressed Trie + top-K
```

### 10.5 Comparison Table: Trie Variants

| Feature                 | Basic Trie          | Radix Tree          | TST                 | HashMap             |
|-------------------------|---------------------|---------------------|---------------------|---------------------|
| **Lookup Time**         | O(L)                | O(L)                | O(L * log A)        | O(1) amortized      |
| **Prefix Search**       | O(P + subtree)      | O(P + subtree)      | O(P * log A)        | Not supported natively |
| **Insert Time**         | O(L)                | O(L)                | O(L * log A)        | O(1) amortized      |
| **Memory/Node**         | High (26+ ptrs)     | Medium (label + map)| Low (3 ptrs)        | N/A                 |
| **Total Memory (200M)** | ~400 GB             | ~5-10 GB            | ~160 GB             | ~15 GB              |
| **Compression**         | None                | Single-child chains | None                | N/A                 |
| **Top-K Support**       | Yes (per node)      | Yes (per node)      | Yes (per node)      | Pre-compute per prefix |
| **Update Complexity**   | Easy                | Moderate (split/merge labels) | Easy       | Easy                |
| **Sorted Iteration**    | Yes (alphabetical)  | Yes (alphabetical)  | Yes (alphabetical)  | No                  |
| **Best For**            | Small datasets      | **Autocomplete**    | Memory-constrained  | Exact match only    |

```
L = length of query string
P = length of prefix
A = alphabet size (26 for lowercase English)

Verdict for Search Autocomplete:
  Radix Tree (Compressed Trie) is the clear winner because:
  (1) Prefix search is the primary operation -> Trie family excels
  (2) Compression reduces 4B nodes to ~500M -> fits in memory
  (3) Top-K pre-computation works naturally with Trie structure
  (4) Sorted iteration useful for range-based sharding

HashMap alternative:
  - Pre-compute suggestions for every possible prefix (up to length 10)
  - Store as HashMap<String, List<Suggestion>>
  - Pros: O(1) lookup, simple implementation
  - Cons: Combinatorial explosion of keys
    26^1 + 26^2 + ... + 26^10 = ~141 trillion keys (impossible!)
    With real data: ~500M distinct prefixes -> 500M * 200 bytes = 100 GB
    Feasible but 10x more memory than Radix Tree with top-K nodes
```

---

## 11. Ranking Algorithms

### 11.1 Frequency-Based Ranking (Simple Count)

The simplest ranking: suggestions sorted by total historical search frequency.

```
Algorithm:
  score(query) = frequency(query)

Example for prefix "java":
  "java"            -> 50,000,000 searches all-time -> score: 50M
  "javascript"      -> 35,000,000 -> score: 35M
  "java tutorial"   -> 12,000,000 -> score: 12M
  "java 21"         ->  2,000,000 -> score: 2M

Result: [java, javascript, java tutorial, java 21]

Pros:
  - Extremely simple, no tuning parameters
  - Stable rankings (don't fluctuate)
  - Good for head queries (most popular = most useful)

Cons:
  - Historical bias: old popular queries dominate forever
  - "java 8 tutorial" may outrank "java 21 tutorial" despite Java 21 being current
  - No personalization, no trending, no context
  - New queries can never compete with established ones
```

### 11.2 Time-Weighted Frequency (Exponential Decay)

Recent searches count more than old searches. Uses exponential decay to de-weight historical frequency.

```
Algorithm:
  For each search event at time t:
    contribution(t) = e^(-lambda * (now - t))
  
  score(query) = SUM over all searches of contribution(t)
  
  Where lambda controls the decay rate:
    lambda = 0.01/hour -> half-life = ln(2)/0.01 = ~69 hours (~3 days)
    lambda = 0.001/hour -> half-life = ~693 hours (~29 days)

Practical implementation (avoid summing over billions of events):

  Exponential Moving Average (EMA):
    score_new = alpha * 1 + (1 - alpha) * score_old
    alpha = 1 - e^(-lambda * time_delta)
  
  Or bucket-based approximation:
    score = freq_today * 1.0
          + freq_yesterday * 0.9
          + freq_2_days_ago * 0.81
          + freq_3_days_ago * 0.729
          + ...
          + freq_7_days_ago * 0.478
          + freq_30_days_ago * 0.042

Example for prefix "java":
  "java 21" -> recent: 500K/day, all-time: 2M
    Score = 500K*1.0 + 480K*0.9 + ... = high (trending upward)
  
  "java 8 tutorial" -> recent: 50K/day, all-time: 12M
    Score = 50K*1.0 + 52K*0.9 + ... = lower (declining)
  
  Result: "java 21" now ranks ABOVE "java 8 tutorial" despite lower all-time count

Pros:
  - Naturally surfaces fresh, relevant queries
  - Old queries fade gracefully (no cliff)
  - Simple to implement with EMA

Cons:
  - Needs tuning of lambda/alpha
  - May be too aggressive for evergreen queries ("how to tie a tie")
  - Slightly more complex aggregation pipeline
```

**Exponential Decay Visualization:**

```
Score contribution of a single search event over time:

1.0 |*
    | *
    |  *
    |   **
    |     **
0.5 |       ***
    |          ****
    |              *****
    |                   ********
    |                           *************
0.0 +------+------+------+------+------+-------> hours
    0      24     48     72     96    120    144
    (now)  (1d)   (2d)   (3d)  (4d)  (5d)   (6d)

With lambda = 0.01/hour:
  After 1 day:  e^(-0.01*24) = 0.79 (79% weight)
  After 3 days: e^(-0.01*72) = 0.49 (49% weight -- roughly half-life)
  After 7 days: e^(-0.01*168) = 0.19 (19% weight)
  After 30 days: e^(-0.01*720) = 0.0007 (essentially zero)
```

### 11.3 Personalized Ranking

Boost suggestions that the individual user has searched before.

```
Algorithm:
  user_score(query, userId) = user_search_count(query, userId) / max_user_searches
  
  final_score = w_global * global_score + w_personal * user_score
  
  Where:
    w_global = 0.70 (global popularity still dominates)
    w_personal = 0.30 (personal history provides a boost)

Implementation:
  (1) On each search event: update Redis hash
      HINCRBY user_history:{userId} {query} 1
  
  (2) On suggestion request with userId:
      HGETALL user_history:{userId}  -> get all user queries
      For each candidate suggestion:
        user_count = user_history.get(suggestion.query, 0)
        personal_boost = min(user_count / 10.0, 1.0)  // cap at 1.0
        final_score = 0.70 * global_score + 0.30 * personal_boost

Example:
  User "user_abc123" has searched "java streams" 15 times
  
  Prefix: "ja"
  Global ranking: [japan, java, january, jazz, japanese]
  
  Personalization:
    "java":    global=0.90, personal=1.0 -> final = 0.63 + 0.30 = 0.93
    "japan":   global=0.95, personal=0.0 -> final = 0.665 + 0.0 = 0.665
    "java streams": global=0.40, personal=1.0 -> final = 0.28 + 0.30 = 0.58
  
  Personalized ranking: [java, japan, java streams, january, jazz]
  (User's preferred "java" jumped from #2 to #1, "java streams" entered top-5)

Edge Cases:
  - New user (no history): personal_boost = 0 for all -> pure global ranking
  - User with many searches: capped at 1.0 -> doesn't completely override global
  - Privacy: user can delete history (GDPR) -> fall back to global
  - Cold start: blend with collaborative filtering (users who searched X also searched Y)
```

### 11.4 Trending Boost

Detect and boost queries experiencing unusual frequency spikes.

```
Algorithm:
  trending_score(query) = (freq_recent / avg_freq_baseline) - 1.0
  
  Where:
    freq_recent = search count in the last 1-4 hours
    avg_freq_baseline = average search count for same time window over last 30 days
  
  Clamped to [0, 1.0]:
    trending_score = min(max(trending_raw, 0), 1.0)
  
  Interpretation:
    trending_score = 0.0 -> normal frequency, no boost
    trending_score = 0.5 -> 50% above baseline
    trending_score = 1.0 -> 100%+ above baseline (capped)

Example: Breaking news -- "earthquake california" at 2 PM on April 26
  Normal frequency for 2PM slot: 500 searches/hour
  Current frequency: 50,000 searches/hour
  trending_score = (50000 / 500) - 1.0 = 99.0 -> clamped to 1.0

  Without trending boost:
    prefix "earth": [earth, earthquake, earthworm, earth day, ...] (ranked by all-time freq)
    "earthquake california" might be #8 (low all-time frequency)
  
  With trending boost (w_trending = 0.20):
    "earthquake california": 
      global=0.30, recency=0.95, trending=1.0, personal=0.0
      final = 0.50*0.30 + 0.15*0.95 + 0.20*1.0 + 0.15*0.0 = 0.49
    "earth" (evergreen):
      global=0.90, recency=0.60, trending=0.0, personal=0.0
      final = 0.50*0.90 + 0.15*0.60 + 0.20*0.0 + 0.15*0.0 = 0.54
    
    "earth" still #1 (very high base frequency), but "earthquake california"
    jumped from #8 to #2 -- surfacing trending news to users!

Detection Implementation:
  (1) Kafka Streams / Flink job consumes search events
  (2) Maintains sliding window counts per query (tumbling window: 1 hour)
  (3) Compares current window count against 30-day rolling average
  (4) Writes trending_score to Redis: SET trending:{query} {score} EX 3600
  (5) Ranking Service reads trending score from Redis during re-ranking
```

**Trending Detection Pipeline:**

```
Search Events (Kafka)
       |
       v
+---------------------------+
| Flink / Kafka Streams     |
|                           |
| (1) Tumbling window: 1 hr|
|     COUNT by query        |
|                           |
| (2) Join with baseline    |
|     table (avg from HDFS) |
|                           |
| (3) Compute ratio:        |
|     current / baseline    |
|                           |
| (4) Filter: ratio > 2.0  |
|     (only significant     |
|      spikes)              |
|                           |
| (5) Write to Redis:       |
|     trending:{query} =    |
|     score (TTL 1 hour)    |
+---------------------------+
       |
       v
  Redis (trending scores)
       ^
       |
  Ranking Service reads during re-rank
```

### 11.5 BM25/TF-IDF for Relevance (Conceptual)

For autocomplete, BM25/TF-IDF is **less relevant** than for full-text search, but the concept is worth mentioning in interviews.

```
BM25 in context of autocomplete:
  - TF (Term Frequency): how often the query appears in the search logs
    -> This is essentially our "frequency" score
  
  - IDF (Inverse Document Frequency): how rare/unique the query is
    -> In autocomplete: rare queries are LESS desirable (we want popular ones)
    -> IDF is inverted for our use case: common queries rank HIGHER
  
  - BM25 formula: score = TF * IDF * (k1 + 1) / (TF + k1 * (1 - b + b * dl/avgdl))
    -> Overly complex for prefix matching
    -> Better suited for document retrieval, not autocomplete ranking

When BM25 IS relevant:
  - If autocomplete includes "federated search" (searching across multiple indices)
  - If suggestions come from document titles (not just search queries)
  - If building an auto-suggest for a search engine that needs to rank by content relevance

For interview purposes:
  "We primarily use frequency-based ranking with recency decay for autocomplete.
   BM25/TF-IDF would be more relevant if we were ranking search results, not search 
   suggestions. However, if we federate suggestions from multiple sources (queries, 
   product names, article titles), TF-IDF could help normalize scores across sources."
```

### Ranking Algorithm Comparison

| Algorithm              | Complexity | Freshness | Personalized | Implementation    | Best For              |
|------------------------|-----------|-----------|-------------|-------------------|-----------------------|
| Frequency Count        | Simple    | No        | No          | Counter           | MVP, prototype        |
| Time-Weighted (EMA)    | Medium    | Yes       | No          | EMA per query     | General autocomplete  |
| Personalized           | Medium    | Partial   | Yes         | User history hash | Logged-in users       |
| Trending Boost         | Medium    | Excellent | No          | Streaming job     | News, events          |
| Composite (all above)  | Complex   | Yes       | Yes         | Weighted sum      | **Production system** |
| BM25/TF-IDF            | Complex   | No        | No          | Full scorer       | Document search       |

---

## 12. Data Collection & Aggregation

### 12.1 Real-Time: Search Query Logging to Kafka

Every search query submitted by a user is published to Kafka as an event.

```
Flow:

(1) User submits search: "how to learn java"
(2) Search Service handles the query and returns results
(3) Search Service ALSO publishes event to Kafka (async, non-blocking):

    Kafka Topic: search-events
    Key: hash("how to learn java") % 64  (64 partitions)
    Value (JSON):
    {
      "event_id": "evt_abc123",
      "query": "how to learn java",
      "user_id": "user_abc123",
      "timestamp": 1745675400000,
      "session_id": "sess_xyz789",
      "country": "US",
      "device_type": "MOBILE",
      "selected_position": 2,
      "was_suggestion": true
    }

(4) Kafka acknowledges (acks=all, replication factor 3)
(5) Event is durable and available for consumption

Why Kafka?
  - Handles 60K+ events/sec sustained (5B/day)
  - Decouples producers (Search Service) from consumers (aggregation, trending)
  - Replay capability: can reprocess from any offset if consumer fails
  - 30-day retention: raw data always available for re-aggregation
  
Kafka Cluster Sizing:
  - 64 partitions (matches parallelism for Spark consumers)
  - 3 brokers minimum (replication factor 3)
  - Each broker: 1 TB disk (30 days * 250 GB/day / 3 brokers = 2.5 TB -> 3 TB each)
  - Throughput per broker: ~60K events/sec / 3 = 20K events/sec (well within limits)
```

### 12.2 Batch: MapReduce/Spark Aggregation

Hourly and daily Spark jobs aggregate raw search events into the query frequency table.

```
Hourly Aggregation Job:

Input:  Raw search events from Kafka (via S3/HDFS sink connector)
        Files: s3://search-logs/2026/04/26/14/*.parquet

(1) Read all events from the last hour:
    
    spark.read.parquet("s3://search-logs/2026/04/26/14/")
    
(2) Group by normalized query, compute counts:
    
    SELECT 
      LOWER(TRIM(query)) as query,
      COUNT(*) as hourly_count,
      MAX(timestamp) as last_seen,
      COUNT(DISTINCT user_id) as unique_users
    FROM search_events
    WHERE timestamp >= hour_start AND timestamp < hour_end
    GROUP BY LOWER(TRIM(query))
    
(3) Merge with existing frequency table:
    
    -- Read current frequencies
    current = spark.read.parquet("s3://query-frequencies/latest/")
    
    -- Join and update
    updated = current.join(hourly, "query", "full_outer")
      .withColumn("frequency", coalesce(current.frequency, 0) + coalesce(hourly.hourly_count, 0))
      .withColumn("recent_frequency", compute_rolling_7_day(hourly.hourly_count))
      .withColumn("last_searched_at", greatest(current.last_searched_at, hourly.last_seen))
    
    -- Write updated table
    updated.write.parquet("s3://query-frequencies/2026-04-26-15/")
    
(4) Output: updated query_frequencies table

Duration: 5-15 minutes (200M rows, reading ~30 GB, writing ~10 GB)

Daily Aggregation Job (more comprehensive):

(1) Reads full 24 hours of events
(2) Computes:
    - All-time frequency (cumulative)
    - 7-day rolling frequency
    - 30-day rolling frequency  
    - Trending score: (7_day_avg / 30_day_avg) ratio
(3) Prunes queries with frequency < 10 (noise, typos)
(4) Outputs clean frequency table for Trie Builder
```

**Aggregation Pipeline Diagram:**

```
+-------------------+      +-------------------+      +-------------------+
|  Kafka            |      |   S3 / HDFS       |      |  Query Freq       |
|  (search-events)  | ---> |  (raw logs by     | ---> |  Table            |
|                   | sink |   hour/day)        | agg  |  (200M rows)      |
+-------------------+      +-------------------+      +-------------------+
                                                               |
                                                               | (input to)
                                                               v
                                                       +-------------------+
                                                       |  Trie Builder     |
                                                       |  (builds new Trie)|
                                                       +-------------------+
                                                               |
                                                               | (hot-swap)
                                                               v
                                                       +-------------------+
                                                       |  Trie Service     |
                                                       |  (serves queries) |
                                                       +-------------------+

Timeline:
  T+0:00  User searches "earthquake california"
  T+0:00  Event published to Kafka
  T+0:05  Kafka sink writes to S3 (micro-batch, 5 min delay)
  T+1:00  Hourly Spark job processes the event
  T+1:15  Updated frequency table written to S3
  T+1:30  Trie Builder reads new frequencies, starts building
  T+2:00  New Trie deployed via hot-swap
  
  Total: ~2 hours from search to suggestion (batch path)
  
  For trending queries (real-time path via Flink):
  T+0:00  User searches "earthquake california"
  T+0:00  Event published to Kafka
  T+0:01  Flink detects spike (1-minute tumbling window)
  T+0:01  Trending score written to Redis
  T+0:02  Next autocomplete request reads trending score, boosts ranking
  
  Total: ~2 minutes from search to trending suggestion (real-time path)
```

### 12.3 Trie Rebuild: Serialize and Hot-Swap

```
Trie Rebuild Process (triggered after aggregation job completes):

(1) Read query_frequencies table (filtered: freq > 10, is_blocked = false)
    ~50M queries after filtering
    
(2) Sort queries alphabetically (enables efficient radix tree construction)

(3) Build compressed Trie (Radix Tree):
    for each (query, frequency) in sorted order:
        insert(root, query, frequency)
    compress(root)  // merge single-child chains
    
    Duration: ~5 minutes for 50M queries

(4) Compute top-K at every node (bottom-up post-order traversal):
    computeTopK(root, K=15)  // compute top-15 to have headroom for filtering
    
    Duration: ~5 minutes

(5) Serialize Trie to binary format:
    Custom binary serialization (NOT Java Serializable):
    
    Format:
    [4 bytes: magic number] [4 bytes: version]
    [4 bytes: node count] [4 bytes: query count]
    [String pool: all unique query strings]
    [Node data: label, children offsets, isEnd, frequency, topK indices]
    
    Output size: ~5 GB compressed
    Duration: ~3 minutes

(6) Upload to S3:
    s3://trie-builds/2026-04-26-15-00/trie.bin
    Duration: ~2 minutes (at 500 MB/sec upload speed)

(7) Notify Trie Service instances (via SQS/SNS or config service):
    "New Trie version available: 2026-04-26-15-00"

(8) Each Trie Service instance:
    (a) Downloads trie.bin from S3 in background thread
    (b) Deserializes into JVM heap (new TrieNode graph)
    (c) Atomic swap: currentRoot = newRoot (volatile write)
    (d) Old Trie becomes GC-eligible
    
    Duration per instance: ~5 minutes (download + deserialize)
    Rolling deployment: stagger across instances (not all swap at once)

(9) Verify: health check confirms new Trie version is active
    If verification fails: rollback to previous version (S3 retains old builds)

Total build pipeline: ~20-30 minutes
Frequency: every 1-4 hours (configurable)
```

---

## 13. Concurrency

Autocomplete is an **extremely read-heavy** workload. Writes (Trie updates) are infrequent and batched.

### Read/Write Ratio

```
Reads:  1.4M QPS (every keystroke generates a suggestion request)
Writes: 1 Trie swap every 1-4 hours (not per-request writes)

Ratio: effectively infinite read-to-write ratio
       The Trie is immutable between swaps.

This is the ideal scenario for lock-free, copy-on-write concurrency.
```

### Copy-on-Write for Trie Updates

```
The Trie is treated as an IMMUTABLE data structure during its lifetime:

(1) Trie V1 is built offline, deployed to Trie Service
(2) All read threads share the same Trie V1 reference (via volatile field)
(3) No locks needed for reads -- Trie nodes are never modified in-place
(4) When Trie V2 is ready:
    (a) V2 is fully constructed in a background thread
    (b) Atomic reference swap: currentRoot = v2Root (single volatile write)
    (c) In-flight reads on V1 continue safely (they hold a local reference)
    (d) V1 becomes GC-eligible once all in-flight reads complete

Java 21 implementation:

    private volatile TrieNode currentRoot;  // volatile ensures visibility

    // Read path: called by thousands of threads concurrently
    public List<Suggestion> getSuggestions(String prefix) {
        TrieNode root = this.currentRoot;  // single volatile read (cheap)
        // All subsequent traversal uses local 'root' reference
        // Safe even if another thread swaps currentRoot mid-traversal
        return traverse(root, prefix);
    }

    // Write path: called by ONE background thread every few hours
    public void deployNewTrie(TrieNode newRoot) {
        // newRoot is fully constructed -- all nodes are initialized
        this.currentRoot = newRoot;  // single volatile write
        // Happens-before guarantee: all writes to newRoot's nodes
        // are visible to any thread that reads currentRoot after this write
    }

Why no locks?
  - Trie is immutable after construction (no in-place mutation)
  - Volatile write provides happens-before guarantee
  - Java Memory Model ensures visibility of the entire Trie graph
  - In-flight reads use the old Trie until they complete (no torn reads)
  - GC handles cleanup of old Trie (no manual memory management)

Alternative: AtomicReference<TrieNode>
  AtomicReference<TrieNode> currentRoot = new AtomicReference<>(initialRoot);
  
  Read:  currentRoot.get()  (equivalent to volatile read)
  Write: currentRoot.set(newRoot)  (equivalent to volatile write)
  
  Functionally identical to volatile for this use case.
  AtomicReference adds CAS operations we don't need (no concurrent writers).
```

### Cache Concurrency

```
L1 Cache (Caffeine):
  - Thread-safe by design (Caffeine uses ConcurrentHashMap internally)
  - Lock-free reads via optimistic concurrency
  - Bounded by maxSize with W-TinyLFU eviction (near-optimal hit ratio)
  - No external synchronization needed

Redis Cache:
  - Redis is single-threaded (commands are atomic by nature)
  - Multiple Autocomplete Service instances can read/write concurrently
  - No race conditions: GET is atomic, SET with TTL is atomic
  - Potential issue: thundering herd on cache miss for hot prefix
  
  Thundering Herd Mitigation:
  
  (1) Problem: 1000 requests for prefix "ama" arrive simultaneously
      All check L1: MISS. All check Redis: MISS.
      All 1000 requests hit the Trie service simultaneously.
      
  (2) Solution: Request coalescing (singleflight pattern)
  
      ConcurrentHashMap<String, CompletableFuture<List<Suggestion>>> inflightRequests;
      
      CompletableFuture<List<Suggestion>> getSuggestions(String prefix) {
          return inflightRequests.computeIfAbsent(prefix, key -> {
              // Only ONE request actually hits the Trie
              return CompletableFuture.supplyAsync(() -> {
                  List<Suggestion> result = trieService.lookup(key);
                  cacheService.put(key, result);
                  return result;
              }).whenComplete((result, error) -> {
                  inflightRequests.remove(prefix);  // cleanup after completion
              });
          });
          // All other concurrent requests for same prefix
          // get the same CompletableFuture and wait for it
      }
      
  (3) Result: 1000 concurrent requests for "ama" -> only 1 Trie lookup
      999 requests wait on the CompletableFuture (cheap, no extra work)
```

### Thread Model (Java 21 Virtual Threads)

```
Java 21 virtual threads are ideal for autocomplete:

  // Autocomplete Service HTTP handler
  try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      httpServer.setExecutor(executor);  // each request gets a virtual thread
  }
  
  Why virtual threads?
  (1) Autocomplete requests are lightweight (in-memory lookup, no I/O blocking)
  (2) Millions of concurrent requests need millions of threads
      Platform threads: limited to ~10K (stack memory)
      Virtual threads: millions (cheap, no kernel thread per request)
  (3) Redis calls (on cache miss) involve network I/O
      Virtual thread unmounts from carrier thread during I/O wait
      No thread blocking, no wasted resources
  (4) Perfect for the "many short-lived concurrent requests" pattern

Thread pool sizing:
  Carrier threads: = number of CPU cores (e.g., 16)
  Virtual threads: unbounded (millions, limited only by heap)
  Each virtual thread: ~1 KB stack (vs 1 MB for platform thread)
```

---

## 14. Scaling

### 14.1 Sharding by Prefix Range

When the Trie is too large for a single machine or QPS exceeds single-node capacity, shard by prefix range.

```
Sharding Strategy:

  Shard 1: prefixes starting with a-f
  Shard 2: prefixes starting with g-m
  Shard 3: prefixes starting with n-s
  Shard 4: prefixes starting with t-z
  Shard 5: prefixes starting with 0-9, special characters

  Each shard holds a COMPLETE Trie for its prefix range.
  Each shard has 2-3 replicas for fault tolerance.

Request Routing:

  (1) User types "how to learn java"
  (2) API Gateway extracts first character: 'h'
  (3) Routing table: 'h' -> Shard 2 (g-m)
  (4) Request forwarded to one of Shard 2's replicas (round-robin)
  (5) Shard 2 performs Trie lookup for "how to learn java"
  (6) Returns top-K suggestions

  Routing table (maintained at API Gateway / Load Balancer):
  +--------+----------+-----------+
  | Prefix | Shard    | Replicas  |
  +--------+----------+-----------+
  | a-f    | Shard 1  | 3 nodes   |
  | g-m    | Shard 2  | 3 nodes   |
  | n-s    | Shard 3  | 3 nodes   |
  | t-z    | Shard 4  | 3 nodes   |
  | 0-9,#  | Shard 5  | 2 nodes   |
  +--------+----------+-----------+

Why prefix-range sharding?
  (1) Queries naturally distribute across prefixes (a-z)
  (2) No cross-shard queries needed (prefix determines the shard)
  (3) Each shard is independent -- can scale/restart independently
  (4) Easy to re-shard: split "a-f" into "a-c" + "d-f" if it grows too hot
```

**Handling Hot Prefixes:**

```
Problem: Some prefixes are much more popular than others.
  "s" accounts for 12% of English queries (starting with 's')
  "x" accounts for 0.3% (starting with 'x')
  
  Shard 3 (n-s) has ~4x the load of Shard 5 (0-9)

Solutions:

(1) Unequal range partitioning:
    Instead of alphabetical ranges, partition by QPS:
    
    Shard 1: a-c         (~18% of queries)
    Shard 2: d-h         (~20% of queries)
    Shard 3: i-n         (~18% of queries)
    Shard 4: o-s         (~22% of queries)  <- 's' is heavy
    Shard 5: t-z, 0-9    (~22% of queries)  <- 't','w' are heavy
    
    More balanced, but still imperfect.

(2) Per-prefix replication factor:
    Hot shards get more replicas:
    
    Shard 4 (o-s): 5 replicas (high load from 's')
    Shard 5 (0-9): 2 replicas (low load)
    
    Load balancer distributes across replicas.

(3) Consistent hashing with virtual nodes:
    Map prefix ranges to virtual nodes on a hash ring.
    Hot ranges get more virtual nodes -> more physical servers.
    
    This is more complex but allows dynamic rebalancing.
```

### 14.2 Horizontal Scaling Architecture

```
                    +-------------------+
                    |   Load Balancer   |
                    +--------+----------+
                             |
              +--------------+--------------+
              |              |              |
    +---------+--+  +--------+---+  +------+-----+
    |API GW #1   |  |API GW #2   |  |API GW #3   |
    +-----+------+  +-----+------+  +-----+------+
          |              |              |
          +--------------+--------------+
          |         Routing by prefix first char
          |
    +-----+-----+-----+-----+-----+
    |     |     |     |     |     |
    v     v     v     v     v     v
  +---+ +---+ +---+ +---+ +---+ +---+
  |S1 | |S1 | |S2 | |S2 | |S3 | |S3 |    S = Shard
  |R1 | |R2 | |R1 | |R2 | |R1 | |R2 |    R = Replica
  +---+ +---+ +---+ +---+ +---+ +---+
  (a-f)        (g-m)        (n-s)
  
  +---+ +---+ +---+ +---+
  |S4 | |S4 | |S5 | |S5 |
  |R1 | |R2 | |R1 | |R2 |
  +---+ +---+ +---+ +---+
  (t-z)        (0-9)

Total nodes: 5 shards * 2-3 replicas = 10-15 Trie servers
Each server: 16 GB heap, 4-8 cores

Capacity per shard (single replica):
  - 50K Trie lookups/sec per node
  - After cache absorption (90%): actual Trie QPS = 140K
  - 140K / 5 shards = 28K per shard -> 1 node per shard is enough
  - Replicas are for fault tolerance, not throughput (at this scale)

Scaling triggers:
  (1) QPS per shard > 40K sustained -> add replica
  (2) Trie size > 12 GB per shard -> split shard into two ranges
  (3) p99 latency > 5 ms -> investigate (likely GC, not capacity)
```

### 14.3 Scaling the Data Pipeline

```
Kafka scaling:
  Current: 64 partitions, 3 brokers
  Scaling trigger: producer throughput > 60K/sec sustained per broker
  Action: add brokers, rebalance partitions
  
Spark aggregation scaling:
  Current: 10-node Spark cluster for hourly job
  Scaling trigger: job duration > 30 minutes (target: 15 min)
  Action: add executor nodes, increase parallelism

Trie Builder scaling:
  Current: single large machine (64 GB RAM, 16 cores)
  Scaling trigger: build duration > 30 minutes
  Action: distributed Trie building (partition queries by prefix range,
           build sub-Tries in parallel, merge)

Redis cache scaling:
  Current: 6-node cluster (3 primary + 3 replica)
  Scaling trigger: memory > 80% or p99 > 2 ms
  Action: add shards to cluster (Redis Cluster auto-reshards)
```

---

## 15. Database Choice

### Storage Technology Selection

| Data Type              | Storage Choice        | Rationale                                             |
|------------------------|-----------------------|-------------------------------------------------------|
| **Hot cache (prefix -> top-K)** | Redis Cluster  | Sub-ms reads, TTL-based expiry, cluster mode for scale |
| **Trie (in-memory)**   | JVM Heap              | Fastest possible access, no serialization overhead     |
| **Raw query logs**     | S3 / HDFS (Parquet)   | Append-only, cheap, integrates with Spark for batch processing |
| **Query frequencies**  | S3 / HDFS (Parquet)   | Bulk reads by Trie Builder, no random access needed    |
| **User search history** | Redis (Hash)         | Fast per-user lookup, small footprint per user         |
| **Trending scores**    | Redis (String + TTL)  | Real-time read/write, auto-expires with TTL            |
| **Blocklist**          | PostgreSQL + in-memory HashSet | Persisted in DB, loaded into memory on each node |
| **Trie binary snapshots** | S3                 | Large files (5 GB), distributed to Trie servers        |
| **Kafka events**       | Kafka (on disk)       | Durable event stream, 30-day retention                 |

### Redis Cluster Design

```
+------------------------------------------------------------------+
|                    REDIS CLUSTER                                   |
|                                                                   |
|  Purpose: distributed cache for prefix -> suggestions mapping     |
|                                                                   |
|  Topology: 6 nodes                                                |
|    Primary 1 (slots 0-5460)      Replica 1                        |
|    Primary 2 (slots 5461-10922)  Replica 2                        |
|    Primary 3 (slots 10923-16383) Replica 3                        |
|                                                                   |
|  Memory per node: 16 GB                                           |
|  Total cluster memory: 48 GB (usable: ~32 GB after overhead)      |
|                                                                   |
|  Key patterns:                                                    |
|    ac:{prefix}:{limit}:global     -> cached global suggestions    |
|    ac:{prefix}:{limit}:{userId}   -> cached personal suggestions  |
|    trending:{query}               -> trending score (TTL 1h)      |
|    user_history:{userId}          -> hash of query -> count        |
|                                                                   |
|  Memory estimation:                                               |
|    Cached suggestions: 5M unique prefix+user combos              |
|      5M * 500 bytes avg value = 2.5 GB                            |
|    Trending scores: 100K queries * 50 bytes = 5 MB                |
|    User history: 50M users * 200 bytes avg = 10 GB                |
|    Total: ~13 GB -> fits comfortably in 32 GB usable              |
|                                                                   |
|  TTL policy:                                                      |
|    Global suggestions: 5 minutes                                  |
|    Personal suggestions: 2 minutes                                |
|    Trending scores: 1 hour (set by Flink job)                     |
|    User history: no TTL (explicit deletion on GDPR request)       |
|                                                                   |
|  Eviction policy: allkeys-lru (evict least recently used on OOM)  |
+------------------------------------------------------------------+
```

### Why NOT a Traditional Database for Suggestions?

```
PostgreSQL / MySQL:
  - Autocomplete needs sub-5ms lookups -> DB round-trip is 2-10ms minimum
  - 140K QPS on cache miss -> DB can't handle this without massive cluster
  - LIKE 'prefix%' query uses B-tree index but still slower than in-memory Trie
  - Only viable as a backing store for the frequency table, NOT for live queries

Elasticsearch:
  - Has built-in "completion suggester" with FST (Finite State Transducer)
  - Good for moderate scale (< 10K QPS)
  - At our scale (1.4M QPS): would need a massive ES cluster
  - Adds network hop + serialization overhead vs in-memory Trie
  - Good alternative if you're already using ES for full-text search

MongoDB:
  - Similar latency issues as PostgreSQL for prefix queries
  - Regex prefix query: db.queries.find({query: /^prefix/})
  - Slower than Trie even with index (B-tree vs character-level traversal)

Verdict:
  In-memory Trie > Redis > Elasticsearch > PostgreSQL for autocomplete
  Use Redis as a cache layer, S3/HDFS as bulk storage, in-memory Trie as the engine.
```

---

## 16. CAP Theorem

### CAP Analysis for Autocomplete

```
+-----------------------------------------------------------+
|                   CAP THEOREM                              |
|                                                            |
|              Consistency (C)                                |
|                 /\                                          |
|                /  \                                         |
|               /    \                                        |
|              / CA   \  CP                                   |
|             /        \                                      |
|            /    WE    \                                     |
|           /   CHOOSE   \                                   |
|          /     AP       \                                   |
|         /________________\                                  |
|   Availability (A)     Partition Tolerance (P)             |
|                                                            |
+-----------------------------------------------------------+

We choose: AP (Availability + Partition Tolerance)

Why AP?

(1) Stale suggestions are acceptable:
    - If a user gets suggestions from 5 minutes ago, they won't notice
    - "amazon" being ranked #1 vs #2 doesn't break user experience
    - Trending queries surfacing 5 minutes late is OK
    - This is NOT a financial system where stale data = money lost

(2) Availability is critical:
    - Autocomplete is triggered on EVERY KEYSTROKE
    - If autocomplete is down, the search box feels broken
    - Users will leave the page or think the site is down
    - Even returning cached/stale suggestions is better than nothing

(3) Partition tolerance is mandatory:
    - Distributed system across multiple data centers
    - Network partitions WILL happen (not if, but when)
    - Must handle it gracefully

What "stale" looks like in practice:
    - Trie was last rebuilt 2 hours ago -> missing queries from last 2 hours
    - Redis cache was populated 3 minutes ago -> missing very recent trending
    - CDN cache is 10 minutes old -> slightly outdated rankings
    
    None of these are noticeable to users. Autocomplete is inherently approximate.
```

### Consistency Levels by Component

```
| Component            | Consistency Level  | Why                                       |
|----------------------|-------------------|-------------------------------------------|
| Trie (in-memory)     | Eventually consistent | Rebuilt hourly, stale between rebuilds  |
| Redis Cache          | Eventually consistent | TTL-based, may serve stale data         |
| L1 Cache             | Eventually consistent | Per-node cache, not synchronized        |
| Kafka (event log)    | Strongly consistent  | acks=all, replication factor 3           |
| Frequency Table      | Eventually consistent | Updated hourly by batch job             |
| Trending Scores      | Eventually consistent | Updated every 1-5 minutes by Flink      |
| Blocklist            | Strongly consistent  | Blocked queries must NEVER appear        |
| User History (Redis) | Eventually consistent | Small delay in updating is OK           |

Exception: Blocklist requires strong consistency
  - If a query is blocked (legal, safety), it must be removed immediately
  - Blocklist changes trigger push to all nodes (not TTL-based)
  - This is the ONE place where we sacrifice latency for correctness
```

### Failure Scenarios and Behavior

```
Scenario 1: Trie Service node goes down
  (1) Load Balancer detects failure (health check fails)
  (2) Traffic routed to replica nodes for that shard
  (3) No data loss -- other replicas have identical Trie
  (4) User impact: none (transparent failover)

Scenario 2: Redis Cluster partially down (1 primary lost)
  (1) Redis Cluster promotes replica to primary (automatic)
  (2) During failover (~5-15 seconds): some cache lookups fail
  (3) Autocomplete Service falls through to Trie (cache miss path)
  (4) User impact: slightly higher latency for ~15 seconds
  
Scenario 3: Entire Redis Cluster down
  (1) All L2 cache lookups fail
  (2) L1 cache absorbs some load (60-second TTL)
  (3) Remaining requests hit Trie directly
  (4) Trie handles 140K QPS -> may need throttling
  (5) Degraded mode: disable personalization, serve global only
  (6) User impact: higher latency (20ms -> 50ms), no personalization

Scenario 4: Kafka down (can't ingest search events)
  (1) Search events are buffered locally (in-memory queue)
  (2) If buffer fills: events are dropped (acceptable -- we lose some counts)
  (3) Autocomplete still works (serves from existing Trie)
  (4) No new trending detection until Kafka recovers
  (5) User impact: trending queries won't update, stale suggestions

Scenario 5: Network partition between data centers
  (1) Each DC operates independently with its local Trie + Redis
  (2) Suggestions may diverge between DCs (different cached data)
  (3) When partition heals: Trie rebuild re-syncs from shared S3
  (4) User impact: potentially different suggestions in different regions
     (acceptable -- regional suggestions are a feature, not a bug)
```

---

## 17. Cloud Services

### AWS Mapping

| Component                | AWS Service                     | Configuration                           |
|--------------------------|---------------------------------|-----------------------------------------|
| API Gateway              | Amazon API Gateway / ALB        | Rate limiting, auth, request routing    |
| Load Balancer            | Application Load Balancer (ALB) | Cross-AZ, health checks                |
| Autocomplete Service     | Amazon ECS (Fargate) or EC2     | 16 GB memory instances, auto-scaling    |
| Trie Service             | Amazon EC2 (r6g.xlarge)         | 16 GB RAM, in-memory Trie              |
| L1 Cache                 | In-process (Caffeine in JVM)    | No AWS service needed                   |
| Distributed Cache        | Amazon ElastiCache (Redis)      | Cluster mode, 6 nodes                  |
| Event Streaming          | Amazon MSK (Managed Kafka)      | 3 brokers, 64 partitions              |
| Raw Log Storage          | Amazon S3                       | Parquet format, lifecycle to Glacier   |
| Batch Aggregation        | Amazon EMR (Spark)              | Hourly jobs, auto-scaling cluster      |
| Trending Detection       | Amazon Kinesis Data Analytics    | Or Flink on EMR                         |
| Trie Binary Storage      | Amazon S3                       | Versioned bucket for Trie snapshots    |
| Frequency Table          | Amazon S3 (Parquet)             | Read by Trie Builder, write by Spark   |
| Monitoring               | Amazon CloudWatch               | Latency, QPS, cache hit ratio metrics  |
| CDN                      | Amazon CloudFront               | Edge caching for popular prefixes      |
| DNS                      | Amazon Route 53                 | Latency-based routing                  |
| Secrets                  | AWS Secrets Manager             | API keys, service tokens               |

### GCP Mapping

| Component                | GCP Service                     |
|--------------------------|---------------------------------|
| API Gateway              | Cloud Endpoints / Cloud Armor   |
| Load Balancer            | Cloud Load Balancing            |
| Autocomplete Service     | Google Kubernetes Engine (GKE)  |
| Trie Service             | GCE (n2-highmem-4)             |
| Distributed Cache        | Memorystore (Redis)             |
| Event Streaming          | Cloud Pub/Sub or Confluent Kafka|
| Raw Log Storage          | Cloud Storage (GCS)             |
| Batch Aggregation        | Dataproc (Spark)                |
| Trending Detection       | Dataflow (Apache Beam)          |
| CDN                      | Cloud CDN                       |

### Azure Mapping

| Component                | Azure Service                   |
|--------------------------|---------------------------------|
| API Gateway              | Azure API Management            |
| Load Balancer            | Azure Load Balancer / App GW    |
| Autocomplete Service     | Azure Kubernetes Service (AKS)  |
| Trie Service             | Azure VMs (E-series)            |
| Distributed Cache        | Azure Cache for Redis           |
| Event Streaming          | Azure Event Hubs                |
| Raw Log Storage          | Azure Blob Storage              |
| Batch Aggregation        | Azure HDInsight (Spark)         |
| Trending Detection       | Azure Stream Analytics          |
| CDN                      | Azure CDN                       |

### Multi-Region Architecture

```
+-------------------+          +-------------------+
|   US-EAST Region  |          |   EU-WEST Region  |
|                   |          |                   |
|  +-------------+  |          |  +-------------+  |
|  | API GW + LB |  |          |  | API GW + LB |  |
|  +------+------+  |          |  +------+------+  |
|         |         |          |         |         |
|  +------+------+  |          |  +------+------+  |
|  | Autocomplete|  |          |  | Autocomplete|  |
|  | Service     |  |          |  | Service     |  |
|  +------+------+  |          |  +------+------+  |
|         |         |          |         |         |
|  +------+------+  |          |  +------+------+  |
|  | Trie Shards |  |          |  | Trie Shards |  |
|  | (5 shards)  |  |          |  | (5 shards)  |  |
|  +------+------+  |          |  +------+------+  |
|         |         |          |         |         |
|  +------+------+  |          |  +------+------+  |
|  | Redis Cache |  |          |  | Redis Cache |  |
|  +-------------+  |          |  +-------------+  |
+--------+----------+          +--------+----------+
         |                              |
         +----------+  +---------------+
                    |  |
               +----+--+-----+
               |   S3 (shared)|
               |   Trie builds|
               |   Query logs |
               +--------------+

Each region:
  - Has its own Trie, Cache, and Autocomplete Service
  - Reads Trie builds from shared S3 (same global Trie)
  - Local cache -> local latency benefits
  - Regional suggestions can diverge slightly (acceptable)
  
DNS (Route 53): routes users to nearest region (latency-based)
```

---

## 18. Tradeoffs Summary

| Decision                        | Chosen Approach              | Alternative                     | Why We Chose It                                          |
|---------------------------------|------------------------------|---------------------------------|----------------------------------------------------------|
| **Data Structure**              | Compressed Trie (Radix Tree) | HashMap of prefix -> results    | Trie supports incremental prefix matching; HashMap needs all prefixes pre-computed |
| **Top-K Strategy**              | Pre-computed at each node    | Compute on-the-fly via DFS      | O(1) read vs O(subtree) scan; extra memory is worth it   |
| **Trie Update Strategy**        | Full rebuild + hot-swap      | Incremental in-place updates    | Simpler, avoids concurrent mutation complexity; rebuild is fast enough (20 min) |
| **Cache Layers**                | CDN + L1 + Redis (3-tier)    | Single Redis cache              | Multi-tier absorbs 90%+ hits; L1 eliminates network hop for hot prefixes |
| **Ranking**                     | Weighted composite score     | Single factor (frequency only)  | Composite captures recency, trending, personalization; tunable weights |
| **Trending Detection**          | Real-time (Flink/Streams)    | Batch only (hourly)             | Breaking news must surface in minutes, not hours          |
| **Sharding**                    | Prefix range partitioning    | Hash-based sharding             | Prefix range preserves locality; one prefix = one shard (no scatter-gather) |
| **Consistency**                 | AP (eventual consistency)    | CP (strong consistency)         | Stale suggestions are acceptable; availability is critical |
| **Personalization Storage**     | Redis hash per user          | Separate personalization DB     | Fast reads, small footprint per user; Redis already in stack |
| **Trie Serialization**          | Custom binary format         | Java Serialization / Protobuf   | Custom is 3-5x faster for this specific structure         |
| **Spell Correction**            | Edit distance at Trie level  | Separate spell-check service    | Co-locating with Trie avoids extra network hop            |
| **Language**                    | Java 21 (virtual threads)    | Go / Rust                       | Team expertise; virtual threads solve C10K without Rust complexity |
| **Cache Invalidation**          | TTL-based expiry             | Event-driven invalidation       | Simpler; 5-minute staleness is acceptable for suggestions |
| **Data Pipeline**               | Kafka + Spark (Lambda arch)  | Pure streaming (Kappa arch)     | Batch rebuild gives consistent Trie; streaming handles trending |

### Key Tradeoff Deep Dives

```
Tradeoff 1: Pre-computed Top-K vs On-the-Fly Computation

  Pre-computed:
    + O(1) read at query time (just read the list from the node)
    + Consistent results (same prefix = same results within a Trie version)
    - Stale until next Trie rebuild (1-4 hours)
    - Extra memory: ~2-3 GB for top-K lists across all nodes
    - Can't incorporate real-time personalization into pre-computed lists
    
  On-the-fly:
    + Always fresh (reads current frequencies)
    + Can personalize per-request
    - O(subtree_size) for short prefixes (seconds for "a" or "t")
    - Inconsistent latency (short prefix = slow, long prefix = fast)
    - CPU-intensive: sorting millions of candidates per request
    
  Our approach: HYBRID
    - Pre-compute top-K at each node (handles 90% of requests)
    - Ranking Service re-scores the pre-computed list (light-weight, O(K))
    - Personalization applied as a re-ranking step, not a Trie traversal
    - Trending scores read from Redis and applied during re-ranking
    
  Result: O(prefix_length + K) per request, with personalization and trending

Tradeoff 2: Full Trie Rebuild vs Incremental Updates

  Full Rebuild:
    + Simple: build new Trie from scratch every 1-4 hours
    + Clean: no accumulated errors, no orphan nodes
    + Testable: can validate new Trie before deploying
    - 20-30 minute build time -> 1-4 hour staleness
    - Resource-intensive: large Spark job + Trie construction
    
  Incremental Updates:
    + Near real-time: new queries appear in seconds
    + Less resource-intensive per update
    - Complex: must handle concurrent reads during mutation
    - Risk of memory fragmentation (many small mutations)
    - Harder to maintain top-K consistency (propagation on every insert)
    - Debugging is harder (what's the current state of the Trie?)
    
  Our approach: Full rebuild for base Trie + real-time trending overlay
    - Base Trie rebuilt every 1-4 hours (covers 99% of suggestions)
    - Trending detection runs in real-time (Flink -> Redis)
    - Ranking Service checks trending scores at re-ranking time
    - Best of both worlds: stable base + real-time trending
```

---

## 19. Interview Talking Points

### Opening Statement (30 seconds)

> "Search autocomplete is a prefix-matching system that returns ranked suggestions as the user types. The core data structure is a compressed Trie with pre-computed top-K suggestions at every node, enabling O(prefix-length) lookups. At scale, we shard the Trie by prefix range, cache aggressively at three levels (CDN, in-process, Redis), and use a batch pipeline (Kafka -> Spark -> Trie Builder) to periodically rebuild and hot-swap the Trie. We layer in real-time trending detection via stream processing and personalization via per-user Redis hashes."

### Key Points to Hit

```
1. Trie + Top-K is the "aha moment":
   "Without pre-computed top-K, searching for prefix 'a' requires a DFS of millions of
    nodes. With top-K stored at each node, it's O(1) after traversal. The tradeoff is
    extra memory (~2 GB) for sub-millisecond lookups."

2. Compressed Trie (Radix Tree):
   "Basic Tries waste nodes on single-child chains. Compression merges 'h-o-w- -t-o'
    into a single node 'how to', reducing node count by 60-80% and fitting 200M queries
    into ~5 GB."

3. Multi-level caching:
   "90%+ of requests hit cache before reaching the Trie. CDN catches global hot prefixes,
    L1 (Caffeine) eliminates network hops, Redis serves personalized results. Only 
    long-tail prefixes actually traverse the Trie."

4. Copy-on-write Trie updates:
   "The Trie is immutable during its lifetime. Updates are full rebuilds in a background
    thread. A single volatile reference swap atomically deploys the new version. No locks
    needed for the read path."

5. Batch + Real-time hybrid:
   "Base Trie is rebuilt hourly from aggregated data (Kafka -> Spark -> Trie Builder).
    Trending queries are detected in real-time by Flink and overlaid during re-ranking.
    This gives us stable suggestions plus real-time freshness for breaking events."

6. Sharding by prefix range:
   "We partition the Trie by first character of the prefix. 'a-f' on Shard 1, 'g-m' on
    Shard 2, etc. Each request goes to exactly one shard -- no scatter-gather needed."

7. AP over CP:
   "Stale suggestions are acceptable -- users won't notice if rankings are 5 minutes old.
    Availability is critical because autocomplete fires on every keystroke. We choose AP."
```

### Common Follow-Up Questions and Answers

```
Q: "How would you handle a query like 'a' that matches millions of results?"
A: "Pre-computed top-K at the 'a' node. We store the top 10-15 suggestions at EVERY
    node, including 'a'. No DFS needed. The top-K for 'a' might be ['amazon', 'apple',
    'amc', ...] -- pre-computed during Trie build."

Q: "What if the Trie doesn't fit in memory?"
A: "First, compress it (Radix Tree). 200M queries compress to ~5 GB. If still too large,
    shard by prefix range across multiple machines. Each machine holds a subset of the
    Trie. At extreme scale, consider storing only frequent prefixes in memory and serving
    long-tail queries from disk/SSD (tiered storage)."

Q: "How do you handle offensive suggestions?"
A: "Blocklist HashSet loaded on every Trie server. Checked after top-K retrieval but
    before returning to the user. Blocklist changes push immediately to all nodes
    (strong consistency for safety). Also filtered during Trie build (is_blocked flag)."

Q: "How do you handle multi-word queries?"
A: "Spaces are characters in the Trie. 'how to learn' is stored as a single path
    h-o-w-' '-t-o-' '-l-e-a-r-n. The Trie naturally handles multi-word prefixes."

Q: "How is this different from Elasticsearch's completion suggester?"
A: "Elasticsearch uses a Finite State Transducer (FST), which is similar to a compressed
    Trie but optimized for disk-based storage. For sub-10K QPS, ES is a good choice.
    At 1.4M QPS, a custom in-memory Trie is faster (no serialization, no network hop
    to ES cluster). We also have full control over ranking, sharding, and hot-swap."

Q: "How would you add spell correction?"
A: "Two approaches: (1) Pre-compute common misspellings during Trie build -- for top 1M
    queries, generate edit-distance-1 variants and store them as aliases. (2) At query
    time, if prefix finds no match, do a bounded BK-tree or Levenshtein automaton search
    for the closest prefix. Approach 1 is faster (O(1) alias lookup), approach 2 is more
    flexible but slower."

Q: "What about mobile-specific optimizations?"
A: "Longer debounce on mobile (100-200ms vs 50ms on desktop) to reduce request volume.
    Smaller suggestion list (5-7 vs 10) since mobile screens are smaller. Aggressive
    client-side caching (if user typed 'how to', cache the response; when they type
    'how to l', filter client-side first, only hit server if client cache expires).
    Prefetch: after first keystroke, prefetch suggestions for likely next keystrokes."

Q: "How would you test this system?"
A: "Unit tests for Trie operations (insert, search, prefix match, top-K computation).
    Integration tests for end-to-end flow (keystroke -> suggestion response).
    Load testing: simulate 1.4M QPS to validate latency and cache hit ratios.
    A/B testing: compare ranking algorithms (frequency-only vs composite score)
    using click-through rate as the metric.
    Chaos testing: kill Trie nodes, Redis nodes, Kafka brokers to verify graceful
    degradation."
```

### Complexity Summary for Interview Whiteboard

```
+-----------------------------+-------------------+-------------------+
| Operation                   | Time Complexity   | Space Complexity  |
+-----------------------------+-------------------+-------------------+
| Trie insert (single query)  | O(L)              | O(L) new nodes    |
| Trie prefix search          | O(P)              | O(1)              |
| Top-K retrieval at node     | O(1)              | O(K) per node     |
| Ranking re-score            | O(K * log K)      | O(K)              |
| Trie build (all queries)    | O(N * L)          | O(N * L)          |
| Top-K propagation (build)   | O(nodes * K * logK)| O(K) per node    |
| Cache lookup (L1/Redis)     | O(1) amortized    | O(entries)        |
| End-to-end (cache hit)      | O(1)              | O(1)              |
| End-to-end (cache miss)     | O(P + K*logK)     | O(K)              |
+-----------------------------+-------------------+-------------------+

L = query length, P = prefix length, K = top-K count (10)
N = total number of unique queries (200M)
nodes = total Trie nodes (~500M compressed)

Key insight: 
  Pre-computing top-K transforms the query-time complexity from
  O(P + subtree_size) to O(P + K*logK), where K << subtree_size.
  For prefix "a" with 50M queries underneath, this is the difference
  between milliseconds and seconds.
```

### 30-Second Architecture Elevator Pitch

```
                    "Search Autocomplete in 30 seconds"

     User types         Cache layers absorb 90%        Trie serves the rest
    each keystroke  -->  CDN -> L1 -> Redis        -->  In-memory Radix Tree
                                                        with pre-computed top-K
    
    Async pipeline:  Kafka --> Spark (hourly) --> Trie Builder --> Hot-swap
                     Flink (real-time trending) --> Redis overlay
    
    Sharded by prefix range | AP consistency | p99 < 100ms | 1.4M QPS
```

---

**End of High-Level Design: Search Autocomplete (Typeahead) System**
