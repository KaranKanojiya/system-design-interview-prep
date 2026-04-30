# Technologies & Infrastructure for the Search Autocomplete System

> Interview-ready reference for a Senior Java developer.
> A search autocomplete system sits at the intersection of tree data structures, caching, and real-time streaming.
> Know Trie internals, compression tradeoffs, memory estimation, and production alternatives.

---

## Table of Contents

| Technology | Why It's Here | Interview Relevance |
|------------|--------------|---------------------|
| Trie Data Structure | Basic, Compressed (Radix), Ternary Search Tree | HIGH -- core of "find completions for prefix" |
| Comparison Table | Trie vs HashMap vs Sorted Array vs B-Tree | HIGH -- asked in every autocomplete interview |
| Elasticsearch/Solr | Production completion suggesters | MEDIUM -- compare to our in-memory approach |
| Redis | Caching suggestions + sorted sets for ranking | HIGH -- common cache layer |
| Kafka | Query log streaming for frequency updates | MEDIUM -- async architecture |
| Java Implementation Details | HashMap children, StringBuilder, AtomicReference | HIGH -- implementation details |
| Memory Analysis | Trie memory for 200M queries | HIGH -- capacity planning |

---

## 1. Trie Data Structure: The Core Problem

### The Problem

```
  Given: 200 million distinct search queries
  Query: User types "app" -- return top 10 completions in <5ms

  BRUTE FORCE:
  for each query in 200,000,000:
      if query.startsWith("app"):
          candidates.add(query)
  sort candidates by frequency
  return top 10

  Time: O(n * m) where n=200M queries, m=avg query length
  At 10,000 QPS = 2 trillion string comparisons/second
  This doesn't scale.

  TRIE:
  Walk to node "a" -> "p" -> "p" in O(m) time (m = prefix length = 3)
  All descendants are completions
  With top-K stored at each node: O(m) for the full query

  Time: O(m) per query, independent of dataset size
  At 10,000 QPS = 30,000 character lookups/second -- trivial
```

---

## 2. Standard Trie (Basic)

### What Is a Trie?

A Trie (prefix tree / digital tree) is a tree data structure where each node represents a single character. The path from root to any node spells out a prefix. Nodes marked as "end of word" represent complete search queries.

### ASCII Diagram -- Standard Trie

```
  Insert: "app" (freq=500), "apple" (freq=800), "application" (freq=300),
          "api" (freq=400), "ape" (freq=100)

  Root
   |
   a (not a word)
   |
   p (not a word)
   |
   +--- p (word: "app", freq=500)
   |    |
   |    +--- l (not a word)
   |         |
   |         +--- e (word: "apple", freq=800)
   |         |
   |         +--- i (not a word)
   |              |
   |              +--- c (not a word)
   |                   |
   |                   +--- a (not a word)
   |                        |
   |                        +--- t (not a word)
   |                             |
   |                             +--- i (not a word)
   |                                  |
   |                                  +--- o (not a word)
   |                                       |
   |                                       +--- n (word: "application", freq=300)
   |
   +--- i (word: "api", freq=400)
   |
   +--- e (word: "ape", freq=100)
```

### TrieNode Structure

```
  +----------------------------+
  | TrieNode                   |
  +----------------------------+
  | - children: Map<Char, Node>|  (HashMap for O(1) child lookup)
  | - isEndOfWord: boolean     |  (marks complete query)
  | - frequency: long          |  (search count)
  | - word: String             |  (stored at end-of-word nodes)
  +----------------------------+
```

### Java Implementation -- Standard Trie

```java
public class TrieNode {
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isEndOfWord = false;
    long frequency = 0;
    String word = null;  // stored only at end-of-word nodes
}

public class StandardTrie implements Trie {
    private final TrieNode root = new TrieNode();
    private int size = 0;

    @Override
    public void insert(String word, long frequency) {
        TrieNode current = root;
        for (char c : word.toCharArray()) {
            current = current.children.computeIfAbsent(c, k -> new TrieNode());
        }
        if (!current.isEndOfWord) {
            size++;
        }
        current.isEndOfWord = true;
        current.frequency = frequency;
        current.word = word;
    }

    @Override
    public List<Suggestion> search(String prefix, int limit) {
        // (1) Walk to the prefix node
        TrieNode node = findNode(prefix);
        if (node == null) return Collections.emptyList();

        // (2) DFS to collect all words under this prefix
        List<Suggestion> results = new ArrayList<>();
        dfs(node, results, limit);
        return results;
    }

    private TrieNode findNode(String prefix) {
        TrieNode current = root;
        for (char c : prefix.toCharArray()) {
            current = current.children.get(c);
            if (current == null) return null;
        }
        return current;
    }

    private void dfs(TrieNode node, List<Suggestion> results, int limit) {
        if (results.size() >= limit) return;
        if (node.isEndOfWord) {
            results.add(new Suggestion(node.word, node.frequency));
        }
        for (TrieNode child : node.children.values()) {
            dfs(child, results, limit);
            if (results.size() >= limit) return;
        }
    }

    @Override
    public int size() { return size; }
}
```

