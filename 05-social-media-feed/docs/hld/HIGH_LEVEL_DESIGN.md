# High-Level Design: Social Media Feed System (Twitter/X-like)

> **Difficulty:** HARD | **Interview Time:** 35-45 minutes | **Focus:** Fan-out strategy, celebrity problem, trending topics

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Traffic Estimates](#7-traffic-estimates)
8. [Data Model](#8-data-model)
9. [High-Level Architecture](#9-high-level-architecture)
10. [The Celebrity Problem and Fan-Out Strategy](#10-the-celebrity-problem--fan-out-strategy)
11. [Fan-Out Service Deep Dive](#11-fan-out-service-deep-dive)
12. [Feed Generation (Read Path)](#12-feed-generation-read-path)
13. [Trending Topics](#13-trending-topics)
14. [Edge Cases](#14-edge-cases)
15. [Ranking / Feed Scoring](#15-ranking--feed-scoring)
16. [Scaling Strategy](#16-scaling-strategy)
17. [Database Choice](#17-database-choice)
18. [Caching Strategy](#18-caching-strategy)
19. [CAP Theorem Analysis](#19-cap-theorem-analysis)
20. [Cloud Services Mapping](#20-cloud-services-mapping)
21. [Tradeoffs Summary](#21-tradeoffs-summary)
22. [Interview Talking Points](#22-interview-talking-points)

---

## 1. Problem Statement

Build a social media feed system where users can post short messages (tweets), follow other users, and see a personalized home timeline aggregating content from everyone they follow.

**Why this is HARD:**

| Challenge | Why It Matters |
|-----------|---------------|
| **Fan-out at scale** | One celebrity tweet to 50M followers = 50M timeline writes |
| **Read-heavy** | 600:1 read-to-write ratio; feed must load in < 500ms |
| **Real-time expectations** | Users expect tweets to appear in their feed within seconds |
| **Ranking** | Chronological is simple but engagement-based ranking drives product value |
| **Mixed workloads** | Normal users (200 followers) vs celebrities (50M followers) need fundamentally different strategies |
| **Trending detection** | Real-time detection of viral topics from billions of daily events |

The core interview challenge is: **How do you deliver a personalized feed to 300M users in under 500ms when a single celebrity post must reach 50M timelines?**

---

## 2. Scope

### In Scope

- Post a tweet (text up to 280 characters, optional images/video)
- Home timeline / news feed generation (aggregated, ranked)
- User timeline (all tweets by a specific user)
- Follow / unfollow users
- Like and retweet
- Trending topics (top hashtags/topics by velocity)
- Search tweets (by keyword, hashtag, user)
- User profile and profile feed

### Out of Scope

| Feature | Reason |
|---------|--------|
| Direct Messages (DMs) | Covered in Chat System design |
| Ads / Promoted Tweets | Separate ads platform |
| ML Recommendations ("Who to Follow") | Separate recommendation engine |
| Analytics Dashboard | Separate analytics system |
| Monetization / Subscriptions | Business layer, not core feed |
| Spaces / Audio | Separate real-time media system |

---

## 3. Assumptions

| Parameter | Value | Derivation |
|-----------|-------|------------|
| Monthly Active Users (MAU) | 300M | Given |
| Daily Active Users (DAU) | ~150M (50% of MAU) | Industry standard |
| Tweets per day | 500M | Given |
| Active posters | 10% of MAU = 30M | Given |
| Consumers (read-only) | 80% = 240M | Given |
| Avg follows per user | 200 | Given |
| Celebrity threshold | >1M followers | Top 1% |
| Celebrity count | ~3M users (top 1% of 300M) | Given |
| "Super celebrity" (>10M followers) | ~10K users | Power law distribution |
| Peak tweet rate | 100K tweets/sec | Major events (World Cup, elections) |
| Timeline reads per user per day | 10 | Average across active users |
| Total timeline reads/day | 3B (300M x 10) | Derived |
| Avg tweet size (text) | ~300 bytes | 280 chars + metadata |
| Media attachment rate | 10% of tweets | Industry observation |
| Avg media size | 500 KB | Compressed images |

---

## 4. Functional Requirements

### FR-1: Post a Tweet
- User can post text (up to 280 characters) with optional media (images, video)
- Tweet is persisted, assigned a globally unique tweet_id (Snowflake ID)
- Tweet is distributed to followers' timelines (fan-out)

### FR-2: Home Timeline (News Feed)
- Aggregated feed of tweets from all users the current user follows
- Ranked by relevance (chronological + engagement signals)
- Paginated with cursor-based pagination
- Near real-time: new tweets appear within seconds

### FR-3: User Timeline
- All tweets by a specific user, in reverse chronological order
- Publicly viewable (unless account is private)

### FR-4: Follow / Unfollow
- Follow a user to receive their tweets in your home timeline
- Unfollow to stop receiving their tweets
- Follower/following counts are updated

### FR-5: Like and Retweet
- Like a tweet (toggle on/off)
- Retweet a tweet to share with your own followers
- Like/retweet counts are visible on the tweet

### FR-6: Trending Topics
- Top trending hashtags/topics in sliding windows (1h, 6h, 24h)
- Based on velocity of mentions, not just volume
- Filterable by region/country

### FR-7: Search
- Full-text search on tweet content
- Search by hashtag, username
- Results ranked by relevance and recency

---

## 5. Non-Functional Requirements

| Requirement | Target | Rationale |
|-------------|--------|-----------|
| **Feed latency** | < 500ms (p99) | User-facing read path must feel instant |
| **Tweet publish latency** | < 1s visible to poster | Poster sees their tweet immediately |
| **Fan-out latency** | < 5s for normal users | Followers see tweet within seconds |
| **Availability** | 99.99% (52 min downtime/year) | Global user base, always on |
| **Consistency** | Eventually consistent | Stale feed for 5-10 seconds is acceptable |
| **Scalability** | 300M MAU, 500M tweets/day | Must handle peak traffic (100K tweets/sec) |
| **Read-Write ratio** | 600:1 | Extremely read-heavy; optimize reads |
| **Durability** | No tweet loss | All tweets must be durably stored |

---

## 6. API Design

### 6.1 Post a Tweet

```
POST /api/tweets
Authorization: Bearer <token>
Content-Type: application/json

Request:
{
  "content": "Hello world! #firsttweet",
  "media_urls": [
    "https://media.example.com/img/abc123.jpg"
  ],
  "reply_to_tweet_id": null
}

Response: 201 Created
{
  "tweet_id": "1749283746501928",
  "user_id": "u_892374",
  "content": "Hello world! #firsttweet",
  "media_urls": ["https://media.example.com/img/abc123.jpg"],
  "like_count": 0,
  "retweet_count": 0,
  "reply_count": 0,
  "created_at": "2026-04-18T10:30:00Z"
}
```

### 6.2 Get Home Timeline (News Feed)

```
GET /api/feed?cursor=1749283746501928&limit=20
Authorization: Bearer <token>

Response: 200 OK
{
  "tweets": [
    {
      "tweet_id": "1749283746501930",
      "user": {
        "user_id": "u_123456",
        "username": "johndoe",
        "display_name": "John Doe",
        "avatar_url": "https://cdn.example.com/avatars/u_123456.jpg"
      },
      "content": "Great morning! #sunshine",
      "media_urls": [],
      "like_count": 42,
      "retweet_count": 5,
      "reply_count": 3,
      "is_liked_by_me": false,
      "is_retweeted_by_me": false,
      "created_at": "2026-04-18T10:28:00Z"
    }
  ],
  "next_cursor": "1749283746501910",
  "has_more": true
}
```

### 6.3 Get User Timeline

```
GET /api/users/{userId}/tweets?cursor=1749283746501928&limit=20

Response: 200 OK
{
  "user": {
    "user_id": "u_123456",
    "username": "johndoe",
    "display_name": "John Doe",
    "follower_count": 1520,
    "following_count": 340
  },
  "tweets": [ ... ],
  "next_cursor": "1749283746501900",
  "has_more": true
}
```

### 6.4 Follow / Unfollow

```
POST /api/users/{userId}/follow
Authorization: Bearer <token>

Response: 200 OK
{ "status": "following", "followed_user_id": "u_123456" }

---

DELETE /api/users/{userId}/follow
Authorization: Bearer <token>

Response: 200 OK
{ "status": "unfollowed", "unfollowed_user_id": "u_123456" }
```

### 6.5 Like / Retweet

```
POST /api/tweets/{tweetId}/like
Authorization: Bearer <token>

Response: 200 OK
{ "tweet_id": "1749283746501930", "like_count": 43, "is_liked": true }

---

POST /api/tweets/{tweetId}/retweet
Authorization: Bearer <token>

Response: 201 Created
{
  "retweet_id": "1749283746501935",
  "original_tweet_id": "1749283746501930",
  "retweet_count": 6
}
```

### 6.6 Trending Topics

```
GET /api/trending?region=US&window=6h

Response: 200 OK
{
  "trending": [
    { "rank": 1, "hashtag": "#WorldCup", "tweet_count": 2450000, "window": "6h" },
    { "rank": 2, "hashtag": "#BreakingNews", "tweet_count": 1800000, "window": "6h" },
    { "rank": 3, "hashtag": "#TechConf2026", "tweet_count": 920000, "window": "6h" }
  ],
  "region": "US",
  "generated_at": "2026-04-18T10:30:00Z"
}
```

### 6.7 Search

```
GET /api/search?q=%23WorldCup&type=recent&cursor=abc123&limit=20

Response: 200 OK
{
  "query": "#WorldCup",
  "results": [
    {
      "tweet_id": "1749283746501940",
      "user": { "user_id": "u_999", "username": "sportsfan" },
      "content": "Amazing goal! #WorldCup",
      "created_at": "2026-04-18T10:29:00Z"
    }
  ],
  "next_cursor": "def456",
  "has_more": true
}
```

---

## 7. Traffic Estimates

### Write Traffic (Tweets)

| Metric | Value |
|--------|-------|
| Tweets per day | 500M |
| Avg tweets/sec | 500M / 86400 = **~6,000 tweets/sec** |
| Peak tweets/sec | **~100K tweets/sec** (major events) |
| Tweet size (text + metadata) | ~300 bytes |
| Daily text storage | 500M x 300B = **150 GB/day** |
| Media tweets (10%) | 50M tweets with media |
| Daily media storage | 50M x 500KB = **25 TB/day** |

### Read Traffic (Feed)

| Metric | Value |
|--------|-------|
| Timeline reads/day | 300M users x 10 reads = **3B reads/day** |
| Avg reads/sec | 3B / 86400 = **~35,000 reads/sec** |
| Peak reads/sec | **~100K reads/sec** |
| **Read:Write ratio** | 3B / 500M = **600:1** |

### Fan-Out Traffic (THE BIG NUMBER)

| Metric | Value |
|--------|-------|
| Avg fan-out per tweet | 200 followers |
| Total fan-out operations/day | 500M tweets x 200 = **100 BILLION fan-out writes/day** |
| Avg fan-out writes/sec | **~1.15M writes/sec** |
| **Celebrity single tweet** | 1 tweet x 50M followers = **50M writes** |
| Time for celebrity fan-out (naive) | 50M / 100K writes/sec = **500 seconds = 8+ minutes** |

> **Key insight:** 100B fan-out writes/day is the reason this problem is hard. The celebrity problem makes it 250x worse per tweet.

### Storage Estimates (1 Year)

| Data | Daily | Yearly |
|------|-------|--------|
| Tweet text | 150 GB | 55 TB |
| Media | 25 TB | 9 PB |
| Timeline cache (Redis) | ~50 GB active | ~500 GB (hot data only) |
| Social graph | — | ~100 GB |
| Search index | 150 GB | 55 TB |

---

## 8. Data Model

### 8.1 User Table (PostgreSQL)

```sql
CREATE TABLE users (
    user_id       BIGINT PRIMARY KEY,       -- Snowflake ID
    username      VARCHAR(30) UNIQUE NOT NULL,
    display_name  VARCHAR(50),
    bio           VARCHAR(160),
    avatar_url    VARCHAR(500),
    follower_count  INT DEFAULT 0,
    following_count INT DEFAULT 0,
    is_celebrity  BOOLEAN DEFAULT FALSE,     -- flag for fan-out strategy
    celebrity_threshold INT DEFAULT 10000,   -- dynamic threshold
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP
);
-- Index: idx_users_username (username)
```

### 8.2 Tweet Table (Cassandra)

```sql
CREATE TABLE tweets (
    tweet_id      BIGINT,                   -- Snowflake ID (time-sortable)
    user_id       BIGINT,
    content       TEXT,                      -- max 280 chars
    media_urls    LIST<TEXT>,
    like_count    COUNTER,
    retweet_count COUNTER,
    reply_count   COUNTER,
    reply_to_id   BIGINT,                   -- NULL if not a reply
    hashtags      SET<TEXT>,
    is_deleted    BOOLEAN,                  -- soft delete
    created_at    TIMESTAMP,
    PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);
-- Partition by user_id: all tweets by a user co-located
-- Clustering by tweet_id DESC: latest first (user timeline)
```

### 8.3 Follow Table (Cassandra)

```sql
-- Who does user X follow?
CREATE TABLE following (
    follower_id   BIGINT,
    followee_id   BIGINT,
    created_at    TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id)
);

-- Who follows user Y?
CREATE TABLE followers (
    followee_id   BIGINT,
    follower_id   BIGINT,
    created_at    TIMESTAMP,
    PRIMARY KEY (followee_id, follower_id)
);
```

### 8.4 Timeline Cache (Redis)

```
Key:    timeline:{user_id}
Type:   Sorted Set
Member: tweet_id
Score:  tweet_timestamp (or ranking score)

Example:
  ZADD timeline:u_892374 1713436200 "tweet_174928374"
  ZADD timeline:u_892374 1713436180 "tweet_174928370"

Read:   ZREVRANGE timeline:u_892374 0 19  (top 20, newest first)
Size:   Max 800 entries per user (TTL: 7 days)
```

### 8.5 Like Table (Cassandra)

```sql
CREATE TABLE likes (
    tweet_id    BIGINT,
    user_id     BIGINT,
    created_at  TIMESTAMP,
    PRIMARY KEY (tweet_id, user_id)
);
-- Also: reverse lookup table for "tweets I liked"
CREATE TABLE user_likes (
    user_id     BIGINT,
    tweet_id    BIGINT,
    created_at  TIMESTAMP,
    PRIMARY KEY (user_id, tweet_id)
) WITH CLUSTERING ORDER BY (tweet_id DESC);
```

### 8.6 Retweet Table (Cassandra)

```sql
CREATE TABLE retweets (
    user_id           BIGINT,
    original_tweet_id BIGINT,
    retweet_id        BIGINT,
    created_at        TIMESTAMP,
    PRIMARY KEY (user_id, retweet_id)
) WITH CLUSTERING ORDER BY (retweet_id DESC);
```

### 8.7 Trending Table (Redis)

```
Key:    trending:{window}:{region}
Type:   Sorted Set
Member: hashtag
Score:  trending_score

Example:
  ZINCRBY trending:1h:US 1 "#WorldCup"
  ZREVRANGE trending:1h:US 0 9  (top 10 trending)
```

### Entity Relationship Diagram

```
┌──────────┐      ┌───────────┐      ┌──────────┐
│   User   │──1:N─│   Tweet   │──1:N─│   Like   │
│          │      │           │      │(user_id, │
│ user_id  │      │ tweet_id  │      │tweet_id) │
│ username │      │ user_id   │      └──────────┘
│ is_celeb │      │ content   │
└────┬─────┘      │ hashtags  │      ┌──────────┐
     │            │ media_urls│──1:N─│ Retweet  │
     │            └───────────┘      │(user_id, │
     │                               │tweet_id) │
     │  ┌───────────┐                └──────────┘
     └──│  Follow   │
        │(follower, │
        │ followee) │    ┌──────────────┐
        └───────────┘    │Timeline Cache│
                         │(user_id,     │
                         │ tweet_id,    │
                         │ score)       │
                         └──────────────┘
```

---

## 9. High-Level Architecture

```
                              ┌─────────────┐
                              │   Clients   │
                              │(Mobile/Web) │
                              └──────┬──────┘
                                     │
                              ┌──────▼──────┐
                              │ API Gateway │
                              │ (Rate Limit,│
                              │  Auth, SSL) │
                              └──────┬──────┘
                                     │
                              ┌──────▼──────┐
                              │    Load     │
                              │  Balancer   │
                              └──────┬──────┘
                                     │
          ┌──────────┬───────────┬───┴────┬──────────┬──────────┐
          │          │           │        │          │          │
    ┌─────▼────┐ ┌───▼───┐ ┌────▼───┐ ┌──▼───┐ ┌───▼────┐ ┌──▼──────┐
    │  Tweet   │ │ Feed  │ │ User   │ │Search│ │Trending│ │ Media   │
    │ Service  │ │Service│ │Service │ │Svc   │ │Service │ │ Service │
    └────┬─────┘ └───┬───┘ └───┬────┘ └──┬───┘ └───┬────┘ └──┬──────┘
         │           │         │         │         │          │
    ┌────▼─────┐     │    ┌────▼────┐    │         │     ┌────▼─────┐
    │  Kafka   │     │    │  User   │    │         │     │  S3/CDN  │
    │ (Tweet   │     │    │   DB    │    │         │     │ (Media   │
    │ Events)  │     │    │(Postgres│    │         │     │ Storage) │
    └────┬─────┘     │    └─────────┘    │         │     └──────────┘
         │           │                   │         │
    ┌────▼─────┐  ┌──▼─────────┐  ┌─────▼───┐  ┌──▼──────────┐
    │ Fan-Out  │  │   Redis    │  │ Elastic  │  │   Redis     │
    │ Service  │  │  Cluster   │  │ Search   │  │(Trending    │
    │(Workers) │  │ (Timeline  │  │ Cluster  │  │ Sorted Sets)│
    └────┬─────┘  │  Cache)    │  └──────────┘  └─────────────┘
         │        └────────────┘
         │              ▲
         └──────────────┘
         (write to timeline cache)

    ┌──────────────┐
    │ Notification │ ◄── Kafka (mention events, like events)
    │   Service    │
    └──────────────┘
```

### Service Responsibilities

| Service | Responsibility |
|---------|---------------|
| **Tweet Service** | CRUD for tweets, publish to Kafka |
| **Feed Service** | Home timeline generation (hybrid fan-out read path) |
| **Fan-Out Service** | Consume Kafka events, push tweets to follower timelines |
| **User Service** | Profile CRUD, follow/unfollow, social graph |
| **Search Service** | Full-text search via Elasticsearch |
| **Trending Service** | Compute trending topics from hashtag streams |
| **Media Service** | Upload/serve images and video via S3/CDN |
| **Notification Service** | Push notifications for mentions, likes, retweets |

---

## 10. The Celebrity Problem & Fan-Out Strategy

> **THIS IS THE CORE OF THE INTERVIEW. Spend 10-15 minutes here.**

### The Problem in Numbers

```
Normal user posts a tweet:
  - 200 followers
  - Fan-out: 200 writes to Redis
  - Time: < 1 ms
  - Cost: negligible

Elon Musk posts a tweet:
  - 50,000,000 followers
  - Fan-out: 50,000,000 writes to Redis
  - Time: 50M / 100K writes/sec = 500 seconds = 8+ MINUTES
  - Cost: massive CPU, memory, network
  - And he tweets 20 times a day = 1 BILLION fan-out writes/day FROM ONE USER
```

### Three Approaches

---

### Approach A: Fan-Out on Write (Push Model)

```
User posts tweet
       │
       ▼
  ┌──────────┐    ┌──────────────┐    ┌───────────────────┐
  │  Tweet    │───▶│  Get ALL     │───▶│  Write tweet_id   │
  │  Service  │    │  follower    │    │  to EACH follower's│
  │           │    │  IDs         │    │  timeline cache    │
  └──────────┘    └──────────────┘    └───────────────────┘
                   (200 IDs)            (200 Redis ZADD ops)

Reading feed = simple ZREVRANGE from Redis. O(1).
```

| Pros | Cons |
|------|------|
| Feed reads are instant: O(1) from cache | WRITE AMPLIFICATION: 50M followers = 50M writes |
| Simple read path | Slow for celebrities (8+ min lag) |
| Pre-computed = fast | Wastes storage for inactive users |
| Great for normal users | Hot partition on celebrity fan-out |

**Good for:** Normal users with < 10K followers

---

### Approach B: Fan-Out on Read (Pull Model)

```
User opens feed
       │
       ▼
  ┌──────────┐    ┌──────────────┐    ┌───────────────────┐
  │  Feed    │───▶│  Get list of │───▶│  For EACH followee │
  │  Service │    │  followees   │    │  query their latest│
  │          │    │  (200 users) │    │  tweets, merge,    │
  └──────────┘    └──────────────┘    │  rank, return top N│
                                      └───────────────────┘
                                       (200 queries + merge)

Writing tweet = just write to own user timeline. O(1).
```

| Pros | Cons |
|------|------|
| No write amplification | SLOW reads: 200+ queries per feed request |
| Celebrity tweets are cheap to write | Merging/ranking at read time = high latency |
| No wasted storage | Cannot meet < 500ms SLA with 200 queries |
| Simple write path | Doesn't scale for users following many accounts |

**Good for:** Celebrity tweets (avoid writing to 50M timelines)

---

### Approach C: HYBRID Model (THE ANSWER)

> **This is what Twitter/X actually uses. This is the answer the interviewer wants.**

```
┌─────────────────────────────────────────────────────────────┐
│                    HYBRID FAN-OUT STRATEGY                   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Normal users (< 10K followers) ──▶ Fan-out on WRITE (push)│
│  Celebrity users (> 10K followers) ──▶ Fan-out on READ (pull)│
│                                                             │
│  When user reads feed:                                      │
│    1. Read pre-computed cache (normal users' tweets)        │
│    2. Pull celebrity tweets (small number of queries)       │
│    3. Merge + rank both lists                               │
│    4. Return top N                                          │
└─────────────────────────────────────────────────────────────┘
```

### Hybrid Flow -- Detailed ASCII Diagram

```
                    WRITE PATH
                    ==========

Normal User (200 followers) posts tweet:
┌────────┐   ┌─────────┐   ┌──────────┐   ┌─────────────────┐
│ Tweet  │──▶│  Kafka  │──▶│ Fan-Out  │──▶│ Redis: Write to │
│Service │   │ (event) │   │ Service  │   │ 200 follower    │
└────────┘   └─────────┘   │          │   │ timeline caches │
                            │ CHECK:   │   └─────────────────┘
                            │ is_celeb?│
                            │ = NO     │
                            └──────────┘

Celebrity (50M followers) posts tweet:
┌────────┐   ┌─────────┐   ┌──────────┐   ┌─────────────────┐
│ Tweet  │──▶│  Kafka  │──▶│ Fan-Out  │──▶│ SKIP fan-out.   │
│Service │   │ (event) │   │ Service  │   │ Tweet stays in  │
└────────┘   └─────────┘   │          │   │ celebrity's own  │
                            │ CHECK:   │   │ user timeline    │
                            │ is_celeb?│   │ only.           │
                            │ = YES    │   └─────────────────┘
                            └──────────┘


                    READ PATH
                    =========

User opens home feed:
                                                            
Step 1: Read pre-computed timeline                          
┌────────┐   ┌──────────┐   ┌───────────────┐              
│  Feed  │──▶│  Redis   │──▶│ Get top 200   │  ◄── O(1)   
│Service │   │ Timeline │   │ tweet_ids     │  Fast!       
└───┬────┘   │  Cache   │   │ (normal users │              
    │        └──────────┘   │  tweets only) │              
    │                       └───────┬───────┘              
    │                               │                       
    │  Step 2: Pull celebrity tweets│                       
    │  ┌──────────────┐             │                       
    ├─▶│ Social Graph │             │                       
    │  │ Cache: get   │             │                       
    │  │ celebrity    │             │                       
    │  │ followees    │             │                       
    │  │ (10-20 IDs)  │             │                       
    │  └──────┬───────┘             │                       
    │         │                     │                       
    │  ┌──────▼───────┐             │                       
    │  │ For each     │             │                       
    │  │ celebrity:   │             │                       
    │  │ get latest 5 │             │                       
    │  │ tweets from  │             │                       
    │  │ their user   │             │                       
    │  │ timeline     │             │                       
    │  └──────┬───────┘             │                       
    │         │                     │                       
    │  Step 3:│  Merge              │                       
    │  ┌──────▼─────────────────────▼──┐                    
    │  │  Merge pre-computed + celeb   │                    
    │  │  tweets. Rank by score.       │                    
    │  │  Return top 20.               │                    
    │  └───────────────────────────────┘                    
    │                                                       
    │  Step 4: Return paginated response                    
    ▼                                                       
  Client                                                    
```

### Why the Hybrid Works: The Math

```
Total users:     300,000,000
Celebrity users: 3,000,000   (top 1%, >10K followers)
Normal users:    297,000,000 (99%)

Tweets per day:  500,000,000
  - From normal users:    ~450,000,000 (90%) → fan-out on WRITE
  - From celebrity users: ~50,000,000  (10%) → fan-out on READ

Fan-out writes with HYBRID:
  - Normal: 450M tweets x 200 avg followers = 90B writes/day ✓ (manageable)
  - Celebrity: 0 fan-out writes                               ✓ (saved!)
  - SAVED: ~10B+ fan-out writes/day

Read path overhead with HYBRID:
  - Avg user follows ~5-20 celebrities
  - Extra queries per feed read: 5-20 (instead of 200)
  - Extra latency: ~20-50ms (acceptable within 500ms budget)
```

### Concrete Example: What Happens When...

**Scenario 1: Normal user Alice (200 followers) tweets "Good morning!"**

```
1. Tweet stored in Cassandra (tweets table)
2. Event published to Kafka: { tweet_id: T1, user_id: alice, is_celebrity: false }
3. Fan-Out Service consumes event
4. Checks: alice.is_celebrity = false → FAN-OUT ON WRITE
5. Gets alice's 200 follower IDs from social graph
6. Batches follower IDs in groups of 1000
7. For each follower, executes: ZADD timeline:{follower_id} {timestamp} {tweet_id}
8. 200 Redis writes complete in < 5ms
9. All 200 followers see alice's tweet in their pre-computed timeline
```

**Scenario 2: Celebrity Elon (50M followers) tweets "Dogecoin to the moon!"**

```
1. Tweet stored in Cassandra (tweets table)
2. Event published to Kafka: { tweet_id: T2, user_id: elon, is_celebrity: true }
3. Fan-Out Service consumes event
4. Checks: elon.is_celebrity = true → SKIP FAN-OUT
5. Tweet stays in elon's user timeline ONLY
6. When any of elon's 50M followers opens their feed:
   a. Pre-computed cache is read (normal users' tweets)
   b. Feed Service checks: "does this user follow any celebrities?"
   c. Finds: [elon, taylorswift, ...] in celebrity follow list
   d. Fetches elon's latest 5 tweets from elon's user timeline
   e. Merges with pre-computed feed
   f. Returns ranked result
7. Total writes: 1 (just the tweet itself)
8. Reads are distributed across 50M users over time (not all at once)
```

### Celebrity Detection Strategies

| Strategy | How It Works | Pros | Cons |
|----------|-------------|------|------|
| **Static threshold** | Flag users with follower_count > 10K as celebrity | Simple, predictable | Doesn't adapt to sudden virality |
| **Dynamic threshold** | Monitor fan-out queue lag. If one user's fan-out takes > 5 min, reclassify | Adapts to real-world bottlenecks | Reactive, not proactive |
| **Tiered thresholds** | 10K = level 1, 100K = level 2, 1M = level 3. Different fan-out strategies per tier | Fine-grained control | More complex logic |
| **Activity-based** | Consider post frequency x followers. High-frequency celebrity = higher priority for read path | Accounts for posting patterns | More state to track |

**Recommended: Static threshold (10K) + dynamic reclassification.**

```java
// Simplified celebrity check in Fan-Out Service
boolean isCelebrity(long userId) {
    int followers = userService.getFollowerCount(userId);
    if (followers > CELEBRITY_THRESHOLD) return true;         // static: 10K

    // dynamic: check if recent fan-out was slow
    Duration lastFanoutDuration = metricsService.getLastFanoutDuration(userId);
    if (lastFanoutDuration != null && lastFanoutDuration.toMinutes() > 5) {
        userService.markAsCelebrity(userId);                  // reclassify
        return true;
    }
    return false;
}
```

---

## 11. Fan-Out Service Deep Dive

### Architecture

```
                         Kafka
                    (tweet-events topic)
                    Partitioned by user_id hash
                           │
              ┌────────────┼────────────┐
              │            │            │
        ┌─────▼────┐ ┌────▼─────┐ ┌───▼──────┐
        │ Fan-Out  │ │ Fan-Out  │ │ Fan-Out  │
        │ Worker 1 │ │ Worker 2 │ │ Worker N │    (scale by partitions)
        │(partition │ │(partition│ │(partition│
        │  0-3)    │ │  4-7)   │ │  8-11)   │
        └────┬─────┘ └────┬────┘ └────┬─────┘
             │             │           │
             ▼             ▼           ▼
        ┌──────────────────────────────────┐
        │          Redis Cluster           │
        │    (Timeline Sorted Sets)        │
        │  timeline:u1 = {T5,T4,T3,T2,T1} │
        │  timeline:u2 = {T7,T5,T3}       │
        └──────────────────────────────────┘
```

### Fan-Out Worker Logic (Pseudocode)

```java
@KafkaListener(topics = "tweet-events")
public void onTweetPublished(TweetEvent event) {
    long userId = event.getUserId();
    long tweetId = event.getTweetId();
    long timestamp = event.getTimestamp();

    // Step 1: Celebrity check
    if (isCelebrity(userId)) {
        // Celebrity: SKIP fan-out. Tweet is in user timeline only.
        // It will be pulled at read time by followers.
        metrics.increment("fanout.skipped.celebrity");
        return;
    }

    // Step 2: Get all follower IDs
    List<Long> followerIds = socialGraphService.getFollowerIds(userId);

    // Step 3: Batch fan-out to Redis
    // Partition followers into batches of 1000 for efficiency
    List<List<Long>> batches = Lists.partition(followerIds, 1000);

    for (List<Long> batch : batches) {
        // Use Redis pipeline for batch writes
        try (RedisPipeline pipeline = redis.pipelined()) {
            for (long followerId : batch) {
                // ZADD timeline:{followerId} {timestamp} {tweetId}
                pipeline.zadd("timeline:" + followerId, timestamp, String.valueOf(tweetId));

                // Trim to keep only latest 800 entries (prevent unbounded growth)
                pipeline.zremrangeByRank("timeline:" + followerId, 0, -801);
            }
            pipeline.sync();
        }
    }

    metrics.increment("fanout.completed");
    metrics.recordTime("fanout.duration", System.currentTimeMillis() - event.getTimestamp());
}
```

### Key Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Message queue | Kafka | High throughput, partitioned, replayable |
| Partitioning strategy | Hash of poster's user_id | All events from same user go to same partition (ordering) |
| Batch size | 1000 followers per Redis pipeline | Balance between latency and throughput |
| Timeline size limit | 800 entries per user | Beyond 800, tweets are older than a week; use DB fallback |
| TTL on timeline entries | 7 days | Old entries auto-expire |
| Worker scaling | Based on Kafka consumer lag | Auto-scale when lag exceeds threshold |

### Monitoring and Auto-Scaling

```
┌─────────────┐     ┌───────────────┐     ┌──────────────┐
│ Kafka Lag   │────▶│  Monitoring   │────▶│  Auto-Scale  │
│ Metrics     │     │  (Prometheus) │     │  Fan-Out     │
│             │     │               │     │  Workers     │
│ lag > 100K  │     │  Alert if     │     │  +5 workers  │
│ per partition│     │  lag > 500K   │     │  per alert   │
└─────────────┘     └───────────────┘     └──────────────┘
```

---

## 12. Feed Generation (Read Path)

### Step-by-Step Flow (Numbered)

```
User opens app → GET /api/feed?cursor=&limit=20

Step 1: Authentication & Rate Limiting
  │  API Gateway validates JWT token, checks rate limit
  ▼
Step 2: Read pre-computed timeline from Redis
  │  ZREVRANGE timeline:{user_id} cursor 20
  │  Result: [tweet_id_1, tweet_id_2, ..., tweet_id_20]
  │  These are tweets from NORMAL users (fan-out on write)
  ▼
Step 3: Get celebrity follow list
  │  Redis/Cache lookup: celebrity_follows:{user_id}
  │  Result: [celeb_1, celeb_2, ..., celeb_15]
  │  (Typically 5-20 celebrities per user)
  ▼
Step 4: Fetch celebrity tweets
  │  For EACH celebrity in the list:
  │    ZREVRANGE user_timeline:{celeb_id} 0 4
  │    (Get latest 5 tweets from each celebrity)
  │  Total: 15 celebrities x 5 tweets = 75 candidate tweets
  │  Parallelized: all 15 queries execute concurrently
  ▼
Step 5: Merge pre-computed + celebrity tweets
  │  Combined candidate set: 20 (cache) + 75 (celebrity) = 95 tweets
  │  De-duplicate (user may have retweeted a celebrity tweet)
  ▼
Step 6: Rank by score
  │  score = time_decay x engagement_weight x social_affinity
  │  Sort by score descending
  ▼
Step 7: Hydrate tweet objects
  │  For top 20 tweet_ids, fetch full tweet objects:
  │    - Tweet content, media URLs from Cassandra/cache
  │    - User info (username, avatar) from user cache
  │    - Engagement counts (likes, retweets)
  │    - "is_liked_by_me" check
  │  Batch fetch, parallelized
  ▼
Step 8: Return paginated response
  │  Response includes:
  │    - 20 hydrated tweets
  │    - next_cursor (tweet_id of last item)
  │    - has_more flag
  ▼
Step 9: Prefetch next page (async, background)
  │  Feed Service pre-computes the next page
  │  and caches it for faster subsequent load
```

### Latency Breakdown

| Step | Operation | Latency |
|------|-----------|---------|
| 2 | Redis ZREVRANGE | ~1-2ms |
| 3 | Celebrity list lookup | ~1ms |
| 4 | 15 celebrity tweet queries (parallel) | ~5-10ms |
| 5 | Merge | ~1ms (in-memory) |
| 6 | Rank | ~2ms (in-memory) |
| 7 | Hydrate 20 tweets (parallel batch) | ~10-20ms |
| **Total** | | **~20-35ms** (well within 500ms) |

---

## 13. Trending Topics

### How Trending Detection Works

> Trending is not about **volume** -- it is about **velocity**. A hashtag used 1M times/day is not trending if its average is 1M/day. A hashtag that jumps from 100/day to 50K/day IS trending.

### Trending Pipeline

```
┌─────────┐   ┌─────────┐   ┌──────────────┐   ┌─────────────┐
│  Tweet  │──▶│  Kafka  │──▶│   Trending   │──▶│    Redis    │
│ Service │   │(hashtag │   │   Consumer   │   │ Sorted Sets │
│         │   │ events) │   │              │   │             │
│ Parse   │   └─────────┘   │ ZINCRBY per  │   │ trending:1h │
│ hashtags│                 │ hashtag per  │   │ trending:6h │
│ from    │                 │ window       │   │ trending:24h│
│ tweet   │                 └──────────────┘   └──────┬──────┘
└─────────┘                                           │
                                                      ▼
                                              ┌───────────────┐
                                              │  Periodic Job │
                                              │ (every 1 min) │
                                              │               │
                                              │ Compute score:│
                                              │ (current -    │
                                              │  baseline) /  │
                                              │  baseline     │
                                              │  x time_decay │
                                              └───────┬───────┘
                                                      │
                                                      ▼
                                              ┌───────────────┐
                                              │  Trending     │
                                              │  Results      │
                                              │  Cache        │
                                              │ (TTL: 1 min)  │
                                              └───────────────┘
```

### Trending Score Formula

```
trending_score = (current_window_count - avg_baseline_count)
                 / avg_baseline_count
                 * time_decay_factor

Where:
  current_window_count = # mentions in last 1 hour
  avg_baseline_count   = average # mentions in same hour over last 7 days
  time_decay_factor    = e^(-lambda * hours_since_peak)

Example:
  #WorldCup: 500K mentions in last hour, avg baseline = 5K/hour
  score = (500000 - 5000) / 5000 * 0.95 = 94.05  → TRENDING

  #GoodMorning: 200K mentions in last hour, avg baseline = 180K/hour
  score = (200000 - 180000) / 180000 * 0.95 = 0.105 → NOT trending
```

### Redis Data Structure for Trending

```
# Per time window, per region
Key: trending:1h:US
Type: Sorted Set

# When a tweet with #WorldCup is published:
ZINCRBY trending:1h:US 1 "#WorldCup"
ZINCRBY trending:6h:US 1 "#WorldCup"
ZINCRBY trending:24h:US 1 "#WorldCup"

# To get top 10 trending:
ZREVRANGE trending:1h:US 0 9 WITHSCORES

# Expire old windows:
# Every hour, rotate: delete trending:1h:US, create new one
# Use key naming with timestamp: trending:1h:US:2026041810
```

### Sliding Window Implementation

```
Window approach: Use multiple overlapping fixed windows.

Time:  |----W1----|----W2----|----W3----|
            |----W1'----|----W2'----|
                  |----W1''---|----W2''---|

Each window = Redis sorted set with TTL = 2x window size.
Trending query = sum of last 2 overlapping windows.

Alternative: HyperLogLog per hashtag per 5-min bucket.
  - Counts unique users (not just mentions) to resist spam.
  - PFADD hashtag:worldcup:202604181030 user_id
  - PFCOUNT hashtag:worldcup:202604181030
```

### Edge Cases in Trending

| Edge Case | Solution |
|-----------|----------|
| **Spam/bot manipulation** | Require minimum unique users (not just tweet count). Filter if 80% of mentions come from < 100 accounts. |
| **Geo-trending** | Separate sorted sets per country/city. Use user's profile location. |
| **Offensive content trending** | Content moderation layer filters blacklisted terms. Human review for borderline cases. |
| **Trend stays too long** | Time decay factor. After 6 hours, score decays even if volume remains high. |
| **New vs. recurring event** | Compare against 7-day baseline. Recurring events (e.g., #MondayMotivation) have high baseline, so they need higher spike to trend. |

---

## 14. Edge Cases

### 14.1 Celebrity Follows Celebrity

```
Scenario: Taylor Swift (80M followers) follows Elon Musk (50M followers).

Both are fan-out-on-read. When Taylor opens her feed:
  1. Read pre-computed cache (tweets from normal users she follows)
  2. Pull celebrity tweets (including Elon's) from their user timelines
  3. Merge and rank

No special handling needed. Both celebrity tweets come from the pull path.
```

### 14.2 User Unfollows a Celebrity

```
Scenario: User unfollows Elon Musk.

Action:
  1. Remove Elon from user's "following" list in social graph
  2. Remove Elon from user's celebrity_follows cache
  3. NO need to touch pre-computed timeline cache
     (Elon's tweets were NEVER in the pre-computed cache --
      they were always pulled at read time)

Cost: O(1). Cheap.
```

### 14.3 User Unfollows a Normal User

```
Scenario: User unfollows Alice (200 followers).

Problem: Alice's tweets ARE in the user's pre-computed timeline cache.
  Removing them requires scanning the sorted set -- expensive.

Solutions (choose one):
  a. Let them AGE OUT: Timeline entries have 7-day TTL. Old tweets
     disappear naturally. Slightly stale feed for a few days.
  b. LAZY FILTER at read time: When hydrating tweets, check if user
     still follows the poster. Filter out unfollowed users' tweets.
     Costs a few ms per read but avoids cache modification.
  c. BACKGROUND CLEANUP: Async job removes unfollowed user's tweets
     from cache. Not time-critical.

Recommended: Option (b) -- lazy filter at read time.
```

### 14.4 Tweet Deletion

```
Scenario: User deletes a tweet that was already fan-out'd to 200 timelines.

Problem: Removing from all 200 pre-computed timelines is expensive.

Solution:
  1. SOFT DELETE: Mark tweet as is_deleted=true in tweets table
  2. At read time (Step 7 in feed generation): when hydrating tweet
     objects, filter out deleted tweets
  3. Eventually, deleted tweet_ids age out of timeline caches (TTL)

Never attempt to remove from all pre-computed timelines.
```

### 14.5 Viral Tweet by Normal User (Follower Explosion)

```
Scenario:
  - Alice has 500 followers, posts a tweet
  - Tweet fans out to 500 timelines (normal)
  - Tweet goes viral. Alice gains 5M new followers in 2 hours.

Problem:
  - Original 500 followers have the tweet (via fan-out)
  - 5M NEW followers do NOT have it in their timeline cache
  - It was already fanned out before they followed

Solutions:
  a. Accept the gap: New followers see the tweet on Alice's profile
     (user timeline) and in search results, but NOT in home feed.
     This is the simplest and what Twitter actually does.
  b. Reclassify Alice as celebrity: Once she crosses 10K followers,
     future tweets use fan-out-on-read. No backfill for old tweets.
  c. Backfill (expensive): Trigger a fan-out job for new followers
     only. Complex to implement, rarely worth it.

Recommended: Option (a) for the viral tweet, option (b) going forward.
```

### 14.6 New User Cold Start

```
Scenario: Brand new user signs up, follows 50 people.

Problem: No pre-computed timeline cache exists.

Solution:
  1. On first follow batch, trigger "cold start" cache build
  2. For each followed normal user: pull their latest 10 tweets
  3. For each followed celebrity: pull their latest 5 tweets
  4. Merge all tweets, rank, populate the timeline cache
  5. Subsequent reads work normally from the cache
  6. Future tweets from followed users will be fan-out'd normally

Latency for first feed load: ~200-500ms (acceptable, one-time cost)
```

### 14.7 User Follows 5000 People

```
Scenario: Power user follows 5000 accounts, 200 of which are celebrities.

Problem: Step 4 in feed generation requires querying 200 celebrity
timelines. That's 200 Redis queries, even parallelized = slow.

Solutions:
  a. CAP celebrity pull list at 50: Rank by "social affinity"
     (how often user interacts with each celebrity). Only pull
     top 50 most relevant celebrities.
  b. PRE-COMPUTE celebrity merge: Background job periodically
     merges celebrity tweets for heavy users and stores result.
  c. TIERED approach: Pull top 20 most-interacted celebrities
     synchronously, rest async in background for next page.

Recommended: Option (a) -- cap at 50 with affinity ranking.
```

### 14.8 Rate Limiting on Posting

```
Problem: Spam accounts posting thousands of tweets/day,
  each triggering fan-out operations.

Solution:
  - Rate limit: max 300 tweets/day per user (API Gateway)
  - Sliding window rate limiter in Redis:
    Key: rate:tweets:{user_id}:{date}
    INCR on each tweet, EXPIRE after 24h
    Reject if count > 300
  - Additional: detect burst patterns (100 tweets in 1 minute)
    and temporarily throttle
```

### 14.9 Thundering Herd on Celebrity Tweet

```
Scenario: Breaking news. Celebrity tweets. 50M followers all
  open the app within 60 seconds.

Problem:
  - 50M concurrent requests for the same celebrity's tweets
  - All hit the celebrity's user timeline in Redis
  - Cache stampede / hot key problem

Solutions:
  a. SINGLEFLIGHT / REQUEST COALESCING:
     Only ONE request fetches the celebrity's latest tweets.
     All other concurrent requests wait for and share that result.

  b. LOCAL CACHE on Feed Service:
     Cache celebrity tweets in local memory (Caffeine cache)
     with 10-second TTL. 50M requests reduce to ~10 cache misses.

  c. REDIS READ REPLICAS:
     Celebrity user timelines replicated to multiple Redis replicas.
     Spread read load across replicas.

  d. PREEMPTIVE CACHE WARMING:
     When celebrity tweets, proactively cache their latest tweets
     on all Feed Service instances.

Recommended: Combine (a) + (b). Singleflight at app level +
  local cache with short TTL.
```

### 14.10 Mixed Media Tweets

```
Problem: Media (images, videos) are large. Cannot store in tweet DB.

Solution:
  1. Client uploads media to Media Service FIRST
  2. Media Service stores in S3, returns CDN URL
  3. Client includes CDN URLs in tweet creation request
  4. Tweet table stores only media_urls (list of strings)
  5. Client renders media by fetching from CDN directly

Flow:
  Client → POST /api/media/upload → Media Service → S3
  Client ← { "media_url": "https://cdn.example.com/img/abc.jpg" }
  Client → POST /api/tweets { content: "...", media_urls: ["https://..."] }

Storage separation: tweet text in Cassandra, media in S3/CDN.
```

---

## 15. Ranking / Feed Scoring

### Level 1: Chronological (Simplest)

```
score = tweet_timestamp

Sorted by newest first. Twitter used this before 2016.
Pros: Simple, predictable, no ML needed.
Cons: Users miss important tweets if they follow many people.
```

### Level 2: Engagement-Based (Recommended for Interview)

```
score = time_decay(tweet_age)
        * (like_count * 1.0 + retweet_count * 2.0 + reply_count * 1.5)
        * social_affinity(viewer, poster)
        * author_quality_score

Where:
  time_decay(age) = 1 / (1 + age_in_hours * 0.1)
    → 1 hour old: 0.91, 6 hours: 0.63, 24 hours: 0.29

  social_affinity(viewer, poster) = normalized score based on:
    - How often viewer likes poster's tweets
    - How often viewer replies to poster
    - How often viewer visits poster's profile
    - Recency of interactions
    Range: 0.1 (never interact) to 2.0 (frequent interaction)

  author_quality_score = based on follower count, verified status
    Range: 0.5 to 1.5
```

### Level 3: ML Ranking (Mention Only)

```
For interview: "If we want to go further, we'd use an ML pipeline."

Pipeline:
  1. CANDIDATE GENERATION: Get 500 candidate tweets (from cache + celebrity pull)
  2. FEATURE EXTRACTION: tweet features, user features, interaction features
  3. RANKING MODEL: Predict P(engagement) for each tweet
  4. RE-RANKING: Apply diversity rules (no more than 3 tweets from same user)
  5. Return top 20

Do NOT go deep on ML in the interview unless asked.
Say: "Chronological first, then add engagement signals as optimization."
```

---

## 16. Scaling Strategy

| Component | Scaling Approach | Details |
|-----------|-----------------|---------|
| **Tweet Service** | Horizontal, stateless | Any instance can handle any tweet. Scale by CPU. |
| **Fan-Out Service** | Kafka consumer groups | Scale by adding partitions + consumers. Auto-scale on lag. |
| **Feed Service** | Horizontal, stateless | Redis-backed. Scale by adding instances behind LB. |
| **Social Graph** | Sharded by user_id | Adjacency lists in Redis. Shard by hash(user_id) % N. |
| **Timeline Cache** | Redis Cluster | Each user's timeline = one sorted set. Distributed across cluster. |
| **Tweet Storage** | Cassandra (auto-sharded) | Partitioned by user_id. Write throughput scales linearly. |
| **Search** | Elasticsearch cluster | Shard by time range. New index per day/week. |
| **Media** | S3 + CDN (CloudFront) | Infinitely scalable object storage + edge caching. |

### Horizontal Scaling Diagram

```
                    ┌─────────────────────────┐
                    │   Load Balancer (L7)     │
                    └────────┬────────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
         ┌────▼───┐    ┌────▼───┐    ┌────▼───┐
         │ Feed   │    │ Feed   │    │ Feed   │   ← stateless
         │ Svc 1  │    │ Svc 2  │    │ Svc N  │      (scale out)
         └───┬────┘    └───┬────┘    └───┬────┘
             │             │             │
             └─────────────┼─────────────┘
                           │
                    ┌──────▼──────┐
                    │ Redis       │
                    │ Cluster     │    ← sharded by user_id
                    │ (6 master + │
                    │  6 replica) │
                    └─────────────┘
```

---

## 17. Database Choice

### Database Selection Matrix

| Data | Database | Why This Choice | Alternatives Considered |
|------|----------|----------------|------------------------|
| **User profiles** | PostgreSQL | Relational, ACID, complex queries, small dataset (~300M rows) | MySQL (similar), DynamoDB (less flexible queries) |
| **Tweets** | Cassandra | High write throughput, partition by user_id, time-series clustering, linear scalability | DynamoDB (more expensive), MongoDB (less scalable writes) |
| **Follow graph** | Cassandra + Redis | Cassandra for persistence, Redis for hot path (follower list lookups) | Neo4j (too slow for fan-out scale), PostgreSQL (hard to shard) |
| **Timeline cache** | Redis Cluster | Sorted sets, sub-ms reads, TTL support, perfect for pre-computed timelines | Memcached (no sorted sets), DynamoDB (higher latency) |
| **Trending** | Redis (Sorted Sets) | ZINCRBY is O(log N), ZREVRANGE for top-K, perfect for counters | Apache Flink (more complex), Kafka Streams (higher latency) |
| **Search** | Elasticsearch | Full-text search, inverted index, relevance ranking | Solr (similar), PostgreSQL FTS (doesn't scale) |
| **Media** | S3 + CloudFront | Unlimited storage, CDN for global delivery | GCS (similar), Azure Blob (similar) |
| **Message queue** | Kafka | High throughput, partitioned, replayable, exactly-once semantics | RabbitMQ (lower throughput), SQS (less flexible) |

### Why Cassandra for Tweets?

```
Requirements match Cassandra strengths:
  ✓ High write throughput (500M tweets/day = 6K/sec)
  ✓ Partition by user_id (all user's tweets co-located)
  ✓ Cluster by tweet_id DESC (latest first - free ordering)
  ✓ Linear horizontal scalability
  ✓ No complex joins needed
  ✓ Tunable consistency (write at QUORUM, read at ONE for speed)

Schema design:
  PRIMARY KEY (user_id, tweet_id)
  → Partition key: user_id → locates the node
  → Clustering key: tweet_id DESC → sorts within partition
  → "Get user's latest 20 tweets" = single partition read = FAST
```

### Why Redis for Timeline Cache?

```
Requirements match Redis strengths:
  ✓ Sorted Set: ZADD O(log N), ZREVRANGE O(log N + M)
  ✓ Sub-millisecond reads (timeline = one ZREVRANGE call)
  ✓ Pipeline support (batch fan-out writes)
  ✓ TTL on keys (auto-expire old timelines)
  ✓ Redis Cluster for horizontal sharding
  ✓ In-memory: 200 entries x 8 bytes per entry = 1.6 KB per user
     300M users x 1.6 KB = ~480 GB (fits in a modest Redis Cluster)
```

---

## 18. Caching Strategy

### What to Cache

| Data | Cache | TTL | Invalidation |
|------|-------|-----|-------------|
| **Pre-computed timelines** | Redis Sorted Set | 7 days | Entries added by fan-out, trimmed to 800 |
| **User profile** | Redis Hash | 1 hour | Invalidate on profile update |
| **Follower/following lists** | Redis Set | 30 min | Invalidate on follow/unfollow |
| **Celebrity follow list** | Redis Set per user | 30 min | Invalidate on follow/unfollow of celebrity |
| **Tweet content (hot tweets)** | Redis String / local cache | 5 min | Soft delete flag checked at read |
| **Trending results** | Redis String (serialized) | 1 min | Recomputed every minute |
| **User's "liked tweets" set** | Redis Set | 10 min | Used for "is_liked_by_me" check |

### Cache Architecture

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   L1 Cache   │────▶│   L2 Cache   │────▶│  Database    │
│ (Local/App)  │miss │   (Redis)    │miss │ (Cassandra/  │
│              │     │              │     │  PostgreSQL) │
│ Caffeine     │     │ Redis Cluster│     │              │
│ TTL: 10-60s  │     │ TTL: varies  │     │              │
│ Size: 1GB    │     │ Size: 500GB  │     │              │
└──────────────┘     └──────────────┘     └──────────────┘

L1: Hot tweets, celebrity tweet lists, trending results
L2: Timelines, user profiles, social graph, all tweet content
L3: Full data (source of truth)
```

### Cache Stampede Prevention

```
Problem: Cache key expires → 1000 concurrent requests all miss →
  1000 DB queries for same data → DB overwhelmed

Solutions used:
  1. SINGLEFLIGHT: Only first request hits DB. Others wait for result.
  2. STALE-WHILE-REVALIDATE: Serve stale cache while refreshing async.
  3. LOCK-BASED: Acquire distributed lock before DB query. Others wait.
  4. EARLY EXPIRATION: Randomly refresh cache before TTL expires.
```

---

## 19. CAP Theorem Analysis

### Classification: AP System (Availability + Partition Tolerance)

```
┌─────────────────────────────────────────────────┐
│                 CAP Theorem                       │
│                                                   │
│    Consistency ──────── Availability               │
│         \                  /                       │
│          \    We choose   /                        │
│           \    AP (*)    /                          │
│            \            /                           │
│             \          /                            │
│              Partition                              │
│              Tolerance                              │
│          (must have for                            │
│           distributed)                             │
└─────────────────────────────────────────────────┘
```

### Consistency Decisions by Feature

| Feature | Consistency Level | Rationale |
|---------|------------------|-----------|
| **Home timeline** | Eventually consistent | Stale feed for 5-10 seconds is acceptable. User refreshes to get latest. |
| **User timeline** | Eventually consistent | Own tweets appear instantly (read-your-writes). Others see within seconds. |
| **Follow/unfollow** | Strongly consistent (for fan-out) | Must be correct: affects which tweets are fan-out'd. Use quorum writes. |
| **Like/retweet counts** | Eventually consistent | Approximate counts are fine. Off by a few is acceptable. |
| **Trending** | Eventually consistent | Computed periodically (every 1 min). Slight lag is fine. |
| **Tweet creation** | Durable (no loss) | Write to Cassandra at QUORUM before returning 201. |

### Read-Your-Writes Consistency

```
Problem: User posts tweet, refreshes feed, doesn't see own tweet.

Solution:
  1. After posting, return the tweet to the client immediately
  2. Client injects it into the local feed (optimistic UI)
  3. Meanwhile, fan-out happens in background
  4. On next server fetch, tweet is in pre-computed cache

User perceives instant publishing. Actual fan-out is async.
```

---

## 20. Cloud Services Mapping

| Component | AWS | GCP | Azure |
|-----------|-----|-----|-------|
| **API Gateway** | API Gateway | Apigee / Cloud Endpoints | API Management |
| **Load Balancer** | ALB / NLB | Cloud Load Balancing | Azure Load Balancer |
| **Compute (Services)** | ECS / EKS (Fargate) | GKE / Cloud Run | AKS / Container Apps |
| **Timeline Cache** | ElastiCache (Redis) | Memorystore (Redis) | Azure Cache for Redis |
| **Tweet Storage** | DynamoDB or Keyspaces (managed Cassandra) | Bigtable or Datastore | Cosmos DB (Cassandra API) |
| **User DB** | RDS (PostgreSQL) | Cloud SQL (PostgreSQL) | Azure DB for PostgreSQL |
| **Message Queue** | MSK (Managed Kafka) | Pub/Sub | Event Hubs (Kafka) |
| **Search** | OpenSearch | Elastic Cloud on GCP | Cognitive Search |
| **Object Storage** | S3 | Cloud Storage | Blob Storage |
| **CDN** | CloudFront | Cloud CDN | Azure CDN |
| **Monitoring** | CloudWatch + X-Ray | Cloud Monitoring + Trace | Azure Monitor |
| **Auto-Scaling** | ECS Auto Scaling | GKE Autoscaler | AKS KEDA |

---

## 21. Tradeoffs Summary

| Decision | Option A | Option B | **Our Choice** | Why |
|----------|----------|----------|----------------|-----|
| Fan-out strategy | Fan-out on Write | Fan-out on Read | **Hybrid** | Best of both: fast reads for normal users, no write amplification for celebrities |
| Celebrity threshold | Static (10K) | Dynamic (queue lag) | **Both** | Static for predictability, dynamic for edge cases |
| Timeline consistency | Strong | Eventual | **Eventual** | 5-10s staleness is acceptable; enables higher availability and performance |
| Tweet storage | SQL (PostgreSQL) | NoSQL (Cassandra) | **Cassandra** | Write throughput, partition design, linear scalability |
| Feed ranking | Chronological | ML-based | **Chronological + engagement** | Start simple, add engagement signals. Full ML is separate project. |
| Timeline cache size | Unlimited | Fixed 800 | **Fixed 800** | Bounded memory. Older tweets fetched from DB on deep pagination. |
| Unfollow cleanup | Immediate removal | Lazy filter at read | **Lazy filter** | Cheaper. Avoids expensive cache modification. |
| Tweet deletion | Hard delete from all caches | Soft delete + filter | **Soft delete** | Cannot remove from 200+ caches. Filter at read time. |
| Media storage | Inline with tweet | Separate (S3/CDN) | **Separate** | Different access patterns, different storage costs |
| Search | SQL LIKE queries | Elasticsearch | **Elasticsearch** | Full-text search at scale, relevance ranking |

---

## 22. Interview Talking Points

### Time Allocation Guide (40-Minute Interview)

```
┌────────────────────────────────────────────────┐
│ Minutes 0-3:   Problem statement & scope       │
│ Minutes 3-5:   Requirements & assumptions      │
│ Minutes 5-8:   API design (key endpoints)      │
│ Minutes 8-10:  Traffic estimates (back-of-env)  │
│ Minutes 10-12: Data model (key tables)         │
│ Minutes 12-15: High-level architecture         │
│                                                │
│ ★ Minutes 15-28: FAN-OUT & CELEBRITY PROBLEM ★ │
│   - Three approaches (push, pull, hybrid)      │
│   - Hybrid deep dive with math                 │
│   - Fan-out service internals                  │
│   - Feed generation read path                  │
│                                                │
│ Minutes 28-33: Trending topics                 │
│ Minutes 33-37: Edge cases                      │
│ Minutes 37-40: Scaling, DB choices, tradeoffs  │
└────────────────────────────────────────────────┘
```

### What to Proactively Mention

1. **Start with the hybrid fan-out approach.** Don't wait for the interviewer to ask. Say: "The core challenge is fan-out. Let me walk through three approaches and why hybrid wins."

2. **Show the math.** Celebrity: 50M followers = 50M writes = 8 min. That's why pure push fails. Normal: 200 queries at read time. That's why pure pull fails. Hybrid: push for 99%, pull for 1%.

3. **Mention Twitter specifically.** "Twitter actually uses this hybrid approach. They switched from pure fan-out-on-write around 2012 when they hit scaling limits."

4. **Edge cases show depth.** Bring up: tweet deletion (soft delete), viral normal user (reclassify), thundering herd (singleflight + local cache), unfollowing (lazy filter).

5. **Trending velocity vs. volume.** "#GoodMorning has high volume every day. It's not trending. Trending = unusual spike relative to baseline."

6. **Read-heavy optimization.** "600:1 read-write ratio means we optimize the read path. Pre-computed timelines in Redis give us O(1) reads."

7. **Cursor-based pagination.** "Offset-based breaks when new tweets are inserted. Cursor-based (last seen tweet_id) gives stable pagination."

### Common Interviewer Follow-ups

| Question | Key Points in Answer |
|----------|---------------------|
| "How do you handle a user with 50M followers?" | Hybrid: skip fan-out, pull at read time. Celebrity detection. Singleflight for hot reads. |
| "What if Redis goes down?" | Feed degrades to pull-from-DB mode. Slower but functional. Rebuild cache from Cassandra. Multi-AZ Redis Cluster for HA. |
| "How do you ensure no duplicate tweets in feed?" | De-duplication at merge step. Tweet IDs are unique (Snowflake). Set-based check during merge. |
| "How do you handle real-time updates?" | WebSocket / SSE for live updates. New tweet events pushed to connected clients. Fallback: poll every 30 seconds. |
| "What about privacy / blocked users?" | Filter at read time. Maintain blocked-user set per user. Check during feed hydration step. |
| "How would you add ads/promoted tweets?" | Separate ads service. Insert promoted tweets at positions 3, 8, 15 in feed. Auction-based ad selection. |
| "How do you shard the social graph?" | Shard by user_id. Follower lookup: query the followee's shard. Following lookup: query the follower's shard. Two tables for bidirectional. |
| "What monitoring would you add?" | Fan-out lag (Kafka consumer lag), feed latency p50/p99, cache hit rate, error rates per service. Alert on fan-out lag > 5 min. |

### Signals That Impress Interviewers

- **Quantitative reasoning:** "50M writes at 100K/sec = 500 seconds. That's unacceptable."
- **Tradeoff articulation:** "We trade write complexity for read simplicity because reads outnumber writes 600:1."
- **Production awareness:** "Tweet deletion is soft-delete because removing from 200 pre-computed caches is too expensive."
- **Progressive refinement:** "Start with chronological ranking, add engagement signals, and ML ranking is a separate project."
- **Acknowledging complexity:** "The hybrid approach adds complexity to the read path, but the math shows it's necessary at this scale."

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────────────┐
│               SOCIAL MEDIA FEED - CHEAT SHEET               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Scale: 300M MAU, 500M tweets/day, 3B reads/day            │
│  Ratio: 600:1 read:write                                   │
│                                                             │
│  HYBRID FAN-OUT:                                            │
│    Normal (< 10K followers) → Push to all follower caches   │
│    Celebrity (> 10K followers) → Pull at read time          │
│                                                             │
│  READ PATH:                                                 │
│    1. Redis cache (normal tweets)        ~2ms               │
│    2. Pull celebrity tweets (parallel)   ~10ms              │
│    3. Merge + rank                       ~3ms               │
│    4. Hydrate                            ~15ms              │
│    Total:                                ~30ms              │
│                                                             │
│  TRENDING = velocity, not volume                            │
│    score = (current - baseline) / baseline * decay          │
│                                                             │
│  KEY STORES:                                                │
│    Redis: timelines, trending, social graph cache           │
│    Cassandra: tweets, follows                               │
│    PostgreSQL: users                                        │
│    Elasticsearch: search                                    │
│    S3/CDN: media                                            │
│                                                             │
│  TOP EDGE CASES:                                            │
│    Tweet deletion → soft delete, filter at read             │
│    Viral normal user → reclassify as celebrity              │
│    Thundering herd → singleflight + local cache             │
│    Unfollow → lazy filter (don't clean cache)               │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

*Prepared for senior Java developer (7+ years) system design interview. Focus 40% of time on the celebrity problem and hybrid fan-out strategy.*
