# Design Patterns in the URL Shortener

> Interview-ready reference for a Senior Java developer.
> For each pattern: what it is, why it's here, alternatives, tradeoffs, and how to explain it in 30 seconds.

---

## Table of Contents

| # | Pattern | Key Class(es) | One-Liner |
|---|---------|---------------|-----------|
| 1 | Strategy | `EncodingStrategy`, `Base62Strategy`, `MD5Strategy`, `RandomStrategy` | Swap encoding algorithms at runtime |
| 2 | Builder | `Url.builder()` | Construct complex Url objects cleanly |
| 3 | Repository | `UrlRepository` -> `InMemoryUrlRepository` | Decouple storage from business logic |
| 4 | Factory | `AppConfig` | Centralized dependency wiring |
| 5 | Singleton | `AtomicLong` counter (conceptual) | One shared counter for ID generation |

---

## 1. Strategy Pattern

### What

Define a family of algorithms, encapsulate each one behind a common interface, and make them interchangeable at runtime.

### ASCII Diagram

```
              +---------------------+
              | <<interface>>       |
              | EncodingStrategy    |
              +---------------------+
              | + encode(url): String|
              +----------+----------+
                         |
          +--------------+--------------+
          |              |              |
+---------+--+  +--------+---+  +------+--------+
| Base62     |  | MD5        |  | Random        |
| Strategy   |  | Strategy   |  | Strategy      |
+------------+  +------------+  +---------------+
| + encode() |  | + encode() |  | + encode()    |
+------------+  +------------+  +---------------+
```

### Code Snippet

```java
// Interface
public interface EncodingStrategy {
    String encode(String originalUrl, long sequenceId);
}

// Concrete strategy
public class Base62Strategy implements EncodingStrategy {
    @Override
    public String encode(String originalUrl, long sequenceId) {
        return Base62.encode(sequenceId);  // e.g., "dnh3K1"
    }
}

// Service uses strategy — no if-else needed
public class UrlShorteningService {
    private final EncodingStrategy strategy;

    public UrlShorteningService(EncodingStrategy strategy) {
        this.strategy = strategy;          // injected at construction
    }

    public String shorten(String originalUrl) {
        long id = counter.incrementAndGet();
        return strategy.encode(originalUrl, id);
    }
}
```

### Why Here

- Multiple valid encoding algorithms exist (Base62, MD5-truncated, random alphanumeric).
- The service layer should not know or care which one is active.
- New algorithms (e.g., CRC32, custom hash) can be added with zero changes to existing code.

### Problem Solved

Without Strategy, you end up with:

```java
// Anti-pattern: if-else dispatch
if (algo.equals("base62"))      return base62Encode(id);
else if (algo.equals("md5"))    return md5Encode(url);
else if (algo.equals("random")) return randomEncode();
// Every new algorithm = modify this method = OCP violation
```

### Alternatives Considered

| Alternative | Verdict |
|-------------|---------|
| Enum-based dispatch | Works for simple cases, but the enum grows and violates SRP |
| if-else chain | Violates Open-Closed Principle; hard to test in isolation |
| Command Pattern | Overkill here — Command is for undo/redo and request queuing |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Open-Closed Principle: add new algorithms without modifying service | More classes (one per algorithm) |
| Each strategy is independently unit-testable | Slight indirection; reader must jump to implementation |
| Runtime switchable (A/B testing different encodings) | Caller must know which strategy to inject |

### Interview Explanation (30 seconds)

> "We use the Strategy pattern so the encoding algorithm can be swapped at runtime without modifying the service layer. For example, switching from Base62 to MD5 requires zero code changes in the service -- we just inject a different strategy implementation. This also lets us A/B test different encoding approaches or roll out a new algorithm behind a feature flag."

### When to Mention in Interview

- When the interviewer asks "How would you support multiple encoding schemes?"
- When discussing extensibility and the Open-Closed Principle.
- When explaining how you would A/B test different short-code formats.

---

## 2. Builder Pattern

### What

Separate the construction of a complex object from its representation, allowing the same construction process to create different representations. In practice: fluent, step-by-step object building.

