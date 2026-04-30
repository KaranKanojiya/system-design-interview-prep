# Low-Level Design: News Feed System (Facebook/LinkedIn)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Fan-out Strategies, Ranking Algorithms, Timeline Caching, Hybrid Push/Pull, Cursor-based Pagination
> This is the classic distributed systems interview question. It tests fan-out on write vs. read trade-offs, celebrity/hotkey handling (hybrid approach), feed ranking with affinity/recency/engagement scoring, and cursor-based infinite scroll pagination.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Service Layer Design](#7-service-layer-design)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [SOLID Principles Applied](#9-solid-principles-applied)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: User (userId, name, followerCount, isCelebrity >10K), Post (Builder, postId, authorId, content, contentType, media, timestamp, likes, comments), ContentType (enum: TEXT, IMAGE, VIDEO, LINK, POLL), FeedItem (post + score + source TIMELINE/PULLED + position), Comment (commentId, postId, authorId, content, timestamp), Like (userId, postId, timestamp), Follow (followerId, followeeId, timestamp), FeedCursor (lastPostId, lastTimestamp, pageSize for infinite scroll). |
| **Strategy (Fanout)** | `strategy/fanout/` | Pluggable post distribution: FanoutOnWriteStrategy (push to all follower timelines at write time), FanoutOnReadStrategy (no-op at write, pull at read time), HybridFanoutStrategy (write for normal users, read for celebrities). Strategy pattern -- swap distribution model without touching service logic. The Hybrid strategy is the interview-winning answer. |
| **Strategy (Ranking)** | `strategy/ranking/` | Pluggable feed ranking: ChronologicalRankingStrategy (sort by timestamp desc -- Twitter classic), AlgorithmicRankingStrategy (score = affinity * recency * engagement * typeWeight -- Facebook/LinkedIn style). Strategy pattern -- swap ranking without changing FeedService. |
| **Service** | `service/` | Business logic: FeedService (Facade -- generates ranked feed for a user), PostService (create/get posts, trigger fan-out), FanoutService (distributes posts to follower timelines), TimelineService (manages per-user timeline cache), RankingService (applies ranking strategy), SocialGraphService (follow/unfollow, get followers/following, celebrity detection), EngagementService (likes, comments, share counts), NotificationService (push notifications on mentions/likes). |
| **Store** | `store/` | Timeline cache: TimelineCache interface with InMemoryTimelineCache (sorted TreeMap per user, capped at 1000 entries). This simulates Redis sorted sets in production. |
| **Repository** | `repository/` | Data access layer: PostRepository, UserRepository, FollowRepository, EngagementRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like API entry point: FeedController maps requests to FeedService/PostService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | FeedStatsDisplay: feed generation stats, fan-out metrics, cache hit rates, engagement counts. |
| **Exception** | `exception/` | Domain exceptions: FeedException (base), UserNotFoundException, PostNotFoundException. |

### Why News Feed Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you know fan-out on write vs. fan-out on read?            --> Core Trade-off
  2. Can you handle the celebrity problem (millions of followers)? --> Hybrid Fan-out
  3. Is your ranking pluggable (chronological vs. algorithmic)?   --> Strategy Pattern
  4. Do you support cursor-based pagination (not offset-based)?   --> Infinite Scroll
  5. Is timeline cache per-user with bounded size?                --> Memory Management
  6. Can you explain affinity * recency * engagement scoring?     --> Ranking Model
  7. Is your FeedService a clean Facade over multiple services?   --> Facade Pattern
  8. Do you separate social graph from content from timeline?     --> Separation of Concerns
  9. Can you add a new content type without changing PostService? --> Open-Closed
  10. Is fan-out thread-safe for concurrent post creation?         --> Concurrency
```

---

## 2. Package Structure

```
com.systemdesign.newsfeed
│
├── model/
│   ├── User.java               -- userId, name, followerCount, isCelebrity (>10K followers)
│   ├── Post.java               -- Builder, postId, authorId, content, contentType, media, timestamp, likes, comments
│   ├── ContentType.java        -- enum: TEXT, IMAGE, VIDEO, LINK, POLL
│   ├── FeedItem.java           -- post + score + source (TIMELINE/PULLED), position
│   ├── Comment.java            -- commentId, postId, authorId, content, timestamp
│   ├── Like.java               -- userId, postId, timestamp
│   ├── Follow.java             -- followerId, followeeId, timestamp
│   └── FeedCursor.java         -- lastPostId, lastTimestamp, pageSize (for infinite scroll)
│
├── strategy/
│   ├── fanout/
│   │   ├── FanoutStrategy.java       -- interface: distribute(Post, User, List<User> followers)
│   │   ├── FanoutOnWriteStrategy.java -- push to all follower timelines
│   │   ├── FanoutOnReadStrategy.java  -- no-op at write, pull at read
│   │   └── HybridFanoutStrategy.java -- write for normal, read for celebrities (Composite)
│   │
│   └── ranking/
│       ├── RankingStrategy.java       -- interface: rank(List<FeedItem>, User) → List<FeedItem>
│       ├── ChronologicalRankingStrategy.java -- sort by timestamp desc
│       └── AlgorithmicRankingStrategy.java   -- score = affinity * recency * engagement * typeWeight
│
├── service/
│   ├── FeedService.java        -- FACADE: generates ranked feed for a user
│   ├── PostService.java        -- create/get posts, trigger fan-out
│   ├── FanoutService.java      -- distributes posts to follower timelines
│   ├── TimelineService.java    -- manages per-user timeline cache (sorted by time)
│   ├── RankingService.java     -- applies ranking strategy
│   ├── SocialGraphService.java -- follow/unfollow, get followers/following, celebrity detection
│   ├── EngagementService.java  -- likes, comments, share counts
│   └── NotificationService.java -- push notifications (simulated)
│
├── store/
│   ├── TimelineCache.java         -- interface: addToTimeline, getTimeline
│   └── InMemoryTimelineCache.java -- sorted TreeMap per user, capped at 1000 entries
│
├── repository/
│   ├── PostRepository.java, InMemoryPostRepository.java
│   ├── UserRepository.java, InMemoryUserRepository.java
│   ├── FollowRepository.java, InMemoryFollowRepository.java
│   └── EngagementRepository.java, InMemoryEngagementRepository.java
│
├── controller/
│   └── FeedController.java     -- REST-like entry point
│
├── config/
│   └── AppConfig.java          -- factory wiring, pure constructor injection
│
├── display/
│   └── FeedStatsDisplay.java   -- formatted feed/engagement/cache stats
│
├── exception/
│   ├── FeedException.java           -- base exception for all feed errors
│   ├── UserNotFoundException.java   -- thrown when user lookup fails
│   └── PostNotFoundException.java   -- thrown when post lookup fails
│
└── NewsFeedApp.java            -- Main demo: wires everything, runs feed scenarios
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                  FAN-OUT STRATEGY HIERARCHY (THE Core Trade-off)                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-------------------------------------------------------------------+
    |            <<interface>>  FanoutStrategy                           |
    |-------------------------------------------------------------------|
    | + distribute(post: Post, author: User,                            |
    |              followers: List<User>): void                         |
    | + shouldPullOnRead(author: User): boolean                        |
    | + getStrategyName(): String                                      |
    +-------------------------------------------------------------------+
          ^                       ^                        ^
          |                       |                        |
     implements              implements               implements
          |                       |                        |
    +-----+----------+   +-------+----------+   +---------+--------+
    | FanoutOnWrite  |   | FanoutOnRead     |   | HybridFanout     |
    |   Strategy     |   |   Strategy       |   |   Strategy       |
    |----------------|   |------------------|   |------------------|
    | -timelineCache |   |                  |   | -writeStrategy   |
    |  :TimelineCache|   |                  |   | -readStrategy    |
    |----------------|   |------------------|   | -celebrityThresh |
    | distribute():  |   | distribute():    |   |------------------|
    |  push postId   |   |  no-op (skip)    |   | distribute():    |
    |  to EVERY      |   |  posts stay in   |   |  if author is    |
    |  follower's    |   |  author's post   |   |  celebrity →     |
    |  timeline      |   |  list, pulled    |   |   readStrategy   |
    |  cache         |   |  at read time    |   |  else →          |
    |                |   |                  |   |   writeStrategy   |
    | shouldPull:    |   | shouldPull:      |   |                  |
    |  false         |   |  true (always)   |   | shouldPull:      |
    |                |   |                  |   |  true only for   |
    |                |   |                  |   |  celebrity posts  |
    +----------------+   +------------------+   +------------------+


    FAN-OUT DECISION FLOW:

    User publishes a post
         │
         ▼
    Is author a celebrity (followers > 10K)?
         │                    │
        YES                  NO
         │                    │
         ▼                    ▼
    Fan-out on READ       Fan-out on WRITE
    (don't push to        (push postId to
     millions of           every follower's
     timelines)            timeline cache)
         │                    │
         ▼                    ▼
    At READ time:         At READ time:
    Pull celebrity        Timeline cache
    posts from their      already has the
    post lists and        posts -- just
    merge with timeline   read and rank


    WHY HYBRID?
    ┌──────────────────────────────────────────────────────────────────┐
    │  Fan-out on Write ONLY:                                         │
    │    - Celebrity with 10M followers → 10M cache writes per post   │
    │    - Takes minutes to propagate, wastes storage                 │
    │    - Most followers never log in → wasted writes                │
    │                                                                  │
    │  Fan-out on Read ONLY:                                          │
    │    - Every feed request queries ALL followed users' post lists  │
    │    - User follows 500 people → 500 queries per feed load        │
    │    - High read latency, N+1 query problem                       │
    │                                                                  │
    │  Hybrid (THE answer interviewers want):                         │
    │    - Normal users (< 10K followers): push at write time         │
    │      → their followers get instant timeline updates              │
    │    - Celebrities (>= 10K followers): pull at read time          │
    │      → no thundering herd on celebrity post                      │
    │    - At read time, merge pushed timeline + pulled celebrity      │
    │      posts, then rank → best of both worlds                     │
    └──────────────────────────────────────────────────────────────────┘


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                  RANKING STRATEGY HIERARCHY (Strategy Pattern)                    ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-------------------------------------------------------------------+
    |            <<interface>>  RankingStrategy                          |
    |-------------------------------------------------------------------|
    | + rank(items: List<FeedItem>, viewer: User): List<FeedItem>       |
    | + getStrategyName(): String                                      |
    +-------------------------------------------------------------------+
          ^                                    ^
          |                                    |
     implements                           implements
          |                                    |
    +-----+------------------+   +-------------+------------------+
    | Chronological          |   | Algorithmic                    |
    |  RankingStrategy       |   |  RankingStrategy               |
    |------------------------|   |--------------------------------|
    |                        |   | -affinityWeight: double (0.3)  |
    |                        |   | -recencyWeight: double (0.3)   |
    |                        |   | -engagementWeight: double (0.2)|
    |                        |   | -typeWeight: double (0.2)      |
    |------------------------|   |--------------------------------|
    | rank():                |   | rank():                        |
    |  sort by timestamp     |   |  for each item:                |
    |  descending            |   |    score = affinityScore       |
    |  (Twitter classic)     |   |           * recencyScore       |
    |                        |   |           * engagementScore    |
    |                        |   |           * contentTypeWeight  |
    |                        |   |  sort by score desc            |
    +------------------------+   +--------------------------------+

    SCORING FORMULA (Algorithmic):

    ┌──────────────────────────────────────────────────────────────────┐
    │   score = (W_a * affinity) + (W_r * recency) +                  │
    │           (W_e * engagement) + (W_t * typeWeight)               │
    │                                                                  │
    │   WHERE:                                                         │
    │     affinity    = interactions(viewer, author) / maxInteractions │
    │                   (how often viewer engages with this author)    │
    │     recency     = 1.0 / (1 + hoursAge * decayFactor)            │
    │                   (newer posts score higher, exponential decay)  │
    │     engagement  = (likes + 2*comments + 3*shares) / normFactor  │
    │                   (comments weighted higher than likes)          │
    │     typeWeight  = contentTypeBoost(VIDEO=1.3, IMAGE=1.2,        │
    │                   POLL=1.1, LINK=1.0, TEXT=0.9)                 │
    │                                                                  │
    │   Default weights: W_a=0.3, W_r=0.3, W_e=0.2, W_t=0.2         │
    └──────────────────────────────────────────────────────────────────┘


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    SERVICE LAYER (Facade Pattern)                                 ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────────────────────────────────────────────────────────────────┐
    │                    FeedController                                    │
    │   getFeed() │ createPost() │ likePost() │ follow() │ comment()     │
    └────────┬────────────┬───────────┬────────────┬──────────┬──────────┘
             │            │           │            │          │
             ▼            ▼           ▼            ▼          ▼
    ┌─────────────────────────────────────────────────────────────────────┐
    │                    FeedService (FACADE)                              │
    │   Orchestrates: timeline read → celebrity pull → merge → rank       │
    └──┬──────────┬───────────┬──────────┬───────────┬──────────┬────────┘
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Post       Fanout     Timeline   Ranking    SocialGraph  Engagement
    Service    Service    Service    Service    Service      Service
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Post       Fanout     Timeline   Ranking    Follow      Engagement
    Repo       Strategy   Cache      Strategy   Repo        Repo
               (pluggable)(bounded)  (pluggable)


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    TIMELINE CACHE (Per-User Sorted Set)                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌──────────────────────────────────────────────────────────────────┐
    │            <<interface>>  TimelineCache                          │
    │─────────────────────────────────────────────────────────────────│
    │ + addToTimeline(userId, postId, timestamp): void                │
    │ + getTimeline(userId, cursor): List<String>                     │
    │ + removeFromTimeline(userId, postId): void                      │
    │ + getTimelineSize(userId): int                                  │
    │ + clearTimeline(userId): void                                   │
    +─────────────────────────────────────────────────────────────────+
              ^
              |
         implements
              |
    +---------+--------------------------------------------------+
    |         InMemoryTimelineCache                               |
    |------------------------------------------------------------|
    | - timelines: Map<String, TreeMap<Long, String>>             |
    |   (userId → sorted map of timestamp → postId)              |
    | - MAX_TIMELINE_SIZE: int = 1000                            |
    |------------------------------------------------------------|
    | + addToTimeline(userId, postId, timestamp):                 |
    |     get-or-create TreeMap for user                          |
    |     put(timestamp, postId)                                  |
    |     if size > MAX_TIMELINE_SIZE → evict oldest              |
    |                                                             |
    | + getTimeline(userId, cursor):                             |
    |     if cursor == null → return latest pageSize entries      |
    |     else → return entries BEFORE cursor.lastTimestamp       |
    |     (enables infinite scroll pagination)                    |
    +-------------------------------------------------------------+

    MEMORY MODEL (simulates Redis Sorted Sets):

    User "user-001" timeline:
    ┌──────────────────────┬───────────────┐
    │  timestamp (score)   │  postId       │
    ├──────────────────────┼───────────────┤
    │  1714200000000       │  post-099     │  ← newest (top of feed)
    │  1714199000000       │  post-098     │
    │  1714198000000       │  post-095     │
    │  1714197000000       │  post-091     │
    │  ...                 │  ...          │
    │  1714100000000       │  post-001     │  ← oldest (evicted when > 1000)
    └──────────────────────┴───────────────┘

    Cursor-based pagination:
      Page 1: cursor=null           → posts 099, 098, 095, ...  (pageSize=20)
      Page 2: cursor={ts=098's ts}  → posts 091, 089, 087, ...  (next 20)
      Page 3: cursor={ts=087's ts}  → posts 085, 083, 080, ...  (next 20)
```

---

## 4. Entity Design

### 4.1 User

```java
/**
 * Represents a user in the news feed system.
 *
 * Key design decision: isCelebrity is computed from followerCount.
 * Celebrity threshold is 10,000 followers. This drives the HYBRID
 * fan-out decision: celebrity posts use fan-out on read, normal
 * users use fan-out on write.
 *
 * Used by:
 *   - SocialGraphService: follow/unfollow, get followers/following
 *   - FanoutService: checks isCelebrity() to pick fan-out path
 *   - RankingService: uses viewer's affinity with post authors
 *   - PostService: validates author exists before creating post
 *
 * Why a class (not record)?
 *   - followerCount is mutable: changes on every follow/unfollow.
 *   - Records are immutable, so a class fits better here.
 *   - We still make fields private with controlled mutation.
 */
public class User {

    private static final int CELEBRITY_THRESHOLD = 10_000;

    private final String userId;
    private final String name;
    private int followerCount;        // mutable: changes on follow/unfollow

    public User(String userId, String name) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        this.userId = userId;
        this.name = name;
        this.followerCount = 0;
    }

    /**
     * Celebrity detection: drives the hybrid fan-out decision.
     *
     * In a real system (Facebook), celebrity threshold might be dynamic
     * and based on recent activity patterns, not just follower count.
     * For interview purposes, a static threshold is sufficient.
     *
     * CALL CHAIN:
     *   HybridFanoutStrategy.distribute(post, author, followers)
     *     → author.isCelebrity()
     *       → true  → FanoutOnReadStrategy.distribute()   (no-op)
     *       → false → FanoutOnWriteStrategy.distribute()   (push to all)
     */
    public boolean isCelebrity() {
        return followerCount >= CELEBRITY_THRESHOLD;
    }

    public void incrementFollowerCount() { this.followerCount++; }
    public void decrementFollowerCount() {
        if (this.followerCount > 0) this.followerCount--;
    }

    // --- Getters ---
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public int getFollowerCount() { return followerCount; }

    @Override
    public String toString() {
        return String.format("User[id=%s, name=%s, followers=%d, celebrity=%s]",
            userId, name, followerCount, isCelebrity());
    }
}
```

---

### 4.2 Post (Builder Pattern)

> **Builder pattern**: Post has many optional fields (media, contentType defaults to TEXT, likes/comments initialized to 0). Builder avoids telescoping constructors and provides a fluent API. PostId and timestamp are generated automatically.

#### Anti-Pattern: Telescoping Constructors

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: Telescoping Post Constructor              │
     │                                                                  │
     │   // Which parameter is which? Unreadable at the call site.     │
     │   Post post = new Post(                                          │
     │       "post-001",             // postId                          │
     │       "user-123",             // authorId                        │
     │       "Hello world!",         // content                         │
     │       ContentType.TEXT,       // contentType                     │
     │       null,                   // mediaUrl (nullable)             │
     │       Instant.now(),          // timestamp                       │
     │       0,                      // likeCount                       │
     │       0,                      // commentCount                    │
     │       0                       // shareCount                      │
     │   );                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. 9 constructor params -- unreadable at call site           │
     │     2. postId should be auto-generated, not passed in            │
     │     3. timestamp should be auto-set, not passed in               │
     │     4. likeCount, commentCount, shareCount always start at 0    │
     │        -- why pass them?                                         │
     │     5. mediaUrl is nullable -- leads to null checks everywhere   │
     │     6. No validation: content could be empty                     │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Builder Pattern

```java
/**
 * A post in the news feed system.
 *
 * Uses the BUILDER PATTERN for clean construction:
 *   Post post = new Post.Builder("user-123", "Hello world!")
 *       .contentType(ContentType.IMAGE)
 *       .mediaUrl("https://example.com/photo.jpg")
 *       .build();
 *
 * Key design decisions:
 *   1. postId is auto-generated (UUID) -- never passed by caller.
 *   2. timestamp is auto-set to Instant.now() -- never passed by caller.
 *   3. Engagement counters (likes, comments, shares) start at 0.
 *   4. contentType defaults to TEXT if not specified.
 *   5. mediaUrl is Optional<String> -- no null checks downstream.
 *   6. Builder validates authorId and content are non-blank.
 *
 * Used by:
 *   - PostService: creates posts and triggers fan-out
 *   - FeedService: retrieves posts for feed generation
 *   - EngagementService: updates like/comment/share counts
 *   - TimelineCache: stores postId references (not full Post objects)
 */
public class Post {

    private final String postId;
    private final String authorId;
    private final String content;
    private final ContentType contentType;
    private final String mediaUrl;            // nullable -- only for IMAGE/VIDEO/LINK
    private final Instant timestamp;
    private int likeCount;
    private int commentCount;
    private int shareCount;

    /** Private constructor: only Builder can create Post instances. */
    private Post(Builder builder) {
        this.postId = UUID.randomUUID().toString();
        this.authorId = builder.authorId;
        this.content = builder.content;
        this.contentType = builder.contentType;
        this.mediaUrl = builder.mediaUrl;
        this.timestamp = Instant.now();
        this.likeCount = 0;
        this.commentCount = 0;
        this.shareCount = 0;
    }

    // --- Engagement mutations (called by EngagementService) ---
    public void incrementLikes() { this.likeCount++; }
    public void decrementLikes() { if (this.likeCount > 0) this.likeCount--; }
    public void incrementComments() { this.commentCount++; }
    public void incrementShares() { this.shareCount++; }

    // --- Getters ---
    public String getPostId() { return postId; }
    public String getAuthorId() { return authorId; }
    public String getContent() { return content; }
    public ContentType getContentType() { return contentType; }
    public Optional<String> getMediaUrl() { return Optional.ofNullable(mediaUrl); }
    public Instant getTimestamp() { return timestamp; }
    public int getLikeCount() { return likeCount; }
    public int getCommentCount() { return commentCount; }
    public int getShareCount() { return shareCount; }

    @Override
    public String toString() {
        return String.format("Post[id=%s, author=%s, type=%s, likes=%d, comments=%d, shares=%d]",
            postId, authorId, contentType, likeCount, commentCount, shareCount);
    }

    /**
     * Builder for Post.
     *
     * Required: authorId, content.
     * Optional: contentType (defaults to TEXT), mediaUrl.
     *
     * Usage:
     *   Post textPost = new Post.Builder("user-1", "Just a thought")
     *       .build();
     *
     *   Post imagePost = new Post.Builder("user-1", "Check this out!")
     *       .contentType(ContentType.IMAGE)
     *       .mediaUrl("https://cdn.example.com/img.jpg")
     *       .build();
     */
    public static class Builder {
        private final String authorId;       // required
        private final String content;        // required
        private ContentType contentType = ContentType.TEXT;  // default
        private String mediaUrl = null;      // optional

        public Builder(String authorId, String content) {
            if (authorId == null || authorId.isBlank()) {
                throw new IllegalArgumentException("authorId cannot be null or blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content cannot be null or blank");
            }
            this.authorId = authorId;
            this.content = content;
        }

        public Builder contentType(ContentType contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder mediaUrl(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        public Post build() {
            // Validate: IMAGE/VIDEO/LINK should have a mediaUrl
            if ((contentType == ContentType.IMAGE || contentType == ContentType.VIDEO
                    || contentType == ContentType.LINK) && mediaUrl == null) {
                throw new IllegalStateException(
                    contentType + " post requires a mediaUrl");
            }
            return new Post(this);
        }
    }
}
```

---

### 4.3 ContentType

```java
/**
 * Types of content that can appear in a post.
 *
 * Each type has a boost factor used by AlgorithmicRankingStrategy:
 *   VIDEO > IMAGE > POLL > LINK > TEXT
 *
 * Rationale: video content drives higher engagement on most platforms
 * (Facebook, LinkedIn, Instagram all boost video in their algorithms).
 *
 * Used by:
 *   - Post.Builder: sets the content type
 *   - AlgorithmicRankingStrategy: applies typeWeight to score
 *   - FeedStatsDisplay: shows content type distribution in feed
 */
public enum ContentType {
    TEXT(0.9),
    IMAGE(1.2),
    VIDEO(1.3),
    LINK(1.0),
    POLL(1.1);

    private final double boostFactor;

    ContentType(double boostFactor) {
        this.boostFactor = boostFactor;
    }

    /** Used by ranking algorithm to weight content types. */
    public double getBoostFactor() {
        return boostFactor;
    }
}
```

---

### 4.4 FeedItem

```java
/**
 * A single item in a user's feed.
 *
 * Wraps a Post with additional metadata needed for feed presentation:
 *   - score: computed by the ranking strategy (higher = shown first)
 *   - source: where this item came from (TIMELINE cache or PULLED from author)
 *   - position: final position in the rendered feed (1-based)
 *
 * FeedItem is a transient object: it exists only during feed generation.
 * It is NOT persisted. The timeline cache stores postIds, not FeedItems.
 *
 * Used by:
 *   - FeedService: builds FeedItems from posts, passes to RankingService
 *   - RankingService: sets score, sorts by score, assigns position
 *   - FeedController: returns List<FeedItem> to the client
 *
 * Why separate from Post?
 *   - Score is viewer-specific (same post has different scores for different users)
 *   - Source tracks whether the item was pre-cached or pulled at read time
 *   - Position is presentation-layer data that does not belong in the domain Post
 */
public class FeedItem {

    /**
     * Source of the feed item: where did it come from?
     *   TIMELINE: was already in the user's timeline cache (fan-out on write)
     *   PULLED: fetched at read time from the author's post list (fan-out on read)
     */
    public enum Source { TIMELINE, PULLED }

    private final Post post;
    private double score;          // set by ranking strategy
    private final Source source;
    private int position;          // set after ranking (1 = top of feed)

    public FeedItem(Post post, Source source) {
        if (post == null) {
            throw new IllegalArgumentException("post cannot be null");
        }
        this.post = post;
        this.source = source;
        this.score = 0.0;
        this.position = 0;
    }

    // --- Setters (used during feed generation pipeline) ---
    public void setScore(double score) { this.score = score; }
    public void setPosition(int position) { this.position = position; }

    // --- Getters ---
    public Post getPost() { return post; }
    public double getScore() { return score; }
    public Source getSource() { return source; }
    public int getPosition() { return position; }

    @Override
    public String toString() {
        return String.format("FeedItem[pos=%d, score=%.4f, source=%s, post=%s]",
            position, score, source, post.getPostId());
    }
}
```

---

### 4.5 Comment, Like, Follow

```java
/**
 * A comment on a post.
 *
 * Immutable record: once created, a comment does not change.
 * Deletions would be handled via soft-delete (not in scope for LLD).
 *
 * Used by:
 *   - EngagementService: creates comments, updates Post.commentCount
 *   - EngagementRepository: stores comments by postId
 */
public record Comment(
    String commentId,
    String postId,
    String authorId,
    String content,
    Instant timestamp
) {
    public Comment {
        if (commentId == null || commentId.isBlank()) {
            throw new IllegalArgumentException("commentId required");
        }
        if (postId == null || postId.isBlank()) {
            throw new IllegalArgumentException("postId required");
        }
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("authorId required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("comment content cannot be empty");
        }
    }
}
```

```java
/**
 * A like on a post.
 *
 * Immutable record. The unique constraint is (userId, postId) --
 * a user can like a post at most once. EngagementRepository enforces this.
 *
 * Used by:
 *   - EngagementService: creates/removes likes, updates Post.likeCount
 *   - AlgorithmicRankingStrategy: likeCount contributes to engagement score
 */
public record Like(
    String userId,
    String postId,
    Instant timestamp
) {
    public Like {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required");
        }
        if (postId == null || postId.isBlank()) {
            throw new IllegalArgumentException("postId required");
        }
    }
}
```

```java
/**
 * A follow relationship between two users.
 *
 * Immutable record. The unique constraint is (followerId, followeeId) --
 * you can follow a user at most once.
 *
 * Used by:
 *   - SocialGraphService: manages follow/unfollow, builds follower lists
 *   - FanoutService: gets followers to distribute posts to
 *   - AlgorithmicRankingStrategy: follow recency can boost affinity
 */
public record Follow(
    String followerId,
    String followeeId,
    Instant timestamp
) {
    public Follow {
        if (followerId == null || followerId.isBlank()) {
            throw new IllegalArgumentException("followerId required");
        }
        if (followeeId == null || followeeId.isBlank()) {
            throw new IllegalArgumentException("followeeId required");
        }
        if (followerId.equals(followeeId)) {
            throw new IllegalArgumentException("a user cannot follow themselves");
        }
    }
}
```

---

### 4.6 FeedCursor (Cursor-based Pagination)

> **Cursor-based pagination** is essential for infinite scroll feeds. Offset-based pagination breaks when new posts are inserted (user sees duplicates or misses posts). Cursor-based pagination uses the last seen postId/timestamp as an anchor -- always stable.

#### Anti-Pattern: Offset-based Pagination

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: Offset-based Feed Pagination             │
     │                                                                  │
     │   // Page 1: SELECT * FROM timeline ORDER BY ts DESC            │
     │   //         LIMIT 20 OFFSET 0                                  │
     │   //   → returns posts [100, 99, 98, ..., 81]                   │
     │   //                                                             │
     │   // User scrolls down. Meanwhile, 3 new posts are inserted.    │
     │   //                                                             │
     │   // Page 2: SELECT * FROM timeline ORDER BY ts DESC            │
     │   //         LIMIT 20 OFFSET 20                                 │
     │   //   → returns posts [81, 80, 79, ..., 62]  ← DUPLICATE!     │
     │   //                                                             │
     │   // Post 81 appears on BOTH pages because the offset shifted.  │
     │                                                                  │
     │   public List<Post> getFeed(String userId, int page, int size) {│
     │       int offset = page * size;     // <-- UNSTABLE ANCHOR      │
     │       return timeline.subList(offset, offset + size);           │
     │   }                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. New posts shift all offsets → duplicates on next page    │
     │     2. Deleted posts shift offsets → user misses posts          │
     │     3. Performance: OFFSET 10000 still scans 10000 rows        │
     │     4. No stable anchor: page 2 today != page 2 tomorrow       │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Cursor-based Pagination

```java
/**
 * Cursor for paginating through a user's feed.
 *
 * Instead of page numbers, we use the last seen postId and timestamp
 * as a stable anchor. "Give me 20 posts OLDER than this timestamp."
 *
 * Cursor-based pagination is:
 *   - Stable: new posts don't shift the cursor
 *   - Efficient: seeks directly to the anchor point (O(log n) in TreeMap)
 *   - Infinite-scroll friendly: client just passes back the cursor
 *
 * Usage:
 *   // First page: no cursor
 *   List<FeedItem> page1 = feedService.getFeed(userId, null);
 *
 *   // Next page: pass cursor from last item of page1
 *   FeedCursor cursor = FeedCursor.from(page1.getLast());
 *   List<FeedItem> page2 = feedService.getFeed(userId, cursor);
 *
 * Used by:
 *   - FeedService: passes cursor to TimelineService for page retrieval
 *   - TimelineService: uses cursor.lastTimestamp for TreeMap.headMap()
 *   - FeedController: receives cursor from client, passes to FeedService
 */
public class FeedCursor {

    private final String lastPostId;
    private final Instant lastTimestamp;
    private final int pageSize;

    public FeedCursor(String lastPostId, Instant lastTimestamp, int pageSize) {
        if (lastPostId == null || lastPostId.isBlank()) {
            throw new IllegalArgumentException("lastPostId required for cursor");
        }
        if (pageSize <= 0 || pageSize > 100) {
            throw new IllegalArgumentException(
                "pageSize must be 1-100, got: " + pageSize);
        }
        this.lastPostId = lastPostId;
        this.lastTimestamp = lastTimestamp;
        this.pageSize = pageSize;
    }

    /** Create a cursor from the last FeedItem on the current page. */
    public static FeedCursor from(FeedItem lastItem, int pageSize) {
        return new FeedCursor(
            lastItem.getPost().getPostId(),
            lastItem.getPost().getTimestamp(),
            pageSize
        );
    }

    // --- Getters ---
    public String getLastPostId() { return lastPostId; }
    public Instant getLastTimestamp() { return lastTimestamp; }
    public int getPageSize() { return pageSize; }

    @Override
    public String toString() {
        return String.format("FeedCursor[lastPost=%s, lastTs=%s, pageSize=%d]",
            lastPostId, lastTimestamp, pageSize);
    }
}
```

---

## 5. Interface Contracts

### 5.1 FanoutStrategy

```java
/**
 * Strategy for distributing a newly created post to followers.
 *
 * This is THE core interface of the news feed system.
 * The choice of fan-out strategy determines:
 *   - Write latency (how fast createPost() returns)
 *   - Read latency (how fast getFeed() returns)
 *   - Storage usage (how much timeline cache is needed)
 *   - Celebrity handling (thundering herd avoidance)
 *
 * Three implementations:
 *   FanoutOnWriteStrategy  → push to all followers at write time
 *   FanoutOnReadStrategy   → no-op at write, pull at read time
 *   HybridFanoutStrategy   → write for normal, read for celebrities
 *
 * CALL CHAIN:
 *   PostService.createPost(authorId, content, ...)
 *     → FanoutService.distribute(post, author, followers)
 *       → FanoutStrategy.distribute(post, author, followers)
 *         → (implementation-specific distribution logic)
 */
public interface FanoutStrategy {

    /**
     * Distribute a post to the author's followers.
     *
     * @param post      the newly created post
     * @param author    the user who created the post
     * @param followers the list of users following the author
     */
    void distribute(Post post, User author, List<User> followers);

    /**
     * Should the feed reader pull this author's posts at read time?
     *
     * Returns true for authors whose posts were NOT pushed to timelines
     * (i.e., fan-out on read was used for this author).
     *
     * CALL CHAIN:
     *   FeedService.getFeed(userId, cursor)
     *     → for each followed user:
     *       → FanoutStrategy.shouldPullOnRead(followedUser)
     *         → true  → pull posts from PostRepository
     *         → false → posts already in TimelineCache
     */
    boolean shouldPullOnRead(User author);

    /** Strategy name for logging and display. */
    String getStrategyName();
}
```

---

### 5.2 RankingStrategy

```java
/**
 * Strategy for ranking feed items before presenting to the user.
 *
 * The ranking strategy determines the ORDER in which posts appear
 * in a user's feed. Two common approaches:
 *   - Chronological: newest first (simple, transparent to users)
 *   - Algorithmic: scored by affinity, recency, engagement, type
 *
 * CALL CHAIN:
 *   FeedService.getFeed(userId, cursor)
 *     → RankingService.rank(feedItems, viewer)
 *       → RankingStrategy.rank(feedItems, viewer)
 *         → scored and sorted List<FeedItem>
 *
 * The viewer (User) is passed in because ranking is PERSONALIZED:
 *   - Affinity: how often does THIS viewer engage with the author?
 *   - Same post has different scores for different viewers.
 */
public interface RankingStrategy {

    /**
     * Rank a list of feed items for a specific viewer.
     *
     * @param items  unranked feed items
     * @param viewer the user viewing the feed
     * @return ranked feed items (highest score first), with score and position set
     */
    List<FeedItem> rank(List<FeedItem> items, User viewer);

    /** Strategy name for logging and display. */
    String getStrategyName();
}
```

---

### 5.3 TimelineCache

```java
/**
 * Cache for per-user timelines.
 *
 * In production, this would be Redis Sorted Sets:
 *   ZADD user:123:timeline <timestamp> <postId>
 *   ZREVRANGEBYSCORE user:123:timeline +inf <cursor_ts> LIMIT 0 20
 *
 * For our in-memory implementation, we use TreeMap<Long, String> per user:
 *   - Key: timestamp (epoch millis) for natural ordering
 *   - Value: postId (lightweight reference, not the full Post)
 *   - TreeMap gives O(log n) insert, O(log n) range queries
 *
 * The cache is BOUNDED: max 1000 entries per user.
 * When a user's timeline exceeds 1000, the oldest entry is evicted.
 * This matches production behavior: Redis sorted sets are capped.
 */
public interface TimelineCache {

    /** Add a post reference to a user's timeline. */
    void addToTimeline(String userId, String postId, long timestamp);

    /**
     * Get a page of postIds from a user's timeline.
     *
     * @param userId the user whose timeline to read
     * @param cursor null for first page, otherwise the pagination cursor
     * @return list of postIds, newest first, limited to cursor.pageSize
     */
    List<String> getTimeline(String userId, FeedCursor cursor);

    /** Remove a specific post from a user's timeline. */
    void removeFromTimeline(String userId, String postId);

    /** Get the number of entries in a user's timeline. */
    int getTimelineSize(String userId);

    /** Clear all entries in a user's timeline. */
    void clearTimeline(String userId);
}
```

---

### 5.4 Repository Interfaces

```java
/**
 * Repository for Post entities.
 *
 * Used by:
 *   - PostService: save and retrieve posts
 *   - FeedService: look up full Post objects from postIds in timeline
 *   - FanoutOnReadStrategy: pull recent posts by author at read time
 */
public interface PostRepository {
    void save(Post post);
    Optional<Post> findById(String postId);
    List<Post> findByAuthorId(String authorId);
    List<Post> findByAuthorIdSince(String authorId, Instant since);
    List<Post> findAll();
}
```

```java
/**
 * Repository for User entities.
 */
public interface UserRepository {
    void save(User user);
    Optional<User> findById(String userId);
    List<User> findAll();
}
```

```java
/**
 * Repository for Follow relationships.
 *
 * Two key queries:
 *   - getFollowers(userId): who follows THIS user → needed for fan-out
 *   - getFollowing(userId): who does THIS user follow → needed for feed generation
 */
public interface FollowRepository {
    void save(Follow follow);
    void delete(String followerId, String followeeId);
    boolean exists(String followerId, String followeeId);
    List<Follow> getFollowers(String userId);   // people who follow userId
    List<Follow> getFollowing(String userId);   // people userId follows
}
```

```java
/**
 * Repository for engagement data (likes, comments).
 *
 * Enforces uniqueness: a user can like a post at most once.
 */
public interface EngagementRepository {
    void saveLike(Like like);
    void removeLike(String userId, String postId);
    boolean hasLiked(String userId, String postId);
    int getLikeCount(String postId);

    void saveComment(Comment comment);
    List<Comment> getComments(String postId);
    int getCommentCount(String postId);

    /** Interaction count between two users (for affinity calculation). */
    int getInteractionCount(String userId, String targetUserId);
}
```

---

## 6. Strategy Implementations

### 6.1 FanoutOnWriteStrategy (Push Model)

```java
/**
 * Fan-out on write: when a user publishes a post, PUSH it to every
 * follower's timeline cache immediately.
 *
 * TRADE-OFFS:
 *   + Fast reads: timeline cache is pre-populated
 *   + Simple read path: just read from cache, rank, return
 *   - Slow writes: proportional to follower count
 *   - Storage: N copies of postId (one per follower)
 *   - Celebrity problem: 10M followers → 10M cache writes
 *
 * WHEN TO USE:
 *   - Users with small follower counts (< 10K)
 *   - Systems that prioritize read latency over write latency
 *   - Twitter pre-2012 (before switching to hybrid)
 *
 * CALL CHAIN:
 *   PostService.createPost(...)
 *     → FanoutService.distribute(post, author, followers)
 *       → FanoutOnWriteStrategy.distribute(post, author, followers)
 *         → for each follower:
 *           → timelineCache.addToTimeline(follower.userId, postId, timestamp)
 *
 * Complexity: O(F) where F = number of followers
 */
public class FanoutOnWriteStrategy implements FanoutStrategy {

    private final TimelineCache timelineCache;

    public FanoutOnWriteStrategy(TimelineCache timelineCache) {
        this.timelineCache = timelineCache;
    }

    @Override
    public void distribute(Post post, User author, List<User> followers) {
        long timestamp = post.getTimestamp().toEpochMilli();

        System.out.printf("  [FanoutOnWrite] Pushing post %s to %d follower timelines%n",
            post.getPostId(), followers.size());

        // Push the postId to every follower's timeline cache.
        // In production, this would be done asynchronously via a message queue.
        for (User follower : followers) {
            timelineCache.addToTimeline(
                follower.getUserId(),
                post.getPostId(),
                timestamp
            );
        }

        System.out.printf("  [FanoutOnWrite] Done. %d timeline caches updated.%n",
            followers.size());
    }

    /**
     * Fan-out on write means posts are ALREADY in the timeline cache.
     * No need to pull at read time.
     */
    @Override
    public boolean shouldPullOnRead(User author) {
        return false;  // already pushed
    }

    @Override
    public String getStrategyName() {
        return "FanoutOnWrite";
    }
}
```

---

### 6.2 FanoutOnReadStrategy (Pull Model)

```java
/**
 * Fan-out on read: when a user publishes a post, do NOTHING to follower
 * timelines. When a follower requests their feed, PULL the author's
 * recent posts on the fly.
 *
 * TRADE-OFFS:
 *   + Fast writes: createPost() is O(1) -- just save the post
 *   + No wasted storage: posts only fetched when actually requested
 *   + No celebrity problem: same cost regardless of follower count
 *   - Slow reads: must query every followed user's post list
 *   - N+1 problem: follow 500 users → 500 queries per feed load
 *
 * WHEN TO USE:
 *   - Celebrity accounts with millions of followers
 *   - Systems that prioritize write latency over read latency
 *   - Users who rarely log in (no point pre-populating their timeline)
 *
 * CALL CHAIN (at write time):
 *   PostService.createPost(...)
 *     → FanoutOnReadStrategy.distribute(post, author, followers)
 *       → no-op (nothing to do)
 *
 * CALL CHAIN (at read time):
 *   FeedService.getFeed(userId, cursor)
 *     → for each followed celebrity:
 *       → PostRepository.findByAuthorIdSince(authorId, since)
 *       → wrap each Post in FeedItem(post, Source.PULLED)
 */
public class FanoutOnReadStrategy implements FanoutStrategy {

    @Override
    public void distribute(Post post, User author, List<User> followers) {
        // Intentional no-op: posts are pulled at read time, not pushed at write time.
        System.out.printf("  [FanoutOnRead] Post %s stored. Will be pulled at read time.%n",
            post.getPostId());
    }

    /**
     * Fan-out on read: posts are NOT in timeline cache.
     * Must pull from author's post list at read time.
     */
    @Override
    public boolean shouldPullOnRead(User author) {
        return true;  // always pull
    }

    @Override
    public String getStrategyName() {
        return "FanoutOnRead";
    }
}
```

---

### 6.3 HybridFanoutStrategy (The Interview Answer)

> **This is what interviewers want to hear.** Neither pure push nor pure pull is optimal. The hybrid approach uses fan-out on write for normal users (fast reads, acceptable write cost) and fan-out on read for celebrities (avoids thundering herd). This is what Facebook, Twitter, and LinkedIn all use in production.

#### Anti-Pattern: One-size-fits-all Fan-out

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   ANTI-PATTERN: Always Fan-out on Write (No Celebrity Handling)  │
     │                                                                  │
     │   public void distribute(Post post, List<User> followers) {     │
     │       // Same logic for ALL users regardless of follower count   │
     │       for (User follower : followers) {                         │
     │           timelineCache.addToTimeline(                          │
     │               follower.getUserId(), post.getPostId(), ts);      │
     │       }                                                         │
     │   }                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. Celebrity with 10M followers → 10M synchronous writes    │
     │     2. createPost() takes MINUTES for celebrities               │
     │     3. 80% of followers may never log in → wasted writes        │
     │     4. Timeline cache memory explodes (10M * N posts/day)       │
     │     5. Thundering herd: celebrity posts spike write traffic      │
     │        by 1000x, overwhelming the cache servers                 │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Hybrid Fan-out (Composite Pattern)

```java
/**
 * Hybrid fan-out: delegates to FanoutOnWrite for normal users and
 * FanoutOnRead for celebrities.
 *
 * This is the COMPOSITE PATTERN applied to fan-out strategies:
 *   - HybridFanoutStrategy composes FanoutOnWriteStrategy and FanoutOnReadStrategy
 *   - It decides which to use based on the author's celebrity status
 *
 * Decision logic:
 *   author.isCelebrity() == true  → FanoutOnReadStrategy  (skip push)
 *   author.isCelebrity() == false → FanoutOnWriteStrategy  (push to all)
 *
 * At read time, FeedService checks shouldPullOnRead() for each followed user:
 *   - Normal authors: false (already in timeline cache)
 *   - Celebrity authors: true (must pull their recent posts)
 *
 * CALL CHAIN:
 *   PostService.createPost(...)
 *     → FanoutService.distribute(post, author, followers)
 *       → HybridFanoutStrategy.distribute(post, author, followers)
 *         → author.isCelebrity()?
 *           → YES: readStrategy.distribute(post, author, followers)  [no-op]
 *           → NO:  writeStrategy.distribute(post, author, followers) [push]
 *
 * WHY HYBRID WINS:
 *   - Normal user (500 followers): push at write time → 500 cache writes
 *     → fast reads for those 500 followers
 *   - Celebrity (10M followers): skip push → 0 cache writes
 *     → pull at read time for the ~1% of followers who are online
 *     → saves 99% of write traffic
 */
public class HybridFanoutStrategy implements FanoutStrategy {

    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy readStrategy;

    public HybridFanoutStrategy(FanoutOnWriteStrategy writeStrategy,
                                 FanoutOnReadStrategy readStrategy) {
        this.writeStrategy = writeStrategy;
        this.readStrategy = readStrategy;
    }

    @Override
    public void distribute(Post post, User author, List<User> followers) {
        if (author.isCelebrity()) {
            System.out.printf("  [HybridFanout] Author %s is celebrity (%d followers). "
                + "Using fan-out on READ.%n", author.getUserId(), author.getFollowerCount());
            readStrategy.distribute(post, author, followers);
        } else {
            System.out.printf("  [HybridFanout] Author %s is normal user (%d followers). "
                + "Using fan-out on WRITE.%n", author.getUserId(), author.getFollowerCount());
            writeStrategy.distribute(post, author, followers);
        }
    }

    /**
     * For hybrid: only pull posts from celebrities.
     * Normal users' posts are already in the timeline cache.
     */
    @Override
    public boolean shouldPullOnRead(User author) {
        return author.isCelebrity();
    }

    @Override
    public String getStrategyName() {
        return "HybridFanout";
    }
}
```

---

### 6.4 ChronologicalRankingStrategy

```java
/**
 * Simplest ranking: sort by timestamp descending (newest first).
 *
 * This is what Twitter used originally ("reverse chronological feed").
 * It is transparent and predictable, but misses opportunities to show
 * high-engagement content that the user might have missed.
 *
 * Score = post timestamp in epoch millis (higher = newer = shown first).
 *
 * CALL CHAIN:
 *   FeedService.getFeed(userId, cursor)
 *     → RankingService.rank(feedItems, viewer)
 *       → ChronologicalRankingStrategy.rank(feedItems, viewer)
 *         → sort by timestamp desc, assign positions
 */
public class ChronologicalRankingStrategy implements RankingStrategy {

    @Override
    public List<FeedItem> rank(List<FeedItem> items, User viewer) {
        // Score = timestamp in epoch millis
        for (FeedItem item : items) {
            item.setScore(item.getPost().getTimestamp().toEpochMilli());
        }

        // Sort by score descending (newest first)
        List<FeedItem> ranked = items.stream()
            .sorted(Comparator.comparingDouble(FeedItem::getScore).reversed())
            .toList();

        // Assign positions (1-based)
        List<FeedItem> result = new ArrayList<>(ranked);
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setPosition(i + 1);
        }

        return result;
    }

    @Override
    public String getStrategyName() {
        return "Chronological";
    }
}
```

---

### 6.5 AlgorithmicRankingStrategy

> **This is the Facebook/LinkedIn-style ranking algorithm.** Each post is scored using four signals: affinity (how close the viewer is to the author), recency (how old the post is), engagement (how many likes/comments/shares), and content type weight (video > image > text). The weights are configurable.

```java
/**
 * Algorithmic ranking: scores each post using multiple signals.
 *
 * FORMULA:
 *   score = (W_a * affinity) + (W_r * recency) + (W_e * engagement) + (W_t * typeWeight)
 *
 * WHERE:
 *   affinity    = interactions between viewer and author / MAX_INTERACTIONS
 *                 (how often viewer likes/comments on author's posts)
 *   recency     = 1.0 / (1.0 + hoursAge * DECAY_FACTOR)
 *                 (exponential decay: 1-hour-old post scores ~0.5)
 *   engagement  = (likes + 2*comments + 3*shares) / ENGAGEMENT_NORM
 *                 (comments weighted more: they indicate deeper engagement)
 *   typeWeight  = ContentType.getBoostFactor()
 *                 (VIDEO=1.3, IMAGE=1.2, POLL=1.1, LINK=1.0, TEXT=0.9)
 *
 * Default weights: W_a=0.3, W_r=0.3, W_e=0.2, W_t=0.2
 *
 * Why these weights?
 *   - Affinity and recency equally important (0.3 each):
 *     "Show me RECENT posts from people I CARE about"
 *   - Engagement matters but less (0.2):
 *     prevents pure popularity contest, avoids filter bubble
 *   - Content type is a nudge, not dominant (0.2):
 *     platform wants to boost video but not overwhelm
 *
 * CALL CHAIN:
 *   FeedService.getFeed(userId, cursor)
 *     → RankingService.rank(feedItems, viewer)
 *       → AlgorithmicRankingStrategy.rank(feedItems, viewer)
 *         → for each item:
 *           → computeAffinity(viewer, author)
 *           → computeRecency(timestamp)
 *           → computeEngagement(likes, comments, shares)
 *           → getTypeWeight(contentType)
 *           → score = weighted sum
 *         → sort by score desc, assign positions
 */
public class AlgorithmicRankingStrategy implements RankingStrategy {

    private static final double DEFAULT_AFFINITY_WEIGHT = 0.3;
    private static final double DEFAULT_RECENCY_WEIGHT = 0.3;
    private static final double DEFAULT_ENGAGEMENT_WEIGHT = 0.2;
    private static final double DEFAULT_TYPE_WEIGHT = 0.2;

    private static final double DECAY_FACTOR = 0.1;          // recency decay rate
    private static final double ENGAGEMENT_NORM = 100.0;      // normalization factor
    private static final int MAX_INTERACTIONS = 50;            // affinity normalization

    private final double affinityWeight;
    private final double recencyWeight;
    private final double engagementWeight;
    private final double typeWeight;
    private final EngagementRepository engagementRepository;

    public AlgorithmicRankingStrategy(EngagementRepository engagementRepository) {
        this(engagementRepository,
             DEFAULT_AFFINITY_WEIGHT, DEFAULT_RECENCY_WEIGHT,
             DEFAULT_ENGAGEMENT_WEIGHT, DEFAULT_TYPE_WEIGHT);
    }

    public AlgorithmicRankingStrategy(EngagementRepository engagementRepository,
                                       double affinityWeight, double recencyWeight,
                                       double engagementWeight, double typeWeight) {
        this.engagementRepository = engagementRepository;
        this.affinityWeight = affinityWeight;
        this.recencyWeight = recencyWeight;
        this.engagementWeight = engagementWeight;
        this.typeWeight = typeWeight;
    }

    @Override
    public List<FeedItem> rank(List<FeedItem> items, User viewer) {
        // Score each item
        for (FeedItem item : items) {
            double affinity = computeAffinity(viewer, item.getPost().getAuthorId());
            double recency = computeRecency(item.getPost().getTimestamp());
            double engagement = computeEngagement(item.getPost());
            double contentTypeBoost = item.getPost().getContentType().getBoostFactor();

            double score = (affinityWeight * affinity)
                         + (recencyWeight * recency)
                         + (engagementWeight * engagement)
                         + (typeWeight * contentTypeBoost);

            item.setScore(score);
        }

        // Sort by score descending
        List<FeedItem> ranked = new ArrayList<>(items);
        ranked.sort(Comparator.comparingDouble(FeedItem::getScore).reversed());

        // Assign positions (1-based)
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setPosition(i + 1);
        }

        return ranked;
    }

    /**
     * Affinity: how often does the viewer interact with this author?
     *
     * Higher affinity → viewer has liked/commented on author's posts recently.
     * Normalized to [0, 1] by dividing by MAX_INTERACTIONS.
     */
    private double computeAffinity(User viewer, String authorId) {
        int interactions = engagementRepository.getInteractionCount(
            viewer.getUserId(), authorId);
        return Math.min(1.0, (double) interactions / MAX_INTERACTIONS);
    }

    /**
     * Recency: how old is the post?
     *
     * Uses exponential decay: score = 1 / (1 + hours * decayFactor)
     *   - 0 hours old → score = 1.0
     *   - 1 hour old  → score ≈ 0.91
     *   - 10 hours old → score ≈ 0.50
     *   - 24 hours old → score ≈ 0.29
     *   - 72 hours old → score ≈ 0.12
     */
    private double computeRecency(Instant postTimestamp) {
        long ageMillis = Instant.now().toEpochMilli() - postTimestamp.toEpochMilli();
        double ageHours = ageMillis / (1000.0 * 60 * 60);
        return 1.0 / (1.0 + ageHours * DECAY_FACTOR);
    }

    /**
     * Engagement: how popular is this post?
     *
     * Weighted formula: likes + 2*comments + 3*shares
     * Rationale: comments > likes (more effort), shares > comments (amplification).
     * Normalized to [0, 1] by dividing by ENGAGEMENT_NORM.
     */
    private double computeEngagement(Post post) {
        double raw = post.getLikeCount()
                   + 2.0 * post.getCommentCount()
                   + 3.0 * post.getShareCount();
        return Math.min(1.0, raw / ENGAGEMENT_NORM);
    }

    @Override
    public String getStrategyName() {
        return "Algorithmic";
    }
}
```

---

## 7. Service Layer Design

### 7.1 FeedService (Facade)

> **The Facade.** This is the single entry point for feed generation. It orchestrates TimelineService (read cached postIds), PostRepository (hydrate postIds to full Posts), SocialGraphService (find followed celebrities for pull), RankingService (apply ranking strategy). The controller never talks to individual services directly.

```
    FEED GENERATION PIPELINE:

    Step 1: READ from timeline cache
       → TimelineService.getTimeline(userId, cursor)
       → returns List<postId> from pre-populated cache
       → wrap each as FeedItem(post, Source.TIMELINE)

    Step 2: PULL celebrity posts (hybrid fan-out)
       → SocialGraphService.getFollowing(userId)
       → for each followed user where shouldPullOnRead(user) == true:
         → PostRepository.findByAuthorIdSince(authorId, sinceTimestamp)
         → wrap each as FeedItem(post, Source.PULLED)

    Step 3: MERGE timeline + pulled posts
       → deduplicate by postId (avoid showing same post twice)
       → combine into single List<FeedItem>

    Step 4: RANK
       → RankingService.rank(mergedItems, viewer)
       → returns scored, sorted List<FeedItem> with positions assigned

    Step 5: PAGINATE
       → return first pageSize items
       → client uses last item to build FeedCursor for next page
