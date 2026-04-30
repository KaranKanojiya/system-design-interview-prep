# News Feed System (Facebook/LinkedIn) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway (REST/HTTP) + WAF | API Management + Front Door | Cloud Endpoints + Cloud Armor | TLS termination, rate limiting, auth, request routing |
| **Feed Service** | ECS/EKS (Fargate) | AKS | GKE | Core feed generation: cache read + celebrity pull + merge + rank |
| **Post Service** | ECS/EKS (Fargate) | AKS | GKE | Post CRUD, media upload, publish to message queue |
| **Fan-Out Workers** | ECS/EKS (Fargate) | AKS | GKE | Kafka consumers: push posts to follower timelines |
| **Post Storage** | DynamoDB (on-demand) | Cosmos DB | Firestore / Bigtable | Posts partitioned by authorId, GSI on timestamp |
| **Timeline Cache** | ElastiCache Redis (Cluster Mode) | Azure Cache for Redis | Memorystore (Redis) | Sorted Set per user: pre-computed feed, max 800 items |
| **Message Queue (Fan-Out)** | MSK (Managed Kafka) | Event Hubs | Pub/Sub | new-posts topic, partitioned by authorId for ordering |
| **Social Graph** | Neptune (Graph DB) | Cosmos DB (Gremlin API) | Neo4j on GKE | Follow relationships, friend-of-friend, suggestions |
| **Social Graph Cache** | ElastiCache Redis (Sets) | Azure Cache for Redis | Memorystore | Fast follower/following lookups: SMEMBERS, SCARD |
| **Search** | OpenSearch Service | Azure AI Search | Vertex AI Search | Full-text post search, hashtag indexing, user search |
| **Media Storage** | S3 + CloudFront (CDN) | Blob Storage + Azure CDN | Cloud Storage + Cloud CDN | Images, videos, thumbnails with global CDN delivery |
| **Ranking ML** | SageMaker (real-time inference) | Azure ML | Vertex AI | Personalized feed ranking model, real-time endpoint |
| **Real-Time Push** | AppSync (GraphQL subscriptions) / API GW WebSocket | SignalR Service | Firebase Realtime / Pub/Sub | Push new posts to online users via WebSocket |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Feed latency p50/p99, fan-out lag, cache hit rate |
| **DNS** | Route 53 (latency-based routing) | Traffic Manager | Cloud DNS | Multi-region, route to nearest edge |
| **User DB** | RDS Aurora PostgreSQL | Azure SQL | Cloud SQL / AlloyDB | User profiles, settings, auth -- small relational data |

---

## Fan-Out Architecture on AWS (Numbered)

