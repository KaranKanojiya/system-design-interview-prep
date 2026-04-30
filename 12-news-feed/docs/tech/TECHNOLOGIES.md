# Technologies -- News Feed System (Facebook/LinkedIn)

> Production technology stack for a news feed system. For each tech:
> why it fits, key operations, data model, complexity analysis, and how our
> Java implementation maps to the production version.
>
> **Differentiator from Project 05:** Deeper coverage of ML ranking pipeline,
> WebSocket/SSE for real-time push, content-type-aware storage, and
> engagement affinity computation.

---

## Technology Map

```
  +-------------------+     +-------------------+     +-------------------+
  |   Client (App)    |---->|   API Gateway /   |---->|   Feed Service    |
  |   Mobile / Web    |     |   Load Balancer   |     |   (Facade/Mediator)|
  +-------------------+     +-------------------+     +-------------------+
                                                             |
                    +--------+--------+--------+--------+----+----+
                    |        |        |        |        |         |
                    v        v        v        v        v         v
              +--------+ +------+ +------+ +------+ +------+ +--------+
              | Redis  | |Redis | |Redis | |Cassan| |Post  | |Elastic |
              |Timeline| |Social| |Engage| |dra/  | |greSQL| |search  |
              |Cache   | |Graph | |Counts| |Dynamo| |Users | |Search  |
              +--------+ +------+ +------+ +------+ +------+ +--------+
                    ^                                    ^
                    |                                    |
              +----------+                        +-----------+
              |  Kafka   |                        | ML Ranking|
              | Event    |                        | Pipeline  |
              | Pipeline |                        | (TF Serve)|
              +----------+                        +-----------+
                    ^
                    |
              +-----------+        +-----------+
              | WebSocket |        | S3 / CDN  |
              | SSE Push  |        | Media     |
              +-----------+        +-----------+
```

---

## 1. Redis Sorted Set -- Timeline Cache

**THE key data structure.** Every user's home timeline is a Redis Sorted Set.

### Data Model

```
Key:    timeline:{userId}
Type:   Sorted Set (ZSET)
Member: postId (string)
Score:  timestamp (epoch millis) or engagement score

Example:
  ZADD timeline:user-42 1713446400000 "post-101"
  ZADD timeline:user-42 1713446500000 "post-102"
  ZADD timeline:user-42 1713446600000 "post-103"
```

### Key Operations

| Operation | Redis Command | Complexity | Use Case |
|-----------|--------------|------------|----------|
| Add post to timeline | `ZADD timeline:{userId} {score} {postId}` | O(log N) | Fan-out-on-write pushes here |
| Get feed (newest first) | `ZREVRANGEBYSCORE timeline:{userId} +inf -inf LIMIT 0 50` | O(log N + M) | Feed generation |
| Get feed (cursor) | `ZREVRANGEBYSCORE timeline:{userId} ({maxScore} -inf LIMIT 0 50` | O(log N + M) | Cursor-based pagination |
| Remove post | `ZREM timeline:{userId} {postId}` | O(log N) | Post deletion |
| Evict oldest | `ZREMRANGEBYRANK timeline:{userId} 0 -{keepCount}` | O(log N + M) | Keep max 1000 items |
| Timeline size | `ZCARD timeline:{userId}` | O(1) | Monitoring |

### Why Sorted Set (Not a List)?

| Feature | Sorted Set | List |
|---------|-----------|------|
| Insert by score | O(log N) -- auto-sorted | O(N) -- must find position |
| Range by score | O(log N + M) | Not supported |
| Dedup | Built-in (same member = update score) | Must check manually |
| Remove by value | O(log N) | O(N) scan |
| Pagination by score | Native ZRANGEBYSCORE | Not supported |
| Cursor-based pagination | Exclusive bound `(score` | Not possible |

### Cursor Pagination with ZREVRANGEBYSCORE

```
First page (no cursor):
  ZREVRANGEBYSCORE timeline:user-42 +inf -inf LIMIT 0 20
  -> Returns top 20 posts by score (highest first)
  -> Last item score = 85.7 -> cursor = "85.7|post-99"

Second page (cursor = score 85.7):
  ZREVRANGEBYSCORE timeline:user-42 (85.7 -inf LIMIT 0 20
  -> "(" means exclusive: skip items WITH score 85.7
  -> Returns next 20 posts with score < 85.7

Tie-breaking:
  If two posts have the same score, add postId hash as microsecond jitter:
  score = baseScore + hash(postId) * 0.000001
  Ensures unique scores, correct cursor semantics
```