```

```java
/**
 * Facade for news feed generation.
 *
 * Orchestrates the full feed pipeline:
 *   1. Read timeline cache (pre-pushed posts from fan-out on write)
 *   2. Pull celebrity posts (fan-out on read for high-follower authors)
 *   3. Merge and deduplicate
 *   4. Rank using the configured ranking strategy
 *   5. Paginate and return
 *
 * CALL CHAIN:
 *   FeedController.getFeed(userId, cursor)
 *     → FeedService.getFeed(userId, cursor)
 *       → TimelineService.getTimeline(userId, cursor)
 *       → SocialGraphService.getFollowing(userId)
 *       → for each celebrity: PostRepository.findByAuthorIdSince(...)
 *       → RankingService.rank(mergedItems, viewer)
 *       → return ranked List<FeedItem>
 */
public class FeedService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int CELEBRITY_PULL_HOURS = 72;  // pull last 72 hours

    private final TimelineService timelineService;
    private final PostService postService;
    private final SocialGraphService socialGraphService;
    private final RankingService rankingService;
    private final FanoutStrategy fanoutStrategy;
    private final UserRepository userRepository;
    private final PostRepository postRepository;

    public FeedService(TimelineService timelineService,
                       PostService postService,
                       SocialGraphService socialGraphService,
                       RankingService rankingService,
                       FanoutStrategy fanoutStrategy,
                       UserRepository userRepository,
                       PostRepository postRepository) {
        this.timelineService = timelineService;
        this.postService = postService;
        this.socialGraphService = socialGraphService;
        this.rankingService = rankingService;
        this.fanoutStrategy = fanoutStrategy;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
    }

    /**
     * Generate a ranked feed for a user.
     *
     * @param userId the user requesting their feed
     * @param cursor null for first page, FeedCursor for subsequent pages
     * @return ranked list of FeedItems (pageSize items max)
     */
    public List<FeedItem> getFeed(String userId, FeedCursor cursor) {
        System.out.println("\n=== Generating feed for user: " + userId + " ===");

        User viewer = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        int pageSize = (cursor != null) ? cursor.getPageSize() : DEFAULT_PAGE_SIZE;

        // --- Step 1: Read from timeline cache (fan-out on write posts) ---
        List<String> cachedPostIds = timelineService.getTimeline(userId, cursor);
        System.out.printf("  [Feed] Step 1: %d posts from timeline cache%n",
            cachedPostIds.size());

        List<FeedItem> feedItems = new ArrayList<>();

        // Hydrate postIds to full Post objects, wrap as FeedItem(Source.TIMELINE)
        for (String postId : cachedPostIds) {
            postRepository.findById(postId).ifPresent(post ->
                feedItems.add(new FeedItem(post, FeedItem.Source.TIMELINE))
            );
        }

        // --- Step 2: Pull celebrity posts (fan-out on read) ---
        List<Follow> following = socialGraphService.getFollowing(userId);
        Instant since = Instant.now().minus(CELEBRITY_PULL_HOURS, ChronoUnit.HOURS);

        int pulledCount = 0;
        Set<String> seenPostIds = new HashSet<>(cachedPostIds);

        for (Follow follow : following) {
            User followedUser = userRepository.findById(follow.followeeId()).orElse(null);
            if (followedUser == null) continue;

            // Only pull posts for authors whose posts were NOT pushed at write time
            if (fanoutStrategy.shouldPullOnRead(followedUser)) {
                List<Post> recentPosts = postRepository.findByAuthorIdSince(
                    followedUser.getUserId(), since);

                for (Post post : recentPosts) {
                    // Deduplicate: skip if already in timeline cache
                    if (seenPostIds.add(post.getPostId())) {
                        feedItems.add(new FeedItem(post, FeedItem.Source.PULLED));
                        pulledCount++;
                    }
                }
            }
        }

        System.out.printf("  [Feed] Step 2: %d posts pulled from celebrities%n", pulledCount);

        // --- Step 3: Merge is implicit (feedItems already contains both) ---
        System.out.printf("  [Feed] Step 3: %d total items after merge%n", feedItems.size());

        // --- Step 4: Rank ---
        List<FeedItem> ranked = rankingService.rank(feedItems, viewer);
        System.out.printf("  [Feed] Step 4: Ranked %d items with %s%n",
            ranked.size(), rankingService.getStrategyName());

        // --- Step 5: Paginate ---
        List<FeedItem> page = ranked.stream()
            .limit(pageSize)
            .toList();
        System.out.printf("  [Feed] Step 5: Returning page of %d items%n", page.size());

        return page;
    }
}
```

---

### 7.2 PostService

```java
/**
 * Manages post creation and retrieval.
 *
 * On createPost(), it:
 *   1. Validates the author exists
 *   2. Creates the Post via Builder
 *   3. Persists the Post
 *   4. Triggers fan-out via FanoutService
 *
 * CALL CHAIN:
 *   FeedController.createPost(authorId, content, type, mediaUrl)
 *     → PostService.createPost(authorId, content, type, mediaUrl)
 *       → UserRepository.findById(authorId)     // validate author
 *       → new Post.Builder(authorId, content)... // build post
 *       → PostRepository.save(post)              // persist
 *       → FanoutService.distribute(post, author) // fan-out to followers
 *       → return post
 */
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final FanoutService fanoutService;

    public PostService(PostRepository postRepository,
                       UserRepository userRepository,
                       FanoutService fanoutService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.fanoutService = fanoutService;
    }

    /**
     * Create a new post and trigger fan-out distribution.
     */
    public Post createPost(String authorId, String content,
                           ContentType contentType, String mediaUrl) {
        // Validate author exists
        User author = userRepository.findById(authorId)
            .orElseThrow(() -> new UserNotFoundException("Author not found: " + authorId));

        // Build the post
        Post.Builder builder = new Post.Builder(authorId, content)
            .contentType(contentType);
        if (mediaUrl != null) {
            builder.mediaUrl(mediaUrl);
        }
        Post post = builder.build();

        // Persist
        postRepository.save(post);
        System.out.printf("  [PostService] Created post %s by %s (%s)%n",
            post.getPostId(), authorId, contentType);

        // Trigger fan-out (async in production, sync in our demo)
        fanoutService.distribute(post, author);

        return post;
    }

    /** Simple text post shorthand. */
    public Post createTextPost(String authorId, String content) {
        return createPost(authorId, content, ContentType.TEXT, null);
    }

    public Optional<Post> getPost(String postId) {
        return postRepository.findById(postId);
    }

    public List<Post> getPostsByAuthor(String authorId) {
        return postRepository.findByAuthorId(authorId);
    }
}
```

---

### 7.3 FanoutService

```java
/**
 * Distributes posts to follower timelines using the configured FanoutStrategy.
 *
 * This service sits between PostService and FanoutStrategy, providing:
 *   1. Follower list retrieval from SocialGraphService
 *   2. Delegation to the active FanoutStrategy
 *   3. Metrics and logging
 *
 * In production, this would use a message queue (Kafka, SQS) for async
 * fan-out. For our demo, it is synchronous.
 *
 * CALL CHAIN:
 *   PostService.createPost(...)
 *     → FanoutService.distribute(post, author)
 *       → SocialGraphService.getFollowers(authorId)
 *       → FanoutStrategy.distribute(post, author, followers)
 */