```
User creates a new post (text, image, or video)
    |
    1. Client uploads media to S3 (pre-signed URL)
       Media processed: thumbnail generation (Lambda), video transcoding (MediaConvert)
       CloudFront CDN URL generated for each media asset
    |
    v
    2. POST /v1/posts -> API Gateway
       Auth: JWT token validation (Cognito)
       Rate limit: 100 posts/day per user (WAF rule)
       Body: { content: "Just got promoted!", mediaUrls: ["cdn.example.com/img1.jpg"] }
    |
    v
    3. Post Service (ECS Fargate):
       - Validate content (length, media count, profanity filter)
       - Generate postId (Snowflake ID: timestamp + worker + sequence)
       - Store in DynamoDB:
           PK: authorId="U001", SK: postId="post_001"
           content, mediaUrls, contentType, createdAt, visibility
       - Publish to Kafka (MSK):
           Topic: new-posts, Key: authorId (partition by author)
           Value: { postId, authorId, content, createdAt, followerCount }
    |
    v
+---------------------------------------------------------------+
|              MSK (Managed Kafka)                               |
|   Topic: new-posts                                            |
|   Partitions: 128 (keyed by authorId)                         |
|   Consumer groups: fan-out-workers, search-indexer,            |
|                    trending-workers, notification-workers      |
+---------------------------+-----------------------------------+
                            |
    4. Fan-Out Worker (ECS Fargate, 50+ tasks) consumes message:
       |
       +-- Is author a celebrity? (follower_count > 10,000)
       |     |
       |     +-- YES (celebrity path):
       |     |     5a. Write to celebrity cache:
       |     |         Redis HSET celebrity:U002:posts post_002 "{...}"
       |     |         Redis ZADD celebrity:U002:timeline <timestamp> post_002
       |     |         Redis EXPIRE celebrity:U002:posts 86400 (24h TTL)
       |     |         Cost: 3 Redis operations (vs 5,000,000 ZADD for push)
       |     |
       |     +-- NO (normal user path):
       |           5b. Lookup followers:
       |               Redis SMEMBERS followers:U001 -> [U003, U004, U005, ...]
       |               500 followers returned
       |           |
       |           v
       |           6. Batch fan-out to timelines (Redis pipeline):
       |              ZADD timeline:U003 <score> post_001
       |              ZADD timeline:U004 <score> post_001
       |              ZADD timeline:U005 <score> post_001
       |              ... (500 ZADD operations, pipelined in batches of 100)
       |              |
       |              v
       |           7. Trim timelines (keep max 800 items):
       |              ZREMRANGEBYRANK timeline:U003 0 -801
       |              (removes oldest items beyond 800)
       |
       v
    8. Parallel consumers also process the message:
       |
       +-- Search Indexer: index post in OpenSearch
       |     PUT /posts/_doc/post_001 { content, authorId, hashtags, createdAt }
       |
       +-- Trending Worker: extract hashtags, ZINCRBY trending:global #promoted 1
       |
       +-- Notification Worker: check @mentions, send push notifications via SNS
                            |
                            v
+---------------------------------------------------------------+
|   FEED READ PATH (when user opens app)                        |
|                                                                |
|   9. GET /v1/feed?limit=20&cursor=... -> API Gateway          |
|      -> Feed Service (ECS Fargate)                            |
|                                                                |
|   10. Read pre-computed timeline from Redis:                  |
|       ZREVRANGEBYSCORE timeline:U003 +inf <cursor_score> LIMIT 0 20
|       Returns: [post_042, post_041, post_039, ...] (20 post IDs)
|       Latency: 2ms                                            |
|                                                                |
|   11. Identify celebrity followees for this user:             |
|       Redis SMEMBERS following:U003 -> filter where           |
|       isCelebrity=true -> [U002, U005, U009]                  |
|                                                                |
|   12. Pull celebrity posts in parallel:                       |
|       ZREVRANGEBYSCORE celebrity:U002:timeline +inf <cursor> LIMIT 0 5
|       ZREVRANGEBYSCORE celebrity:U005:timeline +inf <cursor> LIMIT 0 5
|       ZREVRANGEBYSCORE celebrity:U009:timeline +inf <cursor> LIMIT 0 5
|       Parallel fetch, latency: max(3ms, 2ms, 4ms) = 4ms      |
|                                                                |
|   13. Hydrate post IDs -> full post objects:                  |
|       DynamoDB BatchGetItem: [post_042, post_041, ...]        |
|       Or: Redis MGET post:post_042, post:post_041, ...        |
|       (post content cached in Redis with 1h TTL)              |
|       Latency: 5ms (cache hit) or 15ms (DynamoDB fallback)   |
|                                                                |
|   14. Merge + Rank:                                           |
|       Combine pre-computed + celebrity posts (62 total)       |
|       Score each: affinity * recency * engagement * type      |
|       SageMaker real-time endpoint for ML ranking (10ms)      |
|       Select top 20 by score                                  |
|                                                                |
|   15. Build response with cursor:                             |
|       nextCursor = base64(last_item_timestamp:last_item_id)   |
|       Return: { items: [...], nextCursor: "abc123..." }       |
|       Total latency: 18-35ms                                  |
+---------------------------------------------------------------+
                            |
    16. Real-time push (online users):
        When a new post is fanned out to timeline:U003,
        if U003 has an active WebSocket connection:
          AppSync / API Gateway WebSocket -> push notification
          Client prepends new post to feed (optimistic update)
```

---

## Real-Time Push via WebSocket/AppSync

