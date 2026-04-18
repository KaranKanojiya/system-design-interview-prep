# Technology Choices for URL Shortener

> For each technology: why chosen, alternatives, when to use which, and interview-level pros/cons.

---

## Architecture Overview

```
+--------+     +-----------+     +----------------+     +-----------+
| Client | --> | CDN/Edge  | --> | Load Balancer  | --> | API       |
|        |     | (302 cache)|    | (L7)           |     | Gateway   |
+--------+     +-----------+     +----------------+     +-----+-----+
                                                              |
                              +-------------------------------+
                              |
                    +---------v---------+
                    | URL Shortener     |
                    | Service (Java)    |
                    +---------+---------+
                              |
              +---------------+---------------+
              |               |               |
        +-----v-----+  +-----v-----+  +------v------+
        | Redis      |  | Cassandra |  | Kafka       |
        | (Cache)    |  | (Primary) |  | (Analytics) |
        +------------+  +-----------+  +-------------+
                                              |
                                        +-----v-----+
                                        | Analytics  |
                                        | Pipeline   |
                                        +------------+
```

---

## Core Language: Java

### Why Java

| Reason | Detail |
|--------|--------|
| Strong typing | Catches bugs at compile time; complex domain models are clearer |
| Ecosystem | Mature libraries for everything (HTTP, caching, serialization) |
| Performance | JIT compilation; handles high-throughput redirect workloads well |
| Interview standard | Most system design interviewers are comfortable reading Java |
| Concurrency | `java.util.concurrent` (AtomicLong, ConcurrentHashMap) is battle-tested |
| Hiring pool | Large talent pool for production maintenance |

### Alternatives

| Language | When to Choose Instead |
|----------|----------------------|
| Go | If you want lower memory footprint and simpler concurrency model |
| Python | Rapid prototyping; not ideal for high-throughput redirect service |
| Node.js | If the team is JS-heavy; single-threaded event loop handles I/O well |
| Rust | If you need bare-metal performance and zero-cost abstractions |

### Interview Tip

> "I chose Java because it's the language I'm strongest in, and its concurrency primitives and ecosystem make it well-suited for a high-throughput service. In production, Go would also be a great choice for the lower memory footprint."

---

## Primary Database: Cassandra (or DynamoDB)

### Why This Pattern Fits

URL shortener data access is simple: `shortCode -> URL`. This is a pure **key-value lookup** with:

- **Write-heavy** ingestion (millions of URLs created daily)
- **Read-heavy** redirects (100:1 read-to-write ratio)
- **No joins** needed
- **No complex queries** -- just point lookups by short code

### Cassandra

```
Table: urls
+------------+--------------------+-------------+-----------+----------+
| short_code | original_url       | custom_alias| expires_at| user_id  |
| (PK)       |                    |             |           |          |
+------------+--------------------+-------------+-----------+----------+
| abc123     | https://example... | my-link     | 2026-12-31| user-42  |
+------------+--------------------+-------------+-----------+----------+
```

| Pro | Con |
|-----|-----|
| Masterless (no single point of failure) | No transactions (lightweight transactions are slow) |
| Linear horizontal scaling | Eventual consistency by default |
| Tunable consistency per query | Operational complexity (compaction, repair) |
| Built for write-heavy workloads | Limited query flexibility (design tables around queries) |
| Multi-region replication built-in | Learning curve for data modeling |

### DynamoDB (AWS Alternative)

| Pro | Con |
|-----|-----|
| Fully managed (zero ops) | Vendor lock-in (AWS) |
| On-demand scaling | Cost can spike unpredictably at scale |
| Conditional writes (`attribute_not_exists`) | 400KB item size limit |
| Single-digit ms latency | Less flexible than self-managed Cassandra |
| Global tables for multi-region | Eventual consistency for global tables |

### Database Comparison