### Time and Space Complexity -- Standard Trie

| Operation | Time | Space |
|-----------|------|-------|
| Insert | O(m) where m = word length | O(m) new nodes |
| Search prefix | O(m + k) where k = results needed | O(k) for result list |
| Find node | O(m) | O(1) |
| Total space | -- | O(N * m * C) where N=words, m=avg length, C=node overhead |

---

## 3. Compressed Trie (Radix Tree / Patricia Tree)

### The Problem with Standard Trie

```
  Standard Trie for "application":
  a -> p -> p -> l -> i -> c -> a -> t -> i -> o -> n
  
  That's 11 nodes for ONE word. Most of these nodes have exactly ONE child.
  They're just wasting memory.

  Compressed Trie:
  "app" -> "lication"
  
  TWO nodes instead of 11. Same lookup semantics.
```

### ASCII Diagram -- Compressed Trie

```
  Insert same words: "app", "apple", "application", "api", "ape"

  Root
   |
   "a" (not a word)
   |
   +--- "p" (not a word)
        |
        +--- "p" (word: "app", freq=500)
        |    |
        |    +--- "l" (not a word)
        |         |
        |         +--- "e" (word: "apple", freq=800)
        |         |
        |         +--- "ication" (word: "application", freq=300)
        |
        +--- "i" (word: "api", freq=400)
        |
        +--- "e" (word: "ape", freq=100)

  STANDARD TRIE: 19 nodes
  COMPRESSED:     9 nodes (53% reduction)
  
  For real data (200M queries), compression is typically 40-70%.
```

### Java Implementation -- Compressed Trie

```java
public class CompressedTrieNode {
    String edge;                                    // edge label (can be multi-char)
    Map<Character, CompressedTrieNode> children = new HashMap<>();
    boolean isEndOfWord = false;
    long frequency = 0;
    String word = null;

    public CompressedTrieNode(String edge) {
        this.edge = edge;
    }
}

public class CompressedTrie implements Trie {
    private final CompressedTrieNode root = new CompressedTrieNode("");
    private int size = 0;

    @Override
    public void insert(String word, long frequency) {
        insertRecursive(root, word, 0, frequency);
    }

    private void insertRecursive(CompressedTrieNode node, String word,
                                  int index, long frequency) {
        if (index == word.length()) {
            node.isEndOfWord = true;
            node.frequency = frequency;
            node.word = word;
            if (!node.isEndOfWord) size++;
            return;
        }

        char firstChar = word.charAt(index);
        CompressedTrieNode child = node.children.get(firstChar);

        if (child == null) {
            // No child for this character -- create new edge with remaining string
            CompressedTrieNode newNode = new CompressedTrieNode(
                word.substring(index));
            newNode.isEndOfWord = true;
            newNode.frequency = frequency;
            newNode.word = word;
            node.children.put(firstChar, newNode);
            size++;
            return;
        }

        // Find common prefix between child's edge and remaining word
        String remaining = word.substring(index);
        int commonLength = commonPrefixLength(child.edge, remaining);

        if (commonLength == child.edge.length()) {
            // Full edge matched -- recurse into child
            insertRecursive(child, word, index + commonLength, frequency);
        } else {
            // Partial match -- split the edge
            splitEdge(node, child, firstChar, remaining, commonLength, word, frequency);
        }
    }

    private void splitEdge(CompressedTrieNode parent, CompressedTrieNode existing,
                            char key, String remaining, int splitAt,
                            String fullWord, long frequency) {
        // Create intermediate node at the split point
        String commonPart = existing.edge.substring(0, splitAt);
        String existingSuffix = existing.edge.substring(splitAt);
        String newSuffix = remaining.substring(splitAt);

        CompressedTrieNode splitNode = new CompressedTrieNode(commonPart);
        parent.children.put(key, splitNode);

        // Move existing node under split node
        existing.edge = existingSuffix;
        splitNode.children.put(existingSuffix.charAt(0), existing);

        // Add new word under split node
        if (newSuffix.isEmpty()) {
            splitNode.isEndOfWord = true;
            splitNode.frequency = frequency;
            splitNode.word = fullWord;
        } else {
            CompressedTrieNode newNode = new CompressedTrieNode(newSuffix);
            newNode.isEndOfWord = true;
            newNode.frequency = frequency;
            newNode.word = fullWord;
            splitNode.children.put(newSuffix.charAt(0), newNode);
        }
        size++;
    }

    private int commonPrefixLength(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        for (int i = 0; i < minLen; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return minLen;
    }
}
```

