# Design Patterns in the Distributed Cache System

> Interview-ready reference for a Senior Java developer.
> A distributed cache is a rich pattern playground -- it uses 8 GoF patterns across all three categories.
> For each pattern: ugly anti-pattern code, clean pattern-based code, numbered call chain, and interview one-liner.

---

## Table of Contents

| # | Pattern | GoF Category | Key Class(es) | One-Liner |
|---|---------|-------------|---------------|-----------|
| 1 | Strategy (x2) | Behavioral | `EvictionStrategy` (LRU, LFU, TTL), `HashingStrategy` (Consistent, Mod) | Swap eviction/hashing algorithms without changing service code |
| 2 | Builder | Creational | `CacheEntry.Builder`, `CacheConfig.Builder` | Many optional fields (TTL, frequency, metadata) -- Builder prevents arg confusion |
| 3 | Factory | Creational | `AppConfig` creates all objects and wires dependencies | Centralized object creation, only class that says `new ConcreteClass()` |
| 4 | Repository | Structural (DDD) | `CacheRepository` -> `InMemoryCacheRepository` | Decouple from backing store (swap to Redis/DB) |
| 5 | Facade | Structural | `CacheService` wraps store + eviction + stats + hashing | One entry point for all cache operations |
| 6 | Observer | Behavioral | `CacheStats` observes cache operations (hit/miss/eviction tracking) | Decouple monitoring from business logic |
| 7 | Singleton | Creational | `CacheConfig` (conceptual -- single config for the cluster) | One configuration per cache instance |
| 8 | Proxy | Structural | `NodeAwareCacheStore` proxies to correct node's store | Transparent routing via consistent hashing |

---

## 1. Strategy Pattern (x2)

### What

Define a family of algorithms, encapsulate each behind a common interface, and make them interchangeable at runtime. This project uses Strategy TWICE -- once for eviction policy, once for hashing.

### ASCII Diagram -- Both Strategy Hierarchies

```
  EVICTION STRATEGY                        HASHING STRATEGY
  =================                        ================

  +--------------------+                   +--------------------+
  | <<interface>>      |                   | <<interface>>      |
  | EvictionStrategy   |                   | HashingStrategy    |
  +--------------------+                   +--------------------+
  | + onGet(entry)     |                   | + getNode(key,     |
  | + onPut(entry)     |                   |   nodes): String   |
  | + evict(): String  |                   +----------+---------+
  +--------+-----------+                              |
           |                                    +-----+------+
     +-----+------+                             |            |
     |     |      |                    +--------+---+ +------+--------+
+----+--+ ++-+--+ ++-----+            | Consistent | | ModHashing    |
| LRU   | |LFU  | | TTL  |            | Hashing    | | Strategy      |
|Eviction| |Evic | |Evic  |            | Strategy   | | (key % N)     |
|Strategy| |tion | |tion  |            +------------+ +---------------+
+--------+ +-----+ +------+
```

### Ugly Code -- Without Strategy

```java
// ANTI-PATTERN: if-else chain in CacheService
// Every new eviction algorithm = modify this method = OCP violation
public class CacheService {

    private String evictionMode = "LRU"; // magic string

    public void put(String key, Object value) {
        if (store.size() >= maxSize) {
            // Eviction logic EMBEDDED in the service
            if (evictionMode.equals("LRU")) {
                String oldest = findLeastRecentlyUsed();
                store.remove(oldest);
            } else if (evictionMode.equals("LFU")) {
                String leastFrequent = findLeastFrequentlyUsed();
                store.remove(leastFrequent);
            } else if (evictionMode.equals("TTL")) {
                String expired = findFirstExpired();
                store.remove(expired);
            }
            // Adding LRU-K? ARC? 2Q? -- keep adding else-if blocks...
        }
        store.put(key, value);
    }

    // Same problem for hashing:
    public String getNode(String key) {
        if (hashMode.equals("MOD")) {
            return nodes.get(Math.abs(key.hashCode() % nodes.size()));
        } else if (hashMode.equals("CONSISTENT")) {
            // 50 lines of consistent hashing inline...
        }
        // Adding jump hash? Rendezvous hash? -- more else-if...
    }
}
```

**Problems with this approach:**
- `CacheService` knows about every eviction algorithm's internals (SRP violation)
- Adding a new algorithm requires modifying `CacheService` (OCP violation)
- Cannot unit-test eviction in isolation
- Magic strings for mode selection -- no compile-time safety

### Clean Code -- With Strategy

```java
// --- Strategy 1: Eviction ---
public interface EvictionStrategy {
    void onGet(CacheEntry entry);                      // track access
    void onPut(CacheEntry entry);                      // track insertion
    Optional<String> evict();                          // pick victim
    void onRemove(String key);                         // clean up tracking data
}

public class LRUEvictionStrategy implements EvictionStrategy {
    private final DoublyLinkedList accessOrder;        // most recent at head
    private final Map<String, Node> nodeMap;           // O(1) lookup

    @Override
    public void onGet(CacheEntry entry) {
        Node node = nodeMap.get(entry.getKey());
        accessOrder.moveToHead(node);                  // just accessed -> head
    }

    @Override
    public Optional<String> evict() {
        Node tail = accessOrder.removeTail();          // least recent = tail
        if (tail == null) return Optional.empty();
        nodeMap.remove(tail.key);
        return Optional.of(tail.key);
    }
}

public class LFUEvictionStrategy implements EvictionStrategy {
    private final TreeMap<Integer, LinkedHashSet<String>> frequencyMap;
    private final Map<String, Integer> keyFrequency;

    @Override
    public void onGet(CacheEntry entry) {
        int oldFreq = keyFrequency.get(entry.getKey());
        frequencyMap.get(oldFreq).remove(entry.getKey());
        frequencyMap.computeIfAbsent(oldFreq + 1, k -> new LinkedHashSet<>())
                    .add(entry.getKey());
        keyFrequency.put(entry.getKey(), oldFreq + 1);
    }

    @Override
    public Optional<String> evict() {
        Map.Entry<Integer, LinkedHashSet<String>> lowest = frequencyMap.firstEntry();
        if (lowest == null) return Optional.empty();
        String victim = lowest.getValue().iterator().next();  // FIFO within same freq
        lowest.getValue().remove(victim);
        if (lowest.getValue().isEmpty()) frequencyMap.remove(lowest.getKey());
        keyFrequency.remove(victim);
        return Optional.of(victim);
    }
}

// --- Strategy 2: Hashing ---
public interface HashingStrategy {
    String getNode(String key, List<String> nodes);
}

public class ConsistentHashingStrategy implements HashingStrategy {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodes;

    @Override
    public String getNode(String key, List<String> nodes) {
        long hash = md5Hash(key);
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
        return (entry != null) ? entry.getValue() : ring.firstEntry().getValue();
    }
}

public class ModHashingStrategy implements HashingStrategy {
    @Override
    public String getNode(String key, List<String> nodes) {
        return nodes.get(Math.abs(key.hashCode() % nodes.size()));
    }
}
```

