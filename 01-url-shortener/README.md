# URL Shortener -- System Design Interview Prep

---

## Problem Summary

Design a URL shortening service like bit.ly that converts long URLs into short, unique aliases.
The system must handle high read throughput (redirects) with low latency, store billions of mappings,
and generate short keys that are unique, compact, and non-predictable. This is a classic system design
question that tests key-value storage, encoding strategies, caching, and scalability thinking.

---

## 1-Minute Interview Revision

Scan this before you walk in:

- **What it does**: Takes a long URL, returns a short URL (e.g., `short.url/abc123`), redirects on access
- **Scale**: 100M new URLs/month, **100:1 read-to-write ratio**
- **Throughput**: ~3K writes/sec, ~300K reads/sec
- **Architecture**: `Client -> LB -> API Servers -> Redis Cache -> Cassandra/DynamoDB`
- **URL generation**: Base62 encoding of auto-increment counter (a-z, A-Z, 0-9)
- **Key length**: 7 chars Base62 = 3.5 trillion combinations (enough for years)
- **Why NoSQL**: Simple key-value lookups, write-heavy ingestion, no joins, horizontal scaling
- **CAP theorem**: AP system -- availability + partition tolerance, eventual consistency is fine
- **Key tradeoff**: Pre-generated keys in a key pool (faster, no collisions) vs on-the-fly Base62 (simpler, fewer moving parts)
- **Cache strategy**: Read-through cache -- check Redis first, fall back to DB on miss
- **Redirect**: 301 (permanent, browser caches) vs 302 (temporary, enables analytics)

---

## Architecture Summary

```
    ┌────────┐     ┌─────────────┐     ┌──────────────┐
    │ Client │────>│ API Gateway │────>│ Load Balancer│
    └────────┘     │ (Rate Limit)│     └──────┬───────┘
                   └─────────────┘            │
                          ┌───────────────────┼───────────────────┐
                          │                   │                   │
                    ┌─────▼─────┐       ┌─────▼─────┐      ┌─────▼─────┐
                    │ App Server│       │ App Server│      │ App Server│
                    └─────┬─────┘       └─────┬─────┘      └─────┬─────┘
                          └───────────────────┼───────────────────┘
                                              │
                                       ┌──────▼──────┐
                                       │ Redis Cache │ ← Sub-ms reads
                                       └──────┬──────┘
                                              │ cache miss
                                       ┌──────▼──────┐
                                       │  Cassandra  │ ← Persistent store
                                       │  / DynamoDB │
                                       └──────┬──────┘
                                              │
                                       ┌──────▼──────┐
                                       │  Analytics  │ ← Async (Kafka/Kinesis)
                                       │   Pipeline  │
                                       └─────────────┘
```

---

## Key Components

| Component | Role |
|-----------|------|
| **API Gateway** | Rate limiting, authentication, request routing |
| **App Server** | Handles create (POST) and redirect (GET) endpoints |
| **Encoding Service** | Converts counter/hash to Base62 short key |
| **Cache (Redis)** | Stores hot shortUrl-to-longUrl mappings for sub-ms reads |
| **Database** | Persistent key-value store for all URL mappings |
| **Analytics Pipeline** | Async click tracking without slowing redirects |

---

## Key Tradeoffs

| Decision | Option A | Option B | Choice | Why |
|----------|----------|----------|--------|-----|
| ID Generation | Base62(counter) | MD5/SHA hash truncation | Base62(counter) | No collisions, shorter keys, predictable length |
| Key pool | Pre-generate keys | Generate on-the-fly | On-the-fly | Simpler; pre-gen adds a coordination service |
| Database | SQL (Postgres) | NoSQL (Cassandra/DynamoDB) | NoSQL | Key-value access pattern, horizontal scaling, no joins |
| Cache | Write-through | Read-through (lazy) | Read-through | Most URLs are never accessed again; cache only what's hot |
| Redirect | 301 Permanent | 302 Temporary | 302 | Enables click analytics; 301 bypasses server on repeat visits |
| Counter | Single counter + DB | Zookeeper range alloc | Range-based | Each server gets a range (1M-2M, 2M-3M...) -- no contention |
| Encoding | Base62 | Base64 | Base62 | URL-safe without special chars (+, /, =) |