---

## 4. Ternary Search Tree (TST)

### What Is a TST?

A Ternary Search Tree is a hybrid between a Trie and a BST. Each node has three children: left (less than), middle (equal), right (greater than). It uses less memory than a standard Trie when the alphabet is large.

### ASCII Diagram -- TST

```
  Insert: "app", "apple", "api", "ape"
  (middle child = character matches, left/right = BST on character)

                   'a'
                    |
                   'p' (middle)
                   / \
                 'p'  (middle)
                / | \
             (left) | (right)
              'e'  'p'  'i'
                    |
                   (end: "app")
                    |
                   'l'
                    |
                   'e'
                    |
                   (end: "apple")

  Compared to Trie:
  - Trie node: HashMap with 26+ entries (one per possible character)
  - TST node: exactly 3 pointers (left, middle, right)
  - TST uses ~30% less memory for sparse character distributions
```

### When to Use TST vs Trie

| Criterion | Standard Trie | Compressed Trie | TST |
|-----------|--------------|----------------|-----|
| Lookup time | O(m) | O(m) | O(m * log k) where k = alphabet |
| Memory per node | HashMap overhead (~48 bytes) | HashMap + string | 3 pointers (~24 bytes) |
| Best for | Small alphabet (a-z) | Long common prefixes | Large alphabet (Unicode) |
| Implementation | Simple | Moderate (edge splitting) | Moderate (rotations) |
| Use case | English queries | URL completions | Multi-language queries |

---

## 5. Comparison: Trie vs HashMap vs Sorted Array vs B-Tree

This is a common interview question: "Why use a Trie? Why not a simpler data structure?"

### Detailed Comparison Table

| Criterion | Trie | HashMap | Sorted Array | B-Tree |
|-----------|------|---------|-------------|--------|
| **Prefix lookup** | O(m) -- walk m chars | O(n) -- scan all keys | O(n) -- binary search finds start, scan | O(log n + k) -- find start, scan |
| **Exact lookup** | O(m) | O(1) amortized | O(log n) | O(log n) |
| **Insert** | O(m) | O(1) amortized | O(n) -- shift elements | O(log n) |
| **Memory** | HIGH -- node overhead | MEDIUM -- key storage | LOW -- contiguous | MEDIUM -- page overhead |
| **Prefix ordering** | Natural (DFS) | None | Natural (sorted) | Natural (sorted) |
| **Autocomplete fit** | EXCELLENT | POOR | OK for small data | OK for disk-based |

### Why Trie Wins for Autocomplete

```
  SCENARIO: 200M queries, user types "app", need top 10 completions

  TRIE:
  +--------------------------------------------------+
  | 1. Walk 3 chars: root -> 'a' -> 'p' -> 'p'      |
  | 2. All descendants are completions                |
  | 3. Time: O(3) to find prefix node                |
  | 4. Then DFS for top-K suggestions                |
  | 5. Total: O(m + K) where m=3, K=10              |
  +--------------------------------------------------+
  Result: ~13 operations. FAST.

  HASHMAP:
  +--------------------------------------------------+
  | 1. No prefix support! Must scan ALL 200M keys    |
  | 2. for (String key : map.keySet())               |
  |      if (key.startsWith("app")) candidates.add() |
  | 3. Sort candidates by frequency                  |
  | 4. Return top 10                                 |
  +--------------------------------------------------+
  Result: 200M string comparisons. TERRIBLE.

  SORTED ARRAY (binary search):
  +--------------------------------------------------+
  | 1. Binary search for "app" -> find first match   |
  | 2. Scan forward while startsWith("app")          |
  | 3. Could be 500,000 matches for "app"            |
  | 4. Sort 500K by frequency, take top 10           |
  +--------------------------------------------------+
  Result: O(log n) to find start, but O(k) to collect all matches.
  Better than HashMap, worse than Trie.

  B-TREE (disk-based):
  +--------------------------------------------------+
  | 1. Like sorted array but with disk I/O          |
  | 2. Each node = disk page read                    |
  | 3. For prefix: range scan from "app" to "apq"   |
  | 4. Multiple disk reads vs Trie's in-memory walk  |
  +--------------------------------------------------+
  Result: Good for disk-based, not ideal for in-memory autocomplete.
```

