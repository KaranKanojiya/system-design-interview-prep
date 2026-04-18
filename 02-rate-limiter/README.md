# Rate Limiter -- System Design Interview Prep

---

## Problem Summary

Design a rate limiter that controls the number of requests a client can send to an API within
a given time window. It protects services from abuse, DDoS attacks, and resource exhaustion by
rejecting excess requests with HTTP 429 (Too Many Requests). This is a classic system design
question that tests algorithm knowledge, distributed systems thinking, and tradeoff analysis.

---

## 1-Minute Interview Revision

Scan this before you walk in:

- **What**: Middleware that controls request rate per user/IP/API key within a time window
- **5 algorithms**: Token Bucket (best for bursts, most used), Leaky Bucket (smooth output), Fixed Window (simple but boundary spike), Sliding Window Log (accurate but memory heavy), Sliding Window Counter (best balance)
- **Where it sits**: API Gateway or app middleware layer -- before business logic
- **Distributed**: Redis atomic `INCR` + `EXPIRE` with Lua scripts for consistency
- **Failure mode**: Fail-open (allow requests) preferred over fail-closed (deny) -- availability > strictness
- **Headers**: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`
- **CAP**: AP system -- slight over-limit is acceptable; blocking legitimate users is not
- **Key insight**: Rate limiting is about FAIRNESS, not just protection. Per-user limits ensure one noisy neighbor doesn't starve others.
- **Real-world**: Stripe = token bucket, AWS API Gateway = token bucket, Cloudflare = sliding window, GitHub API = sliding window

---

## Architecture Summary

```
    ┌────────┐     ┌─────────────┐     ┌──────────────┐
    │ Client │────>│  WAF / CDN  │────>│ API Gateway  │
    └────────┘     │ (IP limits) │     │  (Throttle)  │
                   └─────────────┘     └──────┬───────┘
                                              │
                          ┌───────────────────┼───────────────────┐
                          │                   │                   │
                    ┌─────▼─────┐       ┌─────▼─────┐      ┌─────▼─────┐
                    │ App Server│       │ App Server│      │ App Server│
                    │ [RL Mid-  │       │ [RL Mid-  │      │ [RL Mid-  │
                    │  dleware] │       │  dleware] │      │  dleware] │
                    └─────┬─────┘       └─────┬─────┘      └─────┬─────┘
                          └───────────────────┼───────────────────┘
                                              │
                                 ┌────────────┼────────────┐
                                 │                         │
                          ┌──────▼──────┐           ┌──────▼──────┐
                          │ Redis Cache │           │  Rules DB   │
                          │ (Counters)  │           │ (DynamoDB)  │
                          │ Lua scripts │           │ Cached in   │
                          │ Atomic ops  │           │ memory      │
                          └─────────────┘           └─────────────┘