### Capacity Planning

```
Per user:
  1000 items max x ~50 bytes per entry = ~50 KB per user
  500M active users x 50 KB = ~25 TB

Redis cluster:
  ~25 TB / 25 GB per shard = ~1000 shards
  Shard key: userId (consistent hashing for even distribution)
  Read replicas: 2 per shard (handle 3x read throughput)
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Storage | `TreeMap<Double, Set<String>>` per user | Redis ZSET, 1000 shards |
| Add post | `treeMap.computeIfAbsent(score, k -> new TreeSet<>()).add(postId)` | `ZADD timeline:{userId} {score} {postId}` |
| Get feed | `treeMap.descendingMap().values().stream().flatMap(...)` | `ZREVRANGEBYSCORE ... LIMIT` |
| Eviction | Manual `while (totalSize > 1000) remove first entry` | `ZREMRANGEBYRANK ... 0 -{keepCount}` |
| Persistence | JVM heap (lost on restart) | Redis AOF + RDB, replicated |

### Interview Talking Point

> "Each user's timeline is a Redis Sorted Set capped at 1000 items. ZADD is
> O(log N) and idempotent -- same postId just updates the score. Cursor-based
> pagination maps directly to ZREVRANGEBYSCORE with exclusive lower bound.
> We shard by userId across ~1000 Redis nodes."

---

## 2. Kafka -- Fan-Out Event Pipeline

The async backbone that distributes posts to follower timelines and drives
notifications, engagement updates, and search indexing.

### Architecture

```
  Post Published / Liked / Commented / Shared
       |
       v
  +-----------------------------+
  | Kafka Topic:                |
  | "post.events"               |
  | Partitions: 256             |
  | Partition Key: author userId|
  +-----------------------------+
       |
       +---> Consumer Group: "fanout-workers" (64 instances)
       |     - Read message
       |     - Fetch followers from social graph
       |     - ZADD to each follower's timeline
       |
       +---> Consumer Group: "engagement-updater" (16 instances)
       |     - HINCRBY engagement:{postId} likes/comments/shares
       |     - Update affinity scores
       |
       +---> Consumer Group: "notification-sender" (16 instances)
       |     - Push notifications for close friends, likes, comments
       |     - WebSocket push for real-time feed updates
       |
       +---> Consumer Group: "search-indexer" (8 instances)
             - Bulk index to Elasticsearch
```

### Message Schema

```json
{
  "eventType": "post.published",
  "postId": "post-123",
  "authorId": "user-42",
  "content": "Just shipped the new feature! #java #systemdesign",
  "contentType": "TEXT",
  "hashtags": ["java", "systemdesign"],
  "followerCount": 5000,
  "authorType": "NORMAL",
  "timestamp": 1713446400000
}
```

```json
{
  "eventType": "post.liked",
  "postId": "post-123",
  "authorId": "user-42",
  "likerId": "user-7",
  "timestamp": 1713446500000
}
```

### Partition Strategy

```
Partition key = author's userId (for post.published)
Partition key = postId (for post.liked, post.commented, post.shared)

Why author userId for publishes:
  - All posts by the same author go to the same partition
  - Preserves ordering of posts from the same author
  - Prevents two workers from fanning out the same author's post simultaneously

Why postId for engagements:
  - All likes/comments on the same post go to the same partition
  - One worker handles all engagement for a given post
  - Prevents race conditions on engagement counters
