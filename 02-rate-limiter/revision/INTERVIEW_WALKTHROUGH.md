# Rate Limiter -- Interview Walkthrough

> How to structure your 35-45 minute system design answer. Follow this order.

---

## Phase 1: Clarify Requirements (2 min)

- "Before I dive in, let me clarify the scope."
- Ask: What are we limiting by -- IP, user ID, API key, or a combination?
- Ask: Do we need different limits per endpoint or per user tier (free vs premium)?
- Ask: Is this a single-server or distributed system?
- Ask: Failure mode -- if the rate limiter goes down, do we allow or block traffic?
- Ask: Do we need to return rate limit headers to the client?
- Confirm scope: "I'll design a distributed rate limiter that supports multiple algorithms, per-user limits, and returns standard rate limit headers."
- **Tip**: Scoping questions show you understand the problem has many flavors. Drive the conversation.

## Phase 2: Algorithm Deep Dive (8-10 min -- THIS IS THE CORE)

This is the most important phase. Interviewers expect you to know all 5 algorithms cold.

### Algorithm 1: Token Bucket

```
Bucket capacity = 10 tokens
Refill rate = 2 tokens/sec

  Time 0:   [##########]  10 tokens (full)
  3 requests arrive:
  Time 1:   [#######___]   7 tokens remaining
  Wait 2 seconds (4 tokens refilled, capped at 10):
  Time 3:   [##########]  10 tokens
  Burst of 10:
  Time 4:   [__________]   0 tokens -- next request REJECTED until refill
```

- **How it works**: Bucket holds tokens. Each request consumes 1 token. Tokens refill at a constant rate. Request rejected if bucket is empty.
- **Pros**: Allows bursts (up to bucket size). Simple. Memory-efficient (2 values: token count + last refill timestamp).
- **Cons**: Two parameters to tune (bucket size + refill rate).
- **Used by**: Stripe, AWS API Gateway, most production systems.
- **Redis implementation**: Store `{tokens, last_refill_time}`. Lua script: calculate tokens to add since last refill, subtract 1, reject if < 0.

### Algorithm 2: Leaky Bucket

```
Queue (capacity = 5):

  Incoming requests:  ████████  (8 requests burst)
                        │
                   ┌────▼────┐
                   │ [5 max] │  ← 5 queued, 3 REJECTED (overflow)
                   └────┬────┘
                        │  drain at fixed rate (1/sec)
                   ┌────▼────┐
                   │ Process │  ← Steady output regardless of input
                   └─────────┘
```

- **How it works**: Requests enter a FIFO queue. Queue drains at a fixed rate. If queue is full, request is rejected.
- **Pros**: Smooth, constant output rate. Good for traffic shaping.
- **Cons**: No burst support. A burst of legitimate traffic gets queued or rejected.
- **Best for**: Systems that need steady processing rate (payment processing, message queues).

### Algorithm 3: Fixed Window Counter

```
Window: 1 minute, Limit: 5

  1:00:00 ──────────── 1:01:00 ──────────── 1:02:00
     [||||| ]              [||    ]
      5 req → FULL          2 req → OK

  PROBLEM (boundary spike):
  1:00:30       1:01:00       1:01:30
     [  ||||| ] [ |||||     ]
      5 at end    5 at start  = 10 in 1 minute!
```

- **How it works**: Divide time into fixed windows. Counter per window. Reject if counter exceeds limit.
- **Pros**: Extremely simple. Very low memory (1 counter per window).
- **Cons**: Boundary spike problem -- 2x the limit can pass at window boundaries.
- **Redis**: `INCR ratelimit:{key}:{window}` + `EXPIRE` (but these two commands are NOT atomic -- use Lua).

### Algorithm 4: Sliding Window Log

```
Window: 1 minute, Limit: 3

  Timestamps stored: [1:00:15, 1:00:30, 1:00:45]

  New request at 1:01:10:
    - Remove entries older than 1:00:10 (1 min ago)
    - Remaining: [1:00:15, 1:00:30, 1:00:45] → remove 1:00:15 (expired)
    - Count: 2 → under limit → ALLOW
    - Log: [1:00:30, 1:00:45, 1:01:10]
```

