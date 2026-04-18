# Technologies for Rate Limiter Systems

> For each technology: what it is, why chosen, alternatives, and interview talking points.

---

## Table of Contents

| Technology | Role | Interview Importance |
|-----------|------|---------------------|
| Redis | Counter store | HIGH -- core infrastructure |
| Redis Lua Scripts | Atomic multi-step ops | HIGH -- shows depth |
| API Gateway | Where rate limiting lives | HIGH -- system design level |
| Java Middleware | Interceptors / Filters | MEDIUM -- implementation detail |
| Load Balancer | Request distribution | MEDIUM -- affects counter strategy |
| Observability | Metrics and alerting | MEDIUM -- production readiness |
| Database (Rules) | Rule storage | LOW-MEDIUM |
| Message Queue | Async rule propagation | LOW -- supporting infrastructure |

---

## 1. Redis -- Primary Counter Store

### Why Redis

Redis is the industry-standard backing store for rate limiters. Nearly every production rate limiter at scale uses Redis.

```
  +-------------------+     +-------------------+
  | Rate Limiter Node |---->| Redis             |
  | (app server)      |     | (counter store)   |
  +-------------------+     |                   |
  | Rate Limiter Node |---->| INCR user:123     |
  | (app server)      |     | GET  user:123     |
  +-------------------+     | EXPIRE user:123   |
                            +-------------------+
```

### Key Redis Features for Rate Limiting

| Feature | How It Helps |
|---------|-------------|
| `INCR` / `INCRBY` | Atomic counter increment -- no race conditions |
| `EXPIRE` / `PEXPIRE` | Auto-cleanup of counters after window expires |
| `TTL` / `PTTL` | Check remaining time in current window |
| `MULTI` / `EXEC` | Transaction for multi-command atomicity |
| Lua scripting | Complex atomic operations (token bucket, sliding window) |
| Single-threaded | Sequential consistency per key -- no locks needed |
| Sub-ms latency | Rate limit check adds < 1ms to request path |
| `EVALSHA` | Cached Lua scripts -- avoid re-transmitting script on every call |

### Redis Commands by Algorithm

```
  TOKEN_BUCKET:
    HMGET  bucket:{key} tokens last_refill
    HMSET  bucket:{key} tokens {n} last_refill {now}
    EXPIRE bucket:{key} 3600
    --> Best done in a single Lua script

  FIXED_WINDOW:
    INCR   window:{key}:{window_id}
    EXPIRE window:{key}:{window_id} {window_size_seconds}
    --> Two commands, can pipeline

  SLIDING_WINDOW_LOG:
    ZADD     log:{key} {timestamp} {request_id}
    ZREMRANGEBYSCORE log:{key} 0 {window_start}
    ZCARD    log:{key}
    EXPIRE   log:{key} {window_size_seconds}
    --> Must be Lua script for atomicity

  SLIDING_WINDOW_COUNTER:
    GET    window:{key}:{current_window}
    GET    window:{key}:{previous_window}
    INCR   window:{key}:{current_window}
    EXPIRE window:{key}:{current_window} {2 * window_size}
    --> Lua script for atomic read + write
```

### Comparison with Alternatives

| Store | Latency | Atomic Ops | Distributed | Scripting | Verdict |
|-------|---------|------------|-------------|-----------|---------|
| **Redis** | < 1ms | INCR, Lua | Yes (Cluster) | Lua | Best overall |
| Memcached | < 1ms | INCR only | Yes | No Lua | No multi-step atomicity |
| ConcurrentHashMap | ~0ms | CAS | No (local only) | N/A | Single-node only |
| DynamoDB | 5-10ms | Conditional writes | Yes | No | Too slow for hot path |
| PostgreSQL | 2-5ms | Transactions | Yes | PL/pgSQL | Overkill, slower |

### Interview Talking Points

> "Redis is the standard choice for rate limiting counters. It gives us atomic INCR for simple counters, Lua scripting for multi-step algorithms like Token Bucket, and sub-millisecond latency. Being single-threaded, there are no race conditions for single-key operations. The main risk is Redis becoming a single point of failure -- we mitigate with Redis Sentinel for HA and fail-open semantics in the application."

---

## 2. Redis Lua Scripts -- Atomic Multi-Step Operations

### Why They Matter

Simple algorithms like Fixed Window need only `INCR` + `EXPIRE` (two commands, can be pipelined). Complex algorithms like Token Bucket need read-compute-write as a single atomic operation. Lua scripts execute atomically on the Redis server -- no other commands run between steps.

### Token Bucket Lua Script

