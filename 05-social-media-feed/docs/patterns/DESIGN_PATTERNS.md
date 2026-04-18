# Design Patterns -- Social Media Feed System (Twitter/X-like)

> Quick reference for system design interviews. Each pattern includes why it fits,
> a code sketch, an ASCII diagram, and a one-liner you can drop in an interview.

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Strategy (x2) | Behavioral | Fan-out strategy + Feed ranking |
| 2 | Composite | Structural | HybridFanoutStrategy composes write + read |
| 3 | Builder | Creational | Tweet construction |
| 4 | Observer | Behavioral | Tweet published triggers fan-out, trending, notifications |
| 5 | Repository | Structural (enterprise) | Data access abstraction (5 repos) |
| 6 | Factory | Creational | AppConfig wiring |
| 7 | Mediator | Behavioral | FeedService orchestrates cache + pull + merge + rank |

---

## 1. Strategy Pattern (Behavioral) -- THE KEY PATTERN

**Two independent Strategy interfaces** power the core of this system.
This is the pattern interviewers care about most -- it directly answers
"How do you handle the celebrity problem?"

### Strategy Interface A: FanoutStrategy

Determines **how** a tweet reaches followers' timelines.

```java
public interface FanoutStrategy {
    /**
     * Distribute a tweet to the poster's followers.
     * @param tweet     the newly published tweet
     * @param poster    the user who posted it
     * @param followers list of follower user IDs
     */
    void fanout(Tweet tweet, User poster, List<String> followers);
}
```

Three concrete strategies:

| Strategy | When Used | Cost Model |
|----------|-----------|------------|
| FanoutOnWriteStrategy | Normal users (< 10K followers) | O(followers) write at post time |
| FanoutOnReadStrategy | Celebrities (>= 10K followers) | O(following-celebrities) read at feed time |
| HybridFanoutStrategy | Production default | Delegates to write or read based on poster type |

### Strategy Interface B: FeedRanker

Determines the **order** of tweets in a user's feed.

```java
public interface FeedRanker {
    /**
     * Assign a score to each feed item and return sorted list.
     */
    List<FeedItem> rank(List<FeedItem> items, String userId);
}
```

Two concrete strategies:

| Strategy | Score Formula | Use Case |
|----------|--------------|----------|
| ChronologicalRanker | score = timestamp millis | "Latest tweets" tab |
| EngagementRanker | likes*1.0 + retweets*2.0 + replies*1.5 + recency bonus | "For You" tab |

### ASCII Diagram -- Two Strategy Axes

```
              FanoutStrategy                         FeedRanker
          (how tweets distribute)              (how feed is ordered)
                   |                                    |
        +----------+----------+               +---------+---------+
        |          |          |               |                   |
  FanoutOnWrite  FanoutOnRead  Hybrid   Chronological      Engagement
  (push to all   (pull at     (compose   (score =           (score =
   followers'     read time)   both)      timestamp)         engagement
   timelines)                                                + recency)
```

### ASCII Diagram -- HybridFanoutStrategy (Strategy + Composite)

```
                  +---------------------------+
                  |   HybridFanoutStrategy    |
                  |   implements FanoutStrategy|
                  +---------------------------+
                  | - writeStrategy: FanoutOnWriteStrategy |
                  | - readStrategy:  FanoutOnReadStrategy  |
                  +---------------------------+
                  | + fanout(tweet, poster, followers)     |
                  +---------------------------+
                              |
                   poster.isCelebrity()?
                       /            \
                     YES             NO
                    /                  \
        +-------------------+   +---------------------+
        | FanoutOnReadStrategy|  | FanoutOnWriteStrategy|
        | (skip push --      |  | (push tweet to each  |
        |  tweet stays in    |  |  follower's Redis     |
        |  poster's tweet    |  |  timeline sorted set) |
        |  store, pulled     |  |                       |
        |  at read time)     |  |                       |
        +-------------------+   +---------------------+
```

### Code Sketch -- HybridFanoutStrategy

