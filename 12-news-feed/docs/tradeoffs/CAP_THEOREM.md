# CAP Theorem -- News Feed System (Facebook/LinkedIn)

> Interview-ready analysis of consistency, availability, and partition tolerance
> tradeoffs for a news feed system. Deeper than Project 05: covers ranking
> consistency, engagement count propagation, real-time push, and how Facebook,
> Twitter, and LinkedIn approach CAP differently.

---

## CAP Recap

| Letter | Property | Meaning |
|--------|----------|---------|
| **C** | Consistency | Every read receives the most recent write |
| **A** | Availability | Every request receives a response (no timeouts) |
| **P** | Partition Tolerance | System continues operating despite network partitions |

In a distributed system, network partitions **will** happen. You must choose:

```
           C
          / \
         /   \
        /     \
      CP       CA  <-- not possible in distributed systems
      /         \
     /           \
    P ----------- A
          AP
```

**You can have CP or AP, never CA in a real distributed system.**

---

## News Feed = AP System (Overall)

### Why AP?

| Concern | Why Availability Wins |
|---------|----------------------|
| Feed staleness | A post appearing 5-10 seconds late is invisible to users |
| Revenue | Feed must always load -- every failed load = lost ad impressions |
| User perception | Users do not notice eventual consistency in feeds |
| Scale | 2B+ users (Facebook) -- strong consistency across all timelines is not feasible |
| Write amplification | Fan-out to millions of timelines is inherently asynchronous |
| Ranking tolerance | Algorithmic ranking is inherently approximate -- exact data is not needed |

### The Core Argument

```
User opens app
     |
     v
Feed loads in < 200ms with slightly stale data   <-- AP: always available
     |
     vs.
     v
Feed blocks for 2s waiting for globally consistent data  <-- CP: sometimes slow
     |
     vs.
     v
Feed fails with "try again later" during partition  <-- unacceptable
```

**Interview answer:** "A news feed is an AP system. Feed staleness of
5-10 seconds is acceptable -- users will not notice if a new post appears
a few seconds late. But the feed *must* load every time. Downtime or slow
feeds directly impact engagement metrics and ad revenue."

---

## Consistency Spectrum by Feature

Not everything is equally tolerant of staleness. Here is the full breakdown:

| Feature | Consistency Model | Staleness Tolerance | Why |
|---------|------------------|--------------------|----|
| Feed timeline | Eventual (seconds) | 5-10s | Fan-out takes time, users do not notice |
| Like/comment counts | Eventual (seconds) | 10-30s | Approximate counts fine ("12.3K likes") |
| Post deletion | Eventual (seconds) | 5-30s | Deleted post may appear briefly |
| Follow/unfollow | **Strong (immediate)** | 0s | Affects future fan-out correctness |
| Post creation | Strong (write-ack) | 0s | Author must see their own post immediately |
| Ranking scores | Eventual (minutes) | 1-5min | Algorithmic ranking is approximate anyway |
| User profile | Eventual (minutes) | 5-15min | Profile changes propagate slowly |
| Search index | Eventual (seconds) | 2-5s | Near-real-time indexing |
| Notification delivery | Eventual (seconds) | 1-5s | Push notification can be slightly delayed |
| Engagement affinity | Eventual (hours) | 1-24h | ML model retrains periodically |

---

## Social Graph: CP -- The Exception

### Why Strong Consistency?

The social graph (follow/unfollow) is the **one place** where strong consistency
is critical. A stale follower list means:

1. Fan-out includes someone who unfollowed (they see unwanted posts)
2. Fan-out excludes someone who just followed (they miss posts)

Both are bad UX and hard to debug.

```
WRONG (eventual consistency on social graph):

  User A unfollows User B at T=0
       |
       v
  Social graph update propagates... (eventual, 5s delay)
       |
  At T=2: User B posts
       |
       v
  Fan-out worker reads STALE graph -> User A is still in follower list
       |
       v
  User A receives User B's post in timeline -- AFTER unfollowing!
  User A: "I unfollowed this person, why am I seeing their posts?"
```