```lua
-- KEYS[1] = bucket key
-- ARGV[1] = max_tokens (capacity)
-- ARGV[2] = refill_rate (tokens per second)
-- ARGV[3] = current timestamp (seconds)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- Get current state
local data = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(data[1])
local last_refill = tonumber(data[2])

-- Initialize if new key
if tokens == nil then
    tokens = capacity
    last_refill = now
end

-- Calculate refill
local elapsed = math.max(0, now - last_refill)
tokens = math.min(capacity, tokens + elapsed * refill_rate)

-- Try to consume
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, math.ceil(capacity / refill_rate) + 10)
    return {1, math.floor(tokens), 0}  -- allowed, remaining, retry_after
else
    local retry_after = math.ceil((1 - tokens) / refill_rate)
    return {0, 0, retry_after}  -- rejected, remaining, retry_after
end
```

### Sliding Window Counter Lua Script

```lua
-- KEYS[1] = current window key
-- KEYS[2] = previous window key
-- ARGV[1] = max_requests
-- ARGV[2] = window_size_seconds
-- ARGV[3] = elapsed fraction of current window (0.0 to 1.0)

local current_count = tonumber(redis.call('GET', KEYS[1]) or "0")
local prev_count = tonumber(redis.call('GET', KEYS[2]) or "0")
local max_requests = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local elapsed_fraction = tonumber(ARGV[3])

-- Weighted count: previous window * remaining fraction + current window
local weight = 1.0 - elapsed_fraction
local estimated_count = prev_count * weight + current_count

if estimated_count < max_requests then
    -- Allow: increment current window
    local new_count = redis.call('INCR', KEYS[1])
    redis.call('EXPIRE', KEYS[1], window_size * 2)
    local remaining = max_requests - math.ceil(prev_count * weight + new_count)
    return {1, math.max(0, remaining)}  -- allowed, remaining
else
    return {0, 0}  -- rejected
end
```

### Interview Talking Points

> "Lua scripts are critical for rate limiting algorithms that need multi-step atomic operations. The script runs on the Redis server itself, so no other commands can interleave. This is how we implement Token Bucket (read tokens, calculate refill, consume, write back) as one atomic operation. The alternative -- MULTI/EXEC -- does not allow reading a value and using it in a subsequent command within the same transaction. Lua scripts solve this."

---

## 3. API Gateway -- Where Rate Limiting Sits

### Architecture Options

```
  Option A: Gateway-Level Rate Limiting
  +--------+     +-----------+     +-------------+
  | Client |---->| API       |---->| Microservice|
  |        |     | Gateway   |     |             |
  +--------+     | (rate     |     +-------------+
                 |  limiter) |
                 +-----------+

  Option B: Application-Level Rate Limiting
  +--------+     +-----------+     +-------------+
  | Client |---->| API       |---->| Microservice|
  |        |     | Gateway   |     | (rate       |
  +--------+     | (pass-    |     |  limiter)   |
                 |  through) |     +-------------+
                 +-----------+

  Option C: Hybrid (Most Common in Production)
  +--------+     +-----------+     +-------------+
  | Client |---->| API       |---->| Microservice|
  |        |     | Gateway   |     | (per-user   |
  +--------+     | (global   |     |  rate limit)|
                 |  rate     |     +-------------+
                 |  limit)   |
                 +-----------+
```

### Gateway Comparison

| Gateway | Rate Limiting | Pros | Cons |
|---------|--------------|------|------|
| **Kong** | Built-in plugin (Redis-backed) | Feature-rich, Lua extensible | Heavier deployment |
| **AWS API Gateway** | Built-in (token bucket) | Zero ops, pay-per-use | Limited customization, AWS lock-in |
| **Envoy** | Rate limit service (gRPC) | K8s native, very fast | Requires separate rate limit service |
| **Nginx** | `limit_req` module (leaky bucket) | Ultra-fast, simple config | Local only (per-instance), no Redis |
| **Spring Cloud Gateway** | Built-in filter (Redis) | Java ecosystem, customizable | JVM overhead |

### Interview Talking Points

> "In production, rate limiting typically happens at two layers. The API Gateway handles global rate limits -- total requests per second across all users. The application layer handles per-user, per-endpoint limits that require understanding the request context. Our design focuses on the application-layer rate limiter because that is where the interesting algorithm choices happen."

---

## 4. Java Middleware Frameworks

### Options for Integrating Rate Limiting in Java

| Approach | Framework | How It Works |
|----------|-----------|-------------|
| Servlet Filter | Java EE / Jakarta | `doFilter()` -- framework-agnostic, runs before any controller |
| HandlerInterceptor | Spring MVC | `preHandle()` -- Spring-specific, access to handler metadata |
| Spring AOP | Spring | `@Around` advice on annotated methods |
| Bucket4j | Standalone library | In-memory or distributed rate limiting, JCache-compatible |
| Resilience4j | Standalone library | Rate limiter + circuit breaker + retry in one |

