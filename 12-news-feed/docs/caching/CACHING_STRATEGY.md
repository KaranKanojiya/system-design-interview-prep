# Caching Strategy -- News Feed System (Facebook/LinkedIn)

> Every cache layer in the system, from the core timeline cache to celebrity
> post handling, engagement count caching, and cache warming strategies.
> Interview-ready with Redis commands, eviction policies, invalidation
> strategies, and the full feed generation cache flow.
>
> **Differentiator from Project 05:** Deeper coverage of engagement count
> caching, affinity score caching, cache warming on login, cursor-aware
> cache reads, and content-type-specific cache policies.

---

## Cache Layer Overview

```
  GET /feed?userId=user-42&limit=20&cursor=abc123
       |
       v
  +-------------------+
  | User Profile Cache|  L1 (Caffeine) + L2 (Redis), 5-min TTL
  +-------------------+
       |
       v
  +-------------------+
  | Timeline Cache    |  Redis Sorted Set, max 1000 items   <-- THE CORE CACHE
  | (fan-out-on-write)|  Populated by fan-out workers
  +-------------------+
       |
       | cache miss or celebrity posts needed
       v
  +-------------------+
  | Celebrity List    |  Redis Set: celebrity followees for this user
  | Cache             |  Invalidated on follow/unfollow
  +-------------------+
       |
       v
  +-------------------+
  | Celebrity Post    |  Redis Sorted Set: recent posts per celebrity
  | Cache             |  One entry serves ALL followers (thundering herd fix)
  +-------------------+
       |
       v
  +-------------------+
  | Post Content      |  Redis String: hot/viral post details by postId
  | Cache             |  Key: post:{postId}, TTL: 24h
  +-------------------+
       |
       v
  +-------------------+
  | Engagement Counts |  Redis Hash: likes/comments/shares per post
  | Cache             |  Updated async via Kafka, approximate OK
  +-------------------+
       |
       v
  +-------------------+
  | Affinity Score    |  Redis Hash: user-author interaction history
  | Cache             |  Used by AlgorithmicRankingStrategy
  +-------------------+
```

---

## 1. Timeline Cache -- THE Core Cache

The user's pre-computed home timeline. This is the primary read path.

### Data Model

```
Key:    timeline:{userId}
Type:   Redis Sorted Set (ZSET)
Member: postId
Score:  timestamp (epoch millis) or engagement score
Max:    1000 items per user
```

### How It Gets Populated

```
Normal user (< 10K followers) publishes a post:
  |
  v
Kafka -> Fan-out worker
  |
  v
For each follower:
  ZADD timeline:{followerId} {timestamp} {postId}
  |
  v
If ZCARD timeline:{followerId} > 1000:
  ZREMRANGEBYRANK timeline:{followerId} 0 0    (remove lowest score / oldest)
```

### What Is NOT in This Cache

Celebrity posts are NOT pushed to follower timelines. They are pulled
at read time from the Celebrity Post Cache (see section 4).

```
@celebrity posts "Big announcement!"
  |
  v
Fan-out? NO -- 5M followers, skip push.
  |
  v
Post stored in:
  1. Cassandra (durable)
  2. Celebrity Post Cache (Redis, for read-time pull)
  |
  v
When follower opens feed:
  FeedService merges timeline cache + pulled celebrity posts
```

### Cache Miss = Cold Start

```
User opens app after 30 days of inactivity
  |
  v
timeline:{userId} does not exist (expired or evicted)
  |
  v
Cold start: build timeline from scratch
  |
  +---> Get following list: SMEMBERS following:{userId}
  +---> For each followed user:
  |       Query Cassandra: SELECT * FROM posts_by_user
  |         WHERE user_id = ? AND created_at > (now - 7 days)
  |         LIMIT 20
  +---> Merge all results
  +---> Rank by AlgorithmicRankingStrategy
  +---> Write top 1000 back to timeline:{userId}
  +---> Return first page (top 20)
  |
  Cold start latency: 500ms - 2s (depending on following count)
  Subsequent reads: < 50ms (cache hit)
```

### Eviction Policy

```
Max 1000 items per user.
After every ZADD:
  ZCARD timeline:{userId}
  If > 1000:
    ZREMRANGEBYRANK timeline:{userId} 0 (count - 1000 - 1)
    (Removes the lowest-scored items)

Why 1000 (vs 200 in Project 05)?
  - News feed has richer content types (video, polls) -- users scroll deeper
  - Algorithmic ranking reorders significantly -- need larger candidate pool
  - Cursor-based pagination supports deep scrolling
  - 1000 items x 50 bytes = ~50 KB per user (still tiny)
  - Beyond 1000: pull from Cassandra for deep-scroll (rare)
```

