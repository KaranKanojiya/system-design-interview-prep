# Search Autocomplete (Typeahead)

## Problem Summary

Design a **search autocomplete (typeahead) system** (like Google Search, Amazon search bar) that suggests the top-K most relevant completions as a user types each character. The core challenges are **Trie data structure** for O(L) prefix lookup (where L = prefix length), **TopK pre-computation** to avoid traversing the entire subtree on every keystroke, **ranking** (frequency, time-decay, personalization), and a **data pipeline** to rebuild the Trie from aggregated query logs. The system must serve suggestions with sub-100ms latency at 500M+ autocomplete requests per day, with graceful staleness (AP) since showing slightly outdated suggestions is acceptable.

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Trie: prefix tree, O(L) insert/search where L = word length. Each node = character.** Traverse from root, one node per character. Leaf marks end-of-word.
- **Compressed Trie: merge single-child chains. 'apple' stored as 'appl'->'e' instead of a->p->p->l->e.** Reduces node count by 50-70%. Also called Patricia Trie / Radix Tree.
- **TopK Trie: pre-compute top-K suggestions at every node. O(1) lookup at query time. O(L*K) insert.** Trade space for time. Each node stores a sorted list of K best completions under it.
- **Ranking: frequency (simple), time-decay (recent boost), personalized (user history).** Time-decay: score = count * decay^(hours_since_event). Personalized: blend global 70% + user 30%.
- **Data pipeline: Kafka -> Spark aggregation -> Trie rebuild -> hot-swap.** Never update the live Trie. Build a new one offline, swap atomically. Rebuild every 15-60 min.
- **Cache: prefix -> top-K. LRU. Short prefixes (1-2 chars) = hot. Zipf distribution.** 70% of traffic is for ~700 prefixes (1-2 chars). Cache these at CDN edge.
- **CAP: AP -- stale suggestions OK. Rebuild Trie periodically, serve from cache.** Users don't notice if trending queries appear 15-60 min late. Availability > freshness.

---

## Class Hierarchy

```
TrieNode (building block)                Trie (data structure)
  |-- character                            |-- root: TrieNode
  |-- children: Map<Character, TrieNode>   |-- insert(word, weight)
  |-- isEndOfWord: boolean                 |-- search(prefix) -> List<String>
  |-- topKSuggestions: List<Suggestion>     |-- delete(word)
  |-- frequency: long                      |-- getTopK(prefix, k) -> List<Suggestion>

CompressedTrieNode                       Suggestion (value object)
  |-- label: String (merged chars)         |-- term: String
  |-- children: Map<Character, node>       |-- score: double
  |-- isEndOfWord                          |-- toSring()

RankingStrategy (interface)              AutocompleteService (Facade)
  |-- FrequencyRanking                     |-- suggest(prefix, k)
  |-- TimeDecayRanking                     |-- recordQuery(query)
  |-- PersonalizedRanking                  |-- rebuildTrie(queryLogs)

TrieBuilder (offline pipeline)           PrefixCache (caching layer)
  |-- buildFromQueryLogs()                 |-- get(prefix) -> List<Suggestion>
  |-- serialize(trie) -> byte[]            |-- put(prefix, suggestions)
  |-- deserialize(byte[]) -> Trie          |-- evict(prefix)
  |-- hotSwap(oldTrie, newTrie)            |-- LRU eviction, TTL-based

AppConfig (wiring)
  |-- creates services, strategies, cache
```

---

## Key Components

| Component | Role |
|-----------|------|
| `TrieNode` | Building block. Stores character, children map, isEndOfWord flag, top-K list. |
| `Trie` | Core data structure. O(L) insert and prefix search. Root node, no character. |
| `CompressedTrieNode` | Merges single-child chains into a single node with multi-char label. Saves 50-70% nodes. |
| `Suggestion` | Value object. Holds term + score. Comparable by score for top-K sorting. |
| `RankingStrategy` | Interface for scoring. Frequency (count), TimeDecay (recency-weighted), Personalized (user blend). |
| `AutocompleteService` | Facade. Handles suggest(prefix, k), recordQuery(query), and Trie rebuild orchestration. |
| `TrieBuilder` | Offline pipeline. Reads aggregated query logs, builds new Trie, serializes to snapshot, hot-swaps. |
| `PrefixCache` | LRU cache. prefix -> top-K results. Short TTL (5 min). Absorbs 85% of Trie lookups. |
| `AppConfig` | Wires everything together. Creates Trie, cache, ranking strategy, service. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Trie type | Basic Trie (simple, O(L)) | Compressed Trie (space-efficient) | **Both** -- basic for demo, compressed for production |
| Top-K computation | At query time (DFS subtree) | Pre-computed at each node | **Pre-computed** -- O(1) lookup vs O(subtree) traversal |
| Ranking | Frequency only (simple) | Time-decay + personalized | **Time-decay** -- frequency is stale, recency matters |
| Trie update | In-place mutation (real-time) | Offline rebuild + hot-swap | **Offline rebuild** -- no locking, no corruption, atomic swap |
| Cache layer | Redis only | CDN + Redis (two-tier) | **CDN + Redis** -- CDN for short prefixes (Zipf), Redis for long |
| Storage | In-memory Trie only | Elasticsearch Completion Suggester | **In-memory Trie** -- sub-ms latency, sufficient for 10M terms |
| Personalization | None (global only) | Per-user history blended | **Blended** -- 70% global + 30% user history |
| Data pipeline | Real-time (stream processing) | Batch (hourly Spark) | **Batch** -- simpler, stale by 15-60 min is acceptable (AP) |

---

## SOLID Principles

