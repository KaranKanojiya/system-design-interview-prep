# URL Shortener — Interview Walkthrough

> How to structure your 35-45 minute system design answer. Follow this order.

---

## Phase 1: Clarify Requirements (2-3 min)

- "Before I jump in, let me clarify the scope"
- Ask: Is this just shortening + redirect, or also analytics, custom aliases, expiry?
- Ask: Who's the user — public API (like Bitly) or internal service?
- Ask: Scale — how many URLs/day? Read-heavy or write-heavy?
- Confirm: No user auth, no billing, no link editing — keep scope tight
- **Tip**: Interviewers love when YOU drive the scoping, not them

## Phase 2: Back-of-Envelope Estimates (3-4 min)

- State assumptions upfront: "Let's say 100M new URLs/month"
- Writes: 100M / (30 × 86400) ≈ **~40 writes/sec**
- Reads: 100:1 ratio → **~4K reads/sec**
- Storage (5 years): 100M × 12 × 5 = 6B URLs × ~300 bytes ≈ **1.8 TB**
- Cache (80-20 rule): 4K × 86400 × 0.2 × 300 bytes ≈ **~20 GB** — fits in one Redis node
- Short code length: 62^7 ≈ 3.5 trillion — more than enough for 6B URLs
- **Tip**: Don't spend more than 3 min here. Round aggressively. Show you know HOW to estimate.

## Phase 3: API Design (2-3 min)

- `POST /api/shorten` → { originalUrl, customAlias?, expiresAt? } → { shortUrl, shortCode }
- `GET /{shortCode}` → 302 redirect to original URL
- `GET /api/{shortCode}/stats` → { clickCount, createdAt, ... }
- `DELETE /api/{shortCode}` → 204
- Mention: 301 vs 302 tradeoff (301 = browser caches, you lose analytics; 302 = every request hits server, you get analytics)
- **Tip**: Keep it RESTful. Don't over-design the API — move to architecture quickly.

## Phase 4: High-Level Architecture (5-7 min — THE CORE)

- Draw: Client → **Load Balancer** → **API Servers** → **Cache (Redis)** → **Database**
- Mention separately: **Key Generation Service**, **Analytics Pipeline (Kafka)**
- Write path: Client → API Server → generate short code → write to DB → return
- Read path: Client → API Server → check Redis → if miss, check DB → cache it → 302 redirect
- **Tip**: Draw the diagram FIRST, then explain the flow. Interviewers follow visuals better.

## Phase 5: URL Generation — Deep Dive (5-7 min — MOST ASKED)

- Present 3-4 approaches, compare, recommend one:
  - **MD5 hash + first 7 chars**: Simple, but collisions possible. Need collision check + retry.
  - **Base62 of auto-increment counter**: Predictable (sequential), but fast, no collisions. Need distributed counter.
  - **Pre-generated Key Service (KGS)**: Batch-generate keys offline, serve from pool. No collision at all. Best for scale.
  - **Random string**: Simple, but collision risk grows with scale.
- Recommend: **KGS** for production scale, **Base62** for simplicity in interviews
- Custom alias: Just check uniqueness and insert — treat as user-provided short code
- **Tip**: This is where interviewers probe deep. Have a clear opinion and justify it.

## Phase 6: Database Choice (2-3 min)

- Access pattern: simple key-value lookup (shortCode → URL). No joins. No complex queries.
- Write-heavy with massive reads
- Recommend: **DynamoDB / Cassandra** — horizontal scaling, fast key-value reads, AP system
- Mention: PostgreSQL works fine at moderate scale, but sharding is harder
- Schema: `url_mapping(short_code PK, original_url, created_at, expires_at, click_count)`
- **Tip**: Justify with access pattern, not just "NoSQL is better." Show you think about WHY.

## Phase 7: Caching (2-3 min)

- 80-20 rule: 20% of URLs get 80% of traffic
- **Cache-aside**: Read from Redis first → miss → read DB → write to Redis
- TTL: 24 hours, LRU eviction
- Multi-layer: Browser (302 header) → CDN edge → App-local (Caffeine) → Redis → DB
- Hot/viral URLs: Local in-memory cache to avoid hammering Redis
- **Tip**: Mention cache stampede briefly — shows depth without going off track

## Phase 8: Scaling (3-4 min)

- **App servers**: Stateless → horizontal scaling behind LB, easy
- **Database sharding**: Hash-based on shortCode (consistent hashing). Range-based causes hot partitions.
- **Read replicas**: For stats/analytics queries, not for redirect path
- **Cache sharding**: Consistent hashing across Redis nodes
- **Key generation at scale**: Distributed counter with ZooKeeper ranges OR KGS with pre-allocated key pools per server
- **Tip**: Don't just say "add more servers." Explain WHAT you shard and HOW.

## Phase 9: Extras — Show Depth (2-3 min)

Pick 2-3 of these depending on time and interviewer interest:

- **Analytics**: Async — write click events to Kafka → consumer writes to analytics DB. Don't slow down the redirect path.
- **Rate limiting**: Token bucket per IP/API key at the API Gateway layer
- **Expiry cleanup**: Background cron job / TTL in DB (DynamoDB has native TTL)
- **Abuse prevention**: Blacklist URLs, rate limit creation, CAPTCHA for anonymous users
- **Geo-distribution**: Multi-region DB replication + edge CDN for redirects
- **Monitoring**: Track p99 redirect latency, cache hit ratio, error rates

## Phase 10: Tradeoffs & CAP (1-2 min — wrap up strong)

- "This is an **AP system** — availability of redirects is critical. A user clicking a short link that doesn't resolve is worse than getting a slightly stale analytics count."
- Eventual consistency is fine for click counts
- Custom alias uniqueness: Use conditional writes (DynamoDB PutItem with condition) — this is the one place we need consistency
- Key tradeoffs to mention:
  - 301 vs 302 (caching vs analytics)
  - NoSQL vs SQL (scale vs flexibility)
  - KGS vs on-the-fly generation (pre-compute vs simplicity)
  - Base62 vs random (predictable vs collision-free)

---

## Red Flags to Avoid

- Don't jump to code or DB schema before drawing architecture
- Don't say "just use Redis" without explaining the access pattern
- Don't pick a database without justifying WHY
- Don't forget to mention caching — it's expected for read-heavy systems
- Don't ignore the URL generation deep-dive — it's the core of this problem
- Don't over-engineer: no need for microservices, Kubernetes, or CI/CD here

## Green Flags Interviewers Love

- YOU drive the scoping conversation
- Back-of-envelope with clear math (even rough)
- Compare 2-3 options, then pick one with a reason
- Mention CAP without being asked
- Proactively bring up failure scenarios
- Say "At this scale I'd also consider..." — shows growth thinking
- Draw clean diagrams with clear data flow arrows

---

## 30-Second Elevator Pitch

> "A URL shortener takes a long URL and returns a 7-character short code. When someone visits the short URL, we 302-redirect to the original. The system is read-heavy (100:1), so we put Redis in front of a NoSQL store like DynamoDB. Short codes are generated using Base62 encoding of an auto-increment counter, or a pre-generated key service for better scale. It's an AP system — availability of redirects matters more than strict consistency. The key design decisions are the encoding strategy, database choice, and caching layer."