### Why Plain Java in This Project

```java
// Our approach: pure Java, no framework dependency
public interface RateLimiterStrategy {
    RateLimitResult tryConsume(String key, RateLimitRule rule);
    void reset(String key);
    Algorithm algorithm();
}
```

**Rationale**:
- Interview-focused: demonstrates understanding of the algorithm, not framework config
- Portable: the same strategy logic works in Spring, Quarkus, Dropwizard, or plain Java
- Testable: no Spring context needed for unit tests
- Clear: all logic is visible, not hidden behind annotations

### Bucket4j Comparison

```
  Our Code:                       Bucket4j:
  +---------------------+        +---------------------+
  | RateLimiterStrategy |        | Bucket              |
  | (5 algorithms)      |        | (token bucket only) |
  | Custom, visible     |        | Production-ready    |
  | Interview-friendly  |        | JCache integration  |
  +---------------------+        +---------------------+

  Use ours for: interviews, learning, custom algorithms
  Use Bucket4j for: production Java applications with standard needs
```

---

## 5. Load Balancer -- Request Distribution Impact

### How Load Balancing Affects Rate Limiting

```
  Scenario: 3 app servers, each with local rate limiter, limit = 100/min

  Round-Robin LB:
  +--------+     +----+     +----------+
  | Client |---->| LB |---->| Server A |  Gets ~33 requests
  |        |     |    |---->| Server B |  Gets ~33 requests
  |        |     |    |---->| Server C |  Gets ~33 requests
  +--------+     +----+     +----------+
  Client can make ~300 requests/min (3x limit) if counters are local!

  Sticky Sessions:
  +--------+     +----+     +----------+
  | Client |---->| LB |---->| Server A |  Gets ALL 100 requests
  |        |     |    |     | Server B |  Gets 0
  +--------+     +----+     | Server C |  Gets 0
                            +----------+
  Correct count, but uneven load and failover problems
```

### Tradeoff Table

| Strategy | Counter Location | Accuracy | Latency | Failover |
|----------|-----------------|----------|---------|----------|
| Sticky sessions + local | In-memory | Exact | Fastest | Bad (lost counters) |
| Any LB + Redis | Centralized Redis | Exact | +0.5ms | Good (Redis persists) |
| Any LB + local + sync | Local + periodic sync | Approximate | Fastest | Medium |

### Interview Talking Points

> "Load balancing strategy directly impacts rate limiter design. With local counters and round-robin LB, a client can get N times their limit where N is the number of servers. That is why centralized Redis counters are the standard approach -- they work correctly regardless of how the load balancer distributes requests. Sticky sessions are a partial solution but create uneven load and failover problems."

---

## 6. Observability -- Metrics and Alerting

### Key Metrics to Track

| Metric | Type | What It Tells You |
|--------|------|------------------|
| `rate_limit.requests_allowed` | Counter | Total allowed requests |
| `rate_limit.requests_rejected` | Counter | Total 429 responses |
| `rate_limit.fail_open_count` | Counter | Times Redis was unreachable |
| `rate_limit.check_latency_ms` | Histogram | Time to perform rate limit check |
| `rate_limit.utilization_percent` | Gauge | current_count / max_limit (per key) |
| `rate_limit.redis_errors` | Counter | Redis connection/timeout errors |

### Prometheus Example

```java
public class RateLimiterMetrics {
    private final Counter allowedRequests = Counter.build()
        .name("rate_limit_requests_allowed_total")
        .help("Total allowed requests")
        .labelNames("algorithm", "rule_key")
        .register();

    private final Counter rejectedRequests = Counter.build()
        .name("rate_limit_requests_rejected_total")
        .help("Total rejected requests (429)")
        .labelNames("algorithm", "rule_key")
        .register();

    private final Histogram checkLatency = Histogram.build()
        .name("rate_limit_check_duration_seconds")
        .help("Rate limit check latency")
        .buckets(0.0001, 0.0005, 0.001, 0.005, 0.01)
        .register();

    private final Counter failOpenCount = Counter.build()
        .name("rate_limit_fail_open_total")
        .help("Times rate limiter failed open due to Redis unavailability")
        .register();
}
```

### Alerting Rules

```yaml
# Alert if fail-open rate exceeds 1% for 5 minutes
- alert: RateLimiterFailingOpen
  expr: rate(rate_limit_fail_open_total[5m]) > 0.01
  for: 5m
  annotations:
    summary: "Rate limiter is failing open -- Redis may be down"

# Alert if rejection rate spikes (possible attack or misconfigured limit)
- alert: HighRejectionRate
  expr: >
    rate(rate_limit_requests_rejected_total[5m])
    / rate(rate_limit_requests_allowed_total[5m]) > 0.5
  for: 5m
  annotations:
    summary: "More than 50% of requests being rate-limited"
```

