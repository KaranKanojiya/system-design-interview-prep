# Technologies -- Social Media Feed System

> Production technology stack for a Twitter/X-like feed system. For each tech:
> why it fits, key operations, data model, and complexity analysis.

---

## Technology Map

```
  +-------------------+     +-------------------+     +-------------------+
  |   Client (App)    |---->|   API Gateway /   |---->|   Feed Service    |
  |                   |     |   Load Balancer   |     |   (Mediator)      |
  +-------------------+     +-------------------+     +-------------------+
                                                             |
                    +--------+--------+--------+--------+----+----+
                    |        |        |        |        |         |
                    v        v        v        v        v         v
              +--------+ +------+ +------+ +------+ +-----+ +--------+
              | Redis  | | Redis| | Redis| |Cassan| |Post | | Elastic|
              |Timeline| |Social| |Trend | |dra   | |greSQL| |search |
              |Cache   | |Graph | |Cache | |Tweets| |Users | |Search  |
              +--------+ +------+ +------+ +------+ +-----+ +--------+
                    ^
                    |
              +----------+
              |  Kafka   |
              | Fan-out  |
              | Pipeline |
              +----------+
                    ^
                    |
              +-----------+
              | S3 / CDN  |
              | Media     |
              +-----------+
```

---

## 1. Redis Sorted Set -- Timeline Cache

**THE key data structure.** Every user's home timeline is a Redis Sorted Set.

### Data Model

```
Key:    timeline:{userId}
Type:   Sorted Set (ZSET)
Member: tweetId (string)
Score:  timestamp (epoch millis) or engagement score

Example:
  ZADD timeline:user-42 1713446400000 "tweet-101"
  ZADD timeline:user-42 1713446500000 "tweet-102"
  ZADD timeline:user-42 1713446600000 "tweet-103"
```

### Key Operations

| Operation | Redis Command | Complexity | Use Case |
|-----------|--------------|------------|----------|
| Add tweet to timeline | `ZADD timeline:{userId} {score} {tweetId}` | O(log N) | Fan-out-on-write pushes here |
| Get feed (newest first) | `ZREVRANGEBYSCORE timeline:{userId} +inf -inf LIMIT 0 50` | O(log N + M) | Feed generation |
| Get feed (paginated) | `ZREVRANGEBYSCORE timeline:{userId} {maxScore} -inf LIMIT 0 50` | O(log N + M) | Cursor-based pagination |
| Remove tweet | `ZREM timeline:{userId} {tweetId}` | O(log N) | Tweet deletion |
| Evict oldest | `ZREMRANGEBYRANK timeline:{userId} 0 -{keepCount}` | O(log N + M) | Keep max 200 items |
| Timeline size | `ZCARD timeline:{userId}` | O(1) | Monitoring |

### Why Sorted Set (Not a List)?

| Feature | Sorted Set | List |
|---------|-----------|------|
| Insert by score | O(log N) -- auto-sorted | O(N) -- must find position |
| Range by score | O(log N + M) | Not supported |
| Dedup | Built-in (same member = update score) | Must check manually |
| Remove by value | O(log N) | O(N) scan |
| Pagination by score | Native ZRANGEBYSCORE | Not supported |

### Capacity Planning

```
Per user:
  200 items max x ~50 bytes per entry = ~10 KB per user
  100M active users x 10 KB = ~1 TB

Redis cluster:
  ~1 TB / 25 GB per shard = ~40 shards
  Shard key: userId (even distribution)
```

### Interview Talking Point

> "Each user's timeline is a Redis Sorted Set capped at 200 items. ZADD is
> O(log N) and idempotent -- same tweetId just updates the score. We get
> free dedup and efficient range queries for pagination."

---

## 2. Kafka -- Fan-Out Pipeline

The async backbone that distributes tweets to follower timelines.

### Architecture