```
CORRECT (strong consistency on social graph):

  User A unfollows User B at T=0
       |
       v
  MULTI
    SREM followers:B A
    SREM following:A B
  EXEC                          <-- Synchronous, atomic
       |
  At T=2: User B posts
       |
       v
  Fan-out worker reads CURRENT graph -> User A NOT in follower list
       |
       v
  User A does NOT receive User B's post. Correct.
```

### Implementation

```
Social graph storage: Redis Sets with MULTI/EXEC for atomicity
  - SADD/SREM operations are synchronous
  - No Kafka async path for follow/unfollow writes
  - Read replicas for follower count queries (SCARD)
  - Master for mutation operations (SADD, SREM)

Fallback (Redis down):
  - PostgreSQL as durable backing store
  - Follow/unfollow writes go to PostgreSQL synchronously
  - Redis repopulated from PostgreSQL on recovery
  - Feed generation degrades but does not break
```

---

## Fan-Out Consistency Model

Three fan-out strategies, three consistency profiles.

### Fan-Out-on-Write (Push)

```
Normal user posts
       |
       v
  Kafka topic: "post.published"
       |
       v
  Fan-out workers (consumer group, partitioned by authorId)
       |
       +---> Write to follower-1's timeline (Redis ZADD)    t=0.1s
       +---> Write to follower-2's timeline (Redis ZADD)    t=0.1s
       +---> ...
       +---> Write to follower-N's timeline (Redis ZADD)    t=???
       |
  For 5000 followers: fan-out takes < 1 second
  For 50K followers: fan-out takes seconds
  Followers see the post at different times.
```

| Property | Value |
|----------|-------|
| Consistency model | Eventual -- followers see the post at different times |
| Delivery guarantee | At-least-once (Kafka consumer offset) |
| Dedup strategy | Redis ZADD is idempotent for same member (postId) |
| Worst case lag | Seconds for normal users, minutes if worker is backed up |

### Fan-Out-on-Read (Pull)

```
Follower opens feed
       |
       v
  FeedService pulls posts from each followed celebrity
       |
       +---> Get @celebrity1's recent posts    (cache or Cassandra read)
       +---> Get @celebrity2's recent posts    (cache or Cassandra read)
       +---> Merge with pre-computed timeline
       |
  Post is available INSTANTLY (read from source of truth)
  No fan-out lag at all.
```

| Property | Value |
|----------|-------|
| Consistency model | Read-time consistent (always reads latest from source) |
| Delivery guarantee | N/A -- no async pipeline |
| Tradeoff | Higher read latency (must pull from multiple celebrity sources) |
| Best for | Celebrity posts (avoids writing to millions of timelines) |

### Hybrid (Production)

```
  Post Published
       |
       v
  Is author a celebrity (>= 10K followers)?
       |                    |
      YES                   NO
       |                    |
  Skip fan-out.         Fan-out-on-write
  Store in author's     to all followers.
  post store + cache.   Timeline updated
       |                in < 1 second.
       |                    |
       v                    v
  Pulled at read time   Pre-computed in
  by each follower.     follower's cache.
  Always fresh.         Eventual (< 1s).
```

| User Type | Consistency | Write Latency | Read Latency |
|-----------|-------------|---------------|--------------|
| Normal (< 10K followers) | Eventual, < 1s | O(followers) ZADD | O(1) cache hit |
| Celebrity (>= 10K followers) | Read-time consistent | O(1) just persist post | O(celebrities-followed) pulls |

---

## Timeline Cache: AP with TTL

### Cache Architecture

```
  User requests feed
       |
       v
  Timeline Cache (Redis Sorted Set)
       |
  HIT? ----YES----> Return cached feed (slightly stale, but fast)
       |                <200ms latency, AP behavior
      MISS
       |
       v
  Rebuild from source:
  1. Pull following list from social graph
  2. Query Cassandra for each followed user's recent posts
  3. Merge, rank, write back to Redis
  4. Return feed
       |
  Cold start latency: 500ms - 2s
  Subsequent reads: < 50ms
```

### TTL Strategy

