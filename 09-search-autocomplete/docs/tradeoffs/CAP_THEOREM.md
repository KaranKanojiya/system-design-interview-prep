# CAP Theorem & Distributed Tradeoffs in the Search Autocomplete System

> Interview-ready reference for a Senior Java developer.
> A search autocomplete system is firmly AP -- stale suggestions are perfectly acceptable, but unavailability means a broken search bar.
> Users tolerate "yesterday's trending query" but NOT a blank dropdown.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| CAP Classification | AP -- availability + partition tolerance, eventual consistency |
| Why AP | Stale suggestions are invisible to users; blank dropdown is catastrophic |
| Consistency Model | Eventual consistency for suggestion rankings |
| Network Partition Scenarios | Users get cached/stale suggestions -- still useful |
| Trie Rebuild Consistency | Blue-green deployment, atomic swap |
| PACELC Extension | When no partition: EL (favor latency over consistency) |
| Comparison with Search Systems | Elasticsearch, Solr, Algolia |
| Per-Component CAP Analysis | Trie, cache, query logs, config |
| Interview Q&A | Ready-to-use answers |

---

## CAP Classification: This Is an AP System

```
         Consistency (C)
            /\
           /  \
          /    \
         /      \
        /   AP   \  <--- AUTOCOMPLETE (stale suggestions OK)
       /____________\
  Availability (A) --- Partition Tolerance (P)
```

### Why AP for Autocomplete?

| Question | Answer |
|----------|--------|
| What happens if suggestions are 5 minutes stale? | User doesn't notice -- "weather" is still the top suggestion for "wea" |
| What happens if suggestions are 1 hour stale? | User doesn't notice -- trending queries shift slowly |
| What happens if suggestions are unavailable? | Search bar shows blank dropdown -- user thinks search is BROKEN |
| What happens if suggestions are inconsistent across nodes? | Node A shows "weather" at rank 1, Node B shows "weather forecast" at rank 1 -- user doesn't care |

### The Key Insight

```
  AUTOCOMPLETE SUGGESTIONS ARE INHERENTLY STALE

  Why? The suggestion ranking is based on past search frequencies.
  By definition, we're showing what people searched BEFORE, not right now.

  Timeline:
  =========
  t=0      "world cup" becomes trending (event just started)
  t=1 min  Query logs start accumulating "world cup" searches
  t=5 min  Trie rebuilds with updated frequencies
  t=6 min  "world cup" appears in autocomplete for "wor"

  The 6-minute delay is INVISIBLE to users.
  They don't know (or care) that "world cup" wasn't suggested 3 minutes ago.

  Compare to ride-sharing (CP for ride state):
  - A 6-minute delay in ride assignment = TWO riders assigned to ONE driver = disaster
  - A 6-minute delay in autocomplete = user types 2 extra characters = trivially OK
```

---

## Consistency Model: Eventual Consistency

### What "Eventual" Means for Autocomplete

```
  +-----------------------------------------------------------------+
  |                 EVENTUAL CONSISTENCY FLOW                        |
  +-----------------------------------------------------------------+
  |                                                                 |
  |  User searches "world cup"                                      |
  |        |                                                        |
  |        v                                                        |
  |  (1) Query logged to Kafka                                      |
  |        |                                                        |
  |        v                                                        |
  |  (2) Query aggregator batches logs (every 1-5 minutes)          |
  |        |                                                        |
  |        v                                                        |
  |  (3) Frequency map updated: "world cup" count +1                |
  |        |                                                        |
  |        v                                                        |
  |  (4) Trie rebuild triggered (every 15-60 minutes)               |
  |        |  OR incremental update (every 1-5 minutes)             |
  |        v                                                        |
  |  (5) New trie swapped in (blue-green)                           |
  |        |                                                        |
  |        v                                                        |
  |  (6) Cache invalidated for affected prefixes                    |
  |        |                                                        |
  |        v                                                        |
  |  (7) Next user typing "wor" sees "world cup" in suggestions     |
  |                                                                 |
  |  Total delay: 5-60 minutes (TOTALLY ACCEPTABLE for autocomplete)|
  +-----------------------------------------------------------------+
```