- **How it works**: Store timestamp of every request in a sorted set. Count entries within the sliding window. Reject if count exceeds limit.
- **Pros**: Perfectly accurate. No boundary problem.
- **Cons**: Memory-intensive -- stores every request timestamp. O(n) cleanup.
- **Redis**: Sorted set (`ZADD`, `ZREMRANGEBYSCORE`, `ZCARD`).
- **Best for**: Low-volume, audit-critical APIs where accuracy matters more than memory.

### Algorithm 5: Sliding Window Counter (RECOMMENDED for accuracy)

```
Window: 1 minute, Limit: 10

  Previous window (0:59-1:00): 8 requests
  Current window  (1:00-1:01): 3 requests
  Current position: 1:00:40 (40% into current window)

  Weighted count = (8 × 0.6) + (3 × 1.0) = 4.8 + 3 = 7.8
  Limit: 10 → 7.8 < 10 → ALLOW
```

- **How it works**: Combines fixed window counters with a weighted calculation. Weight the previous window's count by the overlap percentage.
- **Pros**: Very accurate (no boundary spike). Low memory (2 counters). Good balance.
- **Cons**: Slightly more complex than fixed window. Approximation (not exact).
- **Best for**: Production systems that need accuracy without the memory cost of sliding log.

### Algorithm Recommendation

> "For most production systems, I'd recommend **Token Bucket** -- it's battle-tested (Stripe, AWS),
> handles bursts gracefully, and is simple to implement in Redis. If accuracy is critical and we
> can't tolerate boundary spikes, **Sliding Window Counter** is the best balance of accuracy and memory."

## Phase 3: High-Level Architecture (5 min)

- Draw the architecture diagram:

```
Client → WAF (IP limits) → API Gateway (per-API throttle)
       → Load Balancer → App Servers [Rate Limit Middleware]
       → Redis (counters, Lua scripts) + Rules DB (DynamoDB)
```

- **Where the rate limiter sits**: Middleware in the app server, BEFORE business logic. Alternatively at the API Gateway for simpler rules.
- **Request flow**:
  1. Request arrives with user ID / API key / IP
  2. Middleware extracts rate limit key (e.g., `user:123:POST:/api/orders`)
  3. Look up rule from cached rules (limit: 100/min, algorithm: token_bucket)
  4. Call Redis Lua script: check counter, increment if allowed, return result
  5. If allowed: pass to handler. If rejected: return 429 with headers.
- **Rule storage**: DynamoDB/Postgres. Loaded into local cache on startup. Hot-reload via polling or pub/sub.
- **Tip**: Draw first, then explain the request flow. Interviewers follow visuals better.

## Phase 4: Distributed Challenges (5-7 min)

This is where senior engineers differentiate themselves.

### Race Conditions

```
Without Lua (BROKEN):
  Server A: GET counter → 99     Server B: GET counter → 99
  Server A: counter < 100? YES   Server B: counter < 100? YES
  Server A: SET counter 100      Server B: SET counter 100
  Result: Both allowed. Limit 100 was exceeded.

With Lua (CORRECT):
  -- Lua script runs atomically in Redis
  local current = redis.call('INCR', KEYS[1])
  if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
  end
  if current > tonumber(ARGV[2]) then
    return 0  -- rejected
  end
  return 1  -- allowed
```

- **Key point**: `INCR` + `EXPIRE` as separate commands is a race condition. Lua scripts execute atomically in Redis.

### Fail-Open vs Fail-Closed

```
Redis is down. What do you do?

Fail-open:  Redis down → allow all requests → service stays available
            Risk: Temporary over-limit. Acceptable for most APIs.

Fail-closed: Redis down → reject all requests → service is effectively down
             Risk: Self-inflicted outage. Only for security-critical systems.
```

- **Recommendation**: Fail-open with local in-memory fallback. Each server runs a local token bucket as degraded mode.

### Clock Skew

- Different app servers may have slightly different clocks
- **Solution**: Use `redis.call('TIME')` in Lua scripts -- all window calculations use Redis server time
- Alternative: NTP sync all servers, but still use Redis time for window boundaries

### Synchronization Across Nodes

- Centralized Redis = single source of truth for all app servers
- If Redis cluster has multiple shards, ensure same key always goes to same shard (consistent hashing)
- For multi-region: accept that global rate limiting will have some lag. Per-region limits are more practical.

## Phase 5: API & Headers (2 min)

### Rate-Limited Response

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1672531260
Retry-After: 30