| Cache | TTL | Reason |
|-------|-----|--------|
| Timeline sorted set | None (evicted by size cap of 1000) | Continuously updated by fan-out |
| Post content cache | 24 hours | Content rarely changes; refreshed on access |
| User profile cache | L1: 1 min, L2: 5 min | Profile changes are infrequent |
| Celebrity post cache | 1 hour | Pulled at read time, refreshed frequently |
| Engagement counts | 5 minutes | Approximate counts, updated async |

### What "Stale" Means for a Feed

```
Scenario: User A follows User B. User B posts at T=0.

  T=0.0s: User B's post saved to Cassandra
  T=0.1s: Kafka message published
  T=0.3s: Fan-out worker pushes to User A's timeline cache (ZADD)
  T=0.3s: User A's cache is now CURRENT

  But if User A opens feed at T=0.2s (before fan-out completes):
  -> User A sees STALE feed (missing User B's latest post)
  -> On next refresh (pull-to-refresh or 30s auto-refresh): post appears

  User impact: NONE -- users do not notice a 0.3 second delay
```

---

## Like/Comment Counts: Eventual Consistency

### Why Approximate Counts Are Fine

```
Facebook displays: "12.3K likes"    NOT: "12,347 likes"

Users see approximate numbers. The difference between 12,300 and 12,347
is invisible. This means:
  - Counts can be eventually consistent
  - Different users may see different counts at the same time
  - Counts converge within seconds
```

### Count Update Flow

```
User likes post-123
     |
     v
1. Write to EngagementRepository (durable)
     |
     v
2. Publish event to Kafka: "post.liked"
     |
     +---> Consumer: HINCRBY engagement:post-123 likes 1 (Redis)
     |     -> Redis count updated within 1 second
     |
     +---> Consumer: Update affinity(user, author) score
     |     -> Used by AlgorithmicRankingStrategy
     |
  Post.likeCount in Cassandra: updated eventually
  Post.likeCount in Redis cache: updated within 1s
  Post.likeCount in client's cached feed: stale until next render
```

### Counter Inconsistencies That Are OK

| Scenario | Impact | Acceptable? |
|----------|--------|-------------|
| User A sees 12.3K likes, User B sees 12.2K likes | Different Redis replicas | Yes -- converges in seconds |
| Like count goes 100 -> 101 -> 100 -> 101 | Race between increment and cache refresh | Yes -- brief flicker |
| User likes, refreshes, count unchanged | Cache has not been updated yet | Yes -- count updates on next refresh |
| Like count shows 0 for 1 second after first like | Async pipeline delay | Yes -- not noticeable |

### Where Exact Counts DO Matter

```
Engagement counts: approximate OK (feed display)
Notification counts: approximate OK ("5 new notifications")
Follower counts: approximate OK (profile display)

But:
  - Billing/monetization: EXACT (e.g., ad impression counting)
  - Rate limiting: EXACT (cannot allow extra API calls)
  - Voting/polls: EXACT (each user votes once, final count must be accurate)
```

---

## Consistency During Failures

### Failure Modes and Degradation

| Failure | Impact | Mitigation | Consistency Impact |
|---------|--------|------------|--------------------|
| Kafka broker down | Fan-out stalls | Feed still works via cache + pull path | Stale feeds, no new posts until recovery |
| Redis timeline cache lost | Feed cache miss | Cold-start rebuild from Cassandra | Slow but correct |
| Cassandra partition | Cannot read post content | Serve from Redis post cache (stale) | Stale content, but available |
| Social graph Redis down | Cannot determine followers | Fan-out paused, retry with backoff | Feed serves stale cache |
| Ranking service down | Cannot score feed | Fall back to chronological | Suboptimal but functional |
| Notification service down | No push notifications | Events queued in Kafka, delivered on recovery | Delayed notifications |

### Graceful Degradation Flow

```
Full system healthy:
  Feed = timeline cache (pushed) + celebrity pull + algorithmic ranking + cursor pagination
  Latency: < 200ms

Kafka down:
  Feed = STALE timeline cache + celebrity pull + algorithmic ranking
  New posts not fanning out, but feed still loads
  Latency: < 200ms (cache still warm from before failure)

Redis timeline cache down:
  Feed = cold-start from Cassandra + celebrity pull + ranking
  Latency: 500ms - 2s (acceptable for degraded mode)

Both Kafka + Redis down:
  Feed = direct Cassandra reads for all followed users
  Latency: 2-5s (very degraded, but still returns a feed)
  Users see: "Loading..." spinner for a few seconds, then feed appears

Cassandra down:
  Feed = whatever is in Redis cache (may be stale)
  New posts cannot be persisted (rejected with 503)
  Latency: < 200ms for reads (cache-only mode)
```

