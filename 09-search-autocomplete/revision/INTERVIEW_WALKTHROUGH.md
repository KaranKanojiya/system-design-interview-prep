# Interview Walkthrough -- Search Autocomplete (Typeahead)

> **Total time: ~35 minutes. The Trie deep dive and ranking/personalization are 50% of this interview.**
> This problem tests Trie data structures (basic -> compressed -> TopK), ranking algorithms (frequency, time-decay, personalization), caching strategy (Zipf distribution), and offline data pipelines (Kafka -> Spark -> Trie rebuild).

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "How many unique search queries per day? 100M? 1B? This determines Trie size and whether it fits in memory."
- "What's the acceptable latency? Sub-100ms end-to-end including network?"
- "Do we need personalized suggestions, or global top-K only?"
- "How fresh must suggestions be? Is a 15-60 minute delay for trending queries acceptable?"
- "Do we need fuzzy matching -- handling typos like 'facbook' -> 'facebook'?"
- "Multi-language support? Or English only for now?"

### Clarified Scope

```
In scope:   Prefix-based autocomplete, top-K suggestions per prefix,
            ranking (frequency + time-decay), caching, offline Trie rebuild
Out of scope: Full-text search (that's Elasticsearch), spell correction (mention only),
              voice search, image search, ad-driven suggestions
```

### What This Signals

You understand this is a **read-heavy, latency-sensitive system** with an **offline data pipeline** -- not a real-time update problem. You're probing for the hard parts: Trie optimization, ranking, and cache strategy.

**Common follow-up:** "Why does the number of unique queries matter?"

**Answer:** "At 50M unique queries, the Trie is about 2-4 GB in memory -- fits comfortably in a single JVM instance. At 1B unique queries, it's 20-40 GB -- I'd need to either shard the Trie by first character, use a compressed Trie, or offload to Elasticsearch's Completion Suggester which uses an on-disk FST (Finite State Transducer). The crossover point is roughly 100M terms."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll design four layers: a **CDN edge cache** for short prefixes (1-2 chars, 70% of traffic), a **Redis LRU cache** for prefix -> top-K results, an **Autocomplete Service** with an in-memory Trie, and an **offline data pipeline** (Kafka -> Spark -> Trie rebuild) that hot-swaps the Trie every 15-60 minutes."

### Draw This Diagram