### Interview Talking Points

> "Observability is critical for rate limiters. The three metrics I always track are: allowed count, rejected count, and fail-open count. The ratio of rejected to total tells me if limits are correctly configured. Fail-open count tells me if Redis is having issues. Latency histogram catches Redis slowdowns before they affect users. I would also track per-key utilization to proactively identify clients approaching their limits."

---

## 7. Database for Rule Storage

### Where Rules Live

```
  +-------------------+      +-------------------+      +-------------------+
  | PostgreSQL /      |----->| Local In-Memory   |----->| Rate Limit Check  |
  | DynamoDB          |      | Cache (rules)     |      | (uses cached rule)|
  | (source of truth) |      | TTL: 30-60 sec    |      |                   |
  +-------------------+      +-------------------+      +-------------------+
        ^
        | Admin updates rules via API
```

### Rules are Read-Heavy

| Operation | Frequency | Where |
|-----------|-----------|-------|
| Read rule for rate limit check | Thousands/sec | In-memory cache |
| Admin creates/updates a rule | Rarely (per day) | Database + cache invalidation |
| Load rules on startup | Once | Database |

### Database Options

| Database | Pros | Cons | Best For |
|----------|------|------|----------|
| **PostgreSQL** | ACID, flexible queries, joins | Overkill for key-value rules | Complex rule hierarchies |
| **DynamoDB** | Managed, auto-scaling, fast reads | Limited query flexibility | Simple key-value rules |
| **Redis (dual use)** | Already in stack, fast | Not durable by default | Small rule sets |
| **Config file (YAML)** | Simplest, version controlled | Requires redeploy to change | Static environments |

---

## 8. Message Queue -- Async Rule Propagation

### Problem: Rule Updates Across Nodes

When an admin updates a rate limit rule, all nodes must learn about it. With local caching, nodes might serve stale rules for up to TTL seconds.

```
  Admin updates rule: "user:premium" -> 1000 req/min (was 500)
       |
       v
  +----+-----+     +------------------+
  | Rules DB  |---->| Kafka / SNS      |---> Topic: rule-changes
  +-----------+     +------------------+
                           |
              +------------+------------+
              |            |            |
              v            v            v
         +--------+   +--------+   +--------+
         | Node A |   | Node B |   | Node C |
         | invalidate | invalidate | invalidate
         | cache   |   | cache   |   | cache   |
         +--------+   +--------+   +--------+
```

### Options for Rule Propagation

| Approach | Latency | Complexity | Consistency |
|----------|---------|-----------|-------------|
| Short TTL cache (30-60s) | Up to TTL seconds | Low | Eventually consistent |
| Kafka/SNS event on change | Seconds | Medium | Near real-time |
| Redis Pub/Sub | Milliseconds | Medium | Near real-time |
| Polling DB every N seconds | Up to N seconds | Low | Eventually consistent |

### Interview Talking Points

> "For rule updates, the simplest approach is a short-TTL local cache -- rules rarely change, so 30-60 second staleness is acceptable. For faster propagation, we can publish a cache-invalidation event to Kafka or Redis Pub/Sub when a rule changes. The rate limit counters themselves do NOT go through a message queue -- they must be real-time in Redis."

---

## Technology Stack Summary

```
  +------------------------------------------------------------------+
  |                    RATE LIMITER SYSTEM                            |
  +------------------------------------------------------------------+
  |                                                                  |
  |  +-----------+    +--------------+    +---------------------+    |
  |  | API       |--->| Rate Limiter |--->| Redis               |    |
  |  | Gateway   |    | Service      |    | (counters + Lua)    |    |
  |  | (Kong /   |    | (Java)       |    +---------------------+    |
  |  |  Envoy)   |    +--------------+                               |
  |  +-----------+          |             +---------------------+    |
  |                         +------------>| PostgreSQL/DynamoDB |    |
  |                         |             | (rules)             |    |
  |                         |             +---------------------+    |
  |                         |                                        |
  |                         |             +---------------------+    |
  |                         +------------>| Prometheus/Grafana  |    |
  |                                       | (metrics)           |    |
  |                                       +---------------------+    |
  |                                                                  |
  |  Optional:                                                       |
  |  +-------------------+    +-------------------+                  |
  |  | Kafka / SNS       |    | Redis Sentinel /  |                  |
  |  | (rule propagation)|    | Cluster (HA)      |                  |
  |  +-------------------+    +-------------------+                  |
  +------------------------------------------------------------------+
```