```java
public class HybridFanoutStrategy implements FanoutStrategy {

    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy  readStrategy;

    public HybridFanoutStrategy(FanoutOnWriteStrategy writeStrategy,
                                FanoutOnReadStrategy readStrategy) {
        this.writeStrategy = writeStrategy;
        this.readStrategy  = readStrategy;
    }

    @Override
    public void fanout(Tweet tweet, User poster, List<String> followers) {
        if (poster.isCelebrity()) {
            // Celebrity: skip push. Followers pull at read time.
            readStrategy.fanout(tweet, poster, followers);
            System.out.println("  [Hybrid] Celebrity @" + poster.getUsername()
                + " -> fan-out-on-READ (followers pull at read time)");
        } else {
            // Normal user: push to every follower's timeline cache
            writeStrategy.fanout(tweet, poster, followers);
            System.out.println("  [Hybrid] Normal @" + poster.getUsername()
                + " -> fan-out-on-WRITE (" + followers.size() + " timelines)");
        }
    }
}
```

### Code Sketch -- EngagementRanker

```java
public class EngagementRanker implements FeedRanker {

    private static final long RECENCY_WINDOW_HOURS = 24;

    @Override
    public List<FeedItem> rank(List<FeedItem> items, String userId) {
        long now = System.currentTimeMillis();
        for (FeedItem item : items) {
            Tweet t = item.getTweet();
            double engagement = t.getEngagementScore();  // likes*1 + retweets*2 + replies*1.5
            double recencyBonus = computeRecency(t.getCreatedAt(), now);
            item.setScore(engagement + recencyBonus);
        }
        Collections.sort(items);  // FeedItem.compareTo: higher score first
        return items;
    }
}
```

### Interview One-Liner

> "We use **two Strategy interfaces** -- FanoutStrategy picks push vs. pull
> based on poster follower count (the celebrity problem), and FeedRanker
> picks chronological vs. engagement scoring. The hybrid strategy composes
> both fan-out approaches, choosing at runtime per tweet."

### Why This Matters in Interviews

```
Interviewer: "What happens when @taylorswift (90M followers) tweets?"
You:         "HybridFanoutStrategy detects isCelebrity() and delegates to
              FanoutOnReadStrategy -- we skip pushing to 90M timelines.
              When a follower opens their feed, FeedService pulls Taylor's
              recent tweets and merges them with the pre-computed timeline."

Interviewer: "What about a normal user with 200 followers?"
You:         "Same HybridFanoutStrategy, but now isCelebrity() is false,
              so it delegates to FanoutOnWriteStrategy -- we push the tweet
              to 200 Redis sorted sets. Instant delivery, no read-time cost."
```

### Tradeoffs

| Pro | Con |
|-----|-----|
| Swappable fan-out without touching feed generation | Two abstractions to maintain |
| Celebrity problem solved cleanly at the strategy level | Threshold tuning (10K? 50K?) is an ops concern |
| Ranker is independent of fan-out path | Engagement ranking needs real-time engagement data |
| Easy A/B testing: swap ranker per user cohort | Hybrid adds latency at read time for celebrity tweets |

---

## 2. Composite Pattern (Structural)

### Why

HybridFanoutStrategy **composes** FanoutOnWriteStrategy and FanoutOnReadStrategy
behind a single FanoutStrategy interface. Callers do not know they are talking to
a composite -- they call `fanout()` and the hybrid decides internally.

This is Strategy + Composite working together: the composite IS a strategy.

### ASCII Diagram

```
         FanoutStrategy (interface)
                  |
     +------------+-------------+
     |            |             |
FanoutOnWrite  FanoutOnRead  HybridFanout
                              |
                     +--------+--------+
                     |                 |
              FanoutOnWrite     FanoutOnRead
              (held as field)   (held as field)
```

### Code Sketch

```java
// HybridFanoutStrategy IS-A FanoutStrategy (Strategy pattern)
// HybridFanoutStrategy HAS-A FanoutOnWriteStrategy + FanoutOnReadStrategy (Composite)

public class HybridFanoutStrategy implements FanoutStrategy {
    private final FanoutOnWriteStrategy writeStrategy;  // leaf
    private final FanoutOnReadStrategy  readStrategy;   // leaf

    @Override
    public void fanout(Tweet tweet, User poster, List<String> followers) {
        // Delegate to the appropriate leaf based on poster type
        if (poster.isCelebrity()) {
            readStrategy.fanout(tweet, poster, followers);
        } else {
            writeStrategy.fanout(tweet, poster, followers);
        }
    }
}
```