**Key principle:** The feed ALWAYS loads. It may be stale, slow, or missing
recent items, but it never shows an error page. This is AP in action.

---

## Facebook vs. Twitter vs. LinkedIn Approaches

### Facebook

```
Strategy: Primarily fan-out-on-write + heavy algorithmic ranking

  - 2B+ users, average user has ~200 friends
  - Fan-out-on-write is feasible for most users (friend count is bounded)
  - Pages/celebrities: hybrid (fan-out-on-read for pages with millions of followers)
  - Ranking: deep ML model (EdgeRank successor)
    - Affinity (how often you interact with the author)
    - Weight (post type: video > photo > text)
    - Time decay (exponential)
  - Consistency: AP for feed, CP for friend list
  - Real-time: "X new posts" notification banner, not auto-refresh
  - Unique: "Meaningful Social Interactions" -- prioritizes comments over likes
```

| Aspect | Facebook Approach |
|--------|------------------|
| Fan-out | Write for friends, read for pages/celebrities |
| Ranking | Deep ML (thousands of features) |
| Consistency | AP (feed), CP (friend list) |
| Real-time | Banner notification, not live stream |
| Cache | Timeline aggregator per user, multi-tier |
| Celebrity threshold | Dynamic, per-page (based on engagement rate) |

### Twitter/X

```
Strategy: Hybrid fan-out + dual feed (For You / Following)

  - 500M+ users, highly asymmetric follow graph (celebrities >> friends)
  - Following tab: chronological, fan-out-on-write for normal, pull for celebrities
  - For You tab: algorithmic, ML-based ranking
  - Consistency: AP for timeline, CP for follow graph
  - Real-time: "X new tweets" counter, auto-refresh optional
  - Unique: The celebrity problem is extreme (some accounts have 100M+ followers)
```

| Aspect | Twitter Approach |
|--------|-----------------|
| Fan-out | Hybrid with 10K threshold |
| Ranking | Dual feed: chrono + algorithmic |
| Consistency | AP (timeline), CP (follow graph) |
| Real-time | "X new tweets" counter |
| Cache | Redis sorted set per user, 200-800 items |
| Celebrity threshold | ~10K followers (public knowledge from old blog posts) |

### LinkedIn

```
Strategy: Fan-out-on-write + professional network ranking

  - 900M+ users, smaller active feed audience
  - Network is more symmetric (connections vs. followers)
  - Ranking: engagement + professional relevance + network distance
    - 1st-degree connection posts ranked higher
    - "Trending in your industry" injected
    - Job-related content boosted
  - Consistency: AP for feed, CP for connection graph
  - Real-time: notification badge, not live feed update
  - Unique: Content is less time-sensitive (professional posts, not breaking news)
```

| Aspect | LinkedIn Approach |
|--------|------------------|
| Fan-out | Primarily write (smaller network than Twitter) |
| Ranking | Professional relevance + engagement + network distance |
| Consistency | AP (feed), CP (connection graph) |
| Real-time | Notification badge |
| Cache | Feed cache with longer TTL (content less time-sensitive) |
| Celebrity threshold | "Influencer" mode (LinkedIn Influencer program) |

### Comparison Matrix

| Dimension | Facebook | Twitter/X | LinkedIn |
|-----------|---------|-----------|----------|
| Graph type | Symmetric (friends) | Asymmetric (follow) | Semi-symmetric (connections + follow) |
| Celebrity severity | Moderate (pages) | Extreme (100M+ followers) | Low (influencer program) |
| Content velocity | High | Very high (breaking news) | Moderate |
| Staleness tolerance | 5-10s | 1-5s (real-time expectation) | 30-60s (professional content) |
| Ranking complexity | Very high (1000s features) | High (dual feed) | Moderate (professional + engagement) |
| Fan-out model | Write + read hybrid | Write + read hybrid | Primarily write |
| CAP choice | AP | AP | AP |
| Real-time push | Banner | Counter + optional auto-refresh | Badge |