public class FanoutService {

    private final FanoutStrategy fanoutStrategy;
    private final SocialGraphService socialGraphService;
    private final UserRepository userRepository;

    public FanoutService(FanoutStrategy fanoutStrategy,
                         SocialGraphService socialGraphService,
                         UserRepository userRepository) {
        this.fanoutStrategy = fanoutStrategy;
        this.socialGraphService = socialGraphService;
        this.userRepository = userRepository;
    }

    /**
     * Distribute a post to the author's followers.
     */
    public void distribute(Post post, User author) {
        System.out.printf("  [FanoutService] Distributing post %s (strategy: %s)%n",
            post.getPostId(), fanoutStrategy.getStrategyName());

        // Get all followers as User objects
        List<Follow> followerRelations = socialGraphService.getFollowers(
            author.getUserId());

        List<User> followers = followerRelations.stream()
            .map(f -> userRepository.findById(f.followerId()).orElse(null))
            .filter(u -> u != null)
            .toList();

        System.out.printf("  [FanoutService] Author %s has %d followers%n",
            author.getUserId(), followers.size());

        // Delegate to strategy
        fanoutStrategy.distribute(post, author, followers);
    }
}
```

---

### 7.4 TimelineService

```java
/**
 * Manages per-user timeline caches.
 *
 * The timeline is a sorted list of postIds, ordered by timestamp descending.
 * It is populated by FanoutOnWriteStrategy and read by FeedService.
 *
 * CALL CHAIN (write path):
 *   FanoutOnWriteStrategy.distribute(post, author, followers)
 *     → timelineCache.addToTimeline(followerId, postId, timestamp)
 *
 * CALL CHAIN (read path):
 *   FeedService.getFeed(userId, cursor)
 *     → TimelineService.getTimeline(userId, cursor)
 *       → timelineCache.getTimeline(userId, cursor)
 *       → return List<postId>
 */