```
  Tweet Published
       |
       v
  +-----------------------------+
  | Kafka Topic:                |
  | "tweet.published"           |
  | Partitions: 128             |
  | Partition Key: poster userId|
  +-----------------------------+
       |
       +---> Consumer Group: "fanout-workers" (32 instances)
       |     - Read message
       |     - Fetch followers from social graph
       |     - ZADD to each follower's timeline
       |
       +---> Consumer Group: "trending-updater" (4 instances)
       |     - Extract hashtags
       |     - ZINCRBY trending:{window} {hashtag} 1
       |
       +---> Consumer Group: "search-indexer" (8 instances)
       |     - Bulk index to Elasticsearch
       |
       +---> Consumer Group: "notification-sender" (8 instances)
             - Push notifications for close friends
```

### Why Kafka Over SQS?

| Feature | Kafka | SQS |
|---------|-------|-----|
| Ordering | Per-partition ordering by key | FIFO queues (limited throughput) |
| Consumer groups | Multiple independent groups on same topic | One queue per consumer |
| Replay | Seek to offset, replay events | Messages deleted after consumption |
| Throughput | Millions of msgs/sec per cluster | ~3K msgs/sec per FIFO queue |
| Fan-out to multiple consumers | Built-in (consumer groups) | Requires SNS + multiple queues |

### Partition Strategy

```
Partition key = poster's userId

Why:
  - All tweets by the same user go to the same partition
  - Preserves ordering of tweets from the same poster
  - Prevents two workers from fanning out the same user's tweet simultaneously

Not follower's userId:
  - One tweet goes to millions of followers -- can't partition by follower
  - Fan-out worker reads one message and writes to many timelines
```

### Message Schema

```json
{
  "eventType": "tweet.published",
  "tweetId": "tweet-123",
  "userId": "user-42",
  "content": "Hello world! #java",
  "hashtags": ["java"],
  "followerCount": 5000,
  "userType": "NORMAL",
  "timestamp": 1713446400000
}
```

### Delivery Guarantee

At-least-once. Consumer commits offset after processing. If crash before
commit, message is redelivered. Dedup by ZADD idempotency (same tweetId
in sorted set = no duplicate).

### Interview Talking Point

> "Fan-out goes through Kafka partitioned by poster userId. Independent
> consumer groups handle timeline writes, trending updates, search indexing,
> and notifications. At-least-once delivery, deduped by Redis ZADD idempotency."

---

## 3. Cassandra -- Tweet Storage

The durable store for all tweet content. Optimized for time-series writes.

### Data Model

```
CREATE TABLE tweets_by_user (
    user_id     TEXT,
    created_at  TIMESTAMP,
    tweet_id    TEXT,
    content     TEXT,
    media_urls  LIST<TEXT>,
    hashtags    SET<TEXT>,
    like_count  COUNTER,       -- or use a separate counter table
    retweet_count COUNTER,
    reply_count COUNTER,
    deleted     BOOLEAN,
    PRIMARY KEY ((user_id), created_at, tweet_id)
) WITH CLUSTERING ORDER BY (created_at DESC);
```

### Access Patterns

| Query | Cassandra Query | Partition |
|-------|----------------|-----------|
| Get user's recent tweets | `SELECT * FROM tweets_by_user WHERE user_id = ? LIMIT 20` | Single partition |
| Get specific tweet | `SELECT * FROM tweets_by_id WHERE tweet_id = ?` | Separate table |
| Fan-out-on-read (pull) | `SELECT * FROM tweets_by_user WHERE user_id = ? AND created_at > ? LIMIT 10` | Single partition |

### Why Cassandra?

| Requirement | Why Cassandra Fits |
|-------------|-------------------|
| High write throughput | Fan-out writes millions of tweets/day. Cassandra handles massive write load. |
| Time-series data | Tweets are append-only, queried by time range. Wide rows are natural. |
| Horizontal scaling | Linear scale-out by adding nodes. Consistent hashing. |
| No single point of failure | Masterless architecture, tunable replication (RF=3). |
| TTL for old data | `INSERT ... USING TTL 7776000` (90 days). Old tweets auto-expire. |

### Wide Row Pattern

```
Partition: user_id = "user-42"
  +----------+----------+---------+---------+---------+
  | tweet-5  | tweet-4  | tweet-3 | tweet-2 | tweet-1 |
  | 10:05am  | 9:30am   | 8:15am  | 7:00am  | 6:30am  |
  +----------+----------+---------+---------+---------+
  Clustered by created_at DESC -- newest first
  One partition read = all of a user's recent tweets
```