### Interview One-Liner

> "HybridFanoutStrategy is a Composite -- it implements FanoutStrategy but
> internally holds write and read strategies as children, delegating based on
> whether the poster is a celebrity. The caller just sees one `fanout()` call."

---

## 3. Builder Pattern (Creational)

### Why

A Tweet has required fields (tweetId, userId, content), optional fields
(mediaUrls, createdAt), and derived fields (hashtags extracted from content).
The Builder enforces validation at construction time.

### ASCII Diagram

```
  Client Code
       |
       v
  Tweet.Builder
  +-------------------+
  | - tweetId         |  required
  | - userId          |  required
  | - content         |  required (max 280 chars)
  | - mediaUrls       |  optional
  | - createdAt       |  optional (defaults to now)
  +-------------------+
  | + tweetId(id)     |  returns this
  | + userId(uid)     |  returns this
  | + content(text)   |  returns this
  | + mediaUrls(urls) |  returns this
  | + createdAt(dt)   |  returns this
  | + build()         | ---> validates, extracts hashtags,
  +-------------------+      returns immutable Tweet
                                |
                                v
                         Tweet (immutable)
                         - tweetId, userId, content
                         - mediaUrls (unmodifiable list)
                         - hashtags (auto-extracted, unmodifiable)
                         - likeCount, retweetCount, replyCount (AtomicInteger)
                         - createdAt, deleted (volatile)
```

### Code Sketch (from actual codebase)

```java
Tweet tweet = new Tweet.Builder()
        .tweetId("tweet-123")
        .userId("user-42")
        .content("Just shipped the new feature! #java #systemdesign")
        .mediaUrls(List.of("https://cdn.example.com/img/abc.jpg"))
        .build();

// Hashtags auto-extracted: ["java", "systemdesign"]
// createdAt defaults to LocalDateTime.now()
// likeCount, retweetCount, replyCount initialized to 0
```

### Validation in build()

```java
public Tweet build() {
    if (tweetId == null || tweetId.isBlank())
        throw new IllegalArgumentException("tweetId is required");
    if (userId == null || userId.isBlank())
        throw new IllegalArgumentException("userId is required");
    if (content == null || content.isBlank())
        throw new IllegalArgumentException("content is required");
    if (content.length() > 280)
        throw new IllegalArgumentException("content exceeds 280 characters");
    return new Tweet(this);
}
```

### Interview One-Liner

> "Tweet uses Builder because it has required fields with validation, optional
> fields with defaults, and derived fields (hashtags are auto-extracted from
> content at construction time). The resulting Tweet is immutable."

---

## 4. Observer Pattern (Behavioral)

### Why

When a tweet is published, multiple independent subsystems must react:
fan-out to followers, update trending topics, send push notifications,
index for search. Observer decouples the publisher from all consumers.

### Where It Applies

| Subject | Event | Observers |
|---------|-------|-----------|
| TweetPublisher | tweet.published | FanoutService, TrendingService, NotificationService, SearchIndexer |
| FollowService | user.followed / user.unfollowed | TimelineCache (invalidate), SocialGraphCache |
| TweetPublisher | tweet.deleted | TimelineCache (remove), SearchIndexer (remove) |

### ASCII Diagram

```
  TweetPublisher (Subject)
  +---------------------------+
  | - listeners: List<        |
  |     TweetEventListener>   |
  +---------------------------+
  | + publish(tweet)          |
  | + addListener(listener)   |
  | + removeListener(listener)|
  +---------------------------+
           |
           | tweet.published event
           |
     +-----+------+-------+-----------+
     |            |        |           |
  FanoutSvc  TrendingSvc  NotifSvc  SearchIndexer
  (push to    (ZINCRBY    (push to   (index in
   timelines)  hashtags)   mobile)    Elasticsearch)
```