```

---

## Algorithm Comparison Table

| Algorithm | Burst Support | Accuracy | Memory | Complexity | Best For |
|-----------|:------------:|:--------:|:------:|:----------:|----------|
| **Token Bucket** | Yes (up to bucket size) | Good | Low (2 values) | Low | General purpose, APIs with burst tolerance |
| **Leaky Bucket** | No (smooths output) | Good | Low (queue size) | Low | Steady-rate processing, traffic shaping |
| **Fixed Window** | Boundary spike (2x) | Low | Very Low (1 counter) | Very Low | Simple use cases, non-critical limits |
| **Sliding Window Log** | No | Exact | High (stores timestamps) | Medium | Audit-critical systems, low-volume APIs |
| **Sliding Window Counter** | Minimal | Very Good | Low (2 counters) | Medium | Best balance of accuracy and memory |

---

## Key Tradeoffs

| Decision | Option A | Option B | Pick | Why |
|----------|----------|----------|------|-----|
| Algorithm | Token Bucket | Sliding Window Counter | Token Bucket | Industry standard, handles bursts, simple to reason about |
| Counter Store | Redis | In-memory (local) | Redis | Distributed, survives restarts, shared across instances |
| Atomicity | Redis INCR + EXPIRE | Redis Lua script | Lua script | INCR + EXPIRE is NOT atomic -- race condition between the two |
| Failure mode | Fail-open (allow) | Fail-closed (deny) | Fail-open | Prefer serving requests over blocking legitimate users |
| Rate limit key | IP address | User ID / API key | Composite | IP only = shared office blocked; User only = can't stop unauthenticated abuse |
| Rule storage | Config file | Database | Database | Hot-reload without redeploy; per-tenant flexibility |
| Limit precision | Exact | Approximate | Approximate | Exact requires distributed locks (slow); AP system tolerates slight over-limit |
| Placement | API Gateway | App middleware | Both | Gateway for global limits, middleware for per-endpoint/per-user logic |

---

## Design Patterns Used

- **Strategy** -- Swappable rate limiting algorithms (TokenBucket, SlidingWindow, LeakyBucket)
- **Chain of Responsibility** -- Request passes through RL middleware before reaching handler
- **Factory** -- Create algorithm instances based on rule configuration
- **Repository** -- Abstract Redis/DB access behind interfaces
- **Observer** -- Publish 429 events for monitoring and alerting

---

## CAP Summary

Rate Limiter is an **AP system**. Availability is paramount -- a rate limiter that blocks legitimate
users due to a Redis partition is worse than one that lets a few extra requests through. Slight
over-counting across distributed nodes is acceptable. Use eventual consistency and tolerate the
small window where distributed counters may be slightly stale.

---

## Tech Stack Summary

| Layer | Technology | Why |
|-------|-----------|-----|
| API Framework | Spring Boot | Interceptor/filter-based middleware, production-grade |
| Algorithms | Custom (Strategy pattern) | Interview demonstrates understanding of each algorithm |
| Counter Store | Redis (Lua scripts) | Atomic operations, sub-ms latency, TTL for auto-expiry |
| Rules DB | DynamoDB / PostgreSQL | Small dataset, read-heavy, cached in memory |
| Config | Spring Cloud Config / AppConfig | Hot-reload rules without redeploy |
| Monitoring | Prometheus + Grafana | Track 429 rates, limit utilization, algorithm performance |

---

## Common Interview Follow-Up Questions

1. **Token bucket vs sliding window -- when to use which?**
   Token bucket for APIs that tolerate bursts (e.g., Stripe charges). Sliding window counter when you need consistent rate enforcement without boundary spikes.

2. **How to rate limit in a distributed system?**
   Centralized Redis with Lua scripts for atomic check-and-increment. Each app server calls Redis before processing. Lua ensures INCR + EXPIRE + comparison happen atomically.

3. **What happens when Redis goes down?**
   Fail-open: allow all requests. Rate limiting is a safeguard, not a gatekeeper. Use local in-memory fallback (per-instance token bucket) as degraded mode.

4. **How to handle clock skew across servers?**
   Use Redis server time (not app server time) for all window calculations. Redis `TIME` command ensures all counters use the same clock. Alternatively, NTP sync all servers.

5. **Fail-open vs fail-closed?**
   Fail-open: Redis down = allow requests. Better for user experience. Fail-closed: Redis down = block requests. Better for security-critical systems (payment APIs). Most systems choose fail-open.

6. **How to rate limit by composite key (user + endpoint)?**
   Key format: `ratelimit:{userId}:{endpoint}:{window}`. Each combination has its own counter. Rules DB defines limits per combination.

7. **How does the fixed window boundary problem work?**
   If limit is 100/min and user sends 100 requests at 1:00:59 and 100 more at 1:01:01, they get 200 in 2 seconds. Sliding window counter fixes this by weighting the previous window.

8. **How to do rate limiting at the edge/CDN level?**
   AWS: Lambda@Edge or CloudFront Functions. Cloudflare: Workers with KV store. Good for IP-based DDoS protection. Complex per-user rules should stay at the application layer.

9. **How to prevent a race condition in distributed counters?**
   Redis Lua scripts execute atomically. Alternative: Redis `MULTI/EXEC` transactions. Never do read-then-write from the app server -- the gap between read and write is the race condition.

10. **How is rate limiting different from throttling?**
    Rate limiting = hard reject (429) when over limit. Throttling = slow down requests (queue, delay, backpressure). Throttling is a superset that includes rate limiting.

11. **How to implement per-tenant rate limiting in a multi-tenant system?**
    Rules DB stores per-tenant limits. Key includes tenant ID. Premium tenants get higher limits. Tenant isolation ensures one noisy tenant can't exhaust shared capacity.

12. **How to handle burst traffic gracefully?**
    Token bucket: allow bursts up to bucket capacity. Return `Retry-After` header so clients know when to retry. Implement client-side exponential backoff. Queue excess requests if latency is acceptable.

---

## How to Run

```bash
cd 02-rate-limiter
../gradlew run
```

---

## What to Improve Later

- Add Redis integration for distributed counters with Lua scripts
- Implement all 5 algorithms with the Strategy pattern
- Per-tenant rule configuration via REST API
- Dashboard for real-time 429 rates and limit utilization
- Sliding window counter with weighted previous-window calculation
- Circuit breaker for Redis failure (fail-open fallback)
- Composite rate limit keys (user + endpoint + tier)
- Rule hot-reload without service restart
- Benchmark: compare algorithm throughput under load
- Multi-region rate limiting with Redis cluster