### Interview Talking Point

> "Tweets are stored in Cassandra partitioned by userId, clustered by
> createdAt DESC. One partition read gives us all recent tweets by a user --
> perfect for fan-out-on-read pull. Wide row pattern, TTL for old data."

---

## 4. Redis Sets -- Social Graph

Follow relationships stored as Redis Sets for O(1) membership checks.

### Data Model

```
Key: followers:{userId}     Value: Set of follower userIds
Key: following:{userId}     Value: Set of followed userIds

Example:
  SADD followers:user-42 "user-1" "user-2" "user-3"
  SADD following:user-1  "user-42" "user-99"
```

### Key Operations

| Operation | Redis Command | Complexity | Use Case |
|-----------|--------------|------------|----------|
| Follow | `SADD followers:{B} {A}` + `SADD following:{A} {B}` | O(1) each | Follow action |
| Unfollow | `SREM followers:{B} {A}` + `SREM following:{A} {B}` | O(1) each | Unfollow action |
| Am I following? | `SISMEMBER following:{A} {B}` | O(1) | UI "Follow" button state |
| Get all followers | `SMEMBERS followers:{userId}` | O(N) | Fan-out worker needs full list |
| Follower count | `SCARD followers:{userId}` | O(1) | Profile display |
| Mutual friends | `SINTER following:{A} following:{B}` | O(min(N,M)) | "Followers you know" |

### Dual-Write Pattern

Every follow/unfollow maintains **two** sets atomically:

```
User A follows User B:
  MULTI
    SADD followers:B A     -- B's follower list includes A
    SADD following:A B     -- A's following list includes B
  EXEC

User A unfollows User B:
  MULTI
    SREM followers:B A
    SREM following:A B
  EXEC
```

### Why Redis Sets (Not a Relational Table)?

| Feature | Redis Set | PostgreSQL |
|---------|-----------|-----------|
| SISMEMBER (is following?) | O(1) | O(log N) index scan |
| SMEMBERS (all followers) | O(N), in-memory | O(N), disk I/O |
| SCARD (count) | O(1) | COUNT(*) scan or cached |
| SINTER (mutual) | O(min(N,M)) | JOIN, potentially slow |
| Throughput | 100K+ ops/sec per node | Thousands |

### Interview Talking Point

> "Social graph lives in Redis Sets -- O(1) for SISMEMBER (am I following?),
> O(1) for SCARD (follower count). Fan-out workers call SMEMBERS to get the
> full follower list. Follow/unfollow uses MULTI for atomic dual writes."

---

## 5. Elasticsearch -- Tweet Search

Full-text search on tweet content, hashtag filtering, user search.

### Index Mapping

```json
{
  "mappings": {
    "properties": {
      "tweetId":    { "type": "keyword" },
      "userId":     { "type": "keyword" },
      "username":   { "type": "keyword" },
      "content":    { "type": "text", "analyzer": "standard" },
      "hashtags":   { "type": "keyword" },
      "createdAt":  { "type": "date" },
      "likeCount":  { "type": "integer" },
      "retweetCount": { "type": "integer" }
    }
  }
}
```

### Key Queries

| Query | Elasticsearch Query | Use Case |
|-------|-------------------|----------|
| Text search | `match` on content | "Search tweets" |
| Hashtag filter | `term` on hashtags | "#java tweets" |
| User's tweets | `term` on userId + sort by createdAt | Profile page |
| Trending content | `terms` on hashtags + date range + sort by score | Trending detail |

### Near-Real-Time Indexing

```
Kafka Consumer Group: "search-indexer"
     |
     v
  Batch tweets (100 at a time, or every 1 second)
     |
     v
  Elasticsearch Bulk API
     |
  Index refresh interval: 1 second (default)
  -> Tweets searchable within ~2 seconds of posting
```

### Interview Talking Point