```

### Why Kafka Over SQS?

| Feature | Kafka | SQS |
|---------|-------|-----|
| Ordering | Per-partition ordering by key | FIFO queues (limited throughput) |
| Consumer groups | Multiple independent groups on same topic | One queue per consumer |
| Replay | Seek to offset, replay events | Messages deleted after consumption |
| Throughput | Millions of msgs/sec per cluster | ~3K msgs/sec per FIFO queue |
| Fan-out to multiple consumers | Built-in (consumer groups) | Requires SNS + multiple queues |
| Retention | Configurable (7 days default) | 4 days max |

### Delivery Guarantee

At-least-once. Consumer commits offset after processing. If crash before
commit, message is redelivered. Dedup by:
- ZADD idempotency for timeline pushes (same postId = no duplicate)
- Dedup key for engagement: `SETNX dedup:like:{userId}:{postId} 1 EX 300`

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Event bus | `List<PostEventListener>` (in-process) | Kafka topic with 256 partitions |
| Delivery | Synchronous method calls | At-least-once with offset commits |
| Consumers | Single-threaded listeners | 64 fan-out workers, 16 engagement updaters |
| Replay | Not supported | Seek to offset, replay entire topic |
| Ordering | Guaranteed (single thread) | Per-partition (guaranteed per author) |

### Interview Talking Point

> "Fan-out goes through Kafka partitioned by author userId. Independent
> consumer groups handle timeline writes, engagement updates, notifications,
> and search indexing. At-least-once delivery, deduped by Redis ZADD
> idempotency. Engagement events partitioned by postId to serialize counter updates."

---

## 3. Cassandra / DynamoDB -- Post Storage

The durable store for all post content. Optimized for write-heavy workloads
and time-series access patterns.

### Data Model (Cassandra)

```
CREATE TABLE posts_by_user (
    user_id       TEXT,
    created_at    TIMESTAMP,
    post_id       TEXT,
    content       TEXT,
    content_type  TEXT,         -- TEXT, IMAGE, VIDEO, LINK, POLL
    media_urls    LIST<TEXT>,
    hashtags      SET<TEXT>,
    like_count    INT,
    comment_count INT,
    share_count   INT,
    deleted       BOOLEAN,
    PRIMARY KEY ((user_id), created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC);

CREATE TABLE posts_by_id (
    post_id       TEXT PRIMARY KEY,
    user_id       TEXT,
    content       TEXT,
    content_type  TEXT,
    media_urls    LIST<TEXT>,
    hashtags      SET<TEXT>,
    like_count    INT,
    comment_count INT,
    share_count   INT,
    created_at    TIMESTAMP,
    deleted       BOOLEAN
);
```

### Access Patterns

| Query | CQL | Partition |
|-------|-----|-----------|
| Get user's recent posts | `SELECT * FROM posts_by_user WHERE user_id = ? LIMIT 20` | Single partition |
| Get specific post | `SELECT * FROM posts_by_id WHERE post_id = ?` | Single row |
| Celebrity pull (read path) | `SELECT * FROM posts_by_user WHERE user_id = ? AND created_at > ? LIMIT 10` | Single partition, range scan |
| Soft delete | `UPDATE posts_by_id SET deleted = true WHERE post_id = ?` | Single row |

### DynamoDB Alternative

```
Table: Posts
  Partition Key: userId
  Sort Key: createdAt#postId

GSI: PostById
  Partition Key: postId
  
Throughput: On-demand (auto-scale)
TTL: createdAt + 90 days (auto-expire old posts)
```

### Why Cassandra/DynamoDB (Not PostgreSQL)?

| Requirement | Cassandra/DynamoDB | PostgreSQL |
|-------------|-------------------|------------|
| Write throughput | Millions of writes/sec | Thousands (single-master) |
| Horizontal scaling | Linear scale-out, consistent hashing | Vertical scaling, read replicas |
| Time-series queries | Wide row pattern, efficient range scan | Requires index, slower at scale |
| Availability | Masterless (Cassandra), multi-AZ (DynamoDB) | Single master, failover lag |
| TTL for old data | Native TTL support | Manual archival |

### Wide Row Pattern

```
Partition: user_id = "user-42"
  +----------+----------+---------+---------+---------+
  | post-5   | post-4   | post-3  | post-2  | post-1  |
  | 10:05am  | 9:30am   | 8:15am  | 7:00am  | 6:30am  |
  | VIDEO    | IMAGE    | TEXT    | LINK    | TEXT     |
  +----------+----------+---------+---------+---------+
  Clustered by created_at DESC -- newest first
  One partition read = all of a user's recent posts
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Storage | `Map<String, Post>` + `Map<String, List<Post>>` | Cassandra cluster, RF=3 |
| Write | `map.put(postId, post)` | `INSERT INTO posts_by_user ...` |
| Read by user | `map.get(userId).stream().sorted(...)` | `SELECT ... WHERE user_id = ? LIMIT 20` |
| Deletion | `post.setDeleted(true)` | `UPDATE ... SET deleted = true` |
| TTL | None (in-memory) | `USING TTL 7776000` (90 days) |

### Interview Talking Point

> "Posts are stored in Cassandra partitioned by userId, clustered by
> createdAt DESC. One partition read gives us all recent posts by a user --
> perfect for fan-out-on-read pull. ContentType stored per post for
> ranking weight. TTL auto-expires old posts after 90 days."

---

## 4. PostgreSQL / MySQL -- Social Graph and User Profiles

Relational storage for user profiles and the durable backing store for the
social graph (Redis Sets are the primary read/write path, PostgreSQL is
the durable fallback).

### Schema

```sql
-- User profiles
CREATE TABLE users (
    user_id         VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    email           VARCHAR(255) UNIQUE NOT NULL,
    bio             TEXT,
    follower_count  INTEGER DEFAULT 0,
    following_count INTEGER DEFAULT 0,
    post_count      INTEGER DEFAULT 0,
    user_type       VARCHAR(20) DEFAULT 'NORMAL',
    joined_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_type ON users(user_type);

-- Social graph (durable backing store)
CREATE TABLE follows (
    follower_id     VARCHAR(36) NOT NULL,
    followee_id     VARCHAR(36) NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (follower_id, followee_id)
);

CREATE INDEX idx_follows_followee ON follows(followee_id);

-- Friend-of-friend query (LinkedIn-style)
-- "People you may know" = friends of your friends who you don't follow
SELECT DISTINCT f2.followee_id
FROM follows f1
JOIN follows f2 ON f1.followee_id = f2.follower_id
WHERE f1.follower_id = ?        -- your user ID
  AND f2.followee_id != ?       -- exclude yourself
  AND f2.followee_id NOT IN (   -- exclude people you already follow
      SELECT followee_id FROM follows WHERE follower_id = ?
  )
LIMIT 20;
```

### Why PostgreSQL for Social Graph (vs. Redis Only)?

| Concern | Redis Sets | PostgreSQL |
|---------|-----------|------------|
| Speed | O(1) SISMEMBER, O(N) SMEMBERS | O(log N) index scan |
| Durability | AOF + RDB, can lose recent writes | WAL, fully durable |
| Complex queries | No JOINs, no friend-of-friend | Native JOINs, CTEs |
| Backup | RDB snapshots | pg_dump, WAL archiving |
| Recovery | Rebuild from PostgreSQL | Source of truth |

**Architecture:** Redis Sets for hot-path reads/writes (fan-out, feed generation).
PostgreSQL as durable backing store (recovery, complex queries, analytics).

### Friend-of-Friend Queries (LinkedIn Feature)

```
LinkedIn's "People You May Know" requires traversing the social graph:

  Your connections -> their connections -> filter already-known

This is a JOIN operation -- not possible with Redis Sets alone.
PostgreSQL handles it efficiently with the follows table + index.

In production: pre-computed in a batch job, results cached in Redis.
Not computed in real-time per request.
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| User storage | `Map<String, User>` | PostgreSQL users table |
| Follow storage | `Map<String, Set<String>>` followers + following | Redis Sets (hot) + PostgreSQL (durable) |
| Friend-of-friend | Not implemented | PostgreSQL JOIN or graph DB |
| Profile cache | Direct map lookup | L1 (Caffeine) + L2 (Redis) + L3 (PostgreSQL) |

### Interview Talking Point

> "User profiles live in PostgreSQL -- small dataset, needs relational queries
> like friend-of-friend. Social graph hot path is Redis Sets for O(1) operations.
> PostgreSQL is the durable backing store for recovery and complex queries."

---

## 5. Elasticsearch -- Post Search

Full-text search on post content, hashtag filtering, user search.

### Index Mapping

```json
{
  "mappings": {
    "properties": {
      "postId":       { "type": "keyword" },
      "authorId":     { "type": "keyword" },
      "authorName":   { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
      "content":      { "type": "text", "analyzer": "standard" },
      "contentType":  { "type": "keyword" },
      "hashtags":     { "type": "keyword" },
      "createdAt":    { "type": "date" },
      "likeCount":    { "type": "integer" },
      "commentCount": { "type": "integer" },
      "shareCount":   { "type": "integer" }
    }
  }
}
```

### Key Queries

| Query | Elasticsearch Query | Use Case |
|-------|-------------------|----------|
| Text search | `match` on content | "Search posts" |
| Hashtag filter | `term` on hashtags | "#java posts" |
| User's posts | `term` on authorId + sort by createdAt | Profile page |
| Content type filter | `term` on contentType | "Show only videos" |
| Trending content | `terms` on hashtags + date range + sort by engagement | Trending detail |
| Search with ranking | `function_score` with recency boost + engagement boost | Relevance + freshness |

### Near-Real-Time Indexing

```
Kafka Consumer Group: "search-indexer" (8 instances)
     |
     v
  Batch posts (100 at a time, or every 1 second)
     |
     v
  Elasticsearch Bulk API
     |
  Index refresh interval: 1 second (default)
  -> Posts searchable within ~2 seconds of publishing
```

### Search Ranking (function_score)

```json
{
  "query": {
    "function_score": {
      "query": {
        "match": { "content": "java system design" }
      },
      "functions": [
        {
          "gauss": {
            "createdAt": {
              "origin": "now",
              "scale": "24h",
              "decay": 0.5
            }
          }
        },
        {
          "field_value_factor": {
            "field": "likeCount",
            "modifier": "log1p",
            "factor": 0.1
          }
        }
      ],
      "score_mode": "multiply",
      "boost_mode": "multiply"
    }
  }
}
```

### Interview Talking Point

> "Elasticsearch handles full-text search on post content and keyword
> filtering on hashtags. Indexed via Kafka consumer group with bulk API.
> Near-real-time -- posts searchable within 2 seconds. Search ranking
> uses function_score with recency decay and engagement boost."

---

## 6. ML Ranking -- Feature Extraction and Model Serving

### Why ML for Feed Ranking?

```
Rule-based ranking (our Java implementation):
  score = affinity * recency * engagement * contentTypeWeight
  - Simple, explainable, easy to implement
  - But: cannot capture complex interactions between features
  - Cannot personalize beyond basic affinity

ML-based ranking (production):
  score = model.predict(feature_vector)
  - Captures nonlinear feature interactions
  - Personalizes based on hundreds of user/author/content signals
  - Continuously improves with new training data
```

### Feature Extraction Pipeline

```
  Post enters ranking pipeline
       |
       v
  +-----------------------------+
  | Feature Extraction          |
  +-----------------------------+
  | User features:              |
  |   - age, location, device   |
  |   - past engagement history |
  |   - session duration        |
  |   - time of day             |
  |                             |
  | Author features:            |
  |   - follower count          |
  |   - avg engagement rate     |
  |   - post frequency          |
  |   - content category        |
  |                             |
  | Post features:              |
  |   - content type (weight)   |
  |   - age (recency)           |
  |   - engagement counts       |
  |   - hashtag popularity      |
  |   - media presence          |
  |                             |
  | Interaction features:       |
  |   - affinity (user-author)  |
  |   - last interaction time   |
  |   - interaction type (like, |
  |     comment, share, click)  |
  |   - mutual connections      |
  +-----------------------------+
       |
       v
  Feature vector (50-200 dimensions)
       |
       v
  Model prediction -> score
```

### Model Architecture

```
  Two-stage ranking (industry standard):

  Stage 1: Candidate Generation (coarse)
  +----------------------------------------+
  | Input: 5000 candidate posts            |
  | Model: lightweight (logistic regression |
  |        or small neural net)            |
  | Output: top 500 candidates             |
  | Latency budget: < 10ms                 |
  +----------------------------------------+
       |
       v
  Stage 2: Fine Ranking (precise)
  +----------------------------------------+
  | Input: 500 candidates                  |
  | Model: deep neural network (DNN)       |
  |   - Wide & Deep or DLRM architecture   |
  |   - Trained on click/engagement data   |
  | Output: ranked list with scores        |
  | Latency budget: < 50ms                 |
  +----------------------------------------+
       |
       v
  Stage 3: Business Logic Re-ranking
  +----------------------------------------+
  | Diversity: no more than 3 posts from   |
  |   the same author in top 20            |
  | Freshness: boost posts < 1 hour old    |
  | Ads: interleave sponsored content      |
  | Safety: filter flagged content         |
  +----------------------------------------+
       |
       v
  Final ranked feed (20 items per page)
```

### Model Serving (TensorFlow Serving / Triton)

```
  FeedService
       |
       v
  gRPC call to TF Serving
  +------------------------------------+
  | Model name: "feed_ranker_v3"       |
  | Input: batch of feature vectors    |
  |   [user_features + post_features]  |
  | Output: batch of scores            |
  |   [0.95, 0.87, 0.72, 0.68, ...]   |
  | Latency: < 50ms for 500 items     |
  +------------------------------------+
       |
       v
  Sort by score descending
       |
       v
  Apply business logic re-ranking
       |
       v
  Return top 20
```

### How Our Java Code Maps to Production

```
Our code:
  AlgorithmicRankingStrategy.rank(items, userId)
    score = affinity * recency * engagement * contentTypeWeight

Production:
  MLRankingStrategy.rank(items, userId)
    featureVectors = featureExtractor.extract(items, userId)
    scores = tfServingClient.predict("feed_ranker_v3", featureVectors)
    // Same interface (RankingStrategy), different implementation
```

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Ranking strategy | AlgorithmicRankingStrategy (4 factors) | MLRankingStrategy (200+ features) |
| Affinity | Hardcoded or simple counter | ML model trained on interaction history |
| Model | Hand-coded formula | DNN served via TF Serving |
| Training data | None | Billions of engagement events |
| A/B testing | Swap strategy object | ML experiment framework (different model versions) |
| Latency | < 1ms (in-memory computation) | < 50ms (network call to model server) |

### Interview Talking Point

> "In production, AlgorithmicRankingStrategy is replaced by an ML model
> served via TensorFlow Serving. Two-stage ranking: coarse candidate
> generation (500 from 5000) then fine ranking (DNN with 200+ features).
> Same RankingStrategy interface -- the Strategy pattern makes this swap
> transparent to FeedService."

---

## 7. WebSocket / SSE -- Real-Time Push

### Why Real-Time Push?

```
Without push:
  User sees stale feed -> pulls to refresh -> sees new posts
  Delay: user-initiated (could be minutes)

With push:
  New post published -> server pushes notification to connected clients
  Client: shows "3 new posts" banner or auto-prepends to feed
  Delay: < 2 seconds
```

### WebSocket vs Server-Sent Events (SSE)

| Feature | WebSocket | SSE |
|---------|-----------|-----|
| Direction | Bidirectional | Server -> Client only |
| Protocol | ws:// or wss:// | HTTP (text/event-stream) |
| Reconnection | Manual | Built-in auto-reconnect |
| Binary data | Yes | No (text only) |
| Proxy support | Sometimes problematic | Works with all proxies |
| Browser support | All modern browsers | All modern browsers |
| Best for | Chat, interactive | Feed updates, notifications |

**Recommendation:** SSE for feed updates (server -> client only), WebSocket
for interactive features (chat, typing indicators).

### Architecture

```
  User opens feed (browser / mobile)
       |
       v
  SSE connection established: GET /feed/stream?userId=user-42
       |
       v
  Connection Manager (server-side)
  +------------------------------------+
  | Map<String, SseEmitter> connections|
  | "user-42" -> SseEmitter            |
  +------------------------------------+
       ^
       |
  Kafka Consumer: "realtime-push" group
       |
       v
  When post.published event arrives for a follower:
    1. Lookup SseEmitter for followerId
    2. emitter.send(SseEvent.builder()
         .name("new-post")
         .data(postId)
         .build())
       |
       v
  Client receives SSE event
       |
       v
  Client shows "1 new post" banner
  (or auto-prepends post to feed on tap)
```

### Scaling WebSocket/SSE Connections

```
Challenge: 50M concurrent connections

Single server: ~50K connections max (file descriptor limit)
50M / 50K = 1000 servers needed

Connection routing:
  1. User connects to any server via load balancer (sticky sessions)
  2. Each server maintains local connection map
  3. When a push event arrives, how to find which server has the connection?

Solution: Redis Pub/Sub for cross-server broadcast
  +--------+     +--------+     +--------+
  |Server 1|     |Server 2|     |Server 3|
  | 50K    |     | 50K    |     | 50K    |
  |connects|     |connects|     |connects|
  +--------+     +--------+     +--------+
       |              |              |
       v              v              v
  +------------------------------------+
  | Redis Pub/Sub                      |
  | Channel: push:{userId}            |
  +------------------------------------+
       ^
       |
  Fan-out worker publishes to Redis channel
  -> Only the server holding that user's connection receives it
  -> That server pushes via SSE/WebSocket to the client
```

### Notification Types

| Event | Push Payload | Client Action |
|-------|-------------|---------------|
| New post from followed user | `{"type":"new-post","postId":"p-123","authorName":"Alice"}` | Show "X new posts" banner |
| Post liked | `{"type":"post-liked","postId":"p-123","likerName":"Bob"}` | Update like count in UI |
| New comment | `{"type":"new-comment","postId":"p-123","commenterName":"Carol"}` | Show notification badge |
| New follower | `{"type":"new-follower","followerName":"Dave"}` | Show notification |

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Push mechanism | Console output / print statements | SSE + Redis Pub/Sub |
| Connection management | Not implemented | Connection Manager + sticky LB |
| Scaling | Single JVM | 1000+ SSE servers, Redis Pub/Sub |
| Client handling | Not implemented | "X new posts" banner, auto-refresh |

### Interview Talking Point

> "Real-time push uses SSE (Server-Sent Events) for feed updates.
> When a post is published, a Kafka consumer looks up connected followers
> and pushes via SSE. For cross-server routing, Redis Pub/Sub broadcasts
> to the correct server. Clients show a 'new posts' banner on receive."

---

## 8. Redis Hash -- Engagement Counts and Affinity

### Engagement Counts

```
Key:    engagement:{postId}
Type:   Hash
Fields: likes, comments, shares

Example:
  HSET engagement:post-123 likes 42 comments 7 shares 3
  HINCRBY engagement:post-123 likes 1     -> 43
  HGETALL engagement:post-123             -> {likes: 43, comments: 7, shares: 3}
```

### Affinity Scores

```
Key:    affinity:{userId}:{authorId}
Type:   Hash
Fields: like_count, comment_count, share_count, click_count, last_interaction

Example:
  HSET affinity:user-42:user-7 like_count 15 comment_count 3 last_interaction 1713446400000
  
  Affinity score = (like_count * 1.0 + comment_count * 2.0 + share_count * 3.0
                    + click_count * 0.5) * recency_factor(last_interaction)
```

### Why Redis Hash (Not Separate Keys)?

```
Separate keys:
  GET engagement:post-123:likes      -> 1 network call
  GET engagement:post-123:comments   -> 1 network call
  GET engagement:post-123:shares     -> 1 network call
  Total: 3 network calls per post

Hash:
  HGETALL engagement:post-123        -> 1 network call
  Total: 1 network call per post (3x fewer round trips)

For a feed of 50 posts:
  Separate keys: 150 network calls
  Hash: 50 network calls (pipeline: 1 round trip with 50 HGETALL)
```

### Interview Talking Point

> "Engagement counts use Redis Hash -- one HGETALL per post instead of
> three separate GETs. Affinity scores stored per user-author pair, used
> by AlgorithmicRankingStrategy for personalization. HINCRBY for atomic
> counter increments."

---

## 9. S3 / CDN -- Media Storage

### Upload Flow

```
  Client                    API Server              S3
    |                           |                    |
    |-- POST /media/upload ---->|                    |
    |                           |-- Generate pre-    |
    |                           |   signed URL ----->|
    |<-- Pre-signed URL --------|                    |
    |                           |                    |
    |-- PUT directly to S3 ---------------------------->|
    |                           |                    |
    |-- POST /posts (with      |                    |
    |   mediaUrls: [s3-key]) ->|                    |
```

### CDN Layer

```
  User requests image/video in post
       |
       v
  CDN Edge (CloudFront / Fastly)
       |
  Cache HIT?  ----YES----> Return media (< 50ms)
       |
      NO
       |
       v
  Fetch from S3 origin -> Cache at edge -> Return media
```

### Content-Type-Specific Storage

| Content Type | Storage | Processing |
|-------------|---------|------------|
| TEXT | Cassandra only | None |
| IMAGE | S3 + CDN | Lambda: generate thumbnails (150px, 300px, 600px) |
| VIDEO | S3 + CDN | MediaConvert: transcode to multiple resolutions (360p, 720p, 1080p) |
| LINK | Cassandra (URL) + S3 (preview image) | Lambda: unfurl link, generate preview |
| POLL | Cassandra only | None (poll options stored as JSON) |

### Interview Talking Point

> "Media uploads use pre-signed S3 URLs for direct client upload. CDN caches
> hot media at the edge. Video transcoded to multiple resolutions via
> MediaConvert. ContentType enum tracks type for both storage and ranking."

---

## 10. Observability -- Key Metrics

### Dashboard Metrics

| Metric | Source | Alert Threshold |
|--------|--------|----------------|
| Fan-out lag | Kafka consumer lag | > 60s behind latest offset |
| Feed generation p99 | FeedService timer | > 500ms |
| Timeline cache hit ratio | Redis HIT / (HIT + MISS) | < 90% |
| Post publish p99 | PostService timer | > 200ms |
| Social graph op p99 | FollowRepository timer | > 10ms |
| Cassandra read latency p99 | Client-side timer | > 50ms |
| CDN cache hit ratio | CloudFront metrics | < 95% |
| SSE connection count | Connection Manager | > 80% of capacity |
| ML ranking p99 | TF Serving timer | > 50ms |
| Engagement counter lag | Kafka consumer lag for engagement-updater | > 30s |

### Distributed Tracing

```
  Request: GET /feed?userId=user-42&limit=20&cursor=abc
       |
       +-- [Span 1] FeedService.getFeed          18ms total
       |     |
       |     +-- [Span 2] Redis: ZREVRANGEBYSCORE    2ms
       |     |   timeline:user-42
       |     |
       |     +-- [Span 3] Cassandra: batch read       4ms
       |     |   (hydrate post content)
       |     |
       |     +-- [Span 4] Redis: SMEMBERS              1ms
       |     |   following:user-42
       |     |
       |     +-- [Span 5] Cassandra: multi-read        3ms
       |     |   (pull celebrity posts)
       |     |
       |     +-- [Span 6] Redis: pipeline HGETALL      2ms
       |     |   (engagement counts for ranking)
       |     |
       |     +-- [Span 7] RankingStrategy.rank          4ms
       |     |   (AlgorithmicRanking or ML model)
       |     |
       |     +-- [Span 8] Cursor pagination             1ms
       |           (apply cursor, build next cursor)
```

### Interview Talking Point

> "Fan-out lag is the critical metric -- it measures time from post publish
> to the last follower's timeline update. For normal users it is under 1 second.
> Feed generation p99 should be under 200ms with cache hits. ML ranking
> adds ~50ms in production but improves engagement significantly."

---

## Technology Decision Matrix

| Requirement | Technology | Alternative Considered | Why This Choice |
|-------------|-----------|----------------------|-----------------|
| Timeline cache | Redis Sorted Set | Memcached | Need sorted range queries, cursor pagination |
| Fan-out pipeline | Kafka | SQS/SNS | Need ordering, replay, multi-consumer groups |
| Post storage | Cassandra/DynamoDB | PostgreSQL | Write-heavy, wide row pattern, TTL |
| Social graph (hot) | Redis Sets | Neo4j | Simple follow/follower, O(1) checks |
| Social graph (durable) | PostgreSQL | Neo4j | Friend-of-friend JOINs, backup |
| Search | Elasticsearch | Solr | Better ecosystem, function_score, near-real-time |
| Media | S3 + CDN | GCS | Industry standard, pre-signed URLs, Lambda triggers |
| User profiles | PostgreSQL | MySQL | Better JSON support, array types |
| Engagement counts | Redis Hash | Cassandra counters | Sub-millisecond HINCRBY, HGETALL pipeline |
| Affinity scores | Redis Hash | PostgreSQL | Read-heavy, sub-millisecond per lookup |
| ML ranking | TF Serving / Triton | Custom gRPC server | Optimized model serving, GPU support |
| Real-time push | SSE + Redis Pub/Sub | WebSocket | SSE auto-reconnects, simpler for server->client |
| Async messaging | Kafka | RabbitMQ | Higher throughput, log-based replay, consumer groups |