### Consistency Windows by Data Type

| Data | Consistency Window | Why Acceptable |
|------|-------------------|----------------|
| Query frequency counts | 1-5 minutes | "weather" having 9,999 vs 10,000 searches makes no visible difference |
| Suggestion rankings | 5-60 minutes | Top-10 changes slowly; "apple" doesn't suddenly drop from rank 1 |
| Profanity blocklist | Seconds (push-based) | New blocked terms propagated via config push, near-instant |
| Trie structure | 15-60 minutes (rebuild) | Structural changes batched for efficiency |
| Cache entries | TTL-based (5-10 min) | Stale cache = slightly outdated top-10, still 95% accurate |
| Personalization data | 1-5 minutes | User's history from 5 minutes ago is still relevant |

---

## Network Partition Scenarios

### Scenario 1: Partition Between Load Balancer and Trie Servers

```
  User          Load Balancer          Trie Server A     Trie Server B
    |                |                      |                  |
    | (1) type "wea" |                      |                  |
    |--------------->|                      |                  |
    |                |                      |                  |
    |                |  PARTITION            |                  |
    |                |------XXXXX---------->|                  |
    |                |  (can't reach A)     |                  |
    |                |                      |                  |
    |                | (2) route to B       |                  |
    |                |  (healthy server)    |                  |
    |                |-------------------------------------------->|
    |                |                      |                  |
    |                |  [weather, wear,     |                  |
    |                |   weapons, wealth]   |                  |
    |                |<--------------------------------------------|
    |                |                      |                  |
    |  suggestions   |                      |                  |
    |<---------------|                      |                  |
    |                |                      |                  |
    | USER SEES RESULTS -- NEVER KNEW A WAS DOWN               |

  AP Behavior:
  - Load balancer routes around failed node
  - Server B has its own trie replica (might be slightly stale)
  - User gets suggestions -- slightly different ranking, but functional
```

### Scenario 2: Partition Between Trie Servers and Query Log Pipeline

```
  User Types        Trie Server            Kafka (Query Logs)     Aggregator
    |                   |                       |                     |
    | (1) "weather"     |                       |                     |
    |   (search)        |                       |                     |
    |------------------>|                       |                     |
    |  suggestions      |                       |                     |
    |<------------------|                       |                     |
    |                   |                       |                     |
    |                   | (2) log query         |                     |
    |                   |   "weather"           |                     |
    |                   |------XXXXX----------->|                     |
    |                   |  PARTITION             |                     |
    |                   |  (log lost!)          |                     |
    |                   |                       |                     |
    |                   |                       |                     |

  Impact:
  - Serving path is UNAFFECTED (trie serves from memory)
  - Query frequency update is lost
  - "weather" count is 999,999 instead of 1,000,000
  - Ranking impact: ZERO (difference is negligible)
  - Next rebuild uses slightly stale frequencies -- nobody notices
```

### Scenario 3: Partition During Trie Rebuild

```
  Trie Server A          Trie Build Service         Trie Server B
       |                        |                        |
       | (has trie v42)         |                        | (has trie v42)
       |                        |                        |
       |                        | (1) Build trie v43     |
       |                        |   from latest query    |
       |                        |   frequencies          |
       |                        |                        |
       | (2) Deploy v43         |                        |
       |<-----------------------|                        |
       | (swapped to v43)       |                        |
       |                        |                        |
       |                        | (3) Deploy v43         |
       |                        |------XXXXX------------>|
       |                        |  PARTITION              |
       |                        |  (B still on v42!)     |
       |                        |                        |
       |                        |                        |

  Impact:
  - Server A: trie v43 (latest frequencies)
  - Server B: trie v42 (frequencies from 1 hour ago)
  - User routed to A sees: "app store" at rank 1
  - User routed to B sees: "apple" at rank 1
  - BOTH are acceptable! Neither user notices the difference.
  - When partition heals, B gets v43 and they converge.
```