```
HOW REAL-TIME FEED UPDATES WORK (Numbered):

    1. User opens app -> client establishes WebSocket connection
       API Gateway WebSocket endpoint: wss://feed.example.com/ws
       OR: AppSync GraphQL subscription:
         subscription onNewFeedItem($userId: ID!) {
           newFeedItem(userId: $userId) { postId, authorName, content, mediaUrl }
         }

    2. Connection registered in DynamoDB:
       PK: userId="U003", connectionId="abc123", ttl=now+2h
       Used to look up active connections for a user

    3. When fan-out worker writes to timeline:U003:
       |
       v
    4. Check if U003 has active WebSocket connection:
       DynamoDB GET: PK=userId="U003"
       |
       +-- NO connection: skip (user will see post on next feed load)
       |
       +-- YES, connectionId="abc123":
             |
             v
    5. Push new post via WebSocket:
       API Gateway: POST @connections/abc123
       Body: { type: "NEW_FEED_ITEM", post: { postId, authorName, content } }

       OR AppSync: publish to subscription channel
       AppSync handles fan-out to all subscribed clients automatically

    6. Client receives push:
       - Prepend post to current feed view (optimistic)
       - Show "New posts available" banner (conservative)
       - Increment unread badge count

    SCALING WEBSOCKET CONNECTIONS (500M DAU):
    - Peak concurrent connections: ~150M (not all online simultaneously)
    - API Gateway WebSocket: 500 connections/sec per account (soft limit)
    - AppSync: managed, auto-scales to millions of subscriptions
    - Alternative: Fanout.io or Pusher for managed WebSocket at scale
    - Connection registry in DynamoDB: auto-scales, TTL cleanup

    COST-EFFECTIVE APPROACH:
    - Only push to users with active WebSocket connections
    - Inactive users (no connection): see updates on next feed load
    - Rate limit pushes: max 1 push per 5 seconds per user
      (batch multiple new posts into single push notification)
    - Use SNS for mobile push notifications (cheaper than WebSocket)
```

---

## Multi-Region Feed Serving

```
Feed reads MUST be fast globally. Users in Tokyo, London, Sao Paulo
all expect < 200ms feed latency. Single-region = 300ms+ for distant users.

                         +-------------------------------+
                         |       Route 53 (DNS)          |
                         |  Latency-based routing:       |
                         |  US users -> us-east-1        |
                         |  EU users -> eu-west-1        |
                         |  Asia users -> ap-northeast-1 |
                         +------+--------+--------+-----+
                                |        |        |
              +-----------------v--+ +---v--------v-----------+
              |    us-east-1       | |  eu-west-1 / ap-ne-1   |
              |    (PRIMARY)       | |  (READ REPLICAS)        |
              |                    | |                          |
              |  API GW + WAF     | |  API GW + WAF            |
              |  ECS (Feed, Post, | |  ECS (Feed Service       |
              |   Fan-Out Workers)| |   -- read-only)          |
              |  MSK Kafka        | |  MSK Kafka (mirror)      |
              |  ElastiCache Redis| |  ElastiCache Redis       |
              |   (primary cache) | |   (replica cache)        |
              |                    | |                          |
              |  DynamoDB Global  | |  DynamoDB Global          |
              |   Table (writer)  | |   Table (replica reader) |
              |                    | |                          |
              |  Neptune (graph)  | |  Neptune (read replica)  |
              |                    | |                          |
              |  OpenSearch       | |  OpenSearch (cross-       |
              |   (primary)       | |   cluster replication)   |
              +--------------------+ +--------------------------+
                       |                          |
                       +--- DynamoDB Global ------+
                       |   Tables: automatic      |
                       |   replication < 1 second  |
                       +--- S3 cross-region ------+
                           replication (media)

ARCHITECTURE DECISIONS FOR MULTI-REGION:

  1. POST WRITES: routed to primary region (us-east-1)
     - Fan-out happens in primary region
     - DynamoDB Global Tables replicate posts to all regions
     - Timeline caches in each region populated by regional fan-out workers
       consuming from mirrored Kafka topics (MSK cross-region replication)

  2. FEED READS: served from nearest region
     - Redis timeline cache is regional (not cross-region replicated)
     - Each region's fan-out workers build timelines from mirrored Kafka
     - DynamoDB Global Tables provide local read for post hydration
     - Result: feed reads hit only local resources -> < 50ms

  3. SOCIAL GRAPH: eventual consistency across regions
     - Follow/unfollow writes go to primary Neptune
     - Redis Sets replicated via Kafka events (follow/unfollow events)
     - A follow in Tokyo may take 1-2s to reflect in Sao Paulo
     - Acceptable: follow confirmation is instant to the user

  4. MEDIA: CloudFront global CDN
     - S3 origin in us-east-1
     - CloudFront edge caches in 400+ locations
     - Images/videos served from nearest edge (< 30ms globally)
     - Lambda@Edge for image resizing on the fly
```

### Multi-Region Feed Read Flow (Numbered)

