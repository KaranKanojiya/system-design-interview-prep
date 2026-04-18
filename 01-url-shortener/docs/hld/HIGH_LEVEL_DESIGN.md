# High-Level Design: URL Shortener Service

> Interview-optimized system design document.
> Target: 30-45 minute system design discussion.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Traffic Estimates (Back-of-the-Envelope)](#7-traffic-estimates-back-of-the-envelope)
8. [Data Model](#8-data-model)
9. [High-Level Architecture](#9-high-level-architecture)
10. [Component Deep Dive](#10-component-deep-dive)
11. [URL Generation Strategies](#11-url-generation-strategies)
12. [Scaling Strategy](#12-scaling-strategy)
13. [Database Choice](#13-database-choice)
14. [Caching Strategy](#14-caching-strategy)
15. [Bottlenecks and Solutions](#15-bottlenecks-and-solutions)
16. [Messaging and Eventing](#16-messaging-and-eventing)
17. [CAP Theorem Analysis](#17-cap-theorem-analysis)
18. [Cloud Services Mapping](#18-cloud-services-mapping)
19. [Tradeoffs Summary](#19-tradeoffs-summary)
20. [Interview Talking Points](#20-interview-talking-points)

---

## 1. Problem Statement

A URL shortener converts long URLs into short, fixed-length aliases that redirect users to the original destination.

**Why is it needed?**

- Long URLs are unwieldy for sharing on social media, SMS, or printed materials.
- Short URLs are easier to remember, type, and track.
- They enable click analytics (who clicked, when, from where).
- Character-limited platforms (e.g., old Twitter 140-char limit) require compact links.
- Branded short domains (e.g., `amzn.to`, `bit.ly`) improve trust and CTR.

**Core Workflow:**

```
User submits:  https://www.example.com/very/long/path?query=params&more=stuff
System returns: https://short.ly/aB3x7Kq
User visits:    https://short.ly/aB3x7Kq  -->  HTTP 301/302  -->  original URL
```

---

## 2. Scope

### In Scope

| Feature             | Description                                      |
|---------------------|--------------------------------------------------|
| Shorten URL         | Accept a long URL, return a short URL            |
| Redirect            | Given a short code, redirect to the original URL |
| Custom Aliases      | Allow users to pick a custom short code          |
| Expiration          | URLs expire after a configurable TTL             |
| Click Analytics     | Track click count, timestamp, referrer, geo      |
| Delete URL          | Allow creator to delete/deactivate a short URL   |

### Out of Scope

| Feature              | Reason                                          |
|----------------------|-------------------------------------------------|
| User Authentication  | Simplifies the core design; can be layered on   |
| Billing/Rate Limits  | Separate concern, not core to the design        |
| Link Preview/OG Tags | UI concern, not backend system design           |
| Admin Dashboard      | Frontend concern                                |
| Spam/Abuse Detection | Important in production but not core to HLD     |

---

## 3. Assumptions

| Parameter              | Value                          | Rationale                        |
|------------------------|--------------------------------|----------------------------------|
| URLs created per month | 100 million                    | High-traffic service like bit.ly |
| Read:Write ratio       | 100:1                          | Reads (redirects) dominate       |
| Data retention         | 5 years                        | Long-tail link usage             |
| Short code length      | 7 characters                   | Base62^7 = 3.5 trillion combos   |
| Character set          | [a-zA-Z0-9] (62 chars)        | URL-safe, no special characters  |
| Average URL length     | 200 bytes                      | Conservative estimate            |
| Peak traffic           | 3x average                     | Standard burst assumption        |

---

## 4. Functional Requirements

| ID   | Requirement                                                                 |
|------|-----------------------------------------------------------------------------|
| FR-1 | Given a long URL, the system generates a unique short URL                   |
| FR-2 | Given a short URL, the system redirects the user to the original URL        |
| FR-3 | Users can optionally specify a custom alias (e.g., `short.ly/my-brand`)     |
| FR-4 | URLs have a configurable expiration time (default: no expiry)               |
| FR-5 | The system tracks click analytics: count, timestamp, referrer, user-agent   |
| FR-6 | Users can delete a short URL they created                                   |
| FR-7 | Expired URLs return HTTP 410 Gone                                           |

---

## 5. Non-Functional Requirements

| Requirement       | Target              | Notes                                          |
|-------------------|----------------------|------------------------------------------------|
| Redirect Latency  | < 50 ms (p99)       | Cache-first design is essential                |
| Availability      | 99.99% (52 min/year) | Redirect must never go down                   |
| Durability        | No data loss         | Once created, a URL must remain accessible     |
| Scalability       | 10B+ redirects/month | Must handle 100:1 read-write ratio             |
| Non-Guessable     | Short codes must not be sequential or predictable | Security concern    |
| Consistency       | Eventual consistency is acceptable | Read-after-write within seconds   |
| Idempotency       | Same long URL can produce same or different short codes | Design choice |

---

## 6. API Design

### 6.1 Create Short URL

```
POST /api/v1/shorten
Content-Type: application/json
```

**Request:**

```json
{
  "original_url": "https://www.example.com/very/long/path?query=params",
  "custom_alias": "my-brand",        // optional
  "expires_at": "2027-01-01T00:00:00Z" // optional, ISO-8601
}
```

**Response (201 Created):**

```json
{
  "short_code": "aB3x7Kq",
  "short_url": "https://short.ly/aB3x7Kq",
  "original_url": "https://www.example.com/very/long/path?query=params",
  "created_at": "2026-04-18T10:30:00Z",
  "expires_at": "2027-01-01T00:00:00Z"
}
```

**Error (409 Conflict - alias taken):**

```json
{
  "error": "ALIAS_TAKEN",
  "message": "The custom alias 'my-brand' is already in use."
}
```

### 6.2 Redirect

```
GET /{shortCode}
```

**Response:** `HTTP 301 Moved Permanently` (or `302 Found` if analytics matter more)

```
HTTP/1.1 301 Moved Permanently
Location: https://www.example.com/very/long/path?query=params
```

> **Interview Tip:** 301 is cached by browsers (fewer server hits, less analytics).
> 302 forces the browser to always hit the server (better for analytics).
> Most URL shorteners use **302** in practice.

### 6.3 Get Stats

```
GET /api/v1/{shortCode}/stats
```

**Response (200 OK):**

```json
{
  "short_code": "aB3x7Kq",
  "original_url": "https://www.example.com/very/long/path?query=params",
  "created_at": "2026-04-18T10:30:00Z",
  "expires_at": "2027-01-01T00:00:00Z",
  "total_clicks": 15234,
  "clicks_by_day": [
    { "date": "2026-04-17", "count": 312 },
    { "date": "2026-04-18", "count": 187 }
  ],
  "top_referrers": [
    { "referrer": "twitter.com", "count": 8021 },
    { "referrer": "facebook.com", "count": 3102 }
  ]
}
```

### 6.4 Delete Short URL

```
DELETE /api/v1/{shortCode}
```

**Response:** `HTTP 204 No Content`

---

## 7. Traffic Estimates (Back-of-the-Envelope)

> **Tip for interviews:** Always do this on the whiteboard. Walk through each step aloud.

### 7.1 Write Traffic (URL Creation)

```
URLs created per month  = 100M
URLs created per day    = 100M / 30         = ~3.3M / day
URLs created per second = 3.3M / 86,400     = ~40 URLs/sec
Peak writes             = 40 * 3 (burst)    = ~120 URLs/sec
```

### 7.2 Read Traffic (Redirects)

```
Read:Write ratio        = 100:1
Redirects per second    = 40 * 100          = 4,000 redirects/sec
Peak redirects          = 4,000 * 3         = 12,000 redirects/sec
```

### 7.3 Storage Estimate (5 Years)

```
Total URLs in 5 years   = 100M * 12 * 5    = 6 billion URLs

Per record:
  short_code (7 chars)  =    7 bytes
  original_url (avg)    =  200 bytes
  created_at            =    8 bytes
  expires_at            =    8 bytes
  click_count           =    8 bytes
  user_id               =   16 bytes (UUID)
  overhead/indexes      =  ~53 bytes
  --------------------------------
  Total per record      = ~300 bytes

Total storage = 6B * 300 bytes = 1.8 TB
```

**Takeaway:** 1.8 TB over 5 years is very manageable for modern databases.

### 7.4 Bandwidth Estimate

```
Write bandwidth:
  40 req/sec * 300 bytes   = 12 KB/sec  (negligible)

Read bandwidth:
  4,000 req/sec * 300 bytes = 1.2 MB/sec (very manageable)
```

### 7.5 Cache Memory (80-20 Rule)

```
80% of traffic goes to 20% of URLs.

Daily redirects       = 4,000 * 86,400        = ~345M redirects/day
Unique URLs accessed  = 345M / 100 (avg hits) = ~3.5M unique URLs/day
Cache 20% hot URLs    = 3.5M * 0.2            = 700K entries
Memory per entry      = 300 bytes (key + value)
Cache memory          = 700K * 300 bytes       = ~210 MB

With headroom (2x)    = ~500 MB
```

**Takeaway:** A single Redis instance (typical 25-50 GB) handles this trivially.

### Summary Table

| Metric              | Value             |
|---------------------|-------------------|
| Writes/sec          | ~40 (peak: 120)   |
| Reads/sec           | ~4,000 (peak: 12K)|
| Storage (5 years)   | ~1.8 TB           |
| Write bandwidth     | ~12 KB/sec        |
| Read bandwidth      | ~1.2 MB/sec       |
| Cache memory needed | ~500 MB           |

---

## 8. Data Model

### Primary Table: `url_mapping`

| Column         | Type          | Notes                                              |
|----------------|---------------|----------------------------------------------------|
| `id`           | BIGINT / UUID | Primary key, auto-generated                        |
| `short_code`   | VARCHAR(7)    | Unique index, the Base62 code                      |
| `original_url` | TEXT          | The destination URL (up to 2048 chars)             |
| `created_at`   | TIMESTAMP     | Immutable, set on creation                         |
| `expires_at`   | TIMESTAMP     | Nullable, NULL = never expires                     |
| `click_count`  | BIGINT        | Denormalized counter for quick reads               |
| `user_id`      | UUID          | Nullable, for future user ownership                |
| `is_active`    | BOOLEAN       | Soft delete flag                                   |

**Indexes:**

- **Primary Key:** `id`
- **Unique Index:** `short_code` (this is the hot lookup path)
- **Index:** `user_id` (for listing a user's URLs)
- **TTL Index:** `expires_at` (for cleanup jobs, if using Cassandra/DynamoDB TTL)

### Analytics Table: `click_events`

| Column         | Type       | Notes                               |
|----------------|------------|-------------------------------------|
| `event_id`     | UUID       | Primary key                         |
| `short_code`   | VARCHAR(7) | Foreign reference                   |
| `clicked_at`   | TIMESTAMP  | Event timestamp                     |
| `referrer`     | TEXT       | HTTP Referer header                 |
| `user_agent`   | TEXT       | Browser/device info                 |
| `ip_address`   | VARCHAR(45)| For geo-lookup (IPv4/IPv6)          |
| `country`      | VARCHAR(3) | Derived from IP                     |

> **Design Choice:** `click_count` is denormalized in `url_mapping` to avoid
> expensive COUNT queries. The analytics table is append-only and can be stored
> in a columnar store (e.g., ClickHouse) or streamed to a data warehouse.

---

## 9. High-Level Architecture

```
                                    +------------------+
                                    |  ID Generator /  |
                                    |  Key Generation  |
                                    |  Service (KGS)   |
                                    +--------+---------+
                                             |
                                             | (pre-generated keys)
                                             v
+--------+     +-----------+     +--------------------+     +----------+
|        |     |           |     |                    |     |          |
| Client +---->+   Load    +---->+   API Servers      +---->+  Cache   |
|        |     |  Balancer |     |   (Stateless)      |     |  (Redis) |
+--------+     +-----------+     +--------------------+     +-----+----+
                                         |                        |
                                         |                   Cache Miss
                                         v                        |
                                 +-------+--------+               |
                                 |                |               v
                                 |   Database     +<--------------+
                                 |  (Cassandra /  |
                                 |   DynamoDB)    |
                                 +-------+--------+
                                         |
                                         | (click events via async writes)
                                         v
                                 +-------+--------+
                                 |                |
                                 |     Kafka      |
                                 |  (Event Bus)   |
                                 +-------+--------+
                                         |
                                         v
                                 +-------+--------+
                                 |   Analytics    |
                                 |   Service      |
                                 | (ClickHouse /  |
                                 |  Elasticsearch) |
                                 +----------------+
```

### Request Flow: Create Short URL

```
1. Client  --POST /shorten-->  Load Balancer
2. LB      -------->           API Server
3. API Server ---->            KGS (fetch a pre-generated key)
4. API Server ---->            Database (INSERT url_mapping)
5. API Server ---->            Cache (SET short_code -> original_url)
6. API Server  <---            Return short URL to client
```

### Request Flow: Redirect

```
1. Client  --GET /aB3x7Kq-->  Load Balancer
2. LB      -------->          API Server
3. API Server ---->           Cache (GET short_code)
4a. Cache HIT:                Return original_url, respond 302
4b. Cache MISS:               Query Database -> populate Cache -> respond 302
5. API Server ---->           Kafka (async: publish click event)
```

---

## 10. Component Deep Dive

### 10.1 API Gateway / Load Balancer

- **Role:** Route requests, SSL termination, rate limiting, request validation.
- **Implementation:** AWS ALB, Nginx, or Kong.
- **Why it matters:** Single entry point; deploy multiple for HA (active-passive or active-active).

### 10.2 Application Servers (Stateless)

- **Role:** Business logic for shortening, redirecting, and stats.
- **Stateless design:** No session affinity; any server can handle any request.
- **Scaling:** Horizontal auto-scaling based on CPU/request count.
- **Tech:** Spring Boot (Java), deployed in containers (ECS/EKS).

### 10.3 URL Generation Service

This is the heart of the system. Three main strategies exist (detailed in Section 11):

- **Base62 Encoding** of a unique counter/ID
- **Hash-based** (MD5/SHA-256 + truncation)
- **Pre-Generated Key Service (KGS)** -- recommended approach

### 10.4 Cache Layer (Redis)

- **Pattern:** Cache-aside (look-aside)
- **Key:** `short_code` -> `original_url`
- **TTL:** Match the URL's expiration, or default 24h for hot entries.
- **Eviction:** LRU (Least Recently Used)
- **Why Redis:** Single-digit ms latency, built-in TTL, cluster mode for sharding.

### 10.5 Database

- **Primary store:** Cassandra or DynamoDB (see Section 13 for comparison).
- **Access pattern:** Simple key-value lookup by `short_code`. No joins, no transactions.
- **This access pattern is what makes NoSQL ideal.**

### 10.6 Analytics Pipeline

- **Click events** are published asynchronously to Kafka.
- A consumer aggregates events into a time-series or columnar store.
- The `click_count` in `url_mapping` is updated via a periodic batch job or an async counter service (not on the redirect hot path).

---

## 11. URL Generation Strategies

### Strategy Comparison

| Strategy                        | How It Works                                               | Uniqueness        | Predictable? | Coordination Needed? | Collision Risk |
|---------------------------------|------------------------------------------------------------|--------------------|-------------|----------------------|----------------|
| **(a) MD5/SHA-256 + Truncate** | Hash the long URL, take first 7 Base62 chars               | High but not 100% | No          | None                 | **Yes**        |
| **(b) Base62 of Auto-Inc ID**  | DB generates sequential ID, encode to Base62               | Guaranteed         | **Yes**     | DB auto-increment    | None           |
| **(c) Key Generation Service** | Pre-generate random keys in bulk, hand out on demand       | Guaranteed         | No          | KGS coordination     | None           |
| **(d) Snowflake / UUID**       | Distributed ID generator (timestamp + machine + sequence)  | Guaranteed         | Partially   | Clock sync           | Virtually none |

### Detailed Analysis

#### (a) MD5/SHA-256 Hash + Truncation

```
original_url --> MD5 --> "5d41402abc4b2a76b9719d911017c592"
                         take first 7 chars --> "5d41402"
                         Base62 encode --> "aB3x7Kq"
```

- **Pros:** No coordination between servers. Same URL always produces the same hash.
- **Cons:** Collision risk with truncation. Must handle collisions (retry with salt). 7 chars of hex = only 268M combinations vs. Base62's 3.5T.
- **Mitigation:** On collision, append a counter and re-hash.

#### (b) Base62 Encoding of Auto-Increment ID

```
DB auto-increment ID: 123456789
Base62 encode: 123456789 --> "8m0Kx"
```

- **Pros:** Zero collisions. Simple. Compact.
- **Cons:** Sequential IDs are guessable (security risk). Single point of failure if using one DB for IDs. Requires coordination.
- **Mitigation:** Use multiple ID ranges (e.g., Server1 gets odd IDs, Server2 gets even).

#### (c) Pre-Generated Key Service (KGS) -- RECOMMENDED

```
KGS pre-generates millions of random 7-char Base62 keys.
Stores them in a "keys_available" table.
When API server needs a key:
  1. KGS moves a key from "keys_available" to "keys_used"
  2. Returns the key to the API server
```

- **Pros:** No collision. Not guessable. No runtime computation. Fast (just a DB lookup).
- **Cons:** KGS is a single point of failure (mitigate with replicas). Requires pre-generation.
- **Implementation Detail:** KGS can load keys into memory in batches (e.g., 1000 at a time). Multiple KGS instances each grab a disjoint batch. If a server dies, those unused keys are simply lost (acceptable given 3.5T total combinations).

**This is the best strategy for interviews -- it is simple to explain, avoids collisions, and is non-guessable.**

#### (d) Snowflake / UUID

```
64-bit ID: [1-bit unused][41-bit timestamp][10-bit machine][12-bit sequence]
Base62 encode the 64-bit number --> 7-11 char string
```

- **Pros:** Distributed, no coordination. Sortable by time.
- **Cons:** Longer codes (11 chars for 64-bit). Partially predictable (timestamp prefix). More complex to implement.

---

## 12. Scaling Strategy

### 12.1 Application Tier -- Horizontal Scaling

- App servers are stateless; add more instances behind the load balancer.
- Auto-scale based on CPU utilization or request rate.
- Target: each instance handles ~1,000 req/sec.
- At peak 12K req/sec, we need ~12-15 instances.

### 12.2 Database Sharding

| Strategy            | How It Works                                | Pros                      | Cons                            |
|---------------------|---------------------------------------------|---------------------------|---------------------------------|
| **Hash-Based**      | `shard = hash(short_code) % N`              | Even distribution         | Resharding is painful           |
| **Range-Based**     | `a-m` on shard 1, `n-z` on shard 2         | Simple, range queries     | Hot spots if distribution skews |
| **Consistent Hash** | Virtual nodes on a hash ring                | Minimal resharding impact | More complex implementation     |

**Recommended:** Consistent hashing on `short_code`. Cassandra and DynamoDB handle this natively via partition keys.

### 12.3 Read Replicas

- Deploy read replicas to handle the 100:1 read-heavy workload.
- Writes go to the primary; reads served from replicas.
- Acceptable staleness: a few seconds (eventual consistency is fine for redirects).

### 12.4 Cache Sharding

- Redis Cluster with consistent hashing.
- Partition by `short_code`.
- 3-6 Redis nodes with replication for HA.

### 12.5 Scaling Summary

```
                    +---+---+---+---+
                    | API Servers   |   (12-15 instances, auto-scaled)
                    +---+---+---+---+
                        |       |
              +---------+-+   +-+---------+
              | Redis     |   | Redis     |   (3-6 nodes, clustered)
              | Cluster   |   | Cluster   |
              +---------+-+   +-+---------+
                        |       |
              +---------+-+   +-+---------+
              | DB Shard 1|   | DB Shard 2|   (Cassandra ring / DynamoDB)
              | + Replica |   | + Replica |
              +-----------+   +-----------+
```

---

## 13. Database Choice

### Access Pattern Analysis

- **Write:** Insert a new `(short_code, original_url)` pair.
- **Read:** Lookup `original_url` by `short_code`.
- **No joins.** No multi-table transactions. No complex queries.
- **This is a textbook key-value access pattern.**

### Comparison Table

| Criteria              | PostgreSQL              | Cassandra                 | DynamoDB                  |
|-----------------------|-------------------------|---------------------------|---------------------------|
| **Data Model**        | Relational              | Wide-column (key-value)   | Key-value / document      |
| **Write Throughput**  | Moderate (single-leader)| Excellent (peer-to-peer)  | Excellent (managed)       |
| **Read Latency**      | Low (with index)        | Low (partition key lookup) | Low (single-digit ms)    |
| **Scaling**           | Vertical + read replicas| Horizontal (add nodes)    | Auto-scaling (serverless) |
| **Availability**      | 99.95% (with HA)        | 99.999% (multi-DC)        | 99.999% (multi-region)   |
| **Operational Cost**  | High (manage yourself)  | Medium (self or managed)  | Low (fully managed)       |
| **Schema Flexibility**| Rigid schema            | Flexible                  | Flexible                  |
| **Transactions**      | Full ACID               | Lightweight transactions  | Conditional writes        |
| **Best For**          | Complex queries, joins  | Write-heavy, distributed  | Serverless, managed NoSQL |

### Recommendation

**Primary choice: DynamoDB** (if on AWS) or **Cassandra** (if cloud-agnostic).

**Justification:**
1. Access pattern is simple key-value lookup -- no need for relational features.
2. Write-heavy at scale (40 writes/sec sustained, 120 peak) favors distributed writes.
3. High availability requirement (99.99%) aligns with multi-node peer-to-peer architecture.
4. Horizontal scaling without downtime is critical for a 5-year growth plan.
5. TTL support is native in both (automatic expiration cleanup).

> **Interview Note:** If the interviewer pushes back on NoSQL, acknowledge that PostgreSQL
> with proper indexing and read replicas works fine at this scale. The choice becomes
> more relevant at 10x-100x the traffic.

---

## 14. Caching Strategy

### 14.1 Pattern: Cache-Aside (Look-Aside)

```
Read Path:
  1. Check Redis for short_code
  2. If HIT  -> return original_url (fast path, <5ms)
  3. If MISS -> query DB -> store result in Redis -> return original_url

Write Path:
  1. Generate short_code
  2. Write to DB
  3. Write to Redis (write-through for new entries)
```

### 14.2 Why Cache-Aside?

- Simple to implement and reason about.
- Cache is not in the critical write path (if Redis fails, writes still succeed).
- Naturally handles cache warm-up via read traffic.

### 14.3 Configuration

| Parameter       | Value      | Rationale                                          |
|-----------------|------------|----------------------------------------------------|
| Eviction Policy | LRU        | Least Recently Used -- evicts cold URLs             |
| Default TTL     | 24 hours   | Prevents stale data for expired URLs                |
| Max Memory      | 1 GB       | Generous for ~500 MB working set                    |
| Replication     | 1 replica  | HA; promote replica if primary fails                |

### 14.4 Cache Warming

- On deployment or cache restart, proactively load the top-N most accessed URLs.
- Query the DB for URLs with the highest `click_count` and populate Redis.

### 14.5 Handling Expired URLs

- When a cached URL's `expires_at` is in the past, treat it as a miss.
- Return HTTP 410 Gone.
- Delete the key from cache.

### 14.6 Hot Key Problem

- A viral URL (e.g., shared by a celebrity) could get millions of hits/sec.
- **Solution:** Use local in-memory cache (Caffeine in Java) on each app server with a short TTL (5-10 sec) in front of Redis. This absorbs the thundering herd.

```
Client -> App Server [Local Cache: Caffeine, 10s TTL]
                |
                +-- miss --> Redis [Distributed Cache]
                                |
                                +-- miss --> Database
```

---

## 15. Bottlenecks and Solutions

| Bottleneck                     | Impact                          | Solution                                                          |
|--------------------------------|---------------------------------|-------------------------------------------------------------------|
| **Single Load Balancer**       | SPOF for all traffic            | Deploy LB in active-passive pair; use DNS failover                |
| **DB Write Bottleneck**        | Writes slow under peak load     | Shard the database; use async writes for analytics                |
| **Hot Key in Cache**           | One viral URL melts Redis       | Local in-memory cache (Caffeine) on app servers                   |
| **Key Collision (Hash-based)** | Two URLs get same short code    | Use KGS (no collisions) or retry with salt                        |
| **KGS Failure**                | Cannot generate new short URLs  | Run multiple KGS instances; each grabs disjoint key batches       |
| **Cache Stampede**             | Cache expires, all requests hit DB | Use lock/semaphore: only 1 thread refreshes, others wait       |
| **Database Failure**           | All reads and writes fail       | Multi-AZ deployment; read replicas; cache absorbs reads during failover |
| **Slow Analytics Writes**      | Redirect latency increases      | Decouple analytics via Kafka (async, non-blocking)                |

---

## 16. Messaging and Eventing

### Why Kafka?

The redirect path must be fast (<50ms). Writing analytics data synchronously on every redirect would increase latency and couple the redirect service to the analytics store.

### Architecture

```
+------------+     +--------+     +-------------------+     +-------------+
| API Server | --> | Kafka  | --> | Analytics         | --> | ClickHouse  |
| (Producer) |     | Topic: |     | Consumer Service  |     | / Druid     |
|            |     | clicks |     | (batch aggregate) |     | (OLAP Store)|
+------------+     +--------+     +-------------------+     +-------------+
```

### Click Event Schema

```json
{
  "short_code": "aB3x7Kq",
  "timestamp": "2026-04-18T10:30:15Z",
  "referrer": "https://twitter.com/...",
  "user_agent": "Mozilla/5.0...",
  "ip_address": "203.0.113.42",
  "country": "US"
}
```

### Key Design Decisions

- **Topic Partitioning:** Partition by `short_code` to ensure ordering per URL.
- **Consumer Group:** Multiple consumers for parallel processing.
- **Batch Aggregation:** Consumer batches events every 5-10 seconds, then bulk-inserts into the analytics store.
- **Counter Update:** A separate consumer periodically updates `click_count` in the `url_mapping` table (denormalized counter).

> **Interview Note:** Mentioning Kafka here shows you think about decoupling,
> async processing, and keeping the hot path lean. This is a strong signal.

---

## 17. CAP Theorem Analysis

### Background

The CAP theorem states a distributed system can guarantee at most two of:

- **C**onsistency -- Every read returns the most recent write.
- **A**vailability -- Every request receives a response.
- **P**artition Tolerance -- The system works despite network partitions.

Since network partitions are inevitable, the real choice is **CP vs. AP**.

### URL Shortener: AP System

| Property     | Stance      | Reasoning                                                       |
|-------------|-------------|------------------------------------------------------------------|
| Availability | **Prioritized** | A redirect failing is unacceptable. Users expect links to always work. |
| Consistency  | Relaxed     | A newly created URL being unavailable for 1-2 seconds is tolerable.   |
| Partition Tolerance | Required | Distributed system must handle network failures.                 |

### Why AP?

1. **Redirects are the hot path.** If a user clicks a short link and gets an error, the system has failed its primary purpose. Availability is paramount.
2. **Eventual consistency is fine.** After creating a short URL, if it takes 1-2 seconds to propagate to all replicas, that is acceptable. The user can retry.
3. **No financial transactions.** Unlike a banking system, there is no risk of monetary loss from stale reads.
4. **Cassandra and DynamoDB are AP by default.** They prioritize availability and partition tolerance, offering tunable consistency (e.g., `QUORUM` reads for stronger guarantees when needed).

### Consistency Tuning

- **Writes:** Use `QUORUM` (majority of replicas acknowledge) for durability.
- **Reads:** Use `ONE` for fastest redirects; accept possible stale data during partitions.
- This gives us a practical middle ground: strong enough for correctness, fast enough for performance.

---

## 18. Cloud Services Mapping

| Component               | AWS                        | GCP                         | Azure                        |
|--------------------------|----------------------------|-----------------------------|------------------------------|
| Load Balancer            | ALB / NLB                  | Cloud Load Balancing        | Azure Load Balancer / App GW |
| API Servers              | ECS Fargate / EKS          | Cloud Run / GKE             | AKS / App Service            |
| Cache                    | ElastiCache (Redis)        | Memorystore (Redis)         | Azure Cache for Redis        |
| Database (NoSQL)         | DynamoDB                   | Bigtable / Firestore        | Cosmos DB                    |
| Database (SQL)           | Aurora PostgreSQL           | Cloud SQL                   | Azure SQL / PostgreSQL       |
| Key Generation Service   | Lambda + DynamoDB           | Cloud Functions + Bigtable  | Azure Functions + Cosmos DB  |
| Message Queue            | MSK (Kafka) / SQS          | Pub/Sub                     | Event Hubs                   |
| Analytics Store          | Redshift / Athena           | BigQuery                    | Synapse Analytics            |
| CDN (optional)           | CloudFront                  | Cloud CDN                   | Azure CDN                    |
| DNS                      | Route 53                    | Cloud DNS                   | Azure DNS                    |
| Monitoring               | CloudWatch + X-Ray          | Cloud Monitoring + Trace    | Azure Monitor + App Insights |
| Object Storage (backups) | S3                          | Cloud Storage               | Blob Storage                 |

---

## 19. Tradeoffs Summary

| Decision                         | Option Chosen              | Alternative              | Why This Choice                                    |
|----------------------------------|----------------------------|--------------------------|----------------------------------------------------|
| URL generation                   | KGS (pre-generated keys)  | Hash + truncate          | No collisions, not guessable, simpler at scale     |
| Database                         | Cassandra / DynamoDB       | PostgreSQL               | Simple key-value pattern, write-heavy, easy scaling|
| Redirect status code             | 302 Found                  | 301 Moved Permanently    | 302 ensures every click hits server for analytics  |
| Consistency model                | Eventual (AP)              | Strong (CP)              | Availability is more critical than instant consistency |
| Analytics writes                 | Async (Kafka)              | Synchronous DB writes    | Keeps redirect path fast; decouples concerns       |
| Cache pattern                    | Cache-aside                | Write-through everywhere | Simpler; cache not on critical write path          |
| Short code length                | 7 characters               | 6 or 8                   | 3.5T combinations; enough for decades of growth    |
| Character set                    | Base62 [a-zA-Z0-9]        | Base64 (with +, /)       | URL-safe without encoding; clean in links          |
| Sharding strategy                | Consistent hashing         | Range-based              | Even distribution; minimal resharding overhead     |
| Counter storage                  | Denormalized in url_mapping| Real-time count from events | Faster reads; eventual accuracy is acceptable   |

---

## 20. Interview Talking Points

Use these as a mental checklist during the interview. Proactively mention these to demonstrate depth.

### Opening (2 min)

- Clarify requirements: "Is this a read-heavy or write-heavy system? What is the expected scale?"
- State assumptions explicitly (100M URLs/month, 100:1 ratio, 5-year retention).
- Define scope: in-scope and out-of-scope features.

### Estimation (5 min)

- Walk through back-of-the-envelope math on the whiteboard.
- Show writes/sec, reads/sec, storage, bandwidth, cache memory.
- This demonstrates structured thinking. Interviewers love seeing the math.

### Core Design (10 min)

- Draw the architecture diagram: Client -> LB -> API Servers -> Cache -> DB.
- Explain each component briefly.
- Focus on the **redirect hot path** -- this is where performance matters most.

### Deep Dives to Proactively Offer

- **"Let me walk through how URL generation works..."** -- Discuss KGS, why it avoids collisions, how keys are pre-generated and assigned.
- **"For caching, I would use cache-aside with Redis..."** -- Mention 80-20 rule, LRU eviction, hot key mitigation with local cache.
- **"I chose Cassandra/DynamoDB because..."** -- Key-value access pattern, no joins, write-heavy, horizontal scaling.
- **"For analytics, I would decouple using Kafka..."** -- Shows you think about async processing and keeping the hot path fast.
- **"Let me talk about the CAP theorem implications..."** -- AP system, eventual consistency is acceptable for redirects.

### Tradeoff Awareness

- **301 vs 302:** Show you understand the browser caching implications.
- **Hash vs KGS vs Counter:** Show you can compare multiple approaches with pros/cons.
- **SQL vs NoSQL:** Show you can justify the choice based on access patterns, not hype.
- **Sync vs Async analytics:** Show you know how to keep the critical path lean.

### Red Flags to Avoid

- Do NOT jump into code. Start with requirements and estimation.
- Do NOT pick a database without justifying it.
- Do NOT forget about caching -- it is essential at this read:write ratio.
- Do NOT design a single-server system. Always think distributed from the start.

### Bonus Points

- Mention **rate limiting** to prevent abuse (e.g., 100 URLs/min per IP).
- Mention **URL validation** (reject malicious URLs, check for redirects to phishing sites).
- Mention **monitoring and alerting** (latency p99, error rates, cache hit ratio).
- Mention **graceful degradation** (if DB is down, serve from cache; if cache is down, serve from DB).
- Mention **data cleanup** (periodic job to purge expired URLs and reclaim storage).

---

> **Final Tip:** The interviewer is not looking for a perfect production design.
> They want to see structured thinking, tradeoff analysis, and the ability to
> communicate clearly under time pressure. Lead with the big picture, then
> offer to dive deep into any component they find interesting.