```
                  +---------------------------+
                  |         User              |
                  |  Types "how t" (debounced |
                  |   100-200ms per keystroke) |
                  +------------+--------------+
                               |
              1. GET /autocomplete?prefix=how+t
                               |
                  +------------v--------------+
                  |     CDN (CloudFront)       |
                  |  Cache short prefixes      |
                  |  1-2 char: TTL 1 hour      |
                  |  HIT rate: 70% overall     |
                  +------------+--------------+
                               |
              2. MISS -> forward to origin
                               |
                  +------------v--------------+
                  |     API Gateway            |
                  |  Rate limit: 50 req/sec    |
                  |  per user (anti-abuse)     |
                  +------------+--------------+
                               |
              3. Route to Autocomplete Service
                               |
                  +------------v--------------+
                  |   Autocomplete Service     |
                  |   (Stateless, N replicas)  |
                  +------+------------+-------+
                         |            |
            4. Check     |            |  5. Cache MISS:
            Redis cache  |            |  query in-memory Trie
                         v            v
                  +-----------+  +------------------+
                  |   Redis   |  |  In-Memory Trie  |
                  |   (LRU)   |  |  (per instance)  |
                  |           |  |                  |
                  | Key:      |  | TopK Trie:       |
                  |  "how t"  |  |  O(L) traverse   |
                  | Val:      |  |  O(1) return     |
                  |  [top-10] |  |  pre-computed K  |
                  | TTL: 5min |  |                  |
                  +-----------+  +--------+---------+
                                          |
              6. Return top-K suggestions to user
                 (write to Redis cache on miss)

                  ====== OFFLINE PIPELINE ======

                  +---------------------------+
                  |  User search events       |
                  |  (async, non-blocking)    |
                  +------------+--------------+
                               |
              7. Publish to Kafka/Kinesis
                               |
                  +------------v--------------+
                  |   Kafka (query-events)    |
                  |   Partition by prefix     |
                  +------------+--------------+
                               |
              8. Spark (hourly aggregation)
                 Count per query, apply time-decay
                               |
                  +------------v--------------+
                  |   Trie Builder (Spark)    |
                  |   Build new Trie from     |
                  |   aggregated frequencies  |
                  +------------+--------------+
                               |
              9. Serialize Trie -> S3 snapshot
                               |
             10. Autocomplete instances hot-swap:
                 Load new Trie from S3, atomic pointer swap
                 Old Trie GC'd after all in-flight requests drain
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| CDN (CloudFront) | Cache short prefix responses at edge, Zipf distribution | AP (stale OK) |
| API Gateway | Rate limiting, request routing, authentication | Stateless |
| Autocomplete Service | In-memory Trie + ranking, stateless N replicas | AP |
| Redis Cache (LRU) | prefix -> top-K, absorbs 85% of Trie lookups | AP |
| In-Memory Trie | Pre-computed TopK at every node, O(1) lookup | AP (rebuilt periodically) |
| Kafka / Kinesis | Query event stream for aggregation pipeline | Durable |
| Spark (EMR) | Hourly frequency aggregation, time-decay scoring | Batch |
| S3 | Trie snapshots for hot-swap, versioned | Durable |

### What This Signals

You separate the **online serving path** (CDN -> Redis -> Trie, all AP) from the **offline data pipeline** (Kafka -> Spark -> S3 -> hot-swap). This is the key architectural insight for autocomplete.

**Common follow-up:** "Why not update the Trie in real-time as queries come in?"

**Answer:** "Three reasons. First, concurrent writes to a Trie require locking, which kills read latency -- every keystroke blocks on a write lock. Second, individual query events are noisy -- you need aggregation to compute meaningful frequencies. Third, hot-swap is safer -- if the new Trie is bad (bug in aggregation), I roll back to the previous S3 snapshot in seconds. The cost is 15-60 minutes of staleness, which is fine for autocomplete -- users don't notice if a trending query appears 30 minutes late."

---

## Phase 3: Trie Deep Dive (8-10 min)

**This is the core of the interview. Spend the most time here.**

### Part A: Basic Trie -- O(L) Insert and Search

> "A Trie is a tree where each node represents a single character. To insert a word, I traverse from the root, creating nodes for each character. To search a prefix, I traverse the same path. The depth equals the word length, so both operations are O(L)."

```
Insert: "apple", "app", "ape", "bat", "ball"

         (root)
        /      \
      'a'      'b'
      /          \
    'p'          'a'
    / \            \
  'p'  'e'*       't'*
  /     |           \
'l'    (end)        'l'*
 |                   |
'e'*               'l'*
 |
(end)
  |
"app"* (isEndOfWord = true at this node)

Legend: * = isEndOfWord (a complete word ends here)

Search for prefix "ap":
  1. root -> 'a' (found)
  2. 'a' -> 'p' (found)
  3. Node 'p' exists -> prefix "ap" is valid
  4. DFS from 'p': find all words below
     -> "app", "apple", "ape"
  Total: O(L) to reach prefix node + O(subtree) to collect all words
```

### The Subtree Problem

```
Problem: Searching prefix "a" requires DFS of ENTIRE subtree under 'a'.
         If 10M words start with 'a', we traverse 10M nodes. SLOW!

         (root)
           |
          'a'
         / | \
       ...10M words below here...

  O(L) to reach node + O(subtree) to enumerate = O(L + N) worst case
  For prefix "a" with 10M matches, this is O(10M). UNACCEPTABLE for autocomplete.

Solution: Pre-compute top-K at every node! (Part C below)
```

### Part B: Compressed Trie (Patricia / Radix Tree)

> "A compressed Trie merges chains of single-child nodes into one node with a multi-character label. This reduces node count by 50-70% without changing the O(L) complexity."

```
Before compression (Basic Trie):          After compression (Compressed Trie):
         (root)                                    (root)
        /      \                                  /      \
      'a'      'b'                             "ap"     "ba"
      /          \                             / \        \
    'p'          'a'                        "pl"  "e"*   "t"*
    / \            \                          |           |
  'p'  'e'*       't'*                      "e"*       "ll"*
  /                 \
'l'                'l'*
 |                  |