### CacheService -- Uses Strategy (Doesn't Know the Algorithm)

```java
public class CacheService {
    private final CacheStore store;
    private final EvictionStrategy evictionStrategy;   // injected
    private final HashingStrategy hashingStrategy;     // injected
    private final CacheStats stats;

    public void put(String key, Object value) {
        // (1) Check if eviction needed
        while (store.size() >= store.getMaxSize()) {
            // (2) Ask strategy to pick a victim -- we don't know HOW
            Optional<String> victim = evictionStrategy.evict();
            victim.ifPresent(store::remove);
            victim.ifPresent(k -> stats.recordEviction());
        }
        // (3) Store the entry
        CacheEntry entry = CacheEntry.builder()
            .key(key).value(value).build();
        store.put(key, entry);
        // (4) Tell strategy about the new entry
        evictionStrategy.onPut(entry);
    }
}
```

### Numbered Call Chain -- put() with LRU Eviction

```
  Client                CacheService           LRUEvictionStrategy         CacheStore
    |                        |                         |                       |
    | (1) put("user:1", obj) |                         |                       |
    |----------------------->|                         |                       |
    |                        | (2) store.size()        |                       |
    |                        |------------------------------------------------>|
    |                        |                         |         size = 1000   |
    |                        |<------------------------------------------------|
    |                        |                         |                       |
    |                        | (3) evict()             |                       |
    |                        |------------------------>|                       |
    |                        |                         | removeTail()          |
    |                        |                         | -> victim="session:X" |
    |                        |<------------------------|                       |
    |                        |                         |                       |
    |                        | (4) store.remove("session:X")                   |
    |                        |------------------------------------------------>|
    |                        |                         |                       |
    |                        | (5) store.put("user:1", entry)                  |
    |                        |------------------------------------------------>|
    |                        |                         |                       |
    |                        | (6) onPut(entry)        |                       |
    |                        |------------------------>|                       |
    |                        |                         | addToHead("user:1")   |
    |                        |                         |                       |
```

### Interview One-Liner

> "We use Strategy twice: EvictionStrategy lets us swap LRU/LFU/TTL without touching CacheService, and HashingStrategy lets us swap consistent hashing vs mod hashing for node selection. Each is independently variable -- classic OCP."

### Cross-Reference

| Project | Strategy Used For |
|---------|------------------|
| 01 - URL Shortener | `EncodingStrategy` (Base62, MD5) |
| 02 - Rate Limiter | `RateLimitStrategy` (Fixed Window, Sliding Window, Token Bucket) |
| 06 - Parking Lot | `ParkingStrategy`, `PricingStrategy`, `PaymentProcessor` (x3) |
| **07 - Distributed Cache** | **`EvictionStrategy` (LRU, LFU, TTL), `HashingStrategy` (Consistent, Mod)** |

---

## 2. Builder Pattern

### What

Separate the construction of a complex object from its representation, allowing the same construction process to create different representations. Used when an object has many optional fields.

### ASCII Diagram

```
  Client Code                          CacheEntry.Builder                    CacheEntry
  ===========                          ==================                    ==========

  CacheEntry.builder()  ------(1)----> [new Builder()]
      .key("user:1")    ------(2)----> [set key]
      .value(userData)  ------(3)----> [set value]
      .ttlSeconds(300)  ------(4)----> [compute expiresAt]
      .sizeBytes(256)   ------(5)----> [set sizeBytes]
      .build()          ------(6)----> [validate + new CacheEntry(this)] ---> immutable object
```

### Ugly Code -- Without Builder

```java
// ANTI-PATTERN: Constructor with 7 parameters
// Which LocalDateTime is createdAt? Which is lastAccessedAt? Which is expiresAt?
CacheEntry entry = new CacheEntry(
    "user:1",                           // key
    userData,                           // value
    LocalDateTime.now(),                // createdAt? lastAccessedAt?
    LocalDateTime.now(),                // wait, which one is this?
    LocalDateTime.now().plusSeconds(300),// expiresAt? maybe?
    0,                                  // frequency? sizeBytes?
    256L                                // sizeBytes? frequency?
);

// Even worse -- what if TTL is optional?
// Need ANOTHER constructor:
CacheEntry entryNoTTL = new CacheEntry(
    "config:theme",
    "dark",
    LocalDateTime.now(),
    LocalDateTime.now(),
    null,    // no TTL -- but caller must remember to pass null
    0,
    64L
);

// And ANOTHER for entries without size tracking:
CacheEntry entryMinimal = new CacheEntry("key", "value", now, now, null, 0, 0L);
// Telescoping constructors: 2^N combinations for N optional fields
```

### Clean Code -- With Builder

