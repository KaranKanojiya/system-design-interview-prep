# Interview Walkthrough -- News Feed System (Facebook/LinkedIn)

> **Total time: ~35 minutes. The Fan-Out Deep Dive is 50% of this interview.**
> This problem tests fan-out strategy (write vs read vs hybrid), the celebrity problem, algorithmic ranking and personalization, cursor-based pagination, real-time push, and cache-first architecture. This is the ultimate "read-heavy, distributed systems" interview problem -- the hard part is balancing write amplification against read latency at 500M DAU scale.

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "What's the scale? 10M DAU or 500M DAU? This determines whether we need hybrid fan-out or if pure push works."
- "Is the feed chronological or ranked? Chronological is simpler, ranked requires a scoring model."
- "What content types? Text only, or images/videos too? Media changes storage and CDN requirements."
- "How real-time does the feed need to be? Seconds-stale is acceptable, or do we need instant push?"
- "What's the follow graph like? Symmetric (friends, like Facebook) or asymmetric (followers, like Twitter)? This affects fan-out volume."
- "Do we need to handle celebrities? Users with 10M+ followers fundamentally change the fan-out strategy."

### Clarified Scope

```
In scope:   Post creation, feed generation (hybrid fan-out), feed ranking,
            follow/unfollow, cursor pagination, real-time push,
            celebrity handling, media (images/video), search
Out of scope: Ads insertion, stories/reels, groups/pages feed,
              content moderation ML (mention only), chat/messaging,
              notification system (mention only)
```

### What This Signals

You understand this is a **fan-out and ranking problem** where the hard part is **distributing 200M daily posts to 5B daily feed reads** while keeping read latency under 200ms. You're probing for the celebrity problem and ranking requirements that drive the architecture.

**Common follow-up:** "Why does symmetric vs asymmetric matter?"

**Answer:** "In a symmetric graph (Facebook friends), both users see each other's posts. The average user has ~300 friends, so fan-out is bounded -- 300 timeline writes per post. In an asymmetric graph (Twitter/LinkedIn followers), a celebrity can have 10M followers. One post = 10M writes. This makes pure fan-out-on-write impossible for celebrities. That's why I ask -- it determines whether we need the hybrid approach."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll design a hybrid fan-out architecture with five core services: Post Service (creates and stores posts), Fan-Out Workers (push to follower timelines via Kafka), Feed Service (reads pre-computed cache + pulls celebrity posts + merges + ranks), Ranking Service (ML-based personalization), and Social Graph Service (follow relationships). The key insight is hybrid fan-out: push for normal users (99%), pull for celebrities (1%). This eliminates trillions of writes per day while keeping reads under 200ms. The system is AP -- a stale feed for 2-5 seconds is acceptable."

### Draw This Diagram