{
  "error": "rate_limit_exceeded",
  "message": "Rate limit of 100 requests per minute exceeded. Retry after 30 seconds.",
  "retry_after": 30
}
```

### Successful Response (include headers)

```
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 73
X-RateLimit-Reset: 1672531260
```

- **Tip**: Mention that EVERY response should include rate limit headers, not just 429s. This helps clients self-regulate.

## Phase 6: Scaling (3 min)

- **Redis cluster**: Shard by rate limit key. Each key hashes to one shard. Counters for the same key always go to the same node.
- **Rule caching**: Load rules into local memory (Caffeine/Guava). Reload every 30s or via pub/sub notification. Avoids DB call on every request.
- **Edge rate limiting**: WAF or Lambda@Edge for IP-based limits. Blocks DDoS before traffic reaches origin. Simple fixed-window or token bucket.
- **Multi-region**: Per-region Redis clusters with per-region limits. Global limits require cross-region coordination (too slow for real-time). Alternative: allocate fraction of global limit to each region.
- **Tip**: "At 100K+ req/s, I'd tier the rate limiting: WAF for IP-based, API Gateway for per-API, and custom middleware for per-user."

## Phase 7: Tradeoffs & CAP (2-3 min)

- "This is an **AP system**. We optimize for availability and partition tolerance. If Redis is partitioned, we fail-open rather than blocking legitimate users."
- Slight over-limit is acceptable. Strict consistency would require distributed locks, which add latency and reduce availability -- the opposite of what a rate limiter should do.
- Key tradeoffs to mention:
  - **Token bucket vs sliding window** (bursts vs accuracy)
  - **Fail-open vs fail-closed** (availability vs strictness)
  - **Centralized Redis vs local counters** (accuracy vs latency)
  - **Exact vs approximate counting** (locks vs speed)
  - **Per-region vs global limits** (latency vs global fairness)

## Phase 8: Real-World Examples (2 min)

| Company | Algorithm | Details |
|---------|-----------|---------|
| **Stripe** | Token Bucket | Per-API-key limits. Generous burst for legitimate spikes. |
| **AWS API Gateway** | Token Bucket | Per-stage, per-method throttling. Usage plans for different tiers. |
| **Cloudflare** | Sliding Window | Edge-based rate limiting. Millions of rules across global network. |
| **GitHub API** | Sliding Window | 5,000 req/hour for authenticated. Returns headers on every response. |
| **Google Cloud** | Token Bucket | Per-project quotas with burst allowance. |
| **Discord** | Token Bucket | Per-route rate limits. Returns `Retry-After` header. |

- **Tip**: "I've studied how Stripe implements rate limiting -- token bucket with per-key counters in Redis. It's the most common production pattern."

---

## Red Flags to Avoid

- Don't skip the algorithm deep dive -- this IS the core of the question
- Don't say "just use Redis INCR" without mentioning Lua scripts and atomicity
- Don't forget to discuss failure modes (what if Redis goes down?)
- Don't pick an algorithm without explaining WHY (compare at least 2-3)
- Don't ignore the distributed aspect -- single-server rate limiting is trivial
- Don't over-engineer: no need for Kafka, microservices, or ML-based detection

## Green Flags Interviewers Love

- Walk through all 5 algorithms with clear diagrams and tradeoffs
- Recommend an algorithm with a clear reason ("Token bucket because...")
- Mention Lua scripts for atomicity without being asked
- Bring up fail-open vs fail-closed proactively
- Discuss composite rate limit keys (user + endpoint)
- Mention that every response (not just 429) should include rate limit headers
- Layer the defense: WAF → Gateway → Middleware
- Reference real-world implementations (Stripe, GitHub, Cloudflare)

---

## 30-Second Elevator Pitch

> "A rate limiter controls how many requests a client can make in a given time window. It sits as
> middleware in the API layer, checking a Redis counter before each request. I'd use the Token Bucket
> algorithm -- it's what Stripe and AWS use, handles bursts well, and is simple to implement with
> Redis Lua scripts for atomicity. The system is AP -- we fail-open if Redis is down because blocking
> legitimate users is worse than allowing a few extra requests. For a distributed system, Redis is
> the single source of truth, Lua scripts prevent race conditions, and we use composite keys like
> user:endpoint to support per-user, per-endpoint limits. Standard headers (X-RateLimit-Remaining,
> Retry-After) help clients self-regulate."