public class TimelineService {

    private final TimelineCache timelineCache;

    public TimelineService(TimelineCache timelineCache) {
        this.timelineCache = timelineCache;
    }

    /**
     * Get a page of postIds from a user's timeline.
     *
     * @param userId the user whose timeline to read
     * @param cursor null for first page, FeedCursor for next pages
     * @return list of postIds, newest first
     */
    public List<String> getTimeline(String userId, FeedCursor cursor) {
        return timelineCache.getTimeline(userId, cursor);
    }

    /** Add a post to a user's timeline cache. */
    public void addToTimeline(String userId, String postId, long timestamp) {
        timelineCache.addToTimeline(userId, postId, timestamp);
    }

    /** Get the size of a user's timeline cache. */
    public int getTimelineSize(String userId) {
        return timelineCache.getTimelineSize(userId);
    }

    /** Remove a post from a user's timeline. */
    public void removeFromTimeline(String userId, String postId) {
        timelineCache.removeFromTimeline(userId, postId);
    }
}
```

---

### 7.5 RankingService

```java
/**
 * Applies the configured ranking strategy to feed items.
 *
 * Thin wrapper around RankingStrategy -- exists to allow:
 *   - Future middleware (A/B testing, logging, caching of scores)
 *   - Strategy switching at runtime
 *   - Metrics collection (ranking latency, score distribution)
 *
 * CALL CHAIN:
 *   FeedService.getFeed(userId, cursor)
 *     → RankingService.rank(feedItems, viewer)
 *       → RankingStrategy.rank(feedItems, viewer)
 *       → return ranked List<FeedItem>
 */