### Prefix Lookup: Trie vs Alternatives (Visual)

```
  Query: Find all completions for "app" in dataset of 200M queries

  +-------------------+-------------------+-------------------+
  |       TRIE        |     HASHMAP       |   SORTED ARRAY    |
  +-------------------+-------------------+-------------------+
  |                   |                   |                   |
  | Walk 3 chars      | Scan ALL keys     | Binary search     |
  | a -> p -> p       | "aardvark" no     | [...              |
  |     |             | "about" no        |  "ape"            |
  |  subtree!         | "app" YES         |  "api"            |
  |  only visit       | "apple" YES       |  "app"  <-- found |
  |  "app*" words     | "application" YES |  "apple"          |
  |                   | "banana" no       |  "application"    |
  | Visited: ~500     | "cat" no          |  "applebees"      |
  | nodes under       | "dog" no          |  "apricot"        |
  | "app"             | ... 200M keys     |  ...]             |
  |                   | Visited: 200M     | Scan fwd: ~500K   |
  |                   |                   |                   |
  | Time: O(3+K)      | Time: O(200M)     | Time: O(log200M  |
  |                   |                   |    + 500K)        |
  +-------------------+-------------------+-------------------+
```

---

## 6. Elasticsearch / Solr Completion Suggesters

### Elasticsearch Completion Suggester

```
  HOW ES COMPLETION WORKS:

  Data Structure: FST (Finite State Transducer)
  - Like a compressed trie, but also stores output (weight/frequency)
  - Loaded entirely into memory
  - Supports fuzzy matching (edit distance)

  Indexing:
  PUT /suggestions
  {
    "mappings": {
      "properties": {
        "suggest": {
          "type": "completion",        // <-- special type
          "analyzer": "simple",
          "max_input_length": 50
        }
      }
    }
  }

  // Index a suggestion:
  POST /suggestions/_doc
  {
    "suggest": {
      "input": ["apple", "apple inc", "apple store"],
      "weight": 800                   // <-- frequency
    }
  }

  // Query:
  POST /suggestions/_search
  {
    "suggest": {
      "song-suggest": {
        "prefix": "app",
        "completion": {
          "field": "suggest",
          "size": 10,
          "fuzzy": {
            "fuzziness": 1            // <-- typo tolerance!
          }
        }
      }
    }
  }
```

### Solr Suggester

```
  HOW SOLR SUGGESTER WORKS:

  Config (solrconfig.xml):
  <searchComponent name="suggest" class="solr.SuggestComponent">
    <lst name="suggester">
      <str name="name">mySuggester</str>
      <str name="lookupImpl">AnalyzingInfixLookupFactory</str>
      <str name="dictionaryImpl">DocumentDictionaryFactory</str>
      <str name="field">title</str>
      <str name="weightField">popularity</str>
      <str name="suggestAnalyzerFieldType">text_general</str>
    </lst>
  </searchComponent>

  Query:
  /suggest?suggest=true&suggest.dictionary=mySuggester&suggest.q=app&suggest.count=10

  Lookup Implementations:
  - AnalyzingSuggester: Lucene FST, exact prefix
  - AnalyzingInfixSuggester: matches in the MIDDLE of words
  - BlendedInfixSuggester: weighted by position (prefix > infix)
  - FreeTextSuggester: n-gram based, multi-word queries
```

### Our Trie vs ES vs Solr

| Feature | Our Trie | Elasticsearch | Solr |
|---------|----------|--------------|------|
| Prefix matching | Yes | Yes | Yes |
| Infix matching | No | With `regex` (slow) | AnalyzingInfixSuggester |
| Fuzzy matching | No | Yes (edit distance) | Yes (edit distance) |
| Weighted results | Yes (frequency field) | Yes (weight field) | Yes (weightField) |
| Custom ranking | Strategy pattern | Custom scoring script | Custom comparator |
| Personalization | Decorator pattern | User-specific index | Boost query |
| Latency | 1-2ms (in-process) | 5-10ms (network) | 5-10ms (network) |
| Operational cost | Zero (in-process) | Cluster management | Cluster management |
| Horizontal scaling | Replicate trie per node | Sharding + replicas | Sharding + replicas |

---

## 7. Redis for Caching Suggestions

### Redis as Suggestion Cache