| Criteria | Cassandra | DynamoDB | PostgreSQL | MongoDB | Redis |
|----------|-----------|----------|------------|---------|-------|
| Data model fit | Key-value | Key-value | Relational (overkill) | Document (overkill) | Key-value |
| Write throughput | Excellent | Excellent | Good | Good | Excellent |
| Read latency | Low | Low | Low | Low | Sub-ms |
| Horizontal scaling | Native | Managed | Manual (read replicas) | Sharding | Cluster mode |
| Durability | Yes | Yes | Yes | Yes | Configurable |
| Multi-region | Built-in | Global tables | Complex | Atlas | Enterprise |
| Ops burden | Medium | None | Low | Low | Low |
| Cost at scale | Moderate | Variable | Moderate | Moderate | High (RAM) |
| Best for | Primary store at scale | Primary store (AWS) | < 10M URLs | Flexible schema needs | Cache layer |

### Interview Tip

> "I'd start with PostgreSQL for an MVP. At scale -- hundreds of millions of URLs and thousands of writes/sec -- I'd migrate to Cassandra for its masterless architecture and linear scaling. If on AWS, DynamoDB is the pragmatic choice since it's fully managed."

---

## Cache: Redis

### Why Redis

```
  Request flow:
  
  GET /abc123
      |
      v
  +-------+  HIT   +-----------+
  | Redis  | -----> | 302       |
  | Cache  |        | Redirect  |
  +---+----+        +-----------+
      |
      | MISS
      v
  +----------+     +-------+     +-----------+
  | Cassandra| --> | Redis  | --> | 302       |
  | (read)   |    | (write)|    | Redirect  |
  +----------+    +--------+    +-----------+
```

| Feature | Why It Matters |
|---------|---------------|
| Sub-millisecond reads | Redirect latency is critical for UX |
| TTL support | Auto-expire cached URLs; no manual cleanup |
| Distributed | Redis Cluster for horizontal scaling |
| Atomic operations | SETNX for cache stampede prevention |
| Pub/Sub | Cache invalidation across nodes |
| Data structures | Sorted sets for "top URLs" leaderboard |

### Redis vs Memcached

| Criteria | Redis | Memcached |
|----------|-------|-----------|
| Data structures | Strings, hashes, sets, sorted sets, lists | Strings only |
| Persistence | RDB/AOF snapshots | None |
| Replication | Built-in | None |
| TTL granularity | Per-key | Per-key |
| Memory efficiency | Slightly less | Slightly better (simpler) |
| Cluster mode | Redis Cluster | Client-side sharding |
| Use case | Cache + more (rate limiting, pub/sub) | Pure caching |
| Verdict | **Choose this** -- more versatile | Only if you need nothing but simple caching |

### Interview Tip

> "Redis is the cache layer for hot URLs. With the 80-20 rule, caching the top 20% of URLs handles 80% of traffic. Sub-millisecond lookups keep redirect latency under 10ms."

---

## Load Balancer

### Algorithms Compared

| Algorithm | How It Works | Best For | URL Shortener? |
|-----------|-------------|----------|----------------|
| **Round Robin** | Rotate through servers sequentially | Homogeneous servers, stateless services | Yes (default) |
| **Least Connections** | Send to server with fewest active connections | Varying request durations | Good alternative |
| **Consistent Hashing** | Hash the request key to a server | Sticky sessions, cache affinity | For cache-layer routing |
| **Weighted Round Robin** | Round robin with server weights | Mixed hardware | If servers differ |
| **IP Hash** | Hash client IP to a server | Session affinity | Not needed (stateless) |

### Recommendation

```
  Layer 7 (L7) Load Balancer
  - Route by URL path: /api/* -> service nodes
  - Health checks: /health endpoint
  - TLS termination at the LB
  - Algorithm: Round Robin (stateless service)
  
  Tools: AWS ALB, Nginx, HAProxy, Envoy
```

### Interview Tip