---

## Design Patterns Used

- **Strategy** -- Swappable encoding algorithms (Base62, MD5, custom)
- **Builder** -- Construct URL entities with optional fields (expiry, custom alias, user)
- **Repository** -- Abstract DB access behind an interface for testability
- **Factory** -- Create encoder instances based on configuration
- **Singleton** -- Counter service / ID generator shared across requests

---

## CAP Summary

URL Shortener is an **AP system**. Availability matters most -- a redirect that fails is a broken link.
Partition tolerance is non-negotiable in a distributed system. Consistency can be eventual: if a newly
created short URL takes 1-2 seconds to propagate across replicas, that's acceptable since the creator
just received the short URL and hasn't shared it widely yet.

---

## Tech Stack Summary

| Layer | Technology | Why |
|-------|-----------|-----|
| API Framework | Spring Boot | Production-grade, dependency injection, actuator for health checks |
| Encoding | Base62 (custom) | URL-safe, compact, 7 chars = 3.5T combinations |
| Cache | Redis | Sub-ms lookups, TTL support, cluster mode for scaling |
| Database | Cassandra / DynamoDB | Key-value native, linear horizontal scaling, tunable consistency |
| Message Queue | Kafka / Kinesis | Async analytics without blocking the redirect path |
| Monitoring | Prometheus + Grafana | Latency percentiles, throughput, cache hit ratios |

---

## Common Interview Follow-Up Questions

1. **How do you handle key collisions?**
   Counter-based generation eliminates collisions. If using hashing, check DB before inserting and retry with a salt.

2. **How would you shard the database?**
   Hash-based partitioning on the short key. Consistent hashing to minimize rebalancing when adding nodes.

3. **301 vs 302 redirect?**
   301 = browser caches it, fewer server hits but no analytics. 302 = every click hits the server, enabling tracking. Choose 302 if analytics matter.

4. **How to handle hot/viral URLs?**
   Redis cache absorbs the read spike. Replicate hot keys across cache nodes. CDN edge caching for the most viral URLs.

5. **What if the counter overflows?**
   7-char Base62 supports 3.5T URLs. At 100M/month, that's 35,000 months (~2,900 years). If needed, add an 8th character.

6. **How to add analytics without slowing redirects?**
   Fire-and-forget to a message queue (Kafka/Kinesis). Consumer processes events async. Redirect returns immediately.

7. **How to prevent abuse?**
   Rate limiting per IP/API key at the gateway. Block known malicious URLs via a blocklist. CAPTCHA for anonymous creation.

8. **How does cache invalidation work?**
   TTL-based expiry (24-48 hours). URL mappings are immutable -- once created, a short URL always points to the same long URL. No invalidation needed for correctness.

9. **How to make custom aliases unique across shards?**
   Check all shards (or use a separate uniqueness index/Bloom filter) before accepting a custom alias. Custom aliases are rare, so the extra check is acceptable.

10. **What if a user creates the same long URL twice?**
    Option A: Return the existing short URL (requires a reverse index: longUrl -> shortUrl). Option B: Create a new short URL each time (simpler, uses more keys). Most systems choose B.

11. **How would you handle expiring URLs?**
    Store a TTL column. Background job deletes expired entries. Check expiry on read and return 404 if expired.

12. **How do you ensure high availability?**
    Multi-AZ deployment, DB replication, Redis cluster with replicas, health checks + auto-restart, circuit breakers for downstream failures.

13. **How would you migrate from SQL to NoSQL?**
    Dual-write during migration. Read from NoSQL, fall back to SQL. Backfill tool copies historical data. Verify with checksums. Cut over.

---

## How to Run

```bash
cd 01-url-shortener
../gradlew run
```

---

## What to Improve Later

- Add Redis cache layer for read-through caching
- Persistent database (replace in-memory store with Cassandra/DynamoDB)
- Rate limiting middleware per IP and API key
- Authentication and user-scoped URL management
- Click analytics dashboard (total clicks, referrers, geo, time series)
- A/B testing for encoding strategies (Base62 vs Base58 vs custom)
- URL expiration and cleanup job
- Custom alias support with uniqueness validation
- Bloom filter for fast collision detection
- Multi-region deployment with geo-routing