### In Production: Kafka Replaces In-Process Observer

```
  Tweet Published
       |
       v
  +------------------+
  | Kafka Topic:     |
  | "tweet.published"|
  +------------------+
       |
       +---> Consumer Group: fanout-service
       |     (partitioned by poster userId)
       |
       +---> Consumer Group: trending-service
       |     (extracts hashtags, ZINCRBY)
       |
       +---> Consumer Group: notification-service
       |     (push to mobile for close friends)
       |
       +---> Consumer Group: search-indexer
             (Elasticsearch bulk index)
```

### Code Sketch

```java
public interface TweetEventListener {
    void onTweetPublished(Tweet tweet, User poster);
    void onTweetDeleted(Tweet tweet);
}

public class TweetPublisher {
    private final List<TweetEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(TweetEventListener listener) {
        listeners.add(listener);
    }

    public void publish(Tweet tweet, User poster) {
        // Persist tweet
        tweetRepository.save(tweet);

        // Notify all observers
        for (TweetEventListener listener : listeners) {
            listener.onTweetPublished(tweet, poster);
        }
    }
}

// FanoutService reacts to tweet.published
public class FanoutService implements TweetEventListener {
    private final FanoutStrategy strategy;  // injected (Strategy pattern!)

    @Override
    public void onTweetPublished(Tweet tweet, User poster) {
        List<String> followers = socialGraphRepo.getFollowers(poster.getUserId());
        strategy.fanout(tweet, poster, followers);
    }

    @Override
    public void onTweetDeleted(Tweet tweet) {
        // Remove from all timelines where it was pushed
        timelineCache.removeFromAll(tweet.getTweetId());
    }
}
```

### Interview One-Liner

> "Tweet publication fires an Observer event consumed by fan-out, trending,
> notifications, and search. In production this becomes a Kafka topic with
> independent consumer groups -- same Observer semantics, distributed."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Adding a new consumer (e.g., analytics) needs zero changes to publisher | Ordering between observers is not guaranteed |
| Each consumer scales independently (separate Kafka consumer group) | Debugging event chains is harder than direct calls |
| Failure in one consumer does not block others | Need idempotent consumers (at-least-once delivery) |

---

## 5. Repository Pattern (Structural / Enterprise)

### Why

Abstracts data access behind interfaces. The in-memory implementations
used for interview demos can be swapped for Redis/Cassandra/PostgreSQL
in production without changing business logic.

### Five Repositories

| Repository | Backing Store (Prod) | Key Operations |
|------------|---------------------|----------------|
| TweetRepository | Cassandra | save, findById, findByUser(userId, limit) |
| UserRepository | PostgreSQL | save, findById, findByUsername |
| SocialGraphRepository | Redis Sets | follow, unfollow, getFollowers, getFollowing, isFollowing |
| TimelineRepository | Redis Sorted Set | addToTimeline, getTimeline, removeFromTimeline |
| TrendingRepository | Redis Sorted Set | incrementHashtag, getTopTrending(k) |

### ASCII Diagram

```
  FeedService / FanoutService / TrendingService
       |              |               |
       v              v               v
  +-----------+ +-----------+ +-----------+
  | Timeline  | | SocialGraph| | Trending  |
  | Repository| | Repository | | Repository|
  | (interface)| | (interface)| | (interface)|
  +-----------+ +-----------+ +-----------+
       |              |               |
       v              v               v
  +-----------+ +-----------+ +-----------+
  |InMemory   | |InMemory   | |InMemory   |
  |Timeline   | |SocialGraph| |Trending   |
  |Repository | |Repository | |Repository |
  +-----------+ +-----------+ +-----------+
       |              |               |
       | (swap in production)         |
       v              v               v
  +-----------+ +-----------+ +-----------+
  |Redis      | |Redis      | |Redis      |
  |Sorted Set | |Set        | |Sorted Set |
  +-----------+ +-----------+ +-----------+
```

### Code Sketch

