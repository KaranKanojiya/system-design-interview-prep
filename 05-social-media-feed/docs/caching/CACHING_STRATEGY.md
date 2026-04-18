# Caching Strategy -- Social Media Feed System

> Every cache layer in the system, from the core timeline cache to the
> thundering herd solution for celebrity tweets. Interview-ready with
> Redis commands, eviction policies, and invalidation strategies.

---

## Cache Layer Overview

```
  GET /feed?userId=user-42
       |
       v
  +-------------------+
  | User Profile Cache|  Local + Redis, 5-min TTL
  +-------------------+
       |
       v
  +-------------------+
  | Timeline Cache    |  Redis Sorted Set, max 200 items   <-- THE CORE CACHE
  | (fan-out-on-write)|  Populated by fan-out workers
  +-------------------+
       |
       | cache miss or celebrity tweets needed
       v
  +-------------------+
  | Celebrity List    |  Redis Set: celebrity followees for this user
  | Cache             |  Invalidated on follow/unfollow
  +-------------------+
       |
       v
  +-------------------+
  | Celebrity Tweet   |  Redis: recent tweets per celebrity
  | Cache             |  One entry serves ALL followers (thundering herd fix)
  +-------------------+
       |
       v
  +-------------------+
  | Tweet Content     |  Redis: hot/viral tweet details
  | Cache             |  Key: tweet:{tweetId}, TTL: 24h
  +-------------------+
       |
       v
  +-------------------+
  | Trending Cache    |  Redis Sorted Set, refresh every 1-5 min
  +-------------------+
```

---

## 1. Timeline Cache -- THE Core Cache

The user's pre-computed home timeline. This is the primary read path.

### Data Model

```
Key:    timeline:{userId}
Type:   Redis Sorted Set (ZSET)
Member: tweetId
Score:  timestamp (epoch millis) or engagement score
Max:    200 items per user
```

### How It Gets Populated

```
Normal user (< 10K followers) posts a tweet:
  |
  v
Kafka -> Fan-out worker
  |
  v
For each follower:
  ZADD timeline:{followerId} {timestamp} {tweetId}
  |
  v
If ZCARD timeline:{followerId} > 200:
  ZREMRANGEBYRANK timeline:{followerId} 0 0    (remove lowest score / oldest)
```

### What Is NOT in This Cache

Celebrity tweets are NOT pushed to follower timelines. They are pulled
at read time from the Celebrity Tweet Cache (see section 6).

```
@taylorswift tweets "Hello world!"
  |
  v
Fan-out? NO -- 90M followers, skip push.
  |
  v
Tweet stored in:
  1. Cassandra (durable)
  2. Celebrity Tweet Cache (Redis, for read-time pull)
  |
  v
When follower opens feed:
  FeedService merges timeline cache + pulled celebrity tweets
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
  |       Query Cassandra: SELECT * FROM tweets_by_user
  |         WHERE user_id = ? AND created_at > (now - 7 days)
  |         LIMIT 20
  +---> Merge all results
  +---> Rank by engagement or chronology
  +---> Write top 200 back to timeline:{userId}
  +---> Return feed
  |
  Cold start latency: 500ms - 2s (depending on following count)
  Subsequent reads: < 50ms (cache hit)
```

### Eviction Policy

```
Max 200 items per user.
After every ZADD:
  ZCARD timeline:{userId}
  If > 200:
    ZREMRANGEBYRANK timeline:{userId} 0 (count - 200 - 1)
    (Removes the lowest-scored items)

Why 200?
  - Average user scrolls 50-100 tweets per session
  - 200 provides buffer for engagement ranking reordering
  - 200 items x 50 bytes = ~10 KB per user (tiny)
  - Older tweets available via pull from Cassandra if user scrolls deep
```

### Interview Talking Point

> "The timeline cache is a Redis Sorted Set per user, capped at 200 items.
> Fan-out-on-write populates it for normal user tweets. Celebrity tweets are
> NOT in this cache -- they are pulled at read time and merged. Cache miss
> triggers a cold-start rebuild from Cassandra."

---

## 2. Tweet Content Cache

Caches the full Tweet object for hot/viral tweets to avoid Cassandra reads.

### Data Model

```
Key:    tweet:{tweetId}
Type:   Redis String (JSON serialized)
TTL:    24 hours
Value:  { tweetId, userId, content, mediaUrls, hashtags,
          likeCount, retweetCount, replyCount, createdAt }
```

### Population Strategy