'e'*              'l'*

Nodes before: 12                          Nodes after: 7 (42% reduction)

Rule: If a node has exactly ONE child and is NOT end-of-word,
      merge it with the child.

  'a' -> 'p' (single child, not end-of-word) -> merge to "ap"
  'p' -> 'l' -> 'e' (chain) -> merge to "ple" under "ap"
  'b' -> 'a' (single child) -> merge to "ba"
  't' -> stays (end-of-word)
  'l' -> 'l' (single child, end-of-word at parent) -> can't merge 't', but 'l'->'l' merges to "ll"

Insert into compressed Trie:
  Insert "application":
    1. Traverse to "ap" node
    2. Match "pl" of "ple" node
    3. Split: "pl" becomes "pl" -> "e"* and "pl" -> "ication"*
    4. O(L) time, may require one node split
```

### Part C: TopK Trie -- The Key Optimization

> "The TopK Trie pre-computes the top-K most frequent suggestions at every node. When a user types a prefix, I traverse to that node in O(L) and return the pre-computed list in O(1). No DFS needed."

```
TopK Trie (K=3, stored at each node):

         (root) top3: ["how to cook pasta", "hotel booking", "how are you"]
        /      \
      'h'       'w'
  top3: ["how to cook pasta",    top3: ["weather today", ...]
         "hotel booking",
         "how are you"]
      /
    'o'
  top3: ["how to cook pasta",
         "hotel booking",
         "hot dog recipe"]
    / \
  'w'  't'
  top3: ["how to cook      top3: ["hotel booking",
    pasta", "how to           "hot dog recipe",
    tie a tie", "how          "hotmail login"]
    to lose weight"]
   |
  ' '  (space)
  top3: ["how to cook pasta",
         "how to tie a tie",
         "how to lose weight"]

Query: user types "how"
  1. Traverse: root -> 'h' -> 'o' -> 'w'          O(3) = O(L)
  2. Return node's pre-computed top3:               O(1)
     ["how to cook pasta", "how to tie a tie", "how to lose weight"]
  Total: O(L)    NO DFS!

Insert: "how to learn java" with frequency 50000
  1. Traverse root -> 'h' -> 'o' -> 'w' -> ' ' -> ... -> 'a'   O(L)
  2. At EACH node along the path, check if new entry beats
     the K-th entry in the top-K list:
     - At 'h': top3 has min score 65000. 50000 < 65000. Skip.
     - At 'o': same. Skip.
     - At 'w': same. Skip.
     - At ' ': top3 has min score 78000. 50000 < 78000. Skip.
     - At 'l': top3 is not full. Add. Insert.
  Total: O(L * K) per insert (check K entries at L nodes)

Space: Each node stores K suggestions (pointers + scores).
       50M nodes * K=10 * 8 bytes = ~4 GB overhead
       Tradeoff: more memory, but O(1) lookup at query time.
```

### Why TopK is the Right Tradeoff for Autocomplete

```
             | Basic Trie     | TopK Trie
  -----------|----------------|------------------
  Query time | O(L + subtree) | O(L) + O(1) = O(L)
  Insert time| O(L)           | O(L * K)
  Space      | Minimal        | +K entries/node
  -----------|----------------|------------------
  
  Autocomplete characteristics:
    - Reads >> Writes (10,000:1 ratio)
    - Writes are batched (offline rebuild)
    - Latency budget: <10ms for Trie lookup
    - Subtree for "a" could be 10M nodes

  Conclusion: TopK is perfect. Pay O(L*K) at insert time
              (offline, nobody waiting), get O(1) at query time
              (user is waiting for every keystroke).
```

**Common follow-up:** "What if K changes? Do you rebuild the entire Trie?"

**Answer:** "Yes, changing K requires a full rebuild. But K is a system constant (usually 5-10), not a per-request parameter. If different users want different K values, I pre-compute with K=10 and truncate the list at query time. The rebuild cost is O(N * L * K) where N is vocabulary size -- about 30-60 seconds for 50M terms on a Spark cluster."

---

## Phase 4: Ranking & Personalization (5-7 min)

### Ranking Strategies

> "There are three progressively sophisticated ranking approaches. Start with frequency, add time-decay for freshness, then blend in personalization."

### Strategy 1: Raw Frequency (Baseline)

```
score(query) = total_search_count