### ASCII Diagram

```
+---------------------------+
|        Url (immutable)    |
+---------------------------+
| - shortCode: String       |
| - originalUrl: String     |
| - customAlias: String?    |    +------------------+
| - expiresAt: Instant?     |<---| Url.Builder      |
| - userId: String?         |    +------------------+
| - createdAt: Instant      |    | + shortCode()    |
| - clickCount: AtomicLong  |    | + originalUrl()  |
+---------------------------+    | + customAlias()  |
                                 | + expiresAt()    |
                                 | + userId()       |
                                 | + build(): Url   |
                                 +------------------+
```

### Code Snippet

```java
// Clean, readable construction
Url url = Url.builder()
    .shortCode("abc123")
    .originalUrl("https://example.com/very/long/path")
    .customAlias("my-link")          // optional
    .expiresAt(Instant.now().plus(Duration.ofDays(30)))  // optional
    .userId("user-42")              // optional
    .build();

// Compare with telescoping constructor anti-pattern:
// new Url("abc123", "https://...", "my-link", null, expiry, "user-42", now, 0L)
//  — which param is which? Impossible to read.
```

### Why Here

The `Url` entity has a mix of required and optional fields:

| Field | Required? |
|-------|-----------|
| `shortCode` | Yes |
| `originalUrl` | Yes |
| `createdAt` | Yes (auto-set) |
| `customAlias` | No |
| `expiresAt` | No |
| `userId` | No |

Without Builder, you either get a constructor with 7+ parameters or mutable setters that break immutability.

### Problem Solved

- **Telescoping constructor anti-pattern**: `new Url(a, b, null, null, c, null, d)` is unreadable.
- **Mutability risk**: Using setters means the object can be changed after creation.
- **Validation**: `build()` can validate required fields before creating the object.

### Alternatives Considered

| Alternative | Verdict |
|-------------|---------|
| Constructor + null params | Unreadable, error-prone positional args |
| Setter methods | Loses immutability; object in inconsistent state mid-construction |
| Java Records | Good for simple DTOs, but no built-in builder; limited validation |
| Lombok `@Builder` | Production shortcut; in interviews, show you understand the mechanics |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Immutable objects | More boilerplate (mitigated by Lombok in production) |
| Self-documenting construction | Inner Builder class adds lines of code |
| Compile-time safety for required fields | Slightly more memory (builder + object) |

### When to Mention in Interview

- When designing the data model and the interviewer sees optional fields.
- When discussing immutability and thread safety.
- Quick mention: "I'd use a Builder here since the entity has several optional fields."

---

## 3. Repository Pattern

### What

An abstraction layer between the domain/business logic and the data access layer. The domain works with an interface; the implementation details (SQL, NoSQL, in-memory) are hidden.

### ASCII Diagram

```
+---------------------+       +------------------------+
| UrlShorteningService| ----> | <<interface>>          |
| (business logic)    |       | UrlRepository          |
+---------------------+       +------------------------+
                              | + save(Url): void      |
                              | + findByShortCode(s): ? |
                              | + existsByAlias(s): bool|
                              +----------+-------------+
                                         |
                    +--------------------+--------------------+
                    |                    |                    |
          +---------+------+  +----------+-----+  +----------+-----+
          | InMemory       |  | Cassandra      |  | Redis          |
          | UrlRepository  |  | UrlRepository  |  | UrlRepository  |
          +----------------+  +----------------+  +----------------+
          | ConcurrentMap  |  | CQL queries    |  | Jedis client   |
          +----------------+  +----------------+  +----------------+
```

### Code Snippet

```java
// Interface — the contract
public interface UrlRepository {
    void save(Url url);
    Optional<Url> findByShortCode(String shortCode);
    boolean existsByCustomAlias(String alias);
}

// In-memory implementation (for interviews and testing)
public class InMemoryUrlRepository implements UrlRepository {
    private final Map<String, Url> store = new ConcurrentHashMap<>();

    @Override
    public void save(Url url) {
        store.put(url.getShortCode(), url);
    }

    @Override
    public Optional<Url> findByShortCode(String shortCode) {
        return Optional.ofNullable(store.get(shortCode));
    }
}

// Service depends on interface, not implementation
public class UrlShorteningService {
    private final UrlRepository repository;  // could be anything

    public UrlShorteningService(UrlRepository repository) {
        this.repository = repository;
    }
}
```

