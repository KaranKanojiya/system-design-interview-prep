# Interview Walkthrough -- Social Media Feed System

> **Total time: ~30 minutes. The fan-out discussion is 40% of this interview.**
> This is one of the hardest system design problems because the naive approach breaks at scale.

---

## Phase 1: Clarify Requirements (2 min)

### Functional
- "Can users post tweets (text + media), follow other users, and view a personalized feed?"
- "Do we need trending topics, search, likes, retweets?"
- "Is the feed chronological or ranked?"

### Non-Functional
- "What scale? I'll assume Twitter-scale: 300M MAU."
- "Feed latency target? I'll target < 500ms p99."
- "Consistency model? I'll assume eventual consistency is fine for feeds."

### Clarified Scope
```
In scope:  Post tweet, view feed, follow/unfollow, trending, feed ranking
Out of scope: DMs, notifications, ads, media upload pipeline (mention only)
```

---

## Phase 2: Traffic Estimation (2-3 min)

```
MAU:                300M
DAU:                150M (50% of MAU)
Tweets/day:         500M (~3.3 tweets per active user)
Feed reads/day:     3B (~20 reads per active user)
Read:Write ratio:   3B / 500M = 6:1 on API, but...

THE BIG NUMBER:
Avg followers:      200 (median -- power law distribution)
Fan-out ops/day:    500M tweets x 200 followers = 100 BILLION cache writes/day
Fan-out ops/sec:    100B / 86400 = ~1.16M writes/sec sustained

Celebrity math:
  1 celebrity tweet x 50M followers = 50M writes
  That single tweet = 50 seconds of fan-out at 1M ops/sec
  THIS is why pure fan-out-on-write breaks.

Storage:
  Tweet size: ~300 bytes (140 chars + metadata)
  500M x 300B = 150 GB/day = 55 TB/year
  Timeline cache: 300M users x 200 items x 60B = ~3.6 TB in Redis
```

> **Say this**: "The key insight is that 100B fan-out operations per day is the dominant scaling challenge, not tweet storage or read throughput."

---

## Phase 3: API Design (2 min)

```
POST   /api/v1/tweets
Body:  { "content": "...", "media_ids": [...] }
Return: { "tweet_id": "..." }

GET    /api/v1/feed?cursor=<timestamp>&limit=20
Return: { "tweets": [...], "next_cursor": "..." }
Note:  Cursor-based pagination (NOT offset-based -- offset breaks with real-time inserts)

POST   /api/v1/users/{userId}/follow
DELETE /api/v1/users/{userId}/follow

GET    /api/v1/trending?region=global
Return: { "topics": [{ "hashtag": "#...", "tweet_count": N, "velocity": V }] }
```

> **Key point**: Cursor-based pagination using timestamp. Offset-based breaks when new tweets are inserted (items shift).

---

## Phase 4: High-Level Architecture (3-4 min)

### Draw This on the Whiteboard

```
Client --> API Gateway --> Tweet Service --> Kafka --> Fan-out Workers --> Redis (timelines)
                      --> Feed Service  --> Redis (read) + Celebrity Cache --> Merge --> Rank --> Return
                      --> Search Service --> Elasticsearch
```

### Components to Name

| Component | Role |
|-----------|------|
| Tweet Service | Accept tweets, write to DB, publish to Kafka |
| Fan-out Workers | Consume from Kafka, push tweet IDs to followers' Redis ZSETs |
| Feed Service | Read pre-computed timeline, pull celebrity tweets, merge, rank |
| Trending Workers | Count hashtags, compute velocity scores |
| Social Graph | Redis Sets storing follow relationships |

> **Transition**: "Now let me discuss the most important part -- how fan-out actually works, because the naive approach doesn't scale."

---

## Phase 5: THE FAN-OUT PROBLEM (8-10 min)

**This is the core of the interview. Spend the most time here.**

### Opening Statement

> "The main challenge in a Twitter-like system is how to distribute tweets to followers' feeds. There are three approaches, and the right answer is a hybrid."

### Approach 1: Fan-out on Write (Push)

```
User tweets --> Fan-out worker reads follower list --> ZADD to each follower's timeline ZSET

Pros: Feed read is O(1) -- just ZRANGEBYSCORE on cached timeline
Cons: Celebrity with 50M followers = 50M writes per tweet
      Write amplification is enormous at scale
```

### Approach 2: Fan-out on Read (Pull)

```
User opens feed --> Feed service queries all N followees' tweet lists --> Merge --> Return

Pros: Zero write cost, always fresh
Cons: If user follows 500 people, that's 500 queries per feed read
      3B feed reads/day x 500 queries = 1.5T read ops/day -- worse!
```

### Approach 3: Hybrid (What Twitter Actually Does)