```
When is a tweet cached?

1. On publish: if poster has > 1K followers (likely to be read many times)
2. On first read: cache-aside for any tweet not in cache
3. On viral detection: background job caches tweets with engagement > threshold

Cache-aside pattern:
  FeedService.hydrateTweet(tweetId):
    tweet = redis.get("tweet:" + tweetId)
    if (tweet == null):
      tweet = cassandra.get(tweetId)      // cache miss
      redis.setex("tweet:" + tweetId, 86400, serialize(tweet))
    return tweet
```

### Why Cache Tweet Content?

```
Without cache:
  Feed of 50 items -> 50 Cassandra reads (one per tweetId)
  Latency: 50 x 5ms = 250ms

With cache (90% hit rate):
  5 Cassandra reads + 45 Redis reads
  Latency: 5 x 5ms + 45 x 0.5ms = 47.5ms (5x faster)
```

### Engagement Count Staleness

Cached tweet has stale like/retweet counts. This is acceptable:
- Counts are approximate anyway ("12.3K likes")
- Real-time exact counts are not expected
- TTL of 24h means counts refresh daily at most
- For viral tweets, a background job can refresh more frequently

### Interview Talking Point

> "Hot tweets are cached in Redis to avoid hitting Cassandra on every feed
> generation. Cache-aside pattern with 24-hour TTL. Stale engagement counts
> are acceptable -- users see approximate numbers."

---

## 3. Social Graph Cache

Follow/following relationships in Redis Sets.

### Data Model

```
Key: followers:{userId}    Type: Redis Set    Members: follower userIds
Key: following:{userId}    Type: Redis Set    Members: followed userIds
```

### Used By

| Consumer | Operation | Why |
|----------|-----------|-----|
| Fan-out worker | SMEMBERS followers:{posterId} | Get all followers to push to |
| FeedService | SMEMBERS following:{userId} | Find celebrity followees for pull path |
| API (UI) | SISMEMBER following:{A} {B} | "Follow" button state |
| Mutual friends | SINTER following:{A} following:{B} | "Followers you know" |

### Invalidation

```
Follow action:
  MULTI
    SADD followers:{B} {A}
    SADD following:{A} {B}
  EXEC
  -> Invalidate celebrity-list cache for user A (see section 6)

Unfollow action:
  MULTI
    SREM followers:{B} {A}
    SREM following:{A} {B}
  EXEC
  -> Invalidate celebrity-list cache for user A
```

### Consistency Requirement

Social graph is one of the few places where **strong consistency** matters.
A stale follower list means:
- Fan-out includes someone who unfollowed (they see unwanted tweets)
- Fan-out excludes someone who just followed (they miss tweets)

Both are bad UX, so follow/unfollow writes are synchronous to Redis.

### Interview Talking Point

> "Social graph is in Redis Sets -- O(1) for SISMEMBER, O(N) for SMEMBERS.
> Follow/unfollow is synchronous (strong consistency) because a stale graph
> means wrong fan-out. This is the one place we prioritize consistency."

---

## 4. User Profile Cache

Two-tier cache: local in-process + Redis.

### Architecture

```
  Request needs user profile
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
| L1 (local) | < 1ms | Free (JVM heap) | Up to 1 minute |
| L2 (Redis) | ~1ms | Network hop | Up to 5 minutes |
| PostgreSQL | ~5ms | Disk I/O, connection pool | Source of truth |

User profiles change rarely (name, bio updates are infrequent), so
5-minute staleness is perfectly acceptable.

### Interview Talking Point

> "User profiles use a two-tier cache -- local Caffeine cache (1-min TTL)
> backed by Redis (5-min TTL) backed by PostgreSQL. Profile changes are
> rare, so staleness is fine."

---

## 5. Trending Cache

Pre-computed top trending topics, refreshed periodically.

### Data Model

```
Underlying data:
  Key: trending:{window}     Type: Sorted Set
  Member: hashtag            Score: tweet count in window
  (See TECHNOLOGIES.md for details on ZINCRBY)

Computed cache:
  Key: trending:top10        Type: Redis String (JSON list)
  TTL: none (refreshed by background job)
  Value: [{"hashtag":"java","count":4200,"score":8400.0}, ...]
```

### Refresh Strategy

```
Background job (every 1-5 minutes):
  1. ZUNIONSTORE trending:combined 3
       trending:{current-hour}
       trending:{prev-hour-1}
       trending:{prev-hour-2}
       WEIGHTS 4.0 2.0 1.0          (recent hours weighted more)

  2. ZREVRANGE trending:combined 0 9 WITHSCORES
     -> Top 10 trending hashtags with scores

  3. Build TrendingTopic objects with count + score

  4. SET trending:top10 (serialize to JSON)
     -> Served to all trending page requests

  5. DEL trending:combined   (cleanup temp key)