```
                  +---------------------------+
                  |        Client (App)       |
                  |  POST /v1/posts           |
                  |  GET  /v1/feed            |
                  +------------+--------------+
                               |
              1. HTTPS + JWT auth + rate limiting
                               |
                  +------------v--------------+
                  |   WAF + API Gateway        |
                  |  Rate limit per user       |
                  |  Auth: JWT (Cognito)       |
                  +-----+------------+--------+
                        |            |
           WRITE PATH   |            |   READ PATH
                        v            v
              +-----------+    +------------+
              |   Post    |    |   Feed     |
              |  Service  |    |  Service   |
              |  (ECS)    |    |  (ECS)     |
              +-----------+    +------------+
                   |               |    |    |
     2. Store post |               |    |    |
        in DynamoDB|     10. Read  |    |    | 14. Rank
                   |         cache |    |    |     (SageMaker)
              +----v----+         |    |    |
              |DynamoDB |    +----v----+    |
              | (posts) |    |  Redis  |    |
              +---------+    | timeline|    |
                   |         | cache   |    |
     3. Publish    |         +---------+    |
        to Kafka   |               |        |
                   v         12. Pull       |
           +-------------+   celebrity      |
           |   MSK       |   posts    +-----v------+
           |   Kafka     |        +-->| SageMaker  |
           | (new-posts) |        |   | (ranking   |
           +------+------+        |   |  ML model) |
                  |               |   +------------+
     4. Fan-Out   |               |
        Workers   v               |
           +-------------+  +----+--------+
           |  Fan-Out    |  |   Redis     |
           |  Workers    |  |  celebrity  |
           |  (ECS x50)  |  |   cache    |
           +------+------+  +------------+
                  |
     5. Check: is author celebrity?
        |                    |
        NO (normal)          YES (celebrity)
        |                    |
     6. SMEMBERS             7. HSET celebrity
        followers:U001          cache only
        -> 500 followers        (skip fan-out)
        |
     8. Pipeline ZADD
        timeline:U003 <score> post_001
        timeline:U004 <score> post_001
        ... (500 writes, batched)
        |
     9. ZREMRANGEBYRANK (trim to 800 max)

    15. Response to client:
        { items: [ranked posts], nextCursor: "base64..." }

    16. REAL-TIME (async):
        If user has WebSocket connection:
          Push new feed item via AppSync/WebSocket
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| Post Service | Create/store posts, publish to Kafka, media upload to S3. Generates Snowflake IDs. | AP (accept writes, replicate async) |
| Fan-Out Workers | Kafka consumers. Normal users: push to follower timelines. Celebrities: write to celebrity cache only. Batched Redis pipeline. | AP (retry on failure, at-least-once) |
| Feed Service | Orchestrates feed generation: read cache + pull celebrities + merge + rank + paginate. Target < 200ms. | AP (serve stale cache if backend slow) |
| Ranking Service | Score posts: affinity * recency * engagement * content_type. SageMaker ML endpoint. A/B test ranking algorithms. | AP (fall back to chronological if ML slow) |
| Social Graph Service | Follow/unfollow. Redis Sets for fast lookup. Neptune for graph traversal (friend-of-friend suggestions). | AP (eventual consistency on follow) |
| Timeline Cache | Redis Sorted Set per user. Pre-computed feed. ZADD insert, ZRANGEBYSCORE read, trim to 800 items. | AP (cache miss -> rebuild from DB) |

### What This Signals

You lead with **hybrid fan-out** (the #1 architectural decision) and clearly separate the **write path** (post -> Kafka -> fan-out) from the **read path** (cache -> merge -> rank). You don't treat this as a simple CRUD API -- you understand the fan-out trade-offs.

**Common follow-up:** "Why Kafka and not SQS for the fan-out pipeline?"

**Answer:** "Three reasons. First, Kafka supports multiple consumer groups -- the same post event is consumed by fan-out workers, search indexer, trending detector, and notification service independently. SQS would require publishing to SNS first, then separate SQS queues. Second, Kafka partitions by authorId guarantee per-author ordering -- a user's posts arrive in sequence. Third, Kafka's consumer lag metrics tell me exactly how far behind fan-out is -- critical for monitoring celebrity-vs-normal performance."

---

## Phase 3: Fan-Out Deep Dive (8-10 min)

**This is the star of the interview. Spend the most time here.**

### Part A: Fan-Out on Write (Push Model)

> "Fan-out on write means when a user publishes a post, we immediately push the post ID into every follower's pre-computed timeline cache. The read path is trivial -- just one Redis ZRANGEBYSCORE call. The write cost is O(followers) per post."

```
FAN-OUT ON WRITE (Numbered):

  User U001 (Alice, 500 followers) publishes a post:
      |
      1. Post Service stores post in DynamoDB:
         PK=U001, SK=post_001, content="Just got promoted!"
      |
      v
      2. Publish to Kafka:
         Topic: new-posts, Key: U001
         Value: { postId: post_001, authorId: U001, followerCount: 500 }
      |
      v
      3. Fan-Out Worker consumes message:
         followerCount = 500 (< 10,000 threshold -> PUSH)
      |
      v
      4. Lookup followers:
         Redis: SMEMBERS followers:U001
         Returns: [U003, U004, U005, ... U502]  (500 user IDs)
      |
      v
      5. Batch write to 500 timelines (Redis pipeline):
         Batch 1 (100 commands):
           ZADD timeline:U003 1745658600 post_001
           ZADD timeline:U004 1745658600 post_001
           ... (100 ZADD)
         Batch 2 (100 commands): ...
         Batch 3 (100 commands): ...
         Batch 4 (100 commands): ...
         Batch 5 (100 commands): ...
         Total: 500 ZADD, 5 round-trips (~12ms)
      |
      v
      6. Trim timelines (keep max 800):
         For each timeline that exceeded 800 items:
           ZREMRANGEBYRANK timeline:Uxxx 0 -801
      |
      v
      7. Done. All 500 followers will see post_001
         in their next feed read (single Redis call).

  COST ANALYSIS:
    Write cost:  500 ZADD operations per post (O(followers))
    Read cost:   1 ZRANGEBYSCORE per feed request (O(1))
    At 200M posts/day * 200 avg followers = 40B ZADD/day
    At 100K ZADD/sec per Redis shard:
      40B / 86400 = ~460K ZADD/sec -> need ~5 Redis shards (with headroom: 20+ shards)

  PROS:
    - Reads are O(1) -- one Redis call, < 5ms
    - Simple read path (no merge logic)
    - Pre-computed = predictable latency

  CONS:
    - Celebrity with 10M followers = 10M ZADD per post
    - Write amplification: 40B+ writes/day
    - Wasted writes for inactive users (60% never read their feed that day)