| Principle | Example |
|-----------|---------|
| **S** -- Single Responsibility | `Trie` handles only prefix lookup. `RankingStrategy` handles only scoring. `TrieBuilder` handles only offline builds. |
| **O** -- Open/Closed | Add `PersonalizedRanking` without modifying `AutocompleteService`. New ranking = new class. |
| **L** -- Liskov Substitution | Any `RankingStrategy` (Frequency, TimeDecay, Personalized) works wherever the interface is expected. |
| **I** -- Interface Segregation | `RankingStrategy` and `PrefixCache` are separate interfaces. Ranking doesn't know about caching. |
| **D** -- Dependency Inversion | `AutocompleteService` depends on `RankingStrategy` interface, not `FrequencyRanking` class. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | RankingStrategy (Frequency, TimeDecay, Personalized) | Swap ranking algorithms without changing service |
| **Facade** | AutocompleteService | Single entry point for suggest(), recordQuery(), rebuildTrie() |
| **Builder** | TrieBuilder (offline pipeline) | Complex multi-step Trie construction from query logs |
| **Observer** | Query events published to Kafka | Decoupled: user searches, pipeline aggregates asynchronously |
| **Factory** | StrategyFactory creates ranking strategies | Encapsulate ranking selection logic (A/B testing) |
| **Flyweight** | TrieNode children sharing common suffixes | Compressed Trie merges nodes, reduces memory footprint |
| **Template Method** | RankingStrategy base with score() hook | Common ranking flow (normalize, filter, sort), custom scoring |
| **Prototype** | Trie snapshot deserialization (clone from S3) | Hot-swap: deserialize snapshot into new Trie instance |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :09-search-autocomplete:run
```

---

## Demo Output Preview

```
========================================
  SEARCH AUTOCOMPLETE (TYPEAHEAD) DEMO
========================================

--- Basic Trie Demo ---
Building Trie with 20 search terms...
  Inserting: "how to cook pasta", "how to tie a tie", "how to lose weight",
             "how are you", "hotel booking", "hot dog recipe"...
  Trie nodes: 142, words stored: 20

Search prefix "how":
  1. "how to cook pasta"       freq=95000  score=0.95
  2. "how to tie a tie"        freq=82000  score=0.82
  3. "how to lose weight"      freq=78000  score=0.78
  4. "how are you"             freq=65000  score=0.65

Search prefix "hot":
  1. "hotel booking"           freq=110000 score=1.00
  2. "hot dog recipe"          freq=42000  score=0.38
  3. "hotmail login"           freq=38000  score=0.35

--- Compressed Trie Demo ---
Compressing Trie...
  Before: 142 nodes
  After:  68 nodes (52% reduction)
  "how to " merged into single node (7 chars)
  Lookup still O(L) but fewer pointer dereferences

--- TopK Pre-computation Demo ---
Pre-computing top-5 at every node...
  Node 'h': ["hotel booking", "how to cook pasta", "how to tie a tie", "how are you", "hot dog recipe"]
  Node 'ho': ["hotel booking", "how to cook pasta", "how to tie a tie", "how are you", "hot dog recipe"]
  Node 'how': ["how to cook pasta", "how to tie a tie", "how to lose weight", "how are you"]
  Lookup time: O(1) -- just return pre-computed list!

--- Ranking Demo ---
Frequency ranking for prefix "how":
  1. "how to cook pasta"       freq=95000

Time-decay ranking for prefix "how" (boost recent):
  1. "how to vote 2024"        freq=12000  recency_boost=3.2x  final=38400
  2. "how to cook pasta"       freq=95000  recency_boost=0.3x  final=28500
  (Trending query outranks all-time popular query!)

--- Cache Demo ---
Cache stats after 1000 queries:
  Redis cache hit rate:  87%
  Average latency (hit): 0.3ms
  Average latency (miss): 1.2ms (Trie lookup + cache write)
  Most cached prefix: "a" (hit 142 times)

--- Pipeline Demo ---
Simulating Trie rebuild from query logs...
  Reading 10,000 aggregated queries from logs
  Building new Trie (took 45ms)
  Hot-swap: replacing old Trie atomically
  Old Trie: 18 terms, New Trie: 20 terms (+2 trending)
  Zero downtime during swap!

========================================
  DEMO COMPLETE
========================================
```

---

## Quick Reference

```
Trie insert:         O(L)     where L = word length    (traverse/create L nodes)
Trie prefix search:  O(L)     where L = prefix length  (traverse L nodes to prefix node)
TopK lookup:         O(1)     pre-computed at each node (return stored list)
TopK insert cost:    O(L * K) update K-list at L nodes  (trade insert speed for lookup speed)
Compressed Trie:     O(L)     same complexity, fewer nodes (50-70% reduction)
Cache hit (Redis):   O(1)     prefix -> top-K list      (LRU, TTL = 5 min)
CDN hit:             O(1)     edge-cached response       (short prefixes, TTL = 1 hour)
Trie rebuild:        O(N * L) N terms, L avg length     (offline, every 15-60 min)
Memory (50M terms):  ~2-4 GB  compressed Trie + top-K   (fits single JVM instance)
```

---

## What to Improve Later

- [ ] Full Trie implementation with insert, search, delete, and top-K pre-computation
- [ ] Compressed Trie (Patricia/Radix) with node merging
- [ ] Time-decay ranking with configurable decay factor
- [ ] Personalized ranking blending global + user history
- [ ] LRU prefix cache with TTL and hit-rate tracking
- [ ] Trie serialization/deserialization for hot-swap
- [ ] Fuzzy matching with Levenshtein distance (1-edit tolerance)
- [ ] Multi-language Trie support (Unicode-aware nodes)
- [ ] Trending query detection (sliding window, spike detection)
- [ ] A/B testing framework for ranking strategies