---

## At-Least-Once Delivery and Deduplication

Fan-out via Kafka uses at-least-once delivery. A post might be pushed to
the same timeline twice (e.g., consumer crash and retry).

```
Kafka Consumer:
  1. Read message: "Push post-123 to timeline:user-42"
  2. ZADD timeline:user-42 <score> post-123
  3. Commit offset

  If crash between step 2 and 3:
  -> Message redelivered -> ZADD again
  -> But Redis ZADD is idempotent for same member!
  -> No duplicate in the sorted set.
```

**Key insight:** Redis Sorted Set's ZADD is naturally idempotent -- adding the
same member (postId) again just updates the score. This makes dedup free.

For the pull path, dedup happens at merge time:

```java
private List<FeedItem> mergeAndDedup(List<FeedItem> pushed, List<FeedItem> pulled) {
    Map<String, FeedItem> seen = new LinkedHashMap<>();
    for (FeedItem item : pushed) {
        seen.putIfAbsent(item.getPost().getPostId(), item);
    }
    for (FeedItem item : pulled) {
        seen.putIfAbsent(item.getPost().getPostId(), item);
    }
    return new ArrayList<>(seen.values());
}
```

---

## Read-Your-Own-Writes Consistency

### The Problem

```
User posts "Hello world!" at T=0
  |
  v
Post saved to Cassandra (durable)
  |
  v
Kafka event published for fan-out
  |
  v
User refreshes their own feed at T=0.1s
  |
  v
Fan-out has NOT completed yet (takes ~0.3s)
  |
  v
User's timeline cache does NOT have the new post!
  |
  v
User: "Where is my post? Did it fail?"
```

### The Solution

```
User posts "Hello world!" at T=0
  |
  +---> Synchronous: add to OWN timeline cache (ZADD)
  |     User immediately sees their post.
  |
  +---> Async: publish to Kafka -> fan-out to followers
  |     Followers see it within seconds.
  |
  Result: author always sees their own post instantly.
  Followers see it within 1-5 seconds (eventual).
```

### Implementation

```java
public void publishPost(Post post, User author) {
    // 1. Persist (durable)
    postRepository.save(post);

    // 2. Add to author's OWN timeline synchronously (read-your-own-writes)
    timelineRepository.addToTimeline(
            author.getUserId(),
            post.getPostId(),
            post.getCreatedAt().toEpochMilli());

    // 3. Async fan-out to followers (eventual consistency)
    kafkaTemplate.send("post.published", author.getUserId(),
            serialize(post));
}
```

---

## Consistency of Ranking Scores

### Why Ranking Can Be Eventually Consistent

```
AlgorithmicRankingStrategy computes:
  score = affinity * recency * engagement * contentTypeWeight

Each factor has different staleness tolerance:

  affinity (how often user interacts with author):
    Updated hourly or daily by ML pipeline
    Stale for hours -- FINE (interaction patterns change slowly)

  recency (time decay):
    Computed at read time from post.createdAt
    Always EXACT (pure computation, no external data)

  engagement (likes + comments + shares):
    Updated via async Kafka consumers
    Stale for seconds -- FINE (approximate counts)

  contentTypeWeight (VIDEO=1.5, IMAGE=1.3, etc.):
    Static per post, never stale
    Always EXACT
```

### Impact on Feed Quality

```
Scenario: Post X has 100 likes in reality, but cache shows 95 likes.

  With 95 likes:  score = 0.8 * 0.9 * (95*1 + 10*2 + 5*3) * 1.3 = 156.0
  With 100 likes: score = 0.8 * 0.9 * (100*1 + 10*2 + 5*3) * 1.3 = 160.7

  Difference: 3% -- this does NOT change the ranking order in practice.
  Post X still appears in roughly the same position.
```