```

### Part B: Fan-Out on Read (Pull Model)

> "Fan-out on read means the post is stored once. When a user opens their feed, we look up who they follow, fetch recent posts from each, merge, and return. Write cost is O(1), read cost is O(following)."

```
FAN-OUT ON READ (Numbered):

  User U003 (Bob) opens their feed.
  Bob follows 300 users.
      |
      1. Feed Service receives: GET /v1/feed?limit=20
      |
      v
      2. Lookup who Bob follows:
         Redis: SMEMBERS following:U003
         Returns: [U001, U002, U004, ... U300]  (300 user IDs)
      |
      v
      3. Fetch recent posts from each followee:
         For each of 300 users, get their latest 5 posts:
           DynamoDB: QUERY PK=U001, SK begins_with "post_", LIMIT 5, DESC
           DynamoDB: QUERY PK=U002, SK begins_with "post_", LIMIT 5, DESC
           ... (300 queries, parallelized in batches of 50)
         Returns: up to 1,500 post candidates
      |
      v
      4. Merge all posts by timestamp / rank score:
         Sort 1,500 posts, take top 20
      |
      v
      5. Return feed (20 items)

  COST ANALYSIS:
    Write cost:  1 DynamoDB PUT per post (O(1))
    Read cost:   300 DynamoDB QUERY per feed request (O(following))
    At 5B feed reads/day * 300 queries = 1.5T DynamoDB reads/day
    Latency: 300 parallel queries * 10ms = still 50-100ms (manageable)
             But at scale, DynamoDB cost is enormous.

  PROS:
    - Write is O(1) -- store once
    - No wasted writes for inactive users
    - Always fresh data (no cache staleness)

  CONS:
    - Read is O(following) -- 300 queries per feed request
    - 5B reads/day * 300 = 1.5T DynamoDB reads/day (expensive)
    - Latency: higher than pre-computed cache (50-100ms vs 2ms)
    - Complex merge logic on every read
```

### Part C: Hybrid (This Design) -- The Interview Answer

> "The hybrid approach combines both: fan-out on write for normal users (99% of users, < 10K followers) and fan-out on read for celebrities (1%, > 10K followers). When a user opens their feed, we read the pre-computed timeline (pushed posts) and pull recent celebrity posts, then merge. This eliminates trillions of celebrity writes while keeping reads fast."

```
HYBRID FAN-OUT (Numbered):

  === WRITE PATH ===

  Normal user (Alice, 500 followers) posts:
      |
      1. Fan-Out Worker: followerCount=500 < 10,000 -> PUSH
      2. SMEMBERS followers:U001 -> 500 followers
      3. Pipeline ZADD to 500 timelines (12ms)
      4. Done.

  Celebrity (Taylor, 10M followers) posts:
      |
      1. Fan-Out Worker: followerCount=10,000,000 > 10,000 -> PULL
      2. Store in celebrity cache ONLY:
         HSET celebrity:U002:posts post_002 "{content, mediaUrl, createdAt}"
         ZADD celebrity:U002:timeline 1745658600 post_002
         EXPIRE celebrity:U002:posts 86400 (24h TTL)
      3. Done. (3 Redis ops vs 10,000,000 ZADD)

  === READ PATH ===

  User (Bob) opens feed. Bob follows 290 normal users + 10 celebrities.
      |
      1. Read pre-computed timeline:
         ZREVRANGEBYSCORE timeline:U003 +inf <cursor_score> LIMIT 0 30
         Returns: 30 post IDs (pushed by 290 normal followees)
         Latency: 2ms
      |
      v
      2. Identify celebrity followees:
         SMEMBERS following:U003 -> 300 user IDs
         Filter: isCelebrity? -> [U002, U005, U009, ...] (10 celebrities)
         (celebrity flag cached in Redis HASH or user metadata)
         Latency: 1ms
      |
      v
      3. Pull celebrity posts IN PARALLEL:
         ZREVRANGEBYSCORE celebrity:U002:timeline +inf <cursor> LIMIT 0 5
         ZREVRANGEBYSCORE celebrity:U005:timeline +inf <cursor> LIMIT 0 5
         ... (10 parallel Redis calls)
         Returns: ~50 celebrity post IDs
         Latency: max(3ms, 2ms, 4ms, ...) = 4ms (parallel)
      |
      v
      4. Hydrate post IDs to full objects:
         Redis MGET post:post_001, post:post_002, ...
         Cache miss -> DynamoDB BatchGetItem
         Latency: 5ms (cache hit) or 15ms (DynamoDB fallback)
      |
      v
      5. Merge: 30 pre-computed + 50 celebrity = 80 candidates
      |
      v
      6. Rank: score = affinity * recency * engagement * content_type
         SageMaker endpoint: rank(userId=U003, posts=80) -> sorted scores
         Latency: 10ms
      |
      v
      7. Take top 20, build cursor, return:
         nextCursor = base64("1745658600:post_019")
         Total latency: 2 + 1 + 4 + 5 + 10 = 22ms

  === SAVINGS ===

  Without hybrid (pure push):
    10M followers * 100 celebrity posts/day * 1000 celebrities
    = 1,000,000,000,000 (1T) ZADD operations/day JUST for celebrities

  With hybrid:
    1000 celebrities * 100 posts/day * 3 Redis ops = 300,000 ops/day
    + Pull at read time: 5B feed reads * 10 celebrity pulls * 1 Redis call
    = 50B reads/day (reads are cheaper than writes in Redis)

  Net savings: 1T writes -> 300K writes + 50B reads
  This is why every large-scale feed system uses hybrid.
