# Cloud Services Mapping -- Social Media Feed System

## Component-to-Service Mapping

| Component | AWS | GCP | Azure | Notes |
|-----------|-----|-----|-------|-------|
| Timeline Cache | ElastiCache (Redis) | Memorystore | Azure Cache for Redis | Sorted Sets for pre-computed feeds |
| Message Queue | MSK (Kafka) | Pub/Sub | Event Hubs | Fan-out pipeline, partitioned by user ID |
| Tweet Storage | DynamoDB / Keyspaces | Bigtable | Cosmos DB | Write-heavy, time-series partitioned |
| User DB | RDS (PostgreSQL) | Cloud SQL | Azure SQL Database | Small relational, user profiles |
| Search | OpenSearch | -- | Cognitive Search | Full-text tweet search, hashtag indexing |
| Media | S3 + CloudFront | Cloud Storage + CDN | Blob Storage + CDN | Pre-signed URLs, image resizing via Lambda |
| Compute | ECS / EKS | Cloud Run / GKE | AKS | Fan-out workers on ECS tasks |
| Social Graph | ElastiCache (Redis) / Neptune | Memorystore | Azure Cache / Cosmos (graph) | Redis Sets for follow relationships |
| Trending | ElastiCache (Redis) | Memorystore | Azure Cache for Redis | ZINCRBY + ZRANGEBYSCORE for velocity |
| Monitoring | CloudWatch | Cloud Monitoring | Azure Monitor | Fan-out lag is THE critical metric |

---

## AWS Reference Architecture

```
                            ┌─────────────────────────────────────────────────┐
                            │                   CloudFront CDN                │
                            │              (media, static assets)             │
                            └──────────────────────┬──────────────────────────┘
                                                   │
                            ┌──────────────────────▼──────────────────────────┐
                            │              API Gateway / ALB                  │
                            │         (rate limiting, auth, routing)          │
                            └───────┬──────────────┬──────────────┬───────────┘
                                    │              │              │
                    ┌───────────────▼──┐  ┌───────▼────────┐  ┌──▼───────────────┐
                    │  Tweet Service   │  │  Feed Service   │  │  Search Service  │
                    │  (ECS Fargate)   │  │  (ECS Fargate)  │  │  (ECS Fargate)   │
                    └───────┬──────────┘  └───────┬────────┘  └──┬───────────────┘
                            │                     │              │
               ┌────────────▼────────┐            │              │
               │   MSK (Kafka)       │            │     ┌────────▼──────────┐
               │  topic: tweets      │            │     │   OpenSearch      │
               │  topic: fan-out     │            │     │   (full-text)     │
               └──┬─────────────┬────┘            │     └───────────────────┘
                  │             │                  │
     ┌────────────▼───┐  ┌─────▼──────────┐      │
     │  Fan-out       │  │  Trending       │      │
     │  Workers       │  │  Workers        │      │
     │  (ECS Tasks)   │  │  (ECS Tasks)    │      │
     │                │  │                 │      │
     │  Normal users: │  │  ZINCRBY per    │      │
     │  push to cache │  │  hashtag        │      │
     │                │  │                 │      │
     │  Celebrities:  │  │  Velocity calc  │      │
     │  skip (pull    │  │  every 5 min    │      │
     │  at read time) │  │                 │      │
     └───────┬────────┘  └──────┬──────────┘      │
             │                  │                  │
             ▼                  ▼                  │
     ┌───────────────────────────────────────┐     │
     │      ElastiCache (Redis Cluster)      │◄────┘
     │                                       │
     │  Timeline:  ZSET per user (max 200)   │
     │  Trending:  ZSET "trending:global"    │
     │  Graph:     SET  "followers:{userId}" │
     │  Celebrity: HASH "celeb_tweets:{id}"  │
     └───────────────────┬───────────────────┘
                         │
     ┌───────────────────▼───────────────────┐
     │        DynamoDB / Keyspaces           │
     │   (tweets table, partitioned by       │
     │    user_id, sorted by timestamp)      │
     └───────────────────────────────────────┘
     ┌───────────────────────────────────────┐
     │        RDS PostgreSQL (Multi-AZ)      │
     │   (users, follow relationships,       │
     │    celebrity threshold config)         │
     └───────────────────────────────────────┘
     ┌───────────────────────────────────────┐
     │        S3 (media bucket)              │
     │   + Lambda (image resize on upload)   │
     │   + CloudFront (delivery)             │
     └───────────────────────────────────────┘
```

---

## Cost Analysis

### Redis Timeline Cache (the big number)

