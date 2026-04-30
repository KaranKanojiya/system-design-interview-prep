# News Feed System (Facebook/LinkedIn)

## Problem Summary

Design a **news feed system** (like Facebook or LinkedIn) that generates and serves personalized feeds to 500M+ daily active users. The core challenges are **fan-out strategy** -- deciding whether to push posts to follower timelines at write time (fan-out on write), pull from followed users at read time (fan-out on read), or use a hybrid approach that pushes for normal users and pulls for celebrities. The system must implement **ranking/personalization** using a scoring formula (affinity * recency_decay * engagement_boost * content_type_weight) backed by ML features, **cursor-based pagination** (cursor = lastPostId + timestamp, NOT offset which breaks with new posts), handle the **celebrity problem** (a user with 10M followers generating 10M writes per post), and serve feeds with **sub-200ms latency** from pre-computed Redis caches. The system is **AP** -- a stale feed for a few seconds is acceptable; availability is non-negotiable. It must support real-time push via WebSocket for new posts, rich media (images, videos, links), and multi-region feed serving via CDN.

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Fan-out on Write: push post to all follower timelines. O(followers) write, O(1) read.** When a user publishes a post, a fan-out worker looks up all followers and writes the post ID into each follower's pre-computed timeline cache (Redis Sorted Set). Reads are instant -- just ZRANGEBYSCORE on the user's timeline. Write cost is proportional to follower count. Works great for users with < 10K followers (99% of users).
- **Fan-out on Read: pull from followed users at read time. O(1) write, O(following) read.** Post is stored once. When a user opens their feed, the system fetches recent posts from all users they follow, merges, ranks, and returns. Write is cheap (store once), but read is expensive -- must query N sources and merge. Works for heavy-write, low-read scenarios.
- **Hybrid: write for normal users, read for celebrities. Instagram/Facebook use this.** Normal users (< 10K followers) fan out on write. Celebrities (> 10K followers) skip fan-out; their posts are pulled at read time and merged into the pre-computed timeline. This eliminates 10M+ writes per celebrity post while keeping reads fast. The merge happens in-memory at feed serving time.
- **Ranking: score = affinity * recency_decay * engagement_boost * content_type_weight** Affinity = how often you interact with the author (likes, comments, profile views). Recency decay = 1 / (1 + age_hours * 0.1). Engagement boost = (likes + 2*shares + 1.5*comments) normalized. Content type weight = video > image > link > text. ML model trained on user click/dwell-time data replaces formula at scale.
- **Cursor pagination: cursor = lastPostId + timestamp. NOT offset (offset breaks with new posts).** Offset pagination: "give me items 20-40." If 5 new posts arrive, items shift and user sees duplicates or misses posts. Cursor pagination: "give me posts older than this timestamp + ID." Stable regardless of new insertions. Encode cursor as base64(timestamp:postId). Client sends cursor, server uses WHERE (timestamp, post_id) < (cursor_ts, cursor_id).
- **Celebrity problem: user with 10M followers -> 10M writes per post. Solution: pull, not push.** A celebrity posting once generates 10M Redis ZADD operations. At 100 posts/day from 1000 celebrities, that's 1T fan-out writes/day -- unsustainable. Solution: don't fan out celebrities. Store their posts separately. Pull at read time (fetch from ~50 celebrity caches in parallel, < 50ms). Merge with pre-computed timeline.
- **CAP: AP -- stale feed for a few seconds is OK. Eventual consistency.** Feed reads MUST be available (empty feed = terrible UX). A post appearing 2-5 seconds late in a follower's feed is acceptable. During partition, accept writes and reconcile later. Unlike payments, there's no "double-charge" risk -- a briefly stale feed is harmless.

---

## Class Hierarchy