public class RankingService {

    private RankingStrategy rankingStrategy;

    public RankingService(RankingStrategy rankingStrategy) {
        this.rankingStrategy = rankingStrategy;
    }

    public List<FeedItem> rank(List<FeedItem> items, User viewer) {
        System.out.printf("  [RankingService] Ranking %d items with %s strategy%n",
            items.size(), rankingStrategy.getStrategyName());
        return rankingStrategy.rank(items, viewer);
    }

    /** Switch ranking strategy at runtime (for A/B testing). */
    public void setRankingStrategy(RankingStrategy strategy) {
        this.rankingStrategy = strategy;
    }

    public String getStrategyName() {
        return rankingStrategy.getStrategyName();
    }
}
```

---

### 7.6 SocialGraphService

```java
/**
 * Manages follow/unfollow relationships and social graph queries.
 *
 * Key responsibilities:
 *   1. Follow/unfollow operations with follower count updates
 *   2. Celebrity detection (followerCount >= 10K)
 *   3. Follower list for fan-out distribution
 *   4. Following list for feed generation (which users to pull from)
 *
 * CALL CHAIN (follow):
 *   FeedController.follow(followerId, followeeId)
 *     → SocialGraphService.follow(followerId, followeeId)
 *       → FollowRepository.save(follow)
 *       → followee.incrementFollowerCount()
 *
 * CALL CHAIN (used by fan-out):
 *   FanoutService.distribute(post, author)
 *     → SocialGraphService.getFollowers(authorId)
 *       → FollowRepository.getFollowers(authorId)
 */