### Why Here

- In an interview, you start with `InMemoryUrlRepository` to get the logic right.
- You then tell the interviewer: "In production, I'd swap this for a `CassandraUrlRepository`."
- The service layer does not change at all.

### Problem Solved

| Without Repository | With Repository |
|---|---|
| Service directly calls `cassandraSession.execute(...)` | Service calls `repository.save(url)` |
| Changing DB = rewriting service | Changing DB = new Repository implementation |
| Unit testing requires DB connection | Unit testing uses InMemory or mock |

### Alternatives Considered

| Alternative | Verdict |
|-------------|---------|
| DAO Pattern | Very similar; DAO is more CRUD-focused, Repository is more domain-focused. Either works. |
| Direct DB calls in service | Tightly coupled; untestable without a running DB |
| Spring Data JPA | Production shortcut; generates the repository. Understand the concept first. |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Swappable storage backend | Extra interface + implementation class |
| Trivially testable (mock or in-memory) | Abstraction may leak for complex queries |
| Clean separation of concerns | Over-engineering for truly simple CRUD |

### Interview Explanation (30 seconds)

> "I use the Repository pattern to decouple the service from the storage layer. The service depends on a `UrlRepository` interface. For the interview, I implement it with a `ConcurrentHashMap`. In production, I'd swap in a Cassandra or DynamoDB implementation. The service code stays identical -- only the wiring changes."

### When to Mention in Interview

- Immediately when you start coding -- define the repository interface first.
- When the interviewer asks about database choice -- show that your code is DB-agnostic.
- When discussing testability.

---

## 4. Factory Pattern

### What

Centralize object creation logic in a single place, so the rest of the codebase requests objects without knowing how they're built or wired together.

### ASCII Diagram

```
+--------------------+
|     AppConfig      |  (Factory / Composition Root)
+--------------------+
| + createStrategy() |--> Base62Strategy
| + createRepo()     |--> InMemoryUrlRepository
| + createService()  |--> UrlShorteningService(strategy, repo)
| + createController()|--> UrlController(service)
+--------------------+
         |
         v
   Fully wired application
```

### Code Snippet

```java
public class AppConfig {

    public EncodingStrategy createStrategy() {
        return new Base62Strategy();
        // Change to new MD5Strategy() for a different encoding
    }

    public UrlRepository createRepository() {
        return new InMemoryUrlRepository();
        // Change to new CassandraUrlRepository(session) for production
    }

    public UrlShorteningService createService() {
        return new UrlShorteningService(createStrategy(), createRepository());
    }

    public UrlController createController() {
        return new UrlController(createService());
    }
}
```

### Why Here

- Without a framework like Spring, you need a central place to wire dependencies.
- `AppConfig` acts as a poor-man's dependency injection container.
- In an interview, this shows you understand composition roots and DI principles even without a framework.

### Alternatives Considered

| Alternative | Verdict |
|-------------|---------|
| Spring DI / Guice | Production choice; overkill for interview code |
| `new` everywhere in main() | Works but scatters wiring logic; hard to change |
| Service Locator | Anti-pattern in modern Java; hides dependencies |

### Tradeoffs

| Pro | Con |
|-----|-----|
| One place to change wiring | Manual wiring (no auto-scanning) |
| No framework dependency | Must update factory when adding new classes |
| Explicit and readable | Not as powerful as a full DI container |

### When to Mention in Interview

- At the start, when setting up the project structure.
- "I'm using a simple factory/config class to wire dependencies. In production, Spring would handle this."

---

## 5. Singleton Pattern (Conceptual)

### What

Ensure a class has only one instance and provide a global point of access to it. In this project, the concept appears via `AtomicLong` counter shared across the service.

### ASCII Diagram