### Cursor-Aware Cache Reads

```
First page (no cursor):
  ZREVRANGEBYSCORE timeline:{userId} +inf -inf LIMIT 0 20
  -> Top 20 by score
  -> Build cursor from last item's score

Next page (cursor score = 85.7):
  ZREVRANGEBYSCORE timeline:{userId} (85.7 -inf LIMIT 0 20
  -> Next 20 items with score < 85.7
  -> "(" means exclusive -- no duplicates

Deep scroll (beyond cached 1000 items):
  If cursor returns empty from Redis:
    Fall back to Cassandra:
    SELECT * FROM posts_by_user WHERE ... AND created_at < {cursorTimestamp}
    -> Returns older posts not in cache
    -> Rare: most users never scroll past 200 items
```

### Interview Talking Point

> "The timeline cache is a Redis Sorted Set per user, capped at 1000 items.
> Fan-out-on-write populates it for normal user posts. Celebrity posts are
> NOT in this cache -- they are pulled at read time and merged. Cache miss
> triggers a cold-start rebuild from Cassandra. Cursor pagination maps to
> ZREVRANGEBYSCORE with exclusive lower bound."

---

## 2. Post Content Cache

Caches the full Post object for hot/viral posts to avoid Cassandra reads.

### Data Model

```
Key:    post:{postId}
Type:   Redis String (JSON serialized)
TTL:    24 hours
Value:  { postId, authorId, content, contentType, mediaUrls, hashtags,
          likeCount, commentCount, shareCount, createdAt }
```

### Population Strategy

```
When is a post cached?

1. On publish: if author has > 1K followers (likely to be read many times)
2. On first read: cache-aside for any post not in cache
3. On viral detection: background job caches posts with engagement > threshold

Cache-aside pattern:
  FeedService.hydratePost(postId):
    post = redis.get("post:" + postId)
    if (post == null):
      post = cassandra.get(postId)      // cache miss
      redis.setex("post:" + postId, 86400, serialize(post))
    return post
```

### Content-Type-Specific Caching

| Content Type | Cache Policy | Why |
|-------------|-------------|-----|
| TEXT | Standard (24h TTL) | Small payload, frequently read |
| IMAGE | Cache post metadata only (image from CDN) | Image served by CDN, not Redis |
| VIDEO | Cache post metadata + thumbnail URL | Video stream from CDN, metadata from Redis |
| LINK | Cache post metadata + unfurled preview | Link preview is expensive to regenerate |
| POLL | Cache post + current vote counts | Vote counts change frequently, shorter TTL (1h) |

### Why Cache Post Content?

```
Without cache:
  Feed of 20 items -> 20 Cassandra reads (one per postId)
  Latency: 20 x 5ms = 100ms

With cache (90% hit rate):
  2 Cassandra reads + 18 Redis reads
  Latency: 2 x 5ms + 18 x 0.5ms = 19ms (5x faster)

With pipelined Redis reads:
  Pipeline 18 GET commands -> 1 round trip: ~1ms
  Total: 2 x 5ms + 1ms = 11ms
```

### Engagement Count Staleness in Post Cache

Cached post has stale like/comment/share counts. This is acceptable:
- Counts are approximate anyway ("12.3K likes")
- Real-time exact counts are not expected
- Separate engagement cache (section 5) provides fresher counts for ranking
- Post content cache is for display, not ranking

### Interview Talking Point

> "Hot posts are cached in Redis to avoid hitting Cassandra on every feed
> generation. Cache-aside pattern with 24-hour TTL. Stale engagement counts
> in the post cache are acceptable -- the ranking engine uses a separate,
> fresher engagement cache."

---

## 3. Celebrity List Cache

For each user: which of their followees are celebrities? Used at feed
generation time to know who to pull from.

### Data Model

```
Key:    celebrity-followees:{userId}
Type:   Redis Set
Members: userIds of celebrity accounts that this user follows
TTL:    none (invalidated on follow/unfollow)
```

### Population