```
User in Tokyo opens their feed:
    |
    1. DNS: Route 53 latency-based routing -> ap-northeast-1
       (Tokyo user routed to Asia-Pacific region, not US)
    |
    v
    2. API Gateway (ap-northeast-1):
       GET /v1/feed?limit=20
       Auth: JWT validation against regional Cognito pool
    |
    v
    3. Feed Service (ECS in ap-northeast-1):
       Read timeline from REGIONAL Redis:
         ZREVRANGEBYSCORE timeline:U003 +inf -inf LIMIT 0 20
       Latency: 2ms (same-region Redis, no cross-region hop)
    |
    v
    4. Pull celebrity posts from REGIONAL Redis:
       (celebrity caches replicated via regional fan-out workers)
       Latency: 4ms (same-region)
    |
    v
    5. Hydrate posts from DynamoDB Global Table:
       (read from LOCAL replica in ap-northeast-1)
       BatchGetItem: 25 post IDs
       Latency: 8ms (same-region DynamoDB, not cross-region)
    |
    v
    6. Rank with REGIONAL SageMaker endpoint:
       ML model deployed in ap-northeast-1
       Latency: 10ms (same-region inference)
    |
    v
    7. Return feed to user:
       Total latency: 2 + 4 + 8 + 10 = 24ms (vs 150ms+ if cross-region)
       Media URLs: CloudFront CDN (Tokyo edge cache, < 10ms)
    |
    v
    8. Total user-perceived latency: ~35ms
       (network: ~10ms Tokyo->ap-northeast-1, processing: ~25ms)
```

---

## Cost Estimation at Scale (500M DAU)

### Assumptions

```
Daily Active Users:          500,000,000 (500M DAU)
Monthly Active Users:        2,000,000,000 (2B MAU)
Posts created/day:           200,000,000 (200M)
Feed reads/day:              5,000,000,000 (5B -- avg 10 reads/user/day)
Peak feed reads/sec:         5B / 86400 * 3 = ~175,000 RPS (3x peak)
Avg followers per user:      200
Celebrity users (> 10K):     500,000 (0.025% of users)
Avg following per user:      300 (of which ~10 are celebrities)
Fan-out writes/day:          200M posts * 200 avg followers = 40B timeline writes
                             (minus celebrity posts: ~38B after hybrid optimization)
Timeline cache entries:      500M users * avg 200 cached posts = 100B entries
Media uploads/day:           80M images + 20M videos = 100M media files
WebSocket concurrent:        150M peak concurrent connections
```

### Monthly Cost Breakdown

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **API Gateway** | 150B requests/month (feed reads + posts + misc) | ~$525,000 |
| **WAF + Shield** | 150B inspected requests, DDoS protection | ~$50,000 |
| **ECS Fargate (Feed Service)** | 500 tasks, 4 vCPU / 8 GB (read path) | ~$220,000 |
| **ECS Fargate (Post Service)** | 100 tasks, 2 vCPU / 4 GB | ~$30,000 |
| **ECS Fargate (Fan-Out Workers)** | 200 tasks, 4 vCPU / 8 GB (write path) | ~$88,000 |
| **DynamoDB (posts)** | On-demand: 200M writes/day + 5B reads/day, 50 TB storage | ~$400,000 |
| **ElastiCache Redis (timelines)** | 100 shards, r6g.2xlarge, 1 replica each (6.4 TB total) | ~$350,000 |
| **ElastiCache Redis (social graph)** | 30 shards, r6g.xlarge, 1 replica each | ~$65,000 |
| **MSK (Kafka)** | 30 brokers, kafka.m5.4xlarge, 128 partitions, 3-day retention | ~$95,000 |
| **Neptune (social graph)** | db.r5.4xlarge, 3 read replicas, 50 TB storage | ~$40,000 |
| **OpenSearch (search)** | 20 data nodes, r6g.2xlarge, 3 master nodes | ~$65,000 |
| **S3 (media storage)** | 5 PB storage, 100M uploads/day | ~$120,000 |
| **CloudFront (CDN)** | 500 TB transfer/month, 200B requests/month | ~$450,000 |
| **SageMaker (ranking ML)** | 50 ml.c5.4xlarge endpoints, real-time inference | ~$120,000 |
| **AppSync / API GW WebSocket** | 150M concurrent connections, 10B messages/month | ~$180,000 |
| **Route 53** | Latency-based routing, health checks, 3 regions | ~$5,000 |
| **CloudWatch + X-Ray** | Custom metrics, distributed tracing, alarms | ~$30,000 |
| **Data transfer** | Cross-AZ, cross-region replication, internet egress | ~$200,000 |
| **Total (single region)** | | **~$3,033,000/month** |
| **Total (3 regions)** | Compute + cache replicated 3x, storage shared | **~$5,500,000/month** |

### Cost Optimization Strategies

