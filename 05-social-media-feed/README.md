# Social Media Feed System (Twitter/X-like)

## Problem Summary

Design a social media feed system where users can post tweets, follow other users, and view a personalized timeline. The core challenge is **fan-out**: efficiently distributing 500M daily tweets to billions of feed reads while handling the celebrity problem (users with millions of followers).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Scale**: 300M MAU, 500M tweets/day, 3B feed reads/day, 600:1 read-write ratio
- **THE FAN-OUT PROBLEM**: Normal users (< 10K followers) --> **push** (fan-out on write). Celebrities (> 10K followers) --> **pull** (fan-out on read). **HYBRID = combine both.**
- **Why hybrid**: Celebrity with 50M followers --> 50M Redis writes per tweet is too expensive. Instead, store tweet once, pull at read time, merge into feed.
- **Feed generation**: pre-computed cache (pushed tweets) + pull celebrity tweets + merge by timestamp + apply ranking
- **Timeline cache**: Redis Sorted Set per user, max 200 items, scored by timestamp
- **Trending**: Hashtag counting with **velocity-based** scoring (detect spikes, not just volume). `velocity = count_last_5min / count_last_hour`. High velocity = trending.
- **Ranking**: chronological --> engagement-based: `score = (likes x 1 + retweets x 2 + replies x 1.5) x time_decay(age_hours)`
- **DB choices**: Redis (timeline + trending + social graph), Cassandra/DynamoDB (tweets), PostgreSQL (users), Elasticsearch (search)
- **CAP**: AP system -- stale feed for 5-10 seconds is acceptable, availability is non-negotiable

---

## Architecture Summary

```
┌──────────┐     ┌──────────────┐     ┌───────────┐     ┌──────────────────────┐
│  Client  │────>│  API Gateway │────>│  Tweet    │────>│  Kafka               │
│  (App)   │     │  (Auth+Rate) │     │  Service  │     │  topic: new-tweets   │
└──────────┘     └──────┬───────┘     └───────────┘     └──────┬───────────────┘
                        │                                      │
                        │                          ┌───────────┼───────────┐
                        │                          │           │           │
                        │                    ┌─────▼───┐ ┌─────▼───┐ ┌────▼──────┐
                        │                    │ Fan-out │ │Trending │ │  Search   │
                        │                    │ Workers │ │ Workers │ │  Indexer  │
                        │                    └────┬────┘ └────┬────┘ └────┬──────┘
                        │                         │           │           │
                  ┌─────▼──────┐            ┌─────▼───────────▼───────────▼──────┐
                  │   Feed     │───────────>│              Redis Cluster         │
                  │  Service   │            │  timeline:{userId}  ZSET (feeds)   │
                  │            │            │  trending:global    ZSET (topics)  │
                  │ 1. Read    │            │  followers:{userId} SET  (graph)   │
                  │    cache   │            └───────────────────────────────────┘
                  │ 2. Pull    │
                  │    celeb   │            ┌───────────────────────────────────┐
                  │ 3. Merge   │───────────>│  Cassandra / DynamoDB (tweets)    │
                  │ 4. Rank    │            └───────────────────────────────────┘
                  └────────────┘
```

---

## The Celebrity Problem -- Quick Reference

### Three Approaches Compared