public class SocialGraphService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    public SocialGraphService(FollowRepository followRepository,
                              UserRepository userRepository) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
    }

    /**
     * Follow a user.
     *
     * @throws UserNotFoundException if either user does not exist
     * @throws FeedException if already following
     */
    public Follow follow(String followerId, String followeeId) {
        User follower = userRepository.findById(followerId)
            .orElseThrow(() -> new UserNotFoundException("Follower not found: " + followerId));
        User followee = userRepository.findById(followeeId)
            .orElseThrow(() -> new UserNotFoundException("Followee not found: " + followeeId));

        if (followRepository.exists(followerId, followeeId)) {
            throw new FeedException(followerId + " already follows " + followeeId);
        }

        Follow follow = new Follow(followerId, followeeId, Instant.now());
        followRepository.save(follow);
        followee.incrementFollowerCount();

        System.out.printf("  [SocialGraph] %s now follows %s (followerCount: %d)%n",
            followerId, followeeId, followee.getFollowerCount());

        return follow;
    }

    /**
     * Unfollow a user.
     */
    public void unfollow(String followerId, String followeeId) {
        User followee = userRepository.findById(followeeId)
            .orElseThrow(() -> new UserNotFoundException("Followee not found: " + followeeId));

        if (!followRepository.exists(followerId, followeeId)) {
            throw new FeedException(followerId + " does not follow " + followeeId);
        }

        followRepository.delete(followerId, followeeId);
        followee.decrementFollowerCount();

        System.out.printf("  [SocialGraph] %s unfollowed %s%n", followerId, followeeId);
    }

    /** Get all followers of a user (for fan-out distribution). */
    public List<Follow> getFollowers(String userId) {
        return followRepository.getFollowers(userId);
    }

    /** Get all users that a user follows (for feed generation). */
    public List<Follow> getFollowing(String userId) {
        return followRepository.getFollowing(userId);
    }

    /** Check if a user is a celebrity. */
    public boolean isCelebrity(String userId) {
        return userRepository.findById(userId)
            .map(User::isCelebrity)
            .orElse(false);
    }
}
```

---

### 7.7 EngagementService

```java
/**
 * Manages likes, comments, and share interactions.
 *
 * Updates both the engagement repository (for persistence/queries)
 * and the Post entity (for denormalized counters used by ranking).
 *
 * CALL CHAIN (like):
 *   FeedController.likePost(userId, postId)
 *     → EngagementService.likePost(userId, postId)
 *       → EngagementRepository.hasLiked(userId, postId)  // idempotency check
 *       → EngagementRepository.saveLike(like)
 *       → Post.incrementLikes()
 *
 * CALL CHAIN (comment):
 *   FeedController.commentOnPost(userId, postId, content)
 *     → EngagementService.addComment(userId, postId, content)
 *       → EngagementRepository.saveComment(comment)
 *       → Post.incrementComments()
 *       → NotificationService.notifyComment(postAuthor, commenter)
 */
public class EngagementService {

    private final EngagementRepository engagementRepository;
    private final PostRepository postRepository;
    private final NotificationService notificationService;

    public EngagementService(EngagementRepository engagementRepository,
                             PostRepository postRepository,
                             NotificationService notificationService) {
        this.engagementRepository = engagementRepository;
        this.postRepository = postRepository;
        this.notificationService = notificationService;
    }

    /**
     * Like a post. Idempotent: liking twice has no effect.
     */
    public void likePost(String userId, String postId) {
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new PostNotFoundException("Post not found: " + postId));

        if (engagementRepository.hasLiked(userId, postId)) {
            System.out.printf("  [Engagement] %s already liked post %s (no-op)%n",
                userId, postId);
            return;
        }

        Like like = new Like(userId, postId, Instant.now());
        engagementRepository.saveLike(like);
        post.incrementLikes();

        System.out.printf("  [Engagement] %s liked post %s (total: %d likes)%n",
            userId, postId, post.getLikeCount());

        // Notify post author
        notificationService.notifyLike(post.getAuthorId(), userId, postId);
    }

    /**
     * Unlike a post.
     */
    public void unlikePost(String userId, String postId) {
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new PostNotFoundException("Post not found: " + postId));

        if (!engagementRepository.hasLiked(userId, postId)) {
            return; // not liked, nothing to do
        }

        engagementRepository.removeLike(userId, postId);
        post.decrementLikes();

        System.out.printf("  [Engagement] %s unliked post %s (total: %d likes)%n",
            userId, postId, post.getLikeCount());
    }

    /**
     * Add a comment to a post.
     */
    public Comment addComment(String userId, String postId, String content) {
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new PostNotFoundException("Post not found: " + postId));

        Comment comment = new Comment(
            UUID.randomUUID().toString(), postId, userId, content, Instant.now());
        engagementRepository.saveComment(comment);
        post.incrementComments();

        System.out.printf("  [Engagement] %s commented on post %s (total: %d comments)%n",
            userId, postId, post.getCommentCount());

        // Notify post author
        notificationService.notifyComment(post.getAuthorId(), userId, postId);

        return comment;
    }

    /** Get all comments for a post. */
    public List<Comment> getComments(String postId) {
        return engagementRepository.getComments(postId);
    }
}
```

---

### 7.8 NotificationService

```java
/**
 * Sends push notifications (simulated via console logging).
 *
 * In production, this would integrate with Firebase Cloud Messaging (FCM),
 * Apple Push Notification Service (APNs), or a custom WebSocket push service.
 *
 * For our LLD, we simply log the notification to console.
 *
 * Notification triggers:
 *   - Like: "X liked your post"
 *   - Comment: "X commented on your post"
 *   - Follow: "X started following you"
 *   - Post from followed celebrity: "X published a new post"
 */
public class NotificationService {

    public void notifyLike(String postAuthorId, String likerId, String postId) {
        System.out.printf("  [Notification] → %s: '%s liked your post %s'%n",
            postAuthorId, likerId, postId);
    }

    public void notifyComment(String postAuthorId, String commenterId, String postId) {
        System.out.printf("  [Notification] → %s: '%s commented on your post %s'%n",
            postAuthorId, commenterId, postId);
    }

    public void notifyFollow(String followeeId, String followerId) {
        System.out.printf("  [Notification] → %s: '%s started following you'%n",
            followeeId, followerId);
    }

    public void notifyNewPost(String followerId, String authorId, String postId) {
        System.out.printf("  [Notification] → %s: '%s published a new post %s'%n",
            followerId, authorId, postId);
    }
}
```

---

## 8. Concurrency Considerations

### 8.1 Timeline Cache Concurrent Access

> **The problem.** Multiple users can create posts simultaneously, all triggering fan-out to potentially overlapping sets of followers. FanoutOnWriteStrategy calls `timelineCache.addToTimeline()` for each follower -- this must be thread-safe.

#### Anti-Pattern: Unsynchronized Timeline Cache

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   ANTI-PATTERN: Unsynchronized Timeline Cache                    │
     │                                                                  │
     │   public class NaiveTimelineCache {                             │
     │       // DANGEROUS: HashMap is NOT thread-safe                   │
     │       private Map<String, TreeMap<Long, String>> timelines       │
     │           = new HashMap<>();                                     │
     │                                                                  │
     │       public void addToTimeline(String userId, String postId,   │
     │                                 long timestamp) {               │
     │           // Thread A: gets null for "user-1"                   │
     │           TreeMap<Long, String> tl = timelines.get(userId);     │
     │           if (tl == null) {                                     │
     │               tl = new TreeMap<>();                             │
     │               timelines.put(userId, tl);  // <-- Thread B also │
     │           }                                //    puts here!     │
     │           tl.put(timestamp, postId);  // <-- TreeMap NOT safe   │
     │           // Thread A's TreeMap may be overwritten by Thread B  │
     │       }                                                         │
     │   }                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. HashMap.put not atomic: corrupted internal state          │
     │     2. TreeMap.put not thread-safe: lost entries                 │
     │     3. get-then-put race: two threads create two TreeMaps,      │
     │        one overwrites the other, posts are lost                 │
     │     4. Size check + eviction is check-then-act (not atomic)     │
     │     5. No per-user locking: global lock would serialize ALL     │
     │        timeline updates (unacceptable throughput)                │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: ConcurrentHashMap + Per-User Synchronization

```java
/**
 * Thread-safe in-memory timeline cache.
 *
 * Uses ConcurrentHashMap for the outer map (userId → timeline)
 * and synchronized blocks per-user for TreeMap mutations.
 *
 * WHY per-user locking (not a global lock)?
 *   - Fan-out pushes to many users simultaneously
 *   - A global lock would serialize ALL timeline updates
 *   - Per-user lock: updates to user-A and user-B are independent
 *   - Only concurrent updates to the SAME user's timeline block
 *
 * WHY TreeMap (not ArrayList)?
 *   - TreeMap.descendingMap() gives O(log n) range queries
 *   - Cursor-based pagination needs "entries before timestamp X"
 *   - TreeMap.headMap(timestamp) is exactly this operation
 *   - ArrayList would need O(n) linear scan + sorting
 *
 * Memory bound: MAX_TIMELINE_SIZE = 1000 per user.
 * When exceeded, oldest entries are evicted (LRU by timestamp).
 *
 * PRODUCTION EQUIVALENT: Redis ZADD with LIMIT
 *   ZADD user:123:timeline <timestamp> <postId>
 *   ZREMRANGEBYRANK user:123:timeline 0 -1001  (keep latest 1000)
 */
public class InMemoryTimelineCache implements TimelineCache {

    private static final int MAX_TIMELINE_SIZE = 1000;

    // ConcurrentHashMap: safe for concurrent get/put of different keys
    // Value is TreeMap<timestamp, postId> -- requires per-user sync
    private final ConcurrentHashMap<String, TreeMap<Long, String>> timelines
        = new ConcurrentHashMap<>();

    @Override
    public void addToTimeline(String userId, String postId, long timestamp) {
        // computeIfAbsent is atomic in ConcurrentHashMap
        TreeMap<Long, String> timeline = timelines.computeIfAbsent(
            userId, k -> new TreeMap<>());

        // Synchronized on the individual user's timeline (not globally)
        synchronized (timeline) {
            timeline.put(timestamp, postId);

            // Evict oldest if over capacity
            while (timeline.size() > MAX_TIMELINE_SIZE) {
                timeline.pollFirstEntry();  // remove oldest (smallest timestamp)
            }
        }
    }

    @Override
    public List<String> getTimeline(String userId, FeedCursor cursor) {
        TreeMap<Long, String> timeline = timelines.get(userId);
        if (timeline == null) {
            return List.of();
        }

        synchronized (timeline) {
            int pageSize = (cursor != null) ? cursor.getPageSize() : 20;

            NavigableMap<Long, String> view;
            if (cursor == null) {
                // First page: get the newest entries
                view = timeline.descendingMap();
            } else {
                // Subsequent pages: get entries BEFORE the cursor timestamp
                long cursorTs = cursor.getLastTimestamp().toEpochMilli();
                view = timeline.headMap(cursorTs, false).descendingMap();
            }

            return view.values().stream()
                .limit(pageSize)
                .toList();
        }
    }

    @Override
    public void removeFromTimeline(String userId, String postId) {
        TreeMap<Long, String> timeline = timelines.get(userId);
        if (timeline == null) return;

        synchronized (timeline) {
            timeline.values().remove(postId);
        }
    }

    @Override
    public int getTimelineSize(String userId) {
        TreeMap<Long, String> timeline = timelines.get(userId);
        return (timeline != null) ? timeline.size() : 0;
    }

    @Override
    public void clearTimeline(String userId) {
        timelines.remove(userId);
    }
}
```

---

### 8.2 Engagement Counter Atomicity

> **The problem.** Multiple users can like the same post simultaneously. Post.likeCount must not lose updates.

#### Anti-Pattern: Non-Atomic Counter Update

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   ANTI-PATTERN: Non-Atomic Like Counter                          │
     │                                                                  │
     │   // Thread A: reads likeCount = 5                              │
     │   // Thread B: reads likeCount = 5                              │
     │   // Thread A: writes likeCount = 6                             │
     │   // Thread B: writes likeCount = 6  ← LOST UPDATE!            │
     │   // Expected: 7, Actual: 6                                     │
     │                                                                  │
     │   public class Post {                                           │
     │       private int likeCount = 0;                                │
     │       public void incrementLikes() {                            │
     │           likeCount++;   // <-- NOT ATOMIC (read + write)       │
     │       }                                                         │
     │   }                                                             │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: AtomicInteger or Synchronized Access

```java
/**
 * Option A: Use AtomicInteger for lock-free concurrent updates.
 *
 * AtomicInteger uses CAS (Compare-And-Swap) under the hood:
 *   - Read current value
 *   - Compute new value
 *   - Atomically swap if current value hasn't changed
 *   - Retry if it has (spin loop)
 *
 * This is FASTER than synchronized for high-contention counters
 * because there is no lock acquisition/release overhead.
 */