```

### Why Not Compute On Every Request?

```
ZUNIONSTORE across 3 windows + ZREVRANGE = ~5ms

But at 100K requests/sec for trending page:
  5ms x 100K = 500 seconds of Redis time per second (impossible)

With cached top-10 refreshed every 1 minute:
  GET trending:top10 = 0.1ms per request
  ZUNIONSTORE runs once per minute, not per request
```

### Interview Talking Point

> "Trending is refreshed every 1-5 minutes by a background job that runs
> ZUNIONSTORE across time windows with recency weights, then caches the
> top-10 as a single Redis key. Approximate counts are fine for trending."

---

## 6. Celebrity List Cache

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
  if (celebs == null):
    // Cache miss: compute from social graph
    following = redis.smembers("following:" + userId)
    celebs = new HashSet<>()
    for (uid in following):
      user = userRepo.findById(uid)
      if (user.isCelebrity()):
        celebs.add(uid)
    redis.sadd("celebrity-followees:" + userId, celebs)
  return celebs
```

### Invalidation Events

| Event | Action |
|-------|--------|
| User A follows celebrity B | `SADD celebrity-followees:{A} {B}` |
| User A unfollows celebrity B | `SREM celebrity-followees:{A} {B}` |
| User B crosses celebrity threshold (10K followers) | Invalidate cache for ALL of B's followers (background job) |
| User B drops below celebrity threshold | Same as above |

### Why This Cache Exists

Without it, every feed generation would need to:
1. Get all followees (SMEMBERS following:{userId}) -- could be 500+
2. For each, check if celebrity (lookup user, check followerCount)
3. That is 500 lookups per feed request

With the cache: one SMEMBERS call returns only the celebrity followees.

### Interview Talking Point

> "Each user has a cached set of celebrity followees. At feed time, we pull
> recent tweets only from those celebrities. Invalidated on follow/unfollow.
> Avoids checking every followee's celebrity status on every feed request."

---

## 7. Thundering Herd on Celebrity Tweets

**The biggest caching challenge in the system.**

### The Problem

```
@taylorswift tweets "New album dropping tonight!"
     |
     v
5 million followers open their feeds within 60 seconds
     |
     v
Without cache: 5M Cassandra reads for the same tweet
     |
  Cassandra melts.
```

### The Solution: Celebrity Tweet Cache

```
Key:    celebrity-tweets:{userId}
Type:   Redis Sorted Set
Member: tweetId
Score:  timestamp
Max:    20 items (recent tweets only)

When celebrity posts:
  1. Store in Cassandra (durable)
  2. ZADD celebrity-tweets:{celebrityId} {timestamp} {tweetId}
  3. SET tweet:{tweetId} {serialized tweet content}
     (cache content for hydration)
  4. NO fan-out to follower timelines

When follower reads feed:
  1. Get celebrity followees from celebrity-followees:{userId}
  2. For each celebrity:
       ZREVRANGEBYSCORE celebrity-tweets:{celebId} +inf -inf LIMIT 0 10
  3. Hydrate: GET tweet:{tweetId} for each
  4. Merge with pre-computed timeline
  5. Rank and return
```

### Why This Solves Thundering Herd

```
Without celebrity tweet cache:
  5M followers x 1 Cassandra read each = 5M reads in 60s

With celebrity tweet cache:
  1 Redis write (ZADD) at post time
  5M followers x 1 Redis read (ZREVRANGEBYSCORE) = 5M Redis reads
  Redis handles 100K+ reads/sec per shard = ~50 seconds across cluster
  
  Cassandra reads: ~0 (tweet content also cached in Redis)
```

| Path | Without Cache | With Cache |
|------|--------------|------------|
| Storage hit per follower | 1 Cassandra read | 1 Redis read |
| Latency per follower | ~5ms | ~0.5ms |
| Total load (5M followers) | 5M Cassandra reads | 5M Redis reads (10x cheaper) |
| Single point of truth | One ZSET per celebrity | Same |

### Cache Stampede Protection

Even with Redis, 5M simultaneous reads on the same key can cause issues.
Additional protection:

