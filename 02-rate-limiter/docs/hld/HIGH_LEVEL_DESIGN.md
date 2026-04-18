# High-Level Design: Rate Limiter System

> **Interview context**: Senior Java Developer (7+ years). Optimized for a 30-45 minute system design round.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Traffic Estimates](#7-traffic-estimates)
8. [Rate Limiting Algorithms](#8-rate-limiting-algorithms---the-core)
9. [High-Level Architecture](#9-high-level-architecture)
10. [Component Deep Dive](#10-component-deep-dive)
11. [Distributed Rate Limiting](#11-distributed-rate-limiting)
12. [Scaling Strategy](#12-scaling-strategy)
13. [Database Choice](#13-database-choice)
14. [Caching Strategy](#14-caching-strategy)
15. [Fault Tolerance](#15-fault-tolerance)
16. [CAP Theorem Analysis](#16-cap-theorem-analysis)
17. [Cloud Services Mapping](#17-cloud-services-mapping)
18. [Tradeoffs Summary](#18-tradeoffs-summary)
19. [Interview Talking Points](#19-interview-talking-points)

---

## 1. Problem Statement

A **rate limiter** controls how many requests a client can send to a server within a defined time window. It is a critical defense mechanism for any large-scale distributed system.

### Why Every Large-Scale System Needs One

| Concern              | Without Rate Limiting                                     |
|----------------------|-----------------------------------------------------------|
| **Abuse Prevention** | A single bad actor can DDoS your entire platform          |
| **Resource Protection** | Runaway scripts or bugs can exhaust CPU, memory, DB connections |
| **Fairness**         | One noisy tenant starves other users of capacity          |
| **Cost Control**     | Uncontrolled traffic inflates cloud bills (especially serverless) |
| **Stability**        | Cascading failures when downstream services get overwhelmed |

Real-world examples: Twitter limits 300 tweets/3hrs, Stripe limits 100 API calls/sec, GitHub limits 5000 requests/hr per authenticated user.

---

## 2. Scope

### In Scope

- Configurable rate limiting per **user ID**, **IP address**, **API endpoint**, or any combination
- Multiple rate limiting algorithms (Token Bucket, Leaky Bucket, Fixed Window, Sliding Window Log, Sliding Window Counter)
- Distributed rate limiting across multiple application servers via Redis
- Throttle response: HTTP **429 Too Many Requests** with proper headers
- Admin CRUD for rate limit rules
- Allow-listing and deny-listing of clients
- Rate limit response headers (`X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`)

### Out of Scope

- Full API Gateway implementation (routing, transformation, orchestration)
- Authentication and authorization (OAuth, JWT validation)
- Billing and usage metering
- Request logging and analytics dashboards
- DDoS mitigation at the network layer (L3/L4)

---

## 3. Assumptions

| Assumption | Value |
|------------|-------|
| Total throughput | 10M+ requests/sec across all services |
| Deployment model | Multi-tenant SaaS platform |
| Latency overhead budget | Sub-millisecond for local, <5ms for distributed checks |
| Rule storage | Centralized DB, evaluated locally or via Redis |
| Acceptable error margin | Slight under-limiting (allowing a few extra) is better than over-limiting (blocking legitimate traffic) |
| Client identification | Clients are identifiable by user ID, API key, or IP address |
| Clock synchronization | NTP-synced servers with <100ms clock drift |

---

## 4. Functional Requirements

| ID   | Requirement |
|------|-------------|
| FR-1 | Define rate limit rules: **X requests per Y time window per key** (e.g., 100 req/min per user) |
| FR-2 | Support multiple limiting keys: user ID, IP address, API endpoint, or composite keys (user + endpoint) |
| FR-3 | Return **HTTP 429 Too Many Requests** when a client exceeds the configured limit |
| FR-4 | Support **allow-listing** (VIP clients bypass limits) and **deny-listing** (blocked clients always rejected) |
| FR-5 | Configurable rules per API endpoint and per tenant |
| FR-6 | Return rate limit headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After` |
| FR-7 | Rules can be created, read, updated, and deleted via Admin API |
| FR-8 | Support multiple algorithms selectable per rule |

---

## 5. Non-Functional Requirements

| Requirement | Target | Notes |
|-------------|--------|-------|
| **Latency** | <5ms overhead per request | Must not noticeably slow down the request pipeline |
| **Availability** | 99.99% | Rate limiter downtime = either all traffic blocked or all traffic allowed |
| **Scalability** | 10M+ req/sec | Horizontally scalable with traffic growth |
| **Consistency** | Eventual | Some brief inaccuracy is acceptable; over-limiting is worse than under-limiting |
| **Fault Tolerance** | Fail-open by default | If Redis is down, allow requests rather than block legitimate users |
| **Configurability** | Hot-reload rules | Change limits without redeploying services |
| **Observability** | Metrics + alerts | Track rejection rate, latency percentiles, Redis health |

---

## 6. API Design

> The rate limiter is **middleware**, not a standalone REST service. But it exposes internal APIs and admin endpoints.

### 6.1 Rate Limit Check API (Internal)

This is called internally by the middleware on every inbound request:

```
isAllowed(clientId, endpoint, timestamp)
  --> { allowed: boolean, remaining: int, retryAfter: long }
```

Example (Java interface):

```java
public class RateLimitResult {
    boolean allowed;
    int remaining;       // requests remaining in current window
    long retryAfter;     // seconds until the client can retry (0 if allowed)
    long resetTimestamp;  // epoch when the window resets
}

public interface RateLimiter {
    RateLimitResult isAllowed(String clientId, String endpoint, long timestamp);
}
```

### 6.2 Admin APIs (REST)

```
POST   /admin/rate-limits          -- Create a new rule
GET    /admin/rate-limits           -- List all rules
GET    /admin/rate-limits/{id}      -- Get rule by ID
PUT    /admin/rate-limits/{id}      -- Update a rule
DELETE /admin/rate-limits/{id}      -- Delete a rule
```

**Create Rule Request Body**:

```json
{
  "key_type": "user_id",          // user_id | ip | endpoint | composite
  "endpoint_pattern": "/api/v1/orders/**",
  "max_requests": 100,
  "window_size_seconds": 60,
  "algorithm": "SLIDING_WINDOW_COUNTER",
  "tenant_id": "tenant-abc",
  "action": "THROTTLE",           // THROTTLE | BLOCK | LOG_ONLY
  "priority": 10
}
```

### 6.3 Response Headers Returned to Client

When the rate limiter processes a request, these headers are added to the HTTP response:

```
HTTP/1.1 200 OK                          (or 429 Too Many Requests)
X-RateLimit-Limit: 100                   -- max allowed in window
X-RateLimit-Remaining: 47                -- remaining in current window
X-RateLimit-Reset: 1672531260            -- epoch when window resets
Retry-After: 23                          -- seconds to wait (only on 429)
```

---

## 7. Traffic Estimates

### Request Volume

```
Total throughput:           10,000,000 requests/sec (10M)
Rate limit checks per req:  1 (each request = 1 check)
Total rate limit ops/sec:   10,000,000
```

### Redis Capacity Planning

```
Single Redis node:          ~100,000 - 200,000 ops/sec
Nodes needed (pure Redis):  10M / 150K = ~67 nodes (pessimistic)
                            10M / 200K = ~50 nodes (optimistic)

With local + distributed hybrid approach:
  - 80% of checks served locally (hot path, recently seen clients)
  - 20% hit Redis = 2M ops/sec
  - Nodes needed: 2M / 150K = ~14 Redis nodes
```

### Storage Estimates

```
Rules DB:
  - 10,000 rules x 1KB each = ~10 MB (tiny, fits in any DB)

Redis Counters:
  - Active clients: ~50M unique keys
  - Per key: ~100 bytes (key + counter + TTL metadata)
  - Total: 50M x 100B = ~5 GB
  - With replication (3x): ~15 GB across cluster
```

### Bandwidth

```
Per rate limit check payload: ~200 bytes
Total bandwidth: 10M x 200B = ~2 GB/sec (internal network only)
```

---

## 8. Rate Limiting Algorithms -- THE CORE

This is the **centerpiece** of the rate limiter interview. You must know all five algorithms cold.

---

### 8.1 Token Bucket

**How it works**: A bucket holds up to `B` tokens. Tokens are added at a steady rate `R` tokens/sec. Each request removes one token. If the bucket is empty, the request is rejected. This naturally allows short bursts (up to `B` requests at once) while enforcing a long-term average rate of `R`.

```
    Tokens added at rate R
           |
           v
    +------+------+
    | Token Bucket |   capacity = B
    | [****      ] |   (4 tokens available)
    +------+------+
           |
           v
    Request takes 1 token
    - tokens > 0 ? ALLOW (tokens--)
    - tokens = 0 ? REJECT (429)
```

**Timeline example** (B=5, R=1 token/sec):

```
Time:    0s   1s   2s   3s   4s   5s   6s   7s   8s
Tokens:  5    5    5    5    5    5    5    5    5   (filling, no traffic)

Burst arrives at T=5s: 5 requests at once
Tokens:  5 -> 4 -> 3 -> 2 -> 1 -> 0
6th request at T=5s: REJECTED (bucket empty)
T=6s: 1 token refilled, 1 request allowed
```

**Pseudocode**:

```java
class TokenBucket {
    double tokens;
    long lastRefillTimestamp;
    double maxTokens;      // B - bucket capacity
    double refillRate;     // R - tokens per second

    synchronized boolean isAllowed() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillTimestamp) / 1e9;
        tokens = Math.min(maxTokens, tokens + elapsed * refillRate);
        lastRefillTimestamp = now;
    }
}
```

| Aspect     | Detail |
|------------|--------|
| **Pros**   | Simple, allows controlled bursts, memory-efficient (2 values per key) |
| **Cons**   | Two parameters to tune (bucket size + refill rate) |
| **Use case** | General-purpose API rate limiting |
| **Memory** | O(1) per key -- just `tokens` and `lastRefillTimestamp` |
| **Accuracy** | High -- natural burst allowance is usually desirable |
| **Used by** | AWS (API Gateway), Stripe, most CDNs |

---

### 8.2 Leaky Bucket

**How it works**: Requests enter a FIFO queue (the bucket). The queue drains (processes) at a fixed rate. If the queue is full, new requests are dropped. Unlike Token Bucket, output rate is perfectly smooth.

```
    Incoming requests (bursty)
           |
           v
    +------+------+
    | Leaky Bucket |   queue size = B
    | [req][req][ ]|   (2 queued, 1 slot free)
    +------+------+
           |
           v  (drains at fixed rate R)
    Processed at constant rate
```

**Timeline example** (queue size=3, drain rate=1 req/sec):

```
T=0s: 5 requests arrive at once
  Queue: [r1][r2][r3]  -- 3 accepted
  Dropped: r4, r5      -- queue full

T=1s: r1 processed, queue: [r2][r3][ ]
T=2s: r2 processed, queue: [r3][ ][ ]
T=3s: r3 processed, queue: [ ][ ][ ]
```

| Aspect     | Detail |
|------------|--------|
| **Pros**   | Perfectly smooth output rate, simple concept |
| **Cons**   | Bursts of recent requests fill the queue, causing newer requests to wait; stale requests may clog the queue |
| **Use case** | Traffic shaping where steady output is critical (e.g., network packet processing) |
| **Memory** | O(B) per key -- stores the queue |
| **Accuracy** | High for smoothing, but penalizes burst patterns |
| **Used by** | Network routers, Shopify (variant) |

---

### 8.3 Fixed Window Counter

**How it works**: Divide time into fixed windows (e.g., every minute: 0:00-0:59, 1:00-1:59, ...). Maintain a counter per window. Increment on each request. Reject when counter exceeds limit.

```
    Window 1 (0:00-0:59)    Window 2 (1:00-1:59)
    +-------------------+   +-------------------+
    | count: 97/100     |   | count: 12/100     |
    | [|||||||||||||   ] |   | [||              ] |
    +-------------------+   +-------------------+
              ^                       ^
         current time            next window
```

**The Boundary Spike Problem**:

```
Limit: 100 requests per minute

    Window 1                    Window 2
    |...........[50 reqs]|[50 reqs]...........|
    0:00       0:30     1:00     1:30       2:00
                    ^^^^^
              100 requests in 1-second span!
              (50 at 0:59 + 50 at 1:00)

    Both windows show count=50, under the limit.
    But 100 requests happened in ~1 second -- 
    effectively 2x the intended rate.
```

| Aspect     | Detail |
|------------|--------|
| **Pros**   | Extremely simple, very memory-efficient, fast |
| **Cons**   | Boundary spike problem allows up to 2x burst at window edges |
| **Use case** | When simplicity matters more than precision |
| **Memory** | O(1) per key -- just a counter and window ID |
| **Accuracy** | Low near window boundaries |
| **Used by** | Simple internal services, initial implementations |

---

### 8.4 Sliding Window Log

**How it works**: Store the **exact timestamp** of every request in a sorted log. On each new request, remove timestamps older than the window, then count remaining. If count exceeds limit, reject.

```
Window size: 60 seconds, Limit: 5

Sorted log for user-123:
    [10:00:15, 10:00:22, 10:00:45, 10:01:02, 10:01:10]
                                            ^
                                     current time: 10:01:10
                                     window start: 10:00:10

Remove entries before 10:00:10:
    [10:00:15, 10:00:22, 10:00:45, 10:01:02, 10:01:10]
    
Count = 5  -->  AT LIMIT
Next request: REJECTED (429)
```

**Redis implementation using Sorted Set**:

```
ZADD    rate_limit:user123  <timestamp>  <unique_id>
ZREMRANGEBYSCORE rate_limit:user123  0  <window_start>
ZCARD   rate_limit:user123
```

| Aspect     | Detail |
|------------|--------|
| **Pros**   | Most accurate -- no boundary issues, exact sliding window |
| **Cons**   | High memory usage -- stores every timestamp; expensive cleanup |
| **Use case** | When precision is critical and request volume per key is moderate |
| **Memory** | O(N) per key where N = max allowed requests in window |
| **Accuracy** | Perfect -- truly sliding window |
| **Used by** | Fraud detection systems, financial APIs |

---

### 8.5 Sliding Window Counter

**How it works**: A **hybrid** of Fixed Window Counter and Sliding Window Log. Maintain counters for the current window and the previous window. Estimate the count in the sliding window using a **weighted average**:

```
weighted_count = (prev_window_count * overlap%) + curr_window_count
```

```
Limit: 100 requests per minute

    Previous Window         Current Window
    |------[70 reqs]------|------[30 reqs]--.....|
    0:00                 1:00    1:15           2:00

Current time: 1:15 (25% into current window)
Overlap with previous window: 75%

Weighted count = 70 * 0.75 + 30 = 52.5 + 30 = 82.5 --> round to 82
82 < 100 --> ALLOWED
```

**Another example hitting the limit**:

```
    Previous Window         Current Window
    |------[90 reqs]------|------[20 reqs]--.....|
    0:00                 1:00    1:15           2:00

Weighted count = 90 * 0.75 + 20 = 67.5 + 20 = 87.5 --> 87
87 < 100 --> ALLOWED

At 1:15 with prev=90, curr=40:
Weighted count = 90 * 0.75 + 40 = 67.5 + 40 = 107.5 --> 107
107 > 100 --> REJECTED (429)
```

| Aspect     | Detail |
|------------|--------|
| **Pros**   | Good accuracy with minimal memory, smooth boundary handling |
| **Cons**   | Approximate (assumes even distribution in previous window) |
| **Use case** | Production API rate limiting -- best balance of all tradeoffs |
| **Memory** | O(1) per key -- just two counters |
| **Accuracy** | Very high (~99.97% accuracy per Cloudflare's analysis) |
| **Used by** | Cloudflare, many production API gateways |

---

### Algorithm Comparison Table

| Algorithm | Memory/Key | Accuracy | Burst Handling | Complexity | Best For |
|-----------|-----------|----------|----------------|------------|----------|
| **Token Bucket** | O(1) | High | Allows controlled bursts | Low | General-purpose APIs |
| **Leaky Bucket** | O(B) | High | Smooths bursts | Low | Steady traffic shaping |
| **Fixed Window** | O(1) | Low (edges) | 2x burst at boundary | Very Low | Simple use cases |
| **Sliding Window Log** | O(N) | Perfect | No burst issues | Medium | Precision-critical |
| **Sliding Window Counter** | O(1) | Very High (~99.97%) | Smooth | Low | Production (best balance) |

**Interview recommendation**: Default to **Token Bucket** (most interviewers expect it) or **Sliding Window Counter** (shows depth). Know all five and their tradeoffs.

---

## 9. High-Level Architecture

```
                            +---------------------+
                            |     Admin Portal     |
                            +----------+----------+
                                       |
                                  CRUD Rules
                                       |
                                       v
+--------+    +-------------+    +-----+------+    +------------------+
| Client |--->| API Gateway |--->| Rate Limit |--->| Application      |
|        |<---| (Nginx/LB)  |<---| Middleware  |<---| Server (API)     |
+--------+    +-------------+    +-----+------+    +------------------+
                                       |
                      +----------------+----------------+
                      |                |                |
                      v                v                v
              +-------+----+   +------+------+   +-----+------+
              | Local Cache |   | Redis Cluster|   | Rules DB    |
              | (Rules,     |   | (Counters,   |   | (PostgreSQL)|
              |  Guava/     |   |  Lua Scripts)|   |             |
              |  Caffeine)  |   |              |   |             |
              +-------------+   +--------------+   +-------------+
```

### Request Flow (Happy Path)

```
1. Client sends: GET /api/v1/orders
                      |
2. API Gateway forwards to App Server
                      |
3. Rate Limiter Middleware intercepts (before controller)
                      |
4. Extract key: userId="user-123", endpoint="/api/v1/orders"
                      |
5. Lookup rule from Local Cache (hit) or Rules DB (miss)
      Rule: 100 req/min for /api/v1/orders per user
                      |
6. Check counter in Redis:
      Key: "rl:user-123:/api/v1/orders:202601181430"
      INCR + EXPIRE via Lua script
                      |
7a. count <= 100:                    7b. count > 100:
    ALLOW                                REJECT
    Add headers:                         Return 429
    X-RateLimit-Remaining: 47            Retry-After: 23
    Forward to controller                Add headers
                      |
8. Return response to client
```

---

## 10. Component Deep Dive

### 10.1 Rules Engine

Stores and evaluates rate limit configurations. Rules are structured hierarchically with priority resolution.

```
+------------------+
|   Rules Engine   |
+------------------+
| - loadRules()    |
| - matchRule()    |
| - evaluateRule() |
+------------------+

Rule Resolution Order (highest priority wins):
  1. Deny-list check  --> BLOCK immediately
  2. Allow-list check --> ALLOW immediately (skip rate limiting)
  3. Specific rule    --> user + endpoint combo
  4. User-level rule  --> user across all endpoints
  5. Endpoint rule    --> endpoint across all users
  6. Global default   --> fallback rule
```

```java
public class RateLimitRule {
    String id;
    String keyType;          // USER_ID, IP, ENDPOINT, COMPOSITE
    String endpointPattern;  // /api/v1/orders/**
    String tenantId;
    int maxRequests;         // 100
    int windowSizeSeconds;   // 60
    Algorithm algorithm;     // TOKEN_BUCKET, SLIDING_WINDOW_COUNTER, etc.
    int priority;            // higher = more specific
    Action action;           // THROTTLE, BLOCK, LOG_ONLY
}
```

### 10.2 Counter Store

Maintains request counts. Two implementations depending on deployment:

```
+------------------------------------------+
|           Counter Store                  |
+------------------------------------------+
|                                          |
|  +-- Distributed (Redis) --------+      |
|  | - Atomic INCR + TTL           |      |
|  | - Lua scripts for compound    |      |
|  |   check-and-increment         |      |
|  | - Latency: 1-3ms              |      |
|  +-------------------------------+      |
|                                          |
|  +-- Local (ConcurrentHashMap) --+      |
|  | - In-process, zero network    |      |
|  | - Only for single-node or     |      |
|  |   per-node limits             |      |
|  | - Latency: <0.01ms            |      |
|  +-------------------------------+      |
|                                          |
+------------------------------------------+
```

### 10.3 Decision Engine

Core logic: checks counter value against the matched rule and returns allow/deny.

```java
public class DecisionEngine {

    public RateLimitResult evaluate(String clientKey, RateLimitRule rule, long now) {
        // 1. Check deny-list
        if (denyList.contains(clientKey)) {
            return RateLimitResult.blocked();
        }

        // 2. Check allow-list
        if (allowList.contains(clientKey)) {
            return RateLimitResult.allowed(rule.getMaxRequests());
        }

        // 3. Get current count from counter store
        CounterResult counter = counterStore.incrementAndGet(
            buildKey(clientKey, rule), 
            rule.getWindowSizeSeconds()
        );

        // 4. Compare against limit
        boolean allowed = counter.getCount() <= rule.getMaxRequests();
        int remaining = Math.max(0, rule.getMaxRequests() - counter.getCount());
        long retryAfter = allowed ? 0 : counter.getWindowResetTime() - now;

        return new RateLimitResult(allowed, remaining, retryAfter);
    }
}
```

### 10.4 Header Builder

Builds standard rate limit headers for the HTTP response:

```java
public class HeaderBuilder {

    public Map<String, String> buildHeaders(RateLimitRule rule, RateLimitResult result) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-RateLimit-Limit", String.valueOf(rule.getMaxRequests()));
        headers.put("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        headers.put("X-RateLimit-Reset", String.valueOf(result.getResetTimestamp()));

        if (!result.isAllowed()) {
            headers.put("Retry-After", String.valueOf(result.getRetryAfter()));
        }
        return headers;
    }
}
```

### 10.5 Sync Service

Keeps local rule caches in sync with the central Rules DB:

```
+-------------+      poll every 30s       +-----------+
| Local Cache  | <----------------------- | Rules DB  |
| (Caffeine)   |                          | (Postgres)|
+--------------+                          +-----------+

Strategy:
  - On startup: load all rules into local cache
  - Every 30-60 seconds: poll for changes (or use DB change notifications)
  - On rule update via Admin API: publish event to Redis Pub/Sub
    --> all nodes invalidate and reload affected rules
  - Fallback: if sync fails, use stale rules (better than no rules)
```

---

## 11. Distributed Rate Limiting

This is the **key challenge** interviewers probe. When you have N application servers, each one independently checking limits, how do you ensure global accuracy?

### The Problem

```
Limit: 100 requests/minute for user-123

    Server A               Server B              Server C
    local count: 40        local count: 35        local count: 30
    "Under limit!"         "Under limit!"         "Under limit!"

    Actual total: 40 + 35 + 30 = 105  --> OVER LIMIT but no one knows!
```

### Solution 1: Centralized Redis (Recommended)

All servers share a single Redis counter per key.

```
    Server A ---+
                |
    Server B ---+--> Redis Cluster --> "rl:user-123:count" = 105
                |
    Server C ---+
```

| Pros | Cons |
|------|------|
| Accurate global count | Adds 1-3ms network latency per request |
| Simple implementation | Redis becomes a dependency (SPOF if not clustered) |
| Atomic operations with INCR | Network partition can cause inconsistency |

### Solution 2: Local Counters + Periodic Sync

Each server counts locally and syncs to a central store periodically.

```
    Server A: local=40  ---(sync every 5s)--->  Central: 105
    Server B: local=35  ---(sync every 5s)--->
    Server C: local=30  ---(sync every 5s)--->
```

| Pros | Cons |
|------|------|
| Zero latency overhead | Inaccurate between syncs (can exceed limit by N*sync_interval*rate) |
| Works if Redis is down | Complex synchronization logic |

### Solution 3: Sticky Sessions

Route the same client to the same server consistently (via consistent hashing on client ID).

```
    user-123 --> always Server B
    user-456 --> always Server A
    user-789 --> always Server C
```

| Pros | Cons |
|------|------|
| Accurate per-node counting | Limits load balancing flexibility |
| No Redis dependency | Hot clients create hot servers |
| Simple | Server failure requires re-hashing |

### Race Condition: Read-Then-Write

The classic distributed systems problem:

```
    Server A                    Redis                   Server B
       |                          |                         |
       |--- GET counter --------->|                         |
       |<-- counter = 99 ---------|--- GET counter -------->|
       |                          |<-- counter = 99 --------|
       |   "99 < 100, ALLOW"     |   "99 < 100, ALLOW"     |
       |--- SET counter=100 ----->|                         |
       |                          |--- SET counter=100 ---->|
       |                          |                         |
       Result: Both allowed, but actual count should be 101 (OVER LIMIT)
```

### Solution: Atomic Operations with Lua Scripts

Redis Lua scripts execute atomically -- no interleaving between read and write.

```lua
-- Redis Lua script: atomic check-and-increment
-- KEYS[1] = rate limit key
-- ARGV[1] = max requests allowed
-- ARGV[2] = window size in seconds

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local current = tonumber(redis.call('GET', key) or '0')

if current >= limit then
    return {0, current, tonumber(redis.call('TTL', key))}  -- rejected
end

local new_count = redis.call('INCR', key)
if new_count == 1 then
    redis.call('EXPIRE', key, window)
end

local remaining = limit - new_count
local ttl = redis.call('TTL', key)
return {1, remaining, ttl}  -- allowed, remaining, ttl
```

**Simpler alternative**: Just use `INCR` (already atomic) and check after:

```
MULTI
  INCR  rl:user-123:window-1430
  EXPIRE rl:user-123:window-1430 60   (only if new key)
EXEC

if result > limit:
  return 429 (the request already incremented, but we reject it)
  -- slight over-count but safe; or use Lua script above for precision
```

---

## 12. Scaling Strategy

### Redis Cluster with Consistent Hashing

```
Rate limit keys are distributed across Redis nodes via hash slots:

    Key: "rl:user-123:endpoint-A"
         |
    hash("rl:user-123:endpoint-A") % 16384 = slot 5742
         |
    Slot 5742 --> Redis Node 3
         |
    +--------+  +--------+  +--------+  +--------+
    | Node 1 |  | Node 2 |  | Node 3 |  | Node 4 |  ...
    | 0-4095 |  |4096-8191| |8192-12287| |12288-16383|
    +--------+  +--------+  +--------+  +--------+
```

### Scaling Dimensions

| Dimension | Strategy |
|-----------|----------|
| **More requests** | Add Redis nodes (horizontal sharding via Redis Cluster) |
| **More rules** | Rules DB is tiny; sharding not needed |
| **More app servers** | Stateless middleware; just add instances |
| **Hot keys** | Local caching + Redis; or shard by key prefix |
| **Geo-distribution** | Regional Redis clusters with eventual sync; or per-region limits |

### Optimizations

- **Local cache for hot keys**: If user-123 already hit the limit, cache the rejection locally for a short TTL (1-2 seconds) to avoid redundant Redis calls
- **Batch/Pipeline**: For endpoints that need multiple rule checks, batch Redis commands using pipelining
- **Read replicas for rules**: Rules rarely change; read from replicas to reduce load on primary

---

## 13. Database Choice

### Counters: Redis

| Factor | Redis | Why |
|--------|-------|-----|
| **Atomic operations** | INCR, INCRBY | No read-then-write race condition |
| **TTL support** | EXPIRE, built-in | Counters auto-expire after window ends -- no cleanup job needed |
| **Lua scripting** | EVAL | Complex atomic logic (check-and-increment) |
| **Speed** | ~0.1ms per op | Sub-millisecond for rate limit checks |
| **Data structures** | Sorted Sets, Hashes | Sliding Window Log uses ZADD/ZRANGEBYSCORE |

**Why not Memcached?** No Lua scripting, no sorted sets, no atomic INCR+EXPIRE combo.

### Rules: PostgreSQL or DynamoDB

| Factor | PostgreSQL | DynamoDB |
|--------|-----------|----------|
| **Data size** | ~10 MB (tiny) | ~10 MB (tiny) |
| **Read pattern** | Bulk load on startup, poll changes | Same |
| **Write pattern** | Rare admin CRUD | Same |
| **Schema** | Structured, relational | Key-value with flexible schema |
| **Best when** | Already in your stack | AWS-native, zero ops |

**Recommendation**: Use whatever relational DB your platform already has. The rules dataset is so small that database choice barely matters.

---

## 14. Caching Strategy

```
    +------------------+     +------------------+     +------------------+
    |   Rules Cache    |     |  Counter Store   |     |  Rejection Cache |
    |   (Local)        |     |  (Redis)         |     |  (Local)         |
    +------------------+     +------------------+     +------------------+
    | What: Rate limit |     | What: Request    |     | What: Recently   |
    |   rules/configs  |     |   counters       |     |   rejected keys  |
    | Where: Caffeine/ |     | Where: Redis     |     | Where: In-memory |
    |   Guava in-proc  |     |   Cluster        |     |   with short TTL |
    | TTL: 30-60 sec   |     | TTL: Window size |     | TTL: 1-2 sec     |
    | Refresh: Poll or |     | No caching --    |     | Purpose: Avoid   |
    |   Pub/Sub        |     |   this IS the    |     |   redundant Redis |
    |                  |     |   source of truth|     |   calls for users |
    +------------------+     +------------------+     |   already at limit|
                                                      +------------------+
```

### Key Principles

1. **Rules are cacheable** -- They rarely change. Cache locally with 30-60 second TTL. Use Redis Pub/Sub for instant invalidation when admin updates a rule.

2. **Counters are NOT cached** -- Redis IS the authoritative counter store. Caching counters elsewhere would cause stale reads and allow over-limiting.

3. **Rejection cache is an optimization** -- Once a user hits the limit, cache the rejection locally for 1-2 seconds. Prevents hammering Redis for a user who is already blocked.

---

## 15. Fault Tolerance

### Fail-Open vs Fail-Closed

This is a critical design decision and a favorite interview question.

```
Redis goes down. What happens?

FAIL-OPEN:                              FAIL-CLOSED:
+------------------+                    +------------------+
| Redis is down    |                    | Redis is down    |
| Can't check limit|                    | Can't check limit|
|                  |                    |                  |
| Decision: ALLOW  |                    | Decision: DENY   |
| all requests     |                    | all requests     |
+------------------+                    +------------------+
| Risk: Abuse may  |                    | Risk: ALL users  |
| go unchecked     |                    | are blocked, even|
| temporarily      |                    | legitimate ones  |
+------------------+                    +------------------+
```

| Factor | Fail-Open | Fail-Closed |
|--------|-----------|-------------|
| **Availability** | High -- users not impacted | Low -- complete outage |
| **Protection** | Temporarily reduced | Maintained (at extreme cost) |
| **User experience** | Seamless | Service unavailable |
| **Industry standard** | Most production systems | Financial / security-critical |
| **Risk** | Short burst of unthrottled traffic | Entire platform down |

**Recommendation**: **Fail-open** for most systems. Blocking all legitimate users is almost always worse than allowing a brief spike. Add alerting so the team knows Redis is down and can respond.

### Additional Fault Tolerance Measures

- **Redis Sentinel / Cluster**: Automatic failover if a primary node fails
- **Circuit Breaker**: If Redis latency spikes >10ms, trip the circuit and fall back to local counting
- **Graceful Degradation**: Switch to local per-node rate limiting (less accurate but functional)
- **Fallback chain**: Redis Cluster --> Redis Sentinel --> Local ConcurrentHashMap --> Fail-open

---

## 16. CAP Theorem Analysis

```
        C (Consistency)
       / \
      /   \
     /     \
    / Rate  \
   / Limiter \
  /  is here  \
 /      *      \
A ------------- P
(Availability)  (Partition Tolerance)
```

**The rate limiter is an AP system.**

| Property | Analysis |
|----------|----------|
| **Availability** | Must always respond -- can't block the entire request pipeline |
| **Partition Tolerance** | Network partitions between app servers and Redis will happen |
| **Consistency** | Sacrificed -- counters may be temporarily inaccurate during partitions |

### Why AP over CP?

- During a network partition, we prefer to **keep serving requests** (possibly with inaccurate rate limiting) rather than **block all requests** until consistency is restored.
- Brief over-counting (a few extra requests slip through) is far less damaging than blocking legitimate users.
- Eventual consistency is sufficient: counters will converge once the partition heals.

### Consistency Tradeoff in Practice

```
During partition:
  Server A: counter = 50 (can't reach Redis)
  Server B: counter = 60 (can reach Redis)
  
  User's actual total might be 110 (over 100 limit)
  But both servers think user is under limit
  
  Impact: ~10% over-limit for the partition duration (seconds to minutes)
  Acceptable: YES for most systems
```

---

## 17. Cloud Services Mapping

| Component | AWS | GCP | Azure |
|-----------|-----|-----|-------|
| **Rate Limiter Service** | AWS API Gateway (built-in throttling) | Apigee / Cloud Endpoints | Azure API Management |
| **Counter Store** | Amazon ElastiCache (Redis) | Cloud Memorystore (Redis) | Azure Cache for Redis |
| **Rules DB** | Amazon RDS / DynamoDB | Cloud SQL / Firestore | Azure SQL / Cosmos DB |
| **Load Balancer** | ALB / NLB | Cloud Load Balancing | Azure Load Balancer |
| **CDN (edge rate limiting)** | CloudFront | Cloud CDN | Azure CDN / Front Door |
| **Monitoring** | CloudWatch | Cloud Monitoring | Azure Monitor |
| **Message Bus (rule sync)** | SNS/SQS | Pub/Sub | Service Bus |

### Managed Rate Limiting (if you don't build your own)

| Service | Rate Limiting Capability |
|---------|--------------------------|
| **AWS API Gateway** | Built-in throttling (token bucket), usage plans per API key |
| **AWS WAF** | Rate-based rules at edge (L7) |
| **Cloudflare** | Advanced rate limiting with sliding window at edge |
| **Kong** | Rate limiting plugin (multiple algorithms) |
| **Envoy** | Rate limit filter with external rate limit service |

---

## 18. Tradeoffs Summary

| Decision | Option A | Option B | Our Choice | Rationale |
|----------|----------|----------|------------|-----------|
| **Algorithm** | Token Bucket | Sliding Window Counter | Sliding Window Counter | Best accuracy-to-memory ratio; no boundary spike |
| **Counter store** | Local (ConcurrentHashMap) | Distributed (Redis) | Redis with local fallback | Accuracy across nodes; fallback for resilience |
| **Failure mode** | Fail-open | Fail-closed | Fail-open | Blocking legit users > allowing a brief spike |
| **Consistency** | Strong (CP) | Eventual (AP) | AP | Availability is more important for rate limiting |
| **Rule sync** | Push (Pub/Sub) | Pull (polling) | Push + Pull | Push for real-time, poll as safety net |
| **Granularity** | Per-node limits | Global limits | Global (via Redis) | Per-node is inaccurate when nodes scale up/down |
| **Clock source** | Server clock | Redis clock (via TIME) | Server clock (NTP-synced) | Simpler; NTP gives <100ms accuracy which is sufficient |
| **Key design** | Simple (userId) | Composite (userId + endpoint) | Composite | Finer-grained control per endpoint |
| **Burst handling** | Strict (no bursts) | Allow controlled bursts | Allow controlled bursts | Better UX; Token Bucket / Sliding Window handles this naturally |

---

## 19. Interview Talking Points

### What to Proactively Mention (before they ask)

1. **Start with requirements**: "Before I dive in, let me clarify scope -- are we building middleware for a single service or a platform-wide rate limiter?"

2. **Algorithms first**: Lead with Token Bucket or Sliding Window Counter. Mention you know all five and offer to compare. This signals depth.

3. **Distributed challenge**: Bring up the multi-server problem early. "The interesting challenge here is distributed counting -- let me show how I'd solve that with Redis and Lua scripts."

4. **Fail-open vs fail-closed**: Mention this tradeoff proactively. Interviewers love hearing you think about failure modes.

5. **Race conditions**: Explain read-then-write problem and atomic solutions without being prompted.

### Common Follow-Up Questions and How to Handle Them

| Question | Key Points |
|----------|------------|
| "How do you handle rate limiting at the edge?" | CDN/WAF layer (Cloudflare, AWS WAF) for L7 rate limiting before traffic reaches your servers |
| "What if Redis goes down?" | Fail-open + circuit breaker + local fallback + alerting. Explain the tradeoff. |
| "How do you handle clock skew?" | NTP sync, or use Redis server time. For sliding window log, small skew is tolerable. |
| "How would you test this?" | Load testing with tools like Gatling/JMeter, boundary condition tests, Redis failure injection, concurrent request simulation |
| "Token Bucket vs Sliding Window?" | Token Bucket allows bursts (better UX for APIs); Sliding Window Counter is more predictable. Depends on use case. |
| "How do you rate limit in a microservices mesh?" | Centralized rate limit service (like Envoy's ratelimit) or sidecar proxy pattern. Each sidecar checks the central service. |
| "What about rate limiting WebSocket or streaming?" | Per-connection message rate (not per-HTTP-request). Use in-process Token Bucket since connection is sticky. |

### 30-Minute Interview Pacing Guide

```
0:00 - 0:03   Clarify requirements, confirm scope
0:03 - 0:08   High-level architecture (draw the ASCII diagram)
0:08 - 0:18   Algorithm deep dive (pick 2-3, compare in table)
0:18 - 0:25   Distributed rate limiting (Redis, Lua, race conditions)
0:25 - 0:28   Fault tolerance (fail-open/closed) + scaling
0:28 - 0:30   Wrap up with tradeoffs summary
```

### One-Liner Summaries (for quick recall)

- **Token Bucket**: "Bucket of tokens refilled at rate R; allows bursts up to bucket size B."
- **Leaky Bucket**: "FIFO queue draining at fixed rate; smooths bursty traffic."
- **Fixed Window**: "Counter per time window; simple but 2x burst at boundaries."
- **Sliding Window Log**: "Store every timestamp; perfect accuracy, expensive memory."
- **Sliding Window Counter**: "Weighted average of two fixed windows; 99.97% accurate, O(1) memory."

---

*This document is optimized for interview preparation, not production implementation. Focus on understanding tradeoffs and being able to whiteboard the architecture and algorithms fluently.*
