# CAP Theorem Applied to URL Shortener

> Interview-ready reference. Know this cold -- CAP comes up in every system design round.

---

## CAP Theorem in 30 Seconds

In a distributed system, you can only guarantee **two out of three** properties at any given moment:

```
                  C
                 / \
                /   \
               / CAP \
              /  pick \
             /  two    \
            /___________\
           A             P
```

| Property | Definition | Plain English |
|----------|-----------|---------------|
| **C** - Consistency | Every read receives the most recent write | All nodes see the same data at the same time |
| **A** - Availability | Every request receives a non-error response | The system always responds, even if data is stale |
| **P** - Partition Tolerance | System continues operating despite network partitions | Nodes can't talk to each other but keep working |

### The Uncomfortable Truth

**P is not optional.** Network partitions happen in every distributed system. The real choice is:

```
  CP: When a partition occurs, sacrifice availability (block/error until consistent)
  AP: When a partition occurs, sacrifice consistency (serve potentially stale data)
```

---

## URL Shortener: AP System

### Why AP?

A URL shortener's primary operation is **reads (redirects)** -- roughly 100:1 read-to-write ratio. The system must prioritize:

1. **Availability**: A short URL that fails to redirect is useless. Users expect `bit.ly/abc123` to always work.
2. **Partition Tolerance**: Non-negotiable in any distributed deployment.
3. **Consistency**: Can be relaxed. If a newly created URL takes 1-2 seconds to propagate to all nodes, that is perfectly acceptable.

### Decision Matrix

```
+-----------------------+-------------+------------------------------------+
| Operation             | CAP Choice  | Reasoning                          |
+-----------------------+-------------+------------------------------------+
| Redirect (GET /:code) | AP          | Must always work. Stale = OK.      |
| Create URL (POST)     | AP + tunable| Availability matters, but check    |
|                       |             | for duplicates with conditional    |
|                       |             | writes.                            |
| Custom alias creation | Leaning CP  | Duplicates are unacceptable.       |
|                       |             | Use conditional write / LWT.       |
| Analytics (clicks)    | AP          | Approximate counts are fine.       |
+-----------------------+-------------+------------------------------------+
```

---

## Where Consistency Matters Less

### URL Redirect (the hot path)

```
User clicks short URL
        |
        v
  +-----+------+
  | Any replica |  <-- Serve from the nearest/fastest node
  +-----+------+
        |
        v
  302 Redirect to original URL
```

- If a URL was created 500ms ago and one replica hasn't received it yet, the worst case is a 404 that resolves on retry.
- This happens extremely rarely (only for brand-new URLs accessed within milliseconds of creation).
- Acceptable tradeoff: sub-second staleness vs. always-available redirects.

### Click Analytics

- Counting clicks doesn't need exact real-time accuracy.
- Eventual consistency with periodic aggregation is standard.
- "Your link got 1,247 clicks" vs. "Your link got 1,249 clicks" -- no user notices.

---

## Where Consistency Matters More

### Custom Alias Creation

Two users simultaneously request the alias `my-brand`:

```
  User A: POST /shorten { alias: "my-brand", url: "https://a.com" }
  User B: POST /shorten { alias: "my-brand", url: "https://b.com" }
           |                                    |
           v                                    v
      +----+----+                         +-----+----+
      | Node 1  |                         | Node 2   |
      | saves   |                         | saves    |
      | "my-brand" -> a.com               | "my-brand" -> b.com
      +---------+                         +----------+
                   CONFLICT! Which one wins?
```

**Solution: Conditional writes (compare-and-set)**

```
Cassandra:  INSERT INTO urls (...) IF NOT EXISTS;   -- Lightweight transaction
DynamoDB:   PutItem with ConditionExpression: "attribute_not_exists(shortCode)"
Redis:      SETNX (SET if Not eXists)
```

This gives us **per-key linearizability** without sacrificing system-wide availability.

---

## What Happens During a Network Partition

```
              Network Partition
                    |||
  +----------+     |||     +----------+
  | DC East  |     |||     | DC West  |
  | Node 1,2 |  X--|||--X | Node 3,4 |
  +----------+     |||     +----------+
                    |||

  AP Behavior (our choice):
  - Both DCs continue serving redirects independently
  - Writes accepted on both sides
  - Risk: conflicting custom aliases on different sides
  - After partition heals: merge with last-write-wins or conflict resolution

  CP Behavior (not our choice):
  - One DC goes read-only or returns errors
  - No conflicting writes
  - Users in the "minority" DC see errors/timeouts
```