```java
public class CacheEntry {

    private final String key;
    private final Object value;
    private final LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private final LocalDateTime expiresAt;
    private int frequency;
    private final long sizeBytes;

    private CacheEntry(Builder builder) {     // private -- only Builder calls this
        this.key = builder.key;
        this.value = builder.value;
        this.createdAt = builder.createdAt;
        this.lastAccessedAt = builder.lastAccessedAt;
        this.expiresAt = builder.expiresAt;
        this.frequency = builder.frequency;
        this.sizeBytes = builder.sizeBytes;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String key;
        private Object value;
        private LocalDateTime createdAt = LocalDateTime.now();      // sensible default
        private LocalDateTime lastAccessedAt = LocalDateTime.now(); // sensible default
        private LocalDateTime expiresAt = null;                     // no expiry by default
        private int frequency = 0;                                  // new entry
        private long sizeBytes = 0L;                                // unknown size

        public Builder key(String key)       { this.key = key; return this; }
        public Builder value(Object value)   { this.value = value; return this; }
        public Builder ttlSeconds(long ttl)  {
            if (ttl > 0) this.expiresAt = LocalDateTime.now().plusSeconds(ttl);
            return this;
        }
        public Builder sizeBytes(long size)  { this.sizeBytes = size; return this; }

        public CacheEntry build() {
            if (key == null || key.isEmpty())
                throw new IllegalArgumentException("Key cannot be null or empty");
            return new CacheEntry(this);
        }
    }
}

// Usage -- self-documenting, impossible to mix up arguments:
CacheEntry entry = CacheEntry.builder()
    .key("user:1")
    .value(userData)
    .ttlSeconds(300)
    .sizeBytes(256L)
    .build();

// No TTL? Just don't call it -- default is null (no expiry):
CacheEntry permanent = CacheEntry.builder()
    .key("config:theme")
    .value("dark")
    .build();
```

### Numbered Call Chain -- CacheService.put() Creates Entry via Builder

```
  Client               CacheService              CacheEntry.Builder          CacheEntry
    |                       |                           |                       |
    | (1) put("k", value)   |                           |                       |
    |---------------------->|                            |                       |
    |                       | (2) CacheEntry.builder()  |                       |
    |                       |-------------------------->|                       |
    |                       |                           |                       |
    |                       | (3) .key("k")             |                       |
    |                       |    .value(value)          |                       |
    |                       |    .ttlSeconds(config     |                       |
    |                       |       .getDefaultTtl())   |                       |
    |                       |-------------------------->|                       |
    |                       |                           |                       |
    |                       | (4) .build()              |                       |
    |                       |-------------------------->|                       |
    |                       |                           | (5) validate key      |
    |                       |                           | (6) new CacheEntry()  |
    |                       |                           |----- construct ------>|
    |                       |<--------------------------|     immutable obj     |
    |                       |                           |                       |
    |                       | (7) store.put(key, entry) |                       |
```

### Interview One-Liner

> "CacheEntry has 7 fields, 4 of which are optional. Builder eliminates the telescoping constructor problem and makes the code self-documenting -- you can't accidentally swap createdAt and lastAccessedAt."

### Cross-Reference

| Project | Builder Used For |
|---------|-----------------|
| 01 - URL Shortener | `ShortUrl.Builder` |
| 06 - Parking Lot | `ParkingTicket.Builder` |
| **07 - Distributed Cache** | **`CacheEntry.Builder`, `CacheConfig.Builder`** |

---

## 3. Factory Pattern

### What

Centralize object creation in a single class so that the rest of the codebase never calls `new ConcreteClass()`. In this project, `AppConfig` acts as the factory/composition root -- it is the ONLY class that knows about concrete implementations.

### ASCII Diagram

```
  +------------------------------------------------------------------+
  |                          AppConfig                                |
  |              (THE ONLY CLASS THAT SAYS "new")                     |
  +------------------------------------------------------------------+
  | + createCacheConfig(): CacheConfig                                |
  | + createEvictionStrategy(config): EvictionStrategy                |
  | + createHashingStrategy(): HashingStrategy                        |
  | + createCacheStore(config): CacheStore                            |
  | + createCacheStats(): CacheStats                                  |
  | + createCacheService(...): CacheService                           |
  +----+------+------+------+------+----------------------------------+
       |      |      |      |      |
       v      v      v      v      v
    LRU    Consistent  InMemory  CacheStats  CacheService
  Eviction  Hashing     Cache                (wired with
  Strategy  Strategy    Store                 all above)
```

### Ugly Code -- Without Factory

```java
// ANTI-PATTERN: "new" scattered across the codebase
// Every class creates its own dependencies -- tightly coupled
public class Main {
    public static void main(String[] args) {
        // Scattered construction -- changing one class ripples everywhere
        LRUEvictionStrategy eviction = new LRUEvictionStrategy(1000);
        ConsistentHashingStrategy hashing = new ConsistentHashingStrategy(150);
        InMemoryCacheStore store = new InMemoryCacheStore(1000);
        CacheStats stats = new CacheStats();

        // What if CacheService ALSO creates its own LRUEvictionStrategy internally?
        // Now we have TWO instances tracking different state -- BUG.

        // What if we want to switch to LFU? Hunt down every "new LRU..." in the codebase.
    }
}
```

### Clean Code -- With Factory (AppConfig)