```java
public interface TimelineRepository {
    void addToTimeline(String userId, String tweetId, double score);
    List<String> getTimeline(String userId, int limit);
    void removeFromTimeline(String userId, String tweetId);
    int getTimelineSize(String userId);
}

public interface SocialGraphRepository {
    void follow(String followerId, String followeeId);
    void unfollow(String followerId, String followeeId);
    Set<String> getFollowers(String userId);
    Set<String> getFollowing(String userId);
    boolean isFollowing(String followerId, String followeeId);
}
```

### Interview One-Liner

> "Five Repository interfaces decouple business logic from storage.
> In-memory for demos, Redis Sorted Sets for timelines, Redis Sets for
> social graph, Cassandra for tweets, PostgreSQL for user profiles."

---

## 6. Factory Pattern (Creational)

### Why

AppConfig wires together strategies, repositories, and services. It acts
as a Simple Factory / Composition Root, keeping construction logic in one
place. In production this would be Spring Boot auto-configuration.

### ASCII Diagram

```
  AppConfig (Factory / Composition Root)
  +----------------------------------------+
  | Creates and wires:                     |
  |                                        |
  | Repositories:                          |
  |   tweetRepo     = new InMemoryTweetRepo|
  |   userRepo      = new InMemoryUserRepo |
  |   socialGraphRepo = new InMemorySocial |
  |   timelineRepo  = new InMemoryTimeline |
  |   trendingRepo  = new InMemoryTrending |
  |                                        |
  | Strategies:                            |
  |   writeStrategy = new FanoutOnWrite(   |
  |                       timelineRepo)    |
  |   readStrategy  = new FanoutOnRead()   |
  |   fanoutStrategy = new HybridFanout(   |
  |                    write, read)        |
  |   feedRanker    = new EngagementRanker |
  |                                        |
  | Services:                              |
  |   fanoutService = new FanoutService(   |
  |                   fanoutStrategy,      |
  |                   socialGraphRepo)     |
  |   feedService   = new FeedService(     |
  |                   timelineRepo,        |
  |                   tweetRepo,           |
  |                   socialGraphRepo,     |
  |                   feedRanker)          |
  |   trendingService = new TrendingService|
  |                   (trendingRepo)       |
  +----------------------------------------+
```

### Code Sketch

```java
public class AppConfig {

    public static FanoutStrategy createFanoutStrategy(
            TimelineRepository timelineRepo) {
        FanoutOnWriteStrategy write = new FanoutOnWriteStrategy(timelineRepo);
        FanoutOnReadStrategy  read  = new FanoutOnReadStrategy();
        return new HybridFanoutStrategy(write, read);
    }

    public static FeedRanker createFeedRanker(String mode) {
        return switch (mode) {
            case "engagement"   -> new EngagementRanker();
            case "chronological" -> new ChronologicalRanker();
            default -> new EngagementRanker();
        };
    }

    public static FeedService createFeedService(
            TimelineRepository timelineRepo,
            TweetRepository tweetRepo,
            SocialGraphRepository socialGraphRepo,
            FeedRanker ranker) {
        return new FeedService(timelineRepo, tweetRepo, socialGraphRepo, ranker);
    }
}
```

### Interview One-Liner

> "AppConfig is the composition root -- it wires strategies, repositories,
> and services. Switch from in-memory to Redis by changing one line here.
> In Spring Boot, this becomes `@Configuration` with `@Bean` methods."

---

## 7. Mediator Pattern (Behavioral)

### Why

FeedService orchestrates multiple subsystems to generate a feed:
check timeline cache, pull celebrity tweets, merge, deduplicate, rank,
and trim. Without a mediator, the caller would need to coordinate all
of these steps.

### ASCII Diagram