Example for prefix "how":
  "how to cook pasta"     count = 95,000   score = 95,000
  "how to tie a tie"      count = 82,000   score = 82,000
  "how to lose weight"    count = 78,000   score = 78,000

Problem: "how to vote 2024" surges during election week
         but has low all-time count. Never appears in top-K.
```

### Strategy 2: Time-Decay (Freshness Boost)

```
score(query) = SUM over all events: count_i * decay^(hours_since_event_i)

  decay factor: 0.98 (per hour)

Example for prefix "how" during election week:
  "how to cook pasta"     count=95000  avg_age=720hrs   0.98^720 = 0.00006
                          score = 95000 * 0.00006 = 5.7
  "how to vote 2024"      count=12000  avg_age=2hrs     0.98^2 = 0.96
                          score = 12000 * 0.96 = 11,520

  Result: "how to vote 2024" now ranks #1!

Time-decay ensures trending queries surface quickly
and old queries fade naturally. No manual intervention.

Implementation in Spark aggregation:
  1. For each query, sum: count_in_bucket * decay^(bucket_age_hours)
  2. Buckets: last 1 hour, 1-6 hours, 6-24 hours, 1-7 days, 7-30 days
  3. Rebuild Trie with decayed scores every 15-60 minutes
```

### Strategy 3: Personalized (User History Blend)

```
score(query, user) = 0.7 * global_score + 0.3 * user_score

  global_score: time-decayed frequency across all users
  user_score:   frequency in THIS user's recent search history