```
FeedService.getCelebrityFollowees(userId):
  celebs = redis.smembers("celebrity-followees:" + userId)
  if (celebs == null or empty):
    // Cache miss: compute from social graph
    following = redis.smembers("following:" + userId)
    celebs = new HashSet<>()
    for (uid in following):
      user = userRepo.findById(uid)
      if (user.isCelebrity()):     // followerCount > 10,000
        celebs.add(uid)
    redis.sadd("celebrity-followees:" + userId, celebs)
  return celebs
```

### Invalidation Events

| Event | Action |
|-------|--------|
| User A follows celebrity B | `SADD celebrity-followees:{A} {B}` |
| User A unfollows celebrity B | `SREM celebrity-followees:{A} {B}` |
| User B crosses celebrity threshold (10K followers) | Background job: invalidate cache for ALL of B's followers |
| User B drops below celebrity threshold | Background job: invalidate cache for ALL of B's followers |

### Why This Cache Exists

```
Without it, every feed generation would need to:
  1. Get all followees (SMEMBERS following:{userId}) -- could be 500+
  2. For each, check if celebrity (lookup user, check followerCount)
  3. That is 500 lookups per feed request

With the cache: one SMEMBERS call returns only the celebrity followees.
  Feed generation: 1 SMEMBERS + N pulls (where N = number of celebrity followees)
  Typical N = 5-20 (most users follow few celebrities)
```

### Interview Talking Point

> "Each user has a cached set of celebrity followees. At feed time, we pull
> recent posts only from those celebrities. Invalidated on follow/unfollow.
> Avoids checking every followee's celebrity status on every feed request."

---

## 4. Celebrity Post Cache

Solves the thundering herd problem for celebrity posts.

### Data Model

```
Key:    celebrity-posts:{userId}
Type:   Redis Sorted Set
Member: postId
Score:  timestamp (epoch millis)
Max:    20 items (recent posts only)
```

### The Problem Without This Cache

```
@celebrity posts "Big announcement!"
     |
     v
5 million followers open their feeds within 60 seconds
     |
     v
Without cache: 5M Cassandra reads for the same author's posts
     |
  Cassandra melts.
```

### The Solution

```
When celebrity posts:
  1. Store in Cassandra (durable)
  2. ZADD celebrity-posts:{celebrityId} {timestamp} {postId}
  3. SET post:{postId} {serialized post content}
     (cache content for hydration)
  4. NO fan-out to follower timelines

When follower reads feed:
  1. Get celebrity followees from celebrity-followees:{userId}
  2. For each celebrity:
       ZREVRANGEBYSCORE celebrity-posts:{celebId} +inf -inf LIMIT 0 10
  3. Hydrate: GET post:{postId} for each
  4. Merge with pre-computed timeline
  5. Rank and paginate
```

### Why This Solves Thundering Herd

```
Without celebrity post cache:
  5M followers x 1 Cassandra read each = 5M reads in 60s

With celebrity post cache:
  1 Redis write (ZADD) at post time
  5M followers x 1 Redis read (ZREVRANGEBYSCORE) = 5M Redis reads
  Redis handles 100K+ reads/sec per shard = ~50 seconds across cluster
  
  Cassandra reads: ~0 (post content also cached in Redis)
```

| Path | Without Cache | With Cache |
|------|--------------|------------|
| Storage hit per follower | 1 Cassandra read | 1 Redis read |
| Latency per follower | ~5ms | ~0.5ms |
| Total load (5M followers) | 5M Cassandra reads | 5M Redis reads (10x cheaper) |
| Single point of truth | One ZSET per celebrity | Same |

### Cache Stampede Protection

```
1. Pre-warming:
   When celebrity posts, cache is populated BEFORE the Kafka event
   fires. By the time followers read, cache is already warm.

2. Read-through with mutex lock:
   If celebrity-posts:{celebId} is missing:
     SETNX lock:celebrity-posts:{celebId} 1 EX 5
     If lock acquired:
       Load from Cassandra, populate cache
     Else:
       Wait 50ms, retry (cache will be populated by lock holder)

3. Replication:
   Redis read replicas -- distribute reads across replicas.
   Master handles writes, replicas handle the 5M reads.
```

### Interview Talking Point

> "Celebrity posts are cached in a per-celebrity Redis Sorted Set. One
> cache entry serves all followers -- 5 million followers reading one
> Redis key instead of 5 million Cassandra reads. Pre-warmed at post time
> to avoid thundering herd. Redis read replicas distribute the load."

---

## 5. Engagement Counts Cache

Approximate engagement counts used for ranking and display.

### Data Model