```java
public class AppConfig {

    // --- Configuration ---
    public CacheConfig createCacheConfig() {
        return CacheConfig.builder()
            .maxSize(1000)
            .defaultTtlSeconds(300)
            .evictionPolicy("LRU")
            .build();
    }

    // --- Eviction Strategy ---
    public EvictionStrategy createEvictionStrategy(CacheConfig config) {
        return switch (config.getEvictionPolicy()) {
            case "LRU" -> new LRUEvictionStrategy(config.getMaxSize());
            case "LFU" -> new LFUEvictionStrategy(config.getMaxSize());
            case "TTL" -> new TTLEvictionStrategy();
            default -> throw new IllegalArgumentException(
                "Unknown eviction policy: " + config.getEvictionPolicy());
        };
    }

    // --- Hashing Strategy ---
    public HashingStrategy createHashingStrategy() {
        return new ConsistentHashingStrategy(150); // 150 virtual nodes
    }

    // --- Cache Store ---
    public CacheStore createCacheStore(CacheConfig config) {
        return new InMemoryCacheStore(config.getMaxSize());
    }

    // --- Stats ---
    public CacheStats createCacheStats() {
        return new CacheStats();
    }

    // --- WIRING: compose everything ---
    public CacheService createCacheService() {
        CacheConfig config = createCacheConfig();
        return new CacheService(
            createCacheStore(config),
            createEvictionStrategy(config),
            createHashingStrategy(),
            createCacheStats(),
            config
        );
    }
}
```

### Numbered Call Chain -- Application Startup

```
  main()                   AppConfig                    Concrete Classes
    |                         |                              |
    | (1) createCacheService()|                              |
    |------------------------>|                              |
    |                         | (2) createCacheConfig()      |
    |                         |---> new CacheConfig.Builder() -> CacheConfig
    |                         |                              |
    |                         | (3) createEvictionStrategy() |
    |                         |---> new LRUEvictionStrategy(1000)
    |                         |                              |
    |                         | (4) createHashingStrategy()  |
    |                         |---> new ConsistentHashingStrategy(150)
    |                         |                              |
    |                         | (5) createCacheStore()       |
    |                         |---> new InMemoryCacheStore(1000)
    |                         |                              |
    |                         | (6) createCacheStats()       |
    |                         |---> new CacheStats()         |
    |                         |                              |
    |                         | (7) new CacheService(store, eviction, hashing, stats, config)
    |                         |                              |
    |<-- CacheService --------|                              |
```

### Interview One-Liner

> "AppConfig is the composition root -- the ONE class that knows about concrete implementations. The rest of the codebase programs to interfaces. Swapping LRU for LFU is a one-line change in AppConfig."

### Cross-Reference

| Project | Factory Used For |
|---------|-----------------|
| 01 - URL Shortener | `AppConfig` wires all dependencies |
| 02 - Rate Limiter | `AppConfig` creates rate limiter chain |
| 06 - Parking Lot | `VehicleFactory`, `SpotFactory`, `AppConfig` |
| **07 - Distributed Cache** | **`AppConfig` creates eviction, hashing, store, stats, service** |

---

## 4. Repository Pattern

### What

Mediate between the domain and data mapping layers using a collection-like interface for accessing domain objects. The domain code doesn't know whether data lives in a `HashMap`, Redis, or PostgreSQL.

### ASCII Diagram

```
  CacheService                   <<interface>>                  Concrete
  (domain logic)                 CacheRepository                Implementations
  ==============                 ================               ===============

  "store.put(k, entry)"  -----> +------------------+
                                | CacheRepository   |
  "store.get(k)"         -----> | + put(key, entry) |
                                | + get(key): Entry |
  "store.remove(k)"      -----> | + remove(key)     |     +--------------------+
                                | + size(): int     |---->| InMemoryCacheRepo  |
  "store.containsKey(k)" -----> | + containsKey(k)  |     | (ConcurrentHashMap)|
                                | + getMaxSize()    |     +--------------------+
                                +------------------+
                                        |               +--------------------+
                                        +-------------->| RedisCacheRepo     |
                                                        | (Jedis/Lettuce)    |
                                                        +--------------------+
                                                             (future)
```

### Ugly Code -- Without Repository

```java
// ANTI-PATTERN: CacheService directly depends on ConcurrentHashMap
// Switching to Redis means rewriting CacheService
public class CacheService {
    // Concrete type exposed -- cannot swap implementation
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        // ConcurrentHashMap-specific API used directly
        store.put(key, buildEntry(key, value));
        // What if we want Redis? Rewrite this entire class.
    }

    public Object get(String key) {
        CacheEntry entry = store.get(key);
        // ConcurrentHashMap.get() returns null for missing keys
        // Redis would throw an exception or return Optional
        // Semantics are coupled to the implementation
        return entry != null ? entry.getValue() : null;
    }
}
```

### Clean Code -- With Repository

```java
public interface CacheStore {
    void put(String key, CacheEntry entry);
    Optional<CacheEntry> get(String key);
    void remove(String key);
    boolean containsKey(String key);
    int size();
    int getMaxSize();
    Set<String> keys();
}

public class InMemoryCacheStore implements CacheStore {
    private final ConcurrentHashMap<String, CacheEntry> map;
    private final int maxSize;

    public InMemoryCacheStore(int maxSize) {
        this.map = new ConcurrentHashMap<>(maxSize);
        this.maxSize = maxSize;
    }

    @Override
    public void put(String key, CacheEntry entry) {
        map.put(key, entry);
    }

    @Override
    public Optional<CacheEntry> get(String key) {
        return Optional.ofNullable(map.get(key));
    }

    @Override
    public void remove(String key) {
        map.remove(key);
    }

    @Override
    public int size() { return map.size(); }

    @Override
    public int getMaxSize() { return maxSize; }
}

// CacheService programs to the interface:
public class CacheService {
    private final CacheStore store;  // interface, not ConcurrentHashMap

    public CacheService(CacheStore store, ...) {
        this.store = store;
    }

    public Object get(String key) {
        return store.get(key)           // Optional<CacheEntry>
            .filter(e -> !e.isExpired())
            .map(e -> { e.touch(); evictionStrategy.onGet(e); return e.getValue(); })
            .orElse(null);
    }
}
```

### Numbered Call Chain -- get() Through Repository