> "Round robin is sufficient since our service is stateless. If we wanted cache affinity -- routing the same short code to the same server to improve local cache hit rates -- we'd use consistent hashing on the short code."

---

## API Gateway

### Responsibilities

```
+-------------------+
|   API Gateway     |
+-------------------+
| - Rate limiting   |  <-- Token bucket per API key
| - Authentication  |  <-- API key validation
| - Request routing |  <-- /api/v1/* -> service
| - SSL termination |  <-- HTTPS -> HTTP internally
| - Request logging |  <-- Structured access logs
| - CORS headers    |  <-- For browser-based clients
+-------------------+
```

### Options

| Tool | When to Use |
|------|-------------|
| AWS API Gateway | All-in on AWS; managed, pay-per-request |
| Kong | Open source, plugin ecosystem, self-hosted or cloud |
| Nginx | Lightweight, if you only need reverse proxy + rate limiting |
| Envoy | Service mesh, advanced traffic management |

---

## Message Queue: Kafka

### Why Kafka for Analytics

Every redirect generates a click event. These events must be processed without slowing down the redirect.

```
  GET /abc123
      |
      +---> 302 Redirect (synchronous, fast)
      |
      +---> Kafka topic: "click-events"  (async, fire-and-forget)
                |
                v
            +---+---+
            |Consumer|
            +---+---+
                |
                v
          +-----+------+
          | Analytics   |
          | (ClickHouse,|
          |  Druid, S3) |
          +-------------+
```

| Feature | Why It Matters |
|---------|---------------|
| Async decoupling | Redirect latency not affected by analytics |
| Durability | Events are persisted; no data loss |
| Replay | Can reprocess click events if analytics pipeline fails |
| Throughput | Millions of events/sec |
| Ordering | Per-partition ordering (partition by short code) |

### Message Queue Comparison

| Criteria | Kafka | SQS | RabbitMQ |
|----------|-------|-----|----------|
| Throughput | Very high | High | Moderate |
| Ordering | Per-partition | FIFO queues (extra cost) | Per-queue |
| Replay | Yes (retention-based) | No (once consumed, gone) | No |
| Managed option | MSK, Confluent | Native AWS | CloudAMQP |
| Complexity | High (ZooKeeper/KRaft) | Low | Medium |
| Best for | Event streaming, analytics | Task queues, decoupling | Request-reply, routing |
| URL Shortener fit | **Click event streaming** | Simpler alternative | Overkill routing features |

### Interview Tip

> "I'd use Kafka to stream click events asynchronously. The redirect stays fast -- we fire a message and return the 302 immediately. Downstream consumers aggregate click data into an analytics store. If we need to recompute analytics, Kafka's replay capability lets us reprocess events."

---

## Technologies NOT Needed (and Why)

### Full-Text Search (Elasticsearch)

URL shortener does not need search. All lookups are by exact short code (primary key lookup). Mentioning this shows you understand when NOT to add complexity.

### Graph Database (Neo4j)

No relationships to traverse. Short code -> URL is a flat mapping.

### Object Storage (S3) -- Secondary Role

Not on the hot path. Useful for:
- Exporting analytics reports (CSV/Parquet to S3)
- Storing QR code images if generated for short URLs
- Backup exports of URL mappings

---

## CDN: Edge Caching for Redirects

### The 301 vs 302 Nuance

This is a frequently asked interview question for URL shorteners.

```
  301 Moved Permanently          302 Found (Temporary Redirect)
  +-----------------------+      +-----------------------+
  | Browser caches the    |      | Browser does NOT      |
  | redirect permanently  |      | cache the redirect    |
  | Next visit: browser   |      | Next visit: hits your |
  | goes directly to      |      | server again          |
  | original URL          |      |                       |
  +-----------------------+      +-----------------------+
  
  301: Faster for user            302: You see every click
  301: Lose analytics             302: Full analytics
  301: Can't change target URL    302: Can update target URL
```