```
Key:    engagement:{postId}
Type:   Redis Hash
Fields: likes, comments, shares

Example:
  HSET engagement:post-123 likes 42 comments 7 shares 3
  HINCRBY engagement:post-123 likes 1     -> 43
  HGETALL engagement:post-123             -> {likes: 43, comments: 7, shares: 3}
```

### Update Flow

```
User likes post-123
     |
     v
1. Record in durable store (Cassandra/DynamoDB)
     |
     v
2. Publish event to Kafka: "post.liked"
     |
     v
3. Kafka consumer: "engagement-updater"
     |
     +---> HINCRBY engagement:post-123 likes 1
     |
     +---> Dedup check: SETNX dedup:like:user-7:post-123 1 EX 300
     |     If key exists: skip (duplicate Kafka delivery)
     |     If key set: proceed with HINCRBY
     |
  Redis count updated within 1 second of like event
```

### Why Separate from Post Content Cache?

```
Post content cache (section 2):
  - Updated rarely (content does not change)
  - TTL: 24 hours
  - Used for: display (post text, media URLs, author)
  - Counts are stale (snapshot at cache time)

Engagement counts cache (this section):
  - Updated frequently (every like/comment/share)
  - No TTL (always live, updated by Kafka consumers)
  - Used for: ranking (AlgorithmicRankingStrategy)
  - Counts are near-real-time (< 1 second delay)

Separation allows:
  - Post content cache has long TTL (cheap, rarely invalidated)
  - Engagement cache is always fresh (important for ranking accuracy)
  - Both can scale independently
```

### Batch Reading for Feed Ranking

```
Feed has 50 items to rank. Need engagement counts for all 50.

Naive approach (50 round trips):
  for each postId:
    HGETALL engagement:{postId}     -> 50 network calls, ~25ms

Pipelined approach (1 round trip):
  Pipeline:
    HGETALL engagement:post-1
    HGETALL engagement:post-2
    ...
    HGETALL engagement:post-50
  Execute pipeline                   -> 1 network call, ~1ms

  50x faster. Always pipeline engagement reads for feed ranking.
```

### Approximate Counts Are Fine

| Scenario | Impact | Acceptable? |
|----------|--------|-------------|
| Like count off by 5 | Display shows "12.3K" instead of "12.3K" | Yes -- rounded anyway |
| Comment count off by 1 | Display shows "7" instead of "8" | Yes -- updates on next render |
| Ranking score off by 3% | Post appears 1 position different | Yes -- ranking is approximate |
| Counter reset to 0 (cache lost) | Post appears with 0 likes briefly | No -- rebuild from durable store |

### Interview Talking Point

> "Engagement counts use a separate Redis Hash per post, updated in near-real-time
> by Kafka consumers. Separate from the post content cache so ranking always uses
> fresh counts. Pipelined reads: 50 posts' engagement data in 1ms, not 25ms."

---

## 6. Affinity Score Cache

User-to-author interaction history used by AlgorithmicRankingStrategy.

### Data Model

```
Key:    affinity:{userId}:{authorId}
Type:   Redis Hash
Fields: like_count, comment_count, share_count, click_count, last_interaction
TTL:    7 days (rebuilt by ML pipeline if expired)

Example:
  HSET affinity:user-42:user-7 like_count 15 comment_count 3 share_count 1
       click_count 45 last_interaction 1713446400000
```

### How Affinity Is Computed

```
Simple formula (our Java implementation):
  affinity = (like_count * 1.0 + comment_count * 2.0
              + share_count * 3.0 + click_count * 0.5)
             * recency_factor(last_interaction)

  recency_factor = exp(-0.05 * days_since_last_interaction)
  - Interaction yesterday: recency = 0.95
  - Interaction last week: recency = 0.70
  - Interaction last month: recency = 0.22

Production (ML):
  affinity = model.predict(user_features, author_features, interaction_features)
  - Trained on billions of engagement events
  - Incorporates hundreds of signals
  - Retrained daily
```

### Update Flow

```
User-42 likes a post by User-7
     |
     v
Kafka event: "post.liked"
     |
     v
Engagement updater consumer:
     |
     +---> HINCRBY engagement:post-123 likes 1 (post-level)
     |
     +---> HINCRBY affinity:user-42:user-7 like_count 1 (user-author pair)
     |
     +---> HSET affinity:user-42:user-7 last_interaction {now}
     |
  Affinity score for user-42 -> user-7 is now slightly higher.
  Next feed generation will rank user-7's posts higher for user-42.
```