```
  Client              CacheService              CacheStore (interface)     InMemoryCacheStore
    |                      |                          |                          |
    | (1) get("user:1")    |                          |                          |
    |--------------------->|                          |                          |
    |                      | (2) store.get("user:1")  |                          |
    |                      |------------------------->|                          |
    |                      |                          | (3) delegates to impl    |
    |                      |                          |------------------------->|
    |                      |                          |                          | map.get("user:1")
    |                      |                          |<---- Optional<Entry> ----|
    |                      |<---- Optional<Entry> ----|                          |
    |                      |                          |                          |
    |                      | (4) filter(!isExpired()) |                          |
    |                      | (5) entry.touch()        |                          |
    |                      | (6) evictionStrategy     |                          |
    |                      |     .onGet(entry)        |                          |
    |<-- value ------------|                          |                          |
```

### Interview One-Liner

> "CacheStore is an interface with put/get/remove. InMemoryCacheStore uses ConcurrentHashMap today; swapping to Redis means writing RedisCacheStore -- zero changes to CacheService or eviction logic."

### Cross-Reference

| Project | Repository Used For |
|---------|-------------------|
| 01 - URL Shortener | `UrlRepository` -> `InMemoryUrlRepository` |
| 02 - Rate Limiter | `RateLimitRepository` -> `InMemoryRateLimitRepository` |
| 06 - Parking Lot | `TicketRepository`, `SpotRepository` |
| **07 - Distributed Cache** | **`CacheStore` -> `InMemoryCacheStore`** |

---

## 5. Facade Pattern

### What

Provide a unified interface to a set of interfaces in a subsystem. The client calls ONE class that orchestrates multiple subsystems behind the scenes.

### ASCII Diagram

```
                         Client (Main / API Layer)
                                  |
                                  v
                    +----------------------------+
                    |       CacheService         |  <-- FACADE
                    |    (single entry point)     |
                    +----------------------------+
                    | + get(key): Object          |
                    | + put(key, value): void     |
                    | + remove(key): void         |
                    | + getStats(): CacheStats    |
                    +---+----+----+----+----------+
                        |    |    |    |
              +---------+    |    |    +----------+
              |              |    |               |
              v              v    v               v
       +-----------+   +--------+ +----------+  +----------+
       | CacheStore|   |Eviction| | Hashing  |  | Cache    |
       | (storage) |   |Strategy| | Strategy |  | Stats    |
       +-----------+   +--------+ +----------+  +----------+
```

### Ugly Code -- Without Facade

```java
// ANTI-PATTERN: Client must orchestrate 4 subsystems manually
public class Main {
    public static void main(String[] args) {
        InMemoryCacheStore store = new InMemoryCacheStore(1000);
        LRUEvictionStrategy eviction = new LRUEvictionStrategy(1000);
        ConsistentHashingStrategy hashing = new ConsistentHashingStrategy(150);
        CacheStats stats = new CacheStats();

        // PUT operation -- client must coordinate everything:
        String key = "user:1";
        Object value = userData;

        // Step 1: Check capacity
        if (store.size() >= store.getMaxSize()) {
            // Step 2: Evict
            Optional<String> victim = eviction.evict();
            victim.ifPresent(store::remove);
            victim.ifPresent(k -> stats.recordEviction());
        }

        // Step 3: Build entry
        CacheEntry entry = CacheEntry.builder().key(key).value(value).build();

        // Step 4: Store
        store.put(key, entry);

        // Step 5: Track in eviction
        eviction.onPut(entry);

        // Step 6: Record stats
        stats.recordPut();

        // GET operation -- another 6 steps the client must remember...
        // REMOVE operation -- another 4 steps...
        // Every caller must repeat this exact sequence -- DRY violation
    }
}
```

### Clean Code -- With Facade

```java
public class CacheService {
    private final CacheStore store;
    private final EvictionStrategy evictionStrategy;
    private final HashingStrategy hashingStrategy;
    private final CacheStats stats;
    private final CacheConfig config;

    // PUT -- one call, 4 subsystems coordinated
    public void put(String key, Object value) {
        while (store.size() >= store.getMaxSize()) {
            Optional<String> victim = evictionStrategy.evict();
            victim.ifPresent(store::remove);
            victim.ifPresent(k -> stats.recordEviction());
        }
        CacheEntry entry = CacheEntry.builder()
            .key(key).value(value)
            .ttlSeconds(config.getDefaultTtlSeconds())
            .build();
        store.put(key, entry);
        evictionStrategy.onPut(entry);
        stats.recordPut();
    }

    // GET -- one call, transparent stats + eviction tracking
    public Object get(String key) {
        Optional<CacheEntry> result = store.get(key);
        if (result.isEmpty() || result.get().isExpired()) {
            result.ifPresent(e -> { store.remove(key); evictionStrategy.onRemove(key); });
            stats.recordMiss();
            return null;
        }
        CacheEntry entry = result.get();
        entry.touch();
        evictionStrategy.onGet(entry);
        stats.recordHit();
        return entry.getValue();
    }
}

// Client code -- clean and simple:
CacheService cache = appConfig.createCacheService();
cache.put("user:1", userData);
Object result = cache.get("user:1");  // one line instead of 6
```

### Numbered Call Chain -- get() Through Facade

```
  Client             CacheService         CacheStore      EvictionStrategy    CacheStats
    |                     |                    |                  |                |
    | (1) get("user:1")   |                    |                  |                |
    |------------------->|                    |                  |                |
    |                     | (2) store.get()    |                  |                |
    |                     |------------------>|                  |                |
    |                     |<-- Optional<Entry> |                  |                |
    |                     |                    |                  |                |
    |                     | (3) isExpired()?   |                  |                |
    |                     |   -> false (valid) |                  |                |
    |                     |                    |                  |                |
    |                     | (4) entry.touch()  |                  |                |
    |                     |                    |                  |                |
    |                     | (5) onGet(entry)   |                  |                |
    |                     |---------------------------------------->|                |
    |                     |                    |  moveToHead()    |                |
    |                     |                    |                  |                |
    |                     | (6) recordHit()    |                  |                |
    |                     |------------------------------------------------------>|
    |                     |                    |                  |       hits++   |
    |<-- value -----------|                    |                  |                |
```

