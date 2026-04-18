# Design Patterns in the Rate Limiter System

> Interview-ready reference. For each pattern: what it is, why we use it here, code example, and how to explain it to an interviewer.

---

## Table of Contents

| # | Pattern | Where Used | Interview Weight |
|---|---------|-----------|-----------------|
| 1 | Strategy | 5 rate limiting algorithms | HIGH -- the core pattern |
| 2 | Builder | RateLimitRule construction | MEDIUM |
| 3 | Repository | RuleRepository abstraction | MEDIUM |
| 4 | Factory | AppConfig / strategy creation | LOW-MEDIUM |
| 5 | Chain of Responsibility | Request pipeline (conceptual) | HIGH for system design |
| 6 | Template Method | Common pre/post logic in strategies | LOW |

---

## 1. Strategy Pattern (Primary Pattern)

### What It Is

Define a family of algorithms, encapsulate each one, and make them interchangeable. The client code selects which algorithm to use without knowing the internal details.

### Why Here

We have **five** rate limiting algorithms. Each has the same interface (`tryConsume`, `reset`, `algorithm`) but completely different internal logic -- token buckets track refill rates, sliding window logs store timestamps, fixed windows use simple counters. Without Strategy, we would have a massive `if/else` or `switch` block inside a single class.

### Problem Solved

- **Open/Closed Principle**: Adding a 6th algorithm (e.g., adaptive rate limiting) means creating one new class. Zero changes to `RateLimiterService`, `RateLimitRule`, or any existing strategy.
- **Single Responsibility**: Each algorithm class owns exactly one algorithm's state and logic.
- **Runtime swappability**: Different API endpoints can use different algorithms based on their `RateLimitRule.algorithm` field.

### ASCII Diagram

```
                    +-------------------------+
                    | RateLimiterStrategy     |
                    |  (interface)            |
                    |-------------------------|
                    | + tryConsume(key, rule)  |
                    | + reset(key)            |
                    | + algorithm()           |
                    +------------+------------+
                                 |
          +----------+-----------+-----------+----------+
          |          |           |           |          |
  +-------+------+  |  +--------+-------+   |  +------+--------+
  | TokenBucket  |  |  | FixedWindow    |   |  | SlidingWindow |
  | Strategy     |  |  | Strategy       |   |  | Counter       |
  +--------------+  |  +----------------+   |  | Strategy      |
                    |                       |  +---------------+
            +-------+------+      +---------+------+
            | LeakyBucket  |      | SlidingWindow  |
            | Strategy     |      | LogStrategy    |
            +--------------+      +----------------+
```

### Code: How the Service Selects a Strategy

```java
public class RateLimiterService {

    private final Map<Algorithm, RateLimiterStrategy> strategies;

    public RateLimiterService(List<RateLimiterStrategy> strategyList) {
        // Index strategies by their algorithm enum for O(1) lookup
        this.strategies = strategyList.stream()
            .collect(Collectors.toMap(
                RateLimiterStrategy::algorithm,
                Function.identity()
            ));
    }

    public RateLimitResult check(RequestContext ctx, RateLimitRule rule) {
        if (!rule.isEnabled()) {
            return RateLimitResult.allowed(rule.getMaxRequests(), rule.getMaxRequests(), 0);
        }

        // Strategy selection -- the rule tells us which algorithm to use
        RateLimiterStrategy strategy = strategies.get(rule.getAlgorithm());
        if (strategy == null) {
            throw new IllegalStateException("No strategy for: " + rule.getAlgorithm());
        }

        return strategy.tryConsume(ctx.getRateLimitKey(), rule);
    }
}
```

### Interview Explanation

> "We use the Strategy pattern because each rate limiting algorithm -- Token Bucket, Leaky Bucket, Fixed Window, Sliding Window Log, and Sliding Window Counter -- has the same interface but completely different internal logic. The `RateLimitRule` carries an `Algorithm` enum, and the service uses that to look up the correct strategy from a map. Adding a new algorithm is just a new class that implements `RateLimiterStrategy` -- zero changes to existing code. This keeps us aligned with the Open/Closed Principle and makes the system easy to test, since each strategy can be unit tested in isolation."

### Alternatives Considered

| Alternative | Why Not |
|------------|---------|
| Giant switch/if-else | Violates OCP; every new algorithm modifies existing code |
| Inheritance hierarchy (Template Method alone) | Algorithms share almost no common logic -- Strategy is cleaner |
| Functional interfaces / lambdas | Works for simple cases, but each algorithm has internal state (counters, timestamps) that benefits from a class |

### Tradeoffs