### Why Cache Affinity?

```
Without cache:
  For 50 feed items, need 50 affinity lookups
  Each lookup: query Cassandra for interaction history, compute score
  50 x 10ms = 500ms -- too slow for feed generation

With cache:
  50 pipelined HGETALL calls = 1ms
  Compute score from cached counts = 0.5ms
  Total: 1.5ms (333x faster)
```

### Staleness Tolerance

```
Affinity changes slowly:
  - User-42 likes user-7's posts 2-3 times per week
  - Each like changes the affinity score by ~2%
  - Missing one like event does not meaningfully change ranking

TTL of 7 days is generous:
  - Most active user-author pairs are updated multiple times per week
  - Expired entries rebuilt by ML pipeline batch job (daily)
  - Cache miss: compute on the fly from durable store (slower, but rare)
```

### Interview Talking Point

> "Affinity scores are cached per user-author pair in Redis Hash. Updated
> in real-time by Kafka consumers on every like/comment/share. Used by
> AlgorithmicRankingStrategy to personalize: posts from close friends rank
> higher. 7-day TTL, rebuilt daily by ML pipeline."

---

## 7. User Profile Cache

Two-tier cache: local in-process + Redis.

### Architecture

```
  Request needs user profile (for feed rendering, ranking)
       |
       v
  L1: Local Cache (Caffeine / Guava)
  TTL: 1 minute, max 10K entries
       |
  HIT? -> return immediately (no network call)
       |
      MISS
       |
       v
  L2: Redis Cache
  Key: user:{userId}
  TTL: 5 minutes
       |
  HIT? -> return, populate L1
       |
      MISS
       |
       v
  PostgreSQL: SELECT * FROM users WHERE user_id = ?
       |
  Populate L2 (Redis) and L1 (local)
       |
  Return
```

### Why Two Tiers?

| Tier | Latency | Cost | Staleness |
|------|---------|------|-----------|
| L1 (local) | < 0.1ms | Free (JVM heap) | Up to 1 minute |
| L2 (Redis) | ~1ms | Network hop | Up to 5 minutes |
| PostgreSQL | ~5ms | Disk I/O, connection pool | Source of truth |

User profiles change rarely (name, bio updates are infrequent), so
5-minute staleness is perfectly acceptable.

### Used By

| Consumer | Why Profile Is Needed |
|----------|----------------------|
| Feed rendering | Display author name, profile pic |
| Ranking | isCelebrity() check for fan-out path |
| Ranking | Author follower count (social proof signal) |
| Notification | Author name for push notification text |
| Celebrity detection | Check followerCount > 10K threshold |

### Interview Talking Point

> "User profiles use a two-tier cache -- local Caffeine cache (1-min TTL)
> backed by Redis (5-min TTL) backed by PostgreSQL. Profile changes are
> rare, so staleness is fine. isCelebrity() check uses cached followerCount."

---

## 8. Cache Warming

Pre-load active users' timelines to avoid cold-start latency on first request.

### On Login -- Pre-Fetch Timeline

```
User logs in (or opens app)
     |
     v
Login event published to Kafka: "user.login"
     |
     v
Cache warmer consumer:
     |
     +---> Check: does timeline:{userId} exist?
     |
     +---> If exists and ZCARD > 500: SKIP (cache is warm)
     |
     +---> If missing or stale:
     |       1. Get following list: SMEMBERS following:{userId}
     |       2. For each followed user:
     |            Query Cassandra: recent posts (last 7 days, limit 20)
     |       3. Merge all results
     |       4. Write top 1000 to timeline:{userId}
     |       5. Pre-fetch engagement counts for top 200 posts
     |       6. Pre-fetch celebrity post lists
     |
  Cache warm latency: 200ms - 1s (async, not blocking login)
  First feed request: < 50ms (cache hit)
```

### App Server Startup

```
On app server startup:
  1. Query "active users" list (users active in last 24h)
     -> Stored in Redis Set: active-users (refreshed by login events)

  2. For each active user (parallel, batched, rate-limited):
     a. Check if timeline:{userId} exists and ZCARD > 500
     b. If not: trigger cold-start rebuild
        - Get following list
        - Query recent posts from Cassandra
        - Build timeline, rank, write to Redis

  3. Priority order:
     - DAU (daily active users) first
     - Users with upcoming push notifications
     - Recently registered users (first experience matters)
     - Users who follow celebrities (more complex feed, benefit more from caching)
```