```
  TWO USES OF REDIS IN AUTOCOMPLETE:

  USE 1: Cache layer (prefix -> top-K suggestions)
  ================================================
  Key:   "autocomplete:en:app"
  Value: ["app store", "apple", "application", "applebees", "apple music"]
  TTL:   300 seconds (5 minutes)

  SET autocomplete:en:app '["app store","apple","application"]' EX 300

  USE 2: Sorted set for real-time ranking (alternative to trie)
  ============================================================
  Key:   "queries:prefix:app"
  Members: queries with their frequency as score

  ZADD queries:prefix:app 9000000 "app store"
  ZADD queries:prefix:app 7000000 "apple"
  ZADD queries:prefix:app 5000000 "application"

  ZREVRANGE queries:prefix:app 0 9   -> top 10 by frequency
```

### Redis Cache Architecture

```
  Client          Autocomplete Service        Redis Cache           Trie (in-memory)
    |                    |                        |                       |
    | (1) "app"          |                        |                       |
    |------------------>|                        |                       |
    |                    | (2) GET                |                       |
    |                    |  autocomplete:en:app   |                       |
    |                    |----------------------->|                       |
    |                    |                        |                       |
    |                    | CACHE HIT:             |                       |
    |                    |  ["app store", ...]    |                       |
    |                    |<-----------------------|                       |
    |  suggestions       |                        |                       |
    |<------------------|                        |                       |
    |                    |                        |                       |
    | (OR)               |                        |                       |
    |                    | CACHE MISS:            |                       |
    |                    |  (nil)                  |                       |
    |                    |<-----------------------|                       |
    |                    |                        |                       |
    |                    | (3) trie.search        |                       |
    |                    |   ("app", 10)          |                       |
    |                    |----------------------------------------------->|
    |                    |  [suggestions]          |                       |
    |                    |<-----------------------------------------------|
    |                    |                        |                       |
    |                    | (4) SET + EX 300       |                       |
    |                    |  autocomplete:en:app   |                       |
    |                    |  ["app store", ...]    |                       |
    |                    |----------------------->|                       |
    |                    |                        |                       |
    |  suggestions       |                        |                       |
    |<------------------|                        |                       |
```

### Redis Sorted Set Alternative (No Trie)

```
  FOR SIMPLER SYSTEMS (no custom trie):

  Instead of building a trie, use Redis sorted sets directly.
  For each possible prefix, maintain a sorted set of completions.

  Indexing "apple" (frequency 800):
  ZADD prefix:a 800 "apple"
  ZADD prefix:ap 800 "apple"
  ZADD prefix:app 800 "apple"
  ZADD prefix:appl 800 "apple"
  ZADD prefix:apple 800 "apple"

  Query for prefix "app":
  ZREVRANGE prefix:app 0 9    -> top 10 by score

  Pros: Dead simple, O(log n) per query, built-in ranking
  Cons: Massive key space (one sorted set per prefix)
        For 200M queries with avg 15 chars = 3 BILLION keys
        Memory: prohibitive at scale
        
  WHEN TO USE: <1M queries, rapid prototyping, MVP
  WHEN TO AVOID: >10M queries (memory explosion)
```

---

## 8. Kafka for Query Log Streaming

### Architecture

```
  +-----------------------------------------------------------------------+
  |                    QUERY LOG PIPELINE                                  |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  User Search        API Server          Kafka              Aggregator |
  |  ===========        ==========          =====              ========== |
  |                                                                       |
  |  "weather"  -----> log event ------> topic:                           |
  |  "weather"  -----> log event ------> query-logs -----> batch count   |
  |  "facebook" -----> log event ------> (partitioned      every 5 min   |
  |  "weather"  -----> log event ----->   by prefix[0])     |             |
  |  "gmail"    -----> log event ----->                      |             |
  |                                                          v             |
  |                                                   +-------------+     |
  |                                                   | Frequency   |     |
  |                                                   | Map Update  |     |
  |                                                   | "weather":  |     |
  |                                                   |   10,000,003|     |
  |                                                   | "facebook": |     |
  |                                                   |   9,000,001 |     |
  |                                                   +-------------+     |
  |                                                          |             |
  |                                                          v             |
  |                                                   +-------------+     |
  |                                                   | Trie Rebuild|     |
  |                                                   | Service     |     |
  |                                                   | (every 15-  |     |
  |                                                   |  60 min)    |     |
  |                                                   +-------------+     |
  +-----------------------------------------------------------------------+
```

### Kafka Topic Design