- **Pro**: Clean extension point, testable in isolation, runtime algorithm selection
- **Con**: More classes (5 strategy classes vs 1), slight indirection when debugging
- **Verdict**: Overwhelmingly worth it for 5+ algorithm variants

---

## 2. Builder Pattern

### What It Is

Separate the construction of a complex object from its representation. Provide a fluent API to set optional fields while enforcing required ones.

### Why Here

`RateLimitRule` has **3 required fields** (`key`, `maxRequests`, `windowSizeMs`) and **4 optional fields** (`id`, `algorithm`, `burstCapacity`, `enabled`). A telescoping constructor with 7 parameters is unreadable and error-prone.

### ASCII Diagram

```
  RateLimitRule.builder("user:api", 100, 60000)   // required fields
      .algorithm(Algorithm.TOKEN_BUCKET)            // optional
      .burstCapacity(150)                           // optional
      .enabled(true)                                // optional
      .build();                                     // validates + constructs

  +------------------+         +-----------------+
  | RateLimitRule     |<--------|  Builder        |
  |------------------|  builds  |-----------------|
  | - id             |         | - key (req)     |
  | - key            |         | - maxReqs (req) |
  | - maxRequests    |         | - windowMs (req)|
  | - windowSizeMs   |         | - id = key      |
  | - algorithm      |         | - algo = TOKEN  |
  | - burstCapacity  |         | - burst = -1    |
  | - enabled        |         | - enabled = true|
  +------------------+         +-----------------+
```

### Code: Builder in Action

```java
// From the actual codebase -- RateLimitRule.java
public static Builder builder(String key, int maxRequests, long windowSizeMs) {
    return new Builder(key, maxRequests, windowSizeMs);
}

// Usage
RateLimitRule rule = RateLimitRule.builder("user:api", 100, 60_000)
    .algorithm(Algorithm.SLIDING_WINDOW_COUNTER)
    .burstCapacity(150)
    .build();  // validates: maxRequests > 0, windowSizeMs > 0
```

### Interview Explanation

> "RateLimitRule uses the Builder pattern because it has required fields like key, maxRequests, and windowSizeMs, plus optional fields like burstCapacity and algorithm that have sensible defaults. The builder enforces required fields through its constructor, sets defaults for optional fields, and validates constraints in `build()`. This is the standard approach for immutable value objects with many parameters in Java."

### When to Mention

- When asked about how rules are configured
- When discussing immutability (the rule is fully immutable after construction)
- When discussing API design for configuration objects

---

## 3. Repository Pattern

### What It Is

Mediate between the domain and data mapping layers using a collection-like interface for accessing domain objects. Decouples business logic from storage.

### Why Here

Rate limit rules need to be stored and retrieved. Today it is in-memory (`InMemoryRuleRepository`). Tomorrow it could be PostgreSQL, DynamoDB, or Redis. The service layer should not care.

### ASCII Diagram

```
  +--------------------+      +------------------------+
  | RateLimiterService |----->| RuleRepository         |
  |                    |      |  (interface)           |
  +--------------------+      |------------------------|
                              | + findByKey(key)       |
                              | + findAll()            |
                              | + save(rule)           |
                              | + delete(key)          |
                              +----------+-------------+
                                         |
                          +--------------+--------------+
                          |                             |
                +---------+----------+       +----------+---------+
                | InMemoryRule       |       | RedisRule           |
                | Repository         |       | Repository          |
                | (ConcurrentHashMap)|       | (production)        |
                +--------------------+       +---------------------+
```

### Code

```java
public interface RuleRepository {
    Optional<RateLimitRule> findByKey(String key);
    List<RateLimitRule> findAll();
    void save(RateLimitRule rule);
    void delete(String key);
}

public class InMemoryRuleRepository implements RuleRepository {
    private final Map<String, RateLimitRule> rules = new ConcurrentHashMap<>();

    @Override
    public Optional<RateLimitRule> findByKey(String key) {
        return Optional.ofNullable(rules.get(key));
    }

    @Override
    public void save(RateLimitRule rule) {
        rules.put(rule.getKey(), rule);
    }
    // ...
}
```

### Interview Explanation

> "We abstract rule storage behind a Repository interface. The interview project uses an in-memory ConcurrentHashMap implementation, but in production you would swap in a PostgreSQL or DynamoDB implementation. The service layer programs against the interface, so the storage backend is a deployment decision, not a code change. This also makes unit testing trivial -- we inject the in-memory version."

### When to Mention

- When discussing how rules are stored and loaded
- When asked about testing strategy (inject InMemory for tests)
- When discussing separation of concerns and clean architecture

