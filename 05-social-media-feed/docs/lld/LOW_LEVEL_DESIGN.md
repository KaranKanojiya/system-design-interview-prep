# Low-Level Design: Social Media Feed System (Twitter/X-like)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Fan-out Strategy, Celebrity Problem, Trending, Edge Cases

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Fan-out Strategy Implementations](#6-fan-out-strategy-implementations---the-core)
7. [Feed Generation Service](#7-feed-generation-service---the-read-path)
8. [Ranking Implementations](#8-ranking-implementations)
9. [Trending Service](#9-trending-service)
10. [Concurrency](#10-concurrency)
11. [Edge Cases Handling](#11-edge-cases-handling-in-code)
12. [Sample Workflows](#12-sample-workflows)
13. [Design Patterns](#13-design-patterns)
14. [Extensibility](#14-extensibility)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: User, Tweet, FeedItem, Follow, TrendingTopic, enums |
| **Service** | `service/` | Business logic orchestration: feed generation, tweet CRUD, user ops, follow graph, timeline |
| **Fanout** | `fanout/` | THE critical module. Fan-out strategy selection (write/read/hybrid), celebrity detection, tweet distribution |
| **Ranking** | `ranking/` | Feed scoring and ordering: chronological, engagement-based, extensible rankers |
| **Trending** | `trending/` | Hashtag extraction, count tracking, velocity-based scoring, trending windows |
| **Repository** | `repository/` | Data access layer: in-memory stores with concurrent data structures, CRUD for all entities |
| **Controller** | `controller/` | REST API entry points: feed retrieval, tweet posting, follow/unfollow, trending |
| **Config** | `config/` | Application configuration: thresholds, defaults, factory wiring |
| **Exception** | `exception/` | Domain-specific exceptions: not found, validation, rate limiting |

### Why These Modules Matter in Interviews

```
Interview question: "How does Twitter deliver a tweet to 50M followers?"

Answer path through modules:

  Tweet posted --> FanoutService (fanout/)
                      |
                      +--> Is poster a celebrity? (model/UserType)
                      |       |
                      |       YES --> FanoutOnReadStrategy (do nothing now, pull later)
                      |       NO  --> FanoutOnWriteStrategy (push to all follower timelines)
                      |
                      +--> At read time --> FeedService (service/)
                                              |
                                              +--> Merge pre-computed + celebrity pulls
                                              +--> Rank via FeedRanker (ranking/)
                                              +--> Return top-N items
```

---

## 2. Package Structure

```
com.systemdesign.feed
├── model/
│   ├── User.java                    — User entity with celebrity detection
│   ├── Tweet.java                   — Tweet entity with Builder pattern
│   ├── FeedItem.java                — Wraps Tweet with ranking score + source metadata
│   ├── Follow.java                  — Social graph edge (follower → followee)
│   ├── TrendingTopic.java           — Hashtag with count, score, and time window
│   ├── UserType.java (enum)         — NORMAL, CELEBRITY, VERIFIED
│   └── FeedSource.java (enum)       — FANOUT_WRITE, FANOUT_READ, MERGED
│
├── service/
│   ├── FeedService.java             — THE read path: merge push+pull, rank, return
│   ├── TweetService.java            — Tweet CRUD + fan-out trigger + trending trigger
│   ├── UserService.java             — User management + celebrity threshold check
│   ├── FollowService.java           — Follow/unfollow + follower list management
│   └── TimelineService.java         — User timeline (all tweets by a user)
│
├── fanout/
│   ├── FanoutStrategy.java          — Strategy interface (THE key abstraction)
│   ├── FanoutOnWriteStrategy.java   — Push to all follower timelines (O(followers) writes)
│   ├── FanoutOnReadStrategy.java    — No-op at write time, pull at read time
│   ├── HybridFanoutStrategy.java    — THE ANSWER: write for normal, read for celebrity
│   └── FanoutService.java           — Orchestrator: resolves strategy, executes, tracks stats
│
├── ranking/
│   ├── FeedRanker.java              — Ranker interface
│   ├── ChronologicalRanker.java     — Sort by time (Twitter pre-2016)
│   ├── EngagementRanker.java        — Score by freshness x engagement (modern Twitter)
│   └── RankingScore.java            — Utility class with static scoring methods
│
├── trending/
│   ├── TrendingService.java         — Record hashtags, compute velocity scores, get top-K
│   ├── HashtagExtractor.java        — Regex-based #hashtag extraction
│   └── TrendingWindow.java (enum)   — HOUR_1, HOUR_6, HOUR_24
│
├── repository/
│   ├── TweetRepository.java         — Interface for tweet storage
│   ├── UserRepository.java          — Interface for user storage
│   ├── FollowRepository.java        — Interface for social graph storage
│   ├── TimelineCacheRepository.java — Interface for pre-computed timeline cache
│   ├── TrendingRepository.java      — Interface for trending hashtag storage
│   ├── InMemoryTweetRepository.java
│   ├── InMemoryUserRepository.java
│   ├── InMemoryFollowRepository.java
│   ├── InMemoryTimelineCacheRepository.java
│   └── InMemoryTrendingRepository.java
│
├── controller/
│   └── FeedController.java          — REST endpoints for feed, tweet, follow, trending
│
├── config/
│   └── AppConfig.java               — Threshold constants, bean wiring, factory methods
│
└── exception/
    ├── FeedException.java           — Base exception for feed system
    ├── TweetNotFoundException.java  — Tweet lookup failures
    └── UserNotFoundException.java   — User lookup failures
```

---

## 3. Class Diagram

```
+------------------------------------------------------------------+
|                        <<enum>> UserType                         |
|------------------------------------------------------------------|
| NORMAL | CELEBRITY | VERIFIED                                    |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
|                       <<enum>> FeedSource                        |
|------------------------------------------------------------------|
| FANOUT_WRITE | FANOUT_READ | MERGED                             |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
|                       <<enum>> TrendingWindow                    |
|------------------------------------------------------------------|
| HOUR_1 | HOUR_6 | HOUR_24                                       |
+------------------------------------------------------------------+

+-----------------------------------+     +-----------------------------------+
|             User                  |     |             Tweet                 |
|-----------------------------------|     |-----------------------------------|
| - userId: String                  |     | - tweetId: String                 |
| - username: String                |     | - userId: String                  |
| - displayName: String             |     | - content: String                 |
| - bio: String                     |     | - mediaUrls: List<String>         |
| - followerCount: AtomicInteger    |     | - hashtags: List<String>          |
| - followingCount: AtomicInteger   |     | - likeCount: AtomicInteger        |
| - userType: UserType              |     | - retweetCount: AtomicInteger     |
| - createdAt: LocalDateTime        |     | - replyCount: AtomicInteger       |
|-----------------------------------|     | - createdAt: LocalDateTime        |
| + isCelebrity(): boolean          |     | - deleted: boolean                |
| + incrementFollowers(): void      |     |-----------------------------------|
| + decrementFollowers(): void      |     | + extractHashtags(): List<String> |
| + getUserType(): UserType         |     | + softDelete(): void              |
+-----------------------------------+     | + isDeleted(): boolean            |
         |                                | + incrementLikes(): int           |
         | 1                              | + incrementRetweets(): int        |
         |                                +-----------------------------------+
         |                                       | 1
         | follows                               |
         v                                       v
+-----------------------------------+     +-----------------------------------+
|            Follow                 |     |           FeedItem                |
|-----------------------------------|     |-----------------------------------|
| - followerId: String              |     | - tweet: Tweet                    |
| - followeeId: String              |     | - score: double                   |
| - createdAt: LocalDateTime        |     | - source: FeedSource              |
+-----------------------------------+     | - addedAt: LocalDateTime          |
                                          |-----------------------------------|
                                          | + compareTo(FeedItem): int        |
                                          | + getScore(): double              |
                                          +-----------------------------------+

+-----------------------------------+
|        TrendingTopic              |
|-----------------------------------|
| - hashtag: String                 |
| - count: long                     |
| - score: double                   |
| - window: TrendingWindow          |
| - updatedAt: LocalDateTime        |
+-----------------------------------+

+====================================================================+
|                     <<interface>> FanoutStrategy                    |
|====================================================================|
| + fanout(Tweet tweet, List<String> followerIds): void              |
| + name(): String                                                   |
+====================================================================+
        ^                   ^                   ^
        |                   |                   |
+------------------+ +------------------+ +-------------------------+
| FanoutOnWrite    | | FanoutOnRead     | | HybridFanoutStrategy    |
| Strategy         | | Strategy         | |                         |
|------------------| |------------------| |-------------------------|
| - timelineCache: | | (no state)       | | - writeStrategy         |
|   TimelineCache  | |                  | | - readStrategy          |
|   Repository     | |                  | | - celebrityThreshold    |
|------------------| |------------------| |   : int (default 10000) |
| + fanout(...)    | | + fanout(...)    | |-------------------------|
|   Push to ALL    | |   NO-OP         | | + fanout(...)            |
|   follower       | |   (pulled at     | |   CELEBRITY? -> read    |
|   timelines      | |    read time)    | |   NORMAL?   -> write    |
+------------------+ +------------------+ +-------------------------+
                                                    |
                                            composes|both
                                                    v
                                          +-------------------+
                                          |   FanoutService   |
                                          |-------------------|
                                          | - strategy:       |
                                          |   HybridFanout    |
                                          | - followRepo      |
                                          | - userRepo        |
                                          |-------------------|
                                          | + processTweet()  |
                                          +-------------------+

+====================================================================+
|                      <<interface>> FeedRanker                      |
|====================================================================|
| + rank(List<FeedItem> items): List<FeedItem>                       |
| + name(): String                                                   |
+====================================================================+
        ^                          ^
        |                          |
+---------------------+  +------------------------+
| ChronologicalRanker |  | EngagementRanker       |
|---------------------|  |------------------------|
| + rank(...)         |  | + rank(...)            |
|   Sort by createdAt |  |   Score = freshness *  |
|   descending        |  |   (likes + retweets*2  |
+---------------------+  |   + replies*1.5)       |
                          +------------------------+

+==========================================================================+
|                           FeedService                                    |
|  THE READ PATH — Where hybrid fan-out magic happens                      |
|--------------------------------------------------------------------------|
| - timelineCacheRepo: TimelineCacheRepository                             |
| - tweetRepo: TweetRepository                                            |
| - followRepo: FollowRepository                                          |
| - userRepo: UserRepository                                              |
| - feedRanker: FeedRanker                                                 |
|--------------------------------------------------------------------------|
| + generateFeed(userId, limit): List<FeedItem>                            |
|   1. Pull pre-computed timeline (fan-out-on-write items)                 |
|   2. Find celebrities user follows                                       |
|   3. Pull celebrity tweets directly                                      |
|   4. MERGE both lists                                                    |
|   5. RANK merged list                                                    |
|   6. Return top-N                                                        |
| + getUserTimeline(userId, limit): List<FeedItem>                         |
+==========================================================================+

+==========================================================================+
|                         TweetService                                     |
|--------------------------------------------------------------------------|
| - tweetRepo, userRepo, fanoutService, trendingService                    |
|--------------------------------------------------------------------------|
| + postTweet(userId, content, mediaUrls): Tweet                           |
|   1. Create tweet with Builder                                           |
|   2. Save to TweetRepository                                             |
|   3. Trigger FanoutService.processTweet(tweet)                           |
|   4. Trigger TrendingService.recordHashtags(tweet)                       |
| + deleteTweet(tweetId): void                                             |
| + likeTweet(tweetId): int                                                |
| + retweetTweet(tweetId): int                                             |
+==========================================================================+

+====================================+   +====================================+
| <<interface>>                      |   | <<interface>>                      |
| TimelineCacheRepository            |   | TrendingRepository                 |
|------------------------------------|   |------------------------------------|
| + addToTimeline(userId, FeedItem)  |   | + incrementCount(hashtag, window)  |
| + getTimeline(userId, limit)       |   | + getTopTrending(window, limit)    |
| + removeFromTimeline(userId, tid)  |   | + getCount(hashtag, window)        |
| + getTimelineSize(userId)          |   | + resetWindow(window)              |
+====================================+   +====================================+

+====================================+   +====================================+
| <<interface>> TweetRepository      |   | <<interface>> UserRepository        |
|------------------------------------|   |------------------------------------|
| + save(Tweet): Tweet               |   | + save(User): User                 |
| + findById(tweetId): Optional      |   | + findById(userId): Optional       |
| + findByUserId(userId): List       |   | + findByUsername(username): Opt.    |
| + findByUserIdLatest(userId, N)    |   | + existsById(userId): boolean      |
| + delete(tweetId): void            |   +====================================+
+====================================+
                                         +====================================+
                                         | <<interface>> FollowRepository     |
                                         |------------------------------------|
                                         | + save(Follow): Follow             |
                                         | + delete(followerId, followeeId)   |
                                         | + getFollowerIds(userId): List     |
                                         | + getFolloweeIds(userId): List     |
                                         | + isFollowing(ferId, feId): bool   |
                                         | + getFollowerCount(userId): int    |
                                         +====================================+
```

---

## 4. Entity Design

### 4.1 UserType Enum

```java
public enum UserType {
    NORMAL,      // < 10,000 followers — tweets fan-out on write
    CELEBRITY,   // >= 10,000 followers — tweets fan-out on read
    VERIFIED;    // Verified account — same fan-out as CELEBRITY if follower count qualifies

    public static UserType fromFollowerCount(int followerCount) {
        return followerCount >= 10_000 ? CELEBRITY : NORMAL;
    }
}
```

**Why this matters**: The `UserType` drives the entire fan-out strategy selection. This is the "celebrity problem" — you cannot push Elon Musk's tweet to 150M timelines in real time.

### 4.2 FeedSource Enum

```java
public enum FeedSource {
    FANOUT_WRITE,  // This feed item was pre-computed (pushed to user's timeline cache)
    FANOUT_READ,   // This feed item was pulled at read time (from a celebrity's timeline)
    MERGED;        // This item exists in the final merged + ranked feed

    public boolean isPushed() { return this == FANOUT_WRITE; }
    public boolean isPulled() { return this == FANOUT_READ; }
}
```

**Why track source?** Debugging, analytics, and A/B testing. If a user's feed latency is high, check how many FANOUT_READ items they have (too many celebrity follows = slow feed).

### 4.3 User Entity

```java
public class User {
    private final String userId;
    private final String username;
    private String displayName;
    private String bio;
    private final AtomicInteger followerCount;
    private final AtomicInteger followingCount;
    private UserType userType;
    private final LocalDateTime createdAt;

    // Celebrity threshold — the magic number
    private static final int CELEBRITY_THRESHOLD = 10_000;

    public User(String userId, String username, String displayName, String bio) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.bio = bio;
        this.followerCount = new AtomicInteger(0);
        this.followingCount = new AtomicInteger(0);
        this.userType = UserType.NORMAL;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * THE celebrity check — drives fan-out strategy selection.
     * A user is a celebrity if their follower count exceeds the threshold.
     * This is checked at tweet-post time to decide write vs read fan-out.
     */
    public boolean isCelebrity() {
        return followerCount.get() >= CELEBRITY_THRESHOLD;
    }

    public UserType getUserType() {
        // Dynamically compute — a user can BECOME a celebrity mid-session
        if (isCelebrity() && userType == UserType.NORMAL) {
            userType = UserType.CELEBRITY;
        }
        return userType;
    }

    public int incrementFollowers() {
        int newCount = followerCount.incrementAndGet();
        // Re-evaluate user type on follower change
        if (newCount >= CELEBRITY_THRESHOLD && userType == UserType.NORMAL) {
            userType = UserType.CELEBRITY;
        }
        return newCount;
    }

    public int decrementFollowers() {
        return followerCount.decrementAndGet();
    }

    public int incrementFollowing() {
        return followingCount.incrementAndGet();
    }

    public int decrementFollowing() {
        return followingCount.decrementAndGet();
    }

    // Getters
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public int getFollowerCount() { return followerCount.get(); }
    public int getFollowingCount() { return followingCount.get(); }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

### 4.4 Tweet Entity (Builder Pattern)

```java
public class Tweet {
    private final String tweetId;
    private final String userId;
    private final String content;
    private final List<String> mediaUrls;
    private final List<String> hashtags;
    private final AtomicInteger likeCount;
    private final AtomicInteger retweetCount;
    private final AtomicInteger replyCount;
    private final LocalDateTime createdAt;
    private volatile boolean deleted;  // volatile for visibility across threads

    // Private constructor — force Builder usage
    private Tweet(Builder builder) {
        this.tweetId = builder.tweetId;
        this.userId = builder.userId;
        this.content = builder.content;
        this.mediaUrls = Collections.unmodifiableList(
            builder.mediaUrls != null ? builder.mediaUrls : new ArrayList<>()
        );
        this.hashtags = Collections.unmodifiableList(extractHashtags(builder.content));
        this.likeCount = new AtomicInteger(0);
        this.retweetCount = new AtomicInteger(0);
        this.replyCount = new AtomicInteger(0);
        this.createdAt = LocalDateTime.now();
        this.deleted = false;
    }

    /**
     * Extract hashtags from tweet content.
     * Regex: #followed by one or more word characters.
     * "Hello #world and #Java17 rocks" -> ["world", "Java17"]
     */
    public static List<String> extractHashtags(String content) {
        if (content == null || content.isEmpty()) return Collections.emptyList();

        List<String> hashtags = new ArrayList<>();
        Pattern pattern = Pattern.compile("#(\\w+)");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            hashtags.add(matcher.group(1).toLowerCase());
        }
        return hashtags;
    }

    // Atomic increments — thread-safe engagement tracking
    public int incrementLikes() { return likeCount.incrementAndGet(); }
    public int incrementRetweets() { return retweetCount.incrementAndGet(); }
    public int incrementReplies() { return replyCount.incrementAndGet(); }

    // Soft delete — tweet is not physically removed
    public void softDelete() { this.deleted = true; }
    public boolean isDeleted() { return deleted; }

    // Getters
    public String getTweetId() { return tweetId; }
    public String getUserId() { return userId; }
    public String getContent() { return content; }
    public List<String> getMediaUrls() { return mediaUrls; }
    public List<String> getHashtags() { return hashtags; }
    public int getLikeCount() { return likeCount.get(); }
    public int getRetweetCount() { return retweetCount.get(); }
    public int getReplyCount() { return replyCount.get(); }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // ==================== BUILDER PATTERN ====================
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String tweetId;
        private String userId;
        private String content;
        private List<String> mediaUrls;

        public Builder tweetId(String tweetId) {
            this.tweetId = tweetId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder mediaUrls(List<String> mediaUrls) {
            this.mediaUrls = mediaUrls;
            return this;
        }

        public Tweet build() {
            // Validation
            if (tweetId == null || tweetId.isBlank())
                throw new IllegalArgumentException("tweetId cannot be null or blank");
            if (userId == null || userId.isBlank())
                throw new IllegalArgumentException("userId cannot be null or blank");
            if (content == null || content.isBlank())
                throw new IllegalArgumentException("content cannot be null or blank");
            if (content.length() > 280)
                throw new IllegalArgumentException("content exceeds 280 character limit");

            return new Tweet(this);
        }
    }

    @Override
    public String toString() {
        return String.format("Tweet{id='%s', user='%s', content='%s', likes=%d, retweets=%d}",
            tweetId, userId,
            content.length() > 50 ? content.substring(0, 50) + "..." : content,
            likeCount.get(), retweetCount.get());
    }
}
```

### 4.5 FeedItem Entity

```java
public class FeedItem implements Comparable<FeedItem> {
    private final Tweet tweet;
    private double score;
    private final FeedSource source;
    private final LocalDateTime addedAt;

    public FeedItem(Tweet tweet, FeedSource source) {
        this.tweet = tweet;
        this.score = 0.0;
        this.source = source;
        this.addedAt = LocalDateTime.now();
    }

    public FeedItem(Tweet tweet, double score, FeedSource source) {
        this.tweet = tweet;
        this.score = score;
        this.source = source;
        this.addedAt = LocalDateTime.now();
    }

    /**
     * Natural ordering: higher score first (descending).
     * If scores are equal, newer tweet first.
     */
    @Override
    public int compareTo(FeedItem other) {
        int scoreCompare = Double.compare(other.score, this.score); // descending
        if (scoreCompare != 0) return scoreCompare;
        return other.tweet.getCreatedAt().compareTo(this.tweet.getCreatedAt()); // newer first
    }

    // Getters and setter for score
    public Tweet getTweet() { return tweet; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public FeedSource getSource() { return source; }
    public LocalDateTime getAddedAt() { return addedAt; }

    @Override
    public String toString() {
        return String.format("FeedItem{tweet=%s, score=%.2f, source=%s}",
            tweet.getTweetId(), score, source);
    }
}
```

### 4.6 Follow Entity

```java
public class Follow {
    private final String followerId;   // who is following
    private final String followeeId;   // who is being followed
    private final LocalDateTime createdAt;

    public Follow(String followerId, String followeeId) {
        this.followerId = followerId;
        this.followeeId = followeeId;
        this.createdAt = LocalDateTime.now();
    }

    public String getFollowerId() { return followerId; }
    public String getFolloweeId() { return followeeId; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Follow)) return false;
        Follow follow = (Follow) o;
        return followerId.equals(follow.followerId) &&
               followeeId.equals(follow.followeeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(followerId, followeeId);
    }
}
```

### 4.7 TrendingTopic Entity

```java
public class TrendingTopic implements Comparable<TrendingTopic> {
    private final String hashtag;
    private long count;
    private double score;
    private final TrendingWindow window;
    private LocalDateTime updatedAt;

    public TrendingTopic(String hashtag, long count, TrendingWindow window) {
        this.hashtag = hashtag;
        this.count = count;
        this.score = 0.0;
        this.window = window;
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementCount() {
        this.count++;
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public int compareTo(TrendingTopic other) {
        return Double.compare(other.score, this.score); // higher score first
    }

    // Getters and setters
    public String getHashtag() { return hashtag; }
    public long getCount() { return count; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public TrendingWindow getWindow() { return window; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

### 4.8 TrendingWindow Enum

```java
public enum TrendingWindow {
    HOUR_1(1),
    HOUR_6(6),
    HOUR_24(24);

    private final int hours;

    TrendingWindow(int hours) {
        this.hours = hours;
    }

    public int getHours() { return hours; }

    public LocalDateTime getWindowStart() {
        return LocalDateTime.now().minusHours(hours);
    }
}
```

---

## 5. Interface Contracts

### 5.1 FanoutStrategy -- THE Key Interface

```java
/**
 * THE critical abstraction in the entire system.
 *
 * This interface defines how a tweet is distributed to followers.
 * The implementation chosen determines system behavior:
 *   - FanoutOnWrite: push-based, O(followers) writes, O(1) reads
 *   - FanoutOnRead:  pull-based, O(1) writes, O(following) reads
 *   - Hybrid:        write for normal users, read for celebrities
 *
 * Interview hint: Always mention all three, then explain why hybrid wins.
 */
public interface FanoutStrategy {

    /**
     * Distribute a tweet to the given follower timelines.
     *
     * @param tweet       the tweet to distribute
     * @param followerIds list of user IDs who follow the tweet author
     */
    void fanout(Tweet tweet, List<String> followerIds);

    /**
     * @return strategy name for logging and debugging
     */
    String name();
}
```

### 5.2 FeedRanker Interface

```java
/**
 * Defines how feed items are scored and ordered.
 * Strategy pattern allows swapping ranking algorithms without changing FeedService.
 */
public interface FeedRanker {

    /**
     * Score and sort feed items.
     * Implementations may mutate the score field on each FeedItem.
     *
     * @param items unranked feed items
     * @return ranked (sorted) feed items, best first
     */
    List<FeedItem> rank(List<FeedItem> items);

    /**
     * @return ranker name for logging and A/B test tracking
     */
    String name();
}
```

### 5.3 TimelineCacheRepository Interface

```java
/**
 * Pre-computed timeline cache.
 * Each user has a cached list of FeedItems that were pushed via fan-out-on-write.
 * This is the "mailbox" that gets filled when normal users post tweets.
 */
public interface TimelineCacheRepository {

    /**
     * Add a feed item to a user's cached timeline.
     * Called by FanoutOnWriteStrategy for each follower.
     *
     * @param userId the timeline owner
     * @param item   the feed item to cache
     */
    void addToTimeline(String userId, FeedItem item);

    /**
     * Retrieve the pre-computed timeline for a user.
     * Returns only FANOUT_WRITE items (pushed items).
     *
     * @param userId the timeline owner
     * @param limit  max number of items to return
     * @return cached feed items, newest first
     */
    List<FeedItem> getTimeline(String userId, int limit);

    /**
     * Remove a specific tweet from a user's timeline (e.g., on tweet deletion).
     *
     * @param userId  the timeline owner
     * @param tweetId the tweet to remove
     */
    void removeFromTimeline(String userId, String tweetId);

    /**
     * Get the current size of a user's cached timeline.
     *
     * @param userId the timeline owner
     * @return number of cached items
     */
    int getTimelineSize(String userId);
}
```

### 5.4 TweetRepository Interface

```java
public interface TweetRepository {
    Tweet save(Tweet tweet);
    Optional<Tweet> findById(String tweetId);
    List<Tweet> findByUserId(String userId);
    List<Tweet> findByUserIdLatest(String userId, int limit);
    void delete(String tweetId);
    boolean existsById(String tweetId);
}
```

### 5.5 UserRepository Interface

```java
public interface UserRepository {
    User save(User user);
    Optional<User> findById(String userId);
    Optional<User> findByUsername(String username);
    boolean existsById(String userId);
    List<User> findAll();
}
```

### 5.6 FollowRepository Interface

```java
public interface FollowRepository {
    Follow save(Follow follow);
    void delete(String followerId, String followeeId);
    List<String> getFollowerIds(String userId);
    List<String> getFolloweeIds(String userId);
    boolean isFollowing(String followerId, String followeeId);
    int getFollowerCount(String userId);
}
```

### 5.7 TrendingRepository Interface

```java
public interface TrendingRepository {
    void incrementCount(String hashtag, TrendingWindow window);
    List<TrendingTopic> getTopTrending(TrendingWindow window, int limit);
    long getCount(String hashtag, TrendingWindow window);
    void resetWindow(TrendingWindow window);
}
```

---

## 6. Fan-out Strategy Implementations -- THE CORE

This is the heart of the system and the most critical section for interviews. The "celebrity problem" is what separates a good answer from a great one.

### The Problem Statement

```
User A has 200 followers      --> Can push tweet to 200 timelines (fast)
Celebrity X has 50M followers --> Cannot push tweet to 50M timelines (slow, expensive)

Solution: HYBRID approach
  - Normal users:    fan-out on WRITE (push at post time)
  - Celebrities:     fan-out on READ  (pull at read time)
```

### 6.1 FanoutOnWriteStrategy

```java
/**
 * Fan-out on Write (Push model).
 *
 * When a tweet is posted, immediately push it to every follower's
 * pre-computed timeline cache.
 *
 * PROS:
 *   - Reads are O(1) — timeline is already built
 *   - Feed generation is instant for the reader
 *
 * CONS:
 *   - Writes are O(followers) — expensive for popular users
 *   - Wastes space for inactive followers who may never read
 *   - Celebrity with 50M followers = 50M writes per tweet = DISASTER
 *
 * WHEN TO USE:
 *   - Normal users with < 10K followers
 *   - Any user where write cost is acceptable
 */
public class FanoutOnWriteStrategy implements FanoutStrategy {

    private final TimelineCacheRepository timelineCacheRepo;

    public FanoutOnWriteStrategy(TimelineCacheRepository timelineCacheRepo) {
        this.timelineCacheRepo = timelineCacheRepo;
    }

    @Override
    public void fanout(Tweet tweet, List<String> followerIds) {
        if (followerIds == null || followerIds.isEmpty()) {
            System.out.println("[FANOUT-WRITE] No followers to push to for tweet " +
                tweet.getTweetId());
            return;
        }

        int pushedCount = 0;
        for (String followerId : followerIds) {
            FeedItem feedItem = new FeedItem(tweet, FeedSource.FANOUT_WRITE);
            timelineCacheRepo.addToTimeline(followerId, feedItem);
            pushedCount++;
        }

        System.out.printf("[FANOUT-WRITE] Pushed tweet %s to %d timelines%n",
            tweet.getTweetId(), pushedCount);
    }

    @Override
    public String name() {
        return "FANOUT_ON_WRITE";
    }
}
```

### 6.2 FanoutOnReadStrategy

```java
/**
 * Fan-out on Read (Pull model).
 *
 * When a tweet is posted, do NOTHING. The tweet sits in the poster's
 * user timeline only. When a follower opens their feed, they pull
 * tweets from this user's timeline.
 *
 * PROS:
 *   - Writes are O(1) — tweet is stored once
 *   - No wasted work for inactive followers
 *   - Perfect for celebrities with millions of followers
 *
 * CONS:
 *   - Reads are O(following_celebrities) — must pull from each celebrity
 *   - Feed generation is slower (must merge at read time)
 *   - Higher read latency
 *
 * WHEN TO USE:
 *   - Celebrities/verified users with >= 10K followers
 *   - Any user where write fan-out cost would be prohibitive
 */
public class FanoutOnReadStrategy implements FanoutStrategy {

    @Override
    public void fanout(Tweet tweet, List<String> followerIds) {
        // NO-OP: Tweet stays in poster's user timeline.
        // It will be pulled at read time by FeedService.generateFeed()
        System.out.printf(
            "[FANOUT-READ] Tweet %s by celebrity — skipping push, " +
            "will be pulled at read time%n",
            tweet.getTweetId());
    }

    @Override
    public String name() {
        return "FANOUT_ON_READ";
    }
}
```

### 6.3 HybridFanoutStrategy -- THE ANSWER

```java
/**
 * Hybrid Fan-out Strategy — THE interview answer.
 *
 * This is what Twitter/X actually uses. It combines both approaches:
 *   - NORMAL users (< 10K followers): fan-out on WRITE (push)
 *   - CELEBRITY users (>= 10K followers): fan-out on READ (pull)
 *
 * The magic happens at read time in FeedService:
 *   1. Pre-computed timeline (from write fan-out) is already cached
 *   2. Celebrity tweets are pulled fresh from their user timelines
 *   3. Both are MERGED and RANKED
 *   4. Top N items are returned to the user
 *
 * This Composite pattern delegates to the appropriate sub-strategy
 * based on the tweet author's follower count.
 *
 * Real-world threshold at Twitter: ~10K followers (varies by load)
 */
public class HybridFanoutStrategy implements FanoutStrategy {

    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy readStrategy;
    private final int celebrityThreshold;
    private final UserRepository userRepository;

    public HybridFanoutStrategy(
            FanoutOnWriteStrategy writeStrategy,
            FanoutOnReadStrategy readStrategy,
            UserRepository userRepository,
            int celebrityThreshold) {
        this.writeStrategy = writeStrategy;
        this.readStrategy = readStrategy;
        this.userRepository = userRepository;
        this.celebrityThreshold = celebrityThreshold;
    }

    // Convenience constructor with default threshold
    public HybridFanoutStrategy(
            FanoutOnWriteStrategy writeStrategy,
            FanoutOnReadStrategy readStrategy,
            UserRepository userRepository) {
        this(writeStrategy, readStrategy, userRepository, 10_000);
    }

    @Override
    public void fanout(Tweet tweet, List<String> followerIds) {
        // 1. Look up the tweet poster
        User poster = userRepository.findById(tweet.getUserId())
            .orElseThrow(() -> new UserNotFoundException(
                "User not found: " + tweet.getUserId()));

        // 2. Decide strategy based on celebrity status
        boolean isCelebrity = poster.isCelebrity();

        System.out.printf("[HYBRID] User %s (%d followers) → using %s strategy%n",
            poster.getUsername(),
            poster.getFollowerCount(),
            isCelebrity ? "READ" : "WRITE");

        // 3. Delegate to appropriate strategy
        if (isCelebrity) {
            readStrategy.fanout(tweet, followerIds);
        } else {
            writeStrategy.fanout(tweet, followerIds);
        }
    }

    @Override
    public String name() {
        return "HYBRID";
    }

    public int getCelebrityThreshold() {
        return celebrityThreshold;
    }
}
```

### 6.4 FanoutService -- The Orchestrator

```java
/**
 * Orchestrates the fan-out process when a tweet is posted.
 *
 * Responsibilities:
 *   1. Look up the tweet poster
 *   2. Retrieve the poster's follower IDs
 *   3. Delegate to the HybridFanoutStrategy
 *   4. Track stats: count, time taken
 *
 * This service is called by TweetService.postTweet() — it is the
 * bridge between "tweet was created" and "tweet is distributed."
 */
public class FanoutService {

    private final HybridFanoutStrategy fanoutStrategy;
    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    // Stats tracking
    private final AtomicInteger totalFanouts = new AtomicInteger(0);
    private final AtomicLong totalFanoutTimeMs = new AtomicLong(0);

    public FanoutService(
            HybridFanoutStrategy fanoutStrategy,
            FollowRepository followRepository,
            UserRepository userRepository) {
        this.fanoutStrategy = fanoutStrategy;
        this.followRepository = followRepository;
        this.userRepository = userRepository;
    }

    /**
     * Process a newly posted tweet through the fan-out pipeline.
     *
     * @param tweet the tweet to distribute
     */
    public void processTweet(Tweet tweet) {
        long startTime = System.currentTimeMillis();

        // 1. Get poster user
        User poster = userRepository.findById(tweet.getUserId())
            .orElseThrow(() -> new UserNotFoundException(
                "User not found: " + tweet.getUserId()));

        // 2. Get all follower IDs for the poster
        List<String> followerIds = followRepository.getFollowerIds(poster.getUserId());

        System.out.printf("[FANOUT-SERVICE] Processing tweet %s by %s (%d followers)%n",
            tweet.getTweetId(), poster.getUsername(), followerIds.size());

        // 3. Delegate to hybrid strategy (which decides write vs read)
        fanoutStrategy.fanout(tweet, followerIds);

        // 4. Track stats
        long elapsed = System.currentTimeMillis() - startTime;
        totalFanouts.incrementAndGet();
        totalFanoutTimeMs.addAndGet(elapsed);

        System.out.printf("[FANOUT-SERVICE] Completed in %dms. " +
            "Total fanouts: %d, Avg time: %dms%n",
            elapsed, totalFanouts.get(),
            totalFanoutTimeMs.get() / totalFanouts.get());
    }

    // Stats getters for monitoring
    public int getTotalFanouts() { return totalFanouts.get(); }
    public long getAvgFanoutTimeMs() {
        int count = totalFanouts.get();
        return count > 0 ? totalFanoutTimeMs.get() / count : 0;
    }
}
```

### Fan-out Decision Flow

```
                   Tweet Posted
                       |
                       v
              +------------------+
              |  FanoutService   |
              |  .processTweet() |
              +--------+---------+
                       |
                       v
              +------------------+
              | Get poster User  |
              | Get follower IDs |
              +--------+---------+
                       |
                       v
              +------------------+
              | HybridFanout     |
              | Strategy.fanout()|
              +--------+---------+
                       |
              +--------+---------+
              |                  |
    poster.isCelebrity()?        |
              |                  |
         +----+----+       +----+----+
         |  TRUE   |       |  FALSE  |
         v         |       v         |
   +-----------+   |  +-----------+  |
   | FanoutOn  |   |  | FanoutOn  |  |
   | READ      |   |  | WRITE     |  |
   | (NO-OP)   |   |  | Push to   |  |
   | Tweet sits|   |  | all N     |  |
   | in user   |   |  | follower  |  |
   | timeline  |   |  | timelines |  |
   +-----------+   |  +-----------+  |
         |         |        |        |
         v         v        v        v
   At READ time:         Immediately:
   FeedService pulls     Timeline cache
   from celebrity's      has the tweet
   user timeline         for each follower
```

---

## 7. Feed Generation Service -- THE READ PATH

This is where the hybrid fan-out model comes together. The `FeedService.generateFeed()` method is THE interview-critical flow.

### 7.1 FeedService

```java
/**
 * THE feed generation service — the read path.
 *
 * This is where the hybrid fan-out magic happens:
 *   Step 1: Get pre-computed timeline (pushed items from normal users)
 *   Step 2: Find celebrities the user follows
 *   Step 3: Pull celebrity tweets directly
 *   Step 4: Merge pushed + pulled items
 *   Step 5: Rank the merged list
 *   Step 6: Return top N
 *
 * Interview critical: Be able to whiteboard this flow step by step.
 */
public class FeedService {

    private final TimelineCacheRepository timelineCacheRepo;
    private final TweetRepository tweetRepo;
    private final FollowRepository followRepo;
    private final UserRepository userRepo;
    private final FeedRanker feedRanker;

    private static final int CELEBRITY_PULL_LIMIT = 20; // Max tweets to pull per celebrity
    private static final int DEFAULT_FEED_LIMIT = 50;

    public FeedService(
            TimelineCacheRepository timelineCacheRepo,
            TweetRepository tweetRepo,
            FollowRepository followRepo,
            UserRepository userRepo,
            FeedRanker feedRanker) {
        this.timelineCacheRepo = timelineCacheRepo;
        this.tweetRepo = tweetRepo;
        this.followRepo = followRepo;
        this.userRepo = userRepo;
        this.feedRanker = feedRanker;
    }

    /**
     * Generate the home feed for a user.
     *
     * THIS IS THE INTERVIEW ANSWER FOR "How does Twitter build a user's feed?"
     *
     * @param userId the user requesting their feed
     * @param limit  max number of feed items to return
     * @return ranked list of feed items (merged from push + pull)
     */
    public List<FeedItem> generateFeed(String userId, int limit) {
        // Validate user exists
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        // ============================================================
        // STEP 1: Get pre-computed timeline from cache
        //         These are tweets from NORMAL users, pushed via
        //         fan-out-on-write when they posted.
        // ============================================================
        List<FeedItem> preComputedItems = timelineCacheRepo.getTimeline(
            userId, limit * 2); // Fetch extra to account for filtering

        // Filter out soft-deleted tweets
        List<FeedItem> pushedItems = preComputedItems.stream()
            .filter(item -> !item.getTweet().isDeleted())
            .collect(Collectors.toList());

        int pushCount = pushedItems.size();

        // ============================================================
        // STEP 2: Find CELEBRITIES the user follows
        //         These users' tweets were NOT pushed (fan-out-on-read).
        //         We must pull their tweets now.
        // ============================================================
        List<String> followeeIds = followRepo.getFolloweeIds(userId);

        List<String> celebrityFolloweeIds = followeeIds.stream()
            .map(id -> userRepo.findById(id).orElse(null))
            .filter(Objects::nonNull)
            .filter(User::isCelebrity)
            .map(User::getUserId)
            .collect(Collectors.toList());

        // ============================================================
        // STEP 3: Pull latest tweets from each celebrity
        //         This is the "fan-out on read" part — we're doing
        //         the work NOW, at read time.
        // ============================================================
        List<FeedItem> pulledItems = new ArrayList<>();

        for (String celebrityId : celebrityFolloweeIds) {
            List<Tweet> celebrityTweets = tweetRepo.findByUserIdLatest(
                celebrityId, CELEBRITY_PULL_LIMIT);

            for (Tweet tweet : celebrityTweets) {
                if (!tweet.isDeleted()) {
                    pulledItems.add(new FeedItem(tweet, FeedSource.FANOUT_READ));
                }
            }
        }

        int pullCount = pulledItems.size();

        // ============================================================
        // STEP 4: MERGE pre-computed items + celebrity items
        //         This is the hybrid merge — combining push and pull
        //         results into a single unified feed.
        // ============================================================
        List<FeedItem> mergedItems = new ArrayList<>();
        mergedItems.addAll(pushedItems);
        mergedItems.addAll(pulledItems);

        // Deduplicate (same tweet could appear from both paths in edge cases)
        Map<String, FeedItem> deduped = new LinkedHashMap<>();
        for (FeedItem item : mergedItems) {
            deduped.putIfAbsent(item.getTweet().getTweetId(), item);
        }
        List<FeedItem> uniqueItems = new ArrayList<>(deduped.values());

        // ============================================================
        // STEP 5: RANK the merged list using the configured ranker
        //         (chronological, engagement-based, or ML-based)
        // ============================================================
        List<FeedItem> rankedItems = feedRanker.rank(uniqueItems);

        // ============================================================
        // STEP 6: Return top N items
        // ============================================================
        List<FeedItem> feed = rankedItems.stream()
            .limit(limit)
            .collect(Collectors.toList());

        System.out.printf(
            "[FEED] Generated feed for %s: %d pushed + %d pulled = %d merged, " +
            "returning top %d (ranked by %s)%n",
            userId, pushCount, pullCount, uniqueItems.size(),
            feed.size(), feedRanker.name());

        return feed;
    }

    /**
     * Get a user's own timeline (all tweets they posted).
     * This is NOT the home feed — it's the profile page timeline.
     */
    public List<FeedItem> getUserTimeline(String userId, int limit) {
        List<Tweet> userTweets = tweetRepo.findByUserIdLatest(userId, limit);

        return userTweets.stream()
            .filter(tweet -> !tweet.isDeleted())
            .map(tweet -> new FeedItem(tweet, FeedSource.FANOUT_WRITE))
            .sorted() // uses FeedItem.compareTo (by score desc, then time desc)
            .limit(limit)
            .collect(Collectors.toList());
    }
}
```

### Feed Generation Visual Flow

```
User opens Home Feed
        |
        v
  FeedService.generateFeed("user123", 50)
        |
        +---> STEP 1: timelineCacheRepo.getTimeline("user123", 100)
        |       |
        |       v
        |     Pre-computed timeline cache (pushed items):
        |     [Tweet from Alice, Tweet from Bob, Tweet from Charlie, ...]
        |     These were pushed when Alice/Bob/Charlie posted (fan-out-on-write)
        |     Source: FANOUT_WRITE
        |
        +---> STEP 2: followRepo.getFolloweeIds("user123")
        |       |
        |       v
        |     Find which followees are celebrities:
        |     [followee "elonmusk" (150M followers) -> CELEBRITY]
        |     [followee "taylorswift" (90M followers) -> CELEBRITY]
        |     [followee "alice" (500 followers) -> NORMAL, already in cache]
        |
        +---> STEP 3: Pull celebrity tweets
        |       |
        |       +---> tweetRepo.findByUserIdLatest("elonmusk", 20)
        |       |       --> [Tweet E1, Tweet E2, Tweet E3]
        |       |
        |       +---> tweetRepo.findByUserIdLatest("taylorswift", 20)
        |               --> [Tweet T1, Tweet T2]
        |       
        |       Source: FANOUT_READ
        |
        +---> STEP 4: MERGE
        |       |
        |       v
        |     [Alice_tweet, Bob_tweet, Charlie_tweet,  <-- pushed
        |      E1, E2, E3, T1, T2]                     <-- pulled
        |     Deduplicate by tweetId
        |
        +---> STEP 5: RANK (using EngagementRanker)
        |       |
        |       v
        |     Score each item:
        |       E1 (10K likes, 5K RTs) -> score: 95.7
        |       T1 (8K likes, 3K RTs)  -> score: 82.3
        |       Alice_tweet (50 likes)  -> score: 12.1
        |       ...sorted by score descending
        |
        +---> STEP 6: Return top 50
                |
                v
              Final feed: [E1, T1, E2, Bob_tweet, T2, Alice_tweet, ...]
              Mix of celebrity pulls and normal pushes, ranked by engagement
```

---

## 8. Ranking Implementations

### 8.1 ChronologicalRanker

```java
/**
 * Simple chronological ranking — sort by creation time, newest first.
 * This is what Twitter used before the algorithmic timeline (pre-2016).
 *
 * Score = negative epoch seconds (so newer = higher score)
 */
public class ChronologicalRanker implements FeedRanker {

    @Override
    public List<FeedItem> rank(List<FeedItem> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();

        for (FeedItem item : items) {
            // Score = epoch seconds (higher = newer = better)
            long epochSecond = item.getTweet().getCreatedAt()
                .atZone(ZoneId.systemDefault()).toEpochSecond();
            item.setScore(epochSecond);
        }

        return items.stream()
            .sorted()  // FeedItem.compareTo: higher score first
            .collect(Collectors.toList());
    }

    @Override
    public String name() {
        return "CHRONOLOGICAL";
    }
}
```

### 8.2 EngagementRanker

```java
/**
 * Engagement-based ranking — the modern algorithmic feed.
 *
 * FORMULA:
 *   score = timeFreshness * (likes * 1.0 + retweets * 2.0 + replies * 1.5)
 *
 * Where:
 *   timeFreshness = 1.0 / (1.0 + hoursAge * decayFactor)
 *     - A 1-hour-old tweet: freshness ~= 0.91
 *     - A 6-hour-old tweet: freshness ~= 0.63
 *     - A 24-hour-old tweet: freshness ~= 0.29
 *
 * Retweets are weighted 2x because they signal stronger endorsement.
 * Replies are weighted 1.5x because they indicate conversation (engagement).
 *
 * Interview tip: Explain why retweets > replies > likes in weight.
 * Retweets amplify reach, replies indicate depth, likes are passive.
 */
public class EngagementRanker implements FeedRanker {

    private static final double LIKE_WEIGHT = 1.0;
    private static final double RETWEET_WEIGHT = 2.0;
    private static final double REPLY_WEIGHT = 1.5;
    private static final double TIME_DECAY_FACTOR = 0.1; // controls how fast old tweets decay

    @Override
    public List<FeedItem> rank(List<FeedItem> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();

        LocalDateTime now = LocalDateTime.now();

        for (FeedItem item : items) {
            double score = RankingScore.calculateEngagementScore(item.getTweet(), now);
            item.setScore(score);
        }

        return items.stream()
            .sorted()  // FeedItem.compareTo: higher score first
            .collect(Collectors.toList());
    }

    @Override
    public String name() {
        return "ENGAGEMENT";
    }
}
```

### 8.3 RankingScore Utility

```java
/**
 * Utility class with static scoring methods.
 * Extracted to allow reuse across different rankers and testing.
 */
public final class RankingScore {

    private static final double LIKE_WEIGHT = 1.0;
    private static final double RETWEET_WEIGHT = 2.0;
    private static final double REPLY_WEIGHT = 1.5;
    private static final double TIME_DECAY_FACTOR = 0.1;

    private RankingScore() {} // Prevent instantiation

    /**
     * Calculate engagement score for a tweet.
     *
     * score = timeFreshness * engagementScore
     *
     * @param tweet the tweet to score
     * @param now   current time for freshness calculation
     * @return computed score (higher = better)
     */
    public static double calculateEngagementScore(Tweet tweet, LocalDateTime now) {
        // 1. Calculate engagement component
        double engagement =
            tweet.getLikeCount()    * LIKE_WEIGHT +
            tweet.getRetweetCount() * RETWEET_WEIGHT +
            tweet.getReplyCount()   * REPLY_WEIGHT;

        // 2. Calculate time freshness (decays over hours)
        double hoursAge = Duration.between(tweet.getCreatedAt(), now).toMinutes() / 60.0;
        double timeFreshness = 1.0 / (1.0 + hoursAge * TIME_DECAY_FACTOR);

        // 3. Combined score
        return timeFreshness * (engagement + 1.0); // +1 to avoid zero score for new tweets
    }

    /**
     * Calculate chronological score (just epoch time).
     */
    public static double calculateChronologicalScore(Tweet tweet) {
        return tweet.getCreatedAt()
            .atZone(ZoneId.systemDefault())
            .toEpochSecond();
    }

    /**
     * Normalize a score to 0-100 range.
     */
    public static double normalize(double score, double maxScore) {
        if (maxScore == 0) return 0;
        return (score / maxScore) * 100.0;
    }
}
```

### Ranking Score Decay Visualization

```
Score Freshness Decay Over Time:

Freshness
  1.0 |*
      |  *
  0.8 |    *
      |       *
  0.6 |          *
      |              *
  0.4 |                   *
      |                         *
  0.2 |                               *
      |                                      *          *
  0.0 +-------+-------+-------+-------+-------+-------+-----> Hours
      0       4       8       12      16      20      24

Formula: freshness = 1.0 / (1.0 + hoursAge * 0.1)

At  0h: 1.000    (brand new)
At  1h: 0.909    (still very fresh)
At  6h: 0.625    (moderately fresh)
At 12h: 0.455    (starting to decay)
At 24h: 0.294    (old, needs high engagement to rank well)
At 48h: 0.172    (stale)

Engagement multiplier examples:
  Tweet with 100 likes, 50 RTs, 30 replies at 2h old:
    engagement = 100*1 + 50*2 + 30*1.5 = 245
    freshness  = 1/(1 + 2*0.1) = 0.833
    score      = 0.833 * (245 + 1) = 204.9

  Tweet with 5 likes, 0 RTs, 0 replies at 0.5h old:
    engagement = 5*1 + 0 + 0 = 5
    freshness  = 1/(1 + 0.5*0.1) = 0.952
    score      = 0.952 * (5 + 1) = 5.71
```

---

## 9. Trending Service

### 9.1 HashtagExtractor

```java
/**
 * Extracts hashtags from tweet content using regex.
 *
 * Rules:
 *   - Hashtag starts with #
 *   - Followed by one or more word characters (letters, digits, underscore)
 *   - Normalized to lowercase
 *   - Duplicates within the same tweet are removed
 *
 * Examples:
 *   "Hello #World and #Java17 rocks" -> ["world", "java17"]
 *   "#AI #ai #Ai are the same"       -> ["ai"]
 *   "No hashtags here"               -> []
 *   "#" or "# space"                 -> []
 */
public class HashtagExtractor {

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#(\\w+)");

    /**
     * Extract unique, lowercase hashtags from content.
     *
     * @param content tweet text
     * @return list of unique hashtags (without # prefix)
     */
    public static List<String> extractHashtags(String content) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        Set<String> seen = new LinkedHashSet<>(); // preserve order, deduplicate
        Matcher matcher = HASHTAG_PATTERN.matcher(content);

        while (matcher.find()) {
            seen.add(matcher.group(1).toLowerCase());
        }

        return new ArrayList<>(seen);
    }
}
```

### 9.2 TrendingService

```java
/**
 * Manages trending topics detection and scoring.
 *
 * How trending works:
 *   1. When a tweet is posted, its hashtags are extracted and counted
 *   2. Counts are tracked per TrendingWindow (1h, 6h, 24h)
 *   3. Score is velocity-based: how fast is this hashtag accelerating?
 *      score = (currentCount - baselineCount) / max(baselineCount, 1)
 *   4. Top K hashtags by score are "trending"
 *
 * Why velocity, not raw count?
 *   - Raw count favors always-popular hashtags (#love, #music)
 *   - Velocity detects SPIKES — sudden surges in usage
 *   - "#SuperBowl" goes from 100/hr to 50,000/hr = velocity spike = TRENDING
 *   - "#love" stays at 10,000/hr = no spike = NOT trending
 */
public class TrendingService {

    private final TrendingRepository trendingRepo;

    // Baseline is the count from the previous window (for velocity calculation)
    private final ConcurrentHashMap<String, Long> baselineCounts = new ConcurrentHashMap<>();

    public TrendingService(TrendingRepository trendingRepo) {
        this.trendingRepo = trendingRepo;
    }

    /**
     * Record hashtags from a newly posted tweet.
     * Called by TweetService after a tweet is created.
     *
     * @param tweet the newly posted tweet
     */
    public void recordHashtags(Tweet tweet) {
        List<String> hashtags = HashtagExtractor.extractHashtags(tweet.getContent());

        if (hashtags.isEmpty()) return;

        for (String hashtag : hashtags) {
            // Increment count in all windows
            for (TrendingWindow window : TrendingWindow.values()) {
                trendingRepo.incrementCount(hashtag, window);
            }
        }

        System.out.printf("[TRENDING] Recorded %d hashtags from tweet %s: %s%n",
            hashtags.size(), tweet.getTweetId(), hashtags);
    }

    /**
     * Get trending topics for a specific time window.
     *
     * @param window time window (HOUR_1, HOUR_6, HOUR_24)
     * @param limit  max number of trending topics to return
     * @return top trending topics sorted by score descending
     */
    public List<TrendingTopic> getTrending(TrendingWindow window, int limit) {
        List<TrendingTopic> topics = trendingRepo.getTopTrending(window, limit * 2);

        // Calculate velocity-based scores
        for (TrendingTopic topic : topics) {
            long baseline = baselineCounts.getOrDefault(
                topic.getHashtag() + ":" + window.name(), 0L);
            double score = calculateScore(topic.getCount(), baseline);
            topic.setScore(score);
        }

        // Sort by score and return top K
        return topics.stream()
            .sorted() // TrendingTopic.compareTo: higher score first
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Velocity-based trending score.
     *
     * score = (currentCount - baselineCount) / max(baselineCount, 1)
     *
     * Examples:
     *   current=50000, baseline=100  -> score = 499.0  (MASSIVE spike)
     *   current=10000, baseline=9000 -> score = 0.11   (barely trending)
     *   current=500,   baseline=0    -> score = 500.0  (brand new hashtag)
     *
     * @param currentCount  count in the current window
     * @param baselineCount count in the previous window (or historical average)
     * @return velocity score
     */
    public double calculateScore(long currentCount, long baselineCount) {
        return (double) (currentCount - baselineCount) / Math.max(baselineCount, 1);
    }

    /**
     * Get top trending topics (convenience method, defaults to HOUR_1 window).
     */
    public List<TrendingTopic> getTopTrending(int limit) {
        return getTrending(TrendingWindow.HOUR_1, limit);
    }

    /**
     * Snapshot current counts as baseline for the next window.
     * Called periodically (e.g., every hour) by a scheduler.
     */
    public void snapshotBaseline(TrendingWindow window) {
        List<TrendingTopic> current = trendingRepo.getTopTrending(window, 1000);
        for (TrendingTopic topic : current) {
            baselineCounts.put(
                topic.getHashtag() + ":" + window.name(),
                topic.getCount());
        }
        System.out.printf("[TRENDING] Baseline snapshot taken for %s: %d hashtags%n",
            window, current.size());
    }
}
```

---

## 10. Concurrency

### Concurrency Strategy Overview

| Data Structure | Used In | Why |
|----------------|---------|-----|
| `ConcurrentHashMap` | All InMemory repositories | Thread-safe reads/writes, no locking for reads, segment-level locking for writes |
| `CopyOnWriteArrayList` | Follower lists in `InMemoryFollowRepository` | Reads vastly outnumber writes; iteration is lock-free |
| `AtomicInteger` | `User.followerCount`, `Tweet.likeCount`, `Tweet.retweetCount`, `Tweet.replyCount` | Lock-free atomic increments for counters |
| `AtomicLong` | `FanoutService.totalFanoutTimeMs` | Lock-free stat tracking |
| `volatile` | `Tweet.deleted` | Visibility guarantee for soft-delete flag across threads |

### InMemory Repository Implementations

```java
/**
 * Thread-safe in-memory timeline cache.
 *
 * In production, this would be Redis sorted sets:
 *   ZADD user:{userId}:timeline {score} {tweetId}
 *   ZREVRANGE user:{userId}:timeline 0 49
 *
 * Here we use ConcurrentHashMap<String, CopyOnWriteArrayList<FeedItem>>
 * to simulate the same behavior with thread safety.
 */
public class InMemoryTimelineCacheRepository implements TimelineCacheRepository {

    // userId -> list of FeedItems (pre-computed timeline)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<FeedItem>> cache =
        new ConcurrentHashMap<>();

    private static final int MAX_TIMELINE_SIZE = 800; // Cap to prevent memory bloat

    @Override
    public void addToTimeline(String userId, FeedItem item) {
        cache.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>())
             .add(item);

        // Evict oldest if over capacity (LRU-like)
        CopyOnWriteArrayList<FeedItem> timeline = cache.get(userId);
        if (timeline.size() > MAX_TIMELINE_SIZE) {
            // Remove oldest items (items at the beginning of the list)
            int excess = timeline.size() - MAX_TIMELINE_SIZE;
            for (int i = 0; i < excess; i++) {
                timeline.remove(0);
            }
        }
    }

    @Override
    public List<FeedItem> getTimeline(String userId, int limit) {
        CopyOnWriteArrayList<FeedItem> timeline = cache.get(userId);
        if (timeline == null || timeline.isEmpty()) {
            return Collections.emptyList();
        }

        // Return newest first, up to limit
        return timeline.stream()
            .sorted(Comparator.comparing(
                (FeedItem item) -> item.getTweet().getCreatedAt()).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    @Override
    public void removeFromTimeline(String userId, String tweetId) {
        CopyOnWriteArrayList<FeedItem> timeline = cache.get(userId);
        if (timeline != null) {
            timeline.removeIf(item ->
                item.getTweet().getTweetId().equals(tweetId));
        }
    }

    @Override
    public int getTimelineSize(String userId) {
        CopyOnWriteArrayList<FeedItem> timeline = cache.get(userId);
        return timeline != null ? timeline.size() : 0;
    }
}
```

```java
public class InMemoryTweetRepository implements TweetRepository {

    private final ConcurrentHashMap<String, Tweet> tweets = new ConcurrentHashMap<>();
    // Secondary index: userId -> list of tweetIds (for user timeline queries)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> userTweetIndex =
        new ConcurrentHashMap<>();

    @Override
    public Tweet save(Tweet tweet) {
        tweets.put(tweet.getTweetId(), tweet);
        userTweetIndex.computeIfAbsent(tweet.getUserId(), k -> new CopyOnWriteArrayList<>())
                      .add(tweet.getTweetId());
        return tweet;
    }

    @Override
    public Optional<Tweet> findById(String tweetId) {
        return Optional.ofNullable(tweets.get(tweetId));
    }

    @Override
    public List<Tweet> findByUserId(String userId) {
        CopyOnWriteArrayList<String> tweetIds = userTweetIndex.get(userId);
        if (tweetIds == null) return Collections.emptyList();

        return tweetIds.stream()
            .map(tweets::get)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(Tweet::getCreatedAt).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public List<Tweet> findByUserIdLatest(String userId, int limit) {
        return findByUserId(userId).stream()
            .limit(limit)
            .collect(Collectors.toList());
    }

    @Override
    public void delete(String tweetId) {
        Tweet tweet = tweets.get(tweetId);
        if (tweet != null) {
            tweet.softDelete(); // Soft delete, not physical removal
        }
    }

    @Override
    public boolean existsById(String tweetId) {
        return tweets.containsKey(tweetId);
    }
}
```

```java
public class InMemoryFollowRepository implements FollowRepository {

    // followeeId -> set of followerIds (who follows this user)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> followers =
        new ConcurrentHashMap<>();
    // followerId -> set of followeeIds (who this user follows)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> following =
        new ConcurrentHashMap<>();

    @Override
    public Follow save(Follow follow) {
        followers.computeIfAbsent(follow.getFolloweeId(), k -> new CopyOnWriteArrayList<>())
                 .addIfAbsent(follow.getFollowerId());
        following.computeIfAbsent(follow.getFollowerId(), k -> new CopyOnWriteArrayList<>())
                 .addIfAbsent(follow.getFolloweeId());
        return follow;
    }

    @Override
    public void delete(String followerId, String followeeId) {
        CopyOnWriteArrayList<String> followerList = followers.get(followeeId);
        if (followerList != null) followerList.remove(followerId);

        CopyOnWriteArrayList<String> followingList = following.get(followerId);
        if (followingList != null) followingList.remove(followeeId);
    }

    @Override
    public List<String> getFollowerIds(String userId) {
        CopyOnWriteArrayList<String> list = followers.get(userId);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    @Override
    public List<String> getFolloweeIds(String userId) {
        CopyOnWriteArrayList<String> list = following.get(userId);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    @Override
    public boolean isFollowing(String followerId, String followeeId) {
        CopyOnWriteArrayList<String> list = following.get(followerId);
        return list != null && list.contains(followeeId);
    }

    @Override
    public int getFollowerCount(String userId) {
        CopyOnWriteArrayList<String> list = followers.get(userId);
        return list != null ? list.size() : 0;
    }
}
```

```java
public class InMemoryTrendingRepository implements TrendingRepository {

    // "hashtag:WINDOW" -> TrendingTopic
    private final ConcurrentHashMap<String, TrendingTopic> topics =
        new ConcurrentHashMap<>();

    private String key(String hashtag, TrendingWindow window) {
        return hashtag.toLowerCase() + ":" + window.name();
    }

    @Override
    public void incrementCount(String hashtag, TrendingWindow window) {
        String k = key(hashtag, window);
        topics.computeIfAbsent(k, key ->
            new TrendingTopic(hashtag.toLowerCase(), 0, window))
            .incrementCount();
    }

    @Override
    public List<TrendingTopic> getTopTrending(TrendingWindow window, int limit) {
        return topics.values().stream()
            .filter(t -> t.getWindow() == window)
            .sorted(Comparator.comparingLong(TrendingTopic::getCount).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    @Override
    public long getCount(String hashtag, TrendingWindow window) {
        TrendingTopic topic = topics.get(key(hashtag, window));
        return topic != null ? topic.getCount() : 0;
    }

    @Override
    public void resetWindow(TrendingWindow window) {
        topics.entrySet().removeIf(e -> e.getKey().endsWith(":" + window.name()));
    }
}
```

```java
public class InMemoryUserRepository implements UserRepository {

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> usernameIndex = new ConcurrentHashMap<>();

    @Override
    public User save(User user) {
        users.put(user.getUserId(), user);
        usernameIndex.put(user.getUsername().toLowerCase(), user.getUserId());
        return user;
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        String userId = usernameIndex.get(username.toLowerCase());
        return userId != null ? findById(userId) : Optional.empty();
    }

    @Override
    public boolean existsById(String userId) {
        return users.containsKey(userId);
    }

    @Override
    public List<User> findAll() {
        return new ArrayList<>(users.values());
    }
}
```

---

## 11. Edge Cases Handling in Code

### 11.1 Celebrity Detection (Dynamic Transition)

```java
/**
 * EDGE CASE: A normal user goes viral and crosses the 10K threshold
 * mid-session. Their UserType must update dynamically.
 *
 * Scenario: @alice has 9,999 followers, posts a tweet (fan-out on write).
 * Then gains 2 more followers (now 10,001). Next tweet should use fan-out on read.
 */

// In User.java
public int incrementFollowers() {
    int newCount = followerCount.incrementAndGet();
    // Dynamically re-evaluate celebrity status
    if (newCount >= CELEBRITY_THRESHOLD && userType == UserType.NORMAL) {
        userType = UserType.CELEBRITY;
        System.out.printf("[USER-TYPE] %s crossed celebrity threshold (%d followers)%n",
            username, newCount);
    }
    return newCount;
}

// In HybridFanoutStrategy.fanout()
// No special handling needed — isCelebrity() is checked at tweet-post time,
// so the transition is automatic. The next tweet after threshold will use
// fan-out-on-read.
```

### 11.2 Tweet Deletion (Soft Delete)

```java
/**
 * EDGE CASE: Tweet is deleted after being pushed to 10K timelines.
 * We cannot un-push from all caches (too expensive).
 * Solution: Soft delete + filter at read time.
 */

// In TweetService.java
public void deleteTweet(String tweetId, String userId) {
    Tweet tweet = tweetRepo.findById(tweetId)
        .orElseThrow(() -> new TweetNotFoundException("Tweet not found: " + tweetId));

    // Verify ownership
    if (!tweet.getUserId().equals(userId)) {
        throw new FeedException("Cannot delete another user's tweet");
    }

    // Soft delete — sets deleted = true
    tweet.softDelete();
    System.out.printf("[DELETE] Soft-deleted tweet %s. " +
        "Will be filtered at read time.%n", tweetId);

    // Note: We do NOT remove from timeline caches.
    // Reason: O(followers) removal is too expensive.
    // The soft-delete flag is checked in FeedService.generateFeed() at step 1:
    //   preComputedItems.stream()
    //       .filter(item -> !item.getTweet().isDeleted())  <-- filtered here
}
```

### 11.3 Unfollow (Stale Cache Entries)

```java
/**
 * EDGE CASE: User unfollows someone. Tweets from that user are still
 * in the follower's timeline cache (pushed before the unfollow).
 *
 * Options:
 *   A) Remove all tweets from cache (expensive, O(cache_size))
 *   B) Let them expire via TTL (eventual consistency, simple)
 *   C) Filter at read time (check if still following)
 *
 * Production choice: Option B (TTL) with Option C as fallback.
 * For this implementation: Option C (filter at read time).
 */

// In FollowService.java
public void unfollow(String followerId, String followeeId) {
    // Remove from social graph
    followRepo.delete(followerId, followeeId);

    // Decrement counters
    userRepo.findById(followeeId).ifPresent(User::decrementFollowers);
    userRepo.findById(followerId).ifPresent(User::decrementFollowing);

    System.out.printf("[UNFOLLOW] %s unfollowed %s. " +
        "Stale cache entries will be filtered at read time.%n",
        followerId, followeeId);

    // Note: We do NOT remove tweets from timeline cache.
    // They will either:
    //   - Expire via TTL in production (Redis EXPIRE)
    //   - Be filtered in generateFeed() if we add a "still following?" check
}

// Enhanced FeedService.generateFeed() with unfollow filtering
// Add after Step 1, before merge:
Set<String> currentFolloweeIds = new HashSet<>(followRepo.getFolloweeIds(userId));
List<FeedItem> filteredPushedItems = pushedItems.stream()
    .filter(item -> currentFolloweeIds.contains(item.getTweet().getUserId()))
    .collect(Collectors.toList());
```

### 11.4 New User Cold Start

```java
/**
 * EDGE CASE: Brand new user with no followers and no follows.
 * Their timeline cache is empty. The pull path returns nothing.
 * Feed would be completely empty.
 *
 * Solution: Cold start heuristic.
 */

// In FeedService.java
public List<FeedItem> generateFeed(String userId, int limit) {
    // ... normal flow ...

    // COLD START CHECK: If merged feed is empty, provide bootstrap content
    if (uniqueItems.isEmpty()) {
        System.out.printf("[FEED] Cold start detected for user %s. " +
            "Providing trending/popular content.%n", userId);
        return getColdStartFeed(limit);
    }

    // ... continue with ranking ...
}

/**
 * Cold start feed: show trending/popular tweets for new users.
 * In production, this might use:
 *   - Trending tweets from the last 24 hours
 *   - Popular tweets in user's region/language
 *   - Suggested follows based on signup interests
 */
private List<FeedItem> getColdStartFeed(int limit) {
    // Pull globally popular tweets (simplified: just get recent tweets with high engagement)
    // In production, this would be a separate "Explore" or "For You" service
    return Collections.emptyList(); // Placeholder for cold start logic
}
```

### 11.5 Viral Normal User

```java
/**
 * EDGE CASE: A normal user (@alice, 500 followers) tweets something
 * that goes viral. She gains 100K new followers in an hour.
 *
 * What happens:
 *   1. Original tweet was fan-out-on-write to her 500 followers (already done)
 *   2. Her 100K NEW followers did NOT get the tweet pushed (they followed after)
 *   3. She crosses the 10K threshold and becomes CELEBRITY
 *   4. Her NEXT tweet will use fan-out-on-read
 *
 * The viral tweet is NOT retroactively pushed. This is acceptable because:
 *   - New followers will see it if they visit her profile (user timeline)
 *   - The engagement metrics are still tracked correctly
 *   - Retroactive fan-out would be extremely expensive and unnecessary
 *
 * No special code needed — the system handles this naturally.
 */
```

### 11.6 Self-Tweet in Feed

```java
/**
 * EDGE CASE: Should users see their own tweets in their home feed?
 * Twitter says yes — for consistency and feedback.
 *
 * Implementation: When a user posts, their own timeline cache
 * also receives the tweet (they are implicitly a "follower of themselves").
 */

// In TweetService.java
public Tweet postTweet(String userId, String content, List<String> mediaUrls) {
    // ... create tweet ...

    // Add to poster's own timeline cache (so they see it in their feed)
    FeedItem selfItem = new FeedItem(tweet, FeedSource.FANOUT_WRITE);
    timelineCacheRepo.addToTimeline(userId, selfItem);

    // Trigger fan-out to actual followers
    fanoutService.processTweet(tweet);

    return tweet;
}
```

### Edge Cases Summary Table

| Edge Case | Trigger | Handling | Cost |
|-----------|---------|----------|------|
| Celebrity detection | followerCount crosses 10K | Dynamic UserType transition | O(1) |
| Tweet deletion | User deletes tweet | Soft delete flag, filter at read | O(1) write, O(N) filtered reads |
| Unfollow | User unfollows | Remove from graph, stale entries expire/filter | O(1) |
| Cold start | New user, empty feed | Trending/popular bootstrap content | O(trending_query) |
| Viral normal user | Normal user gains 100K followers | Next tweet uses read strategy | O(1) transition |
| Self-tweet | User posts tweet | Push to own timeline cache | O(1) |
| Duplicate detection | Same tweet from push + pull paths | Dedup by tweetId in generateFeed | O(N) |
| Rate limiting | User posts too fast | Check last post time, throw if < threshold | O(1) |

---

## 12. Sample Workflows

### Workflow 1: Normal User Posts Tweet (Fan-out on Write)

```
Actor: @alice (500 followers, NORMAL user)
Action: Posts "Hello #java world!"

   @alice                  TweetService             FanoutService       HybridFanout       FanoutOnWrite      TimelineCache
     |                          |                        |                   |                   |                  |
     | 1. postTweet("Hello      |                        |                   |                   |                  |
     |    #java world!")        |                        |                   |                   |                  |
     |------------------------->|                        |                   |                   |                  |
     |                          | 2. Build Tweet via     |                   |                   |                  |
     |                          |    Builder pattern     |                   |                   |                  |
     |                          | 3. Extract hashtags    |                   |                   |                  |
     |                          |    -> ["java"]         |                   |                   |                  |
     |                          | 4. tweetRepo.save()    |                   |                   |                  |
     |                          |                        |                   |                   |                  |
     |                          | 5. processTweet(tweet) |                   |                   |                  |
     |                          |----------------------->|                   |                   |                  |
     |                          |                        | 6. getFollowerIds |                   |                  |
     |                          |                        |    -> 500 IDs     |                   |                  |
     |                          |                        |                   |                   |                  |
     |                          |                        | 7. strategy       |                   |                  |
     |                          |                        |    .fanout()      |                   |                  |
     |                          |                        |------------------>|                   |                  |
     |                          |                        |                   | 8. isCelebrity()  |                  |
     |                          |                        |                   |    -> false (500)  |                  |
     |                          |                        |                   |                   |                  |
     |                          |                        |                   | 9. Delegate to    |                  |
     |                          |                        |                   |    WRITE strategy  |                  |
     |                          |                        |                   |------------------>|                  |
     |                          |                        |                   |                   | 10. For each of  |
     |                          |                        |                   |                   |     500 followers|
     |                          |                        |                   |                   |     addToTimeline|
     |                          |                        |                   |                   |----------------->|
     |                          |                        |                   |                   |                  | 11. Push FeedItem
     |                          |                        |                   |                   |                  |     to each
     |                          |                        |                   |                   |                  |     follower's
     |                          |                        |                   |                   |                  |     cache
     |                          |                        |                   |                   |                  |
     |                          | 12. recordHashtags()   |                   |                   |                  |
     |                          |    -> TrendingService  |                   |                   |                  |
     |                          |    increment("java")   |                   |                   |                  |
     |                          |                        |                   |                   |                  |
     |  13. Return Tweet        |                        |                   |                   |                  |
     |<-------------------------|                        |                   |                   |                  |

Console output:
  [HYBRID] User alice (500 followers) → using WRITE strategy
  [FANOUT-WRITE] Pushed tweet t_001 to 500 timelines
  [FANOUT-SERVICE] Completed in 12ms. Total fanouts: 1, Avg time: 12ms
  [TRENDING] Recorded 1 hashtags from tweet t_001: [java]
```

### Workflow 2: Celebrity Posts Tweet (Fan-out on Read)

```
Actor: @elonmusk (150,000,000 followers, CELEBRITY)
Action: Posts "Rockets are cool #SpaceX"

   @elonmusk              TweetService             FanoutService       HybridFanout       FanoutOnRead
     |                          |                        |                   |                   |
     | 1. postTweet("Rockets    |                        |                   |                   |
     |    are cool #SpaceX")    |                        |                   |                   |
     |------------------------->|                        |                   |                   |
     |                          | 2. Build Tweet         |                   |                   |
     |                          | 3. Extract hashtags    |                   |                   |
     |                          |    -> ["spacex"]       |                   |                   |
     |                          | 4. tweetRepo.save()    |                   |                   |
     |                          |                        |                   |                   |
     |                          | 5. processTweet(tweet) |                   |                   |
     |                          |----------------------->|                   |                   |
     |                          |                        | 6. getFollowerIds |                   |
     |                          |                        |    -> 150M IDs    |                   |
     |                          |                        |                   |                   |
     |                          |                        | 7. strategy       |                   |
     |                          |                        |    .fanout()      |                   |
     |                          |                        |------------------>|                   |
     |                          |                        |                   | 8. isCelebrity()  |
     |                          |                        |                   |    -> TRUE (150M)  |
     |                          |                        |                   |                   |
     |                          |                        |                   | 9. Delegate to    |
     |                          |                        |                   |    READ strategy   |
     |                          |                        |                   |------------------>|
     |                          |                        |                   |                   | 10. NO-OP!
     |                          |                        |                   |                   |     Tweet stays
     |                          |                        |                   |                   |     in @elonmusk's
     |                          |                        |                   |                   |     user timeline
     |                          |                        |                   |                   |     ONLY.
     |                          |                        |                   |                   |
     |  11. Return Tweet        |                        |                   |                   |
     |<-------------------------|                        |                   |                   |

Console output:
  [HYBRID] User elonmusk (150000000 followers) → using READ strategy
  [FANOUT-READ] Tweet t_002 by celebrity — skipping push, will be pulled at read time
  [FANOUT-SERVICE] Completed in 1ms. Total fanouts: 2, Avg time: 6ms
  [TRENDING] Recorded 1 hashtags from tweet t_002: [spacex]

NOTE: Zero writes to follower timelines. Tweet will be pulled when followers
      open their feed (see Workflow 3).
```

### Workflow 3: User Opens Feed (Hybrid Merge)

```
Actor: @bob (follows @alice [NORMAL, 500 followers] and @elonmusk [CELEBRITY, 150M followers])
Action: Opens home feed

   @bob                    FeedService              TimelineCache        TweetRepo          FollowRepo
     |                          |                        |                   |                   |
     | 1. generateFeed          |                        |                   |                   |
     |    ("bob", 50)           |                        |                   |                   |
     |------------------------->|                        |                   |                   |
     |                          |                        |                   |                   |
     |                          | STEP 1: Get pre-computed timeline (PUSHED items)              |
     |                          |------->getTimeline("bob", 100)                                |
     |                          |        |                                                      |
     |                          |<-------|                                                      |
     |                          | Returns: [alice_tweet_1, alice_tweet_2, ...]                  |
     |                          | Source: FANOUT_WRITE (these were pushed when alice tweeted)    |
     |                          |                        |                   |                   |
     |                          | STEP 2: Find celebrities bob follows      |                   |
     |                          |---------------------------------------------------------------->|
     |                          |                        |                   | getFolloweeIds   |
     |                          |<----------------------------------------------------------------|
     |                          | Returns: ["alice", "elonmusk"]             |                   |
     |                          | Filter: alice.isCelebrity()=false, skip   |                   |
     |                          |         elonmusk.isCelebrity()=true, PULL! |                  |
     |                          |                        |                   |                   |
     |                          | STEP 3: Pull celebrity tweets             |                   |
     |                          |--------------------------------------->findByUserIdLatest     |
     |                          |                        |              ("elonmusk", 20)        |
     |                          |<---------------------------------------|                      |
     |                          | Returns: [elon_tweet_1, elon_tweet_2, elon_tweet_3]          |
     |                          | Source: FANOUT_READ (pulled right now)                        |
     |                          |                        |                   |                   |
     |                          | STEP 4: MERGE                              |                  |
     |                          | pushed:  [alice_t1, alice_t2]              |                  |
     |                          | pulled:  [elon_t1, elon_t2, elon_t3]      |                  |
     |                          | merged:  [alice_t1, alice_t2, elon_t1, elon_t2, elon_t3]     |
     |                          | Dedup by tweetId                           |                  |
     |                          |                        |                   |                   |
     |                          | STEP 5: RANK (EngagementRanker)            |                  |
     |                          | elon_t1:  score=204.9 (10K likes, 5K RTs) |                  |
     |                          | elon_t2:  score=95.3  (3K likes, 1K RTs)  |                  |
     |                          | alice_t1: score=12.1  (50 likes)           |                  |
     |                          | elon_t3:  score=8.7   (old, low engagement)|                  |
     |                          | alice_t2: score=5.2   (20 likes)           |                  |
     |                          |                        |                   |                   |
     |                          | STEP 6: Return top 50  |                   |                   |
     |  2. Return feed          |                        |                   |                   |
     |<-------------------------|                        |                   |                   |
     | [elon_t1, elon_t2,       |                        |                   |                   |
     |  alice_t1, elon_t3,      |                        |                   |                   |
     |  alice_t2]               |                        |                   |                   |

Console output:
  [FEED] Generated feed for bob: 2 pushed + 3 pulled = 5 merged, returning top 5 (ranked by ENGAGEMENT)
```

### Workflow 4: User Likes a Tweet

```
Actor: @bob
Action: Likes @alice's tweet (tweet_id = "t_001")

   @bob                    TweetService             TweetRepo
     |                          |                        |
     | 1. likeTweet("t_001")    |                        |
     |------------------------->|                        |
     |                          | 2. findById("t_001")   |
     |                          |----------------------->|
     |                          |<-----------------------|
     |                          | 3. tweet.incrementLikes()  <-- AtomicInteger.incrementAndGet()
     |                          |    (thread-safe, no lock)
     |                          |                        |
     |  4. Return new likeCount |                        |
     |     (e.g., 51)           |                        |
     |<-------------------------|                        |

// In TweetService.java
public int likeTweet(String tweetId) {
    Tweet tweet = tweetRepo.findById(tweetId)
        .orElseThrow(() -> new TweetNotFoundException("Tweet not found: " + tweetId));

    if (tweet.isDeleted()) {
        throw new FeedException("Cannot like a deleted tweet");
    }

    int newCount = tweet.incrementLikes();
    System.out.printf("[LIKE] Tweet %s now has %d likes%n", tweetId, newCount);
    return newCount;
}

Note: The like count change is reflected IMMEDIATELY in ranking
      because Tweet uses AtomicInteger — no cache invalidation needed.
      FeedItem holds a reference to the SAME Tweet object.
```

### Workflow 5: Trending Detection

```
Actor: Multiple users
Action: Many users tweet about #WorldCup

   User1 tweets "#WorldCup is starting!"
   User2 tweets "Excited for #WorldCup #football"
   User3 tweets "#WorldCup predictions thread"
   ...
   (1000 tweets in 1 hour)

   TweetService             TrendingService          TrendingRepo         HashtagExtractor
     |                          |                        |                       |
     | 1. For EACH tweet:       |                        |                       |
     |    recordHashtags(tweet) |                        |                       |
     |------------------------->|                        |                       |
     |                          | 2. extractHashtags     |                       |
     |                          |    (tweet.content)     |                       |
     |                          |----------------------------------------------->|
     |                          |<-----------------------------------------------|
     |                          | Returns: ["worldcup"]  |                       |
     |                          |                        |                       |
     |                          | 3. For each hashtag,   |                       |
     |                          |    for each window:    |                       |
     |                          |    incrementCount      |                       |
     |                          |    ("worldcup",        |                       |
     |                          |     HOUR_1)            |                       |
     |                          |----------------------->|                       |
     |                          |                        | count++ (now 1000)    |
     |                          |                        |                       |

   Later, when someone requests trending:

   Client                  TrendingService          TrendingRepo
     |                          |                        |
     | getTrending(HOUR_1, 10)  |                        |
     |------------------------->|                        |
     |                          | 1. getTopTrending      |
     |                          |    (HOUR_1, 20)        |
     |                          |----------------------->|
     |                          |<-----------------------|
     |                          | Returns: [("worldcup", 1000), ("football", 200), ...]
     |                          |                        |
     |                          | 2. Calculate velocity scores:
     |                          |    "worldcup": baseline=50 (last hour avg)
     |                          |    score = (1000-50)/max(50,1) = 19.0  <-- TRENDING!
     |                          |                        |
     |                          |    "football": baseline=180
     |                          |    score = (200-180)/max(180,1) = 0.11 <-- barely trending
     |                          |                        |
     | 3. Return top 10         |                        |
     |    by score              |                        |
     |<-------------------------|                        |
     | [#worldcup (19.0),       |                        |
     |  #football (0.11), ...]  |                        |
```

### Workflow 6: Unfollow Edge Case

```
Actor: @bob unfollows @alice
Issue: @alice's tweets are still in @bob's timeline cache

   @bob                    FollowService            FollowRepo           TimelineCache
     |                          |                        |                       |
     | 1. unfollow              |                        |                       |
     |    ("bob", "alice")      |                        |                       |
     |------------------------->|                        |                       |
     |                          | 2. followRepo.delete   |                       |
     |                          |    ("bob", "alice")    |                       |
     |                          |----------------------->|                       |
     |                          |                        | Remove edge           |
     |                          |                        |                       |
     |                          | 3. Decrement counters  |                       |
     |                          |    alice.decrementFollowers()                  |
     |                          |    bob.decrementFollowing()                    |
     |                          |                        |                       |
     |                          | 4. DO NOT remove       |                       |
     |                          |    alice's tweets      |                       |
     |                          |    from bob's cache    |                       |
     |                          |    (too expensive)     |                       |
     |                          |                        |                       |
     |  5. Unfollow confirmed   |                        |                       |
     |<-------------------------|                        |                       |

   Later, when @bob opens feed:

   @bob                    FeedService              TimelineCache
     |                          |                        |
     | generateFeed("bob", 50)  |                        |
     |------------------------->|                        |
     |                          | 1. Get pre-computed    |
     |                          |    timeline            |
     |                          |----------------------->|
     |                          |<-----------------------|
     |                          | Returns: [...alice_tweet_1, alice_tweet_2...]
     |                          |                        |
     |                          | 2. Filter: check if    |
     |                          |    bob still follows   |
     |                          |    each tweet's author |
     |                          |    bob follows alice?  |
     |                          |    -> NO (unfollowed)  |
     |                          |    -> FILTER OUT       |
     |                          |                        |
     |  3. Feed without alice's |                        |
     |     tweets               |                        |
     |<-------------------------|                        |

Note: In production with Redis, use TTL (Time-To-Live):
  EXPIRE user:bob:timeline 86400  (24 hours)
  Stale entries naturally expire. No explicit cleanup needed.
```

---

## 13. Design Patterns

### 13.1 Strategy Pattern

```
Purpose: Swap algorithms without changing the client code.

Two independent strategy hierarchies:

1. FAN-OUT STRATEGY:
   ┌─────────────────────┐
   │  <<interface>>       │
   │  FanoutStrategy      │
   │─────────────────────│
   │ + fanout(tweet, ids) │
   │ + name()             │
   └────────┬─────────────┘
            │
    ┌───────┼──────────┬──────────────────┐
    │       │          │                  │
    v       v          v                  v
  Write   Read      Hybrid          (Future: ML-based,
  Strategy Strategy Strategy         geo-based, etc.)

2. RANKING STRATEGY:
   ┌─────────────────────────┐
   │  <<interface>>           │
   │  FeedRanker              │
   │─────────────────────────│
   │ + rank(List<FeedItem>)   │
   │ + name()                 │
   └────────┬─────────────────┘
            │
    ┌───────┼──────────┐
    │       │          │
    v       v          v
  Chrono  Engage   (Future: ML ranker,
  Ranker  Ranker    personalized, etc.)

Usage in FeedService:
  // Injected via constructor — can swap at runtime or per A/B test
  private final FeedRanker feedRanker;
  
  // In generateFeed():
  List<FeedItem> rankedItems = feedRanker.rank(mergedItems);
```

### 13.2 Builder Pattern

```
Purpose: Construct complex Tweet objects step by step with validation.

Tweet tweet = Tweet.builder()
    .tweetId(UUID.randomUUID().toString())
    .userId("user123")
    .content("Hello #world! Check out #Java17")
    .mediaUrls(List.of("https://img.com/photo.jpg"))
    .build();  // <-- Validates: content not null, <= 280 chars, extracts hashtags

Why Builder here:
  - Tweet has 5+ constructor parameters
  - Some are optional (mediaUrls)
  - Validation must happen at build time
  - Immutable after construction (thread-safe)
```

### 13.3 Repository Pattern

```
Purpose: Abstract data access behind interfaces. Swap InMemory for Redis/DB.

   FeedService ──uses──> TweetRepository (interface)
                              ^
                              │
               ┌──────────────┼──────────────┐
               │              │              │
               v              v              v
        InMemoryTweet    RedisTweet     PostgresTweet
        Repository       Repository     Repository

5 repositories:
  TweetRepository            — tweet CRUD
  UserRepository             — user CRUD
  FollowRepository           — social graph
  TimelineCacheRepository    — pre-computed timelines
  TrendingRepository         — hashtag counts

Benefit: In interview, say "In production, swap InMemory for Redis/Cassandra."
         The service layer code does not change.
```

### 13.4 Factory Pattern

```
Purpose: Centralize object creation and wiring in AppConfig.

// In AppConfig.java
public class AppConfig {
    // Thresholds
    public static final int CELEBRITY_THRESHOLD = 10_000;
    public static final int FEED_DEFAULT_LIMIT = 50;
    public static final int CELEBRITY_PULL_LIMIT = 20;
    public static final int MAX_TIMELINE_CACHE_SIZE = 800;
    public static final int TWEET_MAX_LENGTH = 280;

    // Factory method: create fully wired FanoutService
    public static FanoutService createFanoutService(
            UserRepository userRepo,
            FollowRepository followRepo,
            TimelineCacheRepository timelineCacheRepo) {

        FanoutOnWriteStrategy writeStrategy =
            new FanoutOnWriteStrategy(timelineCacheRepo);
        FanoutOnReadStrategy readStrategy =
            new FanoutOnReadStrategy();
        HybridFanoutStrategy hybridStrategy =
            new HybridFanoutStrategy(writeStrategy, readStrategy,
                userRepo, CELEBRITY_THRESHOLD);

        return new FanoutService(hybridStrategy, followRepo, userRepo);
    }

    // Factory method: create FeedService with engagement ranking
    public static FeedService createFeedService(
            TimelineCacheRepository timelineCacheRepo,
            TweetRepository tweetRepo,
            FollowRepository followRepo,
            UserRepository userRepo) {

        FeedRanker ranker = new EngagementRanker();
        return new FeedService(timelineCacheRepo, tweetRepo,
            followRepo, userRepo, ranker);
    }

    // Factory method: create FeedService with chronological ranking (for testing)
    public static FeedService createChronologicalFeedService(
            TimelineCacheRepository timelineCacheRepo,
            TweetRepository tweetRepo,
            FollowRepository followRepo,
            UserRepository userRepo) {

        FeedRanker ranker = new ChronologicalRanker();
        return new FeedService(timelineCacheRepo, tweetRepo,
            followRepo, userRepo, ranker);
    }
}
```

### 13.5 Composite Pattern

```
Purpose: HybridFanoutStrategy composes write + read strategies.

HybridFanoutStrategy IS-A FanoutStrategy
HybridFanoutStrategy HAS-A FanoutOnWriteStrategy
HybridFanoutStrategy HAS-A FanoutOnReadStrategy

The client (FanoutService) talks to HybridFanoutStrategy
through the FanoutStrategy interface. It doesn't know that
two sub-strategies are composed inside.

   FanoutService
        |
        | calls fanout()
        v
   HybridFanoutStrategy ──implements──> FanoutStrategy
        |
        | delegates based on poster.isCelebrity()
        |
   ┌────┴────┐
   |         |
   v         v
  Write    Read
  Strategy Strategy
```

### 13.6 Mediator Pattern

```
Purpose: FeedService orchestrates multiple components without them knowing each other.

FeedService is the mediator between:
  - TimelineCacheRepository (pre-computed timelines)
  - TweetRepository (celebrity tweet pulls)
  - FollowRepository (social graph queries)
  - UserRepository (celebrity detection)
  - FeedRanker (scoring and sorting)

None of these components reference each other directly.
FeedService coordinates the flow:

   TimelineCache <──┐
   TweetRepo     <──┤
   FollowRepo    <──┼── FeedService (Mediator)
   UserRepo      <──┤
   FeedRanker    <──┘
```

### 13.7 Observer Pattern

```
Purpose: When a tweet is posted, multiple actions trigger in sequence.

Event: Tweet posted
Observers:
  1. FanoutService      — distribute to followers
  2. TrendingService    — extract and count hashtags
  3. (Future) NotificationService — push notifications to mentioned users

// In TweetService.postTweet():
public Tweet postTweet(String userId, String content, List<String> mediaUrls) {
    Tweet tweet = /* build and save */;

    // Observer 1: Fan-out
    fanoutService.processTweet(tweet);

    // Observer 2: Trending
    trendingService.recordHashtags(tweet);

    // Observer 3: (Future) Notifications
    // notificationService.notifyMentions(tweet);

    return tweet;
}

Note: In production, use an event bus (Kafka, RabbitMQ) for true
      async observer decoupling. Here it's synchronous for simplicity.
```

### Design Patterns Summary

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | FanoutStrategy, FeedRanker | Swap fan-out/ranking algorithms without changing clients |
| **Builder** | Tweet.Builder | Complex object construction with validation |
| **Repository** | 5 repository interfaces + InMemory impls | Abstract data access, swap storage backends |
| **Factory** | AppConfig | Centralize wiring and configuration |
| **Composite** | HybridFanoutStrategy | Compose write + read strategies into one |
| **Mediator** | FeedService | Orchestrate 5 components without coupling them |
| **Observer** | TweetService.postTweet() | Trigger fan-out + trending + notifications on tweet post |

---

## 14. Extensibility

### 14.1 Add ML-Based Ranking

```
Current: EngagementRanker uses a formula (likes*1 + retweets*2 + replies*1.5)
Future:  ML model that considers user preferences, social graph, content embedding

How to add:
  1. Create MLRanker implements FeedRanker
  2. Inject a model scoring client
  3. rank() sends batch of FeedItems to model, gets scores back
  4. Swap in AppConfig: new MLRanker(modelClient) instead of EngagementRanker
  5. Zero changes to FeedService — Strategy pattern at work

public class MLRanker implements FeedRanker {
    private final ModelScoringClient modelClient;

    @Override
    public List<FeedItem> rank(List<FeedItem> items) {
        Map<String, Double> scores = modelClient.batchScore(items);
        items.forEach(item -> item.setScore(
            scores.getOrDefault(item.getTweet().getTweetId(), 0.0)));
        return items.stream().sorted().collect(Collectors.toList());
    }

    @Override
    public String name() { return "ML_RANKER"; }
}
```

### 14.2 Add Ads in Feed

```
How to add:
  1. Create an AdService that returns sponsored FeedItems
  2. In FeedService.generateFeed(), after Step 5 (ranking):
     - Call adService.getAds(userId, limit=3)
     - Insert ads at positions 3, 8, 15 (every N items)
     - Mark with source=AD for tracking
  3. Create AdFeedItem extends FeedItem with ad-specific metadata

// In FeedService.generateFeed(), after ranking:
List<FeedItem> withAds = adService.insertAds(rankedItems, userId);
```

### 14.3 Add Retweet with Quote

```
How to add:
  1. Extend Tweet model:
     - Add: String quotedTweetId (nullable)
     - Add: boolean isRetweet, boolean isQuoteRetweet
  2. In Tweet.Builder: add quotedTweetId(String id)
  3. In FeedItem: if tweet.isQuoteRetweet(), include original tweet for rendering
  4. Fan-out treats quote retweets the same as regular tweets
  5. Engagement: original tweet gets incrementRetweets()

// Extended Tweet fields:
private final String quotedTweetId;     // null if not a quote tweet
private final boolean isRetweet;
private final boolean isQuoteRetweet;
```

### 14.4 Add Polls

```
How to add:
  1. Create Poll entity: pollId, List<PollOption>, expiresAt, Map<String, String> votes
  2. Create PollOption: optionId, text, AtomicInteger voteCount
  3. Link to Tweet: Tweet has Optional<String> pollId
  4. Create PollService with vote(), getResults(), closePoll()
  5. Feed rendering: if tweet.hasPoll(), include poll data in FeedItem

public class Poll {
    private final String pollId;
    private final List<PollOption> options;
    private final LocalDateTime expiresAt;
    private final ConcurrentHashMap<String, String> userVotes; // userId -> optionId
}
```

### 14.5 Add Bookmarks

```
How to add:
  1. Create BookmarkRepository: save(userId, tweetId), getBookmarks(userId, limit)
  2. InMemory: ConcurrentHashMap<String, CopyOnWriteArrayList<String>>
  3. Add to TweetService: bookmarkTweet(), unbookmarkTweet()
  4. Add to FeedController: GET /bookmarks/{userId}
  5. No impact on fan-out — bookmarks are a user-level read path

public interface BookmarkRepository {
    void save(String userId, String tweetId);
    void delete(String userId, String tweetId);
    List<String> getBookmarkedTweetIds(String userId, int limit);
    boolean isBookmarked(String userId, String tweetId);
}
```

### 14.6 Add Lists (Curated Follow Groups)

```
How to add:
  1. Create TwitterList entity: listId, ownerId, name, List<String> memberIds
  2. Create ListRepository
  3. Create ListFeedService:
     - generateListFeed(listId, limit): pull tweets from all list members
     - Uses same ranking pipeline as FeedService
  4. Fan-out: Lists do NOT use pre-computed timelines
     - Always fan-out-on-read (pull from each member's user timeline)
     - Reason: Lists are niche, low-traffic, read latency is acceptable
  5. Add to FeedController: GET /lists/{listId}/feed

public class TwitterList {
    private final String listId;
    private final String ownerId;
    private final String name;
    private final String description;
    private final CopyOnWriteArrayList<String> memberIds;
    private final boolean isPrivate;
}
```

### Extensibility Summary

| Feature | Components to Add | Changes to Existing Code | Fan-out Impact |
|---------|-------------------|--------------------------|----------------|
| ML Ranking | MLRanker | AppConfig swap only | None |
| Ads | AdService, AdFeedItem | FeedService (insert step) | None |
| Quote Retweet | Tweet fields | Tweet.Builder, FeedItem rendering | Same as tweet |
| Polls | Poll, PollOption, PollService | Tweet (optional pollId) | None |
| Bookmarks | BookmarkRepository | TweetService, Controller | None |
| Lists | TwitterList, ListFeedService | Controller | Read-only fan-out |

---

## Controller

### FeedController

```java
/**
 * REST API entry point for the feed system.
 * In a real system, this would be a Spring @RestController.
 * Here, we define the contract and delegate to services.
 */
public class FeedController {

    private final FeedService feedService;
    private final TweetService tweetService;
    private final FollowService followService;
    private final TrendingService trendingService;
    private final UserService userService;

    public FeedController(FeedService feedService, TweetService tweetService,
            FollowService followService, TrendingService trendingService,
            UserService userService) {
        this.feedService = feedService;
        this.tweetService = tweetService;
        this.followService = followService;
        this.trendingService = trendingService;
        this.userService = userService;
    }

    // GET /feed/{userId}?limit=50
    public List<FeedItem> getHomeFeed(String userId, int limit) {
        return feedService.generateFeed(userId, limit);
    }

    // GET /timeline/{userId}?limit=50
    public List<FeedItem> getUserTimeline(String userId, int limit) {
        return feedService.getUserTimeline(userId, limit);
    }

    // POST /tweet
    public Tweet postTweet(String userId, String content, List<String> mediaUrls) {
        return tweetService.postTweet(userId, content, mediaUrls);
    }

    // DELETE /tweet/{tweetId}
    public void deleteTweet(String tweetId, String userId) {
        tweetService.deleteTweet(tweetId, userId);
    }

    // POST /tweet/{tweetId}/like
    public int likeTweet(String tweetId) {
        return tweetService.likeTweet(tweetId);
    }

    // POST /tweet/{tweetId}/retweet
    public int retweetTweet(String tweetId) {
        return tweetService.retweetTweet(tweetId);
    }

    // POST /follow
    public void follow(String followerId, String followeeId) {
        followService.follow(followerId, followeeId);
    }

    // DELETE /follow
    public void unfollow(String followerId, String followeeId) {
        followService.unfollow(followerId, followeeId);
    }

    // GET /trending?window=HOUR_1&limit=10
    public List<TrendingTopic> getTrending(TrendingWindow window, int limit) {
        return trendingService.getTrending(window, limit);
    }
}
```

---

## Exception Classes

```java
/**
 * Base exception for the feed system.
 */
public class FeedException extends RuntimeException {
    public FeedException(String message) {
        super(message);
    }

    public FeedException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Thrown when a tweet lookup fails.
 */
public class TweetNotFoundException extends FeedException {
    public TweetNotFoundException(String message) {
        super(message);
    }
}

/**
 * Thrown when a user lookup fails.
 */
public class UserNotFoundException extends FeedException {
    public UserNotFoundException(String message) {
        super(message);
    }
}
```

---

## Quick Interview Reference Card

```
Q: "How does Twitter deliver tweets to followers?"
A: HYBRID FAN-OUT
   - Normal users (<10K followers): Fan-out on WRITE (push to all follower timelines)
   - Celebrities (>=10K followers): Fan-out on READ (pull at feed generation time)
   - At read time: MERGE pushed + pulled, RANK, return top N

Q: "Why not fan-out on write for everyone?"
A: Celebrity with 50M followers = 50M writes per tweet. 
   At 10 tweets/day = 500M writes/day from ONE user. Unacceptable latency and cost.

Q: "Why not fan-out on read for everyone?"
A: Normal users' followers would all need to pull. With 500 follows,
   that's 500 DB reads per feed load per user. At scale = read amplification.

Q: "What's the celebrity threshold?"
A: ~10K followers (configurable). Below = push. Above = pull.
   User can transition dynamically as they gain/lose followers.

Q: "How is trending calculated?"
A: Velocity-based: score = (current - baseline) / max(baseline, 1).
   Detects SPIKES, not raw volume. #SuperBowl going 100 -> 50K = trending.
   #love staying at 10K = not trending.

Q: "What about deleted tweets in cached timelines?"
A: Soft delete (flag=true), filtered at read time. 
   NOT removed from caches (too expensive, O(followers) operation).

Q: "What design patterns are used?"
A: Strategy (fanout/ranking), Builder (Tweet), Repository (5 repos),
   Factory (AppConfig), Composite (HybridFanout), Mediator (FeedService),
   Observer (tweet -> fanout + trending).

Q: "How do you handle concurrency?"
A: ConcurrentHashMap for stores, CopyOnWriteArrayList for follower lists,
   AtomicInteger for counters, volatile for soft-delete flag.
```