```
  Client: GET /feed?userId=user-42&limit=50
       |
       v
  +----------------------------------+
  |          FeedService             |
  |          (Mediator)              |
  +----------------------------------+
  | 1. timelineRepo.getTimeline()   |  <-- Redis Sorted Set (cached)
  |    -> List<tweetId> from cache  |
  |                                  |
  | 2. tweetRepo.findById(each id) |  <-- Cassandra / Tweet cache
  |    -> List<FeedItem> pushed     |
  |                                  |
  | 3. socialGraphRepo.getFollowing()|  <-- Who does user follow?
  |    -> filter celebrities        |
  |                                  |
  | 4. tweetRepo.findByUser(celeb) |  <-- Pull celebrity tweets
  |    -> List<FeedItem> pulled     |
  |                                  |
  | 5. MERGE pushed + pulled        |
  |    -> dedup by tweetId          |
  |                                  |
  | 6. feedRanker.rank(merged)      |  <-- Strategy! (engagement/chrono)
  |                                  |
  | 7. TRIM to limit (e.g., 50)    |
  |    -> return ranked feed        |
  +----------------------------------+
       |
       v
  List<FeedItem> (sorted by score, deduped)
```

### Code Sketch

```java
public class FeedService {

    private final TimelineRepository    timelineRepo;
    private final TweetRepository       tweetRepo;
    private final SocialGraphRepository socialGraphRepo;
    private final FeedRanker            ranker;

    public List<FeedItem> getFeed(String userId, int limit) {
        // Step 1+2: Get pre-computed timeline (fan-out-on-write items)
        List<FeedItem> pushed = getPushedItems(userId);

        // Step 3+4: Pull celebrity tweets (fan-out-on-read)
        List<FeedItem> pulled = getCelebrityTweets(userId);

        // Step 5: Merge and dedup
        List<FeedItem> merged = mergeAndDedup(pushed, pulled);

        // Step 6: Rank
        List<FeedItem> ranked = ranker.rank(merged, userId);

        // Step 7: Trim
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    private List<FeedItem> getCelebrityTweets(String userId) {
        Set<String> following = socialGraphRepo.getFollowing(userId);
        List<FeedItem> items = new ArrayList<>();
        for (String followeeId : following) {
            User followee = userRepo.findById(followeeId);
            if (followee != null && followee.isCelebrity()) {
                List<Tweet> recent = tweetRepo.findByUser(followeeId, 10);
                for (Tweet t : recent) {
                    items.add(new FeedItem(t, 0.0, FeedSource.FANOUT_READ));
                }
            }
        }
        return items;
    }
}
```

### Interview One-Liner

> "FeedService is a Mediator -- it orchestrates timeline cache lookup,
> celebrity tweet pull, merge, dedup, ranking, and trimming. No other
> component needs to know the full feed generation pipeline."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Single place to understand the full feed pipeline | FeedService can become a god object |
| Each subsystem (cache, pull, rank) evolves independently | Adding steps requires modifying FeedService |
| Easy to add caching, metrics, circuit breakers around each step | Testing requires mocking many dependencies |

---

## Pattern Interaction Map

```
  Tweet.Builder (3)          Observer (4)               Strategy (1a)
  builds Tweet -----> TweetPublisher -----> FanoutService -----> HybridFanout (2)
                           |                                     /         \
                           |                          FanoutOnWrite   FanoutOnRead
                           |
                           +-----> TrendingService (Observer consumer)
                           +-----> NotificationService (Observer consumer)


  GET /feed
     |
     v
  FeedService (7-Mediator)
     |
     +-- TimelineRepository (5) -- pushed items from fan-out-on-write
     +-- TweetRepository (5)    -- tweet content lookup
     +-- SocialGraphRepository (5) -- celebrity detection
     +-- FeedRanker (1b-Strategy) -- rank merged items
     |
     v
  Ranked Feed

  AppConfig (6-Factory) wires all of the above.
```

---

## Quick Reference Table

| Pattern | GoF Category | Where | Solves |
|---------|-------------|-------|--------|
| Strategy (FanoutStrategy) | Behavioral | Fan-out path selection | Celebrity problem |
| Strategy (FeedRanker) | Behavioral | Feed ordering | Chrono vs. engagement |
| Composite | Structural | HybridFanoutStrategy | Compose push + pull |
| Builder | Creational | Tweet | Complex object with validation |
| Observer | Behavioral | Tweet events | Decouple publish from consume |
| Repository | Enterprise | Data access | Swap in-memory for Redis/Cassandra |
| Factory | Creational | AppConfig | Centralize wiring |
| Mediator | Behavioral | FeedService | Orchestrate feed pipeline |