---

## 4. Factory Pattern

### What It Is

Centralize object creation logic in one place. Clients request objects without knowing the construction details.

### Why Here

`AppConfig` (the composition root) is responsible for wiring together all strategies, repositories, and the service. This acts as a simple factory -- it decides which concrete implementations to create and how to connect them.

### ASCII Diagram

```
  +---------------------+
  |     AppConfig       |
  |  (Factory / Wiring) |
  +----------+----------+
             |
             | creates & wires
             |
  +----------+------------------------------------------+
  |          |            |            |                 |
  v          v            v            v                 v
TokenBucket  LeakyBucket  FixedWindow  SlidingWindowLog  SlidingWindowCounter
Strategy     Strategy     Strategy     Strategy          Strategy
  |          |            |            |                 |
  +----------+------------+------------+-----------------+
             |
             v
  +----------+----------+
  | RateLimiterService  |  <-- receives Map<Algorithm, Strategy>
  +---------------------+
```

### Code

```java
public class AppConfig {

    public static RateLimiterService createRateLimiterService() {
        // Factory: centralized creation of all strategies
        List<RateLimiterStrategy> strategies = List.of(
            new TokenBucketStrategy(),
            new LeakyBucketStrategy(),
            new FixedWindowStrategy(),
            new SlidingWindowLogStrategy(),
            new SlidingWindowCounterStrategy()
        );

        return new RateLimiterService(strategies);
    }

    public static RuleRepository createRuleRepository() {
        // Swap this line to change storage backend
        return new InMemoryRuleRepository();
    }
}
```

### Interview Explanation

> "AppConfig acts as a factory and composition root. It creates all the strategy implementations, wires them into the service, and creates the repository. In a Spring application, the DI container handles this. Here we do it explicitly so the wiring is visible and easy to follow. If we needed to add Redis-backed strategies, we would change this one class."

### When to Mention

- When asked about dependency injection without a framework
- When discussing how the application is bootstrapped
- When asked "how would you swap in a different implementation?"

---

## 5. Chain of Responsibility (Conceptual)

### What It Is

Pass a request along a chain of handlers. Each handler decides to process the request or pass it to the next handler.

### Why Here (Conceptual / System Design Level)

In a real production system, the rate limiter is **one handler in a request processing pipeline**. It does not exist in isolation -- it is part of a chain of middleware/filters.

### ASCII Diagram: Request Pipeline

```
  Incoming Request
        |
        v
  +------------------+
  | Load Balancer    |   Route to appropriate server
  +--------+---------+
           |
           v
  +------------------+
  | TLS Termination  |   Decrypt HTTPS
  +--------+---------+
           |
           v
  +------------------+
  | Authentication   |   Validate JWT / API key --> 401 if invalid
  +--------+---------+
           |
           v
  +------------------+
  | Rate Limiter     |   Check counters --> 429 if over limit  <-- WE ARE HERE
  +--------+---------+
           |
           v
  +------------------+
  | Authorization    |   Check permissions --> 403 if forbidden
  +--------+---------+
           |
           v
  +------------------+
  | Business Logic   |   Process the actual request
  +--------+---------+
           |
           v
  +------------------+
  | Response + Rate  |   Add X-RateLimit-* headers
  | Limit Headers    |
  +------------------+
```

### In Java Frameworks

```java
// Spring Interceptor approach
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        RequestContext ctx = extractContext(request);
        RateLimitRule rule = findRuleForRequest(ctx);
        RateLimitResult result = rateLimiter.check(ctx, rule);

        // Add rate limit headers regardless of outcome
        result.getHeaders().forEach(response::setHeader);

        if (!result.isAllowed()) {
            response.setStatus(429);
            return false;  // Stop the chain
        }
        return true;  // Continue to next handler
    }
}

// Servlet Filter approach (framework-agnostic)
public class RateLimitFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        // Check rate limit
        if (overLimit) {
            ((HttpServletResponse) res).setStatus(429);
            return;  // Do NOT call chain.doFilter -- request stops here
        }
        chain.doFilter(req, res);  // Pass to next filter in chain
    }
}
```

### Interview Explanation

> "The rate limiter is a single link in a Chain of Responsibility. In production, a request passes through TLS termination, authentication, rate limiting, authorization, and then business logic. Each step can short-circuit the chain -- if auth fails, we return 401 and never hit the rate limiter. If the rate limiter rejects, we return 429 and never hit business logic. In Spring, this is an `HandlerInterceptor`; in plain Java, a `Servlet Filter`. The key insight is that rate limiting should happen AFTER authentication (so we know who the user is) but BEFORE business logic (so we protect resources)."