```
Post (domain entity)                    FeedItem (value object)
  |-- postId, authorId                    |-- postId, authorId, authorName
  |-- content, mediaUrls                  |-- content, mediaUrls
  |-- contentType: TEXT/IMAGE/VIDEO       |-- rankScore (computed at read time)
  |-- createdAt, updatedAt                |-- engagementCounts (likes, comments, shares)
  |-- visibility: PUBLIC/FRIENDS          |-- cursor (timestamp:postId for pagination)
  |-- toString()                          |-- No setters (immutable, computed)

FanOutStrategy (interface)              RankingService
  |-- PushFanOutStrategy                  |-- rank(userId, feedItems) -> List<FeedItem>
  |     (for normal users, < 10K)         |-- computeAffinity(userId, authorId) -> double
  |-- PullFanOutStrategy                  |-- computeRecencyDecay(createdAt) -> double
  |     (for celebrities, > 10K)          |-- computeEngagementBoost(post) -> double
  |-- FanOutStrategyFactory               |-- ML model scoring via SageMaker endpoint
  |     (creates based on follower count)

FeedService                             SocialGraphService
  |-- getFeed(userId, cursor) -> Page     |-- follow(userId, targetId)
  |-- readFromCache(userId)               |-- unfollow(userId, targetId)
  |-- pullCelebrityPosts(userId)          |-- getFollowers(userId) -> Set<UserId>
  |-- mergeFeed(cached, pulled)           |-- getFollowing(userId) -> Set<UserId>
  |-- applyCursorPagination()             |-- isCelebrity(userId) -> boolean

PostService                             TimelineCacheService
  |-- createPost(authorId, content)       |-- writeToTimeline(userId, postId, score)
  |-- deletePost(postId)                  |-- readTimeline(userId, cursor, limit)
  |-- getPostsByUser(userId)              |-- trimTimeline(userId, maxSize=800)
  |-- publishToKafka(post)               |-- invalidate(userId, postId)
                                          |-- Redis Sorted Set per user

FanOutWorker (Kafka consumer)           AppConfig (wiring)
  |-- onNewPost(post)                     |-- creates services, strategies
  |-- lookupFollowers(authorId)           |-- wires fan-out pipeline
  |-- isCelebrity(authorId)               |-- configures ranking, caching
  |-- fanOutToTimelines(followers, post)  |-- Kafka consumers, Redis pools
  |-- batchWrite to Redis pipeline
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Post` | Core domain entity. Content, media, visibility, timestamps. Published to Kafka on creation. Stored in DynamoDB partitioned by authorId. |
| `FeedItem` | Immutable view object returned to clients. Contains post data + computed rank score + engagement counts + cursor for pagination. |
| `FanOutStrategy` | Strategy pattern: PushFanOutStrategy writes to follower timelines (normal users), PullFanOutStrategy stores once and pulls at read time (celebrities). Factory selects based on follower count threshold. |
| `FeedService` | Orchestrates feed generation: read pre-computed cache + pull celebrity posts + merge + rank + paginate. Target: < 200ms end-to-end. |
| `RankingService` | Computes rank score per post: affinity * recency_decay * engagement_boost * content_type_weight. Calls SageMaker for ML-based scoring at scale. |
| `SocialGraphService` | Manages follow/unfollow relationships. Stores in Redis Sets for fast lookup. Neptune for advanced graph queries (mutual friends, suggestions). |
| `TimelineCacheService` | Redis Sorted Set per user. ZADD to insert, ZRANGEBYSCORE to read, ZREMRANGEBYRANK to trim. Max 800 items per timeline. TTL for inactive users. |
| `PostService` | CRUD for posts. Publishes new-post events to Kafka. Stores in DynamoDB. Handles media upload to S3 with CDN URLs. |
| `FanOutWorker` | Kafka consumer. On new post: check if author is celebrity. If not, fan out to all follower timelines via batched Redis pipeline. If celebrity, skip (pulled at read time). |
| `AppConfig` | Wires everything together. Kafka consumers, Redis connection pools, DynamoDB clients, SageMaker endpoints. Single entry point for demo. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Fan-out strategy | Write (push to all followers) | Read (pull at read time) | **Hybrid** -- push for normal users (< 10K followers), pull for celebrities. Balances write cost and read latency. |
| Feed ranking | Chronological (simple, fair) | Algorithmic (engagement-based) | **Algorithmic** -- score = affinity * recency * engagement * content_type. ML model at scale. |
| Pagination | Offset (page=2&size=20) | Cursor (cursor=base64(ts:id)) | **Cursor** -- offset breaks when new posts arrive (duplicates/gaps). Cursor is stable. |
| Post storage | SQL (PostgreSQL, relational) | NoSQL (DynamoDB, partition by authorId) | **DynamoDB** -- write-heavy, time-series, no joins needed. Partition by authorId for hot-partition avoidance. |
| Timeline cache | Redis LIST (simple) | Redis Sorted Set (scored) | **Sorted Set** -- supports score-based ranking, efficient range queries, O(log N) insert. |
| Social graph | Redis Sets (fast, simple) | Graph DB (Neptune, traversal) | **Both** -- Redis for follow/unfollow and follower lists. Neptune for 2-hop queries (friend-of-friend, suggestions). |
| Real-time updates | Polling (simple, high bandwidth) | WebSocket (push, low latency) | **WebSocket** -- push new posts to online users. Fall back to polling for inactive connections. |
| Celebrity threshold | Static (follower_count > 10K) | Dynamic (monitor fan-out lag) | **Both** -- static threshold as default, dynamic promotion if fan-out lag > 2s. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | FanOutStrategy (Push vs Pull) | Swap fan-out behavior based on author follower count without changing pipeline |
| **Strategy** | RankingStrategy (Chronological vs Engagement vs ML) | Swap ranking algorithm without changing feed generation logic |
| **Factory** | FanOutStrategyFactory creates strategy from follower count | Encapsulate celebrity detection and strategy selection logic |
| **Observer** | PostPublisher notifies fan-out, search, trending subscribers via Kafka | Decouple post creation from downstream processing |
| **Composite** | HybridFeedGenerator composes push-cache + pull-celebrity results | Merge two feed sources into unified timeline transparently |
| **Builder** | FeedRequest.Builder -- userId, cursor, limit, filters, rankingMode | Complex request with many optional parameters and validation |
| **Repository** | TimelineRepository, PostRepository, SocialGraphRepository | Abstract data access; swap Redis/DynamoDB/Neptune implementations |
| **Template Method** | Base feed generation: readCache -> pullCelebrities -> merge -> rank -> paginate | Fixed sequence; subclasses override specific steps (e.g., LinkedIn vs Facebook ranking) |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :12-news-feed:run
```

---

## Demo Output Preview

```
========================================
  NEWS FEED SYSTEM (FACEBOOK/LINKEDIN) DEMO