| Approach | How It Works | Pros | Cons | Use When |
|----------|-------------|------|------|----------|
| **Fan-out on Write** | On tweet, push to ALL followers' caches | Fast reads (O(1)), simple | Celebrity = 50M writes per tweet, massive write amplification | All users have < 10K followers |
| **Fan-out on Read** | On feed request, pull from all followees | No write cost, always fresh | Slow reads (pull from N sources), high read latency | Heavy-write, few-read systems |
| **Hybrid** (Twitter's approach) | Push for normal users, pull for celebrities, merge at read | Balances write/read cost, scalable | More complex merge logic | Production systems at scale |

### Hybrid Feed Generation Flow

```
Step 1: User opens feed
Step 2: Feed Service reads pre-computed timeline from Redis ZSET
        (contains tweets pushed by normal followees)
Step 3: Feed Service identifies celebrity followees (from user's follow list)
Step 4: Pull latest tweets from each celebrity (cached in Redis HASH)
Step 5: Merge pre-computed + celebrity tweets, sort by rank score, return top N
```

### Celebrity Detection

- **Static threshold**: follower_count > 10,000 --> mark as celebrity
- **Dynamic detection**: monitor fan-out lag per user; if fan-out takes > 2s, promote to celebrity tier
- **Demotion**: if follower count drops below threshold, demote back to normal

---

## Key Tradeoffs

| Decision | Option A | Option B | Recommendation |
|----------|----------|----------|----------------|
| Fan-out strategy | Write (push) | Read (pull) | **Hybrid** -- push for normal, pull for celebrities |
| Feed ranking | Chronological | Engagement-based | **Engagement** -- `(likes + 2*RT + 1.5*replies) * time_decay` |
| Timeline data structure | Redis LIST | Redis Sorted Set | **ZSET** -- allows score-based ordering, easy range queries |
| Feed generation | Pre-compute all | On-demand always | **Pre-compute + pull celebrities** at read time |
| Celebrity pull scope | All celebrities followed | Top-N most recent | **Top-N** (e.g., 50) -- bound the merge cost |
| Consistency model | Strong consistency | Eventual consistency | **Eventual** -- 5-10s stale is acceptable for feeds |
| Tweet storage | SQL (PostgreSQL) | NoSQL (Cassandra) | **Cassandra** -- write-heavy, time-series, no joins needed |
| Social graph storage | Graph DB (Neptune) | Redis Sets | **Redis Sets** -- fast lookups, good enough for follow/unfollow |

---

## Edge Cases Quick Reference

- **Tweet deletion**: Remove tweet ID from author's tweet list. Fan-out delete to cached timelines (best-effort). Stale entries filtered at read time (tweet lookup returns null --> skip).
- **Unfollow**: Remove from social graph immediately. Lazy cleanup of timeline cache (old tweets from unfollowed user expire naturally via ZSET trim).
- **Celebrity follows celebrity**: Both are pull-based. Feed merge handles it -- no special logic needed.
- **Normal user goes viral**: Monitor fan-out lag. If a user's tweet causes lag spike, promote to celebrity tier dynamically. Re-queue pending fan-out as a pull instead.
- **Thundering herd on celebrity tweet**: Cache celebrity tweets in Redis HASH with short TTL. All feed requests pull from cache, not from DB. Use request coalescing (singleflight pattern).
- **New user cold start**: No pre-computed timeline. Seed with trending tweets + tweets from suggested follows. Build cache progressively as user follows people.
- **Duplicate tweets in feed**: Dedup at read time using tweet ID set. Can happen if fan-out worker retries after partial success.
- **Timeline cache miss**: User has no cached timeline (inactive user). Rebuild from scratch: query followees' recent tweets, merge, cache, return.
- **Out-of-order delivery**: Kafka partitions guarantee per-user ordering. Cross-user ordering is best-effort (sorted set score = timestamp handles it).
- **Rate limiting / spam**: Rate limit tweet creation (e.g., 300 tweets/day). Spam tweets excluded from fan-out pipeline.

---

## Design Patterns

| Pattern | Where Used | Why |
|---------|-----------|-----|
| **Strategy** | `FanOutStrategy` -- `PushFanOutStrategy` vs `PullFanOutStrategy` | Swap fan-out behavior based on user type (normal vs celebrity) |
| **Strategy** | `RankingStrategy` -- `ChronologicalRanking` vs `EngagementRanking` | Swap ranking algorithm without changing feed generation |
| **Composite** | `HybridFeedGenerator` composes push + pull results | Merges two feed sources into unified timeline |
| **Builder** | `FeedRequestBuilder` -- builds feed query with filters, pagination, ranking | Complex request object with many optional parameters |
| **Observer** | `TweetPublisher` notifies fan-out, trending, search subscribers | Decouple tweet creation from downstream processing |
| **Repository** | `TimelineRepository`, `TweetRepository` | Abstract data access, swap Redis/Cassandra implementations |
| **Factory** | `FanOutStrategyFactory` -- creates strategy based on follower count | Encapsulate celebrity detection logic |
| **Mediator** | `FeedMediator` coordinates between timeline cache, celebrity cache, ranker | Orchestrate multi-step feed generation without tight coupling |

---

## CAP Summary

```
Choice: AP (Availability + Partition Tolerance)

Why:
- Feed reads MUST be available (user sees empty feed = unacceptable UX)
- Stale feed for 5-10 seconds is tolerable (eventual consistency)
- Tweet writes are AP too -- if a partition occurs, accept writes and reconcile later
- Trending can lag by minutes without user noticing

What we sacrifice:
- Consistency: A follower might not see a tweet for a few seconds after posting
- Dedup: Rare duplicates possible, handled at read time
- Ordering: Near-real-time, not exactly real-time
```

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Timeline Cache | Redis (Sorted Sets) | Pre-computed feeds, O(log N) insert, O(log N + M) range query |
| Tweet Storage | Cassandra / DynamoDB | Write-heavy, time-series partitioned by user_id |
| User DB | PostgreSQL | User profiles, auth, small relational data |
| Social Graph | Redis (Sets) | SADD/SREM for follow/unfollow, SMEMBERS for follower list |
| Message Queue | Kafka | Fan-out pipeline, partitioned by user_id for ordering |
| Search | Elasticsearch | Full-text tweet search, hashtag indexing |
| Trending | Redis (Sorted Set) | ZINCRBY for counting, ZRANGEBYSCORE for velocity queries |
| Media Storage | S3 / Cloud Storage | Images, videos, with CDN for delivery |
| API Gateway | Kong / AWS API Gateway | Rate limiting, auth, routing |
| Monitoring | Prometheus + Grafana | Fan-out lag, feed latency, cache hit rate |

---

## Common Interview Follow-Up Questions

### 1. What is fan-out on write vs read? When to use which?
Fan-out on write pushes tweets to followers' caches at write time (fast reads, expensive writes). Fan-out on read pulls from followees at read time (cheap writes, expensive reads). Use write for low-follower-count users, read for celebrities.

### 2. How to handle celebrities with 50M followers?
Do NOT fan-out their tweets. Store once in a celebrity tweet cache. Pull at feed-read time and merge with pre-computed timeline. This avoids 50M Redis writes per tweet.

### 3. What is the hybrid approach and why does Twitter use it?
Combine push (for 99% of users with < 10K followers) and pull (for 1% celebrities). Twitter uses it because pure push is unscalable for celebrities, and pure pull is too slow for reads.

### 4. How to generate a user's feed in < 500ms?
Pre-computed timeline in Redis (1 ZRANGEBYSCORE call, < 5ms). Pull from ~50 celebrity caches in parallel (< 50ms). Merge and rank in-memory (< 10ms). Total well under 500ms.

### 5. How to implement trending topics?
Kafka consumer increments hashtag counts in Redis via ZINCRBY. Every 5 minutes, a worker computes velocity: `count_last_5min / count_last_hour`. High velocity = trending. Filter out spam and low-quality hashtags.

### 6. What happens when a normal user goes viral?
Monitor fan-out lag per tweet. If lag exceeds threshold (e.g., 2 seconds), dynamically promote user to celebrity tier. Switch remaining fan-out to pull-based. Demote after activity normalizes.

### 7. How to handle tweet deletion in pre-computed feeds?
Best-effort fan-out of delete events. At read time, look up each tweet ID -- if deleted (null), skip it. Eventual consistency: deleted tweet may appear for a few seconds.

### 8. How to rank feeds beyond chronological?
Engagement score: `(likes x 1 + retweets x 2 + replies x 1.5) x time_decay(age_hours)`. Time decay: `1 / (1 + age_hours * 0.1)`. Store score in ZSET. Re-rank periodically or on read.

### 9. How to handle unfollow -- remove from cached timeline?
Do NOT eagerly remove old tweets from cache (expensive scan). Instead, let them expire naturally via ZSET trim (keep only top 200). Immediate effect: stop future fan-out from that user.

### 10. What data structure for timeline cache and why?
Redis Sorted Set. Score = timestamp (or rank score). ZADD for insert, ZRANGEBYSCORE for feed retrieval, ZREMRANGEBYRANK for trim. O(log N) operations. Better than LIST because supports score-based ordering.

### 11. How to detect trending -- velocity vs volume?
Volume alone promotes permanently popular topics. Velocity detects spikes: a hashtag going from 100 to 10,000 in 5 minutes is trending, even if another has 1M total. Formula: `velocity = recent_count / baseline_count`.

### 12. How to handle new user cold start?
No pre-computed timeline exists. Seed with: (a) trending tweets, (b) tweets from suggested follows based on interests, (c) popular tweets in user's region. Build real cache as user follows people.

### 13. How to scale fan-out to 100B operations/day?
Kafka partitioning by user_id. Horizontal scaling of fan-out workers (ECS tasks). Redis Cluster with 20+ shards. Batch writes (pipeline multiple ZADD commands). Hybrid approach eliminates 2.5T celebrity ops.

### 14. How to prevent celebrity tweet thundering herd?
Cache celebrity's latest tweets in Redis HASH with TTL. All feed reads pull from this cache. Use request coalescing (singleflight): if 1000 requests ask for the same celebrity tweets simultaneously, only 1 hits the DB.

### 15. What metrics to monitor for feed health?
Fan-out lag (time from tweet to cache write), feed latency p50/p99, cache hit rate (target > 99%), timeline staleness, Kafka consumer lag, Redis memory usage, fan-out worker throughput, error rate on feed reads.

---

## How to Run

```bash
cd 05-social-media-feed && ../gradlew run
```

---

## What to Improve Later

- **ML-based ranking**: Replace formula-based ranking with a trained model (features: user engagement history, content similarity, social proximity)
- **Graph-based recommendations**: "Users who follow X also follow Y" using graph traversal on social graph
- **Real-time notifications**: WebSocket push for new tweets from close friends (small subset)
- **Geo-based trending**: Regional trending topics, not just global
- **Content moderation pipeline**: Filter harmful content before fan-out
- **A/B testing framework**: Test ranking algorithms on user segments
- **Read-your-own-writes**: After posting, immediately show own tweet in feed (client-side injection)
- **Tiered caching**: L1 (local in-memory) -> L2 (Redis) -> L3 (Cassandra) for hot timelines