public class Post {
    private final AtomicInteger likeCount = new AtomicInteger(0);
    private final AtomicInteger commentCount = new AtomicInteger(0);
    private final AtomicInteger shareCount = new AtomicInteger(0);

    public void incrementLikes() { likeCount.incrementAndGet(); }
    public void decrementLikes() { likeCount.updateAndGet(v -> Math.max(0, v - 1)); }
    public void incrementComments() { commentCount.incrementAndGet(); }
    public void incrementShares() { shareCount.incrementAndGet(); }

    public int getLikeCount() { return likeCount.get(); }
    public int getCommentCount() { return commentCount.get(); }
    public int getShareCount() { return shareCount.get(); }
}

/**
 * Option B: Synchronize in EngagementService (coarser granularity).
 *
 * Use when you need to atomically check hasLiked + incrementLike:
 *   synchronized (getPostLock(postId)) {
 *       if (!engagementRepo.hasLiked(userId, postId)) {
 *           engagementRepo.saveLike(like);
 *           post.incrementLikes();
 *       }
 *   }
 *
 * In our LLD, we use Option A (AtomicInteger) for the counters and
 * rely on EngagementRepository's ConcurrentHashMap for idempotency.
 */
```

---

### 8.3 Follower Count Updates

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   CONCURRENCY CONCERN: Follower Count on Follow/Unfollow         │
     │                                                                  │
     │   Multiple users can follow/unfollow the same user at once.     │
     │   User.followerCount must be updated atomically.                │
     │                                                                  │
     │   SOLUTION: SocialGraphService uses synchronized per-user       │
     │   blocks keyed by followeeId:                                   │
     │                                                                  │
     │   private final ConcurrentHashMap<String, Object> locks         │
     │       = new ConcurrentHashMap<>();                              │
     │                                                                  │
     │   public Follow follow(String followerId, String followeeId) {  │
     │       Object lock = locks.computeIfAbsent(followeeId,           │
     │           k -> new Object());                                   │
     │       synchronized (lock) {                                     │
     │           // check exists + save + incrementCount                │
     │           // all within the same lock scope                     │
     │       }                                                         │
     │   }                                                             │
     │                                                                  │
     │   WHY per-followee lock?                                        │
     │     - Two users following different people → no contention       │
     │     - Two users following the SAME person → serialized           │
     │     - Protects: exists check + save + count update atomically   │
     └──────────────────────────────────────────────────────────────────┘
```

---

### 8.4 Concurrency Summary Table

```
┌─────────────────────┬───────────────────────────┬─────────────────────────┐
│  Resource            │  Concurrency Tool          │  Why                    │
├─────────────────────┼───────────────────────────┼─────────────────────────┤
│  Timeline Cache      │  ConcurrentHashMap +       │  Per-user lock avoids   │
│  (outer map)         │  per-user synchronized     │  global bottleneck      │
├─────────────────────┼───────────────────────────┼─────────────────────────┤
│  Timeline TreeMap    │  synchronized(timeline)    │  TreeMap is not         │
│  (per-user)          │  inside addToTimeline()    │  thread-safe            │
├─────────────────────┼───────────────────────────┼─────────────────────────┤
│  Post.likeCount      │  AtomicInteger             │  Lock-free CAS for     │
│  Post.commentCount   │  incrementAndGet()         │  high-contention       │
│  Post.shareCount     │                           │  counters               │
├─────────────────────┼───────────────────────────┼─────────────────────────┤
│  User.followerCount  │  synchronized per-user     │  Follow + count must   │
│                      │  (keyed by followeeId)     │  be atomic             │
├─────────────────────┼───────────────────────────┼─────────────────────────┤
│  EngagementRepo      │  ConcurrentHashMap         │  hasLiked + saveLike   │
│  (likes set)         │  + putIfAbsent             │  idempotency           │
├─────────────────────┼───────────────────────────┼─────────────────────────┤
│  PostRepository      │  ConcurrentHashMap         │  Concurrent writes     │
│  UserRepository      │  (built into InMemory      │  from multiple post    │
│  FollowRepository    │   implementations)         │  creators              │
└─────────────────────┴───────────────────────────┴─────────────────────────┘
```

---

## 9. SOLID Principles Applied