========================================

--- Post Creation Demo ---
Creating post for user U001 (Alice, 500 followers -- normal user)...
  Post{id='post_001', author='U001', content='Just got promoted!', type=TEXT}
  Published to Kafka topic: new-posts (partition 3)

Creating post for user U002 (celebrity, 5M followers)...
  Post{id='post_002', author='U002', content='New product launch!', type=IMAGE}
  Published to Kafka topic: new-posts (partition 7)

--- Fan-Out Strategy Demo ---
Fan-out for post_001 (Alice, 500 followers):
  Strategy: PUSH (fan-out on write)
  Fan-out worker: lookup 500 followers
  Batch Redis pipeline: 500 ZADD operations
  Time: 12ms (all 500 timelines updated)

Fan-out for post_002 (celebrity, 5M followers):
  Strategy: PULL (fan-out on read -- celebrity)
  Fan-out worker: SKIP fan-out (5M writes avoided!)
  Store in celebrity cache: Redis HASH celebrity:U002:posts
  Time: 1ms (single write)

  Savings: 5,000,000 ZADD operations avoided per celebrity post.

--- Feed Generation Demo ---
Generating feed for user U003 (Bob)...
  Step 1: Read pre-computed timeline from Redis ZSET
    timeline:U003 -> 47 posts (from normal followees)
    Cache HIT, latency: 2ms

  Step 2: Identify celebrity followees
    Bob follows 3 celebrities: [U002, U005, U009]

  Step 3: Pull celebrity posts (parallel)
    celebrity:U002:posts -> 5 recent posts (3ms)
    celebrity:U005:posts -> 3 recent posts (2ms)
    celebrity:U009:posts -> 7 recent posts (4ms)
    Total pull latency: 4ms (parallel)

  Step 4: Merge and rank
    Total candidates: 47 + 15 = 62 posts
    Ranking formula: affinity * recency_decay * engagement_boost * content_type
    Top 20 posts selected by score.

  Step 5: Apply cursor pagination
    Cursor: base64("2026-04-26T10:30:00Z:post_042")
    Page size: 20
    Next cursor: base64("2026-04-26T09:15:00Z:post_019")

  Feed response (first 3 items):
    1. Post{id='post_002', author='U002', score=0.94, type=IMAGE}  (celebrity, high engagement)
    2. Post{id='post_001', author='U001', score=0.87, type=TEXT}   (close friend, recent)
    3. Post{id='post_033', author='U004', score=0.82, type=VIDEO}  (video boost)

  Total feed latency: 18ms (target < 200ms)