### Interview One-Liner

> "CacheService is a Facade -- the client calls get/put/remove and doesn't know about eviction strategies, stats tracking, TTL checks, or hashing. Four subsystems hidden behind three methods."

### Cross-Reference

| Project | Facade Used For |
|---------|----------------|
| 01 - URL Shortener | `UrlService` wraps encoding + storage + validation |
| 02 - Rate Limiter | `RateLimitService` wraps strategy + storage + response |
| 06 - Parking Lot | `ParkingService` wraps strategy + pricing + payment + tickets + display |
| **07 - Distributed Cache** | **`CacheService` wraps store + eviction + hashing + stats** |

---

## 6. Observer Pattern

### What

Define a one-to-many dependency between objects so that when one object changes state, all dependents are notified and updated automatically. Here, `CacheStats` observes every cache operation to track hit rate, miss rate, and eviction count.

### ASCII Diagram

```
  CacheService (subject)                    CacheStats (observer)
  ======================                    =====================

  put("user:1", data)                       +-------------------+
       |                                    | hits: AtomicLong  |
       +-- store.put(...)                   | misses: AtomicLong|
       +-- eviction.onPut(...)              | evictions: AtomicLong
       +-- stats.recordPut() -------(1)---->| puts: AtomicLong  |
                                            +-------------------+
  get("user:1")                             | + recordHit()     |
       |                                    | + recordMiss()    |
       +-- store.get(...)                   | + recordEviction()|
       +-- CACHE HIT                        | + recordPut()     |
       +-- stats.recordHit() ------(2)---->| + getHitRate()    |
                                            | + getReport()     |
  get("missing:key")                        +-------------------+
       |
       +-- store.get(...) -> empty
       +-- CACHE MISS
       +-- stats.recordMiss() -----(3)---->
```

### Ugly Code -- Without Observer

```java
// ANTI-PATTERN: Stats tracking embedded in CacheService
// CacheService is now responsible for BOTH caching AND metrics
public class CacheService {

    private long hits = 0;
    private long misses = 0;
    private long evictions = 0;
    private long puts = 0;

    public Object get(String key) {
        CacheEntry entry = store.get(key);
        if (entry != null && !entry.isExpired()) {
            hits++;                          // stats logic mixed with cache logic
            entry.touch();
            evictionStrategy.onGet(entry);
            return entry.getValue();
        } else {
            misses++;                        // more stats logic
            return null;
        }
    }

    public void put(String key, Object value) {
        if (store.size() >= maxSize) {
            evictionStrategy.evict().ifPresent(victim -> {
                store.remove(victim);
                evictions++;                 // even MORE stats logic
            });
        }
        store.put(key, buildEntry(key, value));
        puts++;                              // and more...
    }

    // Now add: latency tracking, percentile calculations, alerts...
    // CacheService becomes a metrics service. SRP destroyed.
}
```

### Clean Code -- With Observer

```java
public class CacheStats {
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);

    public void recordHit()      { hits.incrementAndGet(); }
    public void recordMiss()     { misses.incrementAndGet(); }
    public void recordEviction() { evictions.incrementAndGet(); }
    public void recordPut()      { puts.incrementAndGet(); }

    public double getHitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    public String getReport() {
        return String.format(
            "Hits: %d | Misses: %d | Hit Rate: %.2f%% | Evictions: %d | Puts: %d",
            hits.get(), misses.get(), getHitRate() * 100, evictions.get(), puts.get());
    }
}

// CacheService notifies stats at each operation:
public class CacheService {
    private final CacheStats stats;          // injected

    public Object get(String key) {
        Optional<CacheEntry> result = store.get(key);
        if (result.isPresent() && !result.get().isExpired()) {
            stats.recordHit();               // notify observer
            return result.get().getValue();
        }
        stats.recordMiss();                  // notify observer
        return null;
    }
}
```

### Numbered Call Chain -- Stats Recording During get()

```
  Client          CacheService        CacheStore        CacheStats
    |                  |                   |                 |
    | (1) get("k")     |                   |                 |
    |----------------->|                   |                 |
    |                  | (2) store.get("k") |                 |
    |                  |------------------>|                 |
    |                  |<-- entry found ----|                 |
    |                  |                   |                 |
    |                  | (3) !isExpired()   |                 |
    |                  |                   |                 |
    |                  | (4) recordHit()    |                 |
    |                  |--------------------------------------->|
    |                  |                   |        hits.incrementAndGet()
    |                  |                   |                 |
    |<-- value --------|                   |                 |
    |                  |                   |                 |
    | (5) getStats()   |                   |                 |
    |----------------->|                   |                 |
    |                  | (6) stats         |                 |
    |                  |    .getReport()   |                 |
    |                  |--------------------------------------->|
    |                  |<-- "Hits: 1 | ..." |                 |
    |<-- report -------|                   |                 |
```

### Interview One-Liner

> "CacheStats is an Observer -- it records hits, misses, and evictions without polluting CacheService with metrics logic. Adding latency percentiles means changing CacheStats only, not CacheService."

### Cross-Reference

| Project | Observer Used For |
|---------|------------------|
| 03 - Notification System | `NotificationListener` observes message events |
| 06 - Parking Lot | `DisplayBoard` observes availability changes |
| **07 - Distributed Cache** | **`CacheStats` observes cache operations (hit/miss/eviction)** |

---

## 7. Singleton Pattern

### What

Ensure a class has only one instance and provide a global point of access to it. For a cache cluster, there is ONE configuration that defines max size, TTL, eviction policy, and node topology.

### ASCII Diagram