Example for user who is a Java developer:
  Global top-3 for "how":
    1. "how to cook pasta"        global=0.95
    2. "how to tie a tie"         global=0.82
    3. "how to lose weight"       global=0.78

  User's history for "how":
    1. "how to use streams java"  user=0.90
    2. "how to debug java"        user=0.85
    3. "how to cook pasta"        user=0.60

  Blended (0.7 * global + 0.3 * user):
    1. "how to cook pasta"        0.7*0.95 + 0.3*0.60 = 0.845
    2. "how to use streams java"  0.7*0.00 + 0.3*0.90 = 0.270
    3. "how to tie a tie"         0.7*0.82 + 0.3*0.00 = 0.574

  Final ranking:
    1. "how to cook pasta"        0.845   (global winner still wins)
    2. "how to tie a tie"         0.574   (global #2)
    3. "how to use streams java"  0.270   (personalized injection!)

Architecture for personalization:
  1. Trie stores global top-K (pre-computed, same for all users)
  2. DynamoDB stores per-user search history (last 100 queries)
  3. At query time: fetch global top-K from Trie (O(1))
     + fetch user history from DynamoDB (O(1) by userId)
     + blend and re-rank in-memory
  4. Cache the blended result in Redis with key = "userId:prefix"
     TTL = 1 min (shorter than global cache, personalization changes faster)
```

### Personalization Architecture

```
User types "how"
    |
    1. Autocomplete Service receives prefix "how" + userId
    |
    +---> 2a. Trie lookup: global top-10 for "how"     O(1)
    |         ["how to cook pasta", "how to tie a tie", ...]
    |
    +---> 2b. DynamoDB lookup: user's recent queries    O(1)
    |         with prefix "how"
    |         ["how to use streams java", "how to debug java", ...]
    |
    3. Blend: 0.7 * global_score + 0.3 * user_score
       Re-rank combined list, return top-K
    |
    4. Cache in Redis: key = "user123:how", TTL = 60s
```

**Common follow-up:** "Won't personalization break CDN caching?"

**Answer:** "Yes -- personalized responses can't be cached at the CDN. My approach: for logged-out users or prefixes of length 1-2, serve global suggestions (CDN-cacheable). For logged-in users with prefix length 3+, add the userId to the cache key and serve personalized results from Redis (not CDN). This preserves CDN efficiency for the 70% of traffic that's short prefixes while adding personalization for the remaining 30%."

---

## Phase 5: Scaling & Data Pipeline (5-8 min)

### The Offline Trie Rebuild Pipeline

> "The core insight is: never update the live Trie. Build a new one offline from aggregated query logs, serialize it, and hot-swap it into the serving instances."

```
Full Pipeline (numbered):

  1. User searches "how to cook pasta"
     Autocomplete Service logs event to Kafka (async, non-blocking)
     { "query": "how to cook pasta", "timestamp": "2024-...", "userId": "u123" }
                |
  2. Kafka topic: "query-events"
     Partitioned by hash(prefix[0:3]) for locality
     Retention: 30 days
                |
  3. Spark job (runs every 15-60 minutes on EMR):
     a. Read last 30 days of query events from Kafka/S3
     b. Group by query string
     c. Apply time-decay: score = SUM(count_i * 0.98^hours_since_i)
     d. For each prefix of each query, compute top-K
     e. Build new Trie in memory on Spark driver
     f. Serialize Trie to binary format
                |
  4. Write serialized Trie to S3:
     s3://autocomplete-tries/v2024032415/trie.bin  (versioned)
     Size: ~2-4 GB for 50M terms
                |
  5. Notify Autocomplete Service instances (via SNS or deployment):
     "New Trie available at s3://autocomplete-tries/v2024032415/"
                |
  6. Each ECS instance:
     a. Download new Trie from S3 in background (~30 seconds)
     b. Deserialize into memory (new Trie object)
     c. Atomic pointer swap: trieReference.set(newTrie)
     d. Old Trie becomes eligible for GC after in-flight requests drain
     e. Invalidate Redis cache (prefix keys with old data)
                |
  7. Rollback plan:
     If new Trie has issues (empty, corrupt, suggestion quality drops):
     a. CloudWatch alarm: suggestion_click_rate < threshold
     b. Auto-rollback: load previous S3 snapshot
     c. All S3 versions retained for 7 days
```

### Hot-Swap Implementation Detail

```
class AutocompleteService {
    // AtomicReference ensures atomic swap, no locking needed
    private final AtomicReference<Trie> activeTrie = new AtomicReference<>();

    public List<Suggestion> suggest(String prefix, int k) {
        Trie trie = activeTrie.get();    // snapshot reference, O(1)
        return trie.getTopK(prefix, k);  // read from snapshotted Trie
    }

    public void hotSwap(Trie newTrie) {
        Trie oldTrie = activeTrie.getAndSet(newTrie);  // atomic swap
        // oldTrie is now only referenced by in-flight requests
        // GC will collect it once those requests complete
        // No locking, no downtime, no read pauses
    }
}

Why this works:
  - AtomicReference.getAndSet() is a single CAS (compare-and-swap) instruction
  - In-flight readers still hold a reference to the old Trie -- safe to finish
  - New readers immediately see the new Trie
  - No synchronized blocks, no read-write locks, no pause
```

### Scaling the Serving Layer

```
Problem: 500M autocomplete requests/day, 17K QPS at peak

Serving layer:
  +-----------+     +-----------+     +-----------+
  | ECS #1    |     | ECS #2    |     | ECS #N    |
  | Trie copy |     | Trie copy |     | Trie copy |
  | (2-4 GB)  |     | (2-4 GB)  |     | (2-4 GB)  |
  +-----------+     +-----------+     +-----------+
        |                 |                 |
        +--------+--------+--------+--------+
                 |                 |
           +-----v-----+    +-----v-----+
           | Redis      |    | Redis     |
           | Shard 1    |    | Shard 2   |
           | (LRU cache)|    | (LRU cache)|
           +------------+    +------------+

Scaling strategy:
  1. Each ECS instance holds a FULL copy of the Trie (not sharded)
     - Simple: no cross-instance calls for a single prefix query
     - Memory: 4 GB Trie + 4 GB headroom = 8 GB per instance
     - 15 instances at peak = 120 GB total (acceptable)

  2. Redis cache in front absorbs 85% of traffic
     - 17K QPS * 15% cache miss = 2,550 QPS to Trie (easily handled)

  3. Auto-scaling based on CPU:
     - Each Trie lookup: sub-ms, CPU-bound (pointer traversal)
     - Target: 60% CPU utilization per instance
     - Scale out during business hours (more searches)
     - Scale in at night

  4. If Trie exceeds single-instance memory (>10 GB):
     Option A: Shard Trie by first character (26 shards for a-z)
     Option B: Switch to Elasticsearch Completion Suggester
     Option C: Compressed Trie (50-70% less memory)
```

### Client-Side Optimizations

```
Critical for reducing backend load:

  1. DEBOUNCE: Wait 100-200ms after last keystroke before sending request
     Without debounce: "how" = 3 requests (h, ho, how)
     With debounce:    "how" = 1 request (how) -- 67% reduction!

  2. CACHE PREFIX RESULTS: If "how" returns ["how to cook", "how to tie", ...],
     and user types "how t", filter locally before sending new request.
     Client already has the answer for "how t" from "how" results!

  3. CANCEL IN-FLIGHT: When user types next character, abort previous request.
     XMLHttpRequest.abort() or AbortController.abort()
     Prevents stale responses from overwriting fresh ones.

  4. MINIMUM PREFIX LENGTH: Don't query for prefix length < 2.
     Prefix "a" returns generic results. Wait for at least 2 characters.

  Combined effect:
    Without optimizations: 10 keystrokes = 10 requests
    With all optimizations: 10 keystrokes = 2-3 requests (70-80% reduction)
```

**Common follow-up:** "What if a query is trending RIGHT NOW and you rebuild only every 15 minutes?"

**Answer:** "For truly real-time trending (like during a sports event), I add a secondary path: a Lambda function consuming from Kinesis with a 5-minute sliding window. When a query's frequency spikes above a threshold (e.g., 10x normal), the Lambda injects it directly into the Redis cache for the relevant prefixes with a short TTL. It bypasses the Trie entirely. The next scheduled Trie rebuild will pick it up permanently. This gives me real-time trending for spikes without the complexity of real-time Trie updates."

---

## Phase 6: Tradeoffs (3-5 min)

### Basic Trie vs Compressed Trie vs TopK Trie

| Aspect | Basic Trie | Compressed Trie | TopK Trie |
|--------|-----------|-----------------|-----------|
| Query time | O(L + subtree) | O(L + subtree) | O(L) + O(1) |
| Insert time | O(L) | O(L) + split | O(L * K) |
| Node count | 1 per character | 50-70% fewer | Same as basic |
| Memory per node | Small (char + map) | Medium (string + map) | Large (char + map + K entries) |
| Best for | Learning, small data | Memory-constrained | Production autocomplete |
| Interview | Explain first | Explain as optimization | **Implement this one** |

**Say:** "I'd start with a basic Trie to show I understand the data structure, then explain the TopK optimization as the key insight: pre-compute top-K at every node to turn O(subtree) lookups into O(1). In production, I'd combine compressed Trie (save memory) with TopK (fast lookups)."

### In-Memory Trie vs Elasticsearch Completion Suggester

| Aspect | In-Memory Trie | Elasticsearch Completion |
|--------|---------------|--------------------------|
| Latency | Sub-ms (in-process) | 1-5ms (network hop) |
| Vocabulary limit | ~10M terms (JVM heap) | 100M+ (disk-backed FST) |
| Fuzzy matching | Must implement yourself | Built-in (Levenshtein) |
| Custom ranking | Full control | Weights + contexts |
| Operational cost | Application memory only | Managed OpenSearch cluster |
| Update | Offline rebuild + hot-swap | Near real-time index refresh |

**Say:** "For an interview, I'd implement the in-memory Trie to demonstrate data structure knowledge. For production with under 10M terms, the in-memory Trie wins on latency. Above 100M terms, I'd use Elasticsearch's Completion Suggester -- it uses a Finite State Transducer internally, which is essentially a compressed, on-disk Trie with built-in fuzzy matching."

### Offline Rebuild vs Real-Time Trie Updates

| Aspect | Offline Rebuild (batch) | Real-Time Update (streaming) |
|--------|------------------------|------------------------------|
| Complexity | Simple: build, serialize, swap | Complex: concurrent reads + writes, locking |
| Freshness | 15-60 min stale | Near real-time (<1 min) |
| Safety | Atomic swap, easy rollback | Partial updates can corrupt Trie |
| Aggregation | Full re-aggregation each cycle | Incremental counts (approximations) |
| Best for | Autocomplete (stale OK) | Trending hashtags, breaking news |

**Say:** "For autocomplete, offline rebuild wins because stale suggestions by 15-60 minutes is acceptable -- users don't notice. The simplicity, safety, and ability to rollback outweigh the freshness advantage of real-time updates. If I need real-time trending, I add a secondary path: inject trending queries directly into Redis cache with a short TTL, bypassing the Trie."

### CAP Analysis

| Component | CP or AP | Why |
|-----------|----------|-----|
| Trie (suggestions) | **AP** | Stale suggestions are fine, rebuild periodically |
| Redis cache | **AP** | Cache miss = fallback to Trie, stale = harmless |
| CDN | **AP** | TTL-based, eventually consistent with Trie |
| User history (DynamoDB) | **AP** | Missing recent query in personalization is minor |
| Query event stream (Kafka) | **Durable** | Losing events affects aggregation quality, not serving |
| Trie snapshot (S3) | **Durable** | Versioned, cross-region replicated |

**Say:** "This entire system is AP. There's no CP requirement because showing a slightly stale or imperfect suggestion never causes data loss or inconsistency. The worst case is a user doesn't see a trending query for 15-60 minutes. Compare this to ride-sharing where ride assignment must be CP to prevent double-booking. Autocomplete's AP nature makes it much simpler to scale."

---

## Red Flags (What NOT to Do)

- Saying "just query the database for matching strings" without mentioning Trie
- Not knowing the TopK optimization (DFS of entire subtree on every keystroke)
- Updating the Trie in real-time without discussing concurrency/locking issues
- Ignoring caching (Redis and CDN handle 95%+ of traffic in production)
- Not mentioning client-side debouncing (reduces requests by 70%)
- Making the system CP ("suggestions must be perfectly consistent" -- wrong)
- Forgetting the data pipeline (where does the frequency data come from?)
- Not discussing Zipf distribution and its impact on caching strategy

## Green Flags (What Interviewers Want to Hear)

- Draw a Trie and explain O(L) traversal clearly
- Explain the subtree problem and TopK pre-computation as the solution
- Mention compressed Trie as a memory optimization (Patricia / Radix)
- Describe three ranking strategies: frequency -> time-decay -> personalized
- Explain the offline pipeline: Kafka -> Spark -> S3 -> hot-swap
- Mention CDN for short prefixes (Zipf distribution, 70% at edge)
- Client-side debouncing and prefix result filtering
- Proactively say "AP is fine -- stale suggestions are harmless"
- Calculate capacity: 50M terms = 2-4 GB Trie, fits in-memory

---

## 30-Second Elevator Pitch

> "For a search autocomplete system, I'd use a **TopK Trie** where every node pre-computes the top-K suggestions -- giving O(L) prefix traversal plus O(1) lookup, no DFS needed. The Trie is built **offline** by a Spark pipeline: Kafka streams query events, Spark aggregates frequencies with time-decay scoring, builds a new Trie, and serializes it to S3. Serving instances **hot-swap** the new Trie atomically with no downtime. In front: **CDN** caches short prefixes (1-2 chars, 70% of traffic via Zipf), **Redis LRU** caches prefix -> top-K (85% hit rate). Client-side **debouncing** at 200ms reduces requests by 70%. The system is fully **AP** -- stale suggestions by 15-60 minutes are fine. 50M terms fit in 2-4 GB of memory per instance."

**Time: Under 30 seconds. Covers: Trie + TopK, ranking, pipeline, caching, client optimization, CAP.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements            2-3 min   (queries/day, latency, personalization, fuzzy?)
Phase 2:  High-Level Architecture          5-7 min   (4 layers: CDN, Redis, Trie, pipeline)
Phase 3:  Trie Deep Dive                   8-10 min  (basic -> compressed -> TopK optimization)
Phase 4:  Ranking & Personalization        5-7 min   (frequency -> time-decay -> personalized blend)
Phase 5:  Scaling & Data Pipeline          5-8 min   (Kafka -> Spark -> S3 -> hot-swap, client debounce)
Phase 6:  Tradeoffs Discussion             3-5 min   (Trie vs ES, offline vs real-time, AP everywhere)
-----------------------------------------------------------------------------------
Total:                                     ~35 min
```

If short on time, shorten Phase 4 (ranking) and Phase 6 (tradeoffs). Never skip Phase 3 (Trie deep dive) -- that's the core of the interview.