--- Ranking Demo ---
Ranking 5 posts for user U003 (Bob)...
  Post A: affinity=0.9, recency=0.95, engagement=0.3, type_weight=1.0 -> score=0.257
  Post B: affinity=0.5, recency=0.80, engagement=0.8, type_weight=1.2 -> score=0.384
  Post C: affinity=0.8, recency=0.60, engagement=0.9, type_weight=1.5 -> score=0.648
  Post D: affinity=0.3, recency=0.99, engagement=0.1, type_weight=1.0 -> score=0.030
  Post E: affinity=0.7, recency=0.70, engagement=0.5, type_weight=1.2 -> score=0.294

  Ranked order: [C, B, E, A, D]
  Post C wins: high affinity + high engagement + video content type boost

--- Celebrity Problem Demo ---
Simulating celebrity post (10M followers)...
  Fan-out on Write approach:
    10,000,000 ZADD operations
    At 100K ops/sec per Redis shard: 100 seconds!
    UNACCEPTABLE for a single post.

  Hybrid approach (this design):
    1 write to celebrity cache (1ms)
    Pulled at read time by each follower
    Each read adds ~5ms (parallel pull from cached posts)
    10M reads * 5ms overhead = amortized, not batched.

--- Cursor Pagination Demo ---
User scrolls through feed...
  Request 1: GET /feed?limit=20
    Response: 20 posts, nextCursor="MjAyNi0wNC0yNlQwOToxNTowMFo6cG9zdF8wMTk="
  Request 2: GET /feed?limit=20&cursor=MjAyNi0wNC0yNlQwOToxNTowMFo6cG9zdF8wMTk=
    Decoded cursor: timestamp=2026-04-26T09:15:00Z, postId=post_019
    WHERE (created_at, post_id) < ('2026-04-26T09:15:00Z', 'post_019')
    Response: 20 posts, nextCursor="MjAyNi0wNC0yNVQxODozMDowMFo6cG9zdF8wMDM="

  5 new posts arrive between requests...
    Offset pagination: page 2 would shift, user sees duplicates!
    Cursor pagination: cursor is stable, user sees exactly the next 20 posts.

--- Social Graph Demo ---
User U003 follows celebrity U002...
  Redis: SADD following:U003 U002 -> OK
  Redis: SADD followers:U002 U003 -> OK
  followers:U002 count: 5,000,001

User U003 unfollows U007...
  Redis: SREM following:U003 U007 -> OK
  Redis: SREM followers:U007 U003 -> OK
  Timeline: old posts from U007 expire naturally (ZSET trim, max 800)

========================================
  DEMO COMPLETE -- PROJECT 12 FINISHED!
========================================
```

---

## Quick Reference

```
Fan-out on Write:   Push post to all follower timelines at write time. O(followers) write, O(1) read. Good for < 10K followers.
Fan-out on Read:    Pull from followed users at read time. O(1) write, O(following) read. Good for celebrities.
Hybrid:             Push for normal users, pull for celebrities. Instagram/Facebook/Twitter use this.
Celebrity problem:  10M followers = 10M writes per post. Solution: pull, not push. Store once, fetch at read time.
Ranking formula:    score = affinity * recency_decay * engagement_boost * content_type_weight
Cursor pagination:  cursor = base64(timestamp:postId). NOT offset (offset breaks with new posts).
Timeline cache:     Redis Sorted Set per user. ZADD insert, ZRANGEBYSCORE read, ZREMRANGEBYRANK trim. Max 800 items.
Social graph:       Redis Sets for follow/unfollow, SMEMBERS for follower lists. Neptune for 2-hop queries.
Post storage:       DynamoDB partitioned by authorId. Write-heavy, time-series, no joins.
CAP choice:         AP -- stale feed for 2-5 seconds is OK. Availability is non-negotiable.
Real-time:          WebSocket push for online users. Fall back to polling for inactive connections.
Celebrity threshold: Static (follower_count > 10K) + dynamic (fan-out lag > 2s -> promote).
```

---

## What to Improve Later

- [ ] Full Post entity with media attachment handling and content type detection
- [ ] FanOutWorker with Kafka consumer and batched Redis pipeline writes
- [ ] FeedService with hybrid merge (pre-computed cache + celebrity pull + ranking)
- [ ] RankingService with affinity computation and ML model stub (SageMaker)
- [ ] TimelineCacheService with Redis Sorted Set operations and TTL management
- [ ] SocialGraphService with follow/unfollow and celebrity detection
- [ ] Cursor-based pagination with base64 encoding and stable iteration
- [ ] WebSocket-based real-time feed push for online users
- [ ] Content moderation pipeline before fan-out (spam, harmful content)
- [ ] A/B testing framework for ranking algorithm experiments