```
  +----------------------------------+
  |          CacheConfig             |
  +----------------------------------+
  | - maxSize: int                   |
  | - defaultTtlSeconds: long        |
  | - evictionPolicy: String         |
  | - replicationFactor: int         |
  | - virtualNodes: int              |
  +----------------------------------+
  | + builder(): Builder             |
  | + getMaxSize(): int              |
  | + getEvictionPolicy(): String    |
  +----------------------------------+
           |
           | One instance per cache node
           | (conceptual Singleton)
           v
    [Shared by CacheService,
     EvictionStrategy,
     HashingStrategy]
```

### Ugly Code -- Without Singleton (Config Duplication)

```java
// ANTI-PATTERN: Config values scattered as magic numbers
public class CacheService {
    private final int MAX_SIZE = 1000;       // duplicated!
    private final long TTL = 300;            // duplicated!
}

public class LRUEvictionStrategy {
    private final int MAX_SIZE = 1000;       // same value, different constant
    // What if someone changes CacheService to 2000 but forgets this class?
}

public class ConsistentHashingStrategy {
    private final int VIRTUAL_NODES = 150;   // yet another magic number
}
// Three classes, three sources of truth. Guaranteed to drift.
```

### Clean Code -- With Singleton Config

```java
public class CacheConfig {
    private final int maxSize;
    private final long defaultTtlSeconds;
    private final String evictionPolicy;
    private final int replicationFactor;
    private final int virtualNodes;

    private CacheConfig(Builder builder) {
        this.maxSize = builder.maxSize;
        this.defaultTtlSeconds = builder.defaultTtlSeconds;
        this.evictionPolicy = builder.evictionPolicy;
        this.replicationFactor = builder.replicationFactor;
        this.virtualNodes = builder.virtualNodes;
    }

    public static Builder builder() { return new Builder(); }

    // getters...

    public static class Builder {
        private int maxSize = 1000;
        private long defaultTtlSeconds = 300;
        private String evictionPolicy = "LRU";
        private int replicationFactor = 3;
        private int virtualNodes = 150;

        public Builder maxSize(int maxSize) { this.maxSize = maxSize; return this; }
        public Builder defaultTtlSeconds(long ttl) { this.defaultTtlSeconds = ttl; return this; }
        public Builder evictionPolicy(String policy) { this.evictionPolicy = policy; return this; }
        public CacheConfig build() { return new CacheConfig(this); }
    }
}

// AppConfig creates ONE CacheConfig and passes it everywhere:
public class AppConfig {
    public CacheService createCacheService() {
        CacheConfig config = CacheConfig.builder()
            .maxSize(1000)
            .defaultTtlSeconds(300)
            .evictionPolicy("LRU")
            .build();

        // ONE config shared by all components:
        return new CacheService(
            createCacheStore(config),          // uses config.maxSize
            createEvictionStrategy(config),    // uses config.evictionPolicy
            createHashingStrategy(config),     // uses config.virtualNodes
            createCacheStats(),
            config                             // service itself reads TTL
        );
    }
}
```

### Interview One-Liner

> "CacheConfig is a conceptual Singleton -- one config per cache instance, shared by service, eviction, and hashing. No duplicated magic numbers, no configuration drift."

### Cross-Reference

| Project | Singleton Used For |
|---------|-------------------|
| 06 - Parking Lot | `ParkingLot` -- one physical lot |
| **07 - Distributed Cache** | **`CacheConfig` -- one config per cache instance** |

---

## 8. Proxy Pattern

### What

Provide a surrogate or placeholder for another object to control access to it. `NodeAwareCacheStore` acts as a Proxy that intercepts cache operations and routes them to the correct node's store using consistent hashing.

### ASCII Diagram

```
  Client             NodeAwareCacheStore              HashingStrategy         Node Stores
  ======             ===================              ===============         ===========
                     (Proxy -- looks like                                     
                      a normal CacheStore)                                    +---------+
                                                                             | Node A  |
  put("user:1",     +-------------------+    getNode("user:1")               | Store   |
       data)  ----->| put(key, entry)   |---------------------------->       +---------+
                    |                   |<--- "NodeB"                         +---------+
                    |                   |------- delegate put() ------------>| Node B  |
                    +-------------------+                                    | Store   |
                                                                             +---------+
  get("user:1")     +-------------------+    getNode("user:1")               +---------+
       ------------>| get(key)          |---------------------------->       | Node C  |
                    |                   |<--- "NodeB"                         | Store   |
                    |                   |------- delegate get() ------------>| +---------+
                    +-------------------+                                    (Node B)
```

### Ugly Code -- Without Proxy

```java
// ANTI-PATTERN: Every caller must manually determine the correct node
public class Main {
    Map<String, CacheStore> nodeStores = new HashMap<>();

    public void put(String key, Object value) {
        // Caller must know about consistent hashing, node topology, etc.
        String node = hashingStrategy.getNode(key, nodeList);
        CacheStore targetStore = nodeStores.get(node);
        if (targetStore == null) {
            throw new RuntimeException("Node " + node + " not found");
        }
        CacheEntry entry = CacheEntry.builder().key(key).value(value).build();
        targetStore.put(key, entry);
        // Every other method (get, remove, containsKey) must repeat this logic
    }

    public Object get(String key) {
        // Copy-pasted node resolution -- DRY violation
        String node = hashingStrategy.getNode(key, nodeList);
        CacheStore targetStore = nodeStores.get(node);
        return targetStore.get(key).map(CacheEntry::getValue).orElse(null);
    }
}
```

### Clean Code -- With Proxy