### Why Rate Limiter Goes After Auth

| Order | Reason |
|-------|--------|
| Auth BEFORE Rate Limiter | Need clientId to look up per-user rate limits |
| Rate Limiter BEFORE Business Logic | Protect expensive operations from abuse |
| Exception: IP-based rate limiting | Can go BEFORE auth to block DDoS at the edge |

### When to Mention

- When asked "where does rate limiting fit in the overall architecture?"
- When discussing middleware, filters, or interceptors
- When asked about the request lifecycle

---

## 6. Template Method Pattern (Partial Application)

### What It Is

Define the skeleton of an algorithm in a base class, letting subclasses override specific steps without changing the structure.

### Applicability Here

Template Method has **limited** applicability in our rate limiter because the five algorithms share very little common logic. However, there is some common pre/post processing that could be extracted:

### ASCII Diagram

```
  +----------------------------------+
  | AbstractRateLimiterStrategy      |
  |  (abstract class)               |
  |----------------------------------|
  | + tryConsume(key, rule)          |  <-- template method (final)
  |   1. validateRule(rule)          |  <-- common step
  |   2. doTryConsume(key, rule)     |  <-- abstract: subclass implements
  |   3. recordMetrics(result)       |  <-- common step
  |   return result                  |
  |                                  |
  | # doTryConsume(key, rule)        |  <-- abstract: each algo implements
  | - validateRule(rule)             |  <-- shared validation
  | - recordMetrics(result)          |  <-- shared observability
  +----------------+-----------------+
                   |
        +----------+-----------+
        |                      |
  +-----+--------+    +-------+------+
  | TokenBucket  |    | FixedWindow  |
  |  Strategy    |    |  Strategy    |
  |--------------|    |--------------|
  | doTryConsume |    | doTryConsume |
  +--------------+    +--------------+
```

### Code: If We Applied Template Method

```java
public abstract class AbstractRateLimiterStrategy implements RateLimiterStrategy {

    @Override
    public final RateLimitResult tryConsume(String key, RateLimitRule rule) {
        // Step 1: Common validation
        if (!rule.isEnabled()) {
            return RateLimitResult.allowed(rule.getMaxRequests(), rule.getMaxRequests(), 0);
        }

        // Step 2: Algorithm-specific logic (subclass implements)
        RateLimitResult result = doTryConsume(key, rule);

        // Step 3: Common post-processing (metrics, logging)
        logResult(key, result);
        return result;
    }

    protected abstract RateLimitResult doTryConsume(String key, RateLimitRule rule);

    private void logResult(String key, RateLimitResult result) {
        // Common observability logic
    }
}
```

### Interview Explanation

> "Template Method could wrap the Strategy implementations with common validation and metrics logic. In practice, the five algorithms share so little common code that we keep them as pure Strategy implementations. If we found ourselves duplicating pre-check or post-metric code across all five, we would introduce an abstract base class with a template method. It is a judgment call -- we avoid premature abstraction."

### When to Mention

- When asked "do the strategies share any common logic?"
- When discussing code duplication across strategy implementations
- When asked about combining Strategy with Template Method

---

## Quick Reference: Pattern Cheat Sheet for Interviews

```
Interviewer: "Walk me through the design patterns."

You:
"The core pattern is STRATEGY -- five algorithms behind one interface,
selected at runtime by the rule's algorithm field.

Rules use the BUILDER pattern because they have required and optional
fields with sensible defaults.

Storage is behind a REPOSITORY interface -- in-memory for the demo,
Redis or Postgres in production.

Wiring uses a FACTORY / composition root in AppConfig.

At the system level, the rate limiter is one link in a
CHAIN OF RESPONSIBILITY -- it sits between auth and business logic
in the request pipeline, returning 429 when limits are exceeded."
```

---

## Pattern Interaction Map

```
  AppConfig (Factory)
      |
      | creates
      v
  RateLimiterService
      |
      | uses -----------------------> RuleRepository (Repository)
      |                                     |
      | selects strategy by Algorithm       | stores/retrieves
      |                                     v
      v                               RateLimitRule (Builder)
  RateLimiterStrategy (Strategy)           |
      |                                    | carries Algorithm enum
      +-- TokenBucketStrategy              | that drives strategy
      +-- LeakyBucketStrategy              | selection
      +-- FixedWindowStrategy
      +-- SlidingWindowLogStrategy
      +-- SlidingWindowCounterStrategy

  [Conceptual: Chain of Responsibility]
  Request --> Auth --> RateLimiter --> Business Logic --> Response
```