> "Elasticsearch handles full-text search on tweet content and keyword
> filtering on hashtags. Indexed via Kafka consumer group with bulk API.
> Near-real-time -- tweets searchable within 2 seconds."

---

## 6. S3 / CDN -- Media Storage

Images, videos, and GIFs attached to tweets.

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
    |-- POST /tweet (with      |                    |
    |   mediaUrls: [s3-key]) ->|                    |
```

### CDN Layer

```
  User requests image in tweet
       |
       v
  CDN Edge (CloudFront / Fastly)
       |
  Cache HIT?  ----YES----> Return image (< 50ms)
       |
      NO
       |
       v
  Fetch from S3 origin -> Cache at edge -> Return image
```

### Key Design Points

| Concern | Solution |
|---------|----------|
| Upload security | Pre-signed S3 URLs (expire in 15 min) |
| Hot images (viral tweet) | CDN caches at edge -- one S3 read serves millions |
| Image processing | Lambda trigger on S3 upload -- generate thumbnails |
| Cost | S3 lifecycle rules -- move to Glacier after 90 days |
| URL in tweet | Store S3 key in Tweet.mediaUrls, CDN prefix at serving time |

### Interview Talking Point

> "Media uploads use pre-signed S3 URLs for direct client upload. CDN caches
> hot images at the edge. A viral tweet's image might be served from CDN cache
> millions of times without touching S3."

---

## 7. PostgreSQL -- User Profiles

Small, relational dataset. Users, account settings, verification status.

### Schema

```sql
CREATE TABLE users (
    user_id       VARCHAR(36) PRIMARY KEY,
    username      VARCHAR(50) UNIQUE NOT NULL,
    display_name  VARCHAR(100),
    bio           TEXT,
    follower_count  INTEGER DEFAULT 0,
    following_count INTEGER DEFAULT 0,
    user_type     VARCHAR(20) DEFAULT 'NORMAL',
    created_at    TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_type ON users(user_type);
```

### Why PostgreSQL (Not Cassandra)?

| Concern | PostgreSQL | Cassandra |
|---------|-----------|-----------|
| Dataset size | Small (millions of users, not billions of tweets) | Overkill |
| Query flexibility | Complex queries, JOINs, aggregations | Limited query patterns |
| ACID transactions | Native | Not supported |
| Schema changes | ALTER TABLE, migrations | Schema changes are painful |
| Admin tooling | Mature (pgAdmin, pg_stat) | Less mature |

### Interview Talking Point

> "User profiles live in PostgreSQL -- small dataset, needs relational queries,
> ACID for account changes. Cached in Redis with 5-minute TTL for read-heavy
> profile lookups."

---

## 8. Redis Sorted Set -- Trending Topics

Real-time trending hashtag tracking using atomic increments.

### Data Model

```
Key:    trending:{window}           (e.g., trending:2024-04-18-14)
Type:   Sorted Set
Member: hashtag (string)
Score:  count (number of tweets with this hashtag in the window)

Example:
  ZINCRBY trending:2024-04-18-14 1 "java"       -> score: 42
  ZINCRBY trending:2024-04-18-14 1 "systemdesign" -> score: 17
```

### Key Operations

| Operation | Redis Command | Complexity | Use Case |
|-----------|--------------|------------|----------|
| Increment hashtag | `ZINCRBY trending:{window} 1 {hashtag}` | O(log N) | Tweet published with hashtag |
| Top-K trending | `ZREVRANGEBYSCORE trending:{window} +inf -inf LIMIT 0 10` | O(log N + K) | Trending page |
| Get hashtag count | `ZSCORE trending:{window} {hashtag}` | O(1) | Hashtag detail page |
| Expire old windows | `EXPIRE trending:{window} 86400` | O(1) | Auto-cleanup |

### Time Window Strategy

```
  Sliding window approach:
  
  Current hour: trending:2024-04-18-14
  Previous hour: trending:2024-04-18-13
  
  "Trending now" = ZUNIONSTORE tmp 2 trending:2024-04-18-14 trending:2024-04-18-13
                                      WEIGHTS 2.0 1.0
                   (current hour weighted 2x)
  
  ZREVRANGE tmp 0 9 WITHSCORES -> Top 10 trending
```

### Scoring Formula (TrendingTopic)

```
From the codebase:
  TrendingTopic(String hashtag, long count, double score)

Score can incorporate:
  - Raw count (tweets with this hashtag)
  - Velocity (rate of change -- is it accelerating?)
  - Uniqueness (how many distinct users, not just tweet count)
  - Recency weighting (recent window weighted more)
```

### Interview Talking Point

> "Trending uses Redis Sorted Sets with ZINCRBY for atomic increment per
> hashtag. Separate keys per time window, ZUNIONSTORE with weights to
> combine windows. Top-K is a single ZREVRANGE. Refreshed every 1-5 minutes."

---

## 9. Observability -- Key Metrics

### Dashboard Metrics

| Metric | Source | Alert Threshold |
|--------|--------|----------------|
| Fan-out lag | Kafka consumer lag | > 60s behind latest offset |
| Feed generation p99 | FeedService timer | > 500ms |
| Timeline cache hit ratio | Redis HIT / (HIT + MISS) | < 90% |
| Trending refresh staleness | Time since last ZUNIONSTORE | > 5 minutes |
| Tweet publish p99 | TweetPublisher timer | > 200ms |
| Social graph op p99 | SocialGraphRepository timer | > 10ms |
| Cassandra read latency p99 | Client-side timer | > 50ms |
| CDN cache hit ratio | CloudFront metrics | < 95% |

### Fan-Out Lag (THE Critical Metric)

```
  Fan-out lag = time from tweet published to last follower's timeline updated

  For normal user (500 followers):
    Publish -> Kafka -> Fan-out worker -> 500 ZADD ops
    Expected lag: < 1 second

  For near-celebrity (9,999 followers, just under threshold):
    Publish -> Kafka -> Fan-out worker -> 9,999 ZADD ops
    Expected lag: 2-5 seconds

  For celebrity (if we accidentally push):
    Publish -> Kafka -> Fan-out worker -> 10M ZADD ops
    Expected lag: MINUTES (this is why we use fan-out-on-read!)
```

### Distributed Tracing

```
  Request: GET /feed?userId=user-42
       |
       +-- [Span 1] FeedService.getFeed          12ms total
       |     |
       |     +-- [Span 2] Redis: ZREVRANGE         2ms
       |     |   timeline:user-42
       |     |
       |     +-- [Span 3] Cassandra: batch read     4ms
       |     |   (hydrate tweet content)
       |     |
       |     +-- [Span 4] Redis: SMEMBERS           1ms
       |     |   following:user-42
       |     |
       |     +-- [Span 5] Cassandra: multi-read      3ms
       |     |   (pull celebrity tweets)
       |     |
       |     +-- [Span 6] EngagementRanker.rank      2ms
```

### Interview Talking Point

> "Fan-out lag is the critical metric -- it measures time from tweet publish
> to the last follower's timeline update. For normal users it is under 1 second.
> For celebrities we avoid the problem entirely with fan-out-on-read.
> Feed generation p99 should be under 200ms with cache hits."

---

## Technology Decision Matrix

| Requirement | Technology | Alternative Considered | Why This Choice |
|-------------|-----------|----------------------|-----------------|
| Timeline cache | Redis Sorted Set | Memcached | Need sorted range queries, not just key-value |
| Fan-out pipeline | Kafka | SQS/SNS | Need ordering, replay, multi-consumer groups |
| Tweet storage | Cassandra | DynamoDB | Wide row pattern, tunable consistency, no vendor lock |
| Social graph | Redis Set | Neo4j | Simple follow/follower, O(1) checks, no traversals needed |
| Search | Elasticsearch | Solr | Better ecosystem, near-real-time, Kibana for ops |
| Media | S3 + CDN | GCS | Industry standard, pre-signed URLs, Lambda triggers |
| User profiles | PostgreSQL | MySQL | Better JSON support, array types, more extensible |
| Trending | Redis Sorted Set | Spark Streaming | Real-time atomic increments, simpler for top-K |
| Async messaging | Kafka | RabbitMQ | Higher throughput, log-based (replay), consumer groups |