```

### Part D: The Celebrity Problem -- Deep Dive

> "The celebrity problem is the single biggest challenge in news feed design. A user with 10M followers generates 10M timeline writes per post. If that celebrity posts 10 times a day, that's 100M writes just for one user. With 1000 celebrities, we're at 100B writes/day -- unsustainable."

```
THE CELEBRITY PROBLEM:

  Taylor Swift posts: "New album dropping Friday!"
      |
      v
  PURE PUSH APPROACH:
    Taylor has 10,000,000 followers
    Fan-Out Worker: ZADD to 10,000,000 timelines
    At 100K ZADD/sec per Redis shard: 100 seconds per post!
    Meanwhile, Taylor's next post is queued. Fan-out falls behind.
    10 posts/day = 100M writes/day = 1,000 seconds of fan-out work.
    Other users' fan-out is queued behind Taylor's.

    Cascade effect:
      Taylor posts -> 100s fan-out
      During those 100s, 5,000 other posts are queued
      Fan-out backlog grows -> feeds become stale
      "Why don't I see my friend's post from 10 minutes ago?"

  CELEBRITY DETECTION:
    1. Static threshold: follower_count > 10,000 -> celebrity
       Simple, predictable. Checked when processing fan-out message.
       Stored as flag in Redis: HSET user:U002 isCelebrity true

    2. Dynamic detection: monitor fan-out lag per user
       If fan-out for one post takes > 2 seconds -> promote to celebrity
       Use case: normal user goes viral (suddenly has 100K new followers)
       Worker detects: "fan-out for U007 took 5 seconds, promoting"
       HSET user:U007 isCelebrity true
       Cancel remaining fan-out for current post -> move to pull model

    3. Demotion: if follower count drops below threshold
       Daily batch job checks: any celebrities below 5K followers?
       Demote back to normal (push model)
       Hysteresis: promote at 10K, demote at 5K (prevent flapping)

  CELEBRITY CACHE DESIGN:
    Redis HASH: celebrity:U002:posts
      post_001 -> "{content, mediaUrls, createdAt, engagementCounts}"
      post_002 -> "{...}"
      (last 100 posts cached)

    Redis ZSET: celebrity:U002:timeline
      Score: timestamp, Member: postId
      ZREVRANGEBYSCORE for time-ordered fetch
      Max 100 items, auto-trimmed

    TTL: 24 hours (posts older than 24h rarely appear in feeds)
    Refresh: on new post from celebrity, add to HASH + ZSET