### When to Warm

| Trigger | Scope | Why |
|---------|-------|-----|
| User login | Single user | Ensure warm cache for immediate feed load |
| App server startup | DAU users on this shard | New deployment, fresh JVM |
| Redis failover | All users on failed shard | Cache lost, must rebuild |
| User returns after dormancy | Single user | Cache expired or evicted |
| Celebrity posts | Celebrity's cache | Pre-warm celebrity-posts:{celebId} |
| Follow event | New followee's posts added to timeline | Keep timeline up to date |

### Warm vs Cold Performance

```
Cold start (no cache):
  Following 300 users, 20 celebrities
  1. SMEMBERS following:{userId}                    2ms
  2. For 300 users: batch Cassandra reads           200ms
  3. For 20 celebrities: batch reads                50ms
  4. Merge + rank                                   10ms
  5. Write to Redis: ZADD 1000 items               20ms
  Total: ~280ms (acceptable for async warm)

  If done synchronously on feed request: 280ms delay
  User experience: noticeable spinner

Warm (cache hit):
  1. ZREVRANGEBYSCORE timeline:{userId} ...         2ms
  2. Pipeline GET post:{postId} x 20               1ms
  3. Pipeline HGETALL engagement:{postId} x 20     1ms
  4. Rank (in-memory computation)                   2ms
  Total: ~6ms

  Warm is 47x faster than cold.
```

### Interview Talking Point

> "Cache warming pre-loads active users' timelines on login. A Kafka consumer
> listens for login events and asynchronously builds the timeline cache.
> Priority: DAU first, then recently active. Cold start takes ~280ms,
> warm cache serves feeds in ~6ms -- a 47x improvement."

---

## 9. Cache Invalidation

### Invalidation by Event

| Cache | Invalidation Trigger | Strategy | Consistency |
|-------|---------------------|----------|-------------|
| Timeline | Post deleted | `ZREM timeline:{userId} {postId}` via Kafka | Eventual (seconds) |
| Timeline | User unfollows author | Remove unfollowed author's posts from cache | Eventual (see below) |
| Post content | TTL expiry (24h) | Auto-expire | N/A |
| Post content | Post deleted | `DEL post:{postId}` | Immediate |
| Post content | Post edited | `DEL post:{postId}` (re-cache on next read) | Immediate |
| Social graph | Follow/unfollow | Synchronous `SADD/SREM` | **Strong** |
| Celebrity followees | Follow/unfollow celebrity | `SADD/SREM celebrity-followees:{userId}` | Synchronous |
| Celebrity followees | User crosses threshold | Background job invalidates all followers | Eventual (minutes) |
| Celebrity posts | TTL or cap (20 items) | `ZREMRANGEBYRANK` | N/A |
| Engagement counts | No TTL (always live) | Updated by Kafka consumers | Near-real-time |
| Affinity scores | TTL (7 days) | Rebuilt by ML pipeline | Eventual (hours) |
| User profile | TTL (L1: 1min, L2: 5min) | Auto-expire | Eventual (minutes) |

### Unfollow Cache Invalidation -- Deep Dive

```
User A unfollows User B
     |
     v
1. Social graph updated (strong consistency):
   MULTI
     SREM followers:B A
     SREM following:A B
   EXEC
     |
     v
2. Celebrity followees cache updated:
   SREM celebrity-followees:A B   (if B was a celebrity)
     |
     v
3. Timeline cleanup (eventual):
   Option A: Remove B's posts from A's timeline
     - ZREM timeline:A {each of B's postIds in the cache}
     - Requires knowing which posts in A's timeline are from B
     - Expensive: scan all 1000 items? Or maintain a reverse index?

   Option B: Lazy cleanup (recommended)
     - Do NOT remove B's posts from timeline immediately
     - Posts from B age out naturally (evicted when cap of 1000 exceeded)
     - Feed rendering checks: "is A still following B?" (SISMEMBER)
     - If not following: filter out at render time
     - Eventually: B's old posts are evicted, no trace left

   Option B is preferred because:
     - No expensive timeline scan
     - No reverse index needed
     - Feed rendering already does filtering (deleted posts, etc.)
     - Posts age out within hours as new content pushes them out
```

### Post Deletion Propagation