```
Users:              300M MAU
Timeline size:      200 items per user (tweet IDs + scores in sorted set)
Per-item size:      ~60 bytes (8B tweet ID + 8B score + 44B overhead)
Per-user timeline:  200 x 60 = 12 KB
Total memory:       300M x 12 KB = 3.6 TB (active users only)

BUT: Not all 300M users are daily active.
DAU assumption:     150M (~50% of MAU)
Active timeline:    150M x 12 KB = 1.8 TB

With Redis overhead (fragmentation, replication):
  1.8 TB x 1.5 = ~2.7 TB primary
  With 1 replica: ~5.4 TB total

Full MAU pre-compute (worst case):
  300M x 12 KB x 1.5 overhead x 2 (replica) = ~10.8 TB
  Round up: ~12 TB

AWS ElastiCache cost:
  r7g.4xlarge = 105 GB, ~$2.50/hr = ~$1,800/month
  12 TB / 105 GB = ~115 nodes
  Cost: 115 x $1,800 = ~$207K/month for timeline cache alone
```

### Fan-out Write Operations

```
Daily tweets:       500M
Average followers:  200 (median, not mean -- power law distribution)
Fan-out ops/day:    500M x 200 = 100B Redis ZADD operations

But with hybrid approach (skip celebrities):
  Celebrity tweets:   ~5M tweets (1% of users, but 10% of tweets)
  Celebrity avg fans: 500K (these are NOT fanned out)
  Skipped ops:        5M x 500K = 2.5T (SAVED!)
  
  Actual fan-out:     ~95B ops/day (still huge, but 2.5T saved)

Redis throughput:
  Single node: ~100K ops/sec
  100B ops / 86400 sec = ~1.16M ops/sec sustained
  Need: ~12 Redis shards minimum (just for writes)
  With headroom: 20-30 shards for fan-out traffic
```

### Fan-out Compute Cost

```
Each fan-out worker processes ~10K ops/sec
Workers needed:     1.16M / 10K = ~116 concurrent workers
ECS Fargate (1 vCPU, 2GB):  ~$0.04/hr
Cost: 116 x $0.04 x 24 x 30 = ~$4,000/month

This is cheap compared to Redis -- compute scales linearly.
```

### Total Estimated Monthly Cost (Twitter-scale)

```
Timeline Redis:     ~$207K
Fan-out Redis:      ~$30K  (write throughput nodes)
Trending Redis:     ~$5K   (small, single ZSET)
Social Graph Redis: ~$50K  (300M users, follow sets)
DynamoDB:           ~$80K  (500M writes/day, on-demand)
RDS PostgreSQL:     ~$5K   (small, user profiles)
OpenSearch:         ~$40K  (tweet indexing)
ECS Compute:        ~$20K  (services + fan-out workers)
S3 + CloudFront:    ~$30K  (media storage + delivery)
Kafka (MSK):        ~$15K  (3-broker cluster)
─────────────────────────────
Total:              ~$480K/month (~$5.8M/year)
```

> **Interview tip**: You do NOT need to know exact numbers. Knowing the order of magnitude
> and that Redis is the dominant cost driver shows depth.

---

## Interview Tips

### Start Simple, Then Evolve

When asked "Design Twitter's feed":

```
Step 1: Start with fan-out on write (simple, works for small scale)
        "For a v1, every tweet gets pushed to all followers' timelines."

Step 2: Identify the bottleneck (celebrity problem)
        "This breaks when @KatyPerry tweets -- 100M writes per tweet."

Step 3: Introduce hybrid approach
        "We split users: normal = push, celebrity = pull at read time."

Step 4: Discuss the merge step
        "Feed = pre-computed cache + celebrity tweets, merged by timestamp."
```

This shows the interviewer you can reason about trade-offs, not just memorize architectures.

### Key Metrics to Mention

| Metric | Target | Why It Matters |
|--------|--------|----------------|
| Fan-out lag | < 5 seconds | Time from tweet to appearing in followers' feeds |
| Feed latency (p99) | < 500ms | Time to load a user's timeline |
| Celebrity tweet latency | < 200ms | Pull from cache, not compute on the fly |
| Timeline freshness | < 10 seconds | Staleness tolerance (AP system) |
| Trending update frequency | Every 5 minutes | Balance freshness vs compute cost |

### AWS-Specific Talking Points

- **ElastiCache Cluster Mode**: Shard timelines by user_id hash -- avoids hot spots
- **MSK vs SQS**: MSK for fan-out (ordered, partitioned), SQS for simple tasks
- **DynamoDB**: Use GSI on user_id + timestamp for tweet retrieval
- **ECS vs Lambda**: ECS for fan-out workers (long-running), Lambda for image resize (event-driven)
- **CloudFront**: Signed URLs for media, edge caching for trending topics
- **Auto-scaling**: Fan-out workers scale on Kafka consumer lag metric