```
  Topic: query-logs
  Partitions: 26 (one per first character a-z)
  Replication: 3
  Retention: 7 days

  Message Schema:
  {
    "query": "weather forecast",
    "timestamp": 1700000000000,
    "userId": "u123",           // optional, for personalization
    "location": "US-CA",        // optional, for geo-ranking
    "language": "en",
    "resultCount": 10,
    "selectedSuggestion": "weather forecast tomorrow",
    "latencyMs": 3
  }

  Partitioning Strategy:
  - Key = first character of query (a-z)
  - All "w*" queries go to same partition
  - Enables parallel processing per prefix group
  - Consumer group: "query-aggregator" (one consumer per partition)
```

### Why Kafka for Query Logs?

| Requirement | Kafka Feature |
|-------------|--------------|
| High throughput (10K+ QPS) | Batched writes, partitioned topics |
| Durability | Replicated across brokers |
| Replay | Re-process logs after bug fix or algorithm change |
| Decoupling | Trie serving path doesn't depend on log pipeline |
| Exactly-once (optional) | Idempotent producer + transactional consumer |
| Late arriving data | Retention window handles delayed events |

---

## 9. Java Implementation Details

### Key Java Data Structures Used

| Java Class | Where Used | Why |
|-----------|-----------|-----|
| `HashMap<Character, TrieNode>` | Trie children | O(1) child lookup by character |
| `ConcurrentHashMap<String, QueryRecord>` | InMemoryQueryRepository | Thread-safe frequency storage |
| `LinkedHashMap` (access-order) | LRU suggestion cache | O(1) LRU eviction |
| `AtomicReference<Trie>` | TrieManager (blue-green swap) | Lock-free atomic trie swap |
| `CopyOnWriteArrayList` | Observer listeners | Thread-safe iteration during notification |
| `StringBuilder` | DFS prefix building | Mutable, avoids String concatenation in loop |
| `ArrayDeque` | DFS iterator stack | Non-synchronized Deque, faster than Stack |
| `TreeMap<Character, Node>` | Sorted iteration over children | Alphabetical suggestion ordering |
| `PriorityQueue<Suggestion>` | Top-K selection | O(n log k) top-K without full sort |

### LRU Cache Implementation

```java
public class LRUSuggestionCache implements SuggestionCache {
    private final int maxSize;
    private final long ttlMillis;
    private final Map<String, CacheEntry> cache;

    public LRUSuggestionCache(int maxSize, int ttlSeconds) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlSeconds * 1000L;
        // LinkedHashMap with access-order = true -> LRU behavior
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > maxSize;
            }
        };
    }

    public Optional<List<Suggestion>> get(String prefix) {
        synchronized (cache) {
            CacheEntry entry = cache.get(prefix);
            if (entry == null) return Optional.empty();
            if (System.currentTimeMillis() - entry.createdAt > ttlMillis) {
                cache.remove(prefix);
                return Optional.empty();  // expired
            }
            return Optional.of(entry.suggestions);
        }
    }

    public void put(String prefix, List<Suggestion> suggestions) {
        synchronized (cache) {
            cache.put(prefix, new CacheEntry(suggestions, System.currentTimeMillis()));
        }
    }

    public void invalidateAll() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static class CacheEntry {
        final List<Suggestion> suggestions;
        final long createdAt;

        CacheEntry(List<Suggestion> suggestions, long createdAt) {
            this.suggestions = suggestions;
            this.createdAt = createdAt;
        }
    }
}
```

### Top-K with PriorityQueue

```java
// Efficient top-K selection: O(n log k) instead of O(n log n) full sort
public class TopKSelector {
    public static List<Suggestion> topK(Collection<Suggestion> candidates, int k) {
        // Min-heap of size k -- keeps the k largest elements
        PriorityQueue<Suggestion> minHeap = new PriorityQueue<>(
            Comparator.comparingLong(Suggestion::getFrequency)
        );

        for (Suggestion s : candidates) {
            if (minHeap.size() < k) {
                minHeap.offer(s);
            } else if (s.getFrequency() > minHeap.peek().getFrequency()) {
                minHeap.poll();   // remove smallest
                minHeap.offer(s); // add new larger element
            }
        }

        // Drain heap into list (reverse order for descending frequency)
        List<Suggestion> result = new ArrayList<>(minHeap);
        result.sort(Comparator.comparingLong(Suggestion::getFrequency).reversed());
        return result;
    }
}

// Why PriorityQueue instead of .stream().sorted().limit(k)?
// Stream sort: O(n log n) -- sorts ALL elements, then takes k
// PriorityQueue: O(n log k) -- maintains only k elements
// For n=500,000 and k=10: PriorityQueue is ~4x faster
```

### AtomicReference for Trie Swap