**Interview answer:** "Ranking scores are eventually consistent because the
input factors (engagement, affinity) are approximate anyway. A 5% error
in like count produces a <3% change in final score, which almost never
changes the ordering. Recency is always exact (computed from timestamp).
The ML affinity model retrains on a daily cadence -- stale for hours by design."

---

## Post Deletion Consistency

```
User deletes post-123
     |
     +---> Mark as deleted in Cassandra (soft delete, immediate)
     |     volatile boolean deleted = true
     |
     +---> Publish "post.deleted" to Kafka
     |     -> Fan-out workers: ZREM post-123 from all follower timelines
     |        (takes time, same as initial fan-out)
     |
     +---> DEL post:post-123 from Redis post cache
     |
     +---> Any feed read checks post.isDeleted() and filters it out
           (safety net, even before ZREM completes)
```

### Deletion Timeline

```
T=0.0s: Author deletes post-123
T=0.0s: Cassandra soft delete (deleted=true)
T=0.1s: Kafka event published
T=0.1s: Redis post cache deleted (DEL post:post-123)
T=0.3s: Fan-out workers start removing from follower timelines
T=1.0s: Most follower timelines cleaned up (ZREM)
T=5.0s: All follower timelines cleaned up

Between T=0 and T=5s:
  - Some followers may still see post-123 in their timeline cache
  - But FeedService.hydrate() checks isDeleted() flag -> filters it out
  - So even with stale cache, deleted posts do not appear in feed
```

---

## Interview Follow-Up Q&A

### Q1: "Why not just use strong consistency everywhere?"

**A:** Cost and latency. Strong consistency for 2B users means:
- Every post write must be acknowledged by a quorum across data centers
- Fan-out to millions of timelines must be transactional
- Feed reads must wait for the latest fan-out to complete

This would make post publishing take seconds instead of milliseconds, and feed
generation would be 10x slower. The marginal improvement in freshness is not
worth the performance and cost penalty.

### Q2: "How do you handle a partition between data centers?"

**A:** AP behavior -- each data center serves feeds independently using local
caches and replicas. When the partition heals, fan-out events that were queued
in Kafka are processed and timelines converge. Users in different data centers
may see slightly different feeds during a partition -- this is acceptable.

### Q3: "What if a user unfollows during a partition?"

**A:** This is the one place we accept potential unavailability over inconsistency.
The unfollow write goes to the social graph master. If the master is unreachable,
the unfollow is queued (or retried client-side). We do NOT process it on a replica
because a stale follower list causes incorrect fan-out.

### Q4: "Can you guarantee exactly-once delivery for fan-out?"

**A:** No. Kafka provides at-least-once. We rely on Redis ZADD idempotency for
dedup. If the same postId is pushed twice, the sorted set simply updates the
score -- no duplicate entry. For engagement counts, HINCRBY is NOT idempotent,
so we use a dedup key with TTL: `SETNX dedup:like:{userId}:{postId} 1 EX 300`.

### Q5: "How does real-time push interact with eventual consistency?"

**A:** WebSocket/SSE push notifications are sent when a post is published.
The notification arrives before the post is in the follower's timeline cache
(fan-out has not completed yet). On the client, receiving the push triggers
a feed refresh, and the pull path picks up the new post even if the push
path has not completed. This masks the eventual consistency delay.

---

## Summary Table

| Dimension | Choice | Reason |
|-----------|--------|--------|
| Overall system | AP | Feed must always load |
| Fan-out delivery | At-least-once | Kafka semantics, dedup via ZADD idempotency |
| Timeline consistency | Eventual (< 5s) | Fan-out is async |
| Follow graph | **Strong** | Affects correctness of future fan-out |
| Read-your-own-writes | Guaranteed | Sync write to own timeline |
| Cross-user consistency | Not guaranteed | User A and B may see different feeds |
| Like/comment counts | Eventual (seconds) | Approximate counts, async pipeline |
| Ranking scores | Eventual (minutes-hours) | Inputs are approximate by design |
| Post deletion | Eventual + soft delete | Soft delete flag + async ZREM from timelines |
| Engagement affinity | Eventual (hours) | ML model retrains periodically |
| Notification delivery | Eventual (seconds) | Async push via WebSocket/SSE |