```
Normal users (< 10K followers):  Fan-out on WRITE (push to followers' caches)
Celebrities (> 10K followers):   Fan-out on READ  (pull at feed-read time)

Feed generation at read time:
  1. Read pre-computed timeline from Redis ZSET (pushed tweets)
  2. Get list of celebrity followees for this user
  3. Fetch latest tweets from each celebrity (from celebrity tweet cache)
  4. Merge both lists by score (timestamp or rank)
  5. Return top N items with cursor for pagination

Why this works:
  - 99% of users have < 10K followers --> push is cheap and fast
  - 1% of users are celebrities --> pull 20-50 celebrity feeds is fast
  - Avoids 50M writes per celebrity tweet
  - Feed read is still fast: 1 cache read + ~50 parallel cache reads + merge
```

### Hybrid Flow Diagram

```
Tweet posted by @normal_user (500 followers):
  Tweet --> Kafka --> Fan-out worker --> ZADD to 500 followers' ZSETs  [PUSH]

Tweet posted by @celebrity (50M followers):
  Tweet --> Kafka --> Store in celebrity tweet cache (HASH)            [STORE ONLY]
  (No fan-out. Zero writes to followers' caches.)

User opens feed:
  Step 1: ZRANGEBYSCORE timeline:{userId} --> [pushed tweets]
  Step 2: SMEMBERS celebrity_followees:{userId} --> [@celeb1, @celeb2, ...]
  Step 3: For each celebrity: HGETALL celeb_tweets:{celebId} --> [recent tweets]
  Step 4: Merge all tweets, sort by rank score
  Step 5: Return top 20, set cursor = last tweet's timestamp
```

### Celebrity Detection

```
Static:   follower_count > 10,000 --> celebrity flag in user profile
Dynamic:  Monitor fan-out lag per user
          If fan-out for a single tweet takes > 2 seconds --> promote to celebrity
          Prevents "surprise viral user" from overwhelming fan-out workers
Demotion: If follower count drops below 8,000 (with hysteresis) --> demote back
```

### Edge Cases in Fan-out (Mention These Proactively)

- **Celebrity follows celebrity**: Both are pull-based. No conflict. Merge handles it.
- **Normal user goes viral**: Dynamic promotion to celebrity tier. Re-queue pending fan-out.
- **Thundering herd**: Celebrity tweets, 1M users request feed simultaneously. Solution: cache celebrity tweets with short TTL, request coalescing (singleflight pattern).
- **Celebrity tweets during peak**: Kafka absorbs the burst. Fan-out workers auto-scale on consumer lag.

---

## Phase 6: Feed Ranking (2-3 min)

### Evolution

```
V1: Chronological (sort by timestamp)
V2: Engagement-based (score formula)
V3: ML-based (mention but don't design -- out of scope)
```

### Engagement Score Formula

```
score = (likes x 1.0 + retweets x 2.0 + replies x 1.5) x time_decay(age_hours)

time_decay(age) = 1 / (1 + age_hours * 0.1)

Examples:
  Tweet A: 100 likes, 50 RT, 30 replies, 1 hour old
           = (100 + 100 + 45) x (1 / 1.1) = 245 x 0.91 = 222.7

  Tweet B: 500 likes, 10 RT, 5 replies, 12 hours old
           = (500 + 20 + 7.5) x (1 / 2.2) = 527.5 x 0.45 = 237.4
```

> **Key point**: Time decay prevents old viral tweets from dominating the feed forever.

### Implementation

```
- Store engagement score as ZSET score (updated periodically by background worker)
- Re-score top 200 items per user every 5-10 minutes
- At read time: ZREVRANGEBYSCORE returns highest-scored items first
```

---

## Phase 7: Trending Topics (2-3 min)

### Pipeline

```
Tweet --> Kafka --> Trending Worker --> Extract hashtags --> ZINCRBY trending:{window} hashtag 1
```

### Velocity-Based Scoring (Not Just Volume)

```
Why velocity, not volume?
  #love has 10M mentions/day -- always "popular" but not "trending"
  #SuperBowl goes from 1K to 500K in 5 minutes -- THAT is trending

velocity = count_last_5_min / baseline_count_last_hour

Trending threshold: velocity > 5.0 (5x normal rate)
```

### Implementation

```
Redis Sorted Sets per time window:
  trending:5min   -- ZINCRBY per hashtag, expire after 5 min
  trending:1hour  -- ZINCRBY per hashtag, expire after 1 hour
  trending:global -- Computed every 5 min by worker: velocity = 5min / 1hour

Spam filter: Ignore hashtags from accounts < 7 days old or flagged
Dedup: Count unique users, not unique tweets (prevents bot spamming)
```

---

## Phase 8: Database and Caching (2-3 min)

### Data Store Selection

| Data | Store | Why |
|------|-------|-----|
| Tweets | Cassandra / DynamoDB | Write-heavy (500M/day), time-series, partition by user_id |
| User profiles | PostgreSQL | Small, relational (user -> settings, auth) |
| Timelines | Redis Sorted Set | Pre-computed feeds, fast reads, max 200 items |
| Social graph | Redis Sets | SADD/SREM for follow, SMEMBERS for follower list |
| Celebrity tweets | Redis Hash | HSET celeb_tweets:{id} tweet_id tweet_data, TTL 24h |
| Trending | Redis Sorted Set | ZINCRBY + ZRANGEBYSCORE, windowed |
| Search index | Elasticsearch | Full-text, hashtag search |