### Scenario 4: Total Cache Failure

```
  User Types        Autocomplete Service        Cache (DOWN)         Trie
    |                      |                        |                  |
    | (1) "app"            |                        |                  |
    |--------------------->|                        |                  |
    |                      | (2) cache.get("app")   |                  |
    |                      |----------------------->|                  |
    |                      |   CONNECTION REFUSED    |                  |
    |                      |<-----------------------|                  |
    |                      |                        |                  |
    |                      | (3) FALLBACK: query    |                  |
    |                      |   trie directly        |                  |
    |                      |--------------------------------------->|
    |                      |   [app store, apple,   |                  |
    |                      |    application, ...]   |                  |
    |                      |<---------------------------------------|
    |                      |                        |                  |
    |  suggestions         |                        |                  |
    |  (slower, but works!)|                        |                  |
    |<---------------------|                        |                  |

  AP Behavior:
  - Cache miss falls through to trie
  - Latency increases from 2ms to 20ms (still under 100ms SLA)
  - User sees results -- never knew cache was down
  - This is why we choose AP: degrade gracefully, never fail completely
```

---

## Trie Rebuild Consistency: Blue-Green Deployment

### The Problem

A full trie rebuild takes 5-30 minutes (for 200M+ queries). We cannot lock the serving trie during rebuild -- that would mean zero autocomplete for 30 minutes.

### Blue-Green Trie Swap

```
  +------------------------------------------------------------------------+
  |                    BLUE-GREEN TRIE DEPLOYMENT                          |
  +------------------------------------------------------------------------+
  |                                                                        |
  |  PHASE 1: Build new trie offline                                       |
  |  ==========================================                            |
  |                                                                        |
  |  Serving (BLUE trie v42)          Build (GREEN trie v43)               |
  |  +-------------------+           +-------------------+                 |
  |  |  "app" -> [apple, |           |  Building from    |                 |
  |  |   app store, ...] |           |  latest query     |                 |
  |  |  "wea" -> [weather|           |  frequencies...   |                 |
  |  |   wearing, ...]   |           |  50% complete...  |                 |
  |  +-------------------+           |  80% complete...  |                 |
  |   (serving all traffic)          +-------------------+                 |
  |                                   (building in background)             |
  |                                                                        |
  |  PHASE 2: Atomic swap                                                  |
  |  ==========================================                            |
  |                                                                        |
  |  +-------------------+           +-------------------+                 |
  |  |  BLUE trie v42    |           |  GREEN trie v43   |                 |
  |  |  (old)            |           |  (new, ready)     |                 |
  |  +-------------------+           +-------------------+                 |
  |           |                               |                            |
  |           |    ATOMIC REFERENCE SWAP       |                            |
  |           |    currentTrie.set(green)      |                            |
  |           |<------------------------------>|                            |
  |           |                               |                            |
  |  +-------------------+           +-------------------+                 |
  |  |  BLUE trie v42    |           |  GREEN trie v43   |                 |
  |  |  (GC candidate)   |           |  (NOW SERVING)    |                 |
  |  +-------------------+           +-------------------+                 |
  |                                                                        |
  |  PHASE 3: Cleanup                                                      |
  |  ==========================================                            |
  |  - Invalidate caches (prefixes may have new top-K)                     |
  |  - Blue trie garbage collected after in-flight requests drain           |
  |  - Log swap event for monitoring                                        |
  +------------------------------------------------------------------------+
```

### Java Implementation -- Atomic Trie Swap