```
+-------------------------+
| UrlShorteningService    |
+-------------------------+
| - counter: AtomicLong   |  <-- Single instance, shared state
| - COUNTER_START = 100000|
+-------------------------+
| + shorten(url): String  |  <-- counter.incrementAndGet()
+-------------------------+

  Single JVM: AtomicLong is sufficient
  Distributed: Need external coordination
```

### Code Snippet

```java
public class UrlShorteningService {
    // Single shared counter — effectively a singleton value
    private final AtomicLong counter = new AtomicLong(100_000L);

    public String shorten(String originalUrl) {
        long uniqueId = counter.incrementAndGet();
        String shortCode = strategy.encode(originalUrl, uniqueId);
        // ...
    }
}
```

### Why Here

- Each short URL needs a unique ID.
- On a single JVM, `AtomicLong` provides thread-safe, lock-free incrementing.
- The counter is logically a singleton -- there must be exactly one source of truth for the next ID.

### Distributed Systems Consideration

**This is a critical interview follow-up.** `AtomicLong` breaks in a multi-node deployment.

| Approach | How It Works | Tradeoff |
|----------|-------------|----------|
| **Snowflake ID** (Twitter) | 64-bit ID = timestamp + machine ID + sequence | No coordination needed; IDs are sortable; requires machine ID assignment |
| **ZooKeeper / etcd** | Distributed counter with strong consistency | Correct but slow; becomes a bottleneck |
| **Pre-allocated ranges** | Node 1 gets IDs 1-10000, Node 2 gets 10001-20000 | Fast; wastes IDs if a node dies; simple to implement |
| **UUID / ULID** | Random 128-bit ID, truncate or encode | No coordination; risk of collision if truncated |
| **Database sequence** | `AUTO_INCREMENT` or Cassandra lightweight transaction | Simple; DB becomes bottleneck at scale |

### Interview Explanation (30 seconds)

> "For a single-node prototype, `AtomicLong` gives us a thread-safe unique counter. In a distributed deployment, I'd use pre-allocated ID ranges -- each node gets a block of IDs from a coordination service. This avoids the bottleneck of a centralized counter while guaranteeing uniqueness. If ordering matters, I'd consider Twitter's Snowflake approach."

### When to Mention in Interview

- When the interviewer asks "How do you generate unique short codes?"
- When scaling discussion begins: "AtomicLong works on one node, but for distributed..."
- When discussing consistency guarantees for ID generation.

---

## Quick Reference: All Patterns at a Glance

```
+------------------------------------------------------------------+
|                      URL Shortener Architecture                   |
+------------------------------------------------------------------+
|                                                                    |
|  [AppConfig]  ----Factory----> creates all components             |
|       |                                                            |
|       +---> [UrlController]                                       |
|       |          |                                                 |
|       |          v                                                 |
|       +---> [UrlShorteningService]                                |
|       |          |           |                                     |
|       |          |           +---> EncodingStrategy  (Strategy)    |
|       |          |                   |-- Base62Strategy            |
|       |          |                   |-- MD5Strategy               |
|       |          |                   +-- RandomStrategy            |
|       |          |                                                 |
|       |          +---> AtomicLong counter  (Singleton concept)    |
|       |                                                            |
|       +---> [UrlRepository]  (Repository)                         |
|                  |-- InMemoryUrlRepository                         |
|                  +-- CassandraUrlRepository                        |
|                                                                    |
|  [Url.builder()]  (Builder)                                       |
|       .shortCode("abc")                                           |
|       .originalUrl("https://...")                                  |
|       .build()                                                     |
+------------------------------------------------------------------+
```

## Interview Cheat Sheet

| Question | Pattern to Mention |
|----------|--------------------|
| "How do you handle multiple encoding algorithms?" | Strategy |
| "How do you construct the URL entity?" | Builder |
| "How do you abstract the database?" | Repository |
| "How do you wire dependencies?" | Factory (or Spring DI) |
| "How do you generate unique IDs at scale?" | Singleton -> Snowflake / Range allocation |
| "How do you add a new feature without breaking existing code?" | Strategy + Repository (Open-Closed Principle) |