1. **Reserved Instances / Savings Plans** -- 1-year commit for ECS, ElastiCache, Neptune saves 30-40% (~$300K/month saved)
2. **DynamoDB Reserved Capacity** -- predictable base load on reserved, bursts on on-demand (save 50% on base)
3. **CloudFront Price Class** -- use Price Class 200 (exclude expensive regions) if traffic allows
4. **Redis memory optimization** -- store only post IDs in timelines (not full posts). Hydrate from DynamoDB/post cache. Saves 80% Redis memory.
5. **Kafka tiered storage** -- move old messages to S3 (MSK tiered storage). Reduce broker storage costs.
6. **SageMaker Inference Components** -- share endpoints across models, auto-scale to zero during low traffic
7. **S3 Intelligent-Tiering** -- auto-tier old media to Infrequent Access / Glacier after 90 days
8. **Fan-out batching** -- pipeline 100 ZADD commands per Redis round-trip. Reduces network overhead by 100x.
9. **Spot instances** -- use Spot for fan-out workers (retry-safe, idempotent). Save 60-70% on compute.
10. **WebSocket connection throttling** -- only maintain connections for users active in last 5 minutes

### Cost at Different Scales

| Scale | DAU | Monthly Cost | Cost/DAU |
|-------|-----|-------------|---------|
| Startup | 100K | ~$8,000 | $0.080 |
| Growth | 10M | ~$120,000 | $0.012 |
| Scale | 100M | ~$800,000 | $0.008 |
| Facebook-scale | 500M | ~$3,000,000 | $0.006 |
| Facebook-scale (3 regions) | 500M | ~$5,500,000 | $0.011 |

---

## Interview Tip

> "For a Facebook-scale news feed on AWS, I'd use a **hybrid fan-out architecture**: normal users (< 10K followers) fan out on write via **Kafka + ECS workers** pushing post IDs to **Redis Sorted Set** timelines. Celebrities skip fan-out -- their posts are stored in a separate celebrity cache and **pulled at read time**, avoiding millions of writes per post. Feed reads merge the pre-computed timeline with celebrity posts, then **rank using SageMaker ML** (affinity * recency * engagement * content_type). Posts are stored in **DynamoDB Global Tables** for multi-region reads. Pagination is **cursor-based** (timestamp:postId), not offset, because offset breaks when new posts arrive. Real-time updates go through **AppSync WebSocket** subscriptions for online users. Media is served via **CloudFront CDN** from S3. The system is **AP** -- eventual consistency is fine for feeds. For multi-region, I use **latency-based Route 53 routing** so users hit the nearest region, with DynamoDB Global Tables and regional Redis caches ensuring all reads are local."

This shows you understand **hybrid fan-out, the celebrity problem, algorithmic ranking, cursor pagination, real-time push, and multi-region serving** -- the six pillars of news feed design.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Timeline cache | ElastiCache Redis (Sorted Sets) | 100 shards, cluster mode, 6.4 TB | O(log N) insert/read, score-based ranking, trim old items |
| Post storage | DynamoDB (Global Tables) | On-demand, PK=authorId, SK=postId | Write-heavy, auto-scales, multi-region replication |
| Fan-out pipeline | MSK (Managed Kafka) | 128 partitions, keyed by authorId | Ordered delivery per author, multiple consumer groups |
| Social graph | Neptune + Redis Sets | Neptune for traversal, Redis for fast lookups | Redis: O(1) follow/unfollow. Neptune: friend-of-friend queries. |
| Feed ranking | SageMaker real-time endpoint | ml.c5.4xlarge, auto-scaling | Personalized ranking model, < 10ms inference |
| Post search | OpenSearch | 20 data nodes, 3 master nodes | Full-text search, hashtag indexing, auto-complete |
| Media delivery | S3 + CloudFront | S3 origin, 400+ edge locations | Global CDN, < 30ms for images/videos worldwide |
| Real-time push | AppSync or API GW WebSocket | Managed WebSocket, auto-scales | Push new posts to online users, reduce polling |
| User profiles | RDS Aurora PostgreSQL | Multi-AZ, 2 read replicas | Small relational data, auth, settings |
| Trending topics | Redis Sorted Set + Lambda | ZINCRBY for counts, Lambda for velocity calc | Velocity-based trending detection every 5 minutes |
| Multi-region routing | Route 53 (latency-based) | 3 regions: US, EU, Asia | Route to nearest region for < 50ms feed reads |
| Celebrity detection | Redis + DynamoDB | Follower count threshold: 10K | Static threshold + dynamic promotion on fan-out lag |