### Caching Strategy

```
Timeline cache:  Write-through (fan-out workers write directly to Redis)
Celebrity cache: Write-through (tweet service writes to celebrity HASH)
Tweet content:   Read-through (feed service fetches from Cassandra, caches in Redis)
Cache eviction:  ZREMRANGEBYRANK to keep only top 200 items per timeline
Cache miss:      Rebuild from Cassandra (query followees' recent tweets, merge, cache)
```

---

## Phase 9: Edge Cases (2-3 min)

| Edge Case | Handling |
|-----------|----------|
| Tweet deletion | Fan-out delete event (best-effort). At read time, null-check each tweet ID -- skip deleted. |
| Unfollow | Remove from social graph immediately. Stop future fan-out. Old tweets expire via ZSET trim. |
| Cold start user | Seed with trending + recommended follows. Build cache as user engages. |
| Viral normal user | Dynamic celebrity promotion via fan-out lag monitoring. |
| Thundering herd | Celebrity tweet cache + request coalescing (singleflight). |
| Duplicate delivery | Kafka consumer retries can cause dupes. Dedup at read time by tweet ID. |
| Out-of-order tweets | ZSET score = timestamp handles natural ordering regardless of insert order. |
| Protected/private accounts | Fan-out only to approved followers. Check ACL on read for pull-based tweets. |
| User blocks another | Filter blocked users' tweets at read time. Add to per-user block list in Redis. |

---

## Phase 10: Tradeoffs and CAP (2 min)

### CAP Choice: AP

```
Availability:          Users MUST see a feed, even if slightly stale
Partition tolerance:   Network partitions between data centers must not cause downtime
Consistency sacrifice: Feed may be 5-10 seconds stale. Acceptable for social media.
```

### Key Tradeoff Decisions

| Decision | Chosen | Alternative | Why |
|----------|--------|-------------|-----|
| Fan-out | Hybrid | Pure push or pull | Only viable approach at scale |
| Consistency | Eventual (AP) | Strong (CP) | Stale feed is fine, downtime is not |
| Timeline limit | 200 items | Unlimited | Bound Redis memory, users rarely scroll past 200 |
| Ranking | Engagement-based | Chronological | Higher user engagement, industry standard |
| Dedup | At read time | At write time | Write-time dedup adds latency to fan-out pipeline |

---

## Red Flags (What NOT to Do)

- Proposing ONLY fan-out-on-write without discussing the celebrity problem
- Using a SQL database for tweet storage at 500M writes/day
- Offset-based pagination for a real-time feed
- No caching strategy (hitting DB on every feed read)
- Ignoring the merge step in hybrid approach
- Saying "just use a message queue" without explaining the fan-out logic
- Not mentioning eventual consistency / CAP tradeoff

## Green Flags (What Interviewers Want to Hear)

- Proactively mention the celebrity problem BEFORE the interviewer asks
- Explain why pure fan-out-on-write breaks at scale (50M writes per celebrity tweet)
- Propose hybrid and explain the merge step clearly
- Mention velocity-based trending (not just volume)
- Discuss dynamic celebrity detection (not just static threshold)
- Mention time-decay in ranking
- Quantify: "100B fan-out ops/day" shows you understand the scale

---

## 30-Second Elevator Pitch

> "For a Twitter-like feed system at 300M MAU, the core challenge is fan-out -- distributing 500M daily tweets to 3B feed reads. I'd use a hybrid approach: push tweets to followers' Redis timelines for normal users, but for celebrities with millions of followers, store their tweets separately and pull at read time. The feed service merges pre-computed cache with celebrity tweets, applies engagement-based ranking with time decay, and returns results in under 500ms. Trending uses velocity-based hashtag counting in Redis. The system is AP -- we accept 5-10 seconds of staleness for guaranteed availability."

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Requirements         2 min    (don't skip -- shows maturity)
Phase 2:  Traffic estimation   2-3 min  (SAY "100B fan-out ops/day")
Phase 3:  API design           2 min    (cursor-based pagination!)
Phase 4:  High-level arch      3-4 min  (draw the diagram)
Phase 5:  FAN-OUT PROBLEM      8-10 min (THIS IS THE INTERVIEW)
Phase 6:  Feed ranking         2-3 min  (formula + time decay)
Phase 7:  Trending             2-3 min  (velocity, not volume)
Phase 8:  DB & caching         2-3 min  (Redis ZSET, Cassandra, PG)
Phase 9:  Edge cases           2-3 min  (deletion, unfollow, viral)
Phase 10: Tradeoffs & CAP      2 min    (AP, eventual consistency)
──────────────────────────────────────
Total:                         ~30 min
```