```

**Common follow-up:** "What if a normal user suddenly goes viral mid-post?"

**Answer:** "The fan-out worker monitors elapsed time per post. If writing to 50K timelines and we've only written 20K after 2 seconds, we pause the fan-out, promote the user to celebrity tier, and enqueue a 'convert to pull' event. The 20K timelines already written are fine -- those users will see the post via cache. The remaining 30K+ followers will pull the post at read time. On the next post from this user, the worker sees isCelebrity=true and skips fan-out entirely."

---

## Phase 4: Ranking & Personalization (5-7 min)

### Part A: Ranking Formula

> "The ranking formula determines which posts appear at the top of a user's feed. The goal is to show the most relevant posts, not just the most recent. The formula has four components: affinity (how close you are to the author), recency decay (newer is better), engagement boost (popular posts surface), and content type weight (videos get a boost over text)."

```
RANKING FORMULA:

  score = affinity * recency_decay * engagement_boost * content_type_weight

  === AFFINITY (0.0 to 1.0) ===
  How often you interact with the author.
  Signals:
    - Liked their posts in last 30 days       (weight: 0.3)
    - Commented on their posts                (weight: 0.4)
    - Viewed their profile                    (weight: 0.1)
    - Messaged them                           (weight: 0.15)
    - Tagged them or were tagged by them      (weight: 0.05)

  Computation:
    affinity(Bob, Alice) =
      0.3 * (likes_in_30d / max_likes) +
      0.4 * (comments_in_30d / max_comments) +
      0.1 * (profile_views / max_views) +
      0.15 * (messages / max_messages) +
      0.05 * (tags / max_tags)

  Storage: pre-computed daily in DynamoDB
    PK=U003, SK="affinity:U001" -> 0.82
    Updated by a daily Spark/EMR job analyzing interaction logs

  === RECENCY DECAY (0.0 to 1.0) ===
  Newer posts score higher. Exponential decay.

  recency_decay = 1 / (1 + age_hours * 0.1)

  Examples:
    0 hours old:  1.0 / (1 + 0)    = 1.000
    1 hour old:   1.0 / (1 + 0.1)  = 0.909
    6 hours old:  1.0 / (1 + 0.6)  = 0.625
    24 hours old: 1.0 / (1 + 2.4)  = 0.294
    48 hours old: 1.0 / (1 + 4.8)  = 0.172

  === ENGAGEMENT BOOST (0.0 to 2.0) ===
  Posts with high engagement get boosted.

  engagement_raw = (likes * 1.0 + shares * 2.0 + comments * 1.5)
  engagement_boost = min(2.0, 1.0 + log10(1 + engagement_raw) * 0.3)

  Examples:
    0 engagement:     1.0 + log10(1) * 0.3 = 1.000
    10 engagement:    1.0 + log10(11) * 0.3 = 1.312
    100 engagement:   1.0 + log10(101) * 0.3 = 1.601
    1000 engagement:  1.0 + log10(1001) * 0.3 = 1.901
    10000 engagement: min(2.0, ...) = 2.000 (capped)

  === CONTENT TYPE WEIGHT ===
  | Content Type | Weight | Reason |
  |-------------|--------|--------|
  | Video       | 1.5    | Highest engagement, platform wants video |
  | Image       | 1.2    | High engagement, visual content |
  | Link        | 1.0    | Neutral |
  | Text        | 0.9    | Lower engagement on average |

  === FULL EXAMPLE ===
  Post by Alice (Bob's close friend), 3 hours old, 50 engagements, image:

  affinity           = 0.82 (Bob frequently interacts with Alice)
  recency_decay      = 1/(1 + 0.3) = 0.769
  engagement_boost   = 1.0 + log10(51) * 0.3 = 1.512
  content_type       = 1.2 (image)

  score = 0.82 * 0.769 * 1.512 * 1.2 = 1.143

  Post by distant colleague, 1 hour old, 5 engagements, text:

  affinity           = 0.15
  recency_decay      = 1/(1 + 0.1) = 0.909
  engagement_boost   = 1.0 + log10(6) * 0.3 = 1.233
  content_type       = 0.9 (text)

  score = 0.15 * 0.909 * 1.233 * 0.9 = 0.151

  Alice's post (1.143) ranks much higher than colleague's (0.151).
```

### Part B: ML-Based Ranking (At Scale)

> "At Facebook/LinkedIn scale, the formula is replaced by an ML model trained on billions of user interactions. The model predicts: what is the probability this user will meaningfully engage with this post?"

```
ML RANKING PIPELINE (Numbered):

    1. FEATURE EXTRACTION (per user-post pair):
       User features:
         - user_id, age, location, device_type
         - avg_session_duration, posts_per_day, likes_per_day
         - active_hours (when user typically browses)
       Author features:
         - author_id, follower_count, post_frequency
         - avg_engagement_rate, content_category
       Interaction features:
         - affinity_score (pre-computed)
         - last_interaction_days_ago
         - mutual_friends_count
       Post features:
         - content_type (text/image/video)
         - post_age_hours
         - current_engagement (likes, comments, shares)
         - content_embedding (NLP vector from post text)
         - image_embedding (CV vector from image, if present)
       Context features:
         - time_of_day, day_of_week
         - user_device, connection_type (wifi/cellular)
    |
    v
    2. MODEL INFERENCE (SageMaker real-time endpoint):
       Input: 80 candidate posts * ~50 features each
       Model: gradient-boosted trees (XGBoost) or deep neural network
       Output: P(engagement) for each post (0.0 to 1.0)
       Latency: ~10ms for batch of 80 posts
    |
    v
    3. MULTI-OBJECTIVE RANKING:
       Not just engagement -- also optimize for:
         - Diversity: don't show 10 posts from same author
         - Freshness: boost posts less than 1 hour old
         - Content mix: ensure variety (text, image, video)
         - Social balance: mix close friends and acquaintances
       Re-rank top 50 with diversity constraints.
    |
    v
    4. A/B TESTING:
       10% of users get model_v2, 90% get model_v1
       Measure: session duration, posts read, likes, shares
       If model_v2 improves engagement by > 2%: promote to 100%

  TRAINING PIPELINE (offline, daily):
    - Collect: impression logs (what was shown + position)
    - Labels: did user engage? (like, comment, share, dwell > 10s)
    - Train on 30 days of data, 10B+ training examples
    - Evaluate on held-out day, compare against current model
    - Deploy via SageMaker Model Registry + canary deployment
```

### What This Signals

You know the formula is a simplification and that real systems use ML. But you can explain the formula clearly, then level up to ML features and multi-objective ranking. This shows depth.

**Common follow-up:** "How do you prevent the feed from becoming an echo chamber?"

**Answer:** "Three mechanisms. First, diversity re-ranking: after ML scoring, I enforce that no more than 2 posts from the same author appear in a 20-post page. Second, exploration: 5% of feed slots are filled with posts from users you rarely interact with -- the ML model didn't score them high, but they introduce serendipity. Third, content-type diversity: ensure each page has a mix of text, images, and video. LinkedIn explicitly does this to prevent your feed from being all job-change announcements."

---

## Phase 5: Scaling & Real-Time (5-8 min)

### Part A: Cursor-Based Pagination

> "Cursor pagination is essential for feeds. Offset pagination breaks when new posts arrive -- if you're on page 2 and 5 new posts appear, items shift and you see duplicates. Cursor pagination uses a stable pointer: 'give me posts older than this timestamp and ID.' New posts don't affect the cursor."

```
CURSOR PAGINATION (Numbered):

  === WHY NOT OFFSET? ===

  User on page 1 (items 1-20). Scrolls to page 2 (items 21-40).
  Between page 1 and page 2, 5 new posts arrive.

  With OFFSET:
    Page 1: items 1-20     (correct)
    Page 2: items 21-40    (but items shifted! items 16-20 are now 21-25)
    User sees items 16-20 AGAIN as duplicates.
    And items 21-25 are SKIPPED (never shown).

  With CURSOR:
    Page 1: items 1-20, cursor = "2026-04-26T10:00:00Z:post_020"
    5 new posts arrive (they're NEWER than cursor, irrelevant)
    Page 2: WHERE (created_at, post_id) < ('2026-04-26T10:00:00Z', 'post_020')
    Returns items 21-40. No duplicates, no gaps. Stable.

  === IMPLEMENTATION ===

    1. Client requests: GET /v1/feed?limit=20
    |
    v
    2. Feed Service reads timeline:
       ZREVRANGEBYSCORE timeline:U003 +inf -inf LIMIT 0 20 WITHSCORES
       Returns: [(post_042, 1745658600), (post_041, 1745658590), ...]
    |
    v
    3. Build cursor from LAST item:
       lastPost = (post_020, 1745656800)
       cursor = base64encode("1745656800:post_020")
       cursor = "MTc0NTY1NjgwMDpwb3N0XzAyMA=="
    |
    v
    4. Response:
       {
         "items": [... 20 ranked posts ...],
         "nextCursor": "MTc0NTY1NjgwMDpwb3N0XzAyMA==",
         "hasMore": true
       }
    |
    v
    5. Client scrolls, sends:
       GET /v1/feed?limit=20&cursor=MTc0NTY1NjgwMDpwb3N0XzAyMA==
    |
    v
    6. Feed Service decodes cursor:
       timestamp = 1745656800, postId = "post_020"
       ZREVRANGEBYSCORE timeline:U003 (1745656800 -inf LIMIT 0 20
       (open interval: strictly less than cursor score)
       If same score: filter by postId < "post_020" (tiebreaker)
    |
    v
    7. Return next 20 posts + new cursor.
       Repeat until hasMore = false.

  CURSOR ENCODING:
    cursor = base64(timestamp + ":" + postId)
    - Opaque to client (can't manipulate)
    - Includes postId for tiebreaking (multiple posts at same timestamp)
    - Stateless: server doesn't store cursor state
    - No expiry: cursor is valid as long as posts exist

  WHY TIMESTAMP + POST_ID (not just timestamp)?
    Multiple posts can have the same timestamp (1-second granularity).
    Without postId tiebreaker, cursor might skip or duplicate posts.
    PostId is monotonically increasing (Snowflake), so it's a stable tiebreaker.
```

### Part B: Real-Time Feed Push via WebSocket

```
REAL-TIME PUSH FLOW (Numbered):

    1. User opens app -> establish WebSocket connection
       wss://feed.example.com/ws?token=jwt_abc123
       API Gateway WebSocket endpoint (or AppSync subscription)
    |
    v
    2. Register connection:
       DynamoDB: PUT { PK: userId=U003, connectionId: "conn_abc",
                       connectedAt: now, ttl: now+2h }
       (TTL auto-cleans stale connections)
    |
    v
    3. Fan-Out Worker writes to timeline:U003
       (normal fan-out, post from Alice)
    |
    v
    4. After ZADD, check for active connection:
       DynamoDB: GET PK=userId=U003
       |
       +-- NO connection: skip (user offline, will see on next load)
       |
       +-- YES, connectionId="conn_abc":
             |
             v
    5. Push via WebSocket:
       API Gateway: POST @connections/conn_abc
       Body: {
         "type": "NEW_FEED_ITEM",
         "post": { "postId": "post_001", "author": "Alice",
                   "content": "Just got promoted!", "thumbnail": "cdn/img.jpg" }
       }
    |
    v
    6. Client receives push:
       Option A: prepend to current feed (instant, optimistic)
       Option B: show "New posts" banner (user taps to refresh)
       Option C: increment badge count (non-intrusive)

    SCALING CONSIDERATIONS:
    - 150M concurrent WebSocket connections at peak
    - API Gateway WebSocket: scales to millions, managed infra
    - Push rate limiting: max 1 push per 5s per user (batch new posts)
    - Only push for posts from close friends (affinity > 0.5)
    - Celebrity posts: NOT pushed via WebSocket (too many connections)
      Instead: client polls every 30s or on next feed load
```

### Part C: Cache Architecture

```
TIMELINE CACHE DESIGN:

  Per-user timeline: Redis Sorted Set
    Key: timeline:{userId}
    Score: timestamp (or pre-computed rank score)
    Member: postId
    Max size: 800 items (ZREMRANGEBYRANK to trim)
    TTL: 7 days for inactive users (save memory)

  Celebrity timeline: Redis Sorted Set + Hash
    Key: celebrity:{userId}:timeline (ZSET, scores by time)
    Key: celebrity:{userId}:posts (HASH, postId -> serialized post)
    Max size: 100 items
    TTL: 24 hours

  Post content cache: Redis Hash
    Key: post:{postId}
    Fields: content, authorId, mediaUrls, engagementCounts, createdAt
    TTL: 1 hour (engagement counts update frequently)
    Cache-aside: miss -> read DynamoDB -> populate cache

  CACHE SIZING (500M DAU):
    Timeline entries: 500M users * 800 items * 40 bytes = 16 TB
    But: only active users need warm cache.
    Active (7 days): 500M * 800 * 40B = 16 TB
    Hot (today): 200M * 800 * 40B = 6.4 TB
    With compression + postId-only storage: ~3 TB hot cache

  CACHE HIT RATE TARGET: > 99%
    Miss = rebuild from DynamoDB (expensive, 50-100ms)
    Hit = Redis ZRANGEBYSCORE (cheap, 2ms)
    Warm cache on user login (pre-fetch timeline if cold)

  CACHE INVALIDATION:
    Post deleted: ZREM timeline:{followerId} postId (fan-out delete)
    User unfollowed: let old posts expire naturally (trim to 800)
    Post edited: invalidate post:{postId} cache, next read fetches fresh
```

**Common follow-up:** "What happens when a user hasn't logged in for months and their cache is cold?"

**Answer:** "On login, the feed service checks the timeline cache. If empty or expired, it triggers a cold-start rebuild: query the user's following list, fetch recent posts from each followee (batched DynamoDB queries), merge, rank, populate the cache, and return. This takes 100-200ms -- slower than a cache hit but still acceptable. We can also pre-warm: a daily job identifies users likely to return (based on historical patterns) and pre-builds their timelines."

---

## Phase 6: Tradeoffs (3-5 min)

### AP vs CP: Why AP for Feeds

| Aspect | AP (This Design) | CP (Alternative) |
|--------|-----------------|------------------|
| Consistency | Feed may be 2-5 seconds stale | Every read sees latest post |
| Availability | Always returns a feed (even if stale) | May return 503 during partition |
| User experience | User doesn't notice 5s delay | User sees error page (terrible) |
| Write path | Accept posts even during partition | Reject posts until consistent |
| Best for | Social feeds, timelines, notifications | Financial systems, inventory |

**Say:** "Feeds are AP, no question. If there's a network partition between the feed service and the timeline cache, I serve a slightly stale feed from a secondary cache rather than showing an error. A user seeing a post 5 seconds late is invisible to them. But an empty feed or a 503 error? That's a front-page incident. The only CP component is the social graph -- follow/unfollow must be consistent to avoid ghost followers."

### Pre-Computed vs On-Demand Feed

| Aspect | Pre-Computed (This Design) | On-Demand |
|--------|---------------------------|-----------|
| Read latency | 2ms (one Redis call) | 50-100ms (query + merge + rank) |
| Write cost | O(followers) ZADD per post | O(1) store only |
| Freshness | May be seconds stale | Always fresh |
| Memory | 3-16 TB Redis for timelines | Minimal (no cache) |
| Best for | Read-heavy feeds (600:1 read/write) | Write-heavy, low-read systems |

**Say:** "With a 600:1 read-to-write ratio, pre-computing is a massive win. We pay the write cost once (500 ZADD for a normal user's post) and get millions of cheap reads (2ms each). On-demand would mean 5B daily feed reads each doing 300 DynamoDB queries -- 1.5 trillion reads/day. The Redis cost is a fraction of that DynamoDB bill. Pre-computation trades write amplification for read speed, and feeds are overwhelmingly read-heavy."

### DynamoDB vs Cassandra for Posts

| Aspect | DynamoDB (This Design) | Cassandra |
|--------|----------------------|-----------|
| Operations | Fully managed, zero ops | Self-managed cluster (or DataStax) |
| Scaling | Auto-scales read/write capacity | Manual shard management |
| Global replication | Global Tables (turnkey multi-region) | Multi-DC replication (complex config) |
| Cost at scale | Expensive at very high throughput | Cheaper at extreme scale (self-managed) |
| Best for | < 1B posts/day (managed simplicity) | > 1B posts/day (cost optimization) |

**Say:** "I start with DynamoDB for managed simplicity -- Global Tables give me multi-region replication with zero config. At Facebook scale (billions of posts/day), we'd evaluate Cassandra for cost -- self-managing a Cassandra cluster saves money but requires a dedicated storage team. For an interview, DynamoDB is the right default because it lets me focus on the fan-out architecture rather than database operations."

### Redis Sorted Set vs Redis List for Timelines

| Aspect | Sorted Set (This Design) | List |
|--------|-------------------------|------|
| Ordering | By score (timestamp or rank) | By insertion order only |
| Range queries | ZRANGEBYSCORE (any score range) | LRANGE (by index only) |
| Cursor pagination | Natural (score = timestamp) | Awkward (index shifts) |
| Dedup | Automatic (same member replaced) | Manual (check before insert) |
| Memory | Higher (score + member per entry) | Lower (member only) |
| Best for | Ranked, paginated feeds | Simple FIFO queues |

**Say:** "Sorted Set is the only viable option for feeds. I need score-based ordering (rank score, not just insertion time), cursor pagination (ZRANGEBYSCORE with score < cursor), and automatic dedup (if the same post is fanned out twice, ZADD just updates the score). Lists don't support any of these -- LRANGE by index would break with cursor pagination and requires manual dedup."

---

## Red Flags (What NOT to Do)

- No celebrity handling -- "just fan out to everyone" at 10M followers is a non-starter
- Offset pagination -- breaks with new posts, shows duplicates
- Single fan-out strategy -- pure push or pure pull doesn't scale
- No ranking -- chronological feed is 2009-era, interviewers expect engagement ranking
- Polling instead of WebSocket -- "client polls every 5 seconds" wastes bandwidth at scale
- No cache -- computing feeds on-demand at 5B reads/day is impossibly expensive
- Using SQL for posts -- "store all posts in PostgreSQL" can't handle 200M writes/day
- Ignoring the cold-start problem -- "what about a new user with no timeline?"
- No content type distinction -- treating text and video the same in ranking

## Green Flags (What Interviewers Want to Hear)

- Lead with hybrid fan-out: "Push for normal users, pull for celebrities"
- Quantify the celebrity problem: "10M followers = 10M writes per post -- unsustainable"
- Explain cursor pagination: "Cursor = timestamp:postId, NOT offset"
- Ranking formula with concrete numbers: "affinity=0.8, recency=0.9, engagement=1.2..."
- Distinguish write path from read path: "Kafka -> fan-out workers" vs "cache -> merge -> rank"
- Name the cache structure: "Redis Sorted Set, scored by timestamp, max 800 items"
- Mention real-time push: "WebSocket for online users, not polling"
- AP with justification: "Stale feed for 5 seconds is invisible to users"
- Celebrity detection: "Static threshold + dynamic promotion on fan-out lag"
- Multi-region: "Latency-based routing, regional caches, DynamoDB Global Tables"

---

## 30-Second Elevator Pitch

> "For a Facebook-scale news feed, I'd build a **hybrid fan-out** system: **push** for normal users (< 10K followers) via Kafka workers writing to **Redis Sorted Set** timelines, and **pull** for celebrities at read time -- eliminating trillions of writes per day. Feed reads merge the pre-computed cache with celebrity posts, then **rank using ML** (affinity * recency * engagement * content_type). Pagination is **cursor-based** (timestamp:postId), not offset, because offset breaks when new posts arrive. Posts are stored in **DynamoDB Global Tables** for multi-region reads. Real-time updates go via **WebSocket** push to online users. The system is **AP** -- a 2-5 second stale feed is invisible to users, but an empty feed is catastrophic. At 500M DAU, this serves 5B feed reads/day with sub-200ms latency."

**Time: Under 30 seconds. Covers: Hybrid fan-out, celebrity problem, ranking, cursor pagination, real-time, AP, scale.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements            2-3 min   (scale, ranking, celebrities, symmetric/asymmetric)
Phase 2:  High-Level Architecture          5-7 min   (hybrid fan-out, write vs read path, components)
Phase 3:  Fan-Out Deep Dive                8-10 min  (write vs read vs hybrid, celebrity problem, cache)
Phase 4:  Ranking & Personalization        5-7 min   (formula, ML features, multi-objective, A/B testing)
Phase 5:  Scaling & Real-Time              5-8 min   (cursor pagination, WebSocket, cache architecture)
Phase 6:  Tradeoffs Discussion             3-5 min   (AP vs CP, pre-computed vs on-demand, DynamoDB vs Cassandra)
-----------------------------------------------------------------------------------
Total:                                     ~35 min
```

If short on time, shorten Phase 4 (ranking) and Phase 6 (tradeoffs). Never skip Phase 3 (fan-out deep dive) -- that's the core of the interview.