```java
public class TrieManager {
    // AtomicReference ensures atomic swap -- no locking needed
    private final AtomicReference<Trie> currentTrie = new AtomicReference<>();
    private final SuggestionCache cache;

    public TrieManager(Trie initialTrie, SuggestionCache cache) {
        this.currentTrie.set(initialTrie);
        this.cache = cache;
    }

    // (1) Serving path reads current trie -- lock-free
    public Trie getCurrentTrie() {
        return currentTrie.get();
    }

    // (2) Rebuild path swaps atomically
    public void swapTrie(Trie newTrie) {
        Trie oldTrie = currentTrie.getAndSet(newTrie);
        // (3) Invalidate all cached suggestions (new trie = new rankings)
        cache.invalidateAll();
        // (4) Old trie will be GC'd after in-flight requests finish
        log.info("Trie swapped: {} entries -> {} entries",
            oldTrie.size(), newTrie.size());
    }
}
```

### Numbered Call Chain -- Trie Rebuild and Swap

```
  Scheduler       TrieRebuildService     QueryRepository     TrieManager       SuggestionCache
     |                  |                      |                  |                   |
     | (1) trigger      |                      |                  |                   |
     |   rebuild()      |                      |                  |                   |
     |----------------->|                      |                  |                   |
     |                  | (2) getTopQueries     |                  |                   |
     |                  |   (200_000)           |                  |                   |
     |                  |--------------------->|                  |                   |
     |                  |   [("weather", 10M), |                  |                   |
     |                  |    ("facebook", 9M), |                  |                   |
     |                  |    ...]              |                  |                   |
     |                  |<---------------------|                  |                   |
     |                  |                      |                  |                   |
     |                  | (3) build new trie    |                  |                   |
     |                  |   (insert 200K words) |                  |                   |
     |                  |   (5-30 minutes)      |                  |                   |
     |                  |                      |                  |                   |
     |                  | (4) swapTrie          |                  |                   |
     |                  |   (newTrie)           |                  |                   |
     |                  |--------------------------------------------->|                   |
     |                  |                      |                  |                   |
     |                  |                      |                  | (5) atomic swap    |
     |                  |                      |                  |   currentTrie =    |
     |                  |                      |                  |   newTrie           |
     |                  |                      |                  |                   |
     |                  |                      |                  | (6) cache          |
     |                  |                      |                  |   .invalidateAll() |
     |                  |                      |                  |------------------>|
     |                  |                      |                  |                   |
     |  rebuild done    |                      |                  |                   |
     |<-----------------|                      |                  |                   |
```

---

## PACELC Extension

When there is no partition, we still have to choose between Latency and Consistency.

```
  PACELC for Autocomplete:

  P (Partition)     -> A (Availability)   : Serve stale suggestions, never go down
  E (Else/Normal)   -> L (Latency)        : Serve from cache in 2ms, don't wait for consensus

  Full classification: PA/EL

  +-------------------------------------------------------------------+
  |  PACELC CLASSIFICATION                                            |
  +-------------------------------------------------------------------+
  |                                                                   |
  |  DURING PARTITION:                                                |
  |  +-------------------+     +-------------------+                  |
  |  | Availability (A)  | vs  | Consistency (C)   |                  |
  |  | Serve cached/stale|     | Block until trie  |                  |
  |  | suggestions       |     | is synchronized   |                  |
  |  | *** CHOSEN ***    |     | across all nodes  |                  |
  |  +-------------------+     +-------------------+                  |
  |                                                                   |
  |  DURING NORMAL OPERATION (no partition):                          |
  |  +-------------------+     +-------------------+                  |
  |  | Latency (L)       | vs  | Consistency (C)   |                  |
  |  | Serve from local  |     | Check all nodes   |                  |
  |  | cache in 2ms      |     | for latest trie   |                  |
  |  | *** CHOSEN ***    |     | version before    |                  |
  |  |                   |     | responding        |                  |
  |  +-------------------+     +-------------------+                  |
  |                                                                   |
  +-------------------------------------------------------------------+
```

