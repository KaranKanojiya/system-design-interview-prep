# Distributed Cache -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **CacheService** | ElastiCache Redis | Azure Cache for Redis | Memorystore (Redis) | Managed Redis, sub-ms latency |
| **ConsistentHashing** | ElastiCache Cluster Mode | Redis Cluster (Azure) | Memorystore Cluster | Auto-sharding across slots |
| **Replication** | ElastiCache Replicas (1-5 per shard) | Geo-replication | Read Replicas | Async by default, sync optional |
| **TTL/Eviction** | Redis maxmemory-policy | Same (Redis config) | Same (Redis config) | allkeys-lru is most common |
| **Monitoring** | CloudWatch | Azure Monitor | Cloud Monitoring | Hit rate, evictions, memory, CPU |
| **Write-Behind** | ElastiCache + Lambda + DynamoDB | Azure Functions + Cosmos DB | Cloud Functions + Firestore | Event-driven writeback |
| **L1 Local Cache** | App-level (Caffeine/Guava) | App-level (Caffeine/Guava) | App-level (Caffeine/Guava) | Not a cloud service -- runs in app |
| **CDN Cache** | CloudFront | Azure CDN / Front Door | Cloud CDN | Edge caching for static + API responses |

---

## AWS ElastiCache: Redis vs Memcached

This is a **common interview question**. Know the tradeoffs cold.

| Feature | ElastiCache Redis | ElastiCache Memcached |
|---------|-------------------|----------------------|
| Data structures | Strings, Lists, Sets, Sorted Sets, Hashes, Streams | Strings only (key-value) |
| Persistence | RDB snapshots + AOF | None (pure in-memory) |
| Replication | Yes (async, 1-5 read replicas per shard) | None |
| Cluster mode | Yes (up to 500 shards, auto-resharding) | Yes (up to 40 nodes, client-side sharding) |
| Multi-AZ failover | Yes (auto-failover to replica) | No (node loss = data loss) |
| Pub/Sub | Yes | No |
| Lua scripting | Yes | No |
| Max node size | 635 GB (r7g.16xlarge) | 635 GB |
| Threads | Single-threaded command execution (I/O threads in Redis 6+) | Multi-threaded |
| **Use when** | Need persistence, replication, complex data types, pub/sub | Simple key-value, multi-threaded perf, ephemeral cache only |

### When to Use Which

```
Use Redis when:
  - You need data structures beyond key-value (sorted sets for leaderboards, etc.)
  - You need persistence (survive restarts)
  - You need replication and automatic failover
  - You need pub/sub for cache invalidation
  - You need Lua scripting for atomic operations

Use Memcached when:
  - Simple key-value caching (HTML fragments, session data)
  - You need multi-threaded performance on a single node
  - Data is ephemeral -- loss on restart is acceptable
  - You want simplest possible setup
  - You need to scale horizontally with minimal overhead
```

**Interview one-liner:** "Redis for features, Memcached for simplicity. 90% of the time, Redis is the right answer."

---

## AWS DAX (DynamoDB Accelerator)

DAX is a **write-through, read-through cache** purpose-built for DynamoDB. Not a general-purpose cache.

```
Without DAX:  App --> DynamoDB                 (single-digit ms)
With DAX:     App --> DAX Cluster --> DynamoDB  (microsecond reads from cache)
```

| Aspect | Detail |
|--------|--------|
| Protocol | DynamoDB-compatible API (drop-in replacement) |
| Cache type | Write-through (writes go to DAX + DynamoDB simultaneously) |
| Read behavior | Item cache (GetItem/BatchGetItem) + Query cache (Query/Scan results) |
| TTL | Configurable per table (default 5 min for item cache, 1 min for query cache) |
| Cluster | 1-11 nodes, primary + replicas |
| Consistency | Eventually consistent reads from cache, strongly consistent reads bypass cache |
| **Use when** | DynamoDB read-heavy workload needing microsecond latency |
| **Don't use when** | Write-heavy workload, need strong consistency, non-DynamoDB data |

---

## Redis Cluster vs Redis Sentinel

| Aspect | Redis Cluster | Redis Sentinel |
|--------|---------------|----------------|
| **Purpose** | Sharding + HA | HA only (no sharding) |
| **Data distribution** | Hash slots (16,384 slots across N masters) | All data on one master |
| **Scaling** | Horizontal (add shards) | Vertical only (bigger master) |
| **Failover** | Built-in (replica promotes to master) | Sentinel nodes monitor + promote |
| **Max data** | Unlimited (add more shards) | Limited by single node memory |
| **Multi-key ops** | Only within same hash slot (use hash tags) | Full support (single master) |
| **Complexity** | Higher (client must be cluster-aware) | Lower (Sentinel handles routing) |
| **Use when** | Data > single node memory, need horizontal scale | Data fits one node, need only HA |