```
Author deletes post-123
     |
     v
1. Cassandra: UPDATE posts_by_id SET deleted = true WHERE post_id = 'post-123'
     |
     v
2. Redis: DEL post:post-123   (remove from post content cache)
     |
     v
3. Kafka event: "post.deleted"
     |
     v
4. Fan-out worker:
   For each follower who might have this post in their timeline:
     ZREM timeline:{followerId} post-123
     |
   Takes seconds to minutes (same as initial fan-out)
     |
     v
5. Safety net: FeedService.hydratePost() checks post.isDeleted()
   Even if ZREM has not completed, deleted posts are filtered at render time
```

---

## 10. Feed Generation -- Full Cache Flow

The complete cache read path from request to response.

### Numbered Sequence

```
GET /feed?userId=user-42&limit=20&cursor=MTAwLjB8cG9zdC01

  1. [User Profile Cache] Get user-42's profile
     L1 HIT (Caffeine): return in 0.1ms
     OR L2 HIT (Redis): return in 1ms
     -> Need isCelebrity() check? No (user-42 is requesting, not posting)

  2. [Timeline Cache] Read pre-computed timeline
     ZREVRANGEBYSCORE timeline:user-42 (100.0 -inf LIMIT 0 200
     -> Returns up to 200 postIds (candidates for ranking)
     -> If empty: COLD START (section 8) -- rebuild from Cassandra

  3. [Post Content Cache] Hydrate each postId
     Pipeline: GET post:{postId} for each of 200 postIds
     -> Cache hits: return immediately
     -> Cache misses: batch-read from Cassandra, populate cache

  4. [Celebrity List Cache] Find celebrity followees
     SMEMBERS celebrity-followees:user-42
     -> Returns e.g., [celeb-1, celeb-2, celeb-3]
     -> If empty: compute from following list, populate cache

  5. [Celebrity Post Cache] Pull recent celebrity posts
     For each celebrity:
       ZREVRANGEBYSCORE celebrity-posts:{celebId} +inf -inf LIMIT 0 10
     -> Returns recent postIds per celebrity

  6. [Post Content Cache] Hydrate celebrity posts
     Pipeline: GET post:{postId} for each celebrity post
     -> Same cache as step 3

  7. MERGE pushed (step 3) + pulled (step 6)
     Dedup by postId (LinkedHashMap)

  8. [Engagement Counts Cache] Get fresh counts for ranking
     Pipeline: HGETALL engagement:{postId} for each merged item
     -> Returns {likes, comments, shares} per post

  9. [Affinity Score Cache] Get affinity for each author
     Pipeline: HGETALL affinity:user-42:{authorId} for each unique author
     -> Returns interaction history per user-author pair

  10. RANK using AlgorithmicRankingStrategy
      score = affinity * recency * engagement * contentTypeWeight
      Sort by score descending

  11. APPLY CURSOR
      Skip items with score >= 100.0 (the cursor value)
      Take top 20 items

  12. BUILD NEXT CURSOR
      Last item's score + postId -> encode as Base64

  13. RETURN FeedPage { items: [...20 items...], nextCursor: "..." }
```

### Latency Breakdown (Warm Cache)

```
  Step 1:  User profile (L1 cache)               0.1ms
  Step 2:  Timeline ZREVRANGEBYSCORE              2.0ms
  Step 3:  Post hydration (pipeline, 200 items)   1.0ms
  Step 4:  Celebrity list SMEMBERS                0.5ms
  Step 5:  Celebrity posts (3 ZREVRANGEBYSCORE)   1.5ms
  Step 6:  Celebrity post hydration (pipeline)    0.5ms
  Step 7:  Merge + dedup (in-memory)              0.5ms
  Step 8:  Engagement counts (pipeline)           1.0ms
  Step 9:  Affinity scores (pipeline)             1.0ms
  Step 10: Ranking (in-memory computation)        2.0ms
  Step 11: Cursor application                     0.1ms
  Step 12: Cursor encoding                        0.1ms
  -----------------------------------------------
  Total:                                         10.3ms

  With ML model instead of in-memory ranking:
  Step 10: ML model (gRPC to TF Serving)         50.0ms
  Total:                                         58.3ms
```

### Cache Hit Rates (Target)