### Why EL (Latency over Consistency) in Normal Operation?

```
  SCENARIO: Three trie servers, all healthy, no partition.
  User types "wea" -- should we check all 3 servers for the latest trie version?

  OPTION 1: Consistency (EC)
  User -> LB -> Server A: "Do you have latest trie?"
                Server B: "Do you have latest trie?"
                Server C: "Do you have latest trie?"
                Wait for quorum (2/3 agree on latest version)
                Then serve suggestions.
  Latency: 20-50ms (consensus overhead)

  OPTION 2: Latency (EL)  <--- OUR CHOICE
  User -> LB -> Server A: Here are my suggestions (from local trie)
  Latency: 2-5ms

  The 15-45ms saved MATTERS for autocomplete:
  - User types a character every 50-100ms
  - At 50ms response time, suggestions arrive AFTER the next keystroke
  - At 2ms, suggestions feel instant
  - Stale suggestions from an old trie are still 95%+ identical
```

---

## Comparison with Other Search Systems

### Elasticsearch / Solr Completion Suggesters

| Aspect | Our Trie Implementation | Elasticsearch Completion Suggester | Solr Suggester |
|--------|------------------------|-----------------------------------|---------------|
| Data Structure | Compressed Trie (radix tree) | FST (Finite State Transducer) | Trie / Blended (Lucene FST) |
| Storage | In-memory (JVM heap) | In-memory (off-heap, mmap) | In-memory (JVM heap or mmap) |
| Consistency | Eventual (blue-green swap) | Near-real-time (NRT, ~1s refresh) | NRT (~1s soft commit) |
| CAP Choice | AP (stale OK) | AP (NRT = eventually consistent) | AP (NRT = eventually consistent) |
| Replication | Full trie per node | Shard-level replication | Shard-level replication |
| Rebuild Speed | 5-30 min (full rebuild) | Incremental (segment merge) | Incremental (segment merge) |
| Fuzzy Matching | Not built-in | Built-in (edit distance) | Built-in (edit distance) |
| Scalability | Vertical (bigger heap) | Horizontal (sharding) | Horizontal (sharding) |
| Latency | 1-5ms (in-process) | 2-10ms (network hop) | 2-10ms (network hop) |

### Why Not Just Use Elasticsearch?

```
  FOR INTERVIEW PURPOSES:
  =======================

  Interviewer asks: "Why build a custom trie instead of using Elasticsearch?"

  Answer:
  1. LATENCY: In-process trie = 1-2ms. ES = 5-10ms (network hop).
     For autocomplete (50-100ms between keystrokes), this matters.

  2. UNDERSTANDING: Building a trie from scratch demonstrates you understand
     the data structure, prefix matching, and memory tradeoffs.
     "We'd use Elasticsearch" is a valid PRODUCTION answer but doesn't
     show deep understanding.

  3. CUSTOMIZATION: Custom ranking (time-decay, personalization) is trivial
     with our Strategy pattern. ES requires custom scoring scripts.

  4. COST: A simple trie fits in 2-4GB RAM. ES requires a cluster (3+ nodes,
     JVM overhead, Lucene segments, shard management).

  FOR PRODUCTION:
  ==============
  Use Elasticsearch or Algolia. They handle:
  - Fuzzy matching (typo correction)
  - Multi-language tokenization
  - Horizontal scaling and replication
  - Built-in analytics
  - Operational tooling (Kibana, monitoring)
```

### Consistency Comparison Across Systems