**Interview one-liner:** "Sentinel for HA when data fits one node. Cluster for sharding when it doesn't."

---

## Multi-AZ Deployment Architecture (AWS)

```
                        ┌──────────────────────────────────────┐
                        │           Route 53 (DNS)             │
                        │      (latency-based routing)         │
                        └───────────────┬──────────────────────┘
                                        │
                        ┌───────────────▼──────────────────────┐
                        │       ALB / API Gateway              │
                        │    (distributes to app servers)      │
                        └───────┬───────────────┬──────────────┘
                                │               │
               ┌────────────────▼───┐   ┌───────▼────────────────┐
               │   AZ-1 (Primary)   │   │    AZ-2 (Standby)      │
               │                    │   │                         │
               │  ┌──────────────┐  │   │  ┌──────────────┐      │
               │  │ App Server   │  │   │  │ App Server   │      │
               │  │ + L1 Cache   │  │   │  │ + L1 Cache   │      │
               │  │  (Caffeine)  │  │   │  │  (Caffeine)  │      │
               │  └──────┬───────┘  │   │  └──────┬───────┘      │
               │         │          │   │         │               │
               │  ┌──────▼───────┐  │   │  ┌──────▼───────┐      │
               │  │ Redis Master │◄─┼───┼──│ Redis Replica│      │
               │  │ (Shard 1)    │──┼───┼─►│ (Shard 1)    │      │
               │  └──────────────┘  │   │  └──────────────┘      │
               │  ┌──────────────┐  │   │  ┌──────────────┐      │
               │  │ Redis Master │◄─┼───┼──│ Redis Replica│      │
               │  │ (Shard 2)    │──┼───┼─►│ (Shard 2)    │      │
               │  └──────────────┘  │   │  └──────────────┘      │
               │  ┌──────────────┐  │   │  ┌──────────────┐      │
               │  │ Redis Master │◄─┼───┼──│ Redis Replica│      │
               │  │ (Shard 3)    │──┼───┼─►│ (Shard 3)    │      │
               │  └──────────────┘  │   │  └──────────────┘      │
               │                    │   │                         │
               └────────────────────┘   └─────────────────────────┘
                                        │
                        ┌───────────────▼──────────────────────┐
                        │        RDS (PostgreSQL)              │
                        │   Primary in AZ-1, Standby in AZ-2  │
                        │   (source of truth for cache misses) │
                        └──────────────────────────────────────┘
```

### Read/Write Flow (Numbered)

```
CACHE HIT (happy path):
  1. Client sends GET /api/users/42 to ALB
  2. ALB routes to App Server in AZ-1
  3. App Server checks L1 local cache (Caffeine) --> MISS
  4. App Server checks Redis Master (Shard for key "user:42") --> HIT
  5. Return cached value to client
  Latency: ~2-5ms

CACHE MISS:
  1. Client sends GET /api/users/42 to ALB
  2. ALB routes to App Server in AZ-1
  3. App Server checks L1 local cache --> MISS
  4. App Server checks Redis --> MISS
  5. App Server queries RDS PostgreSQL (source of truth)
  6. App Server writes result to Redis (cache-aside pattern)
  7. App Server writes to L1 local cache
  8. Return value to client
  Latency: ~10-50ms

WRITE:
  1. Client sends PUT /api/users/42 to ALB
  2. App Server updates RDS PostgreSQL
  3. App Server invalidates Redis key "user:42" (delete, not update)
  4. App Server invalidates L1 local cache
  5. Next read will trigger cache-aside refill
  Why invalidate instead of update? Avoids race conditions with concurrent writes.
```

---

## Failover Sequence (ElastiCache Multi-AZ)

```
Normal operation:
  Writes --> Redis Master (AZ-1) --async replication--> Redis Replica (AZ-2)
  Reads  --> Redis Master OR Replica (configurable)

Master failure sequence:
  1. ElastiCache detects master node failure (health check fails for ~30 seconds)
  2. ElastiCache promotes replica in AZ-2 to new master (~30-60 seconds)
  3. DNS endpoint updated to point to new master (same endpoint, no app change)
  4. Old master's AZ recovers --> old master joins as new replica
  5. Total downtime: ~60-90 seconds for writes, reads from replica unaffected

Data loss risk:
  - Async replication means last few writes before failure may be lost
  - Mitigation: Redis Streams or Kafka for critical writes (write to queue first)
  - For most caches: losing last 1-2 seconds of cached data is acceptable
    (cache miss → rebuild from DB)

Application behavior during failover:
  1. Write requests get connection errors for ~60 seconds
  2. Application retry logic kicks in (exponential backoff)
  3. Cache misses increase temporarily (fall through to DB)
  4. After promotion: writes resume to new master, cache refills organically
```