| Cache | Target Hit Rate | If Below Target |
|-------|----------------|-----------------|
| Timeline cache | > 95% | Check: are users logging in before cache warm? |
| Post content cache | > 90% | Check: TTL too short? Viral posts not pre-cached? |
| Celebrity list cache | > 99% | Check: follow/unfollow rate too high? |
| Celebrity post cache | > 99% | Check: celebrities posting too fast? Cap too low? |
| Engagement counts | > 99% | Check: Kafka consumer lag? New posts not indexed? |
| Affinity scores | > 85% | Check: TTL too short? ML pipeline delay? |
| User profile (L1) | > 70% | Check: cache size too small? TTL too short? |
| User profile (L2) | > 95% | Check: Redis memory pressure? |

---

## 11. Memory Budget

### Per-User Cache Footprint

```
Timeline cache:     1000 items x 50 bytes = 50 KB
Post content:       Shared across users (not per-user)
Celebrity list:     ~20 entries x 40 bytes = 0.8 KB
Engagement counts:  Shared across users (per-post)
Affinity scores:    ~300 pairs x 100 bytes = 30 KB
User profile (L2):  ~500 bytes
------------------------------------------------------
Total per-user:     ~81 KB

For 500M active users:
  Timeline:          25 TB
  Affinity:          15 TB
  Total per-user:    ~40 TB
```

### Shared Cache Footprint

```
Post content:       1KB x 50M hot posts = 50 GB
Engagement counts:  100 bytes x 50M posts = 5 GB
Celebrity posts:    20 items x 1M celebrities x 50 bytes = 1 GB
User profiles:      500 bytes x 500M users = 250 GB
------------------------------------------------------
Total shared:       ~306 GB
```

### Total Redis Cluster

```
Per-user:    ~40 TB
Shared:      ~306 GB
Total:       ~40.3 TB

Redis shards (25 GB each): ~1,612 shards
With read replicas (2 per shard): ~4,836 nodes

In practice, most users are not active simultaneously:
  100M concurrent users (not 500M) -> ~8 TB active
  ~320 primary shards + 640 replicas = 960 nodes
```

### Interview Talking Point

> "Timeline cache is the biggest consumer: 50 KB per user x 500M users = 25 TB.
> Affinity scores add 15 TB. Shared caches (post content, engagement, profiles)
> are ~300 GB. Total Redis cluster: ~1600 shards. In practice, 100M concurrent
> users need ~960 nodes (320 primaries + 640 read replicas)."

---

## Interview Talking Points -- Cache Strategy Summary

1. **"What is the most important cache in this system?"**
   Timeline cache -- Redis Sorted Set per user, 1000 items max, populated
   by fan-out-on-write. This is the hot path for every feed request.

2. **"How do you handle celebrity posts?"**
   Do not push to follower timelines. Cache celebrity's recent posts in a
   per-celebrity sorted set. One cache entry serves all followers. Merged
   at read time by FeedService.

3. **"What about the thundering herd problem?"**
   Celebrity post cache is pre-warmed at post time (before Kafka fires).
   Redis read replicas distribute the 5M simultaneous reads. Mutex lock
   prevents cache stampede on miss.

4. **"What happens on a cache miss?"**
   Cold-start rebuild: pull recent posts from Cassandra for all followed
   users, merge, rank, write back to Redis. Takes 280ms-2s. Subsequent
   reads hit cache at < 10ms.

5. **"How do you keep caches consistent?"**
   Social graph: synchronous (strong consistency). Everything else: eventual
   consistency with TTLs. Post deletion: ZREM from timelines + DEL post
   content (async via Kafka). We tolerate brief staleness because it is a
   feed, not a bank account.

6. **"How much Redis memory do you need?"**
   Timeline: 50 KB x 500M users = 25 TB.
   Affinity: 30 KB x 500M users = 15 TB.
   Post content: 1KB x 50M hot posts = 50 GB.
   Engagement: 100 bytes x 50M posts = 5 GB.
   Total: ~40 TB across ~1600 shards.

7. **"How does cache warming work?"**
   On login, a Kafka consumer asynchronously builds the timeline cache.
   On app server startup, DAU users are warmed in priority order.
   Cold start: ~280ms. Warm cache: ~6ms. 47x improvement.

8. **"How does unfollow affect the cache?"**
   Lazy cleanup: unfollowed author's posts age out naturally via the
   1000-item cap. FeedService filters at render time by checking
   SISMEMBER on the follow graph. No expensive timeline scan needed.

9. **"How does cursor pagination interact with the cache?"**
   ZREVRANGEBYSCORE with exclusive lower bound `({score}` maps cursor
   directly to Redis. No offset-based issues. Deep scrolling beyond
   1000 cached items falls back to Cassandra.