```java
public class TrieManager {
    // Volatile + AtomicReference = lock-free reads, atomic writes
    private final AtomicReference<Trie> currentTrie;

    public TrieManager(Trie initialTrie) {
        this.currentTrie = new AtomicReference<>(initialTrie);
    }

    // HOT PATH: Called on every autocomplete request
    // No lock, no synchronization -- just a volatile read
    public Trie getCurrentTrie() {
        return currentTrie.get();
    }

    // COLD PATH: Called once per rebuild cycle (every 15-60 min)
    public void swapTrie(Trie newTrie) {
        Trie old = currentTrie.getAndSet(newTrie);
        // old trie will be GC'd after in-flight requests finish
        // (requests that already got a reference to old trie will complete)
    }
}

// Why AtomicReference?
// - Reads are lock-free (just a volatile read)
// - Writes are atomic (CAS operation)
// - In-flight requests on old trie are NOT interrupted
// - New requests get new trie -- zero-downtime swap
```

---

## 10. Memory Analysis: Trie for 200M Queries

### Memory Estimation

```
  GIVEN:
  - 200 million distinct search queries
  - Average query length: 15 characters
  - We keep top 200,000 queries in the trie (long tail truncated)

  STANDARD TRIE MEMORY:
  =====================
  Each TrieNode:
    - HashMap<Character, TrieNode>: 48 bytes (empty) + 32 bytes per entry
    - isEndOfWord (boolean): 1 byte (padded to 8)
    - frequency (long): 8 bytes
    - word (String reference): 8 bytes
    - Object header: 16 bytes
    Total per node: ~80 bytes (without children)
    With avg 2 children: ~80 + 2*32 = ~144 bytes

  Total nodes estimate (200K queries, avg 15 chars):
    - Naive: 200K * 15 = 3M nodes
    - With prefix sharing: ~1.5M nodes (50% overlap for common prefixes)
    
  Memory: 1.5M * 144 bytes = ~216 MB

  Plus stored Strings (at end-of-word nodes):
    200K * (40 bytes header + 15*2 bytes chars) = ~14 MB

  TOTAL STANDARD TRIE: ~230 MB

  COMPRESSED TRIE MEMORY:
  =======================
  - 40-60% fewer nodes (shared edges)
  - ~900K nodes instead of 1.5M
  - But each node stores an edge String: ~40 bytes avg
  - Memory per node: ~80 + edge(40) + 2 children = ~184 bytes
  
  Memory: 900K * 184 = ~166 MB
  Plus stored Strings: ~14 MB

  TOTAL COMPRESSED TRIE: ~180 MB (22% less than standard)

  +-----------------------------------------------------------+
  |               MEMORY COMPARISON (200K QUERIES)             |
  +-----------------------------------------------------------+
  | Structure        | Nodes     | Per Node | Total            |
  |-----------------|-----------|----------|------------------|
  | Standard Trie    | 1,500,000 | ~144 B   | ~230 MB          |
  | Compressed Trie  |   900,000 | ~184 B   | ~180 MB          |
  | HashMap (baseline)| 200,000  | ~200 B   | ~40 MB           |
  | Sorted Array     |   200,000 | ~70 B    | ~14 MB           |
  +-----------------------------------------------------------+
  
  Note: HashMap is smallest but can't do prefix lookups!
  Trie is larger but prefix lookup is O(m) vs O(n).
```

### Scaling to Full 200M Queries

```
  WHAT IF WE KEEP ALL 200M QUERIES IN THE TRIE?
  (We don't -- we truncate the long tail -- but for estimation:)

  200M queries, avg 15 chars:
  - Nodes: ~500M (with prefix sharing)
  - Standard Trie: 500M * 144 = ~72 GB (WON'T FIT IN RAM)
  - Compressed Trie: 300M * 184 = ~55 GB (STILL TOO BIG)

  SOLUTION: Keep only top 200K-1M queries in the trie.
  
  Why this works:
  - Query distribution follows Zipf's law
  - Top 200K queries cover ~80% of all searches
  - Long tail queries ("best sushi restaurant near LAX terminal 4")
    are too specific for autocomplete anyway
  - If a query is searched only once, it shouldn't be a suggestion

  +-----------------------------------------------------------+
  |          ZIPF DISTRIBUTION OF SEARCH QUERIES               |
  +-----------------------------------------------------------+
  |                                                           |
  |  Frequency                                                |
  |  ^                                                        |
  |  |                                                        |
  |  |*                                                       |
  |  |**                                                      |
  |  | **        Top 200K queries = 80% of traffic            |
  |  |  ***      We put ONLY these in the trie                |
  |  |    *****                                               |
  |  |         ***********                                    |
  |  |                    ******************************      |
  |  |                                                 *****  |
  |  +----------------------------------------------------->  |
  |  Rank 1        200K        1M           10M        200M   |
  |  "weather"     ...         ...          long tail queries |
  +-----------------------------------------------------------+
```

