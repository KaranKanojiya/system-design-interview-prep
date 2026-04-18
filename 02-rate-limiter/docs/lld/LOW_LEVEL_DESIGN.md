# Low-Level Design: Rate Limiter System

> Interview-oriented LLD for a Senior Java Developer (7+ years).
> Focus: clean OOP, strategy pattern mastery, concurrency awareness, algorithm depth.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations — The Core](#6-strategy-implementations--the-core)
7. [Service Layer Design](#7-service-layer-design)
8. [Controller (Simulated Middleware)](#8-controller-simulated-middleware)
9. [Concurrency Considerations](#9-concurrency-considerations)
10. [Validation & Error Handling](#10-validation--error-handling)
11. [Sample Workflows](#11-sample-workflows)
12. [Design Patterns Used](#12-design-patterns-used)
13. [Extensibility Points](#13-extensibility-points)

---

## 1. Core Modules Overview

| Module         | Package        | Responsibility                                                        |
|----------------|----------------|-----------------------------------------------------------------------|
| **model**      | `.model`       | Domain objects: rules, results, request context, enums                |
| **strategy**   | `.strategy`    | Algorithm implementations behind a common `RateLimiterStrategy` interface |
| **service**    | `.service`     | Orchestration: resolve key, pick strategy, invoke, return result      |
| **repository** | `.repository`  | Persistence abstraction for rate-limit rules (in-memory default)      |
| **controller** | `.controller`  | Simulated middleware that intercepts requests and enforces limits      |
| **config**     | `.config`      | Wiring: strategy map, default rules, thread-pool config               |
| **exception**  | `.exception`   | Custom exceptions for rate-limit violations and missing rules         |
| **util**       | `.util`        | Time utilities — monotonic clock, window math helpers                 |

---

## 2. Package Structure

```
com.systemdesign.ratelimiter
├── model/
│   ├── RateLimitRule.java            — Rule definition (key, limit, window, algorithm)
│   ├── RateLimitResult.java          — Outcome of a rate-limit check
│   ├── RequestContext.java           — Incoming request metadata
│   ├── ClientIdentifier.java         — Enum strategy for extracting the rate-limit key
│   └── Algorithm.java                — Enum of supported algorithms
│
├── strategy/
│   ├── RateLimiterStrategy.java      — Common interface for all algorithms
│   ├── TokenBucketStrategy.java      — Token bucket implementation
│   ├── LeakyBucketStrategy.java      — Leaky bucket (FIFO queue) implementation
│   ├── FixedWindowStrategy.java      — Fixed window counter implementation
│   ├── SlidingWindowLogStrategy.java — Sliding window log (sorted timestamps)
│   └── SlidingWindowCounterStrategy.java — Sliding window weighted counter
│
├── service/
│   ├── RateLimiterService.java       — Core orchestrator: context -> result
│   └── RuleService.java              — CRUD operations on rules
│
├── repository/
│   ├── RuleRepository.java           — Interface for rule storage
│   └── InMemoryRuleRepository.java   — ConcurrentHashMap-backed implementation
│
├── controller/
│   └── RateLimiterController.java    — Simulated HTTP middleware
│
├── config/
│   └── AppConfig.java                — Strategy map wiring, defaults
│
├── exception/
│   ├── RateLimitExceededException.java — Thrown / returned on 429
│   └── RuleNotFoundException.java      — Thrown when rule lookup fails
│
└── util/
    └── TimeUtils.java                — currentTimeMillis wrapper, window math
```

---

## 3. Class Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              RATE LIMITER — CLASS DIAGRAM                                │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────┐         ┌──────────────────────────────┐
  │   <<enum>> Algorithm     │         │   <<enum>> ClientIdentifier  │
  ├──────────────────────────┤         ├──────────────────────────────┤
  │ TOKEN_BUCKET             │         │ BY_USER                      │
  │ LEAKY_BUCKET             │         │ BY_IP                        │
  │ FIXED_WINDOW             │         │ BY_ENDPOINT                  │
  │ SLIDING_WINDOW_LOG       │         │ BY_USER_AND_ENDPOINT         │
  │ SLIDING_WINDOW_COUNTER   │         ├──────────────────────────────┤
  └──────────────────────────┘         │ +extractKey(RequestContext)  │
                                       │   : String                   │
                                       └──────────────────────────────┘

  ┌──────────────────────────────────────────────┐
  │             RateLimitRule  (Builder)          │
  ├──────────────────────────────────────────────┤
  │ - id            : String                     │
  │ - key           : String                     │
  │ - maxRequests   : int                        │
  │ - windowSizeMs  : long                       │
  │ - algorithm     : Algorithm                  │
  │ - burstCapacity : int                        │
  │ - enabled       : boolean                    │
  ├──────────────────────────────────────────────┤
  │ + builder()     : RateLimitRuleBuilder       │
  │ + isValid()     : boolean                    │
  └──────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────┐
  │            RateLimitResult                   │
  ├──────────────────────────────────────────────┤
  │ - allowed       : boolean                    │
  │ - remaining     : int                        │
  │ - retryAfterMs  : long                       │
  │ - limit         : int                        │
  │ - resetAtMs     : long                       │
  ├──────────────────────────────────────────────┤
  │ + allowed(rem, limit, resetAt)  : static     │
  │ + denied(retryMs, limit, resetAt) : static   │
  └──────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────┐
  │            RequestContext                     │
  ├──────────────────────────────────────────────┤
  │ - clientId      : String                     │
  │ - ipAddress     : String                     │
  │ - endpoint      : String                     │
  │ - timestamp     : long                       │
  │ - headers       : Map<String, String>        │
  └──────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────┐
  │                <<interface>> RateLimiterStrategy                    │
  ├─────────────────────────────────────────────────────────────────────┤
  │ + tryConsume(key: String, rule: RateLimitRule) : RateLimitResult    │
  │ + reset(key: String) : void                                        │
  │ + algorithm() : Algorithm                                          │
  └────────────────────────────┬────────────────────────────────────────┘
                               │ implements
          ┌────────────────────┼────────────────────────┐
          │                    │                         │
          ▼                    ▼                         ▼
  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────────────┐
  │ TokenBucket      │ │ LeakyBucket      │ │ FixedWindowStrategy      │
  │ Strategy         │ │ Strategy         │ │                          │
  ├──────────────────┤ ├──────────────────┤ ├──────────────────────────┤
  │ -buckets:        │ │ -queues:         │ │ -counters:               │
  │  ConcurrentHash  │ │  ConcurrentHash  │ │  ConcurrentHashMap       │
  │  Map<String,     │ │  Map<String,     │ │  <String, WindowCounter> │
  │    TokenBucket>  │ │    LeakyQueue>   │ │                          │
  ├──────────────────┤ ├──────────────────┤ ├──────────────────────────┤
  │ +tryConsume()    │ │ +tryConsume()    │ │ +tryConsume()            │
  │ +reset()         │ │ +reset()         │ │ +reset()                 │
  │ +algorithm()     │ │ +algorithm()     │ │ +algorithm()             │
  └──────────────────┘ └──────────────────┘ └──────────────────────────┘

          ┌──────────────────────────┐  ┌──────────────────────────────┐
          │ SlidingWindowLog         │  │ SlidingWindowCounter         │
          │ Strategy                 │  │ Strategy                     │
          ├──────────────────────────┤  ├──────────────────────────────┤
          │ -logs:                   │  │ -windows:                    │
          │  ConcurrentHashMap       │  │  ConcurrentHashMap           │
          │  <String,                │  │  <String,                    │
          │   SortedTimestampLog>    │  │   SlidingWindowCounter>      │
          ├──────────────────────────┤  ├──────────────────────────────┤
          │ +tryConsume()            │  │ +tryConsume()                │
          │ +reset()                 │  │ +reset()                     │
          │ +algorithm()             │  │ +algorithm()                 │
          └──────────────────────────┘  └──────────────────────────────┘

  ┌─────────────────────────────────────────────────────┐
  │         <<interface>> RuleRepository                 │
  ├─────────────────────────────────────────────────────┤
  │ + findByKey(key: String) : Optional<RateLimitRule>  │
  │ + findAll()              : List<RateLimitRule>       │
  │ + save(rule: RateLimitRule) : void                  │
  │ + delete(key: String)       : void                  │
  └──────────────────────┬──────────────────────────────┘
                         │ implements
                         ▼
  ┌─────────────────────────────────────────────────────┐
  │        InMemoryRuleRepository                       │
  ├─────────────────────────────────────────────────────┤
  │ - store : ConcurrentHashMap<String, RateLimitRule>  │
  └─────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │              RateLimiterService                          │
  ├──────────────────────────────────────────────────────────┤
  │ - strategyMap  : Map<Algorithm, RateLimiterStrategy>     │
  │ - ruleService  : RuleService                             │
  ├──────────────────────────────────────────────────────────┤
  │ + checkRateLimit(ctx: RequestContext) : RateLimitResult   │
  │ - resolveKey(ctx: RequestContext, rule: RateLimitRule)    │
  │     : String                                             │
  └──────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │              RuleService                                 │
  ├──────────────────────────────────────────────────────────┤
  │ - repository : RuleRepository                            │
  ├──────────────────────────────────────────────────────────┤
  │ + getRule(key: String)         : RateLimitRule            │
  │ + getAllRules()                : List<RateLimitRule>      │
  │ + createRule(rule)             : void                     │
  │ + deleteRule(key: String)      : void                     │
  └──────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │        RateLimiterController  (Simulated Middleware)     │
  ├──────────────────────────────────────────────────────────┤
  │ - rateLimiterService : RateLimiterService                │
  ├──────────────────────────────────────────────────────────┤
  │ + handleRequest(ctx: RequestContext) : void               │
  └──────────────────────────────────────────────────────────┘

  RELATIONSHIPS:
  ─────────────
  Controller ──uses──▶ RateLimiterService
  RateLimiterService ──uses──▶ RuleService
  RateLimiterService ──uses──▶ RateLimiterStrategy (via strategyMap)
  RuleService ──uses──▶ RuleRepository
  RateLimiterStrategy ──reads──▶ RateLimitRule
  RateLimiterStrategy ──returns──▶ RateLimitResult
  ClientIdentifier ──extracts-key-from──▶ RequestContext
```

---

## 4. Entity Design

### 4.1 `Algorithm` Enum

```java
public enum Algorithm {
    TOKEN_BUCKET,
    LEAKY_BUCKET,
    FIXED_WINDOW,
    SLIDING_WINDOW_LOG,
    SLIDING_WINDOW_COUNTER
}
```

### 4.2 `ClientIdentifier` Enum

```java
public enum ClientIdentifier {
    BY_USER {
        public String extractKey(RequestContext ctx) {
            return "user:" + ctx.getClientId();
        }
    },
    BY_IP {
        public String extractKey(RequestContext ctx) {
            return "ip:" + ctx.getIpAddress();
        }
    },
    BY_ENDPOINT {
        public String extractKey(RequestContext ctx) {
            return "api:" + ctx.getEndpoint();
        }
    },
    BY_USER_AND_ENDPOINT {
        public String extractKey(RequestContext ctx) {
            return "user:" + ctx.getClientId() + ":api:" + ctx.getEndpoint();
        }
    };

    public abstract String extractKey(RequestContext ctx);
}
```

### 4.3 `RateLimitRule` — Builder Pattern

```java
public class RateLimitRule {
    private final String id;
    private final String key;               // e.g., "user:123", "ip:10.0.0.1", "api:/orders"
    private final int maxRequests;           // max allowed in window
    private final long windowSizeMs;         // window duration in ms
    private final Algorithm algorithm;       // which strategy to use
    private final int burstCapacity;         // for token/leaky bucket burst allowance
    private final boolean enabled;           // toggle without deleting

    private RateLimitRule(Builder builder) {
        this.id = builder.id;
        this.key = builder.key;
        this.maxRequests = builder.maxRequests;
        this.windowSizeMs = builder.windowSizeMs;
        this.algorithm = builder.algorithm;
        this.burstCapacity = builder.burstCapacity;
        this.enabled = builder.enabled;
    }

    public boolean isValid() {
        return key != null && !key.isEmpty()
            && maxRequests > 0
            && windowSizeMs > 0
            && algorithm != null;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private String key;
        private int maxRequests;
        private long windowSizeMs;
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
        private int burstCapacity;
        private boolean enabled = true;

        public Builder id(String id)                   { this.id = id; return this; }
        public Builder key(String key)                 { this.key = key; return this; }
        public Builder maxRequests(int max)             { this.maxRequests = max; return this; }
        public Builder windowSizeMs(long ms)            { this.windowSizeMs = ms; return this; }
        public Builder algorithm(Algorithm alg)         { this.algorithm = alg; return this; }
        public Builder burstCapacity(int burst)         { this.burstCapacity = burst; return this; }
        public Builder enabled(boolean enabled)         { this.enabled = enabled; return this; }

        public RateLimitRule build() {
            RateLimitRule rule = new RateLimitRule(this);
            if (!rule.isValid()) {
                throw new IllegalArgumentException("Invalid rule: " + rule);
            }
            return rule;
        }
    }

    // Getters omitted for brevity — all fields have standard getters
}
```

### 4.4 `RateLimitResult`

```java
public class RateLimitResult {
    private final boolean allowed;
    private final int remaining;        // tokens/slots left
    private final long retryAfterMs;    // 0 if allowed, else ms to wait
    private final int limit;            // the max from the rule
    private final long resetAtMs;       // epoch ms when the window/bucket resets

    // Private constructor, use factory methods:

    public static RateLimitResult allowed(int remaining, int limit, long resetAtMs) {
        return new RateLimitResult(true, remaining, 0, limit, resetAtMs);
    }

    public static RateLimitResult denied(long retryAfterMs, int limit, long resetAtMs) {
        return new RateLimitResult(false, 0, retryAfterMs, limit, resetAtMs);
    }

    // Maps to HTTP headers:
    // X-RateLimit-Limit     -> limit
    // X-RateLimit-Remaining -> remaining
    // X-RateLimit-Reset     -> resetAtMs
    // Retry-After           -> retryAfterMs / 1000
}
```

### 4.5 `RequestContext`

```java
public class RequestContext {
    private final String clientId;              // authenticated user ID
    private final String ipAddress;             // source IP
    private final String endpoint;              // e.g., "/api/orders"
    private final long timestamp;               // request arrival time (epoch ms)
    private final Map<String, String> headers;  // HTTP headers for custom extraction

    // Constructor + Getters
}
```

---

## 5. Interface Contracts

### 5.1 `RateLimiterStrategy`

```java
public interface RateLimiterStrategy {

    /**
     * Attempt to consume one token/slot for the given key under the given rule.
     * @return RateLimitResult indicating allowed/denied with metadata
     */
    RateLimitResult tryConsume(String key, RateLimitRule rule);

    /**
     * Reset all state for the given key (used for testing / admin override).
     */
    void reset(String key);

    /**
     * Which algorithm this strategy implements.
     */
    Algorithm algorithm();
}
```

### 5.2 `RuleRepository`

```java
public interface RuleRepository {

    Optional<RateLimitRule> findByKey(String key);

    List<RateLimitRule> findAll();

    void save(RateLimitRule rule);

    void delete(String key);
}
```

**Interview Note:** The repository interface lets us swap `InMemoryRuleRepository` for a Redis/DB-backed one without changing service code. This is the **Dependency Inversion Principle** in action.

---

## 6. Strategy Implementations — The Core

---

### 6.1 Token Bucket Strategy

**Concept:** A bucket holds tokens. Tokens are added at a steady refill rate. Each request consumes one token. If the bucket is empty, the request is denied.

**Why use it:** Allows controlled bursts while maintaining a long-term average rate.

#### Data Structure

```java
class TokenBucket {
    double tokens;           // current token count (double for fractional refill)
    long lastRefillTime;     // epoch ms of last refill
    int capacity;            // max tokens (burst capacity)
    double refillRate;       // tokens per millisecond
    ReentrantLock lock = new ReentrantLock();
}
```

#### Storage

```java
private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
```

#### Algorithm — Step by Step

```
tryConsume(key, rule):
  ┌───────────────────────────────────────────────────────┐
  │ 1. GET or CREATE bucket for key                       │
  │    capacity   = rule.burstCapacity > 0                │
  │                   ? rule.burstCapacity                │
  │                   : rule.maxRequests                  │
  │    refillRate = rule.maxRequests / rule.windowSizeMs  │
  │                                                       │
  │ 2. LOCK the bucket (per-key lock)                     │
  │                                                       │
  │ 3. REFILL tokens:                                     │
  │    now     = TimeUtils.currentTimeMillis()             │
  │    elapsed = now - bucket.lastRefillTime              │
  │    newTokens = elapsed * bucket.refillRate            │
  │    bucket.tokens = min(capacity, tokens + newTokens)  │
  │    bucket.lastRefillTime = now                        │
  │                                                       │
  │ 4. CONSUME:                                           │
  │    if bucket.tokens >= 1.0:                           │
  │       bucket.tokens -= 1.0                            │
  │       remaining = (int) bucket.tokens                 │
  │       resetAt = now + (capacity - tokens)/refillRate  │
  │       UNLOCK → return Result.allowed(remaining, ...)  │
  │    else:                                              │
  │       retryAfter = (1.0 - tokens) / refillRate        │
  │       UNLOCK → return Result.denied(retryAfter, ...)  │
  └───────────────────────────────────────────────────────┘
```

#### Visual — Token Bucket Over Time

```
Capacity = 5, Refill = 1 token/sec

Time(s):  0    1    2    3    4    5    6    7    8
Tokens:  [5]  [5]  [5]  [5]  [5]  [5]  [5]  [5]  [5]   ← idle, capped at 5

Burst of 4 requests at t=5:
Time(s):  5    5    5    5    6    7    8
Tokens:  [5]→[4]→[3]→[2]→[1]  [2]  [3]  [4]   ← refilling

Burst of 6 requests at t=0 (exceeds capacity):
Req:      1    2    3    4    5    6
Tokens:  [4]  [3]  [2]  [1]  [0]  [DENIED]
                                    ↑ retryAfter = 1000ms
```

#### Java Implementation

```java
public class TokenBucketStrategy implements RateLimiterStrategy {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        TokenBucket bucket = buckets.computeIfAbsent(key,
            k -> new TokenBucket(resolveCapacity(rule), resolveRefillRate(rule)));

        bucket.lock.lock();
        try {
            long now = TimeUtils.currentTimeMillis();
            // Refill
            long elapsed = now - bucket.lastRefillTime;
            double newTokens = elapsed * bucket.refillRate;
            bucket.tokens = Math.min(bucket.capacity, bucket.tokens + newTokens);
            bucket.lastRefillTime = now;

            // Consume
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                int remaining = (int) bucket.tokens;
                long resetAtMs = now + (long) ((bucket.capacity - bucket.tokens) / bucket.refillRate);
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                long retryAfterMs = (long) ((1.0 - bucket.tokens) / bucket.refillRate);
                long resetAtMs = now + retryAfterMs;
                return RateLimitResult.denied(retryAfterMs, rule.getMaxRequests(), resetAtMs);
            }
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public void reset(String key) { buckets.remove(key); }

    @Override
    public Algorithm algorithm() { return Algorithm.TOKEN_BUCKET; }

    private int resolveCapacity(RateLimitRule rule) {
        return rule.getBurstCapacity() > 0 ? rule.getBurstCapacity() : rule.getMaxRequests();
    }

    private double resolveRefillRate(RateLimitRule rule) {
        return (double) rule.getMaxRequests() / rule.getWindowSizeMs();
    }
}
```

---

### 6.2 Leaky Bucket Strategy

**Concept:** A FIFO queue with a fixed drain rate. Requests enter the queue. If the queue is full, the request is rejected. Requests drain (are "processed") at a constant rate.

**Why use it:** Smooths out bursts entirely — output rate is always constant.

#### Data Structure

```java
class LeakyQueue {
    Deque<Long> queue;          // timestamps of queued requests
    long lastLeakTime;          // when we last drained
    int capacity;               // max queue size
    double leakRate;            // requests drained per millisecond
    ReentrantLock lock = new ReentrantLock();
}
```

#### Storage

```java
private final ConcurrentHashMap<String, LeakyQueue> queues = new ConcurrentHashMap<>();
```

#### Algorithm — Step by Step

```
tryConsume(key, rule):
  ┌──────────────────────────────────────────────────────────┐
  │ 1. GET or CREATE LeakyQueue for key                      │
  │    capacity = rule.burstCapacity > 0                     │
  │                 ? rule.burstCapacity                     │
  │                 : rule.maxRequests                       │
  │    leakRate = rule.maxRequests / rule.windowSizeMs       │
  │                                                          │
  │ 2. LOCK the queue                                        │
  │                                                          │
  │ 3. LEAK (drain processed requests):                      │
  │    now     = TimeUtils.currentTimeMillis()                │
  │    elapsed = now - queue.lastLeakTime                    │
  │    leakedCount = (int)(elapsed * leakRate)               │
  │    Remove min(leakedCount, queue.size) from front        │
  │    queue.lastLeakTime = now                              │
  │                                                          │
  │ 4. ENQUEUE:                                              │
  │    if queue.size < capacity:                             │
  │       queue.addLast(now)                                 │
  │       remaining = capacity - queue.size                  │
  │       UNLOCK → return Result.allowed(remaining, ...)     │
  │    else:                                                 │
  │       retryAfter = 1.0 / leakRate  (time for 1 to leak) │
  │       UNLOCK → return Result.denied(retryAfter, ...)     │
  └──────────────────────────────────────────────────────────┘
```

#### Visual — Leaky Bucket

```
Capacity = 4, Leak rate = 1 req/sec

Queue:  [R1] [R2] [R3] [  ]     ← 3 in queue, 1 slot free
         ↓ drains at 1/sec

After 1 second:
Queue:  [R2] [R3] [  ] [  ]     ← R1 drained, 2 slots free

Burst of 5 requests arrives:
Queue:  [R2] [R3] [R4] [R5]     ← R6 DENIED (queue full)
```

#### Java Implementation

```java
public class LeakyBucketStrategy implements RateLimiterStrategy {

    private final ConcurrentHashMap<String, LeakyQueue> queues = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        LeakyQueue lq = queues.computeIfAbsent(key,
            k -> new LeakyQueue(resolveCapacity(rule), resolveLeakRate(rule)));

        lq.lock.lock();
        try {
            long now = TimeUtils.currentTimeMillis();

            // Leak
            long elapsed = now - lq.lastLeakTime;
            int leaked = (int) (elapsed * lq.leakRate);
            for (int i = 0; i < leaked && !lq.queue.isEmpty(); i++) {
                lq.queue.pollFirst();
            }
            if (leaked > 0) lq.lastLeakTime = now;

            // Enqueue
            if (lq.queue.size() < lq.capacity) {
                lq.queue.addLast(now);
                int remaining = lq.capacity - lq.queue.size();
                long resetAtMs = now + (long) (lq.queue.size() / lq.leakRate);
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                long retryAfterMs = (long) (1.0 / lq.leakRate);
                return RateLimitResult.denied(retryAfterMs, rule.getMaxRequests(),
                    now + retryAfterMs);
            }
        } finally {
            lq.lock.unlock();
        }
    }

    @Override
    public void reset(String key) { queues.remove(key); }

    @Override
    public Algorithm algorithm() { return Algorithm.LEAKY_BUCKET; }
}
```

---

### 6.3 Fixed Window Counter Strategy

**Concept:** Divide time into fixed windows (e.g., every 60 seconds). Count requests per window. If the count exceeds the limit, deny.

**Why use it:** Simple, low memory. Good enough for many real-world use cases.

#### Data Structure

```java
class WindowCounter {
    AtomicInteger count;    // requests in current window
    long windowStart;       // epoch ms when this window began
}
```

#### Storage

```java
private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
```

#### Algorithm — Step by Step

```
tryConsume(key, rule):
  ┌─────────────────────────────────────────────────────────────┐
  │ 1. GET or CREATE WindowCounter for key                      │
  │                                                             │
  │ 2. Calculate currentWindow:                                 │
  │    now = TimeUtils.currentTimeMillis()                       │
  │    windowStart = now - (now % rule.windowSizeMs)            │
  │                                                             │
  │ 3. WINDOW CHECK:                                            │
  │    if counter.windowStart != windowStart:                   │
  │       // NEW window — reset                                 │
  │       counter.windowStart = windowStart                     │
  │       counter.count.set(0)                                  │
  │                                                             │
  │ 4. INCREMENT and CHECK:                                     │
  │    currentCount = counter.count.incrementAndGet()            │
  │    if currentCount <= rule.maxRequests:                      │
  │       remaining = rule.maxRequests - currentCount            │
  │       resetAt = windowStart + rule.windowSizeMs             │
  │       return Result.allowed(remaining, ...)                  │
  │    else:                                                    │
  │       counter.count.decrementAndGet()  // rollback          │
  │       retryAfter = (windowStart + windowSizeMs) - now       │
  │       return Result.denied(retryAfter, ...)                  │
  └─────────────────────────────────────────────────────────────┘
```

#### The Boundary Problem

```
Window size = 1 minute, Limit = 100 requests

Window 1: [00:00 ─────────── 01:00]  Window 2: [01:00 ─────────── 02:00]
                                 │   │
           .... 0 requests ..... │100│100 requests ....... 0 ........
                            last │   │ first
                            sec  │   │ sec
                                 │   │
                                 ▼   ▼
At the BOUNDARY (00:59 to 01:01), user sends:
  - 100 requests in last second of Window 1   → ALL ALLOWED
  - 100 requests in first second of Window 2  → ALL ALLOWED
  = 200 requests in 2 seconds!                → DOUBLE the intended limit

This is the "boundary spike" problem. Fixed window cannot detect it.

SOLUTION: Use Sliding Window Counter or Sliding Window Log.
```

#### Java Implementation

```java
public class FixedWindowStrategy implements RateLimiterStrategy {

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        long now = TimeUtils.currentTimeMillis();
        long windowStart = now - (now % rule.getWindowSizeMs());

        WindowCounter counter = counters.computeIfAbsent(key,
            k -> new WindowCounter(windowStart));

        synchronized (counter) {
            // Reset if new window
            if (counter.windowStart != windowStart) {
                counter.windowStart = windowStart;
                counter.count.set(0);
            }

            int current = counter.count.incrementAndGet();
            long resetAtMs = windowStart + rule.getWindowSizeMs();

            if (current <= rule.getMaxRequests()) {
                return RateLimitResult.allowed(
                    rule.getMaxRequests() - current,
                    rule.getMaxRequests(),
                    resetAtMs);
            } else {
                counter.count.decrementAndGet();
                return RateLimitResult.denied(
                    resetAtMs - now,
                    rule.getMaxRequests(),
                    resetAtMs);
            }
        }
    }

    @Override
    public void reset(String key) { counters.remove(key); }

    @Override
    public Algorithm algorithm() { return Algorithm.FIXED_WINDOW; }
}
```

---

### 6.4 Sliding Window Log Strategy

**Concept:** Keep a sorted log of every request timestamp. For each new request, remove entries outside the window, count what remains. If under limit, add the new timestamp.

**Why use it:** Most accurate — no boundary problem. But highest memory cost (stores every timestamp).

#### Data Structure

```java
class TimestampLog {
    TreeMap<Long, Integer> timestamps;   // timestamp -> count (handles same-ms requests)
    ReentrantLock lock = new ReentrantLock();
}
```

> Alternative: `LinkedList<Long>` sorted by insertion order (since timestamps are monotonically increasing).

#### Storage

```java
private final ConcurrentHashMap<String, TimestampLog> logs = new ConcurrentHashMap<>();
```

#### Algorithm — Step by Step

```
tryConsume(key, rule):
  ┌────────────────────────────────────────────────────────────────┐
  │ 1. GET or CREATE TimestampLog for key                          │
  │                                                                │
  │ 2. LOCK the log                                                │
  │                                                                │
  │ 3. EVICT expired entries:                                      │
  │    now = TimeUtils.currentTimeMillis()                          │
  │    windowStart = now - rule.windowSizeMs                       │
  │    log.timestamps.headMap(windowStart).clear()                 │
  │    // removes all entries with timestamp < windowStart         │
  │                                                                │
  │ 4. COUNT remaining:                                            │
  │    totalCount = sum of all values in log.timestamps            │
  │                                                                │
  │ 5. DECIDE:                                                     │
  │    if totalCount < rule.maxRequests:                            │
  │       log.timestamps.merge(now, 1, Integer::sum)               │
  │       remaining = rule.maxRequests - totalCount - 1            │
  │       resetAt = now + rule.windowSizeMs                        │
  │       UNLOCK → return Result.allowed(remaining, ...)            │
  │    else:                                                       │
  │       // Find the oldest entry — that is when a slot frees up │
  │       oldestTs = log.timestamps.firstKey()                     │
  │       retryAfter = oldestTs + rule.windowSizeMs - now          │
  │       UNLOCK → return Result.denied(retryAfter, ...)            │
  └────────────────────────────────────────────────────────────────┘
```

#### Visual — Sliding Window Log

```
Window = 60s, Limit = 5

Timeline (seconds): 0   10   20   30   40   50   60   70   80
Requests:           R1   R2   R3   R4   R5        R6

At t=60, checking for R6:
  Window = [0, 60]
  Log: [0, 10, 20, 30, 40] → count = 5
  R6 DENIED. retryAfter = 0 + 60 - 60 = 0ms → actually next eviction at t=61

At t=70, checking for R6 (retry):
  Window = [10, 70]
  Evict: R1 (t=0) removed
  Log: [10, 20, 30, 40, 50(R5 assumed)] → count = 4(if R5 was at 50)
  Wait — let's say only [10, 20, 30, 40] → count = 4
  R6 ALLOWED.

Memory: O(N) where N = number of requests in the window. For high-throughput
APIs, this can be expensive (millions of entries per key).
```

#### Java Implementation

```java
public class SlidingWindowLogStrategy implements RateLimiterStrategy {

    private final ConcurrentHashMap<String, TimestampLog> logs = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        TimestampLog log = logs.computeIfAbsent(key, k -> new TimestampLog());

        log.lock.lock();
        try {
            long now = TimeUtils.currentTimeMillis();
            long windowStart = now - rule.getWindowSizeMs();

            // Evict expired
            log.timestamps.headMap(windowStart, false).clear();

            // Count
            int totalCount = log.timestamps.values().stream()
                .mapToInt(Integer::intValue).sum();

            if (totalCount < rule.getMaxRequests()) {
                log.timestamps.merge(now, 1, Integer::sum);
                int remaining = rule.getMaxRequests() - totalCount - 1;
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(),
                    now + rule.getWindowSizeMs());
            } else {
                long oldestTs = log.timestamps.firstKey();
                long retryAfterMs = oldestTs + rule.getWindowSizeMs() - now;
                return RateLimitResult.denied(Math.max(retryAfterMs, 1),
                    rule.getMaxRequests(), oldestTs + rule.getWindowSizeMs());
            }
        } finally {
            log.lock.unlock();
        }
    }

    @Override
    public void reset(String key) { logs.remove(key); }

    @Override
    public Algorithm algorithm() { return Algorithm.SLIDING_WINDOW_LOG; }
}
```

---

### 6.5 Sliding Window Counter Strategy

**Concept:** Hybrid of fixed window and sliding window. Keep counters for the current and previous windows. Weight the previous window's count by the overlap percentage.

**Why use it:** Near-accuracy of sliding window log with the O(1) memory of fixed window counter.

#### Data Structure

```java
class SlidingWindowCounter {
    int previousCount;       // count in the previous window
    int currentCount;        // count in the current window
    long currentWindowStart; // epoch ms when the current window began
    ReentrantLock lock = new ReentrantLock();
}
```

#### Storage

```java
private final ConcurrentHashMap<String, SlidingWindowCounter> windows = new ConcurrentHashMap<>();
```

#### Algorithm — Step by Step

```
tryConsume(key, rule):
  ┌─────────────────────────────────────────────────────────────────────┐
  │ 1. GET or CREATE SlidingWindowCounter for key                       │
  │                                                                     │
  │ 2. LOCK the counter                                                 │
  │                                                                     │
  │ 3. CALCULATE current window:                                        │
  │    now = TimeUtils.currentTimeMillis()                               │
  │    windowStart = now - (now % rule.windowSizeMs)                    │
  │                                                                     │
  │ 4. WINDOW ROTATION:                                                 │
  │    if windowStart != counter.currentWindowStart:                    │
  │       if windowStart - counter.currentWindowStart == windowSizeMs:  │
  │          // Adjacent window — rotate                                │
  │          counter.previousCount = counter.currentCount               │
  │       else:                                                         │
  │          // More than 1 window gap — previous is irrelevant         │
  │          counter.previousCount = 0                                  │
  │       counter.currentCount = 0                                      │
  │       counter.currentWindowStart = windowStart                      │
  │                                                                     │
  │ 5. CALCULATE WEIGHTED COUNT:                                        │
  │    elapsedInWindow = now - windowStart                              │
  │    overlapPercentage = 1.0 - (elapsedInWindow / windowSizeMs)      │
  │    weightedCount = (previousCount * overlapPercentage)              │
  │                    + currentCount                                   │
  │                                                                     │
  │ 6. DECIDE:                                                          │
  │    if weightedCount < rule.maxRequests:                              │
  │       counter.currentCount++                                        │
  │       remaining = rule.maxRequests - (int)ceil(weightedCount) - 1   │
  │       UNLOCK → return Result.allowed(remaining, ...)                │
  │    else:                                                            │
  │       retryAfter = compute time until enough weight drops off       │
  │       UNLOCK → return Result.denied(retryAfter, ...)                │
  └─────────────────────────────────────────────────────────────────────┘
```

#### Visual — Sliding Window Counter (The Key Insight)

```
Window = 60s, Limit = 100

  Previous Window        Current Window
  [00:00 ──── 01:00]    [01:00 ──── 02:00]
      84 requests            30 requests
                                    ↑
                              Now = 01:15
                              (25% into current window)

Overlap of previous window = 1.0 - (15/60) = 75%

Weighted count = 84 * 0.75 + 30 = 63 + 30 = 93

93 < 100 → ALLOWED (remaining ~7)

Compare to Fixed Window at the same moment:
  - Fixed window sees only 30 in [01:00-02:00] → allows 70 more!
  - Sliding window counter accounts for the 84 requests that partially overlap.
```

#### Java Implementation

```java
public class SlidingWindowCounterStrategy implements RateLimiterStrategy {

    private final ConcurrentHashMap<String, SlidingWindowCounter> windows
        = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        SlidingWindowCounter counter = windows.computeIfAbsent(key,
            k -> new SlidingWindowCounter());

        counter.lock.lock();
        try {
            long now = TimeUtils.currentTimeMillis();
            long windowSize = rule.getWindowSizeMs();
            long windowStart = now - (now % windowSize);

            // Rotate windows
            if (windowStart != counter.currentWindowStart) {
                if (windowStart - counter.currentWindowStart == windowSize) {
                    counter.previousCount = counter.currentCount;
                } else {
                    counter.previousCount = 0;
                }
                counter.currentCount = 0;
                counter.currentWindowStart = windowStart;
            }

            // Weighted count
            long elapsedInWindow = now - windowStart;
            double overlapPct = 1.0 - ((double) elapsedInWindow / windowSize);
            double weightedCount = (counter.previousCount * overlapPct)
                                   + counter.currentCount;

            if (weightedCount < rule.getMaxRequests()) {
                counter.currentCount++;
                int remaining = rule.getMaxRequests() - (int) Math.ceil(weightedCount) - 1;
                long resetAtMs = windowStart + windowSize;
                return RateLimitResult.allowed(
                    Math.max(remaining, 0), rule.getMaxRequests(), resetAtMs);
            } else {
                long resetAtMs = windowStart + windowSize;
                long retryAfterMs = resetAtMs - now;
                return RateLimitResult.denied(retryAfterMs, rule.getMaxRequests(), resetAtMs);
            }
        } finally {
            counter.lock.unlock();
        }
    }

    @Override
    public void reset(String key) { windows.remove(key); }

    @Override
    public Algorithm algorithm() { return Algorithm.SLIDING_WINDOW_COUNTER; }
}
```

---

### Algorithm Comparison — Quick Reference

```
┌──────────────────────┬───────────┬────────────┬────────────┬──────────────────┐
│ Algorithm            │ Memory    │ Accuracy   │ Burst?     │ Best For         │
├──────────────────────┼───────────┼────────────┼────────────┼──────────────────┤
│ Token Bucket         │ O(1)/key  │ Approx     │ Yes        │ API gateways     │
│ Leaky Bucket         │ O(N)/key  │ Exact rate │ No (smooth)│ Traffic shaping  │
│ Fixed Window         │ O(1)/key  │ Low        │ Boundary!  │ Simple counters  │
│ Sliding Window Log   │ O(N)/key  │ Exact      │ No         │ Precise billing  │
│ Sliding Window Ctr   │ O(1)/key  │ High       │ Smoothed   │ Production APIs  │
└──────────────────────┴───────────┴────────────┴────────────┴──────────────────┘
```

---

## 7. Service Layer Design

### 7.1 `RateLimiterService`

```java
public class RateLimiterService {

    private final Map<Algorithm, RateLimiterStrategy> strategyMap;
    private final RuleService ruleService;

    public RateLimiterService(List<RateLimiterStrategy> strategies, RuleService ruleService) {
        this.ruleService = ruleService;
        // Build lookup map: Algorithm -> Strategy instance
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(RateLimiterStrategy::algorithm, Function.identity()));
    }

    /**
     * Main entry point. Resolves the key, finds the rule, picks the strategy, checks the limit.
     */
    public RateLimitResult checkRateLimit(RequestContext context) {
        // 1. Try to find a rule for this context
        String key = resolveKey(context);
        Optional<RateLimitRule> ruleOpt = ruleService.findRule(key);

        if (ruleOpt.isEmpty() || !ruleOpt.get().isEnabled()) {
            // No rule or disabled → allow by default
            return RateLimitResult.allowed(Integer.MAX_VALUE, 0, 0);
        }

        RateLimitRule rule = ruleOpt.get();

        // 2. Look up the strategy for the rule's algorithm
        RateLimiterStrategy strategy = strategyMap.get(rule.getAlgorithm());
        if (strategy == null) {
            throw new IllegalStateException("No strategy for algorithm: " + rule.getAlgorithm());
        }

        // 3. Execute
        return strategy.tryConsume(key, rule);
    }

    /**
     * Resolve rate-limit key from context.
     * Tries multiple key patterns in priority order.
     */
    private String resolveKey(RequestContext context) {
        // Priority: user+endpoint > user > ip > endpoint
        for (ClientIdentifier identifier : List.of(
                ClientIdentifier.BY_USER_AND_ENDPOINT,
                ClientIdentifier.BY_USER,
                ClientIdentifier.BY_IP,
                ClientIdentifier.BY_ENDPOINT)) {
            String candidateKey = identifier.extractKey(context);
            if (ruleService.findRule(candidateKey).isPresent()) {
                return candidateKey;
            }
        }
        // Fallback: use IP-based key
        return ClientIdentifier.BY_IP.extractKey(context);
    }
}
```

**Interview Note on `resolveKey`:** The key resolution priority means a user-specific rule overrides an IP-based rule. This supports per-user quotas with global IP fallbacks.

### 7.2 `RuleService`

```java
public class RuleService {

    private final RuleRepository repository;

    public RuleService(RuleRepository repository) {
        this.repository = repository;
    }

    public Optional<RateLimitRule> findRule(String key) {
        return repository.findByKey(key);
    }

    public List<RateLimitRule> getAllRules() {
        return repository.findAll();
    }

    public void createRule(RateLimitRule rule) {
        if (!rule.isValid()) {
            throw new IllegalArgumentException("Invalid rule: " + rule.getKey());
        }
        repository.save(rule);
    }

    public void updateRule(RateLimitRule rule) {
        if (repository.findByKey(rule.getKey()).isEmpty()) {
            throw new RuleNotFoundException("Rule not found: " + rule.getKey());
        }
        repository.save(rule);
    }

    public void deleteRule(String key) {
        repository.delete(key);
    }
}
```

---

## 8. Controller (Simulated Middleware)

```java
public class RateLimiterController {

    private final RateLimiterService rateLimiterService;

    public RateLimiterController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Simulates an HTTP request going through a rate-limiting middleware.
     */
    public void handleRequest(RequestContext context) {
        System.out.println("──── Incoming Request ────");
        System.out.printf("  Client: %s | IP: %s | Endpoint: %s%n",
            context.getClientId(), context.getIpAddress(), context.getEndpoint());

        RateLimitResult result = rateLimiterService.checkRateLimit(context);

        // Set response headers (simulated)
        System.out.printf("  X-RateLimit-Limit:     %d%n", result.getLimit());
        System.out.printf("  X-RateLimit-Remaining: %d%n", result.getRemaining());
        System.out.printf("  X-RateLimit-Reset:     %d%n", result.getResetAtMs());

        if (result.isAllowed()) {
            System.out.println("  Status: 200 OK");
            // Forward to actual handler...
        } else {
            System.out.printf("  Status: 429 Too Many Requests%n");
            System.out.printf("  Retry-After: %d ms (%.1f seconds)%n",
                result.getRetryAfterMs(), result.getRetryAfterMs() / 1000.0);
        }
        System.out.println("──────────────────────────");
    }
}
```

### Middleware Pipeline Concept

```
Incoming HTTP Request
        │
        ▼
┌───────────────────┐
│   Auth Middleware  │ ← Extract clientId / IP
└───────┬───────────┘
        ▼
┌───────────────────┐
│  RateLimiter      │ ← Check limit → 429 if exceeded
│  Controller       │
└───────┬───────────┘
        ▼ (if allowed)
┌───────────────────┐
│  Business Logic   │ ← Actual API handler
│  Controller       │
└───────────────────┘
```

---

## 9. Concurrency Considerations

### Thread-Safety Strategy Per Component

| Component              | Mechanism                                                          |
|------------------------|--------------------------------------------------------------------|
| Strategy bucket/counter maps | `ConcurrentHashMap` — lock-free reads, segmented writes       |
| Individual bucket/counter    | `ReentrantLock` per key — fine-grained, no global bottleneck  |
| Window counter increment     | `AtomicInteger` for lock-free CAS where possible              |
| Rule repository              | `ConcurrentHashMap` — thread-safe CRUD                        |

### Why Per-Key Locking (Not Global Lock)

```
GLOBAL LOCK (bad):
  Thread-1 (user:alice) ──LOCK──┐
  Thread-2 (user:bob)   ──WAIT──┤  Bob waits for Alice. No reason.
  Thread-3 (user:carol) ──WAIT──┤  Carol waits for both. Throughput tanks.
                                │
  All threads serialize on one lock.

PER-KEY LOCK (good):
  Thread-1 (user:alice) ──LOCK(alice)──┐
  Thread-2 (user:bob)   ──LOCK(bob)────┐   No contention between keys.
  Thread-3 (user:carol) ──LOCK(carol)──┐   Parallel processing.
  Thread-4 (user:alice) ──WAIT(alice)──┤   Only same-key threads compete.
```

### Expired Entry Cleanup

```java
// Scheduled task to prevent memory leaks from stale entries
ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

cleaner.scheduleAtFixedRate(() -> {
    long now = System.currentTimeMillis();
    buckets.entrySet().removeIf(entry -> {
        TokenBucket bucket = entry.getValue();
        // If bucket hasn't been touched in 2x the window, evict it
        return (now - bucket.lastRefillTime) > 2 * maxWindowSizeMs;
    });
}, 1, 1, TimeUnit.MINUTES);
```

### ConcurrentHashMap.computeIfAbsent — Atomicity Guarantee

```java
// Thread-safe bucket creation — no duplicate buckets for the same key
TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(...));
// computeIfAbsent is atomic: only one thread creates the bucket if absent.
// Other threads for the same key will block until creation completes.
```

---

## 10. Validation & Error Handling

### Custom Exceptions

```java
public class RateLimitExceededException extends RuntimeException {
    private final RateLimitResult result;

    public RateLimitExceededException(RateLimitResult result) {
        super("Rate limit exceeded. Retry after " + result.getRetryAfterMs() + " ms");
        this.result = result;
    }

    public RateLimitResult getResult() { return result; }
}
```

```java
public class RuleNotFoundException extends RuntimeException {
    public RuleNotFoundException(String message) {
        super(message);
    }
}
```

### Validation Rules

```
┌────────────────────────────────────────────────────────────────┐
│                     Rule Validation Checks                     │
├────────────────────┬───────────────────────────────────────────┤
│ Field              │ Constraint                                │
├────────────────────┼───────────────────────────────────────────┤
│ key                │ Non-null, non-empty                       │
│ maxRequests        │ > 0                                       │
│ windowSizeMs       │ > 0                                       │
│ algorithm          │ Non-null, valid enum value                │
│ burstCapacity      │ >= 0 (0 means defaults to maxRequests)    │
│ id                 │ Non-null, non-empty                       │
└────────────────────┴───────────────────────────────────────────┘
```

### Error Handling Flow

```
Request → Service
           │
           ├── Rule not found?  → Allow (default open) OR throw RuleNotFoundException
           │                       (configurable: fail-open vs fail-closed)
           │
           ├── Rule disabled?   → Allow (bypass)
           │
           ├── Strategy missing → throw IllegalStateException (config error)
           │
           ├── Limit exceeded   → return RateLimitResult.denied(...)
           │                       Controller converts to 429 response
           │
           └── Allowed          → return RateLimitResult.allowed(...)
                                   Controller forwards to handler
```

---

## 11. Sample Workflows

### 11.1 Request Within Limit (Token Bucket)

```
Client                Controller            Service               TokenBucketStrategy
  │                       │                     │                         │
  │  POST /api/orders     │                     │                         │
  │──────────────────────▶│                     │                         │
  │                       │  checkRateLimit(ctx) │                         │
  │                       │─────────────────────▶│                         │
  │                       │                     │  resolveKey(ctx)         │
  │                       │                     │  → "user:alice"          │
  │                       │                     │  findRule("user:alice")  │
  │                       │                     │  → Rule(max=100, alg=TB) │
  │                       │                     │                         │
  │                       │                     │  tryConsume("user:alice",│
  │                       │                     │             rule)        │
  │                       │                     │────────────────────────▶│
  │                       │                     │                         │
  │                       │                     │                    refill tokens
  │                       │                     │                    tokens: 97 → 98
  │                       │                     │                    consume 1 → 97
  │                       │                     │                         │
  │                       │                     │  Result(allowed=true,    │
  │                       │                     │         remaining=97)    │
  │                       │                     │◀────────────────────────│
  │                       │ Result              │                         │
  │                       │◀────────────────────│                         │
  │  200 OK               │                     │                         │
  │  X-RateLimit-Rem: 97  │                     │                         │
  │◀──────────────────────│                     │                         │
```

### 11.2 Request Exceeding Limit

```
Client                Controller            Service               Strategy
  │                       │                     │                     │
  │  POST /api/orders     │                     │                     │
  │──────────────────────▶│                     │                     │
  │                       │  checkRateLimit(ctx) │                     │
  │                       │─────────────────────▶│                     │
  │                       │                     │  tryConsume(key,rule)│
  │                       │                     │────────────────────▶│
  │                       │                     │                     │
  │                       │                     │              tokens = 0.3
  │                       │                     │              0.3 < 1.0 → DENIED
  │                       │                     │              retryAfter = 700ms
  │                       │                     │                     │
  │                       │                     │  Result(allowed=false│
  │                       │                     │   retryAfterMs=700) │
  │                       │                     │◀────────────────────│
  │                       │ Result              │                     │
  │                       │◀────────────────────│                     │
  │  429 Too Many Requests│                     │                     │
  │  Retry-After: 1s      │                     │                     │
  │◀──────────────────────│                     │                     │
```

### 11.3 Token Refill Over Time

```
Time ─────────────────────────────────────────────────────────────▶
      t=0        t=500ms     t=1000ms    t=1500ms    t=2000ms

Bucket (capacity=5, refill=2 tokens/sec):

  [5]  ──req──▶  [4]  ──1tok──▶  [5]  ──3reqs──▶ [2] ──1tok──▶ [3]
  full          consumed  refilled    burst       consumed  refilling
                1 token   +1 token    3 tokens    capped     +1 token

Detailed:
  t=0:     tokens=5.0       (full)
  t=0:     request → consume → tokens=4.0
  t=500:   refill 1.0 → tokens=5.0 (capped at capacity)
  t=500:   3 requests → 5.0→4.0→3.0→2.0
  t=1000:  refill 1.0 → tokens=3.0
```

### 11.4 Switching Algorithm at Runtime

```
Admin               RuleService         Repository         RateLimiterService
  │                     │                   │                     │
  │ updateRule(          │                   │                     │
  │  key="user:alice",   │                   │                     │
  │  algorithm=          │                   │                     │
  │  SLIDING_WINDOW_CTR) │                   │                     │
  │────────────────────▶│                   │                     │
  │                     │  save(updatedRule) │                     │
  │                     │──────────────────▶│                     │
  │                     │                   │ stored              │
  │  OK                 │                   │                     │
  │◀────────────────────│                   │                     │
  │                     │                   │                     │
  ═══════ NEXT REQUEST FROM ALICE ══════════════════════════════════
  │                     │                   │                     │
  │                     │                   │  checkRateLimit(ctx)│
  │                     │                   │                     │
  │                     │  findRule →  Rule(alg=SLIDING_WINDOW_CTR)
  │                     │                   │                     │
  │                     │  strategyMap.get(SLIDING_WINDOW_COUNTER) │
  │                     │  → SlidingWindowCounterStrategy          │
  │                     │                   │                     │
  │                     │  Now using new algorithm! No restart needed.
```

**Key Point:** Because the strategy is resolved per-request from the rule's algorithm field, changing the rule's algorithm instantly switches the strategy. No restart, no redeployment.

### 11.5 Boundary Spike in Fixed Window

```
Limit = 10 requests per 60-second window

  Window A [00:00 ──────────── 01:00]   Window B [01:00 ──────────── 02:00]
                                   │     │
                          t=00:55  │     │ t=01:05
                          10 reqs  │     │ 10 reqs
                             ↓     │     │    ↓
                          ┌──┐     │     │ ┌──┐
                          │10│     │     │ │10│
                          └──┘     │     │ └──┘
                                   │     │
  Fixed Window says:               │     │
    Window A count = 10 → OK       │     │ Window B count = 10 → OK
                                   │     │
  Reality:                         │     │
    20 requests in 10 seconds!     │◀───▶│
    (00:55 to 01:05)               │10sec │
                                   │     │

  Sliding Window Counter at t=01:05:
    overlapPct = 1.0 - (5/60) = 0.917
    weighted = 10 * 0.917 + 10 = 19.17
    19.17 > 10 → DENIED! (Catches the spike)
```

---

## 12. Design Patterns Used

| #  | Pattern                  | Where Used                          | Why                                                            | Interview One-Liner                                                                   |
|----|--------------------------|-------------------------------------|----------------------------------------------------------------|---------------------------------------------------------------------------------------|
| 1  | **Strategy**             | `RateLimiterStrategy` + 5 impls     | Swap algorithms at runtime without changing client code        | "Define a family of algorithms, encapsulate each, and make them interchangeable."      |
| 2  | **Factory**              | `AppConfig` wiring strategy map     | Centralize strategy creation, decouple instantiation from use  | "Create objects without exposing creation logic to the client."                         |
| 3  | **Builder**              | `RateLimitRule.Builder`             | Readable construction of objects with many optional parameters | "Separate construction of a complex object from its representation."                   |
| 4  | **Repository**           | `RuleRepository` interface          | Abstract persistence — swap in-memory for Redis/DB seamlessly  | "Mediates between domain and data mapping layers using a collection-like interface."    |
| 5  | **Chain of Responsibility** | Middleware pipeline concept       | Each middleware (auth, rate-limit, handler) passes or halts    | "Pass requests along a chain of handlers; each decides to process or pass it on."      |
| 6  | **Template Method**      | Common tryConsume flow              | All strategies share: get-state → compute → decide → return   | "Define the skeleton of an algorithm, letting subclasses override specific steps."      |

### Strategy Pattern — Deep Dive

```
                    ┌──────────────────────────┐
                    │  RateLimiterService       │
                    │                          │
                    │  strategyMap:             │
                    │  TOKEN_BUCKET ──▶ [impl] │
                    │  LEAKY_BUCKET ──▶ [impl] │
                    │  FIXED_WINDOW ──▶ [impl] │
                    │  SW_LOG ────────▶ [impl] │
                    │  SW_COUNTER ───▶ [impl]  │
                    └──────────────────────────┘

  At runtime, the rule says algorithm=TOKEN_BUCKET:
    → strategyMap.get(TOKEN_BUCKET) → TokenBucketStrategy
    → strategy.tryConsume(key, rule)

  Admin changes rule to SLIDING_WINDOW_COUNTER:
    → strategyMap.get(SLIDING_WINDOW_COUNTER) → SlidingWindowCounterStrategy
    → strategy.tryConsume(key, rule)

  Service code never changes. Open/Closed Principle.
```

### Builder Pattern — Why Not Constructor

```java
// Without Builder — constructor with 7 params (unreadable, error-prone):
new RateLimitRule("id1", "user:alice", 100, 60000L, Algorithm.TOKEN_BUCKET, 150, true);
//                 ↑       ↑           ↑     ↑          ↑                   ↑    ↑
//              Which is which? Easy to swap maxRequests and burstCapacity.

// With Builder — self-documenting:
RateLimitRule.builder()
    .id("id1")
    .key("user:alice")
    .maxRequests(100)
    .windowSizeMs(60_000)
    .algorithm(Algorithm.TOKEN_BUCKET)
    .burstCapacity(150)
    .enabled(true)
    .build();
```

---

## 13. Extensibility Points

### 13.1 Adding a New Algorithm

1. Add a new value to the `Algorithm` enum (e.g., `ADAPTIVE`).
2. Create a class implementing `RateLimiterStrategy`.
3. Register it in `AppConfig` — it gets picked up automatically by the strategy map.
4. No changes needed in `RateLimiterService`, `RuleService`, or `Controller`.

```
Effort:  1 new class + 1 enum constant + 1 line in config.
Changes: Zero in existing service code. Open/Closed Principle.
```

### 13.2 Redis-Backed Counter Store

```
Current:
  Strategy → ConcurrentHashMap<String, TokenBucket> (in-memory, single JVM)

Extensible to:
  Strategy → CounterStore interface
               ├── InMemoryCounterStore  (current, for single JVM)
               └── RedisCounterStore     (distributed, for multi-node)

RedisCounterStore would use:
  - Redis INCR / DECR for atomic counters
  - Redis EXPIRE for automatic TTL-based cleanup
  - Lua scripts for atomic check-and-increment (no race conditions)
  - Redis Sorted Sets for sliding window log timestamps
```

### 13.3 Rule Hot-Reload

```
Option A: Poll-based
  ScheduledExecutor polls DB/config-server every N seconds.
  RuleService refreshes its cache.

Option B: Event-driven
  Admin updates rule → publishes event (Kafka/Redis Pub-Sub)
  RuleService subscribes → updates in-memory cache instantly.

Since strategies are stateless w.r.t. rules (they receive the rule per-call),
changing a rule takes effect on the very next request.
```

### 13.4 Per-Tenant Isolation

```
Current key:  "user:alice"
Tenant key:   "tenant:acme:user:alice"

Add a new ClientIdentifier:
  BY_TENANT_AND_USER {
      extractKey(ctx) → "tenant:" + ctx.getHeader("X-Tenant-Id") + ":user:" + ctx.getClientId()
  }

Each tenant gets its own rate-limit rule and counter space.
No cross-tenant interference.
```

### 13.5 Rate Limit by Composite Key

```java
// Already supported via ClientIdentifier.BY_USER_AND_ENDPOINT:
//   key = "user:alice:api:/orders"

// For more complex composites, add a new enum value or use a strategy:
BY_CUSTOM {
    extractKey(ctx) {
        return ctx.getHeaders().getOrDefault("X-RateLimit-Key",
            "user:" + ctx.getClientId() + ":ip:" + ctx.getIpAddress());
    }
}
```

### 13.6 Summary of Extension Points

```
┌──────────────────────────┬───────────────────────────────────────────────┐
│ Extension                │ Mechanism                                     │
├──────────────────────────┼───────────────────────────────────────────────┤
│ New algorithm            │ Implement RateLimiterStrategy + register      │
│ Distributed counters     │ Extract CounterStore interface, add Redis impl│
│ Rule hot-reload          │ Event-driven cache refresh in RuleService     │
│ Per-tenant isolation     │ New ClientIdentifier enum + tenant-scoped keys│
│ Composite rate-limit key │ New ClientIdentifier or custom header-based   │
│ Metrics / monitoring     │ Decorator around RateLimiterStrategy          │
│ Rate limit response body │ Extend RateLimitResult with custom fields     │
│ Fail-open vs fail-closed │ Config flag in RateLimiterService             │
└──────────────────────────┴───────────────────────────────────────────────┘
```

---

## Quick Interview Cheat Sheet

```
Q: "Design a rate limiter."

1. Clarify: Single server or distributed? Which algorithm?
2. Start with the Strategy pattern — interface + 5 implementations.
3. Draw the class diagram — show Service, Strategy, Rule, Result.
4. Walk through Token Bucket (most common ask) step by step.
5. Explain Fixed Window boundary problem → Sliding Window Counter solution.
6. Mention concurrency: ConcurrentHashMap + per-key ReentrantLock.
7. Mention extensibility: new algorithms via Strategy, Redis via Repository.
8. Time permits: discuss distributed rate limiting (Redis + Lua scripts).
```

---