```
1. Read-through with mutex lock:
   If celebrity-tweets:{celebId} is missing:
     SETNX lock:celebrity-tweets:{celebId} 1 EX 5
     If lock acquired:
       Load from Cassandra, populate cache
     Else:
       Wait 50ms, retry (cache will be populated by lock holder)

2. Pre-warming:
   When celebrity posts, cache is populated BEFORE the Kafka event
   fires. By the time followers read, cache is already warm.

3. Replication:
   Redis read replicas -- distribute reads across replicas.
   Master handles writes, replicas handle the 5M reads.
```

### Interview Talking Point

> "Celebrity tweets are cached in a per-celebrity Redis Sorted Set. One
> cache entry serves all followers -- 5 million followers reading one
> Redis key instead of 5 million Cassandra reads. Pre-warmed at post time
> to avoid thundering herd. Redis read replicas distribute the load."

---

## 8. Cache Warming

Pre-load active users' timelines on application startup or new deployment.

### Strategy

```
On app server startup:
  1. Query "active users" list (users who opened app in last 24h)
     -> Stored in Redis Set: active-users (refreshed by login events)

  2. For each active user (parallel, batched):
     a. Check if timeline:{userId} exists and has > 100 items
     b. If not: trigger cold-start rebuild
        - Get following list
        - Query recent tweets from Cassandra
        - Build timeline, rank, write to Redis

  3. Priority order:
     - DAU (daily active users) first
     - Users with upcoming push notifications
     - Recently registered users (first experience matters)
```

### When to Warm

| Trigger | Scope | Why |
|---------|-------|-----|
| App server startup | DAU users on this shard | New deployment, fresh JVM |
| Redis failover | All users on failed shard | Cache lost, must rebuild |
| User returns after dormancy | Single user | Cache expired or evicted |
| Celebrity crosses threshold | Celebrity's followers | Fan-out strategy changes |

### Interview Talking Point

> "Cache warming pre-loads active users' timelines on startup. Priority:
> DAU first, then recently active. A cold-start rebuild pulls from Cassandra
> and takes 500ms-2s, so we warm proactively to avoid that in the hot path."

---

## Cache Invalidation Summary

| Cache | Invalidation Trigger | Strategy |
|-------|---------------------|----------|
| Timeline | Tweet deleted | ZREM timeline:{userId} {tweetId} |
| Timeline | User unfollows poster | No removal (ages out via 200-cap) |
| Tweet content | TTL expiry (24h) | Auto-expire |
| Tweet content | Tweet deleted | DEL tweet:{tweetId} |
| Social graph | Follow/unfollow | Synchronous SADD/SREM |
| Celebrity followees | Follow/unfollow celebrity | SADD/SREM celebrity-followees:{userId} |
| Celebrity followees | User crosses celebrity threshold | Background job invalidates all followers |
| Celebrity tweets | TTL or cap (20 items) | ZREMRANGEBYRANK |
| User profile | TTL (L1: 1min, L2: 5min) | Auto-expire |
| Trending | Background refresh (1-5 min) | Overwrite trending:top10 |

---

## Interview Talking Points -- Cache Strategy Summary

1. **"What is the most important cache in this system?"**
   Timeline cache -- Redis Sorted Set per user, 200 items max, populated
   by fan-out-on-write. This is the hot path for every feed request.

2. **"How do you handle celebrity tweets?"**
   Do not push to follower timelines. Cache celebrity's recent tweets in a
   per-celebrity sorted set. One cache entry serves all followers. Merged
   at read time by FeedService.

3. **"What about the thundering herd problem?"**
   Celebrity tweet cache is pre-warmed at post time (before Kafka fires).
   Redis read replicas distribute the 5M simultaneous reads. Mutex lock
   prevents cache stampede on miss.

4. **"What happens on a cache miss?"**
   Cold-start rebuild: pull recent tweets from Cassandra for all followed
   users, merge, rank, write back to Redis. Takes 500ms-2s. Subsequent
   reads hit cache at < 50ms.

5. **"How do you keep caches consistent?"**
   Social graph: synchronous (strong consistency). Everything else: eventual
   consistency with TTLs. Tweet deletion: ZREM from timelines + DEL tweet
   content (async via Kafka). We tolerate brief staleness because it is a
   feed, not a bank account.

6. **"How much Redis memory do you need?"**
   Timeline: 200 items x 50 bytes x 100M users = ~1 TB.
   Tweet content: 1KB x 10M hot tweets = ~10 GB.
   Social graph: variable, but followers/following sets for 100M users = ~500 GB.
   Celebrity tweets: 20 items x 1M celebrities = negligible.
   Total: ~2 TB across a Redis cluster (~80 shards at 25 GB each).