---

## CDN as Cache Layer

CDN is the **outermost cache layer** -- sits between users and your servers.

```
User --> CloudFront (CDN) --> ALB --> App Server --> Redis --> DB

With CDN caching:
  - Static assets: cached at 400+ edge locations, TTL = 24h
  - API responses: cached at edge for short TTL (10-60 seconds)
  - Personalized data: NOT cached at CDN (use Cache-Control: private)
```

### CDN Cache Control Headers

```
# Cache at CDN for 60 seconds, browser for 10 seconds
Cache-Control: public, max-age=10, s-maxage=60

# Never cache at CDN (personalized data)
Cache-Control: private, no-store

# Cache at CDN, but revalidate every request (ETag/If-None-Match)
Cache-Control: public, no-cache
```

### CDN Providers

| Provider | Strength | Best For |
|----------|----------|----------|
| CloudFront | Deep AWS integration, Lambda@Edge | AWS-native apps |
| Akamai | Largest network (365K+ servers), enterprise features | Enterprise, media streaming |
| Cloudflare | DDoS protection, Workers (edge compute), free tier | General purpose, cost-conscious |
| Fastly | Real-time purging (< 150ms), VCL scripting | Dynamic content, instant invalidation |

---

## Cost Estimation

### Small Scale (Startup -- <100K users)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| ElastiCache Redis | 1 node, cache.t3.medium (3.09 GB) | ~$50 |
| No replicas | (accept downtime on failure) | $0 |
| CloudWatch | Basic metrics | ~$5 |
| **Total** | | **~$55/month** |

### Medium Scale (Growth -- 1M-10M users)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| ElastiCache Redis | Cluster mode, 3 shards | ~$600 |
| Replicas | 1 replica per shard (Multi-AZ) | ~$600 |
| CloudWatch + alarms | Detailed metrics | ~$30 |
| CloudFront | 1 TB transfer/month | ~$85 |
| **Total** | | **~$1,300/month** |

### Large Scale (Enterprise -- 100M+ users)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| ElastiCache Redis | Cluster mode, 20+ shards, r7g.2xlarge | ~$15,000 |
| Replicas | 2 replicas per shard (cross-AZ) | ~$30,000 |
| CloudWatch + custom dashboards | Full observability | ~$500 |
| CloudFront | 100 TB transfer/month | ~$6,000 |
| Global Datastore | Cross-region replication (2 regions) | ~$30,000 |
| **Total** | | **~$80,000/month** |

### Cost Optimization Strategies

1. **Reserved nodes** -- 1-year commitment saves 30%, 3-year saves 55%
2. **Right-size nodes** -- monitor memory usage, don't over-provision
3. **Data compression** -- compress values before caching (snappy/lz4), reduces memory 2-4x
4. **TTL tuning** -- shorter TTLs reduce memory, longer TTLs reduce DB load. Find the balance.
5. **Tiered caching** -- L1 local cache (free) absorbs 50-80% of reads before hitting Redis
6. **Eviction policy** -- use `allkeys-lru` to auto-evict cold data instead of OOM errors

---

## Interview Tip

> "For a distributed cache, I'd use **ElastiCache Redis in cluster mode** -- it gives me automatic sharding via hash slots (similar to consistent hashing), Multi-AZ replication for HA, and sub-millisecond latency. I'd add an **L1 local cache (Caffeine)** in the app for hot keys to reduce Redis round-trips by 50-80%. For the CDN layer, CloudFront caches API responses at the edge with short TTLs. The three-tier caching architecture -- CDN, Redis, local -- gives us progressively lower latency and higher throughput at each level."

This shows you understand **layered caching** and can map your design to real infrastructure.

---

## Quick Reference: Which Service When

| Scale | Cache Layer | Config | Monthly Cost |
|-------|------------|--------|-------------|
| MVP (<10K users) | Single Redis node (t3.micro) | No replicas, no cluster | ~$15 |
| Growth (10K-1M) | Redis + 1 replica | Multi-AZ, basic monitoring | ~$200 |
| Scale (1M-100M) | Redis Cluster (3-10 shards) + CDN | Multi-AZ, replicas, CloudFront | ~$2,000-15,000 |
| Enterprise (100M+) | Redis Cluster (20+ shards) + Global Datastore + CDN | Cross-region, reserved nodes | ~$50,000-100,000 |