```
  +-------------------------------------------------------------------+
  |              CONSISTENCY MODELS COMPARED                           |
  +-------------------------------------------------------------------+
  |                                                                   |
  |  System              | Consistency Model    | Staleness Window    |
  |  ====================|=====================|====================  |
  |  Our Trie (custom)   | Eventual (rebuild)  | 15-60 minutes       |
  |  Elasticsearch       | Near-Real-Time      | ~1 second (refresh) |
  |  Solr                | Near-Real-Time      | ~1 second (commit)  |
  |  Algolia             | Synchronous         | ~0 seconds          |
  |  Redis Sorted Sets   | Strong (single)     | ~0 seconds          |
  |                      | Eventual (cluster)  | milliseconds        |
  |  DynamoDB            | Eventual or Strong  | configurable        |
  |                                                                   |
  |  ALL are acceptable for autocomplete because:                     |
  |  - Even 60-minute staleness is fine for suggestion rankings       |
  |  - The only thing that matters is AVAILABILITY                    |
  |  - Pick the consistency model that matches your rebuild strategy  |
  +-------------------------------------------------------------------+
```

---

## Per-Component CAP Analysis

Not all components in the autocomplete system make the same CAP choice.

### Component Breakdown

| Component | CAP Choice | Consistency Model | Why |
|-----------|-----------|-------------------|-----|
| Trie (serving) | **AP** | Eventual (rebuild cycle) | Stale suggestions OK; unavailability = broken search |
| Suggestion Cache | **AP** | TTL-based eviction | Stale cache entry = old top-10; cache miss = trie fallback |
| Query Logs (Kafka) | **AP** | At-least-once delivery | Lost log = one less count of "weather"; duplicated log = one extra count |
| Query Frequency Aggregation | **AP** | Eventual (batch aggregation) | Frequency 999,999 vs 1,000,000 makes zero ranking difference |
| Profanity Blocklist | **CP-ish** | Push-based, near-instant | Blocked content must be filtered consistently (legal/compliance) |
| User Personalization | **AP** | Eventual (1-5 min) | User history from 5 min ago is still relevant |
| Trie Build Service | **CP** | Single writer | Only one process builds the trie at a time (no conflicting builds) |

### The One CP-ish Component: Profanity Filter

```
  WHY PROFANITY FILTER LEANS TOWARD CP:

  Scenario: A new offensive term is added to the blocklist.
  - If Node A has the updated blocklist but Node B doesn't:
    - User on Node A: doesn't see offensive suggestion (GOOD)
    - User on Node B: sees offensive suggestion (BAD -- legal risk)

  Solution: Push-based config update with short consistency window.
  - Config service pushes blocklist updates to all nodes
  - Nodes acknowledge receipt
  - Consistency window: seconds, not minutes
  - If a node can't be reached: it's better to take it out of rotation
    than to serve unfiltered suggestions (CP behavior)
```

---

## Consistency During Trie Rebuild: Deep Dive

### The Consistency Challenge

```
  During a 30-minute trie rebuild:
  
  t=0 min    Start building trie v43 from latest frequencies
  t=10 min   User searches "world cup" 500,000 times (World Cup just started!)
  t=30 min   Trie v43 is ready -- but it was built from t=0 frequencies
             "world cup" has t=0 frequency, not t=30 frequency!

  IS THIS A PROBLEM?
  
  Answer: No. 
  
  t=30 min   Trie v43 deployed. "world cup" shows but at lower rank.
  t=60 min   Next rebuild (v44) includes all searches from t=0 to t=60.
             "world cup" now has correct high frequency.
  t=61 min   Trie v44 deployed. "world cup" at correct rank.

  Convergence time: ONE rebuild cycle (15-60 minutes).
  Impact during convergence: trending query at slightly lower rank.
  User perception: "world cup" still appears in suggestions, just maybe
  at position 5 instead of position 1. Users still find what they need.
```

### Incremental Updates (Optimization)