| Feature | 301 | 302 |
|---------|-----|-----|
| Caching | Browser caches permanently | Not cached by browser |
| Analytics | Lose repeat visit data | See every click |
| URL updates | Cannot change target after first visit | Can change anytime |
| SEO | Passes link juice to target | Does not pass link juice |
| **Recommendation** | Only for permanent, never-changing links | **Default choice for URL shortener** |

### CDN Configuration

```
  CDN Edge (CloudFront / Cloudflare)
  - Cache 302 responses for short TTL (60-300 seconds)
  - Cache-Control: public, max-age=300
  - Reduces origin load for viral URLs
  - Custom cache key: just the short code path
```

---

## Observability

### Metrics (Prometheus + Grafana)

| Metric | Type | Alert Threshold |
|--------|------|-----------------|
| `redirect_latency_ms` | Histogram | p99 > 50ms |
| `redirect_count` | Counter | Rate drop > 50% in 5min |
| `url_creation_count` | Counter | Spike > 10x normal |
| `cache_hit_ratio` | Gauge | Below 80% |
| `db_query_latency_ms` | Histogram | p99 > 100ms |
| `error_rate_5xx` | Counter | > 1% of requests |

### Logging (ELK Stack)

```
  Structured log per request:
  {
    "timestamp": "2026-04-18T10:30:00Z",
    "shortCode": "abc123",
    "action": "redirect",
    "latencyMs": 3,
    "cacheHit": true,
    "statusCode": 302,
    "userAgent": "Mozilla/5.0...",
    "country": "US"
  }
```

### Distributed Tracing (Jaeger / X-Ray)

Trace a redirect through: CDN -> LB -> Gateway -> Service -> Cache/DB.

---

## Rate Limiting

### Token Bucket Algorithm

```
  Bucket capacity: 100 tokens
  Refill rate: 10 tokens/second
  
  Each request consumes 1 token
  
  t=0:  [100 tokens] -> Request -> [99 tokens]  OK
  t=0:  [99 tokens]  -> Request -> [98 tokens]  OK
  ...
  t=5:  [0 tokens]   -> Request -> REJECTED (429 Too Many Requests)
  t=6:  [10 tokens]  -> Request -> [9 tokens]   OK (refilled)
```

### Rate Limits by Operation

| Operation | Limit | Reason |
|-----------|-------|--------|
| URL creation | 100/min per API key | Prevent abuse/spam |
| Redirect | 1000/min per IP | Prevent DDoS |
| Analytics API | 30/min per API key | Expensive queries |

### Implementation

- **API Gateway level**: Most load balancers and gateways support this natively.
- **Redis-based**: `INCR` + `EXPIRE` for distributed rate limiting across nodes.

---

## Authentication

### Scope

| Operation | Auth Required? | Method |
|-----------|---------------|--------|
| Redirect (GET /:code) | No | Public -- anyone can follow a short URL |
| Create URL (POST) | Yes | API key in header |
| View analytics (GET /stats) | Yes | API key + ownership check |
| Delete URL (DELETE) | Yes | API key + ownership check |

### Implementation

```
  Header: X-API-Key: sk_live_abc123def456
  
  Gateway validates key -> extracts userId -> passes to service
```

API keys are simple, stateless, and sufficient for a URL shortener. OAuth would be overkill unless integrating with third-party apps.

---

## Technology Decision Flowchart

```
  Is this an MVP / interview demo?
  |
  +-- Yes --> PostgreSQL + local Redis + single server
  |
  +-- No, production scale?
       |
       +-- < 10M URLs --> PostgreSQL + Redis + single region
       |
       +-- 10M-1B URLs --> Cassandra/DynamoDB + Redis Cluster
       |                   + Kafka + multi-AZ
       |
       +-- > 1B URLs --> Cassandra multi-region + Redis Cluster
                          + Kafka + CDN + edge compute
```
