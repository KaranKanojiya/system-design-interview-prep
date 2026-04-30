# High-Level Design: News Feed System (Facebook/LinkedIn/Twitter)

> **Difficulty:** HARD | **Interview Time:** 35-45 minutes | **Focus:** Fan-out strategies, feed ranking, real-time push, infinite scroll, production-scale concerns

> **NOTE:** Project 05 (Social Media Feed) covers basic fan-out and the celebrity problem. This project goes DEEPER into algorithmic ranking, real-time push architecture, infinite scroll pagination, multi-content types, and production-scale concerns that separate senior from mid-level answers.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Data Model](#7-data-model)
8. [High-Level Architecture](#8-high-level-architecture)
9. [Component Deep Dive](#9-component-deep-dive)
10. [Fan-out Deep Dive](#10-fan-out-deep-dive)
11. [Feed Ranking](#11-feed-ranking)
12. [Pagination and Infinite Scroll](#12-pagination-and-infinite-scroll)
13. [Real-time Updates](#13-real-time-updates)
14. [Concurrency](#14-concurrency)
15. [Scaling](#15-scaling)
16. [Database Choice](#16-database-choice)
17. [CAP Theorem](#17-cap-theorem)
18. [Cloud Services](#18-cloud-services)
19. [Tradeoffs Summary](#19-tradeoffs-summary)
20. [Interview Talking Points](#20-interview-talking-points)

---

## 1. Problem Statement

Design a **News Feed System** (like Facebook, LinkedIn, or Twitter) that generates a personalized, ranked feed for each user by aggregating posts from friends and followed accounts, supporting real-time updates, infinite scroll pagination, multiple content types (text, images, video, links, polls), and algorithmic ranking -- all at a scale of 1 billion users.

**Why is it needed?**

- The news feed is the single highest-traffic page on every social platform -- Facebook serves over 100 billion feed impressions per day.
- Feed quality directly drives user engagement, time-on-platform, and ad revenue. A 1% improvement in feed ranking at Facebook was estimated to be worth over $100M/year in engagement.
- The engineering challenge is extreme: you must merge content from hundreds of sources, score and rank it in real time, deliver it in under 200ms, and handle celebrity posts that affect 50M+ timelines without melting the infrastructure.
- Real-time expectations are rising -- users expect to see a friend's new post within seconds, not minutes. This requires a push architecture that coexists with the pull-based ranking pipeline.
- Infinite scroll creates unique pagination challenges: new posts arrive at the top while the user is scrolling down, and naive offset-based pagination breaks catastrophically.

**Core Workflow:**

```
User Alice opens the Facebook app and pulls down to refresh her news feed.

(1) Client sends GET /feed?cursor=null&limit=20 (first page)
(2) API Gateway authenticates Alice (JWT), rate-limits, routes to Feed Service
(3) Feed Service reads Alice's pre-computed timeline from Redis
    → Returns 500 candidate post IDs (from fan-out-on-write by normal friends)
(4) Feed Service queries Social Graph for Alice's celebrity follows
    → Returns [celebrity_1, celebrity_2, ..., celebrity_12]
(5) Feed Service pulls latest posts from each celebrity's user timeline
    → Returns 60 additional candidate post IDs (fan-out-on-read)
(6) Feed Service sends 560 candidate post IDs to Ranking Service
(7) Ranking Service scores each candidate:
    score = affinity(Alice, poster) * recency_decay(post_age)
            * engagement_boost(likes, comments, shares)
            * content_type_weight(video > image > text)
            * negative_signal_penalty(hide, report)
(8) Ranking Service returns top 50 scored post IDs
(9) Feed Service applies diversity injection (no 3 posts from same person in a row)
(10) Feed Service hydrates top 20 posts with full content from Post Store
     → Text, media URLs, author info, engagement counts, "liked by me" flags
(11) Feed Service returns paginated response with cursor = last_post_id
(12) Client renders the feed. User scrolls. Client prefetches next page.
(13) Meanwhile, WebSocket connection receives real-time notification:
     "3 new posts available" banner appears at top.
(14) User taps banner. Client inserts new posts above the current scroll position.
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Meta, Google, LinkedIn, Amazon, Twitter/X, and every social platform because it tests the widest range of distributed systems concepts in a single question:

| Skill Tested                     | What Interviewers Look For                                                     |
|----------------------------------|--------------------------------------------------------------------------------|
| **Fan-out Strategy**             | Push vs pull vs hybrid; why pure push fails for celebrities                    |
| **Feed Ranking**                 | Affinity, recency, engagement scoring; two-pass ranking pipeline               |
| **Real-time Push**               | WebSocket/SSE for live updates; "new posts" banner vs auto-inject              |
| **Caching Strategy**             | Per-user timeline cache in Redis; multi-layer cache (L1 local, L2 Redis, L3 DB)|
| **Pagination**                   | Cursor-based (not offset); handling new arrivals during scroll                 |
| **Data Modeling**                | Denormalized for reads; separate stores for different access patterns           |
| **Content Diversity**            | Text, image, video, link, poll -- different storage, rendering, ranking         |
| **Concurrency**                  | Concurrent likes, comment counts, cache invalidation                           |
| **Scale**                        | 1B users, 50B feed reads/day, fan-out to millions of timelines                 |
| **Production Awareness**         | Celebrity problem, thundering herd, cold start, stale reads, gap detection     |

> **Interview tip**: Start by stating the scale (1B users, 500M DAU) and the core tension: "Feed reads are 100x more frequent than writes, so we pre-compute timelines. But celebrities make pre-computation impossible at 50M followers. The answer is hybrid fan-out combined with algorithmic ranking." Then draw the architecture and spend 40% of your time on fan-out + ranking.

---

## 2. Scope

### In Scope

| Feature                          | Description                                                              |
|----------------------------------|--------------------------------------------------------------------------|
| Post Publishing                  | Create posts with text, images, video, links, polls                      |
| News Feed Generation             | Personalized, ranked feed aggregated from all connections                 |
| Feed Ranking                     | Algorithmic scoring (affinity, recency, engagement, content type)        |
| Fan-out (Push + Pull + Hybrid)   | Pre-compute timelines for normal users, pull for celebrities             |
| Like / Comment / Share           | Engagement actions on posts                                              |
| Follow / Unfollow                | Social graph management, celebrity detection                             |
| Real-time Updates                | WebSocket/SSE push for new posts, live engagement counts                 |
| Infinite Scroll Pagination       | Cursor-based pagination with gap detection                               |
| Multi-content Type Support       | Text, image, video, link preview, poll -- different rendering and storage|
| Timeline Cache                   | Per-user sorted post ID list in Redis                                    |

### Out of Scope

| Feature                          | Reason                                                                   |
|----------------------------------|--------------------------------------------------------------------------|
| Direct Messaging / Chat          | Covered in Project 04 (Chat System)                                      |
| Trending Topics / Hashtags       | Covered in Project 05 (Social Media Feed)                                |
| Search (full-text)               | Covered in Project 09 (Search Autocomplete)                              |
| Ads / Promoted Posts             | Separate ads platform with auction-based insertion                        |
| Stories / Ephemeral Content      | Different lifecycle (24h auto-delete) warrants separate design            |
| Group / Page Posts               | Extension of feed model; adds access control complexity                   |
| ML Model Training Pipeline       | Focus on inference/scoring, not training infrastructure                   |
| Content Moderation               | Separate service (NLP/CV based flagging)                                 |
| Analytics / Metrics Dashboard    | Separate analytics pipeline                                              |
| Notification System              | Covered in Project 03 (Notification System) -- referenced but not designed|

---

## 3. Assumptions

### Platform Scale

| Parameter                        | Value                                     | Derivation                                        |
|----------------------------------|-------------------------------------------|---------------------------------------------------|
| Total registered users           | 1 billion                                 | Given (Facebook-scale)                             |
| Daily Active Users (DAU)         | 500 million (50% of total)                | Industry standard for mature social platform       |
| Average friends/follows per user | 300                                       | Given                                              |
| Active posters per day           | 100 million (20% of DAU)                  | Most users consume, few create                     |
| Posts per active poster per day  | 5                                         | Given                                              |
| Total posts per day              | 500 million                               | 100M * 5                                           |
| Feed reads per user per day      | 10                                        | Given (open app, scroll, refresh)                  |
| Total feed reads per day         | 5 billion                                 | 500M DAU * 10                                      |
| Posts per feed page              | 20                                        | Standard infinite scroll page size                 |
| Total post impressions per day   | ~50 billion                               | 5B reads * ~10 posts actually viewed per session   |
| Celebrity threshold              | > 1 million followers                     | Top 0.01% of users                                 |
| Celebrity count                  | ~100,000 users                            | Power law distribution at 1B scale                 |
| Peak posts per second            | 500M / 86400 * 3 (peak factor) = ~17,000  | 3x average for peak hour                           |
| Peak feed reads per second       | 5B / 86400 * 3 = ~175,000                 | 3x average for peak hour                           |

### Data Volume

| Parameter                        | Value                                     |
|----------------------------------|-------------------------------------------|
| Average post size (text + metadata) | ~1 KB                                  |
| Daily post storage               | 500M * 1 KB = ~500 GB/day                |
| Media attachment rate            | 40% of posts have images, 10% have video  |
| Average image size (compressed)  | 200 KB                                    |
| Average video size (compressed)  | 5 MB                                      |
| Daily media storage              | 200M * 200KB + 50M * 5MB = ~290 TB/day   |
| Timeline cache entry size        | 8 bytes (post ID) + 8 bytes (score) = 16 bytes |
| Timeline cache per user          | 500 entries * 16 B = 8 KB                |
| Total timeline cache (all DAU)   | 500M * 8 KB = ~4 TB                      |
| Social graph size                | 1B users * 300 avg connections * 8 B = ~2.4 TB |

### Back-of-the-Envelope: Latency Budget

```
Feed Generation (end-to-end):      Target p99 < 200 ms
  (1) Network RTT (client -> API):         10-20 ms
  (2) API Gateway (auth + rate limit):      3-5 ms
  (3) Redis timeline read (500 IDs):        1-2 ms
  (4) Celebrity tweet pull (parallel):      5-15 ms
  (5) Ranking (score 560 candidates):      10-20 ms
  (6) Diversity injection:                  1-2 ms
  (7) Post hydration (batch, parallel):    15-30 ms
  (8) Response serialization:               2-5 ms
  (9) Network RTT (API -> client):         10-20 ms
  ------------------------------------------------
  Total:                                   57-119 ms

Post Publishing:                    Target p99 < 300 ms
  (1) Network RTT:                         10-20 ms
  (2) API Gateway:                          3-5 ms
  (3) Post validation + write to DB:        5-15 ms
  (4) Kafka event publish (async):          1-2 ms
  (5) Network RTT:                         10-20 ms
  ------------------------------------------------
  Total (sync path):                       29-62 ms
  (fan-out happens async after response)

Real-time Push:                     Target p99 < 1 second
  (1) Post published -> Kafka:              1-5 ms
  (2) Fan-out detection + routing:          5-10 ms
  (3) WebSocket push to connected user:    10-50 ms
  (4) If fan-out-on-write to timeline:     50-500 ms (depending on follower count)
  ------------------------------------------------
  Total (connected user sees notification): 66-565 ms
```

---

## 4. Functional Requirements

### FR-1: Publish a Post
Users can create a post containing text (up to 5000 characters), optional media (up to 10 images or 1 video), an optional link with preview, or a poll with up to 4 options. Each post is assigned a globally unique Snowflake ID, persisted durably, and distributed to followers' timelines asynchronously. The poster sees their own post immediately (read-your-writes consistency).

### FR-2: Generate News Feed
When a user opens the app or scrolls, the system returns a personalized feed of posts from friends and followed accounts. The feed is ranked algorithmically (not purely chronological) based on affinity, recency, engagement, and content type. The feed supports cursor-based infinite scroll pagination with 20 posts per page.

### FR-3: Like / Comment / Share
Users can like a post (toggle on/off), add a comment (threaded, up to 2 levels), or share a post to their own feed. Engagement counts (likes, comments, shares) are visible on each post and update in near-real-time. A user can only like a post once (idempotent).

### FR-4: Follow / Unfollow
Users can follow another user to receive their posts in the news feed, or unfollow to stop receiving them. The system maintains bidirectional social graph data (followers and following lists). Celebrity users (>1M followers) are detected and handled differently in the fan-out pipeline.

### FR-5: Real-time Updates
Connected users receive real-time notifications of new posts from their friends via WebSocket. The notification can be a "3 new posts" banner (pull-to-see) or auto-injected into the feed. Engagement count updates (like count changed) are also pushed in real-time.

### FR-6: Infinite Scroll
The feed supports infinite scroll with cursor-based pagination. As the user scrolls, the client prefetches the next page. New posts arriving while the user scrolls are handled gracefully via gap detection -- the user sees a "new posts available" banner rather than having the feed jump.

### FR-7: Multi-Content Type Support
The system supports multiple post content types:
- **Text-only**: Plain text with optional hashtags and mentions
- **Image**: Up to 10 images with thumbnails, stored on CDN
- **Video**: Single video with transcoded variants (480p, 720p, 1080p)
- **Link**: URL with auto-generated preview (title, description, thumbnail via OGP)
- **Poll**: Question with 2-4 options, vote counts, optional end time

Each content type has different storage, rendering, and ranking characteristics.

---

## 5. Non-Functional Requirements

| Requirement                 | Target                              | Rationale                                                             |
|-----------------------------|-------------------------------------|-----------------------------------------------------------------------|
| **Feed Generation Latency** | p99 < 200 ms                        | Core user experience; faster than Project 05's 500ms target           |
| **Post Publish Latency**    | p99 < 300 ms (sync path)            | Poster sees their own post immediately                                |
| **Real-time Push Latency**  | p99 < 1 second                      | Friend's new post notification within 1 second                        |
| **Fan-out Completion**      | p99 < 5 seconds (normal users)      | All followers have the post in their timeline cache                   |
| **Availability**            | 99.99% (52 min downtime/year)       | Global user base, always-on expectation                               |
| **Consistency**             | Eventually consistent (feed)         | Stale feed for 5-10 seconds acceptable; engagement counts approximate |
| **Read:Write Ratio**        | ~100:1 (feed reads >> post writes)  | Optimize aggressively for the read path                               |
| **Scalability**             | 1B users, 500M DAU, 50B impressions | Must handle 10x peak (major events, holidays)                         |
| **Durability**              | Zero post data loss                  | All posts durably stored before returning success                     |
| **Content Freshness**       | New post visible in feed < 5 seconds | Combination of fan-out latency + real-time push                       |
| **Pagination Stability**    | No duplicate or missing posts on scroll | Cursor-based pagination with gap detection                          |

---

## 6. API Design

### 6.1 Publish a Post

```
POST /api/v1/posts
Authorization: Bearer <jwt_token>
Content-Type: application/json

Request:
{
  "content": "Had an amazing hike in Yosemite! The views were breathtaking.",
  "content_type": "IMAGE",
  "media_ids": ["media_abc123", "media_def456"],
  "mentions": ["user_789"],
  "hashtags": ["#yosemite", "#hiking"],
  "visibility": "FRIENDS"
}

Response: 201 Created
{
  "post_id": "post_7249183746501928",
  "user_id": "user_892374",
  "content": "Had an amazing hike in Yosemite! The views were breathtaking.",
  "content_type": "IMAGE",
  "media_urls": [
    "https://cdn.example.com/img/media_abc123_1080.jpg",
    "https://cdn.example.com/img/media_def456_1080.jpg"
  ],
  "mentions": ["user_789"],
  "hashtags": ["#yosemite", "#hiking"],
  "visibility": "FRIENDS",
  "like_count": 0,
  "comment_count": 0,
  "share_count": 0,
  "created_at": "2026-04-26T10:30:00Z"
}
```

**Request Parameters:**

| Parameter       | Type       | Required | Description                                                |
|-----------------|------------|----------|------------------------------------------------------------|
| `content`       | String     | Yes      | Post text (max 5000 chars)                                 |
| `content_type`  | Enum       | Yes      | TEXT, IMAGE, VIDEO, LINK, POLL                             |
| `media_ids`     | List<String> | No     | Pre-uploaded media IDs (from media upload endpoint)        |
| `link_url`      | String     | No       | URL for LINK type posts (OGP preview auto-generated)       |
| `poll`          | Object     | No       | Poll object for POLL type posts                            |
| `mentions`      | List<String> | No     | User IDs mentioned in the post                             |
| `hashtags`      | List<String> | No     | Extracted hashtags                                         |
| `visibility`    | Enum       | No       | PUBLIC, FRIENDS, PRIVATE. Default: FRIENDS                 |

### 6.2 Get News Feed (Infinite Scroll)

```
GET /api/v1/feed?cursor=post_7249183746501900&limit=20
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "posts": [
    {
      "post_id": "post_7249183746501920",
      "author": {
        "user_id": "user_123456",
        "display_name": "John Doe",
        "avatar_url": "https://cdn.example.com/avatars/user_123456.jpg",
        "is_verified": false
      },
      "content": "Just finished reading 'Designing Data-Intensive Applications'. Highly recommend!",
      "content_type": "TEXT",
      "media_urls": [],
      "link_preview": null,
      "poll": null,
      "like_count": 142,
      "comment_count": 23,
      "share_count": 8,
      "is_liked_by_me": false,
      "is_shared_by_me": false,
      "top_comments": [
        {
          "comment_id": "cmt_001",
          "user": { "user_id": "user_999", "display_name": "Jane" },
          "text": "Great book! Chapter 5 on replication changed how I think about databases.",
          "created_at": "2026-04-26T09:30:00Z"
        }
      ],
      "ranking_reason": "Liked by 3 friends",
      "created_at": "2026-04-26T08:15:00Z"
    },
    {
      "post_id": "post_7249183746501918",
      "author": {
        "user_id": "user_654321",
        "display_name": "Sarah Kim",
        "avatar_url": "https://cdn.example.com/avatars/user_654321.jpg",
        "is_verified": true
      },
      "content": "Poll: What's your preferred IDE?",
      "content_type": "POLL",
      "media_urls": [],
      "link_preview": null,
      "poll": {
        "poll_id": "poll_001",
        "options": [
          { "id": 1, "text": "IntelliJ IDEA", "vote_count": 892, "vote_pct": 45.2 },
          { "id": 2, "text": "VS Code", "vote_count": 721, "vote_pct": 36.5 },
          { "id": 3, "text": "Vim/Neovim", "vote_count": 234, "vote_pct": 11.9 },
          { "id": 4, "text": "Eclipse", "vote_count": 127, "vote_pct": 6.4 }
        ],
        "total_votes": 1974,
        "my_vote": 1,
        "ends_at": "2026-04-27T10:00:00Z"
      },
      "like_count": 89,
      "comment_count": 45,
      "share_count": 12,
      "is_liked_by_me": true,
      "is_shared_by_me": false,
      "ranking_reason": "Popular in your network",
      "created_at": "2026-04-26T07:00:00Z"
    }
  ],
  "next_cursor": "post_7249183746501900",
  "has_more": true,
  "new_posts_count": 3,
  "gap_detected": false
}
```

**Query Parameters:**

| Parameter | Type   | Required | Description                                                    |
|-----------|--------|----------|----------------------------------------------------------------|
| `cursor`  | String | No       | Post ID of the last item on previous page. Null for first page.|
| `limit`   | Int    | No       | Number of posts per page. Default: 20, Max: 50.                |

**Response Fields (special):**

| Field              | Description                                                               |
|--------------------|---------------------------------------------------------------------------|
| `next_cursor`      | Post ID to pass as cursor for the next page                               |
| `has_more`         | Whether more posts exist beyond this page                                 |
| `new_posts_count`  | Number of new posts available above the current cursor (for banner)        |
| `gap_detected`     | True if posts were inserted between pages (signals client to show banner)  |
| `ranking_reason`   | Human-readable explanation of why this post is shown ("Liked by 3 friends")|

### 6.3 Like a Post

```
POST /api/v1/posts/{postId}/likes
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "post_id": "post_7249183746501920",
  "like_count": 143,
  "is_liked": true
}

---

DELETE /api/v1/posts/{postId}/likes
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "post_id": "post_7249183746501920",
  "like_count": 142,
  "is_liked": false
}
```

### 6.4 Add a Comment

```
POST /api/v1/posts/{postId}/comments
Authorization: Bearer <jwt_token>
Content-Type: application/json

Request:
{
  "text": "Couldn't agree more! That chapter on partitioning was eye-opening.",
  "parent_comment_id": null
}

Response: 201 Created
{
  "comment_id": "cmt_7249183746501950",
  "post_id": "post_7249183746501920",
  "user": {
    "user_id": "user_892374",
    "display_name": "Alice",
    "avatar_url": "https://cdn.example.com/avatars/user_892374.jpg"
  },
  "text": "Couldn't agree more! That chapter on partitioning was eye-opening.",
  "parent_comment_id": null,
  "like_count": 0,
  "created_at": "2026-04-26T11:00:00Z"
}
```

### 6.5 Follow / Unfollow

```
POST /api/v1/users/{userId}/follow
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "status": "FOLLOWING",
  "followed_user_id": "user_654321",
  "follower_count": 15201,
  "is_celebrity": false
}

---

DELETE /api/v1/users/{userId}/follow
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "status": "UNFOLLOWED",
  "unfollowed_user_id": "user_654321"
}
```

### 6.6 WebSocket: Real-time Feed Updates

```
Connection: ws://api.example.com/ws/feed
Authorization: Bearer <jwt_token>

--- Server-to-Client Messages ---

Message Type 1: New Post Notification
{
  "type": "NEW_POSTS",
  "count": 3,
  "preview": {
    "author": "John Doe",
    "content_snippet": "Just finished reading..."
  },
  "timestamp": "2026-04-26T11:05:00Z"
}

Message Type 2: Engagement Update (live count)
{
  "type": "ENGAGEMENT_UPDATE",
  "post_id": "post_7249183746501920",
  "like_count": 145,
  "comment_count": 25
}

Message Type 3: Post Deleted
{
  "type": "POST_DELETED",
  "post_id": "post_7249183746501920"
}

--- Client-to-Server Messages ---

Message Type: Acknowledge / Subscribe to specific posts
{
  "type": "SUBSCRIBE_POST_UPDATES",
  "post_ids": ["post_7249183746501920", "post_7249183746501918"]
}
```

---

## 7. Data Model

### 7.1 User Table (PostgreSQL)

```sql
CREATE TABLE users (
    user_id         BIGINT PRIMARY KEY,          -- Snowflake ID
    username        VARCHAR(30) UNIQUE NOT NULL,
    display_name    VARCHAR(100),
    bio             VARCHAR(500),
    avatar_url      VARCHAR(500),
    follower_count  INT DEFAULT 0,
    following_count INT DEFAULT 0,
    post_count      INT DEFAULT 0,
    is_celebrity    BOOLEAN DEFAULT FALSE,        -- >1M followers flag
    is_verified     BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP
);
-- Index: idx_users_username ON users(username)
-- Index: idx_users_celebrity ON users(is_celebrity) WHERE is_celebrity = TRUE
```

### 7.2 Post Table (Cassandra)

```sql
CREATE TABLE posts (
    post_id         BIGINT,                      -- Snowflake ID (time-sortable)
    user_id         BIGINT,
    content         TEXT,                         -- max 5000 chars
    content_type    TEXT,                         -- TEXT, IMAGE, VIDEO, LINK, POLL
    media_urls      LIST<TEXT>,                   -- CDN URLs for images/video
    link_preview    TEXT,                         -- JSON: {url, title, desc, thumbnail}
    poll_data       TEXT,                         -- JSON: {options, end_time}
    visibility      TEXT,                         -- PUBLIC, FRIENDS, PRIVATE
    mentions        SET<BIGINT>,                  -- mentioned user IDs
    hashtags        SET<TEXT>,
    like_count      INT,                          -- denormalized counter
    comment_count   INT,
    share_count     INT,
    is_deleted      BOOLEAN,                      -- soft delete
    created_at      TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
) WITH CLUSTERING ORDER BY (post_id DESC);
-- Partition: user_id → all posts by user co-located
-- Clustering: post_id DESC → latest first (user timeline)
```

### 7.3 Post by ID Lookup Table (Cassandra)

```sql
-- Secondary lookup: fetch post by post_id (for hydration)
CREATE TABLE posts_by_id (
    post_id         BIGINT PRIMARY KEY,
    user_id         BIGINT,
    content         TEXT,
    content_type    TEXT,
    media_urls      LIST<TEXT>,
    link_preview    TEXT,
    poll_data       TEXT,
    visibility      TEXT,
    mentions        SET<BIGINT>,
    hashtags        SET<TEXT>,
    like_count      INT,
    comment_count   INT,
    share_count     INT,
    is_deleted      BOOLEAN,
    created_at      TIMESTAMP
);
```

### 7.4 Follow Table (Cassandra)

```sql
-- Who does user X follow?
CREATE TABLE following (
    follower_id     BIGINT,
    followee_id     BIGINT,
    created_at      TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id)
);

-- Who follows user Y? (for fan-out)
CREATE TABLE followers (
    followee_id     BIGINT,
    follower_id     BIGINT,
    created_at      TIMESTAMP,
    PRIMARY KEY (followee_id, follower_id)
);
```

### 7.5 Like Table (Cassandra)

```sql
CREATE TABLE likes (
    post_id         BIGINT,
    user_id         BIGINT,
    created_at      TIMESTAMP,
    PRIMARY KEY (post_id, user_id)
);

-- Reverse: posts liked by a user
CREATE TABLE user_likes (
    user_id         BIGINT,
    post_id         BIGINT,
    created_at      TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
) WITH CLUSTERING ORDER BY (post_id DESC);
```

### 7.6 Comment Table (Cassandra)

```sql
CREATE TABLE comments (
    post_id             BIGINT,
    comment_id          BIGINT,                  -- Snowflake ID
    user_id             BIGINT,
    text                TEXT,                    -- max 2000 chars
    parent_comment_id   BIGINT,                  -- NULL if top-level
    like_count          INT DEFAULT 0,
    is_deleted          BOOLEAN DEFAULT FALSE,
    created_at          TIMESTAMP,
    PRIMARY KEY (post_id, comment_id)
) WITH CLUSTERING ORDER BY (comment_id ASC);
-- All comments for a post co-located
```

### 7.7 FeedItem / Timeline Cache Entry (Redis)

```
Key:    timeline:{user_id}
Type:   Sorted Set
Member: post_id (string)
Score:  ranking_score (double) -- NOT timestamp; ranked score from ranking pipeline

Example:
  ZADD timeline:user_892374 0.987 "post_724918374650192"
  ZADD timeline:user_892374 0.945 "post_724918374650190"
  ZADD timeline:user_892374 0.912 "post_724918374650188"

Read:   ZREVRANGEBYSCORE timeline:user_892374 +inf -inf LIMIT 0 20
        (top 20, highest score first)

Size:   Max 500 entries per user
TTL:    7 days on the key
```

### 7.8 TimelineCacheEntry Detail

```
For each entry in the sorted set, we store minimal data:
  Member = post_id (8 bytes as string)
  Score  = ranking_score (8 bytes double)

For hydration on read, we need the full post. Two options:
  Option A: Hydrate from Cassandra (posts_by_id table)
  Option B: Cache full post in Redis Hash

We use Option B for hot posts (< 24h old):
  Key:    post:{post_id}
  Type:   Hash
  Fields: user_id, content, content_type, media_urls, like_count,
          comment_count, share_count, created_at
  TTL:    24 hours

For older posts, fall back to Cassandra.
```

### Entity Relationship Diagram

```
┌──────────────┐        ┌───────────────┐        ┌──────────────┐
│     User     │───1:N──│     Post      │───1:N──│    Like      │
│              │        │               │        │(post_id,     │
│ user_id      │        │ post_id       │        │ user_id)     │
│ username     │        │ user_id       │        └──────────────┘
│ display_name │        │ content       │
│ is_celebrity │        │ content_type  │        ┌──────────────┐
│ follower_cnt │        │ media_urls    │───1:N──│   Comment    │
└──────┬───────┘        │ link_preview  │        │(post_id,     │
       │                │ poll_data     │        │ comment_id)  │
       │                │ visibility    │        │ parent_id    │
       │                │ like_count    │        └──────────────┘
       │                │ comment_count │
       │                │ share_count   │        ┌──────────────┐
       │                └───────────────┘   1:N──│    Share     │
       │                                         │(post_id,     │
       │  ┌───────────────┐                      │ user_id)     │
       └──│    Follow     │                      └──────────────┘
          │(follower_id,  │
          │ followee_id)  │    ┌──────────────────────┐
          └───────────────┘    │   Timeline Cache     │
                               │   (Redis Sorted Set) │
                               │                      │
                               │ timeline:{user_id}   │
                               │   member = post_id   │
                               │   score  = rank_score│
                               └──────────────────────┘
```

---

## 8. High-Level Architecture

```
                                ┌────────────────────┐
                                │      Clients       │
                                │  (Mobile/Web/API)  │
                                └─────────┬──────────┘
                                          │
                                    ┌─────▼─────┐
                                    │    CDN    │ ← media (images, video, thumbnails)
                                    └─────┬─────┘
                                          │
                                ┌─────────▼──────────┐
                                │    API Gateway     │
                                │ (Auth, Rate Limit, │
                                │  SSL Termination)  │
                                └─────────┬──────────┘
                                          │
                                ┌─────────▼──────────┐
                                │   Load Balancer    │
                                └─────────┬──────────┘
                                          │
        ┌──────────┬──────────┬───────────┼───────────┬──────────────┐
        │          │          │           │           │              │
  ┌─────▼────┐ ┌───▼───┐ ┌───▼────┐ ┌────▼───┐ ┌────▼─────┐ ┌─────▼──────┐
  │   Post   │ │ Feed  │ │Social  │ │Ranking │ │Real-time │ │Notification│
  │ Service  │ │Service│ │ Graph  │ │Service │ │  Push    │ │  Service   │
  │          │ │       │ │Service │ │        │ │ Service  │ │            │
  └────┬─────┘ └───┬───┘ └───┬────┘ └───┬────┘ └────┬─────┘ └─────┬──────┘
       │           │         │          │            │             │
       │     ┌─────┴─────┐   │          │     ┌──────┴──────┐      │
       │     │   Redis   │   │          │     │  WebSocket  │      │
       │     │  Cluster  │◄──┘          │     │   Gateway   │      │
       │     │(Timeline  │              │     │(connections)│      │
       │     │  Cache)   │◄─────────────┘     └─────────────┘      │
       │     └───────────┘                                         │
       │                                                           │
  ┌────▼─────┐                                              ┌──────▼──────┐
  │  Kafka   │                                              │    Push     │
  │(Post     │                                              │  Provider   │
  │ Events)  │                                              │(APNs, FCM) │
  └────┬─────┘                                              └─────────────┘
       │
  ┌────▼──────────┐
  │   Fan-out     │
  │   Service     │
  │  (Workers)    │
  └────┬──────────┘
       │
       ▼
  ┌───────────────┐     ┌───────────────┐     ┌───────────────┐
  │    Redis      │     │   Cassandra   │     │  PostgreSQL   │
  │   Cluster     │     │   Cluster     │     │  (Users,      │
  │ (Timeline     │     │ (Posts,       │     │   Social      │
  │  Cache)       │     │  Comments,    │     │   Graph)      │
  │               │     │  Likes)       │     │               │
  └───────────────┘     └───────────────┘     └───────────────┘
                              │
                        ┌─────▼─────┐
                        │  S3/CDN   │
                        │  (Media)  │
                        └───────────┘
```

### Data Flow Summary

```
WRITE PATH (Publishing a Post):
==================================

(1) Client ──POST /posts──▶ API Gateway
(2) API Gateway ──auth, rate limit──▶ Post Service
(3) Post Service ──validate, assign Snowflake ID──▶ Cassandra (posts + posts_by_id)
(4) Post Service ──publish event──▶ Kafka (post-events topic)
(5) Post Service ──201 Created──▶ Client (sync path done, ~60ms)
(6) Kafka ──consume──▶ Fan-out Service
(7) Fan-out Service ──check is_celebrity?──▶ Social Graph Service
(8a) IF normal user: Fan-out Service ──get follower IDs──▶ Social Graph Service
(9a) Fan-out Service ──ZADD to each follower's timeline──▶ Redis (timeline cache)
(8b) IF celebrity: Fan-out Service ──SKIP fan-out──▶ (post stays in user timeline only)
(10) Kafka ──consume──▶ Real-time Push Service
(11) Real-time Push Service ──WebSocket push──▶ Connected followers


READ PATH (Loading the Feed):
================================

(1) Client ──GET /feed?cursor=X&limit=20──▶ API Gateway
(2) API Gateway ──auth, rate limit──▶ Feed Service
(3) Feed Service ──ZREVRANGEBYSCORE──▶ Redis (timeline cache)
    Returns: 500 candidate post IDs from fan-out-on-write
(4) Feed Service ──get celebrity follows──▶ Social Graph Cache
    Returns: [celeb_1, celeb_2, ..., celeb_12]
(5) Feed Service ──parallel: get latest posts from each celebrity──▶ Cassandra
    Returns: 60 additional candidate post IDs (fan-out-on-read)
(6) Feed Service ──560 candidates──▶ Ranking Service
(7) Ranking Service ──score, rank, return top 50──▶ Feed Service
(8) Feed Service ──apply diversity, select top 20──▶ (in-memory)
(9) Feed Service ──batch hydrate post IDs──▶ Redis (post cache) or Cassandra
(10) Feed Service ──200 OK with 20 hydrated posts──▶ Client
```

---

## 9. Component Deep Dive

### 9.1 Post Service

**Responsibility:** Create, store, update, and delete posts. Handle media references. Publish post events to Kafka.

```
POST /posts request arrives
          │
    (1)   ▼
  ┌───────────────┐
  │   Validate    │ ← content length, content_type, media_ids exist,
  │   Request     │   visibility permissions, rate limit (300 posts/day)
  └───────┬───────┘
          │
    (2)   ▼
  ┌───────────────┐
  │  Generate     │ ← Snowflake ID (time-sortable, globally unique)
  │  Post ID      │   Contains: timestamp + machine_id + sequence
  └───────┬───────┘
          │
    (3)   ▼
  ┌───────────────┐
  │  Resolve      │ ← If content_type = LINK: fetch OGP metadata (title,
  │  Metadata     │   description, thumbnail) from URL. Cache result.
  │               │   If content_type = VIDEO: trigger async transcoding job
  └───────┬───────┘
          │
    (4)   ▼
  ┌───────────────┐
  │  Write to     │ ← Write to BOTH tables in Cassandra:
  │  Cassandra    │   (a) posts (partitioned by user_id, for user timeline)
  │  (dual write) │   (b) posts_by_id (partitioned by post_id, for hydration)
  └───────┬───────┘   Consistency: QUORUM write for durability
          │
    (5)   ▼
  ┌───────────────┐
  │  Cache in     │ ← HSET post:{post_id} with all fields. TTL: 24h.
  │  Redis        │   Enables fast hydration for new posts.
  └───────┬───────┘
          │
    (6)   ▼
  ┌───────────────┐
  │  Publish to   │ ← Event: {post_id, user_id, content_type, is_celebrity,
  │  Kafka        │   follower_count, created_at}
  └───────┬───────┘   Topic: post-events, Key: user_id (ordering guarantee)
          │
    (7)   ▼
  ┌───────────────┐
  │  Return 201   │ ← Sync path complete. Fan-out is async.
  │  to Client    │
  └───────────────┘
```

**Media Handling:**

```
For posts with media, the upload is a TWO-STEP process:

Step 1: Pre-upload media (before creating the post)
  Client ──POST /media/upload──▶ Media Service ──▶ S3
  Response: { "media_id": "media_abc123", "cdn_url": "https://cdn.../abc123.jpg" }

Step 2: Create post referencing media_ids
  Client ──POST /posts { media_ids: ["media_abc123"] }──▶ Post Service
  Post Service verifies media_ids exist, associates with post.

Why two steps?
  - Media upload can take seconds (large video). Don't block post creation.
  - Media can be reused across posts.
  - Upload failures don't affect post creation flow.
  - Video transcoding (480p, 720p, 1080p) happens async after upload.
```

### 9.2 Fan-out Service (THE Core Component)

**Responsibility:** Consume post events from Kafka and distribute post IDs to follower timelines in Redis. Implement hybrid fan-out strategy.

```
┌────────────────────────────────────────────────────────────────────┐
│                      FAN-OUT SERVICE                                │
│                                                                     │
│  Kafka Consumer Group: "fanout-workers"                             │
│  Topic: post-events (partitioned by user_id hash)                  │
│  Parallelism: 1 consumer per partition (32 partitions = 32 workers)│
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    Fan-out Worker Logic                       │   │
│  │                                                               │   │
│  │  (1) Consume event from Kafka                                │   │
│  │  (2) Check: is user a celebrity? (follower_count > 1M)       │   │
│  │      │                                                       │   │
│  │      ├── YES → SKIP fan-out. Log metric. Return.            │   │
│  │      │         Post lives in user's own timeline only.       │   │
│  │      │         Pulled at read time by Feed Service.          │   │
│  │      │                                                       │   │
│  │      └── NO → PROCEED with fan-out-on-write:                │   │
│  │           (3) Get ALL follower IDs from Social Graph         │   │
│  │           (4) Partition followers into batches of 1000       │   │
│  │           (5) For each batch:                                │   │
│  │               - Create Redis pipeline                        │   │
│  │               - ZADD timeline:{followerId} {score} {postId} │   │
│  │               - ZREMRANGEBYRANK timeline:{followerId} 0 -501│   │
│  │                 (trim to 500 entries max)                     │   │
│  │               - Execute pipeline                             │   │
│  │           (6) Record metrics: fan-out duration, batch count  │   │
│  │           (7) Commit Kafka offset                            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Error Handling:                                                    │
│    - Redis failure: retry with exponential backoff (1s, 2s, 4s)    │
│    - Partial failure: track which followers succeeded, retry rest   │
│    - Poison message: after 3 retries, send to dead-letter topic    │
│    - Worker crash: Kafka rebalances partitions to surviving workers │
│                                                                     │
│  Scaling:                                                           │
│    - Auto-scale based on Kafka consumer lag                         │
│    - Lag > 100K messages → add 4 workers                           │
│    - Lag < 10K messages → remove 2 workers (cooldown: 10 min)      │
└────────────────────────────────────────────────────────────────────┘
```

### 9.3 Feed Service (Read Path)

**Responsibility:** Read a user's timeline, merge pre-computed and celebrity posts, invoke ranking, hydrate, and return paginated response.

```
GET /feed?cursor=X&limit=20
          │
    (1)   ▼
  ┌───────────────────┐
  │  Read pre-computed │ ← ZREVRANGEBYSCORE timeline:{userId} +inf -inf LIMIT 0 500
  │  timeline (Redis)  │   Returns: 500 post IDs with scores (from fan-out-on-write)
  └───────┬───────────┘   Latency: ~1-2ms
          │
    (2)   ▼
  ┌───────────────────┐
  │  Get celebrity     │ ← Lookup: celebrity_follows:{userId} (Redis Set)
  │  follow list       │   Returns: [celeb_1, celeb_2, ..., celeb_12]
  └───────┬───────────┘   Average user follows ~5-20 celebrities
          │
    (3)   ▼
  ┌───────────────────┐
  │  Pull celebrity    │ ← For EACH celebrity (parallel, async):
  │  posts (parallel)  │     ZREVRANGE user_timeline:{celeb_id} 0 4
  │                    │     (latest 5 posts from each celebrity)
  │                    │   Total: 12 celebrities * 5 = 60 candidate posts
  └───────┬───────────┘   Latency: ~5-15ms (parallel, gated by slowest)
          │
    (4)   ▼
  ┌───────────────────┐
  │  Merge candidates  │ ← Combine: 500 (cached) + 60 (celebrity) = 560 candidates
  │  + de-duplicate    │   De-dup by post_id (shared posts, reposts)
  └───────┬───────────┘   Filter: remove posts from unfollowed users (lazy filter)
          │
    (5)   ▼
  ┌───────────────────┐
  │  Send to Ranking   │ ← 560 candidates → Ranking Service
  │  Service           │   Returns: top 50 scored + ranked post IDs
  └───────┬───────────┘   Latency: ~10-20ms
          │
    (6)   ▼
  ┌───────────────────┐
  │  Diversity         │ ← No more than 2 consecutive posts from same author
  │  injection         │   Inject at least 1 different content type per 5 posts
  └───────┬───────────┘   Prevents feed from being "all John's vacation photos"
          │
    (7)   ▼
  ┌───────────────────┐
  │  Apply cursor &    │ ← If cursor provided: skip posts with score > cursor_score
  │  pagination        │   Select next 20 posts after cursor
  └───────┬───────────┘   Set next_cursor = score of 20th post
          │
    (8)   ▼
  ┌───────────────────┐
  │  Hydrate posts     │ ← Batch fetch full post data for 20 post IDs:
  │  (parallel batch)  │   (a) Try Redis post cache: HMGET post:{id} ...
  │                    │   (b) Cache miss: fetch from Cassandra posts_by_id
  │                    │   Also fetch: author info, "is_liked_by_me", top 2 comments
  └───────┬───────────┘   Latency: ~15-30ms (parallel, batch)
          │
    (9)   ▼
  ┌───────────────────┐
  │  Gap detection     │ ← Check: were new posts inserted since last request?
  │                    │   If yes: set gap_detected=true, new_posts_count=N
  └───────┬───────────┘   Client shows "N new posts available" banner
          │
   (10)   ▼
  ┌───────────────────┐
  │  Return response   │ ← 200 OK with 20 hydrated posts, next_cursor, has_more,
  │                    │   new_posts_count, gap_detected
  └───────────────────┘
```

### 9.4 Timeline Cache (Redis)

**Responsibility:** Store per-user sorted list of post IDs ranked by score. The primary data structure for fast feed reads.

```
┌─────────────────────────────────────────────────────────────────┐
│                     TIMELINE CACHE DESIGN                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Data Structure: Redis Sorted Set (ZSET)                         │
│                                                                  │
│  Key:    timeline:{user_id}                                      │
│  Member: post_id (string, 19 chars = ~20 bytes)                 │
│  Score:  ranking_score (double, 8 bytes)                         │
│                                                                  │
│  Max entries per user: 500                                       │
│  Key TTL: 7 days (re-populated by fan-out on next post)          │
│                                                                  │
│  Operations:                                                     │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  WRITE (fan-out):                                        │    │
│  │    ZADD timeline:u_123 0.987 "post_724918374650192"     │    │
│  │    ZREMRANGEBYRANK timeline:u_123 0 -501  (keep top 500)│    │
│  │                                                          │    │
│  │  READ (feed):                                            │    │
│  │    ZREVRANGEBYSCORE timeline:u_123 +inf -inf LIMIT 0 20 │    │
│  │                                                          │    │
│  │  CURSOR READ (page 2+):                                  │    │
│  │    ZREVRANGEBYSCORE timeline:u_123 (0.945 -inf LIMIT 0 20│   │
│  │    (exclusive: scores less than cursor_score)             │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  Memory per user:                                                │
│    500 entries * (~20 bytes member + 8 bytes score + overhead)   │
│    ≈ 500 * 80 bytes ≈ 40 KB per user                            │
│                                                                  │
│  Total memory (500M DAU):                                        │
│    500M * 40 KB = 20 TB                                          │
│    → Redis Cluster with 40 nodes (512 GB each)                  │
│    → With replication: 80 nodes total                            │
│                                                                  │
│  Eviction:                                                       │
│    - ZREMRANGEBYRANK after every ZADD (cap at 500)              │
│    - Key TTL = 7 days. Inactive users' timelines expire.        │
│    - LRU eviction as last resort (volatile-lru policy)          │
│                                                                  │
│  Score Recalculation:                                            │
│    - Initial score: timestamp-based at fan-out time              │
│    - Periodic re-scoring: background job re-ranks top 100       │
│      posts per user every 30 minutes using latest signals        │
│    - This is how Facebook makes your feed "feel fresh" even      │
│      if you haven't opened the app in hours                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 9.5 Ranking Service

**Responsibility:** Score and rank candidate posts for a specific user. The brain of the feed.

```
┌─────────────────────────────────────────────────────────────────┐
│                      RANKING SERVICE                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Input:  560 candidate post IDs + viewer user_id                 │
│  Output: top 50 post IDs with scores, ordered by rank            │
│                                                                  │
│  Pipeline:                                                       │
│                                                                  │
│  (1) FEATURE EXTRACTION (parallel batch)                         │
│      │                                                           │
│      ├── Post features:                                          │
│      │   - age (seconds since creation)                          │
│      │   - content_type (TEXT=1, IMAGE=2, VIDEO=3, LINK=4, POLL=5)│
│      │   - like_count, comment_count, share_count                │
│      │   - media_count                                           │
│      │   - text_length                                           │
│      │   - has_link                                              │
│      │                                                           │
│      ├── Author features:                                        │
│      │   - follower_count                                        │
│      │   - is_verified                                           │
│      │   - avg_engagement_rate (likes/impressions over 30 days)  │
│      │   - post_frequency (posts per day)                        │
│      │                                                           │
│      ├── Viewer-Author affinity features:                        │
│      │   - likes_given (how many times viewer liked author)       │
│      │   - comments_given                                        │
│      │   - profile_visits                                        │
│      │   - time_since_last_interaction                           │
│      │   - mutual_friends_count                                  │
│      │                                                           │
│      └── Contextual features:                                    │
│          - time_of_day (morning/afternoon/evening/night)          │
│          - day_of_week                                            │
│          - viewer's recent content type preferences               │
│                                                                  │
│  (2) SCORING                                                     │
│      For each candidate post:                                    │
│        score = affinity * recency_decay * engagement_boost        │
│                * content_type_weight * negative_penalty            │
│      (Details in Section 11: Feed Ranking)                       │
│                                                                  │
│  (3) SORT by score descending                                    │
│                                                                  │
│  (4) TRUNCATE to top 50                                          │
│                                                                  │
│  (5) RETURN ranked list with scores                              │
│                                                                  │
│  Latency budget: 10-20ms for 560 candidates                     │
│  Implementation: in-memory computation, pre-cached features      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 9.6 Social Graph Service

**Responsibility:** Manage follow/unfollow relationships, maintain follower/following lists, detect celebrities.

```
┌─────────────────────────────────────────────────────────────────┐
│                    SOCIAL GRAPH SERVICE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Storage:                                                        │
│    PostgreSQL (source of truth) + Redis (hot cache)              │
│                                                                  │
│  Core Operations:                                                │
│                                                                  │
│  (1) FOLLOW user_B:                                              │
│      (a) Write to PostgreSQL: INSERT INTO following, followers   │
│      (b) Increment: user_B.follower_count, user_A.following_count│
│      (c) Update Redis: SADD following:{user_A} user_B           │
│      (d) Update Redis: SADD followers:{user_B} user_A           │
│      (e) If user_B.follower_count > 1M → mark is_celebrity=true │
│      (f) Trigger timeline backfill: pull user_B's recent posts  │
│          into user_A's timeline cache (async)                    │
│                                                                  │
│  (2) UNFOLLOW user_B:                                            │
│      (a) Delete from PostgreSQL: following, followers            │
│      (b) Decrement: user_B.follower_count, user_A.following_count│
│      (c) Update Redis: SREM following:{user_A} user_B           │
│      (d) Update Redis: SREM followers:{user_B} user_A           │
│      (e) If user_B was celebrity: remove from                    │
│          celebrity_follows:{user_A}                              │
│      (f) user_B's posts in user_A's timeline: lazy filter       │
│          at read time (don't scan cache to remove)               │
│                                                                  │
│  (3) GET FOLLOWERS (for fan-out):                                │
│      (a) Check Redis: SMEMBERS followers:{user_id}              │
│      (b) If cache miss: query PostgreSQL, populate Redis         │
│      (c) For users with >100K followers: paginate in batches    │
│                                                                  │
│  (4) CELEBRITY DETECTION:                                        │
│      Static: follower_count > 1,000,000                          │
│      Dynamic: if fan-out for this user took >30 seconds,         │
│               reclassify as celebrity                             │
│      Batch: nightly job scans for users crossing threshold       │
│                                                                  │
│  Celebrity Follow Segregation:                                   │
│    Per-user, maintain separate sets:                              │
│      celebrity_follows:{user_id} = {celeb_1, celeb_2, ...}     │
│      normal_follows:{user_id}   = {friend_1, friend_2, ...}    │
│    This enables the hybrid fan-out decision at write time        │
│    and the celebrity pull at read time.                           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 9.7 Notification Service

**Responsibility:** Send push notifications for engagement events (likes, comments, mentions, shares).

```
Notification Triggers:
(1) Post liked      → "John Doe liked your post"
(2) Post commented  → "Jane Kim commented on your post"
(3) Post shared     → "Bob Smith shared your post"
(4) Mention         → "Alice tagged you in a post"
(5) Follow          → "New follower: Charlie"
(6) Comment reply   → "Jane replied to your comment"

Architecture:

  ┌──────────┐    ┌──────────┐    ┌────────────────┐    ┌──────────────┐
  │  Kafka   │───▶│Notif.    │───▶│  Notification  │───▶│  APNs / FCM  │
  │(engage-  │    │Consumer  │    │  Aggregator    │    │  (Push)      │
  │ ment     │    │          │    │                │    │              │
  │ events)  │    │ Dedup    │    │ "5 people      │    │ OR           │
  │          │    │ + filter │    │  liked your    │    │              │
  └──────────┘    │ (mute,   │    │  post"         │    │  In-app      │
                  │  block)  │    │ (batch within  │    │  notification│
                  └──────────┘    │  30 seconds)   │    │  badge       │
                                  └────────────────┘    └──────────────┘

Aggregation Strategy:
  - If 1 like in 30 seconds: "John liked your post"
  - If 5 likes in 30 seconds: "John, Jane, and 3 others liked your post"
  - If 100 likes in 30 seconds: "Your post is getting popular! 100 likes"

  This avoids notification storms on viral posts.
```

### 9.8 Real-time Push Service

**Responsibility:** Maintain WebSocket connections with active users and push real-time feed updates.

```
┌─────────────────────────────────────────────────────────────────┐
│                   REAL-TIME PUSH SERVICE                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Architecture:                                                   │
│                                                                  │
│  ┌──────────┐     ┌──────────────┐     ┌──────────────────┐    │
│  │  Kafka   │────▶│  Push        │────▶│  WebSocket       │    │
│  │(post-    │     │  Router      │     │  Gateway Nodes   │    │
│  │ events,  │     │              │     │  (N nodes)       │    │
│  │ engage-  │     │ Determines   │     │                  │    │
│  │ ment-    │     │ which users  │     │  Each node holds │    │
│  │ events)  │     │ should be    │     │  ~100K active    │    │
│  │          │     │ notified,    │     │  WebSocket       │    │
│  └──────────┘     │ which WS     │     │  connections     │    │
│                   │ node they're │     │                  │    │
│                   │ connected to │     │  User connection │    │
│                   └──────┬───────┘     │  registry:       │    │
│                          │             │  user_123→node_5 │    │
│                          │             └────────┬─────────┘    │
│                          │                      │              │
│                          │  ┌───────────────────▼──────────┐  │
│                          └─▶│  Redis Pub/Sub               │  │
│                             │  Channel: ws_node_{node_id}  │  │
│                             │                              │  │
│                             │  Push Router publishes to    │  │
│                             │  the correct node's channel  │  │
│                             └──────────────────────────────┘  │
│                                                                │
│  Connection Registry (Redis Hash):                              │
│    Key: ws_connections                                           │
│    Field: user_id                                                │
│    Value: ws_node_id                                             │
│                                                                  │
│    When user connects: HSET ws_connections user_123 node_5      │
│    When user disconnects: HDEL ws_connections user_123           │
│    When routing push: HGET ws_connections user_123 → node_5     │
│                                                                  │
│  Fallbacks:                                                      │
│    - If WebSocket not connected: skip real-time push             │
│      User will see new posts on next feed refresh (pull)         │
│    - Long polling fallback: GET /feed/updates?since=timestamp   │
│    - SSE fallback: GET /feed/stream (Server-Sent Events)         │
│                                                                  │
│  Scale:                                                          │
│    - 500M DAU, ~20% connected at any time = 100M connections    │
│    - 100K connections per node = 1,000 WebSocket Gateway nodes  │
│    - Each node = ~4 GB RAM for connection state                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. Fan-out Deep Dive

> **THIS IS THE STAR OF THE INTERVIEW. Spend 10-15 minutes here. Project 05 introduced the concepts; this section goes deeper into architecture, back-pressure, and production realities.**

### 10.1 Fan-out on Write (Push Model)

```
On post → write post ID to ALL follower timelines immediately

User Alice (300 followers) posts:
┌──────────┐   ┌──────────┐   ┌───────────────┐   ┌─────────────────────┐
│  Alice   │──▶│  Post    │──▶│   Kafka       │──▶│  Fan-out Worker     │
│  posts   │   │  Service │   │   (event)     │   │                     │
└──────────┘   └──────────┘   └───────────────┘   │ (1) Get 300 follower│
                                                   │     IDs from Social │
                                                   │     Graph           │
                                                   │ (2) Redis pipeline: │
                                                   │     ZADD timeline:  │
                                                   │     {f1} score pid  │
                                                   │     ZADD timeline:  │
                                                   │     {f2} score pid  │
                                                   │     ... (300 times) │
                                                   │ (3) Pipeline.sync() │
                                                   └─────────────────────┘

Cost: 300 Redis ZADD operations per post
Time: ~1-2ms (pipelined)
```

**Pros:**
- Feed reads are instant: ZREVRANGEBYSCORE from Redis is O(log N + M)
- Pre-computed = deterministic latency on the read path
- Simple read path logic (no merging at read time for normal users)
- Great for users with < 100K followers (99.99% of all users)

**Cons:**
- Write amplification: N followers = N Redis writes per post
- Celebrities with 50M followers: 50M writes per post
- Wastes storage for inactive users (they never read their timeline)
- High fan-out latency for large follower counts

### 10.2 Fan-out on Read (Pull Model)

```
On feed request → query all followed users' posts, merge, rank

User Bob opens feed (follows 300 users):
┌──────────┐   ┌──────────┐   ┌───────────────────────────────────┐
│  Bob     │──▶│  Feed    │──▶│  For EACH of Bob's 300 followees: │
│  opens   │   │  Service │   │    Query their latest 5 posts     │
│  feed    │   │          │   │    from Cassandra / user timeline  │
└──────────┘   └──────────┘   │                                   │
                              │  Merge 1500 candidate posts       │
                              │  Rank by score                    │
                              │  Return top 20                    │
                              └───────────────────────────────────┘

Cost: 300 queries per feed request
Time: ~100-500ms (parallel, but still slow)
```

**Pros:**
- No write amplification (post creation is O(1))
- No wasted storage for inactive users
- Celebrity posts are cheap to write

**Cons:**
- SLOW reads: 300 parallel queries per feed request
- Merging/ranking at read time adds latency
- Cannot reliably meet < 200ms SLA with 300 queries
- Read path latency is unpredictable (depends on how many users you follow)

### 10.3 Hybrid Fan-out (THE Answer)

```
┌──────────────────────────────────────────────────────────────────┐
│                   HYBRID FAN-OUT STRATEGY                         │
│                                                                   │
│  RULE:                                                            │
│    Normal users (< 1M followers)   → Fan-out on WRITE (push)    │
│    Celebrity users (>= 1M followers) → Fan-out on READ (pull)    │
│                                                                   │
│  WRITE PATH:                                                      │
│    Normal user posts → push post ID to ALL follower timelines    │
│    Celebrity posts   → post stays in celebrity's user timeline   │
│                        only. NO fan-out.                          │
│                                                                   │
│  READ PATH:                                                       │
│    (1) Read pre-computed timeline from Redis                      │
│        (contains posts from normal friends only)                  │
│    (2) Identify celebrity follows (typically 5-20)                │
│    (3) Pull latest posts from each celebrity's user timeline     │
│    (4) MERGE pre-computed + celebrity posts                       │
│    (5) RANK combined candidates                                   │
│    (6) Return top 20                                              │
│                                                                   │
│  WHY THIS WORKS:                                                  │
│    - 99.99% of users are normal → push is cheap (avg 300 writes) │
│    - 0.01% are celebrities → pull adds ~5-15ms (10-20 queries)   │
│    - Read path: 1-2ms (cache) + 5-15ms (pull) = ~20ms total     │
│      Well within 200ms budget.                                    │
│    - Write path: eliminated 50M+ write amplification per         │
│      celebrity post.                                              │
└──────────────────────────────────────────────────────────────────┘
```

### 10.4 The Celebrity Problem: Why Pure Push Fails

```
Scenario: Celebrity with 50M followers posts.

Pure Fan-out-on-Write:
  (1) Fan-out Service receives the event
  (2) Queries Social Graph: 50,000,000 follower IDs
  (3) Must execute: ZADD timeline:{follower_id} score post_id
      ... 50,000,000 times
  (4) Redis pipeline throughput: ~100K ZADD/sec per pipeline
  (5) Time: 50,000,000 / 100,000 = 500 seconds = 8.3 MINUTES
  (6) And this celebrity posts 10 times/day:
      8.3 min * 10 = 83 minutes of fan-out per day FROM ONE USER
  (7) During those 83 minutes, fan-out workers are saturated
  (8) Other users' fan-out is DELAYED (queue backs up)
  (9) Result: everyone's feed becomes stale

The math that kills pure push:
  ┌─────────────────────────────────────────────────┐
  │  Celebrity posts per day:  100K users * 5 posts │
  │                          = 500K celebrity posts  │
  │                                                  │
  │  Avg celebrity followers: 5M                     │
  │                                                  │
  │  Fan-out writes per day (celebrities only):      │
  │    500K * 5M = 2.5 TRILLION writes/day           │
  │                                                  │
  │  At 100K writes/sec per worker:                  │
  │    2.5T / 100K = 25M worker-seconds              │
  │    = 289 worker-DAYS of continuous processing    │
  │                                                  │
  │  You would need ~300 dedicated fan-out workers   │
  │  running 24/7 JUST for celebrity posts.           │
  │                                                  │
  │  With hybrid: 0 fan-out writes for celebrities.  │
  │  Cost: 0. Savings: 2.5 trillion writes/day.      │
  └─────────────────────────────────────────────────┘
```

### 10.5 Fan-out Service Architecture

```
                              Kafka Cluster
                    ┌──────────────────────────────┐
                    │   Topic: post-events          │
                    │   Partitions: 64              │
                    │   Key: hash(user_id)          │
                    │   Replication: 3              │
                    │   Retention: 72 hours         │
                    └──────────────┬───────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    │              │              │
              ┌─────▼────┐  ┌─────▼────┐  ┌─────▼────┐
              │ Fan-out  │  │ Fan-out  │  │ Fan-out  │
              │ Worker 1 │  │ Worker 2 │  │ Worker N │
              │          │  │          │  │          │
              │Partitions│  │Partitions│  │Partitions│
              │  0-7     │  │  8-15    │  │  56-63   │
              └────┬─────┘  └────┬─────┘  └────┬─────┘
                   │             │             │
                   │     ┌───────┴───────┐     │
                   │     │               │     │
                   ▼     ▼               ▼     ▼
              ┌──────────────────────────────────────┐
              │         Redis Cluster                 │
              │   (64 shards, hash-slot based)        │
              │                                       │
              │   timeline:user_1  → shard 12         │
              │   timeline:user_2  → shard 45         │
              │   timeline:user_3  → shard 7          │
              │   ...                                 │
              └──────────────────────────────────────┘

Worker Pseudocode (Java 21):

    void processPostEvent(PostEvent event) {
        long userId = event.userId();
        long postId = event.postId();
        double score = computeInitialScore(event);

        // (1) Celebrity check
        if (socialGraph.isCelebrity(userId)) {
            metrics.increment("fanout.skipped.celebrity");
            return;  // No fan-out. Post pulled at read time.
        }

        // (2) Get follower IDs (may be cached in Redis Set)
        List<Long> followers = socialGraph.getFollowerIds(userId);
        metrics.record("fanout.follower_count", followers.size());

        // (3) Batch fan-out with Redis pipeline
        int batchSize = 1000;
        for (int i = 0; i < followers.size(); i += batchSize) {
            List<Long> batch = followers.subList(
                i, Math.min(i + batchSize, followers.size())
            );

            try (var pipeline = redis.pipelined()) {
                for (long followerId : batch) {
                    String key = "timeline:" + followerId;
                    pipeline.zadd(key, score, String.valueOf(postId));
                    pipeline.zremrangeByRank(key, 0, -501); // keep top 500
                }
                pipeline.sync();
            }
        }

        metrics.recordDuration("fanout.duration",
            System.currentTimeMillis() - event.timestamp());
    }

    double computeInitialScore(PostEvent event) {
        // Initial score = normalized timestamp (for chronological baseline)
        // Will be re-ranked by Ranking Service at read time
        return event.timestamp() / 1_000_000_000.0;
    }
```

### 10.6 Back-pressure: When a Celebrity Posts

```
Scenario: Celebrity with 50M followers posts during peak hour.
Even with hybrid (no fan-out for celebrity), the event still
flows through the system. But what about cascading effects?

Problem 1: Celebrity's POST triggers real-time push to 50M users
┌─────────────────────────────────────────────────────────────────┐
│  Celebrity posts → Kafka event → Real-time Push Service         │
│                                                                  │
│  Push Service needs to notify connected followers:               │
│    50M followers * 20% connected = 10M WebSocket pushes          │
│                                                                  │
│  Solution: BATCH + THROTTLE                                      │
│    (1) Push Service reads event                                  │
│    (2) Gets follower list in chunks of 10K                       │
│    (3) Checks connection registry: which are online?             │
│    (4) Sends "new posts available" to connected users            │
│    (5) Throttle: max 100K pushes/sec per Push Service node       │
│    (6) 10M pushes / 100K per node = 100 seconds across cluster  │
│    (7) Acceptable: users see "new post" banner within 2 min     │
│                                                                  │
│  KEY: The push message is just a NOTIFICATION ("new posts"),     │
│  not the actual post content. Content is fetched on next         │
│  feed refresh. This keeps the push message tiny (~100 bytes).    │
└─────────────────────────────────────────────────────────────────┘

Problem 2: 10M users refresh feed within 60 seconds after notification
┌─────────────────────────────────────────────────────────────────┐
│  Thundering Herd on Celebrity's User Timeline                    │
│                                                                  │
│  All 10M users' feed requests include Step 3 (pull celebrity     │
│  posts). They all query the same celebrity's user timeline        │
│  in Redis or Cassandra simultaneously.                           │
│                                                                  │
│  Solutions (layered):                                            │
│                                                                  │
│  Layer 1: LOCAL CACHE (Caffeine, on each Feed Service node)      │
│    Cache celebrity's latest posts locally with 10-second TTL     │
│    10M requests across 100 Feed Service nodes = 100K per node    │
│    With 10s TTL: each node fetches once every 10s = 10 fetches  │
│    Over 60s: 100 nodes * 6 fetches = 600 Redis reads            │
│    (instead of 10M)                                              │
│                                                                  │
│  Layer 2: SINGLEFLIGHT / REQUEST COALESCING                      │
│    If multiple requests arrive at the same node for the same     │
│    celebrity timeline simultaneously, only ONE makes the Redis   │
│    call. Others wait for that result and share it.               │
│                                                                  │
│  Layer 3: REDIS READ REPLICAS                                    │
│    Celebrity user timelines are hot keys. Redis Cluster           │
│    distributes reads across replicas automatically.              │
│                                                                  │
│  Layer 4: PREEMPTIVE CACHE WARMING                               │
│    When celebrity posts, proactively push their latest post       │
│    list to all Feed Service nodes' local caches. Don't wait      │
│    for the first request.                                        │
│                                                                  │
│  Combined: 10M requests → ~600 actual Redis reads. Solved.       │
└─────────────────────────────────────────────────────────────────┘

Problem 3: Fan-out queue depth for normal users spikes during peak
┌─────────────────────────────────────────────────────────────────┐
│  During a major event (election night), normal posting rate      │
│  spikes 10x: 17K posts/sec → 170K posts/sec                     │
│                                                                  │
│  Each post averages 300 followers = 51M fan-out writes/sec       │
│  (up from 5.1M/sec normally)                                     │
│                                                                  │
│  Auto-scaling response:                                          │
│    (1) Kafka consumer lag increases: 0 → 500K messages           │
│    (2) Monitoring detects lag > 100K threshold                   │
│    (3) Auto-scaler adds fan-out worker nodes                     │
│        Target: maintain lag < 100K messages                       │
│    (4) Workers scale from 64 → 256 (4x)                         │
│    (5) Lag stabilizes within 2-3 minutes                         │
│                                                                  │
│  During scaling window:                                          │
│    Feed may be 30-60 seconds stale (posts delayed in fan-out)   │
│    Acceptable: user refreshes and sees "2 new posts" banner      │
│                                                                  │
│  Scale-down:                                                     │
│    (1) Lag drops below 10K for 10 minutes                       │
│    (2) Remove excess workers gradually (4 at a time)             │
│    (3) Cooldown: no scale-down within 10 min of last scale-up   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 11. Feed Ranking

> **This is where Project 12 goes significantly deeper than Project 05. Ranking is what differentiates Facebook/LinkedIn from a simple reverse-chronological feed.**

### 11.1 Chronological (Simple, Twitter Classic)

```
score = post_timestamp

Feed is sorted by newest first. No personalization.

Pros:
  - Simple to implement and understand
  - Predictable: users know newest posts are at the top
  - No "filter bubble" concerns
  - No ML infrastructure needed

Cons:
  - Users miss important posts if they follow many people
  - High-volume posters dominate the feed
  - No engagement optimization (boring posts ranked same as great ones)
  - Low-quality content gets equal treatment

Used by: Twitter (before 2016), Mastodon, RSS readers
```

### 11.2 Algorithmic Ranking (Facebook / LinkedIn / Instagram)

```
score = affinity(viewer, author) * recency_decay(post_age)
        * engagement_boost(likes, comments, shares)
        * content_type_weight(content_type)
        * negative_signal_penalty(hide, report, unfollow_after)

Each factor explained:
```

**Factor 1: Affinity Score (How close is the viewer to the author?)**

```
affinity(viewer, author) = weighted sum of interaction signals:

  Signal                    | Weight | Example
  --------------------------|--------|----------------------------------------
  Likes given (V→A)         | 0.25   | Viewer liked 10 of author's posts
  Comments given (V→A)      | 0.35   | Viewer commented 5 times on author
  Shares given (V→A)        | 0.20   | Viewer shared 2 of author's posts
  Profile visits (V→A)      | 0.10   | Viewer visited author's profile 3 times
  Message exchanges         | 0.10   | Viewer and author exchanged 20 messages

  Each signal is:
    raw_signal = count of interactions in last 30 days
    time_weighted = sum of (1 / (1 + days_since_interaction * 0.1))
    normalized = time_weighted / max_time_weighted_across_all_friends

  Final affinity = SUM(weight_i * normalized_signal_i)
  Range: 0.1 (never interact) to 2.0 (best friend)

  Example:
    Alice and Bob interact heavily:
      likes: 15 in 30 days → normalized: 0.9  → 0.25 * 0.9 = 0.225
      comments: 8          → normalized: 0.8  → 0.35 * 0.8 = 0.280
      shares: 3            → normalized: 0.6  → 0.20 * 0.6 = 0.120
      profile: 5           → normalized: 0.7  → 0.10 * 0.7 = 0.070
      messages: 30         → normalized: 0.95 → 0.10 * 0.95 = 0.095
      Total affinity = 0.79 → scaled to range: 1.58

    Alice and Charlie barely interact:
      likes: 1 → normalized: 0.1 → 0.025
      comments: 0 → 0
      shares: 0 → 0
      profile: 0 → 0
      messages: 0 → 0
      Total affinity = 0.025 → scaled: 0.15
```

**Factor 2: Recency Decay (How old is the post?)**

```
recency_decay(age_hours) = 1 / (1 + age_hours * decay_rate)

  decay_rate = 0.05 (tunable; higher = faster decay)

  Post Age    | Decay Factor | Interpretation
  ------------|-------------|---------------------------
  0 hours     | 1.000       | Brand new, full weight
  1 hour      | 0.952       | Still very fresh
  3 hours     | 0.870       | Recent
  6 hours     | 0.769       | Moderately aged
  12 hours    | 0.625       | Half-day old
  24 hours    | 0.455       | Day old, significant decay
  48 hours    | 0.294       | Two days, heavy decay
  72 hours    | 0.217       | Three days, almost gone
  168 hours   | 0.106       | One week, barely visible

  Why not exponential decay?
    Exponential drops TOO fast. A 6-hour-old post from your best friend
    with 500 likes should still rank high. Linear-harmonic decay
    gives a more gradual decline that respects engagement.
```

**Factor 3: Engagement Boost (How popular is this post?)**

```
engagement_boost = 1 + log2(1 + weighted_engagement)

  weighted_engagement = like_count * 1.0
                      + comment_count * 3.0  (comments = stronger signal)
                      + share_count * 5.0    (shares = strongest signal)

  Engagement  | Boost Factor | Interpretation
  ------------|-------------|---------------------------
  0           | 1.000       | No engagement, no boost
  5 likes     | 1.000 + log2(6) = 3.58 | Slight boost
  50 likes    | 1.000 + log2(51) = 6.67 | Moderate boost
  500 likes   | 1.000 + log2(501) = 9.97 | Strong boost
  5000 likes  | 1.000 + log2(5001) = 13.29 | Very strong

  Why log2?
    Without logarithm, a post with 1M likes would dominate the entire feed.
    Logarithmic scaling ensures diminishing returns: going from 10 to 100
    likes matters more than going from 100K to 1M likes.
    This prevents viral posts from PERMANENTLY occupying the top of everyone's feed.
```

**Factor 4: Content Type Weight**

```
content_type_weight(type):

  Content Type | Weight | Rationale
  -------------|--------|------------------------------------------
  VIDEO        | 1.4    | Highest engagement, platform wants video
  POLL         | 1.3    | Interactive, drives comments
  IMAGE        | 1.2    | Visual content gets more attention
  LINK         | 1.0    | Neutral (takes user off-platform)
  TEXT         | 0.9    | Lowest engagement on average

  Why boost video?
    Platforms (Facebook, LinkedIn, TikTok) optimize for time-on-platform.
    Video keeps users engaged longer. This is a product/business decision.

  Personalization:
    The weights above are defaults. For each user, the system adjusts
    based on their actual behavior:
      - If user_123 clicks on links 3x more than average → link_weight = 1.3
      - If user_456 never watches videos → video_weight = 1.0
    This is stored in a per-user preference vector updated daily.
```

**Factor 5: Negative Signal Penalty**

```
negative_penalty = product of all applicable penalties:

  Signal                | Penalty  | Explanation
  ----------------------|----------|----------------------------------------
  Viewer hid this post  | 0.0      | Hard filter: never show again
  Viewer hid this author| 0.1      | Severely penalize all author's posts
  Post was reported     | 0.5      | Reduce visibility pending review
  Viewer unfollowed     | 0.0      | Remove completely (lazy filter)
  Snooze author 30 days | 0.0      | Temporarily hide
  "See less like this"  | 0.7      | Mild penalty on similar content

  Negative signals are POWERFUL: a single "hide" permanently removes
  the post, and penalizing an author's future posts is the strongest
  way to improve feed quality for that user.
```

### 11.3 Ranking Formula (Combined)

```
score(post, viewer) = affinity(viewer, post.author)
                    * recency_decay(post.age_hours)
                    * engagement_boost(post.likes, post.comments, post.shares)
                    * content_type_weight(post.content_type)
                    * negative_penalty(viewer, post)

Worked Example:
  Post P1: by Alice (viewer's best friend), 3 hours old, 50 likes, 10 comments,
           2 shares, IMAGE type, no negative signals

  affinity       = 1.58
  recency_decay  = 1 / (1 + 3 * 0.05) = 0.870
  engagement     = 50*1.0 + 10*3.0 + 2*5.0 = 90
  engagement_boost = 1 + log2(91) = 7.51
  content_weight = 1.2 (IMAGE)
  negative       = 1.0 (no penalty)

  score = 1.58 * 0.870 * 7.51 * 1.2 * 1.0 = 12.39

  Post P2: by Charlie (barely interact), 1 hour old, 500 likes, 50 comments,
           20 shares, VIDEO type, no negative signals

  affinity       = 0.15
  recency_decay  = 1 / (1 + 1 * 0.05) = 0.952
  engagement     = 500*1.0 + 50*3.0 + 20*5.0 = 750
  engagement_boost = 1 + log2(751) = 10.55
  content_weight = 1.4 (VIDEO)
  negative       = 1.0

  score = 0.15 * 0.952 * 10.55 * 1.4 * 1.0 = 2.11

  Result: P1 (12.39) ranks above P2 (2.11)
  Even though P2 has 10x more engagement and is newer,
  Alice's post ranks higher because of AFFINITY.
  This is why your best friend's mundane post shows up before
  a stranger's viral post. This is the "Facebook feed" experience.
```

### 11.4 Two-Pass Ranking Pipeline

```
┌────────────────────────────────────────────────────────────────┐
│              TWO-PASS RANKING PIPELINE                          │
│                                                                 │
│  PASS 1: CANDIDATE GENERATION (cheap, broad)                   │
│  ─────────────────────────────────────────                     │
│  Input: user_id                                                 │
│  Output: ~1000 candidate post IDs                               │
│                                                                 │
│  Sources:                                                       │
│    (1) Pre-computed timeline (Redis): 500 post IDs             │
│    (2) Celebrity post pull: ~60 post IDs                        │
│    (3) "Friends of friends" popular posts: ~200 post IDs       │
│        (posts liked/commented by 3+ of your friends)            │
│    (4) Content-type backfill: ~100 post IDs                    │
│        (if user prefers video but timeline has few, inject      │
│         popular videos from 2nd-degree connections)              │
│    (5) Recirculation: ~100 post IDs                            │
│        (highly-scored posts from yesterday that user hasn't     │
│         seen yet -- Facebook does this extensively)              │
│                                                                 │
│  Total: ~960 candidates (after dedup)                           │
│                                                                 │
│  PASS 2: SCORING + RANKING (expensive, precise)                │
│  ──────────────────────────────────────────                    │
│  Input: ~960 candidate post IDs + viewer user_id                │
│  Output: top 50 post IDs with scores                            │
│                                                                 │
│  Steps:                                                         │
│    (1) Batch fetch features for all 960 candidates              │
│        (from Redis feature store, ~5ms)                         │
│    (2) Compute score for each using the formula above           │
│        (in-memory, ~5ms for 960 candidates)                     │
│    (3) Sort by score descending                                 │
│    (4) Truncate to top 50                                       │
│    (5) DIVERSITY INJECTION:                                     │
│        - No more than 2 consecutive posts from same author      │
│        - At least 1 different content type per 5 posts          │
│        - At least 1 post with high engagement per 10 posts      │
│        - Demote 3rd+ post from any single author in top 20     │
│    (6) Return final ranked top 50 (client shows first 20,       │
│        next 30 pre-cached for page 2+)                          │
│                                                                 │
│  Total latency: ~10-20ms for both passes                       │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 11.5 Ranking Feature Store

```
To score 960 candidates in <20ms, features must be PRE-COMPUTED
and stored in a fast-access feature store (Redis).

Feature Store Schema (Redis Hashes):

  User features (updated daily):
    Key: user_features:{user_id}
    Fields: follower_count, avg_engagement_rate, post_frequency,
            content_type_preferences (JSON), is_verified

  Affinity features (updated daily):
    Key: affinity:{viewer_id}:{author_id}
    Fields: likes_given, comments_given, shares_given,
            profile_visits, messages, last_interaction_ts
    TTL: 30 days (if no interaction, affinity decays to 0)

  Post features (updated on engagement events):
    Key: post_features:{post_id}
    Fields: like_count, comment_count, share_count,
            content_type, created_at, author_id

  Update Pipeline:
    Engagement event → Kafka → Feature Update Consumer → Redis

  Why Redis (not a dedicated feature store)?
    At interview scale, Redis is sufficient. In production,
    Facebook uses a custom feature store (Feature Store Service).
    Mention: "In production, we'd use a dedicated feature store
    like Feast or a custom solution for lower latency and
    higher throughput."
```

---

## 12. Pagination and Infinite Scroll

### 12.1 Why NOT Offset-based Pagination

```
Problem with OFFSET + LIMIT:

  Page 1: SELECT * FROM posts ORDER BY score DESC LIMIT 20 OFFSET 0
  Page 2: SELECT * FROM posts ORDER BY score DESC LIMIT 20 OFFSET 20

  What happens when NEW posts arrive between page 1 and page 2?

  Timeline state when user loads page 1:
    Position: 1  2  3  4  5  6  7  8  9  10 ... 20 21 22 23 ...
    Post:     A  B  C  D  E  F  G  H  I  J  ... T  U  V  W  ...
              ▲─────── page 1 ───────────▲

  User reads page 1 (posts A through T).

  Meanwhile, 3 new posts (X, Y, Z) arrive with high scores:
    Position: 1  2  3  4  5  6  7  8  9  10 ... 20 21 22 23 24 25 26 ...
    Post:     X  Y  Z  A  B  C  D  E  F  G  ... R  S  T  U  V  W  ...

  User requests page 2: OFFSET 20, LIMIT 20
    Gets: R  S  T  U  V  W  ...
                ↑
    PROBLEM: Posts R, S, T were already on page 1!
    User sees DUPLICATE posts.

  This gets WORSE with high-velocity feeds.
  At 500M posts/day, new posts arrive constantly.
  Offset-based pagination is BROKEN for real-time feeds.
```

### 12.2 Cursor-based Pagination (The Solution)

```
Cursor = unique identifier of the LAST ITEM the user saw.
Next page = "give me items AFTER this cursor."

Implementation options:
  (a) Cursor = post_id of last item (if sorted by ID)
  (b) Cursor = ranking_score of last item (if sorted by score)
  (c) Cursor = composite: {score}_{post_id} (handles ties)

We use option (c) for ranked feeds:

Page 1: GET /feed?limit=20
  Returns: posts with scores [12.39, 11.87, ..., 5.42]
  next_cursor = "5.42_post_724918374650190"

Page 2: GET /feed?cursor=5.42_post_724918374650190&limit=20
  Backend: ZREVRANGEBYSCORE timeline:{userId} (5.42 -inf LIMIT 0 20
           If score ties: use post_id as tiebreaker

Page 3: GET /feed?cursor=3.18_post_724918374650180&limit=20
  ... and so on

Why this works:
  - Cursor is STABLE: it doesn't shift when new posts arrive
  - New posts get higher scores → they appear ABOVE the cursor
  - Old posts don't shift position relative to the cursor
  - No duplicates, no missed posts within a page

Edge case - score changes between pages:
  A post on page 3 gets 1000 likes, its score jumps from 3.0 to 8.0.
  It's now above the cursor → user won't see it on page 3.
  Solution: acceptable. User sees it on next refresh (top of feed).
  Over-optimization here adds complexity with minimal benefit.
```

### 12.3 Handling New Posts During Scroll (Gap Detection)

```
Scenario:
  User loads page 1 at time T1.
  User slowly scrolls and reads for 5 minutes.
  During those 5 minutes, 15 new posts arrive (friends are active).
  User requests page 2 at time T2 = T1 + 5 minutes.

Problem:
  Those 15 new posts have HIGH scores (recent + fresh engagement).
  They're above the user's cursor.
  User never sees them unless they scroll back to the top.

Solution: Gap Detection + "New Posts" Banner

  ┌──────────────────────────────────────────────────────────────┐
  │  Feed Service detects gap on page 2+ requests:               │
  │                                                               │
  │  (1) When serving page N (N > 1):                            │
  │      Count posts with score > page_1_highest_score            │
  │      If count > 0: gap_detected = true                        │
  │                    new_posts_count = count                     │
  │                                                               │
  │  (2) Client receives gap_detected = true, new_posts_count = 15│
  │                                                               │
  │  (3) Client renders a banner:                                 │
  │      ┌──────────────────────────────────────┐                │
  │      │  ↑  15 new posts available  (tap)    │                │
  │      └──────────────────────────────────────┘                │
  │                                                               │
  │  (4) User taps banner:                                        │
  │      Client fetches GET /feed?limit=20 (fresh, no cursor)     │
  │      Scrolls to top with new posts                            │
  │                                                               │
  │  (5) User ignores banner:                                     │
  │      Continue scrolling page 2, 3, 4... normally              │
  │      Banner persists until tapped or feed refreshed            │
  │                                                               │
  │  Alternative: Auto-inject (Twitter style)                     │
  │    New posts are automatically prepended to the feed           │
  │    Feed "jumps" -- user loses scroll position                  │
  │    WORSE UX for most users. Only good for real-time streams.   │
  │                                                               │
  │  RECOMMENDED: Banner approach (Facebook/LinkedIn style)        │
  │    Gives user CONTROL over when to see new content.            │
  └──────────────────────────────────────────────────────────────┘
```

### 12.4 Infinite Scroll: Client-Side Implementation

```
Client Behavior (Mobile App / SPA):

  ┌────────────────────────────────────────────────────────────┐
  │                                                             │
  │  State:                                                     │
  │    posts: []            // rendered posts                   │
  │    cursor: null         // current pagination cursor        │
  │    isLoading: false     // prevent concurrent fetches       │
  │    hasMore: true        // more pages available             │
  │    newPostsCount: 0     // unread posts above cursor        │
  │                                                             │
  │  Initial Load:                                              │
  │    (1) Fetch GET /feed?limit=20                            │
  │    (2) Set posts = response.posts                          │
  │    (3) Set cursor = response.next_cursor                   │
  │    (4) Set hasMore = response.has_more                     │
  │    (5) Open WebSocket connection: ws://api/ws/feed         │
  │                                                             │
  │  Scroll Near Bottom (80% threshold):                        │
  │    (1) If isLoading or !hasMore: return                    │
  │    (2) Set isLoading = true                                │
  │    (3) Fetch GET /feed?cursor={cursor}&limit=20            │
  │    (4) Append response.posts to posts[]                    │
  │    (5) Update cursor, hasMore                              │
  │    (6) If gap_detected: show banner with new_posts_count   │
  │    (7) Set isLoading = false                               │
  │                                                             │
  │  Prefetch Optimization:                                     │
  │    When user is at 50% scroll of current page,              │
  │    prefetch next page in background.                        │
  │    Store in memory. When user reaches bottom,               │
  │    render immediately (0ms load time).                      │
  │                                                             │
  │  WebSocket Message "NEW_POSTS":                             │
  │    (1) Increment newPostsCount                             │
  │    (2) Show banner: "N new posts available"                │
  │    (3) On banner tap: fetch fresh feed, scroll to top      │
  │                                                             │
  │  Pull-to-Refresh:                                          │
  │    (1) Reset cursor = null                                 │
  │    (2) Fetch GET /feed?limit=20                            │
  │    (3) Replace posts[] with fresh response                 │
  │    (4) Reset newPostsCount = 0                             │
  │                                                             │
  └────────────────────────────────────────────────────────────┘
```

### 12.5 Deep Pagination: Beyond the Cache

```
Problem: User scrolls 20+ pages deep (400+ posts).
Timeline cache only holds 500 entries. What happens after?

Solution: Fallback to Database

  Page 1-25 (posts 1-500):
    Served from Redis timeline cache. Fast (~2ms).

  Page 26+ (posts 501+):
    (1) Redis cache exhausted (only 500 entries)
    (2) Feed Service detects: has_more_in_cache = false
    (3) Falls back to Cassandra query:
        SELECT * FROM posts_by_id
        WHERE post_id IN (
          SELECT post_id FROM following WHERE follower_id = ?
        )
        AND created_at < {cursor_timestamp}
        ORDER BY created_at DESC
        LIMIT 20
    (4) Much slower: ~50-100ms instead of ~2ms
    (5) Acceptable: very few users scroll this deep
        (< 0.1% of feed requests reach page 26+)

  Optimization: Pre-compute "archive timeline" for power users
    Separate Redis key: archive_timeline:{user_id}
    Holds posts 501-2000, compressed, refreshed weekly.
    Covers even heavy scrollers without hitting Cassandra.
```

---

## 13. Real-time Updates

### 13.1 WebSocket for Active Users

```
┌────────────────────────────────────────────────────────────────┐
│                 WEBSOCKET ARCHITECTURE                          │
│                                                                 │
│  Protocol: RFC 6455 WebSocket over TLS                          │
│  Endpoint: wss://api.example.com/ws/feed                        │
│  Auth: JWT token in connection handshake header                  │
│                                                                 │
│  Connection Flow:                                                │
│                                                                 │
│  (1) Client opens WebSocket connection                           │
│      Client ──upgrade request + JWT──▶ WebSocket Gateway         │
│                                                                 │
│  (2) Gateway authenticates JWT, extracts user_id                │
│      Registers: HSET ws_connections user_123 gateway_node_5     │
│                                                                 │
│  (3) Connection established. Server sends heartbeat every 30s.  │
│      Client responds with pong. If 2 missed: disconnect.        │
│                                                                 │
│  (4) When friend posts:                                          │
│      Kafka event → Push Router → looks up ws_connections         │
│      → publishes to Redis Pub/Sub channel for gateway_node_5    │
│      → gateway_node_5 sends JSON message over WebSocket          │
│                                                                 │
│  (5) When user likes a post visible in feed:                     │
│      Kafka engagement event → Push Router → all users who        │
│      have this post_id in their SUBSCRIBE_POST_UPDATES list     │
│      → send engagement count update                              │
│                                                                 │
│  Message Types (Server → Client):                                │
│                                                                 │
│  Type 1: NEW_POSTS                                               │
│    Trigger: friend or celebrity posts                             │
│    Content: { count, preview_snippet }                           │
│    Action: client shows "N new posts" banner                     │
│                                                                 │
│  Type 2: ENGAGEMENT_UPDATE                                       │
│    Trigger: like/comment/share on a post in user's visible feed │
│    Content: { post_id, like_count, comment_count, share_count } │
│    Action: client updates count badges in-place (no refresh)     │
│                                                                 │
│  Type 3: POST_DELETED                                            │
│    Trigger: author deletes a post that's in user's feed          │
│    Content: { post_id }                                          │
│    Action: client removes post from rendered feed with animation │
│                                                                 │
│  Type 4: TYPING_INDICATOR                                        │
│    Trigger: someone is typing a comment on a visible post        │
│    Content: { post_id, user_id, display_name }                   │
│    Action: "Jane is commenting..." indicator                     │
│                                                                 │
│  Message Types (Client → Server):                                │
│                                                                 │
│  Type 1: SUBSCRIBE_POST_UPDATES                                  │
│    Trigger: client renders a page of feed                        │
│    Content: { post_ids: [...20 visible post IDs] }               │
│    Action: server tracks which posts this user is viewing        │
│            Only send engagement updates for these posts           │
│            Reduces noise: don't push updates for unseen posts    │
│                                                                 │
│  Type 2: ACK_NEW_POSTS                                           │
│    Trigger: user taps "N new posts" banner                       │
│    Content: { acknowledged: true }                               │
│    Action: reset new post counter on server                      │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 13.2 Long Polling Fallback

```
For clients that can't maintain WebSocket (corporate firewalls,
older browsers, unstable mobile connections):

  GET /api/v1/feed/updates?since=2026-04-26T10:30:00Z&timeout=30
  Authorization: Bearer <jwt_token>

  Behavior:
    (1) Server holds the request open for up to 30 seconds
    (2) If new posts arrive before timeout: return immediately
        Response: { new_posts_count: 3, has_updates: true }
    (3) If no updates within 30 seconds: return empty
        Response: { new_posts_count: 0, has_updates: false }
    (4) Client immediately sends next long-poll request

  Comparison to WebSocket:
    - Latency: ~0-30 seconds (vs ~50ms for WebSocket)
    - Server load: higher (one HTTP connection per poll cycle)
    - Firewall friendly: plain HTTP, works everywhere
    - Simpler implementation on both client and server
```

### 13.3 Server-Sent Events (SSE) for One-way Push

```
For one-way server-to-client push (no bidirectional needed):

  GET /api/v1/feed/stream
  Authorization: Bearer <jwt_token>
  Accept: text/event-stream

  Response (streaming):
    HTTP/1.1 200 OK
    Content-Type: text/event-stream
    Cache-Control: no-cache
    Connection: keep-alive

    event: new_posts
    data: {"count": 3, "preview": "John Doe posted..."}

    event: engagement_update
    data: {"post_id": "post_123", "like_count": 145}

    event: heartbeat
    data: {"ts": 1714130000}

  Comparison to WebSocket:
    - Simpler: HTTP-based, automatic reconnection built into EventSource API
    - One-way only: server → client (sufficient for feed updates)
    - Works with HTTP/2 multiplexing (efficient)
    - Cannot send client → server messages (can't send SUBSCRIBE_POST_UPDATES)

  When to use SSE:
    - Feed updates are inherently one-directional
    - Client doesn't need to send subscription messages
    - Simplest implementation for "new posts" banner
```

### 13.4 "New Posts Available" Banner vs Auto-inject

```
Two approaches when new posts arrive:

APPROACH A: BANNER (Recommended - Facebook/LinkedIn style)
┌──────────────────────────────────────────────┐
│                                               │
│  ┌────────────────────────────────────────┐  │
│  │   ↑  5 new posts  (tap to see)        │  │  ← Banner
│  └────────────────────────────────────────┘  │
│                                               │
│  ┌────────────────────────────────────────┐  │
│  │  Post from Alice (5 min ago)           │  │  ← Currently
│  │  "Had an amazing hike..."              │  │     reading
│  └────────────────────────────────────────┘  │
│                                               │
│  ┌────────────────────────────────────────┐  │
│  │  Post from Bob (2 hours ago)           │  │
│  │  "Just shipped v2.0..."               │  │
│  └────────────────────────────────────────┘  │
│                                               │
└──────────────────────────────────────────────┘

  Pros:
    - User stays in their current scroll position
    - No content jumping / scroll jank
    - User CHOOSES when to see new content
    - Lower perceived "chaos" in the feed
  Cons:
    - User may miss time-sensitive posts for a few minutes


APPROACH B: AUTO-INJECT (Twitter style)
┌──────────────────────────────────────────────┐
│                                               │
│  ┌────────────────────────────────────────┐  │
│  │  NEW: Post from Charlie (just now)     │  │  ← Auto-injected
│  │  "Breaking: New framework released..." │  │     at top
│  └────────────────────────────────────────┘  │
│                                               │
│  ┌────────────────────────────────────────┐  │
│  │  NEW: Post from Diana (30 sec ago)     │  │  ← Auto-injected
│  │  "Check out this thread..."            │  │
│  └────────────────────────────────────────┘  │
│                                               │
│  ┌────────────────────────────────────────┐  │  ← User was here
│  │  Post from Alice (5 min ago)           │  │     but got pushed
│  │  "Had an amazing hike..."              │  │     DOWN
│  └────────────────────────────────────────┘  │
│                                               │
└──────────────────────────────────────────────┘

  Pros:
    - Most real-time experience
    - Good for news/live events feeds
  Cons:
    - Scroll position jumps (ANNOYING)
    - User loses track of what they were reading
    - High-velocity friends flood the feed

RECOMMENDED: BANNER approach for default feed.
             AUTO-INJECT only if user opts in or in "live mode."
```

---

## 14. Concurrency

### 14.1 Concurrent Post Publishing

```
Problem: Two users post at the exact same millisecond.

Non-issue for post creation:
  - Each post gets a unique Snowflake ID
  - Snowflake IDs are generated independently per server node
  - Posts write to different partitions in Cassandra (keyed by user_id)
  - No contention: these are independent writes

Potential issue for fan-out:
  - Both posts may fan-out to the same follower
  - Two concurrent ZADD operations to the same Redis key
  - Redis is single-threaded: operations are serialized atomically
  - Sorted Set handles concurrent inserts correctly by design
  - No race condition possible at the Redis level
```

### 14.2 Concurrent Like Counting

```
Problem: 1000 users like the same viral post within 1 second.

Naive approach (BROKEN):
  (1) Read current like_count: 5000
  (2) Increment: 5000 + 1 = 5001
  (3) Write: like_count = 5001
  If 1000 concurrent readers all read 5000, final count = 5001 (LOST UPDATES)

Solution: Atomic Increment + Eventual Consistency

  Layer 1: Redis Atomic Counter (fast, approximate)
    INCR post_likes:{post_id}
    Redis INCR is atomic, single-threaded. No lost updates.
    Use for DISPLAY purposes (what users see in the feed).

  Layer 2: Cassandra Counter Table (durable, source of truth)
    UPDATE post_counters SET like_count = like_count + 1
    WHERE post_id = ?
    Cassandra counter columns handle concurrent increments.

  Layer 3: Like Deduplication
    Before incrementing, check if user already liked:
      EXISTS in likes table (post_id, user_id)
    If already liked: return current count, don't increment.

  Pipeline:
    (1) Client sends POST /posts/{id}/likes
    (2) Server checks: already liked? (Redis SET: post_likers:{post_id})
    (3) If new like:
        SADD post_likers:{post_id} {user_id}  (Redis Set for dedup)
        INCR post_likes:{post_id}              (Redis Counter for display)
        INSERT INTO likes (post_id, user_id)   (Cassandra for durability)
        Kafka event: {type: LIKE, post_id, user_id}
    (4) Return updated count from Redis counter

  Consistency note:
    Redis counter may be slightly ahead of Cassandra.
    If Redis restarts, counter resets. Recovery:
      Rebuild from Cassandra: SELECT COUNT(*) FROM likes WHERE post_id = ?
    This is acceptable: like counts don't need to be exact.
    "142 likes" vs "143 likes" -- no user notices.
```

### 14.3 Cache Invalidation

```
Problem: Post is edited. Cached copies are stale.

Timeline cache (Redis sorted sets):
  Contains post_ids, NOT post content. NO invalidation needed.
  Post IDs don't change when content is edited.

Post content cache (Redis hash):
  Key: post:{post_id}
  Contains: full post data (content, media_urls, counts)
  MUST be invalidated on edit or delete.

Invalidation Strategy: Write-Through + TTL

  (1) Post edited → Post Service updates Cassandra
  (2) Post Service updates Redis cache: HSET post:{post_id} fields...
      (write-through: DB and cache updated atomically in sequence)
  (3) If Redis write fails: cache entry retains old data
      TTL expires within 24h → stale data auto-corrects
  (4) Publish Kafka event: {type: POST_UPDATED, post_id}
  (5) Other service nodes consuming the event:
      invalidate their L1 local cache (Caffeine)

  Why write-through and not write-behind?
    - Write-behind (async) risks data loss if Redis crashes before flush
    - Write-through is simpler and acceptable for social media
    - Posts are edited rarely (~1% of all posts) -- not a hot path
```

### 14.4 Concurrent Follow/Unfollow

```
Problem: User rapidly follows and unfollows the same person.

Race condition:
  Request 1 (follow):  INSERT INTO following, SADD followers
  Request 2 (unfollow): DELETE FROM following, SREM followers

If processed out of order: database says "following" but Redis says "not following"

Solution: Optimistic Locking + Ordered Processing

  (1) Each follow/unfollow request includes a client-side sequence number
  (2) Social Graph Service uses CAS (Compare-And-Swap):
      UPDATE following SET status = 'ACTIVE', seq = 2
      WHERE follower_id = ? AND followee_id = ? AND seq < 2
  (3) Redis update is gated on successful DB update
  (4) If DB update fails (seq already higher): ignore stale request

  Alternatively: Serialize by user pair
    Kafka topic partitioned by hash(follower_id + followee_id)
    Ensures all follow/unfollow events for the same pair
    are processed by the same worker in order.
```

---

## 15. Scaling

### 15.1 Component Scaling Strategy

| Component              | Scaling Approach                           | Details                                                          |
|------------------------|--------------------------------------------|------------------------------------------------------------------|
| **Post Service**       | Horizontal, stateless                      | Any instance handles any post. Scale by CPU/request rate.        |
| **Feed Service**       | Horizontal, stateless                      | Backed by Redis. Scale behind load balancer.                     |
| **Fan-out Service**    | Kafka consumer group scaling               | Add workers proportional to Kafka partitions. Auto-scale on lag. |
| **Ranking Service**    | Horizontal, stateless                      | In-memory scoring. Scale by CPU.                                 |
| **Social Graph Service** | Sharded by user_id                        | Each shard handles a range of user IDs.                          |
| **Timeline Cache**     | Redis Cluster (hash-slot sharding)         | 500M users * 40KB = 20 TB across 40+ nodes.                     |
| **Post Store**         | Cassandra (auto-sharded by partition key)  | Add nodes for more capacity. Linear scalability.                 |
| **WebSocket Gateway**  | Horizontal, sticky sessions                | ~100K connections per node. 1000 nodes for 100M active.          |
| **Push Router**        | Kafka consumer group                       | Scale by event volume. Partitioned by target user.               |
| **Media / CDN**        | S3 (infinite) + CDN edge caching           | Auto-scales by design. No manual intervention.                   |

### 15.2 Timeline Cache Sharding

```
Redis Cluster: 16384 hash slots distributed across N master nodes.

Key: timeline:{user_id}
Hash slot = CRC16("timeline:user_123") % 16384 = slot 7892

Slot assignment:
  Node 1: slots 0 - 4095
  Node 2: slots 4096 - 8191     ← slot 7892 lives here
  Node 3: slots 8192 - 12287
  Node 4: slots 12288 - 16383

Each node: 512 GB RAM, handles ~125M user timelines
With replication factor 2: 8 nodes total (4 master + 4 replica)

Scaling up:
  Add Node 5 + Node 6 (master + replica)
  Redis Cluster reshards: moves 1/5 of slots to new nodes
  Zero-downtime migration using Redis MIGRATE command

Hot spot mitigation:
  Celebrity timelines (frequently read) can become hot keys.
  Solution: local cache on Feed Service nodes (Caffeine, 10s TTL)
  Actual Redis reads for celebrity keys: < 100/second (from cache misses)
```

### 15.3 Fan-out Worker Auto-scaling

```
┌─────────────────────────────────────────────────────────────────┐
│                FAN-OUT AUTO-SCALING                               │
│                                                                  │
│  Metrics:                                                        │
│    Kafka consumer lag = messages_produced - messages_consumed    │
│    Monitored per partition and aggregated                        │
│                                                                  │
│  Scaling Rules:                                                  │
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  IF aggregate_lag > 100K for 2 minutes:                   │  │
│  │    Scale UP: add 8 workers                                │  │
│  │    Max workers: 256 (= 4x Kafka partitions)               │  │
│  │                                                            │  │
│  │  IF aggregate_lag > 500K for 1 minute:                    │  │
│  │    EMERGENCY scale UP: add 32 workers immediately          │  │
│  │    Alert on-call engineer                                  │  │
│  │                                                            │  │
│  │  IF aggregate_lag < 10K for 10 minutes:                   │  │
│  │    Scale DOWN: remove 4 workers                            │  │
│  │    Min workers: 64 (= Kafka partition count)               │  │
│  │    Cooldown: no scale-down within 10 min of scale-up       │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  Monitoring Dashboard:                                           │
│    - Fan-out lag (real-time, per partition)                      │
│    - Fan-out duration p50/p99                                   │
│    - Worker count (current vs target)                           │
│    - Celebrity post rate (separate tracking)                     │
│    - Error rate (Redis failures, timeout rate)                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 15.4 Cache Warming

```
Problem: After Redis node failure or new user signup,
timeline cache is EMPTY (cold cache).

Cold Cache Scenario 1: Redis Node Restarts
  (1) Node restarts → all timelines on that shard are gone
  (2) Users hitting that shard get empty feeds
  (3) Feed Service detects empty cache → CACHE WARMING

  Warming Process:
    (a) Query Social Graph: get user's following list
    (b) For each followed user: get their latest 20 posts from Cassandra
    (c) Score and rank the merged posts
    (d) Populate timeline cache in Redis
    (e) Serve the rebuilt feed to the user

  Latency: ~200-500ms (acceptable as one-time cost)
  This is transparent to the user -- slightly slower first load.

Cold Cache Scenario 2: New User Signs Up
  (1) New user follows 50 people during onboarding
  (2) Timeline cache doesn't exist yet
  (3) Trigger "cold start" cache build (same as above)
  (4) Additionally: suggest popular/trending posts as filler
      until the cache has enough content from followed users

Cold Cache Scenario 3: Returning User (inactive for 30+ days)
  (1) Timeline cache expired (TTL = 7 days)
  (2) User opens app after 2 months
  (3) Cache warming + recirculation:
      Pull last 30 days of posts from followed users
      Score highly: "While you were away, Alice posted 12 times.
      Here are her most-liked posts."
  (4) This "catch-up" experience keeps users engaged on return
```

---

## 16. Database Choice

### 16.1 Database Selection Matrix

| Data                   | Database          | Why This Choice                                                   |
|------------------------|-------------------|-------------------------------------------------------------------|
| **User profiles**      | PostgreSQL        | Relational, ACID, complex queries (search by username), small dataset (~1B rows) |
| **Posts**              | Cassandra         | High write throughput (500M/day), partition by user_id, time-sorted clustering, linear scalability |
| **Post by ID lookup**  | Cassandra         | Separate table, partition by post_id, for hydration during feed read |
| **Follow graph**       | Cassandra + Redis | Cassandra for persistence (followers, following tables), Redis for hot-path lookups (SMEMBERS) |
| **Timeline cache**     | Redis Cluster     | Sorted Sets for ranked timelines, sub-ms reads, pipeline writes, TTL support |
| **Post content cache** | Redis             | Hash per post, fast hydration, TTL 24h for hot posts |
| **Likes / Comments**   | Cassandra         | High write throughput, partition by post_id, append-heavy |
| **Like/comment counts**| Redis             | Atomic INCR for counters, fast display reads |
| **Affinity features**  | Redis             | Feature store for ranking: per-user-pair interaction counts |
| **Media**              | S3 + CDN          | Unlimited storage, global delivery via edge caching |
| **Events**             | Kafka             | High-throughput event bus: post events, engagement events, fan-out |

### 16.2 Why Cassandra for Posts?

```
Requirements that match Cassandra:
  (1) High write throughput: 500M posts/day = ~6K writes/sec
  (2) Partition by user_id: all posts by a user co-located
  (3) Clustering by post_id DESC: latest first (free ordering)
  (4) Linear horizontal scalability: add nodes for more capacity
  (5) No complex JOINs needed for post reads
  (6) Tunable consistency: QUORUM write, ONE read (speed vs safety)

Why NOT PostgreSQL for posts?
  - Sharding PostgreSQL is complex and manual
  - At 500M rows/day, single-node PostgreSQL can't keep up
  - No native support for partition-based data locality
  - JOINs across shards are extremely expensive

Why NOT DynamoDB?
  - Cassandra is open-source, no vendor lock-in
  - Better control over partition design
  - DynamoDB's pricing at this scale is prohibitive
  - DynamoDB's 400KB item limit can be restrictive for rich posts
```

### 16.3 Why Redis for Timeline Cache?

```
Requirements that match Redis:
  (1) Sorted Set: ZADD O(log N), ZREVRANGEBYSCORE O(log N + M)
  (2) Sub-millisecond reads: timeline = single ZREVRANGEBYSCORE
  (3) Pipeline: batch fan-out writes (1000 ZADDs in one round-trip)
  (4) TTL on keys: auto-expire inactive user timelines
  (5) Redis Cluster: horizontal sharding across nodes
  (6) In-memory: 500 entries * ~80 bytes = ~40 KB per user
      500M users * 40 KB = ~20 TB (manageable Redis Cluster)

Why NOT Memcached?
  - No sorted sets → can't do ranked pagination
  - No persistence → cold restart loses all timelines
  - No data structures → must serialize/deserialize

Why NOT DynamoDB (as cache)?
  - Latency: ~5-10ms vs Redis ~0.5ms (10-20x slower)
  - At 175K feed reads/sec: every ms matters
  - DynamoDB doesn't support sorted set operations natively
```

### 16.4 Why Kafka for Events?

```
Requirements that match Kafka:
  (1) High throughput: 500M post events + billions of engagement events/day
  (2) Partitioned: events partitioned by user_id for ordering
  (3) Consumer groups: multiple consumers (fan-out, push, analytics)
  (4) Replayable: 72h retention for debugging, reprocessing
  (5) Exactly-once semantics (with idempotent producers)
  (6) Decoupling: Post Service doesn't know about Fan-out, Push, etc.

Why NOT RabbitMQ?
  - Lower throughput ceiling (~50K msg/sec vs Kafka ~1M msg/sec)
  - No message replay (once consumed, gone)
  - Less suited for streaming/event-sourcing patterns

Why NOT SQS?
  - No ordering guarantees (standard queue) or limited (FIFO)
  - No consumer groups / multi-subscriber pattern
  - Vendor lock-in to AWS
```

---

## 17. CAP Theorem

### Classification: AP System (Availability + Partition Tolerance)

```
┌────────────────────────────────────────────────────────┐
│                   CAP THEOREM                           │
│                                                         │
│  The news feed is an AP system:                         │
│    - Availability: feed MUST load, even if slightly     │
│      stale. A blank feed is WORSE than a stale feed.    │
│    - Partition Tolerance: network partitions between     │
│      data centers will occur. System must not crash.     │
│    - Consistency: RELAXED. Eventually consistent.        │
│      A 5-10 second delay on new posts is acceptable.    │
│                                                         │
│  Why NOT CP?                                            │
│    If we chose strong consistency, a network partition   │
│    between the Redis cache and Cassandra would mean      │
│    REFUSING to serve the feed (returning an error).      │
│    At 175K requests/sec, even 1 second of errors =      │
│    175K failed requests = terrible user experience.      │
│    A stale feed is infinitely better than no feed.       │
│                                                         │
└────────────────────────────────────────────────────────┘
```

### Consistency Decisions by Component

| Component              | Consistency Level        | Rationale                                                          |
|------------------------|--------------------------|--------------------------------------------------------------------|
| **News Feed**          | Eventually consistent    | Stale for 5-10s is fine. User refreshes to see latest.             |
| **Post creation**      | Strongly consistent      | Write at QUORUM to Cassandra. Post must be durable before 201.     |
| **Timeline cache**     | Eventually consistent    | Fan-out is async. Cache may lag behind post creation by seconds.   |
| **Like/comment counts**| Eventually consistent    | Approximate counts are fine. Off by a few is acceptable.           |
| **Follow/unfollow**    | Strongly consistent      | Affects fan-out decisions. Must be accurate. PostgreSQL transaction.|
| **Celebrity detection**| Eventually consistent    | Nightly batch + dynamic reclassification. Slight lag is OK.        |
| **User profile**       | Read-your-writes         | User sees their own edits immediately. Others see within seconds.  |
| **Social graph**       | Strongly consistent      | Source of truth for who follows whom. Drives fan-out correctness.  |

### Read-Your-Writes Consistency for Posts

```
Problem: User posts, refreshes feed, doesn't see their own post.
The fan-out hasn't completed yet (takes seconds).

Solution: Client-side optimistic rendering

  (1) User creates post → POST /posts → 201 Created (with post data)
  (2) Client immediately renders the post at the TOP of the feed
      (from the 201 response, before fan-out completes)
  (3) Server returns the post with a "local_only: true" flag
  (4) Client shows it with normal rendering (user can't tell)
  (5) Background: fan-out completes, post arrives in timeline cache
  (6) Next server fetch: post is in the feed from the cache
  (7) Client detects duplicate (same post_id), removes local copy
      and uses server version (with accurate engagement counts)

  User perceives INSTANT publishing. Actual fan-out is async.
  This is standard practice at Facebook, Twitter, LinkedIn.
```

---

## 18. Cloud Services

| Component              | AWS                           | GCP                          | Azure                          |
|------------------------|-------------------------------|------------------------------|--------------------------------|
| **API Gateway**        | API Gateway                   | Apigee / Cloud Endpoints     | API Management                 |
| **Load Balancer**      | ALB / NLB                     | Cloud Load Balancing         | Application Gateway            |
| **Compute**            | ECS Fargate / EKS             | GKE / Cloud Run              | AKS / Container Apps           |
| **Timeline Cache**     | ElastiCache (Redis Cluster)   | Memorystore (Redis)          | Azure Cache for Redis          |
| **Post Store**         | Keyspaces (managed Cassandra) | Bigtable                     | Cosmos DB (Cassandra API)      |
| **User DB**            | RDS (PostgreSQL)              | Cloud SQL (PostgreSQL)       | Azure DB for PostgreSQL        |
| **Event Bus**          | MSK (Managed Kafka)           | Pub/Sub                      | Event Hubs (Kafka protocol)    |
| **Object Storage**     | S3                            | Cloud Storage                | Blob Storage                   |
| **CDN**                | CloudFront                    | Cloud CDN                    | Azure CDN / Front Door         |
| **WebSocket**          | API Gateway WebSocket API     | Custom on GKE                | Web PubSub Service             |
| **Feature Store**      | SageMaker Feature Store       | Vertex AI Feature Store      | Custom on Redis                |
| **Monitoring**         | CloudWatch + X-Ray            | Cloud Monitoring + Trace     | Azure Monitor + App Insights   |
| **Auto-Scaling**       | ECS Auto Scaling / KEDA       | GKE Autoscaler               | AKS KEDA / VMSS               |
| **Video Transcoding**  | Elastic Transcoder / MediaConvert | Transcoder API            | Media Services                 |
| **Push Notifications** | SNS + Pinpoint                | Firebase Cloud Messaging     | Notification Hubs              |

---

## 19. Tradeoffs Summary

```
+------------------------------+-------------------------------+-------------------------------+
| Decision                     | Our Choice + Why              | Alternative + Why NOT         |
+------------------------------+-------------------------------+-------------------------------+
| Fan-out strategy             | HYBRID (push for normal,      | Pure push: fails for celebs   |
|                              | pull for celebrities)          | (50M writes/post).            |
|                              | Best of both worlds.           | Pure pull: 300 queries per    |
|                              |                               | feed read, too slow.          |
+------------------------------+-------------------------------+-------------------------------+
| Feed ranking                 | Algorithmic (affinity +       | Chronological: simple but     |
|                              | recency + engagement +        | users miss important posts.   |
|                              | content type). Drives         | Full ML: infrastructure       |
|                              | engagement, matches user      | cost, latency, complexity     |
|                              | expectations from FB/LinkedIn.| -- mention but don't build.   |
+------------------------------+-------------------------------+-------------------------------+
| Pagination                   | Cursor-based (score + postId) | Offset-based: breaks with     |
|                              | Stable across new arrivals.   | real-time inserts (duplicates |
|                              |                               | and missed posts).            |
+------------------------------+-------------------------------+-------------------------------+
| Real-time delivery           | WebSocket + "new posts"       | Auto-inject: scroll jumps,    |
|                              | banner. User controls when    | poor UX. Polling: high        |
|                              | to see new content.           | latency, high server load.    |
+------------------------------+-------------------------------+-------------------------------+
| Post storage                 | Cassandra (partition by       | PostgreSQL: can't shard at    |
|                              | user_id, cluster by post_id). | 500M posts/day. DynamoDB:     |
|                              | Linear scalability, free      | expensive at this scale,      |
|                              | ordering within partition.     | 400KB item limit.             |
+------------------------------+-------------------------------+-------------------------------+
| Timeline cache               | Redis Sorted Set. Sub-ms      | DynamoDB: 5-10ms latency,     |
|                              | reads, pipeline writes, TTL.  | no sorted set operations.     |
|                              | 20 TB total across cluster.   | Memcached: no sorted sets.    |
+------------------------------+-------------------------------+-------------------------------+
| Like counting                | Redis INCR (display) +        | Pure DB counter: too slow     |
|                              | Cassandra counter (durable).  | under contention (1K likes/s) |
|                              | Eventually consistent, fast.  | Pure Redis: data loss risk.   |
+------------------------------+-------------------------------+-------------------------------+
| Celebrity threshold          | Static (1M) + dynamic         | Static only: doesn't catch    |
|                              | reclassification. Simple +    | viral users before threshold. |
|                              | adaptive.                     | Dynamic only: unpredictable.  |
+------------------------------+-------------------------------+-------------------------------+
| Unfollow cleanup             | Lazy filter at read time.     | Eager removal: scan 500       |
|                              | No cache modification needed. | entries per timeline to        |
|                              | Unfollowed user's posts       | remove. Expensive, blocking.  |
|                              | filtered during hydration.    |                               |
+------------------------------+-------------------------------+-------------------------------+
| Post deletion                | Soft delete (is_deleted flag).| Hard delete from all caches:  |
|                              | Filter at read time.          | impossible to remove from     |
|                              | Post ages out of timelines.   | 300+ follower timelines.      |
+------------------------------+-------------------------------+-------------------------------+
| Content type storage         | Text in Cassandra, media in   | Inline media: posts become    |
|                              | S3/CDN. Different access      | huge, slows queries, wastes   |
|                              | patterns, different costs.    | DB storage.                   |
+------------------------------+-------------------------------+-------------------------------+
| Consistency model            | AP (availability > consistency)| CP: network partition means  |
|                              | Stale feed is better than     | feed goes DOWN. 175K          |
|                              | no feed. Eventually consistent.| errors/sec is unacceptable.   |
+------------------------------+-------------------------------+-------------------------------+
| Ranking feature storage      | Redis hashes (feature store). | On-the-fly computation:       |
|                              | Pre-computed, sub-ms lookups. | 960 candidates * N features   |
|                              | Updated by async pipeline.    | = too slow at read time.      |
+------------------------------+-------------------------------+-------------------------------+
```

---

## 20. Interview Talking Points

### Opening (2-3 minutes)

```
"A news feed system needs to aggregate content from hundreds of sources,
rank it algorithmically for each user, and deliver it in under 200ms --
all at a scale of 1 billion users and 50 billion daily feed impressions.

The three hardest problems are:
  (1) FAN-OUT: A celebrity post must reach 50M followers without writing
      to 50M timelines. The answer is hybrid fan-out -- push for normal
      users, pull for celebrities.
  (2) RANKING: The feed must be algorithmically ranked (not just
      chronological) using affinity, recency, engagement, and content type.
      This is what separates Facebook from an RSS reader.
  (3) REAL-TIME: Users expect new posts to appear within seconds via
      WebSocket push, with graceful handling of new arrivals during scroll
      via cursor-based pagination and gap detection."
```

### Key Points to Hit

```
(1) HYBRID FAN-OUT (spend the most time here):
    - Normal users (< 1M followers): push post ID to all follower timelines
    - Celebrities (>= 1M followers): skip fan-out; pull at read time
    - THE MATH: 50M followers * 100K writes/sec = 500 seconds = 8+ min
      for a single celebrity post. That's why pure push fails.
    - Hybrid adds ~5-15ms to read path (pulling ~12 celebrity timelines)
      but ELIMINATES 2.5 trillion unnecessary writes per day.

(2) ALGORITHMIC RANKING (the "senior" signal):
    - score = affinity * recency_decay * engagement_boost * type_weight
    - Affinity: weighted sum of interaction signals (likes, comments, shares,
      profile visits, messages) over 30 days. This is WHY your best friend's
      mundane post ranks above a stranger's viral post.
    - Two-pass pipeline: candidate generation (1000 posts) → scoring → diversity
    - Feature store: pre-computed features in Redis for sub-ms scoring

(3) CURSOR-BASED PAGINATION (not offset):
    - Offset breaks when new posts arrive between pages (duplicates)
    - Cursor = {score}_{post_id} of last item. Stable across inserts.
    - Gap detection: "5 new posts available" banner when posts arrive
      above the user's current scroll position.

(4) REAL-TIME PUSH:
    - WebSocket for connected users (100M concurrent)
    - Push "new posts available" notification, NOT the full post
    - Banner approach (Facebook) vs auto-inject (Twitter)
    - SSE as simpler alternative for one-way push

(5) MULTI-CONTENT TYPES:
    - TEXT, IMAGE, VIDEO, LINK, POLL -- different storage, rendering, ranking
    - Media in S3/CDN (separate from post metadata)
    - Content type weight in ranking (video > image > text by default,
      personalized per user based on behavior)

(6) CONCURRENCY:
    - Like counting: Redis INCR (atomic) + Cassandra counter (durable)
    - Cache invalidation: write-through for post edits, TTL as safety net
    - Fan-out: Redis sorted set operations are atomic, no race conditions
```

### Anticipated Interviewer Deep-Dives

```
Q: "How is this different from a basic social media feed (Project 05)?"

A: "Project 05 covered the fan-out fundamentals. This goes deeper in three ways:
    (1) RANKING: We don't just sort by time. We score by affinity, recency,
        engagement, and content type. Two-pass pipeline with feature store.
    (2) REAL-TIME: WebSocket push architecture for live feed updates,
        with connection registry, push routing, and graceful degradation.
    (3) INFINITE SCROLL: Cursor-based pagination with gap detection,
        prefetching, and deep-pagination fallback to database.
    These are the production-scale concerns that distinguish a mid-level
    answer from a senior-level answer."

Q: "Walk me through what happens when a celebrity with 50M followers posts."

A: "Celebrity is flagged as fan-out-on-read (follower_count > 1M).
    WRITE PATH: Post is stored in Cassandra and published to Kafka.
    Fan-out Service checks: celebrity? YES. Skip fan-out. Done in ~5ms.
    The post lives ONLY in the celebrity's user timeline.

    READ PATH: When any of the 50M followers opens their feed:
    (1) Read pre-computed timeline from Redis (normal friends' posts)
    (2) Check celebrity_follows set: contains this celebrity
    (3) Pull celebrity's latest 5 posts from their user timeline
    (4) Merge with pre-computed, rank, return top 20

    THUNDERING HERD: 10M followers open the app within 60 seconds.
    (a) Local cache on Feed Service nodes (Caffeine, 10s TTL)
    (b) Singleflight: concurrent requests for same celebrity data coalesced
    (c) Result: 10M requests → ~600 actual Redis reads. Solved."

Q: "How do you rank posts? Walk me through the formula."

A: "score = affinity * recency * engagement * type_weight * neg_penalty

    AFFINITY is the key differentiator: weighted sum of how often I
    interact with this author (likes: 0.25, comments: 0.35, shares: 0.20,
    profile visits: 0.10, messages: 0.10) over last 30 days.
    Range: 0.1 (stranger) to 2.0 (best friend).

    RECENCY: 1/(1 + age_hours * 0.05). Harmonic decay. A 6h-old post
    from a close friend still ranks high if engagement is strong.

    ENGAGEMENT: 1 + log2(weighted_sum). Log prevents viral posts from
    dominating permanently. 50 likes matters more than 5000→50000.

    Worked example: best friend's 3h-old image with 50 likes (score: 12.39)
    beats a stranger's 1h-old viral video with 500 likes (score: 2.11)
    because affinity dominates."

Q: "What about offset-based pagination? Why cursor?"

A: "Offset breaks in real-time feeds. Example:
    Page 1: posts at positions 1-20. User reads them.
    3 new posts arrive with high scores, shifting everything down.
    Page 2 with OFFSET 20: user sees posts at new positions 21-40,
    which includes posts 18-20 from the original ordering. DUPLICATES.

    Cursor-based: cursor = score of last seen post. Next page = posts
    with score BELOW the cursor. New posts have higher scores and
    appear ABOVE the cursor. No duplicates. No misses.

    For ties: cursor = {score}_{post_id}. Post ID breaks ties."

Q: "How do you handle users who scroll very deep (page 25+)?"

A: "Timeline cache holds 500 entries (25 pages of 20).
    Pages 1-25: served from Redis. ~2ms latency.
    Page 26+: fall back to Cassandra. Query posts from followed users
    by created_at descending. ~50-100ms latency.
    Only 0.1% of requests reach page 26. Acceptable tradeoff.
    Optimization: archive_timeline Redis key for power users (posts 501-2000)."

Q: "What if Redis goes down?"

A: "Feed degrades gracefully:
    (1) L1 local cache (Caffeine) on Feed Service: serves recent requests
    (2) If local cache also empty: fall back to Cassandra queries
        (fan-out-on-read for ALL users temporarily)
    (3) Latency increases from ~30ms to ~200-500ms. Functional but slower.
    (4) Redis Cluster with replication: if one master fails, replica promotes.
        Typical failover: <30 seconds. Most users don't notice.
    (5) After Redis recovery: timelines rebuild organically as new posts
        are fanned out. Cold-start warming for active users requesting feeds."
```

### Time Allocation (45-minute Interview)

```
+------+---------------------------------+---------------------------------------+
| Min  | Phase                           | What to Cover                         |
+------+---------------------------------+---------------------------------------+
| 0-3  | Requirements + Scope            | 1B users, 500M DAU, 50B impressions   |
|      |                                 | In scope: feed, ranking, real-time     |
+------+---------------------------------+---------------------------------------+
| 3-5  | Back-of-envelope + NFRs         | Feed < 200ms, push < 1s, 99.99%      |
|      |                                 | 5B reads/day, 500M posts/day          |
+------+---------------------------------+---------------------------------------+
| 5-8  | API Design                      | GET /feed?cursor=X&limit=20           |
|      |                                 | POST /posts, WebSocket /ws/feed       |
+------+---------------------------------+---------------------------------------+
| 8-11 | Data Model                      | Posts (Cassandra), Timeline (Redis),  |
|      |                                 | Follow (Cassandra + Redis)            |
+------+---------------------------------+---------------------------------------+
| 11-15| High-Level Architecture         | Draw ASCII diagram. Identify services.|
|      |                                 | Write path + Read path flows.         |
+------+---------------------------------+---------------------------------------+
|      |                                                                         |
| 15-27| *** FAN-OUT DEEP DIVE ***       | Push vs Pull vs HYBRID. The math.     |
|      | (12 min = star of interview)    | Celebrity problem. Back-pressure.     |
|      |                                 | Kafka → workers → Redis pipeline.     |
+------+---------------------------------+---------------------------------------+
|      |                                                                         |
| 27-34| *** RANKING DEEP DIVE ***       | Affinity, recency, engagement,        |
|      | (7 min = differentiator)        | type weight. Formula + worked example.|
|      |                                 | Two-pass pipeline. Feature store.     |
+------+---------------------------------+---------------------------------------+
| 34-38| Pagination + Real-time          | Cursor vs offset. Gap detection.      |
|      |                                 | WebSocket + banner. SSE fallback.     |
+------+---------------------------------+---------------------------------------+
| 38-42| Scaling + DB choices            | Redis Cluster, Cassandra, Kafka.      |
|      |                                 | Fan-out auto-scaling. Cache warming.  |
+------+---------------------------------+---------------------------------------+
| 42-45| Tradeoffs + Edge Cases          | AP not CP. Soft delete. Lazy filter.  |
|      |                                 | Thundering herd. Cold start.          |
+------+---------------------------------+---------------------------------------+
```

### One-Liner Summaries (Quick Recall)

```
- Fan-out:         "Hybrid: push for 99.99%, pull for 0.01% (celebrities)"
- Ranking:         "score = affinity * recency * engagement * type_weight"
- Affinity:        "How often YOU interact with THIS person over 30 days"
- Pagination:      "Cursor = {score}_{post_id}. Stable under real-time inserts."
- Gap detection:   "New posts arrive above cursor → 'N new posts' banner"
- Real-time:       "WebSocket push notification, NOT the full post content"
- Celebrity math:  "50M writes at 100K/sec = 500 seconds. That's why hybrid."
- Cache:           "Redis Sorted Set: ZADD on write, ZREVRANGEBYSCORE on read"
- Consistency:     "AP: stale feed for 5s is better than no feed"
- Content types:   "Text in Cassandra, media in S3/CDN, references in post"
- Like counts:     "Redis INCR (fast display) + Cassandra counter (durable)"
- Cold start:      "Pull last 20 posts from each followed user, rank, cache"
```

---

> **Final Note**: In a news feed interview, the three things that separate a senior answer from a mid-level answer are: (1) the hybrid fan-out with concrete math on why pure push fails, (2) algorithmic ranking with a clear formula and worked example showing affinity domination, and (3) cursor-based pagination with gap detection for infinite scroll. Hit all three with quantitative reasoning and you will ace this question.
