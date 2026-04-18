# CAP Theorem and the Rate Limiter

> How CAP theorem applies to distributed rate limiting. Interview-ready explanations, tradeoff tables, and follow-up Q&A.

---

## Table of Contents

1. [CAP Theorem Quick Recap](#1-cap-theorem-quick-recap)
2. [Rate Limiter is an AP System](#2-rate-limiter-is-an-ap-system)
3. [Fail-Open vs Fail-Closed](#3-fail-open-vs-fail-closed)
4. [Where Consistency Matters](#4-where-consistency-matters)
5. [Distributed Counter Challenge](#5-distributed-counter-challenge)
6. [Redis Consistency Model](#6-redis-consistency-model)
7. [Race Conditions](#7-race-conditions)
8. [Common Interview Follow-Up Q&A](#8-common-interview-follow-up-qa)

---

## 1. CAP Theorem Quick Recap

In a distributed system, you can only guarantee **two of three** properties simultaneously during a network partition:

```
              C
             / \
            /   \
           /     \
          / PICK  \
         /  TWO    \
        /           \
       A ----------- P
```

| Property | Meaning | Example |
|----------|---------|---------|
| **C** -- Consistency | Every read returns the most recent write | All nodes see counter = 97 |
| **A** -- Availability | Every request gets a (non-error) response | Rate limiter always responds allow/deny |
| **P** -- Partition Tolerance | System works despite network splits between nodes | Node A cannot reach Node B |

**Key insight**: P is not optional in distributed systems -- networks partition. The real choice is between **CP** (sacrifice availability) and **AP** (sacrifice consistency).

---

## 2. Rate Limiter is an AP System

### Why AP?

A rate limiter must **always respond**. If a rate limiter becomes unavailable, one of two bad things happens:

- **All requests blocked**: Legitimate users cannot access the service (outage)
- **All requests allowed**: The service is unprotected but functional (degraded)

The second option is always preferable. A rate limiter that causes outages is worse than no rate limiter at all.

### The Tradeoff in Practice

```
  Normal Operation (no partition):
  +---------+     +---------+
  | Node A  |<--->| Node B  |    Both nodes share counters via Redis
  | count=48|     | count=48|    User limit: 100 req/min
  +---------+     +---------+    Consistent: YES

  During Partition:
  +---------+  X  +---------+
  | Node A  |  X  | Node B  |    Network split -- nodes cannot sync
  | count=48|  X  | count=48|
  +---------+  X  +---------+

  After 52 more requests (26 to each node):
  +---------+  X  +---------+
  | Node A  |  X  | Node B  |    Each node thinks user has 74 requests
  | count=74|  X  | count=74|    Real total: 100 (at limit)
  +---------+  X  +---------+    But neither node rejects yet!

  After 100 more requests (50 to each):
  +---------+  X  +---------+
  | Node A  |  X  | Node B  |    Each node thinks user has 124 requests
  | count=124| X  | count=124|   Real total: 200 (2x limit!)
  +---------+  X  +---------+    Each node rejects at its own 100
```

**Result**: During a partition, the user might get up to `N * limit` requests through, where N is the number of partitioned nodes. In practice, this is **110-120%** of the limit for brief partitions.

### Interview Answer

> "Rate limiters are AP systems. During a network partition, I would rather allow 10-20% over-limit than block legitimate traffic. The rate limiter's job is protection, not precision. If it causes an outage by being unavailable, it has failed its primary purpose. For billing-critical limits, I would use a separate CP counter with strong consistency."

---

## 3. Fail-Open vs Fail-Closed

### Decision Matrix

| Scenario | Fail-Open (AP) | Fail-Closed (CP) |
|----------|----------------|-------------------|
| **Behavior on failure** | Allow the request through | Reject the request |
| **Risk** | Briefly over-limit | Legitimate users blocked |
| **User experience** | Seamless (user unaware) | Errors, retries, frustration |
| **When appropriate** | General API rate limiting | Financial transactions, billing |
| **Production default** | YES -- most systems | Rare -- only for critical paths |

### Fail-Open Implementation

```java
public RateLimitResult check(RequestContext ctx, RateLimitRule rule) {
    try {
        RateLimiterStrategy strategy = strategies.get(rule.getAlgorithm());
        return strategy.tryConsume(ctx.getRateLimitKey(), rule);
    } catch (RedisConnectionException | TimeoutException e) {
        // FAIL-OPEN: Redis is down, allow the request
        log.warn("Rate limiter unavailable, failing open for key={}", ctx.getRateLimitKey());
        metrics.increment("rate_limiter.fail_open");
        return RateLimitResult.allowed(rule.getMaxRequests(), rule.getMaxRequests(), 0);
    }
}
```

### Fail-Closed Implementation

```java
public RateLimitResult checkStrict(RequestContext ctx, RateLimitRule rule) {
    try {
        RateLimiterStrategy strategy = strategies.get(rule.getAlgorithm());
        return strategy.tryConsume(ctx.getRateLimitKey(), rule);
    } catch (RedisConnectionException | TimeoutException e) {
        // FAIL-CLOSED: Cannot verify limit, reject to be safe
        log.error("Rate limiter unavailable, failing closed for key={}", ctx.getRateLimitKey());
        metrics.increment("rate_limiter.fail_closed");
        return RateLimitResult.rejected(rule.getMaxRequests(), 1000, 0);
    }
}
```

### Deep Dive: When to Use Each

| Use Case | Strategy | Reasoning |
|----------|----------|-----------|
| Public API rate limiting | Fail-open | User experience > precision |
| Free tier usage caps | Fail-open | Worst case: free user gets extra requests |
| Paid tier billing limits | Fail-closed | Overages cost real money |
| DDoS protection | Fail-open (at edge) | Better to let some through than crash |
| Payment processing | Fail-closed | Double charges are unacceptable |
| Login attempt limiting | Fail-closed | Security > availability |

---

## 4. Where Consistency Matters

Not all rate limiting is equal. Some counters demand strong consistency:

```
  +-------------------+------------------+--------------------+
  |   Use Case        |  Consistency     |  Why               |
  +-------------------+------------------+--------------------+
  | API rate limiting  | Eventual (AP)   | 10% over is fine   |
  | DDoS protection    | Eventual (AP)   | Approximate is OK  |
  | Billing counters   | Strong (CP)     | Money is involved  |
  | Login attempts     | Strong (CP)     | Security critical  |
  | SMS/email sending  | Strong (CP)     | Real cost per msg  |
  +-------------------+------------------+--------------------+
```

### Hybrid Approach

In production, you often run **two rate limiters**:

```
  Request
    |
    v
  +----------------------------+
  | AP Rate Limiter (Redis)    |   General traffic shaping
  | Fail-open, eventual        |   99% of requests hit only this
  +-------------+--------------+
                |
                v (if allowed)
  +----------------------------+
  | CP Rate Limiter (DB + lock)|   Billing/payment paths only
  | Fail-closed, strong        |   Slow but accurate
  +----------------------------+
```

---

## 5. Distributed Counter Challenge

### The Core Problem

Multiple rate limiter nodes must share counters. Each node could be counting independently.

```
  Client (limit: 100/min)
    |
    +----> Node A (via LB) ----> Redis Primary
    |                               |
    +----> Node B (via LB) ----> Redis Primary  (same key)
    |                               |
    +----> Node C (via LB) ----> Redis Primary
```

### Centralized Counter (Redis)

All nodes talk to the same Redis instance. This solves the distributed counter problem but introduces a single point of failure.

```
  Pros:
  - Single source of truth
  - Atomic operations (INCR)
  - Sub-millisecond latency

  Cons:
  - Single point of failure (mitigate with Redis Sentinel/Cluster)
  - Network latency on every rate limit check
  - Redis becomes a critical dependency
```

### Local Counter with Sync (Alternative)

Each node maintains a local counter and periodically syncs. Trades consistency for latency.

```
  Node A: local_count = 30  --sync every 1s-->  Redis: total = 90
  Node B: local_count = 25  --sync every 1s-->  Redis: total = 90
  Node C: local_count = 35  --sync every 1s-->  Redis: total = 90

  Between syncs: each node might allow extra requests
  Accuracy: within (num_nodes * sync_interval * request_rate) of true count
```

### Sticky Sessions Approach

Route the same client to the same node always. Each node tracks only "its" clients.

```
  Pros:
  - No distributed counter needed
  - Fast (local memory only)

  Cons:
  - Uneven load distribution
  - Node failure = lost counters for all its clients
  - Does not work with multiple API keys per client
```

---

## 6. Redis Consistency Model

### Single Key Consistency

Redis is **single-threaded** for command execution. For a single key:

```
  Thread 1: INCR user:123:count  -->  Redis executes atomically
  Thread 2: INCR user:123:count  -->  Redis queues, executes next

  Result: Sequential consistency for single-key operations
  No race condition possible on a single Redis instance
```

### Multi-Key / Cross-Node Consistency

With Redis Cluster (multiple shards):

```
  Key "user:123" --> Shard A (slot 7823)
  Key "user:456" --> Shard B (slot 2941)

  Cross-key atomicity: NOT guaranteed without Lua scripts
  Cross-shard atomicity: NOT guaranteed
```

### Replication Consistency

```
  Redis Primary ---async replication---> Redis Replica

  Write to Primary: count = 100
  Read from Replica: count = 99  (replication lag)

  For rate limiting: read from primary OR accept slight inconsistency
```

---

## 7. Race Conditions

### The Classic Read-Then-Write Race

```
  Thread A                    Thread B
  --------                    --------
  READ count = 99             READ count = 99
  99 < 100? YES               99 < 100? YES
  WRITE count = 100           WRITE count = 100
                              
  Actual count should be 101 (over limit!)
  But both threads saw 99 and both incremented to 100
```

### Solution: Atomic INCR

```
  Thread A                    Thread B
  --------                    --------
  INCR count -> returns 100   (queued)
  100 <= 100? YES, allow      INCR count -> returns 101
                              101 <= 100? NO, reject
```

### Solution: Lua Script (Multi-Step Atomic)

For algorithms that need multi-step logic (e.g., token bucket), use a Lua script:

```lua
-- Token bucket Lua script: runs atomically in Redis
local key = KEYS[1]
local max_tokens = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or max_tokens
local last_refill = tonumber(bucket[2]) or now

-- Refill tokens based on elapsed time
local elapsed = now - last_refill
local new_tokens = math.min(max_tokens, tokens + (elapsed * refill_rate))

if new_tokens >= 1 then
    -- Allow: consume one token
    redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_refill', now)
    redis.call('EXPIRE', key, 3600)
    return {1, math.floor(new_tokens - 1)}  -- {allowed, remaining}
else
    -- Reject
    return {0, 0}
end
```

### Comparison: Approaches to Atomicity

| Approach | Atomicity | Latency | Complexity |
|----------|-----------|---------|------------|
| Redis INCR | Single operation | 1 round trip | Low |
| Redis Lua script | Multi-step atomic | 1 round trip | Medium |
| Redis MULTI/EXEC | Transactional | 2+ round trips | Medium |
| Distributed lock (Redlock) | Cross-key atomic | 3+ round trips | High |
| Optimistic locking (WATCH) | Retry-based | Variable | Medium |

---

## 8. Common Interview Follow-Up Q&A

### Q1: "What happens if Redis goes down?"

> "We fail open. The rate limiter returns 'allowed' for all requests. We fire an alert so the on-call team knows. Meanwhile, the service continues to function -- it is unprotected but available. We also track a `rate_limiter.fail_open` metric to measure exposure. For critical paths like billing, we fail closed instead."

### Q2: "How do you handle rate limiting across multiple data centers?"

> "Two approaches. First, a global Redis cluster (e.g., AWS ElastiCache Global Datastore) with async replication -- simple but adds cross-region latency. Second, local rate limiters in each region with a fraction of the global limit (e.g., 3 regions, each gets 33% of the limit). The second approach is faster but wastes capacity if traffic is uneven. A hybrid uses local counters with periodic sync to a global counter."

### Q3: "Can a user game the system by hitting different nodes?"

> "Not with centralized Redis counters -- all nodes increment the same key. If we use local counters, yes, they could get N times the limit where N is the number of nodes. That is why centralized Redis is the standard approach. Sticky sessions are a partial mitigation but have their own problems (uneven load, failover)."

### Q4: "What about clock skew between nodes?"

> "Clock skew matters for time-window algorithms. If Node A thinks it is 12:00:01 and Node B thinks it is 11:59:59, they might disagree on which window a request belongs to. Mitigations: (1) Use Redis server time instead of local time (`TIME` command). (2) Use NTP to keep clocks synchronized within a few milliseconds. (3) Choose algorithms less sensitive to skew -- Token Bucket is time-delta-based and tolerates small skew better than Fixed Window."

### Q5: "How do you avoid a thundering herd when a window resets?"

> "Fixed Window has this problem -- all counters reset at the same time, creating a burst at window boundaries. Mitigations: (1) Use Sliding Window Counter or Sliding Window Log, which do not have hard resets. (2) Add jitter to window boundaries. (3) Use Token Bucket, which refills gradually. In our design, the algorithm choice in `RateLimitRule` lets us pick the right algorithm per use case."

### Q6: "How would you rate-limit by cost instead of count?"

> "Some endpoints are more expensive than others. Instead of counting requests, we count 'cost units.' A lightweight GET costs 1 unit, a heavy analytics query costs 10 units. The strategy interface stays the same -- `tryConsume` could accept a `cost` parameter instead of always consuming 1. The rule defines max cost-units per window instead of max requests."

### Q7: "What is the difference between rate limiting and throttling?"

> "Rate limiting is a hard boundary -- request 101 out of 100 gets a 429 response. Throttling is a softer mechanism -- it slows down requests by adding delay or queueing them. The Leaky Bucket algorithm naturally throttles because it processes requests at a fixed rate. Token Bucket is more of a hard rate limiter that allows bursts. In practice, the terms are often used interchangeably."

---

## Interview Cheat Sheet: CAP + Rate Limiter

```
Interviewer: "How does CAP apply to rate limiting?"

You:
"A rate limiter is an AP system. During a network partition, we
choose availability over consistency -- it is better to allow 10-20%
over-limit than to block legitimate users.

Practically this means: fail-open on Redis failure, use atomic INCR
to avoid race conditions, and accept eventual consistency for general
rate limiting.

For billing-critical paths, we use a separate CP counter with strong
consistency and fail-closed semantics.

The real-world tradeoff is: most rate limiters do not need exact
counts. They need approximate counts at very low latency. Redis
single-threaded INCR gives us sequential consistency per key, which
is good enough for 99% of rate limiting use cases."
```
