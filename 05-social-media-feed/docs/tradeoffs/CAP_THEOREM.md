# CAP Theorem -- Social Media Feed System

> Interview-ready analysis of consistency, availability, and partition tolerance
> tradeoffs for a Twitter/X-like feed system. Heavy focus on fan-out consistency.

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

## Social Media Feed = AP System

### Why AP?

| Concern | Why Availability Wins |
|---------|----------------------|
| Feed staleness | A tweet appearing 5-10 seconds late is invisible to users |
| Revenue | Feed must always load -- every failed load = lost ad impressions |
| User perception | Users do not notice eventual consistency in feeds |
| Scale | 500M+ users -- strong consistency across all timelines is not feasible |
| Write amplification | Fan-out to millions of timelines is inherently asynchronous |

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

**Interview answer:** "A social media feed is an AP system. Feed staleness of
5-10 seconds is acceptable -- users will not notice if a new tweet appears a few
seconds late. But the feed *must* load every time. Downtime or slow feeds directly
impact engagement metrics and ad revenue."

---

## Consistency Spectrum by Feature

Not everything is equally tolerant of staleness. Here is the full breakdown:

| Feature | Consistency Model | Why |
|---------|------------------|-----|
| Feed timeline | Eventual (seconds) | Fan-out takes time, users do not notice 5s delay |
| Like / retweet counts | Eventual (seconds) | Approximate counts are fine ("12.3K likes") |
| Tweet deletion | Eventual (seconds) | Deleted tweet may appear briefly, then disappear |
| Follow / unfollow | Strong (immediate) | Affects future fan-out -- wrong graph = wrong feed |
| Tweet creation | Strong (write-ack) | User must see their own tweet immediately |
| Trending topics | Eventual (minutes) | Refresh every 1-5 minutes, approximate counts OK |
| User profile | Eventual (minutes) | Profile changes can propagate slowly |
| Search index | Eventual (seconds) | Near-real-time indexing, slight delay OK |

### Where Strong Consistency Matters

```
Follow/Unfollow:
  User A unfollows User B
       |
       v
  Social graph MUST be updated immediately
       |
       v
  Next fan-out from User B must NOT include User A
       |
  (If eventual: User A sees tweets from User B after unfollowing -- bad UX)


Read-Your-Own-Writes:
  User posts a tweet
       |
       v
  User's own timeline MUST show the tweet immediately
       |
  (Even if other followers see it 5 seconds later)
```

---

## Fan-Out Consistency Model

This is where interviewers spend the most time. Three strategies, three
consistency profiles.

### Fan-Out-on-Write (Push)

```
Celebrity posts tweet
       |
       v
  Kafka topic: "tweet.published"
       |
       v
  Fan-out workers (consumer group, partitioned by userId)
       |
       +---> Write to follower-1's timeline (Redis ZADD)    t=0.1s
       +---> Write to follower-2's timeline (Redis ZADD)    t=0.1s
       +---> ...
       +---> Write to follower-N's timeline (Redis ZADD)    t=???
       |
  For 10M followers: fan-out takes minutes
  Follower-1 sees it in 0.1s. Follower-N sees it in minutes.
  Inconsistent window between followers.
```

| Property | Value |
|----------|-------|
| Consistency model | Eventual -- followers see the tweet at different times |
| Delivery guarantee | At-least-once (Kafka consumer offset) |
| Dedup strategy | Dedup by tweetId at read time (set semantics on sorted set) |
| Worst case lag | Minutes for celebrity fan-out (millions of writes) |

### Fan-Out-on-Read (Pull)

```
Follower opens feed
       |
       v
  FeedService pulls tweets from each followed celebrity
       |
       +---> Get @taylorswift's recent tweets      (Cassandra read)
       +---> Get @elonmusk's recent tweets          (Cassandra read)
       +---> Merge with pre-computed timeline
       |
  Tweet is available INSTANTLY (read from source of truth)
  No fan-out lag at all.
```

| Property | Value |
|----------|-------|
| Consistency model | Read-time consistent (always reads latest) |
| Delivery guarantee | N/A -- no async pipeline |
| Tradeoff | Higher read latency (must pull from multiple sources) |
| Best for | Celebrity tweets (avoids writing to millions of timelines) |

### Hybrid (Production)

```
  Tweet Published
       |
       v
  Is poster a celebrity (>= 10K followers)?
       |                    |
      YES                   NO
       |                    |
  Skip fan-out.         Fan-out-on-write
  Store in poster's     to all followers.
  tweet store.          Timeline updated
       |                in < 1 second.
       |                    |
       v                    v
  Pulled at read time   Pre-computed in
  by each follower.     follower's cache.
  Always fresh.         Eventual (< 1s).
```

| User Type | Consistency | Latency (write) | Latency (read) |
|-----------|-------------|-----------------|----------------|
| Normal (< 10K followers) | Eventual, < 1s | O(followers) ZADD | O(1) cache hit |
| Celebrity (>= 10K followers) | Read-time consistent | O(1) just persist tweet | O(celebrities-followed) pulls |

---

## At-Least-Once Delivery and Deduplication

Fan-out via Kafka uses at-least-once delivery. This means a tweet might be
pushed to the same timeline twice (e.g., consumer crash and retry).