### 9.1 Single Responsibility Principle (SRP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   Each service has ONE reason to change:                         │
     │                                                                  │
     │   PostService          → post creation logic changes             │
     │   FanoutService        → distribution routing changes            │
     │   TimelineService      → cache management changes                │
     │   RankingService       → ranking middleware changes               │
     │   SocialGraphService   → follow/unfollow logic changes           │
     │   EngagementService    → like/comment logic changes              │
     │   NotificationService  → notification delivery changes           │
     │   FeedService          → feed generation pipeline changes        │
     │                                                                  │
     │   COUNTEREXAMPLE: A single "NewsFeedService" that handles       │
     │   post creation, fan-out, ranking, social graph, and engagement │
     │   would have 5+ reasons to change. Any change risks breaking    │
     │   unrelated functionality.                                      │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.2 Open/Closed Principle (OCP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   OPEN for extension, CLOSED for modification:                   │
     │                                                                  │
     │   Adding a new fan-out strategy:                                │
     │     1. Create GeographicFanoutStrategy implements FanoutStrategy │
     │     2. Inject it into FanoutService via AppConfig               │
     │     3. ZERO changes to FanoutService, PostService, FeedService  │
     │                                                                  │
     │   Adding a new ranking algorithm:                               │
     │     1. Create TrendingRankingStrategy implements RankingStrategy │
     │     2. Inject it into RankingService via AppConfig              │
     │     3. ZERO changes to RankingService, FeedService              │
     │                                                                  │
     │   Adding a new content type:                                    │
     │     1. Add STORY to ContentType enum with boost factor          │
     │     2. ZERO changes to Post, PostService, RankingService        │
     │     3. AlgorithmicRankingStrategy auto-picks up the new boost   │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.3 Liskov Substitution Principle (LSP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   Any FanoutStrategy implementation is substitutable:            │
     │                                                                  │
     │   FanoutService works identically with:                         │
     │     - FanoutOnWriteStrategy                                     │
     │     - FanoutOnReadStrategy                                      │
     │     - HybridFanoutStrategy                                      │
     │                                                                  │
     │   The FanoutService never downcasts or checks the concrete type.│
     │   It only calls distribute() and shouldPullOnRead() via the     │
     │   FanoutStrategy interface.                                     │
     │                                                                  │
     │   Same for RankingStrategy:                                     │
     │     - ChronologicalRankingStrategy                              │
     │     - AlgorithmicRankingStrategy                                │
     │   Both return List<FeedItem> with scores and positions set.     │
     │   RankingService does not care which implementation it uses.    │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.4 Interface Segregation Principle (ISP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   Interfaces are focused and minimal:                            │
     │                                                                  │
     │   FanoutStrategy:   distribute() + shouldPullOnRead()           │
     │   RankingStrategy:  rank()                                      │
     │   TimelineCache:    addToTimeline() + getTimeline() +           │
     │                     removeFromTimeline()                        │
     │   PostRepository:   save() + findById() + findByAuthorId()      │
     │                                                                  │
     │   COUNTEREXAMPLE: A single "FeedStore" interface with:          │
     │     save(), find(), addTimeline(), getTimeline(), addLike(),    │
     │     getLikes(), addFollow(), getFollowers()                     │
     │   → Forces implementations to implement methods they don't need │
     │   → InMemoryTimelineCache shouldn't know about likes            │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.5 Dependency Inversion Principle (DIP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   High-level modules depend on abstractions, not concretions:    │
     │                                                                  │
     │   FeedService depends on:                                       │
     │     - FanoutStrategy (interface)   NOT HybridFanoutStrategy     │
     │     - PostRepository (interface)   NOT InMemoryPostRepository   │
     │     - UserRepository (interface)   NOT InMemoryUserRepository   │
     │     - TimelineCache (interface)    NOT InMemoryTimelineCache    │
     │                                                                  │
     │   FanoutService depends on:                                     │
     │     - FanoutStrategy (interface)   NOT concrete implementations │
     │                                                                  │
     │   RankingService depends on:                                    │
     │     - RankingStrategy (interface)  NOT concrete implementations │
     │                                                                  │
     │   All wiring happens in AppConfig:                              │
     │     TimelineCache cache = new InMemoryTimelineCache();          │
     │     FanoutStrategy strategy = new HybridFanoutStrategy(...);    │
     │     FeedService feedService = new FeedService(..., strategy);   │
     │                                                                  │
     │   To swap to Redis: create RedisTimelineCache, change ONE line  │
     │   in AppConfig. FeedService, TimelineService never know.        │
     └──────────────────────────────────────────────────────────────────┘
```

---

## 10. Sample Workflows

### 10.1 Normal User Publishes a Post (Fan-out on Write)

```
SCENARIO: Alice (500 followers) publishes a text post.

  FeedController.createPost("alice", "Just got promoted!", TEXT, null)
    │
    ▼
  PostService.createPost("alice", "Just got promoted!", TEXT, null)
    │
    ├── 1. UserRepository.findById("alice")           → User[alice, 500 followers]
    ├── 2. new Post.Builder("alice", "Just got...").build()  → Post[post-abc]
    ├── 3. PostRepository.save(post-abc)
    └── 4. FanoutService.distribute(post-abc, alice)
            │
            ├── SocialGraphService.getFollowers("alice")
            │     → [bob, charlie, dave, ..., 500 followers]
            │
            └── HybridFanoutStrategy.distribute(post-abc, alice, followers)
                  │
                  ├── alice.isCelebrity() → false (500 < 10,000)
                  └── FanoutOnWriteStrategy.distribute(post-abc, alice, followers)
                        │
                        ├── timelineCache.addToTimeline("bob", "post-abc", ts)
                        ├── timelineCache.addToTimeline("charlie", "post-abc", ts)
                        ├── timelineCache.addToTimeline("dave", "post-abc", ts)
                        └── ... (500 cache writes total)

  RESULT:
    - Post created and persisted in ~1ms
    - 500 timeline cache writes in ~5ms (in production: async via Kafka)
    - Bob, Charlie, Dave will see the post when they open their feed
    - No pull needed at read time for Alice's posts
```

---

### 10.2 Celebrity Publishes a Post (Fan-out on Read)

```
SCENARIO: Taylor (10M followers) publishes a video post.

  FeedController.createPost("taylor", "New music video!", VIDEO, "https://cdn/vid.mp4")
    │
    ▼
  PostService.createPost("taylor", "New music video!", VIDEO, "https://cdn/vid.mp4")
    │
    ├── 1. UserRepository.findById("taylor")          → User[taylor, 10M followers]
    ├── 2. new Post.Builder("taylor", "New...").contentType(VIDEO).mediaUrl(...).build()
    ├── 3. PostRepository.save(post-xyz)
    └── 4. FanoutService.distribute(post-xyz, taylor)
            │
            ├── SocialGraphService.getFollowers("taylor")
            │     → [10,000,000 followers]
            │
            └── HybridFanoutStrategy.distribute(post-xyz, taylor, followers)
                  │
                  ├── taylor.isCelebrity() → true (10M >= 10,000)
                  └── FanoutOnReadStrategy.distribute(post-xyz, taylor, followers)
                        │
                        └── NO-OP (post stays in PostRepository only)

  RESULT:
    - Post created and persisted in ~1ms
    - ZERO timeline cache writes (saved 10M writes!)
    - Post will be PULLED at read time by followers who are online
    - Only ~100K followers online at any given time → 99% write savings
```

---

### 10.3 User Opens Feed (Hybrid Merge + Rank)

```
SCENARIO: Bob follows Alice (normal, 500 followers) and Taylor (celebrity, 10M).
          Bob opens his feed.

  FeedController.getFeed("bob", null)
    │
    ▼
  FeedService.getFeed("bob", null)
    │
    ├── Step 1: READ from timeline cache
    │   TimelineService.getTimeline("bob", null)
    │     → ["post-abc", "post-def", "post-ghi", ...]  (20 postIds)
    │     (These are from normal users like Alice, pushed at write time)
    │
    │   Hydrate to FeedItems:
    │     PostRepository.findById("post-abc") → Post[alice, "Just got promoted!"]
    │     → FeedItem(post-abc, Source.TIMELINE)
    │     ... (repeat for all 20)
    │
    ├── Step 2: PULL celebrity posts
    │   SocialGraphService.getFollowing("bob")
    │     → [Follow(bob→alice), Follow(bob→taylor)]
    │
    │   For each followed user:
    │     fanoutStrategy.shouldPullOnRead(alice)  → false (normal user, skip)
    │     fanoutStrategy.shouldPullOnRead(taylor) → true  (celebrity, pull!)
    │
    │   PostRepository.findByAuthorIdSince("taylor", 72_hours_ago)
    │     → [post-xyz, post-lmn]  (Taylor's recent posts)
    │     → FeedItem(post-xyz, Source.PULLED)
    │     → FeedItem(post-lmn, Source.PULLED)
    │
    ├── Step 3: MERGE
    │   feedItems = [20 TIMELINE items] + [2 PULLED items]
    │   Deduplicate by postId → 22 unique items
    │
    ├── Step 4: RANK (Algorithmic)
    │   RankingService.rank(22 items, bob)
    │     → AlgorithmicRankingStrategy.rank(items, bob)
    │       For post-xyz (Taylor's video):
    │         affinity  = interactions(bob, taylor) / 50 = 15/50 = 0.30
    │         recency   = 1/(1 + 2h * 0.1) = 0.83
    │         engagement = (5000 + 2*800 + 3*200) / 100 = min(1.0, 72) = 1.00
    │         typeWeight = VIDEO = 1.3
    │         score = 0.3*0.30 + 0.3*0.83 + 0.2*1.00 + 0.2*1.30 = 0.799
    │
    │       For post-abc (Alice's text):
    │         affinity  = interactions(bob, alice) / 50 = 30/50 = 0.60
    │         recency   = 1/(1 + 1h * 0.1) = 0.91
    │         engagement = (12 + 2*3 + 3*1) / 100 = 0.21
    │         typeWeight = TEXT = 0.9
    │         score = 0.3*0.60 + 0.3*0.91 + 0.2*0.21 + 0.2*0.90 = 0.675
    │
    │     Sort by score desc → [post-xyz (0.799), post-abc (0.675), ...]
    │
    └── Step 5: PAGINATE
        Return first 20 items

  RESULT:
    - Taylor's viral video ranks #1 despite being from a celebrity (pulled)
    - Alice's text post ranks #2 due to high affinity with Bob
    - Mix of TIMELINE and PULLED sources, seamlessly merged and ranked
```

---

### 10.4 Infinite Scroll (Cursor-based Pagination)

```
SCENARIO: Bob scrolls past the first 20 posts and loads page 2.

  // Client sends cursor from last item of page 1
  FeedCursor cursor = new FeedCursor("post-last-of-page1", ts_of_last, 20);

  FeedController.getFeed("bob", cursor)
    │
    ▼
  FeedService.getFeed("bob", cursor)
    │
    ├── Step 1: READ from timeline cache with cursor
    │   TimelineService.getTimeline("bob", cursor)
    │     → timelineCache.getTimeline("bob", cursor)
    │       → TreeMap.headMap(cursor.lastTimestamp, exclusive).descendingMap()
    │       → next 20 postIds OLDER than the cursor
    │
    ├── Step 2-4: Same pipeline (pull, merge, rank)
    │
    └── Step 5: Return next 20 items

  WHY CURSOR > OFFSET:
    ┌──────────────────────────────────────────────────────────────────┐
    │   Between page 1 and page 2, 5 new posts were inserted.         │
    │                                                                  │
    │   OFFSET-BASED (broken):                                        │
    │     Page 2: OFFSET 20 → skips 20, but 5 new posts shifted       │
    │     the list → Bob sees 5 duplicates from page 1                 │
    │                                                                  │
    │   CURSOR-BASED (correct):                                       │
    │     Page 2: "posts older than ts=1714198000000"                  │
    │     → New posts have timestamps > 1714198000000                  │
    │     → They don't affect the cursor → no duplicates              │
    └──────────────────────────────────────────────────────────────────┘
```

---

### 10.5 Follow / Unfollow Workflow

```
SCENARIO: Bob follows Taylor (celebrity).

  FeedController.follow("bob", "taylor")
    │
    ▼
  SocialGraphService.follow("bob", "taylor")
    │
    ├── UserRepository.findById("bob")     → User[bob]
    ├── UserRepository.findById("taylor")  → User[taylor, 10M followers]
    ├── FollowRepository.exists("bob", "taylor") → false (not yet following)
    ├── FollowRepository.save(Follow("bob", "taylor", now))
    ├── taylor.incrementFollowerCount()  → 10,000,001
    └── NotificationService.notifyFollow("taylor", "bob")
          → "[Notification] → taylor: 'bob started following you'"

  AFTER FOLLOW:
    - Bob's next getFeed() will pull Taylor's recent posts at read time
    - Taylor's posts are NOT retroactively pushed to Bob's timeline cache
    - Only NEW posts by Taylor (after Bob follows) will appear naturally
    - In production: a background job could backfill Taylor's recent posts
```

---

## 11. Design Patterns Used

```
┌──────────────────────┬──────────────────┬─────────────────────────────────────────┐
│  Pattern              │  Where Applied    │  Why                                   │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Strategy             │  FanoutStrategy   │  Swap fan-out algorithm without         │
│                       │  RankingStrategy  │  changing services. Core of the system. │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Composite            │  HybridFanout    │  Composes FanoutOnWrite + FanoutOnRead  │
│                       │  Strategy         │  into a single strategy. Delegates      │
│                       │                  │  based on celebrity status.             │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Builder              │  Post.Builder     │  Complex object construction with       │
│                       │                  │  optional fields, validation, auto-     │
│                       │                  │  generated ID and timestamp.            │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Facade               │  FeedService      │  Single entry point for feed            │
│                       │                  │  generation. Orchestrates 6+ services.  │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Repository           │  PostRepository   │  Abstracts data access. In-memory       │
│                       │  UserRepository   │  for demo, swappable to DB.            │
│                       │  FollowRepository │                                        │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Observer             │  Notification     │  Post creation, likes, comments, and    │
│  (simplified)         │  Service          │  follows trigger notifications to       │
│                       │                  │  relevant users.                        │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Factory Method       │  AppConfig        │  Centralized object creation and        │
│  (via Config)         │                  │  dependency injection without a         │
│                       │                  │  framework.                             │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Null Object          │  FanoutOnRead    │  distribute() is intentionally a no-op. │
│  (partial)            │  Strategy         │  Avoids null checks in FanoutService.  │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Iterator/Cursor      │  FeedCursor       │  Cursor-based pagination for infinite   │
│                       │                  │  scroll. Stable across inserts/deletes. │
└──────────────────────┴──────────────────┴─────────────────────────────────────────┘
```

### Pattern Interaction Map

```
    ┌─────────────────────────────────────────────────────────────────────┐
    │                                                                     │
    │   FeedController                                                    │
    │        │                                                            │
    │        ▼                                                            │
    │   FeedService ◄────────────── FACADE                               │
    │        │                                                            │
    │        ├──→ TimelineService                                        │
    │        │         │                                                  │
    │        │         └──→ InMemoryTimelineCache ◄── REPOSITORY         │
    │        │                                                            │
    │        ├──→ FanoutService                                          │
    │        │         │                                                  │
    │        │         └──→ HybridFanoutStrategy  ◄── COMPOSITE          │
    │        │                   │                                        │
    │        │                   ├──→ FanoutOnWriteStrategy ◄─ STRATEGY  │
    │        │                   └──→ FanoutOnReadStrategy  ◄─ NULL OBJ  │
    │        │                                                            │
    │        ├──→ RankingService                                         │
    │        │         │                                                  │
    │        │         └──→ AlgorithmicRankingStrategy  ◄── STRATEGY     │
    │        │                                                            │
    │        ├──→ PostService                                            │
    │        │         │                                                  │
    │        │         └──→ Post.Builder  ◄── BUILDER                    │
    │        │                                                            │
    │        └──→ SocialGraphService                                     │
    │                   │                                                 │
    │                   └──→ FollowRepository  ◄── REPOSITORY            │
    │                                                                     │
    │   AppConfig ◄──────────────── FACTORY / DI CONTAINER               │
    │   FeedCursor ◄─────────────── CURSOR / ITERATOR                    │
    │   NotificationService ◄────── OBSERVER (simplified)                │
    │                                                                     │
    └─────────────────────────────────────────────────────────────────────┘
```

---

## 12. Extensibility Points

### 12.1 New Fan-out Strategy

```
SCENARIO: Add a geographic fan-out strategy that pushes to nearby users first.

  1. Create GeographicFanoutStrategy implements FanoutStrategy
     - distribute(): push to followers in same region first (low latency)
     - shouldPullOnRead(): true for cross-region followers
  2. Change ONE line in AppConfig:
     - FanoutStrategy strategy = new GeographicFanoutStrategy(...);
  3. ZERO changes to: FanoutService, PostService, FeedService

  Files changed: 1 new + 1 modified (AppConfig)
```

### 12.2 New Ranking Algorithm

```
SCENARIO: Add a trending/viral ranking strategy for the "Explore" tab.

  1. Create TrendingRankingStrategy implements RankingStrategy
     - rank(): score = recentEngagementVelocity * viralCoefficient
     - Prioritizes posts gaining likes/shares fastest in last hour
  2. In FeedService (or a new ExploreService):
     - Use TrendingRankingStrategy instead of AlgorithmicRankingStrategy
  3. ZERO changes to: RankingService, FeedService, PostService

  Files changed: 1 new + 1 modified (AppConfig or new service)
```

### 12.3 New Content Type

```
SCENARIO: Add STORY content type (24-hour ephemeral posts).

  1. Add STORY(1.4) to ContentType enum
     - Boost factor 1.4 (highest -- platform wants to promote stories)
  2. AlgorithmicRankingStrategy auto-picks up the new boost factor
     - ContentType.getBoostFactor() returns 1.4 for STORY
  3. Optionally: add story expiry logic in PostService
     - Override getPost() to check if STORY is > 24 hours old

  Files changed: 1 modified (ContentType enum), optionally PostService
```

### 12.4 New Storage Backend

```
SCENARIO: Replace InMemoryTimelineCache with RedisTimelineCache.

  1. Create RedisTimelineCache implements TimelineCache
     - addToTimeline():  ZADD user:{userId}:timeline {ts} {postId}
     - getTimeline():    ZREVRANGEBYSCORE user:{userId}:timeline +inf {cursor_ts} LIMIT 0 20
     - removeFromTimeline(): ZREM user:{userId}:timeline {postId}
  2. Change ONE line in AppConfig:
     - TimelineCache cache = new RedisTimelineCache(redisClient);
  3. ZERO changes to: TimelineService, FeedService, FanoutOnWriteStrategy

  Files changed: 1 new + 1 modified (AppConfig)
```

### 12.5 A/B Testing of Ranking

```
SCENARIO: 50% of users see chronological feed, 50% see algorithmic.

  1. RankingService already supports setRankingStrategy() at runtime
  2. In FeedService.getFeed(), before ranking:
     - if (abTestService.isInGroup(userId, "chrono_feed")):
         rankingService.setRankingStrategy(chronologicalStrategy);
     - else:
         rankingService.setRankingStrategy(algorithmicStrategy);
  3. Metrics: compare engagement rates between groups

  Files changed: 1 new (ABTestService) + FeedService modified
```

### 12.6 Async Fan-out via Message Queue

```
SCENARIO: Replace synchronous fan-out with async message queue.

  CURRENT (synchronous):
    PostService.createPost()
      → FanoutService.distribute()
        → FanoutStrategy.distribute()  // blocks until all writes complete

  EXTENDED (async):
    PostService.createPost()
      → publish PostCreatedEvent to Kafka/SQS
      → return immediately (createPost latency: ~1ms)

    FanoutConsumer (background):
      → consume PostCreatedEvent
      → FanoutService.distribute()
        → FanoutStrategy.distribute()  // async, no user waiting

  Implementation:
    1. Create AsyncFanoutService wrapping FanoutService
    2. Uses ExecutorService / CompletableFuture for async dispatch
    3. PostService injects AsyncFanoutService instead of FanoutService
    4. ZERO changes to FanoutStrategy implementations
```

### 12.7 Extensibility Summary

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   EXTENSIBILITY MATRIX                                           │
     │                                                                  │
     │   Change                          │ Files Modified │ Files New  │
     │   ────────────────────────────────┼───────────────┼────────────│
     │   New fan-out strategy            │ 1 (AppConfig) │ 1          │
     │   New ranking algorithm           │ 1 (AppConfig) │ 1          │
     │   New content type                │ 1 (enum)      │ 0          │
     │   New storage backend             │ 1 (AppConfig) │ 1          │
     │   A/B test ranking                │ 1 (FeedSvc)   │ 1          │
     │   Async fan-out                   │ 1 (PostSvc)   │ 1          │
     │   New engagement type (share)     │ 1 (EngSvc)    │ 0          │
     │   New notification channel        │ 1 (NotifSvc)  │ 0          │
     │                                                                  │
     │   EVERY extension modifies at most 1 existing file.             │
     │   This is the Open/Closed Principle in action.                  │
     └──────────────────────────────────────────────────────────────────┘
```

---

> **Interview tip**: When the interviewer asks "How would you design a news feed system?", start with the hybrid fan-out trade-off (Section 3), show the feed generation pipeline (Section 7.1), and explain cursor-based pagination (Section 4.6). These three topics demonstrate you understand the core challenges: write amplification, read latency, and infinite scroll. Then layer in the ranking algorithm (Section 6.5) and concurrency model (Section 8) to show depth. The Strategy and Composite patterns (Sections 6.1-6.3) demonstrate clean OO design that scales.