```java
public class NodeAwareCacheStore implements CacheStore {
    private final Map<String, CacheStore> nodeStores;
    private final HashingStrategy hashingStrategy;
    private final List<String> nodeIds;

    public NodeAwareCacheStore(HashingStrategy hashingStrategy, List<String> nodeIds) {
        this.hashingStrategy = hashingStrategy;
        this.nodeIds = new ArrayList<>(nodeIds);
        this.nodeStores = new HashMap<>();
        for (String nodeId : nodeIds) {
            nodeStores.put(nodeId, new InMemoryCacheStore(1000));
        }
    }

    private CacheStore resolveNode(String key) {
        String nodeId = hashingStrategy.getNode(key, nodeIds);
        return nodeStores.get(nodeId);
    }

    @Override
    public void put(String key, CacheEntry entry) {
        resolveNode(key).put(key, entry);    // transparent routing
    }

    @Override
    public Optional<CacheEntry> get(String key) {
        return resolveNode(key).get(key);    // transparent routing
    }

    @Override
    public void remove(String key) {
        resolveNode(key).remove(key);        // transparent routing
    }

    @Override
    public int size() {
        return nodeStores.values().stream().mapToInt(CacheStore::size).sum();
    }
}

// Client doesn't know about nodes -- it's just a CacheStore:
CacheStore store = new NodeAwareCacheStore(hashingStrategy, nodeIds);
store.put("user:1", entry);  // routed to correct node automatically
```

### Numbered Call Chain -- put() Through Proxy

```
  Client        NodeAwareCacheStore    HashingStrategy    InMemoryCacheStore
    |                   |                    |              (Node B)
    |                   |                    |                  |
    | (1) put("user:1", |                    |                  |
    |      entry)       |                    |                  |
    |------------------>|                    |                  |
    |                   | (2) getNode        |                  |
    |                   |   ("user:1",       |                  |
    |                   |    nodeIds)        |                  |
    |                   |------------------->|                  |
    |                   |                    | MD5 hash         |
    |                   |                    | -> ring lookup   |
    |                   |<-- "NodeB" --------|                  |
    |                   |                    |                  |
    |                   | (3) nodeStores     |                  |
    |                   |    .get("NodeB")   |                  |
    |                   |    .put("user:1",  |                  |
    |                   |         entry)     |                  |
    |                   |--------------------------------------->|
    |                   |                    |       map.put()  |
    |                   |<---------------------------------------|
    |<-- done ----------|                    |                  |
```

### Interview One-Liner

> "NodeAwareCacheStore is a Proxy -- it implements CacheStore but routes each operation to the correct node via consistent hashing. The caller sees a single cache; the proxy hides the distributed topology."

### Cross-Reference

| Project | Proxy Used For |
|---------|---------------|
| 02 - Rate Limiter | Conceptual -- rate limiter proxy before API handler |
| **07 - Distributed Cache** | **`NodeAwareCacheStore` proxies to correct node's store via consistent hashing** |

---

## Pattern Interaction Map

How all 8 patterns work together in a single `put()` call:

```
  main()
    |
    | (1) AppConfig.createCacheService()              [FACTORY]
    |     -> creates CacheConfig                      [SINGLETON / BUILDER]
    |     -> creates LRUEvictionStrategy              [STRATEGY]
    |     -> creates ConsistentHashingStrategy         [STRATEGY]
    |     -> creates NodeAwareCacheStore               [PROXY + REPOSITORY]
    |     -> creates CacheStats                        [OBSERVER]
    |     -> wires into CacheService                   [FACADE]
    |
    | (2) cacheService.put("user:1", data)            [FACADE entry point]
    |     |
    |     +-- (3) store.size() >= maxSize?            [REPOSITORY]
    |     |       -> NodeAwareCacheStore.size()        [PROXY aggregates]
    |     |
    |     +-- (4) evictionStrategy.evict()            [STRATEGY picks victim]
    |     |       -> LRU removes tail of linked list
    |     |
    |     +-- (5) CacheEntry.builder()                [BUILDER]
    |     |       .key("user:1").value(data)
    |     |       .ttlSeconds(config.getTtl())
    |     |       .build()
    |     |
    |     +-- (6) store.put("user:1", entry)          [REPOSITORY]
    |     |       -> NodeAwareCacheStore               [PROXY]
    |     |          .resolveNode("user:1")
    |     |          -> ConsistentHashing              [STRATEGY]
    |     |             .getNode() -> "NodeB"
    |     |          -> nodeStores.get("NodeB")
    |     |             .put(entry)                    [REPOSITORY impl]
    |     |
    |     +-- (7) stats.recordPut()                   [OBSERVER]
```

---

## Quick Reference: When to Mention Each Pattern in Interviews

| Interview Question | Lead With | Mention Also |
|-------------------|-----------|-------------|
| "How do you swap eviction policies?" | Strategy | Factory (creates the strategy), OCP |
| "How do you construct cache entries?" | Builder | Telescoping constructor anti-pattern |
| "How do you manage dependencies?" | Factory (AppConfig) | DI, composition root |
| "How do you decouple storage?" | Repository | Could swap InMemory for Redis |
| "How does the client interact?" | Facade | Hides 4 subsystems behind 3 methods |
| "How do you track hit rates?" | Observer | AtomicLong for thread safety |
| "How do you manage configuration?" | Singleton | Builder for config construction |
| "How do you route to correct node?" | Proxy | Consistent hashing (Strategy) |

---

## Summary Table

| Pattern | GoF Category | Problem Solved | SOLID Principle |
|---------|-------------|---------------|----------------|
| Strategy (x2) | Behavioral | Algorithm locked into service code | Open/Closed |
| Builder | Creational | Constructor with 7 params | SRP (construction separate from use) |
| Factory | Creational | `new ConcreteClass()` scattered everywhere | Dependency Inversion |
| Repository | Structural (DDD) | Domain coupled to storage impl | Dependency Inversion |
| Facade | Structural | Client must coordinate 4 subsystems | SRP (one entry point) |
| Observer | Behavioral | Stats mixed with business logic | SRP (monitoring separate) |
| Singleton | Creational | Config values duplicated/drifting | DRY |
| Proxy | Structural | Node routing exposed to every caller | SRP + Transparency |