```
  Instead of full rebuilds, we can do INCREMENTAL trie updates:

  +-------------------------------------------------------------------+
  |  INCREMENTAL UPDATE FLOW                                          |
  +-------------------------------------------------------------------+
  |                                                                   |
  |  Full Rebuild (every 60 min):                                     |
  |  - Rebuild entire trie from scratch                               |
  |  - Atomic swap (blue-green)                                       |
  |  - Consistency window: 60 minutes                                 |
  |                                                                   |
  |  Incremental Update (every 1-5 min):                              |
  |  - Read latest frequency deltas from aggregator                   |
  |  - Update frequency counts in current trie                        |
  |  - Re-sort top-K for affected prefixes                            |
  |  - Invalidate affected cache entries                              |
  |  - Consistency window: 1-5 minutes                                |
  |                                                                   |
  |  Hybrid approach:                                                 |
  |  - Incremental updates for frequency changes (fast, frequent)     |
  |  - Full rebuild for structural changes (new words, deleted words) |
  |  - Best of both: 1-5 min freshness + clean structure hourly       |
  +-------------------------------------------------------------------+
```

---

## Interview Q&A

| Question | Answer |
|----------|--------|
| "Is autocomplete CP or AP?" | "Firmly AP. Stale suggestions are invisible to users -- 'weather' is still a good suggestion for 'wea' whether its frequency count is 10M or 10.1M. But if autocomplete is unavailable, the entire search experience feels broken. We choose availability over consistency." |
| "How do you handle stale data?" | "Suggestions are inherently stale -- they're based on past searches. We use eventual consistency with a 5-60 minute convergence window. Cache has 5-10 minute TTL. Even during network partitions, each node serves from its local trie and cache -- users get suggestions, just potentially slightly outdated rankings." |
| "What about the profanity filter?" | "That's the one component that leans toward CP. If a new offensive term is blocked, all nodes must respect it quickly. We use push-based config updates with acknowledgment. If a node can't be reached during a blocklist update, we remove it from the serving pool rather than risk serving unfiltered content." |
| "How do you rebuild the trie without downtime?" | "Blue-green deployment. We build the new trie offline (5-30 minutes) while the old trie continues serving. When the new trie is ready, we do an atomic swap via AtomicReference -- zero downtime, zero locking. In-flight requests drain on the old trie, then it's garbage collected." |
| "Why not use Elasticsearch?" | "For an interview, building a custom trie shows understanding of prefix matching, memory tradeoffs, and ranking strategies. For production, Elasticsearch is excellent -- it provides near-real-time consistency (~1s refresh), fuzzy matching, and horizontal scaling. But its 5-10ms latency (network hop) vs our 1-2ms (in-process) matters when users type every 50ms." |
| "What happens during a network partition?" | "Each trie server serves from its local trie and cache. Users might see slightly different rankings depending on which server they hit (trie v42 vs v43). Both are acceptable. When the partition heals, lagging servers receive the latest trie version and converge. The query log pipeline might lose some events during partition, but losing one count of 'weather' out of 10M is negligible." |
| "How does this compare to 01-URL Shortener?" | "URL shortener read-path is also AP (stale redirect cache is fine). But URL creation is CP (no duplicate short codes). Autocomplete is fully AP -- even the write path (query logging) is AP because losing a single query log is negligible." |
| "PACELC classification?" | "PA/EL. During partition: availability over consistency. During normal operation: latency over consistency. We serve from local cache in 2ms rather than checking all nodes for the latest trie version. The 15-45ms saved matters when users type every 50-100ms." |

---

## Cross-Reference: CAP Choices Across Projects

| Project | CAP Choice | Why |
|---------|-----------|-----|
| 01 - URL Shortener | Read=AP, Write=CP | Stale redirect cache OK; duplicate codes not OK |
| 02 - Rate Limiter | AP (with CP option) | Slightly over-limit OK; blocking availability not OK |
| 04 - Chat System | CP for delivery, AP for presence | Missed message not OK; stale "online" status OK |
| 07 - Distributed Cache | AP | Stale cache entry OK; cache unavailability defeats purpose |
| 08 - Ride Sharing | Split: CP rides, AP location | Double-booking not OK; stale GPS OK |
| **09 - Autocomplete** | **AP (fully)** | **Stale suggestions OK; blank dropdown not OK** |