### Memory Budget

```
  RECOMMENDED MEMORY BUDGET:

  +-----------------------------------------------------------+
  |  Component          | Size      | Notes                    |
  |---------------------|-----------|--------------------------|
  |  Compressed Trie    | ~180 MB   | 200K queries             |
  |  Suggestion Cache   | ~50 MB    | 100K prefixes * 500B     |
  |  Query Repository   | ~100 MB   | 200K records + indexes   |
  |  Profanity Blocklist| ~5 MB     | 50K blocked terms        |
  |  User History Cache | ~200 MB   | 1M users * 200B          |
  |---------------------|-----------|--------------------------|
  |  TOTAL              | ~535 MB   | Fits in a 2GB JVM heap   |
  +-----------------------------------------------------------+

  JVM Overhead:
  - GC overhead: ~30% extra for G1GC
  - Thread stacks, class metadata: ~200 MB
  - Recommended heap: -Xmx2g -Xms2g
  - Total process memory: ~2.5 GB per server

  At 10,000 QPS per server:
  - 4 servers behind load balancer
  - Each has full trie replica
  - Total cluster memory: ~10 GB
  - Total cluster QPS: 40,000
```

---

## Interview Q&A

| Question | Answer |
|----------|--------|
| "Why a Trie and not a HashMap?" | "HashMap can do exact lookup in O(1) but cannot do prefix lookup. For autocomplete, we need ALL words starting with a prefix. Trie does this in O(m) by walking to the prefix node and traversing descendants. HashMap would require scanning all 200M keys." |
| "Why Compressed Trie?" | "Standard Trie wastes memory on single-child chains. 'application' creates 11 nodes, most with one child. Compressed Trie merges these into 2 nodes, saving 40-60% memory. Same lookup time O(m), much less memory." |
| "How much memory does the Trie use?" | "For 200K queries (covers 80% of traffic), a compressed trie uses about 180 MB. With cache, repository, and user history, total is ~535 MB per server. Fits in a 2 GB JVM heap. We don't store all 200M queries -- Zipf's law means the top 200K cover most searches." |
| "Why not use Elasticsearch?" | "For production, ES is great -- it has fuzzy matching, horizontal scaling, and built-in analytics. But its 5-10ms latency (network hop) vs our 1-2ms (in-process) matters for autocomplete. Also, building a trie in an interview demonstrates understanding of the data structure." |
| "How do you handle 200M queries?" | "We don't put all 200M in the trie. Query frequency follows Zipf's law -- top 200K queries cover 80% of searches. We keep only these in the trie. Long-tail queries ('best sushi near LAX terminal 4') are too specific for autocomplete anyway." |
| "Redis Sorted Set vs Trie?" | "Redis sorted sets are simpler (ZADD, ZREVRANGE) but create one key per prefix. For 200M queries with avg 15 chars, that's 3 billion keys -- prohibitive memory. Redis is great for caching the trie's top-K results, not for replacing the trie." |
| "How do you update frequencies?" | "Query logs stream through Kafka, partitioned by first character. An aggregator batches counts every 1-5 minutes. The trie is rebuilt every 15-60 minutes with updated frequencies. Blue-green swap ensures zero downtime." |
| "Standard Trie vs Compressed vs TST?" | "Standard: simplest, O(m) lookup, high memory. Compressed (Radix): 40-60% less memory, same O(m) lookup, more complex insertion (edge splitting). TST: 30% less memory than standard for sparse alphabets (Unicode), but O(m * log k) lookup. For English autocomplete: Compressed Trie wins." |

---

## Cross-Reference: Technologies Across Projects

| Technology | Projects Using It |
|------------|------------------|
| HashMap / ConcurrentHashMap | All projects (01-09) |
| LinkedHashMap (LRU) | 02 (Rate Limiter), 07 (Distributed Cache), **09 (Suggestion Cache)** |
| AtomicReference | 07 (Distributed Cache swap), **09 (Trie swap)** |
| PriorityQueue | 08 (Ride Sharing -- ETA ranking), **09 (Top-K suggestion selection)** |
| Kafka | 03 (Notifications), 05 (Social Feed), **09 (Query log streaming)** |
| Redis | 02 (Rate Limiter), 04 (Chat), 07 (Cache), **09 (Suggestion cache)** |
| Trie / Radix Tree | **09 (Core data structure -- unique to this project)** |