### Post-Partition Reconciliation

| Conflict Type | Resolution Strategy |
|--------------|---------------------|
| Same short code, different URLs | Last-write-wins (timestamp-based) |
| Same custom alias, different URLs | First-write-wins (require conditional writes) |
| Click count divergence | Sum counters from both sides (CRDT counter) |

---

## Comparison with Other Systems

| System | CAP Choice | Why |
|--------|-----------|-----|
| **URL Shortener** | **AP** | Redirects must always work; eventual consistency is fine |
| Banking / Payments | **CP** | Cannot show wrong balance; double-spend is catastrophic |
| Social Media Feed | **AP** | Missing a post for 2 seconds is OK; feed must load |
| Inventory / E-commerce | **CP** | Overselling is expensive; consistency over speed |
| DNS | **AP** | Must resolve; TTL-based eventual consistency |
| Chat / Messaging | **AP** | Messages can arrive slightly out of order; always available |
| Distributed Lock Service | **CP** | Locks must be consistent or they are useless |

---

## Tradeoffs Table

| Tradeoff | AP (Our Choice) | CP (Alternative) |
|----------|----------------|-----------------|
| Redirect availability | Always works | May return errors during partition |
| New URL propagation | 1-2s eventual consistency | Immediately consistent, higher latency |
| Custom alias uniqueness | Need conditional writes | Guaranteed by blocking coordination |
| Write throughput | High (any node accepts writes) | Lower (must coordinate) |
| Operational complexity | Conflict resolution needed | Simpler data model |
| User experience | Smooth, fast | Occasional errors under partition |
| Database fit | Cassandra, DynamoDB | PostgreSQL, CockroachDB |

---

## Practical Interview Answer

> "For a URL shortener, I would choose AP because availability of redirects is critical -- users expect short URLs to always work. Eventual consistency with a 1-second lag is perfectly acceptable for the read path.
>
> For write operations like custom aliases, we can use conditional writes (Cassandra's `IF NOT EXISTS` or DynamoDB's `ConditionExpression`) to ensure uniqueness without giving up system-wide availability. This gives us per-key consistency where it matters.
>
> The database choice reflects this: Cassandra or DynamoDB are AP systems that support tunable consistency -- we can dial up consistency for alias creation while keeping redirects fast and available."

---

## Common Interview Follow-Up Questions

### Q: "Can you have all three? Is CAP really a binary choice?"

**A:** In normal operation (no partition), you get all three. CAP only forces a choice **during a partition**. Modern databases offer **tunable consistency** -- Cassandra lets you set consistency level per query:
- `ONE` for fast reads (AP behavior)
- `QUORUM` for stronger consistency (leaning CP)
- `ALL` for full consistency (CP behavior, sacrifices availability)

### Q: "What about PACELC?"

**A:** PACELC extends CAP: "During a **P**artition, choose **A** or **C**; **E**lse (normal operation), choose **L**atency or **C**onsistency."

For URL shortener: **PA/EL** -- during partition choose Availability, else choose Latency. We want fast reads at all times.

### Q: "How do you handle the case where a user creates a URL and immediately tries to access it?"

**A:** Read-your-own-writes consistency. Options:
1. **Sticky sessions**: Route the creator to the same node that handled the write.
2. **Write-through cache**: After creating, immediately populate the local cache.
3. **Synchronous replication to one replica**: Write to two nodes before acknowledging. Slightly higher write latency, but the URL is immediately accessible from multiple nodes.

### Q: "Why not just use PostgreSQL with read replicas?"

**A:** PostgreSQL with async replicas is effectively AP too. It works at moderate scale. The reason to choose Cassandra/DynamoDB:
- **Write throughput**: URL creation at 1000+ TPS across regions.
- **Multi-region**: Cassandra's masterless architecture avoids single-leader bottleneck.
- **Operational simplicity at scale**: No failover, no leader election.

At smaller scale (< 10M URLs), PostgreSQL is absolutely fine and simpler to operate.

### Q: "What consistency guarantees does your cache layer add?"

**A:** The cache (Redis) introduces another consistency surface:
- Cache may serve stale data if the DB was updated but cache wasn't invalidated.
- For URL shortener, this is fine -- URLs rarely change.
- For deleted URLs, set a short TTL or actively invalidate.
- This is a **cache-aside** pattern: check cache first, fall back to DB, populate cache on miss.