```
Kafka Consumer:
  1. Read message: "Push tweet-123 to timeline:user-42"
  2. ZADD timeline:user-42 <score> tweet-123
  3. Commit offset

  If crash between step 2 and 3:
  -> Message redelivered -> ZADD again
  -> But Redis ZADD is idempotent for same member!
  -> No duplicate in the sorted set.
```

**Key insight:** Redis Sorted Set's ZADD is naturally idempotent -- adding the
same member (tweetId) again just updates the score. This makes dedup free.

For the pull path, dedup happens at merge time:

```java
private List<FeedItem> mergeAndDedup(List<FeedItem> pushed, List<FeedItem> pulled) {
    Map<String, FeedItem> seen = new LinkedHashMap<>();
    for (FeedItem item : pushed) {
        seen.putIfAbsent(item.getTweet().getTweetId(), item);
    }
    for (FeedItem item : pulled) {
        seen.putIfAbsent(item.getTweet().getTweetId(), item);
    }
    return new ArrayList<>(seen.values());
}
```

---

## Consistency During Failures

| Failure | Impact | Mitigation |
|---------|--------|------------|
| Kafka broker down | Fan-out stalls -- new tweets not pushed | Feed still works via pull path (degraded) |
| Redis timeline cache lost | Feed miss -- cold start | Rebuild from Cassandra (pull all followed users' recent tweets) |
| Cassandra partition | Cannot read tweet content | Serve from Redis tweet cache (stale but available) |
| Social graph Redis down | Cannot determine followers | Fan-out paused, retry with backoff. Feed still serves cached timeline. |

**Graceful degradation:** Even during failures, the feed loads. It may be
stale or missing recent tweets, but it never shows an error page.

---

## Interview Follow-Up Q&A

### Q1: "How do you ensure a user sees their own tweet immediately?"

**A:** Read-your-own-writes consistency. After posting, the tweet is added to
the user's own timeline cache synchronously (not via Kafka). The async fan-out
to followers happens in parallel. The poster sees it instantly; followers see
it within seconds.

```
User posts tweet
     |
     +---> Synchronous: add to own timeline cache (ZADD)
     |     User immediately sees their tweet.
     |
     +---> Async: publish to Kafka -> fan-out to followers
           Followers see it within seconds.
```

### Q2: "What if a fan-out worker is slow and a follower opens their feed before the tweet arrives?"

**A:** The follower's feed is missing that one tweet. This is eventual consistency
in action -- it will appear on the next refresh. For the hybrid model, celebrity
tweets are pulled at read time, so they are never missing.

### Q3: "Can two followers see different feeds at the same time?"

**A:** Yes, absolutely. Follower A might have the tweet in their cache (fan-out
completed), while follower B does not yet. This is by design -- per-user
eventual consistency. Each user's feed converges to the correct state within
seconds.

### Q4: "What happens to fan-out when a user unfollows someone?"

**A:** The social graph is updated immediately (strong consistency for follow
graph). Future fan-outs from the unfollowed user will not include this follower.
However, tweets already in the follower's timeline cache from before the unfollow
are not removed (they age out naturally via the 200-item cap).

### Q5: "How do you handle tweet deletion consistency?"

**A:** Tweet deletion is eventually consistent.

```
User deletes tweet-123
     |
     +---> Mark as deleted in Cassandra (soft delete, immediate)
     |
     +---> Publish "tweet.deleted" to Kafka
     |     -> Fan-out workers: ZREM tweet-123 from all timelines
     |        (takes time, same as initial fan-out)
     |
     +---> Any feed read checks tweet.isDeleted() and filters it out
           (even before ZREM completes)
```

The `volatile boolean deleted` field on Tweet ensures visibility across threads.
FeedService filters deleted tweets at read time as a safety net.

### Q6: "What consistency model would you use for the trending topics?"

**A:** Eventual consistency with a refresh window of 1-5 minutes. Trending
is inherently approximate -- exact real-time counts are not needed. We use
Redis Sorted Set with ZINCRBY for atomic increments, but the "top 10 trending"
list is computed periodically, not on every tweet.

### Q7: "Why not just use strong consistency everywhere?"

**A:** Cost and latency. Strong consistency for 500M users means:
- Every tweet write must be acknowledged by a quorum across data centers
- Fan-out to millions of timelines must be transactional
- Feed reads must wait for the latest fan-out to complete

This would make tweet posting take seconds instead of milliseconds, and feed
generation would be 10x slower. The marginal improvement in freshness is not
worth the performance and cost penalty.

---

## Summary Table

| Dimension | Choice | Reason |
|-----------|--------|--------|
| Overall system | AP | Feed must always load |
| Fan-out delivery | At-least-once | Kafka semantics, dedup via ZADD idempotency |
| Timeline consistency | Eventual (< 5s) | Fan-out is async |
| Follow graph | Strong | Affects correctness of future fan-out |
| Read-your-own-writes | Guaranteed | Sync write to own timeline |
| Cross-user consistency | Not guaranteed | Follower A and B may see different feeds |
| Trending | Eventual (minutes) | Approximate counts, periodic refresh |
| Tweet deletion | Eventual + soft delete | Soft delete flag + async ZREM from timelines |
