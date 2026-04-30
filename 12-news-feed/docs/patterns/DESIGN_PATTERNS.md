# Design Patterns -- News Feed System (Facebook/LinkedIn)

> Quick reference for system design interviews. Each pattern includes the ugly
> anti-pattern first, then the clean solution, numbered call chain, ASCII diagram,
> and a one-liner you can drop in an interview.
>
> **Differentiator from Project 05 (Social Media Feed):** Project 05 covered basic
> fan-out mechanics. This project goes deeper into ML-based ranking, real-time push,
> cursor-based pagination, engagement-driven scoring, and production concerns like
> cache warming, notification fanout, and content-type weighting.

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Strategy (x2) | Behavioral | FanoutStrategy (Write, Read, Hybrid) + RankingStrategy (Chronological, Algorithmic) |
| 2 | Composite | Structural | HybridFanoutStrategy composes Write + Read strategies |
| 3 | Builder | Creational | Post.Builder with content type, media, validation |
| 4 | Factory | Creational | AppConfig wires strategies, repos, services |
| 5 | Repository | Structural (enterprise) | 4 repositories (Post, User, Follow, Engagement) |
| 6 | Facade | Structural | FeedService orchestrates timeline + pull + merge + rank + paginate |
| 7 | Observer | Behavioral | NotificationService observes post/like/comment events |
| 8 | Iterator | Behavioral | Cursor-based pagination (FeedCursor) |
| 9 | Mediator | Behavioral | FeedService mediates between FanoutService, TimelineService, RankingService |

---

## 1. Strategy Pattern (Behavioral) -- THE KEY PATTERN

**Two independent Strategy interfaces** power the core of this system.
This is the pattern interviewers care about most -- it directly answers
"How do you handle the celebrity problem?" AND "How do you rank the feed?"

### Strategy Interface A: FanoutStrategy

Determines **how** a post reaches followers' timelines.

```java
public interface FanoutStrategy {
    /**
     * Distribute a post to the poster's followers.
     * @param post      the newly published post
     * @param author    the user who posted it
     * @param followers list of follower user IDs
     */
    void fanout(Post post, User author, List<String> followers);
}
```

Three concrete strategies:

| Strategy | When Used | Cost Model |
|----------|-----------|------------|
| FanoutOnWriteStrategy | Normal users (< 10K followers) | O(followers) write at post time |
| FanoutOnReadStrategy | Celebrities (>= 10K followers) | O(following-celebrities) read at feed time |
| HybridFanoutStrategy | Production default | Delegates to write or read based on author type |

### Strategy Interface B: RankingStrategy

Determines the **order** of posts in a user's feed.

```java
public interface RankingStrategy {
    /**
     * Score and sort feed items for the requesting user.
     * @param items  unranked feed items
     * @param userId the user requesting the feed
     * @return       items sorted by descending score
     */
    List<FeedItem> rank(List<FeedItem> items, String userId);
}
```

Two concrete strategies:

| Strategy | Score Formula | Use Case |
|----------|--------------|----------|
| ChronologicalRankingStrategy | score = timestamp millis | "Recent" tab, reverse-chrono feed |
| AlgorithmicRankingStrategy | affinity * recency * engagement * contentTypeWeight | "For You" / "Top" tab |

### Ugly Anti-Pattern -- Hardcoded Fan-Out + Ranking in One Method

```java
// UGLY: Fan-out logic and ranking tangled in one god method.
// Adding a new fan-out mode or ranking algorithm means editing this method.
// No way to A/B test different strategies per user cohort.

public class UglyFeedService {

    public void publishPost(Post post, User author) {
        List<String> followers = getFollowers(author.getUserId());

        // Hardcoded fan-out decision -- cannot swap at runtime
        if (author.getFollowerCount() > 10000) {
            // Just store the post; followers pull at read time
            postStore.save(post);
            System.out.println("Celebrity post stored for pull");
        } else {
            // Push to every follower's timeline
            for (String followerId : followers) {
                timelineStore.addToTimeline(followerId, post.getPostId(),
                        post.getCreatedAt().toEpochMilli());
            }
        }
    }

    public List<Post> getFeed(String userId, String rankMode) {
        List<Post> posts = timelineStore.getTimeline(userId, 200);

        // Hardcoded ranking -- switch statement grows with every new algorithm
        switch (rankMode) {
            case "chronological":
                posts.sort(Comparator.comparing(Post::getCreatedAt).reversed());
                break;
            case "algorithmic":
                posts.sort((a, b) -> {
                    double scoreA = a.getLikeCount() * 1.0 + a.getCommentCount() * 1.5
                            + a.getShareCount() * 2.0;
                    double scoreB = b.getLikeCount() * 1.0 + b.getCommentCount() * 1.5
                            + b.getShareCount() * 2.0;
                    return Double.compare(scoreB, scoreA);
                });
                break;
            default:
                throw new IllegalArgumentException("Unknown rank mode: " + rankMode);
        }
        return posts;
    }
}
```

**Problems:**
1. Fan-out decision is buried inside `publishPost()` -- cannot swap strategies
2. Ranking is a growing switch statement -- violates Open/Closed Principle
3. Cannot A/B test different fan-out thresholds or ranking formulas per user
4. Testing requires the full service, cannot test fan-out logic in isolation

### Clean Solution -- Two Strategy Interfaces

```java
// CLEAN: FanoutStrategy selects push vs. pull.
// RankingStrategy selects chronological vs. algorithmic scoring.
// Both injected at construction time -- swappable, testable, A/B testable.

public class CleanFeedService {

    private final FanoutStrategy   fanoutStrategy;
    private final RankingStrategy  rankingStrategy;
    private final TimelineRepository timelineRepo;

    public CleanFeedService(FanoutStrategy fanoutStrategy,
                            RankingStrategy rankingStrategy,
                            TimelineRepository timelineRepo) {
        this.fanoutStrategy  = fanoutStrategy;
        this.rankingStrategy = rankingStrategy;
        this.timelineRepo    = timelineRepo;
    }

    public void publishPost(Post post, User author, List<String> followers) {
        // Delegate fan-out decision entirely to the strategy
        fanoutStrategy.fanout(post, author, followers);
    }

    public List<FeedItem> getFeed(String userId, int limit) {
        List<FeedItem> items = timelineRepo.getTimeline(userId, limit);
        // Delegate ranking entirely to the strategy
        return rankingStrategy.rank(items, userId);
    }
}
```

### ASCII Diagram -- Two Strategy Axes

```
              FanoutStrategy                       RankingStrategy
          (how posts distribute)              (how feed is ordered)
                   |                                    |
        +----------+----------+               +---------+---------+
        |          |          |               |                   |
  FanoutOnWrite  FanoutOnRead  Hybrid   Chronological      Algorithmic
  (push to all   (pull at     (compose   (score =           (score =
   followers'     read time)   both)      timestamp)         affinity *
   timelines)                                                recency *
                                                             engagement *
                                                             contentWeight)
```

### Numbered Call Chain -- Post Publication (Hybrid Fan-Out)

```
1. Client calls POST /posts with content
2. PostService validates and builds Post via Post.Builder
3. PostService calls FanoutStrategy.fanout(post, author, followers)
4. HybridFanoutStrategy checks author.isCelebrity()
5a. Celebrity path: FanoutOnReadStrategy stores post in celebrity post cache
5b. Normal path: FanoutOnWriteStrategy pushes to each follower's timeline via ZADD
6. NotificationService.onPostPublished(post, author) fires async (Observer)
```

### Numbered Call Chain -- Feed Generation (Algorithmic Ranking)

```
1. Client calls GET /feed?userId=user-42&limit=50
2. FeedService.getFeed(userId, 50) invoked
3. TimelineRepository.getTimeline(userId, 200) reads Redis sorted set
4. PostRepository.findById(each postId) hydrates FeedItem list
5. Pull celebrity posts: FollowRepository.getFollowing(userId) -> filter celebrities
6. PostRepository.findRecentByUser(celebrityId, 10) for each celebrity
7. Merge pushed (step 4) + pulled (step 6), dedup by postId
8. RankingStrategy.rank(merged, userId) scores and sorts
9. AlgorithmicRankingStrategy computes: affinity * recency * engagement * contentTypeWeight
10. Return top 50 items
```

### AlgorithmicRankingStrategy -- Score Formula

```java
public class AlgorithmicRankingStrategy implements RankingStrategy {

    @Override
    public List<FeedItem> rank(List<FeedItem> items, String userId) {
        for (FeedItem item : items) {
            Post post = item.getPost();

            double affinity    = computeAffinity(userId, post.getAuthorId());
            double recency     = computeRecencyDecay(post.getCreatedAt());
            double engagement  = computeEngagement(post);
            double contentBoost = post.getContentType().getWeight();

            // Final score: all factors multiplied
            double score = affinity * recency * engagement * contentBoost;
            item.setScore(score);
        }
        items.sort(Comparator.comparingDouble(FeedItem::getScore).reversed());
        return items;
    }

    // Affinity: how often does this user interact with the author?
    // High affinity = close friends, frequently liked/commented
    private double computeAffinity(String userId, String authorId) {
        // In production: ML model trained on interaction history
        // Demo: return value from EngagementRepository
        return 1.0; // placeholder
    }

    // Recency: exponential decay over 24 hours
    private double computeRecencyDecay(LocalDateTime createdAt) {
        long ageHours = ChronoUnit.HOURS.between(createdAt, LocalDateTime.now());
        return Math.exp(-0.1 * ageHours); // half-life ~7 hours
    }

    // Engagement: weighted sum of likes, comments, shares
    private double computeEngagement(Post post) {
        return post.getLikeCount() * 1.0
             + post.getCommentCount() * 2.0
             + post.getShareCount() * 3.0
             + 1.0; // base score so new posts are not zero
    }
}
```

### Interview One-Liner

> "We use **two Strategy interfaces** -- FanoutStrategy picks push vs. pull
> based on follower count (the celebrity problem), and RankingStrategy picks
> chronological vs. algorithmic scoring with affinity, recency, engagement,
> and content-type weighting. Both are injected, swappable, and A/B testable."

### Why This Matters in Interviews

```
Interviewer: "How do you rank the feed?"
You:         "AlgorithmicRankingStrategy computes score = affinity * recency *
              engagement * contentTypeWeight. Affinity is how often the user
              interacts with the author. Recency uses exponential decay. Video
              gets a 1.5x content boost because platforms optimize for watch-time.
              Swap to ChronologicalRankingStrategy for the 'Recent' tab."

Interviewer: "What about A/B testing?"
You:         "Inject different RankingStrategy per user cohort. The feed
              pipeline does not change -- only the strategy object differs."
```

### Tradeoffs

| Pro | Con |
|-----|-----|
| Swappable fan-out without touching feed generation | Two abstractions to maintain |
| Celebrity problem solved cleanly at the strategy level | Threshold tuning (10K? 50K?) is an ops concern |
| Ranker is independent of fan-out path | Algorithmic ranking needs real-time engagement data |
| Easy A/B testing: swap ranker per user cohort | Hybrid adds latency at read time for celebrity posts |

### Cross-Reference

- **Composite (Section 2):** HybridFanoutStrategy composes write + read strategies
- **Facade (Section 6):** FeedService uses both strategies behind a single `getFeed()` call
- **Technologies:** AlgorithmicRankingStrategy in production replaced by ML model (see TECHNOLOGIES.md)

---

## 2. Composite Pattern (Structural)

### Why

HybridFanoutStrategy **composes** FanoutOnWriteStrategy and FanoutOnReadStrategy
behind a single FanoutStrategy interface. Callers do not know they are talking to
a composite -- they call `fanout()` and the hybrid decides internally.

This is Strategy + Composite working together: the composite IS a strategy.

### Ugly Anti-Pattern -- Caller Checks Celebrity Status

```java
// UGLY: The caller decides which fan-out to use.
// Every caller must know about celebrity detection logic.
// If the threshold changes, every call site must be updated.

public class UglyPublishService {

    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy  readStrategy;

    public void publishPost(Post post, User author, List<String> followers) {
        // Caller is forced to know about celebrity detection
        if (author.getFollowerCount() > 10000) {
            readStrategy.fanout(post, author, followers);
        } else {
            writeStrategy.fanout(post, author, followers);
        }
    }
}

// Another caller -- duplicated check!
public class UglyBatchPublisher {

    public void batchPublish(List<Post> posts, Map<String, User> authors) {
        for (Post post : posts) {
            User author = authors.get(post.getAuthorId());
            // Same check duplicated -- DRY violation
            if (author.getFollowerCount() > 10000) {
                readStrategy.fanout(post, author, getFollowers(author));
            } else {
                writeStrategy.fanout(post, author, getFollowers(author));
            }
        }
    }
}
```

### Clean Solution -- HybridFanoutStrategy (Composite)

```java
// CLEAN: HybridFanoutStrategy encapsulates the decision.
// Callers just call fanout() -- no knowledge of celebrity detection needed.

public class HybridFanoutStrategy implements FanoutStrategy {

    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy  readStrategy;

    public HybridFanoutStrategy(FanoutOnWriteStrategy writeStrategy,
                                FanoutOnReadStrategy readStrategy) {
        this.writeStrategy = writeStrategy;
        this.readStrategy  = readStrategy;
    }

    @Override
    public void fanout(Post post, User author, List<String> followers) {
        if (author.isCelebrity()) {
            readStrategy.fanout(post, author, followers);
        } else {
            writeStrategy.fanout(post, author, followers);
        }
    }
}

// Caller code is now clean -- no celebrity checks
public class CleanPublishService {

    private final FanoutStrategy strategy; // injected as HybridFanoutStrategy

    public void publishPost(Post post, User author, List<String> followers) {
        strategy.fanout(post, author, followers); // one line, no branching
    }
}
```

### ASCII Diagram

```
         FanoutStrategy (interface)
                  |
     +------------+-------------+
     |            |             |
FanoutOnWrite  FanoutOnRead  HybridFanout (Composite)
                              |
                     +--------+--------+
                     |                 |
              FanoutOnWrite     FanoutOnRead
              (held as field)   (held as field)
                     |
              author.isCelebrity()?
                   /         \
                 YES          NO
                /               \
          FanoutOnRead    FanoutOnWrite
          (skip push)     (push to all)
```

### Numbered Call Chain

```
1. PostService calls fanoutStrategy.fanout(post, author, followers)
2. HybridFanoutStrategy receives the call
3. HybridFanoutStrategy checks author.isCelebrity() (followerCount > 10,000)
4a. Celebrity: delegates to readStrategy.fanout() -- post stored for pull
4b. Normal: delegates to writeStrategy.fanout() -- ZADD to each follower's timeline
5. Caller (PostService) never knows which path was taken
```

### Interview One-Liner

> "HybridFanoutStrategy is a Composite -- it implements FanoutStrategy but
> internally holds write and read strategies as children, delegating based on
> whether the author is a celebrity. The caller just sees one `fanout()` call."

### Cross-Reference

- **Strategy (Section 1):** HybridFanoutStrategy is both a Strategy and a Composite
- **Factory (Section 4):** AppConfig assembles the composite from its parts

---

## 3. Builder Pattern (Creational)

### Why

A Post has required fields (postId, authorId, content), optional fields
(mediaUrls, contentType, createdAt), and derived fields (hashtags extracted
from content). The Builder enforces validation at construction time and
produces an immutable Post.

### Ugly Anti-Pattern -- Telescoping Constructors

```java
// UGLY: Multiple constructors, no validation, mutable state.
// Easy to swap arguments (postId and authorId are both strings).
// No way to set optional fields without passing nulls.

public class UglyPost {
    public String postId;
    public String authorId;
    public String content;
    public ContentType contentType;
    public List<String> mediaUrls;
    public List<String> hashtags;
    public int likeCount;
    public int commentCount;
    public int shareCount;
    public LocalDateTime createdAt;

    // Telescoping constructors -- grows with every field
    public UglyPost(String postId, String authorId, String content) {
        this(postId, authorId, content, ContentType.TEXT, null, null);
    }

    public UglyPost(String postId, String authorId, String content,
                     ContentType contentType) {
        this(postId, authorId, content, contentType, null, null);
    }

    public UglyPost(String postId, String authorId, String content,
                     ContentType contentType, List<String> mediaUrls,
                     LocalDateTime createdAt) {
        this.postId = postId;
        this.authorId = authorId;
        this.content = content;
        this.contentType = contentType;
        this.mediaUrls = mediaUrls;  // mutable reference leak!
        this.createdAt = createdAt;   // null if not passed
        // No validation, no hashtag extraction
    }
}

// Caller: which String is which?
UglyPost post = new UglyPost("user-42", "post-123", "Hello"); // SWAPPED!
```

### Clean Solution -- Post.Builder

```java
public class Post {

    private final String postId;
    private final String authorId;
    private final String content;
    private final ContentType contentType;
    private final List<String> mediaUrls;
    private final List<String> hashtags;     // auto-extracted
    private final LocalDateTime createdAt;

    private volatile int likeCount;
    private volatile int commentCount;
    private volatile int shareCount;
    private volatile boolean deleted;

    private Post(Builder builder) {
        this.postId      = builder.postId;
        this.authorId    = builder.authorId;
        this.content     = builder.content;
        this.contentType = builder.contentType;
        this.mediaUrls   = Collections.unmodifiableList(new ArrayList<>(builder.mediaUrls));
        this.hashtags    = Collections.unmodifiableList(extractHashtags(builder.content));
        this.createdAt   = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.likeCount   = 0;
        this.commentCount = 0;
        this.shareCount  = 0;
        this.deleted     = false;
    }

    private static List<String> extractHashtags(String content) {
        List<String> tags = new ArrayList<>();
        // Extract #hashtags from content
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("#(\\w+)").matcher(content);
        while (m.find()) tags.add(m.group(1).toLowerCase());
        return tags;
    }

    // --- Builder ---
    public static class Builder {
        private String postId;
        private String authorId;
        private String content;
        private ContentType contentType = ContentType.TEXT;
        private List<String> mediaUrls = new ArrayList<>();
        private LocalDateTime createdAt;

        public Builder postId(String postId)         { this.postId = postId; return this; }
        public Builder authorId(String authorId)     { this.authorId = authorId; return this; }
        public Builder content(String content)        { this.content = content; return this; }
        public Builder contentType(ContentType type)  { this.contentType = type; return this; }
        public Builder mediaUrls(List<String> urls)   { this.mediaUrls = urls; return this; }
        public Builder createdAt(LocalDateTime dt)    { this.createdAt = dt; return this; }

        public Post build() {
            if (postId == null || postId.isBlank())
                throw new IllegalArgumentException("postId is required");
            if (authorId == null || authorId.isBlank())
                throw new IllegalArgumentException("authorId is required");
            if (content == null || content.isBlank())
                throw new IllegalArgumentException("content is required");
            if (content.length() > 5000)
                throw new IllegalArgumentException("content exceeds 5000 characters");
            return new Post(this);
        }
    }
}
```

### ASCII Diagram

```
  Client Code
       |
       v
  Post.Builder
  +-------------------+
  | - postId          |  required
  | - authorId        |  required
  | - content         |  required (max 5000 chars)
  | - contentType     |  optional (default TEXT)
  | - mediaUrls       |  optional
  | - createdAt       |  optional (defaults to now)
  +-------------------+
  | + postId(id)      |  returns this
  | + authorId(uid)   |  returns this
  | + content(text)   |  returns this
  | + contentType(ct) |  returns this
  | + mediaUrls(urls) |  returns this
  | + createdAt(dt)   |  returns this
  | + build()         | ---> validates, extracts hashtags,
  +-------------------+      returns immutable Post
                                |
                                v
                         Post (immutable)
                         - postId, authorId, content
                         - mediaUrls (unmodifiable list)
                         - hashtags (auto-extracted, unmodifiable)
                         - contentType (with ranking weight)
                         - likeCount, commentCount, shareCount (volatile)
                         - createdAt, deleted (volatile)
```

### Numbered Call Chain

```
1. Client calls new Post.Builder()
2. Fluent setters: .postId("p-1").authorId("u-42").content("Hello #java")
3. .contentType(ContentType.VIDEO).mediaUrls(List.of("url1"))
4. .build() validates required fields (postId, authorId, content)
5. build() checks content length <= 5000
6. Post constructor extracts hashtags from content: ["java"]
7. Post constructor wraps mediaUrls in unmodifiable list
8. Post constructor sets createdAt to now() if null
9. Returns immutable Post object
```

### Interview One-Liner

> "Post uses Builder because it has required fields with validation, optional
> fields with defaults, and derived fields (hashtags auto-extracted from content,
> contentType with ranking weight). The resulting Post is immutable."

### Cross-Reference

- **Strategy (Section 1):** ContentType.getWeight() used in AlgorithmicRankingStrategy
- **Repository (Section 5):** PostRepository.save() accepts the built Post

---

## 4. Factory Pattern (Creational)

### Why

AppConfig wires together strategies, repositories, and services. It acts
as a Simple Factory / Composition Root, keeping construction logic in one
place. In production this would be Spring Boot `@Configuration` + `@Bean`.

### Ugly Anti-Pattern -- Scattered Construction

```java
// UGLY: Every main() or test creates its own wiring.
// If a constructor changes, every call site breaks.
// No single place to see the full dependency graph.

public class UglyMain {
    public static void main(String[] args) {
        // Scattered construction -- no composition root
        InMemoryPostRepository postRepo = new InMemoryPostRepository();
        InMemoryUserRepository userRepo = new InMemoryUserRepository();
        InMemoryFollowRepository followRepo = new InMemoryFollowRepository();
        InMemoryEngagementRepository engRepo = new InMemoryEngagementRepository();

        FanoutOnWriteStrategy write = new FanoutOnWriteStrategy(postRepo);
        FanoutOnReadStrategy read = new FanoutOnReadStrategy();
        // Oops -- forgot to wrap in HybridFanoutStrategy!

        // Different test creates DIFFERENT wiring -- inconsistent
        AlgorithmicRankingStrategy ranker = new AlgorithmicRankingStrategy(engRepo);
        FeedService feedService = new FeedService(postRepo, followRepo, ranker, write);
        // Wrong strategy passed -- hard to catch
    }
}
```

### Clean Solution -- AppConfig Factory

```java
public class AppConfig {

    // --- Repositories ---
    public static PostRepository createPostRepository() {
        return new InMemoryPostRepository();
    }

    public static UserRepository createUserRepository() {
        return new InMemoryUserRepository();
    }

    public static FollowRepository createFollowRepository() {
        return new InMemoryFollowRepository();
    }

    public static EngagementRepository createEngagementRepository() {
        return new InMemoryEngagementRepository();
    }

    // --- Strategies ---
    public static FanoutStrategy createFanoutStrategy(TimelineRepository timelineRepo) {
        FanoutOnWriteStrategy write = new FanoutOnWriteStrategy(timelineRepo);
        FanoutOnReadStrategy  read  = new FanoutOnReadStrategy();
        return new HybridFanoutStrategy(write, read); // Composite!
    }

    public static RankingStrategy createRankingStrategy(String mode,
                                                         EngagementRepository engRepo) {
        return switch (mode) {
            case "algorithmic"   -> new AlgorithmicRankingStrategy(engRepo);
            case "chronological" -> new ChronologicalRankingStrategy();
            default -> new AlgorithmicRankingStrategy(engRepo);
        };
    }

    // --- Services ---
    public static FeedService createFeedService(PostRepository postRepo,
                                                 FollowRepository followRepo,
                                                 RankingStrategy ranker,
                                                 FanoutStrategy fanout) {
        return new FeedService(postRepo, followRepo, ranker, fanout);
    }
}
```

### ASCII Diagram

```
  AppConfig (Factory / Composition Root)
  +--------------------------------------------+
  | Creates and wires:                         |
  |                                            |
  | Repositories:                              |
  |   postRepo   = new InMemoryPostRepo        |
  |   userRepo   = new InMemoryUserRepo        |
  |   followRepo = new InMemoryFollowRepo      |
  |   engRepo    = new InMemoryEngagementRepo  |
  |                                            |
  | Strategies:                                |
  |   writeStrategy  = new FanoutOnWrite(      |
  |                        timelineRepo)       |
  |   readStrategy   = new FanoutOnRead()      |
  |   fanoutStrategy = new HybridFanout(       |
  |                     write, read)           |
  |   rankingStrategy = new Algorithmic(       |
  |                      engRepo)              |
  |                                            |
  | Services:                                  |
  |   feedService = new FeedService(           |
  |                  postRepo, followRepo,     |
  |                  rankingStrategy,          |
  |                  fanoutStrategy)           |
  |   notificationService = new NotifService() |
  +--------------------------------------------+
```

### Numbered Call Chain

```
1. Main calls AppConfig.createPostRepository() -> InMemoryPostRepository
2. Main calls AppConfig.createFollowRepository() -> InMemoryFollowRepository
3. Main calls AppConfig.createEngagementRepository() -> InMemoryEngagementRepository
4. Main calls AppConfig.createFanoutStrategy(timelineRepo) -> HybridFanoutStrategy
5. Main calls AppConfig.createRankingStrategy("algorithmic", engRepo) -> AlgorithmicRankingStrategy
6. Main calls AppConfig.createFeedService(postRepo, followRepo, ranker, fanout) -> FeedService
7. FeedService is fully wired -- ready to serve feeds
```

### Interview One-Liner

> "AppConfig is the composition root -- it wires strategies, repositories,
> and services. Switch from in-memory to Redis by changing one line here.
> In Spring Boot, this becomes `@Configuration` with `@Bean` methods."

### Cross-Reference

- **Strategy (Section 1):** AppConfig decides which FanoutStrategy and RankingStrategy to inject
- **Composite (Section 2):** AppConfig assembles HybridFanoutStrategy from write + read
- **Repository (Section 5):** AppConfig creates all four repositories

---

## 5. Repository Pattern (Structural / Enterprise)

### Why

Abstracts data access behind interfaces. The in-memory implementations
used for interview demos can be swapped for Redis/Cassandra/PostgreSQL
in production without changing business logic.

### Ugly Anti-Pattern -- Direct Data Access in Services

```java
// UGLY: Service directly manipulates the data store.
// Cannot swap from HashMap to Redis without rewriting the service.
// Cannot unit test without a real data store.

public class UglyFeedService {

    // Direct access to storage -- tightly coupled
    private final Map<String, List<Post>> userPosts = new HashMap<>();
    private final Map<String, Set<String>> followers = new HashMap<>();
    private final Map<String, Integer> likeCounts = new HashMap<>();

    public void savePost(Post post) {
        userPosts.computeIfAbsent(post.getAuthorId(), k -> new ArrayList<>()).add(post);
    }

    public List<Post> getPostsByUser(String userId) {
        return userPosts.getOrDefault(userId, Collections.emptyList());
    }

    public void like(String postId) {
        likeCounts.merge(postId, 1, Integer::sum);
    }

    // Testing this requires the full UglyFeedService -- cannot mock storage
}
```

### Clean Solution -- Four Repository Interfaces

```java
// CLEAN: Four repositories, each abstracting a distinct data concern.
// Business logic depends on interfaces, not concrete storage.

public interface PostRepository {
    void save(Post post);
    Post findById(String postId);
    List<Post> findRecentByUser(String userId, int limit);
    void delete(String postId);
}

public interface UserRepository {
    void save(User user);
    User findById(String userId);
    User findByEmail(String email);
}

public interface FollowRepository {
    void follow(String followerId, String followeeId);
    void unfollow(String followerId, String followeeId);
    Set<String> getFollowers(String userId);
    Set<String> getFollowing(String userId);
    boolean isFollowing(String followerId, String followeeId);
    int getFollowerCount(String userId);
}

public interface EngagementRepository {
    void recordLike(String userId, String postId);
    void recordComment(String userId, String postId);
    void recordShare(String userId, String postId);
    int getLikeCount(String postId);
    int getCommentCount(String postId);
    double getAffinityScore(String userId, String authorId);
}
```

### ASCII Diagram

```
  FeedService / FanoutService / NotificationService
       |              |               |
       v              v               v
  +-----------+ +-----------+ +-----------+ +-----------+
  | Post      | | User      | | Follow    | | Engagement|
  | Repository| | Repository| | Repository| | Repository|
  | (interface)| | (interface)| | (interface)| | (interface)|
  +-----------+ +-----------+ +-----------+ +-----------+
       |              |               |              |
       v              v               v              v
  +-----------+ +-----------+ +-----------+ +-----------+
  |InMemory   | |InMemory   | |InMemory   | |InMemory   |
  |Post       | |User       | |Follow     | |Engagement |
  |Repository | |Repository | |Repository | |Repository |
  +-----------+ +-----------+ +-----------+ +-----------+
       |              |               |              |
       | (swap in production)                        |
       v              v               v              v
  +-----------+ +-----------+ +-----------+ +-----------+
  |Cassandra/ | |PostgreSQL | |Redis Set  | |Redis Hash |
  |DynamoDB   | |           | |           | |+ ML model |
  +-----------+ +-----------+ +-----------+ +-----------+
```

### Four Repositories -- Responsibilities

| Repository | Backing Store (Prod) | Key Operations |
|------------|---------------------|----------------|
| PostRepository | Cassandra/DynamoDB | save, findById, findRecentByUser(userId, limit), delete |
| UserRepository | PostgreSQL | save, findById, findByEmail |
| FollowRepository | Redis Sets | follow, unfollow, getFollowers, getFollowing, isFollowing |
| EngagementRepository | Redis Hash + ML | recordLike, recordComment, getAffinityScore |

### Numbered Call Chain -- FeedService Using Repositories

```
1. FeedService.getFeed(userId, 50) called
2. FeedService calls timelineCache.getTimeline(userId) -- timeline repo
3. FeedService calls postRepo.findById(postId) for each cached postId
4. FeedService calls followRepo.getFollowing(userId) to find celebrity followees
5. FeedService calls postRepo.findRecentByUser(celebId, 10) for each celebrity
6. FeedService calls engagementRepo.getAffinityScore(userId, authorId) for ranking
7. All data access goes through interfaces -- in-memory or production, same code
```

### Interview One-Liner

> "Four Repository interfaces decouple business logic from storage.
> In-memory for demos, Cassandra for posts, Redis Sets for social graph,
> PostgreSQL for users, Redis Hash for engagement. Swap with zero service changes."

### Cross-Reference

- **Factory (Section 4):** AppConfig creates all four repository implementations
- **Facade (Section 6):** FeedService orchestrates calls across all repositories
- **Technologies:** See TECHNOLOGIES.md for production backing stores

---

## 6. Facade Pattern (Structural)

### Why

FeedService is a Facade that hides the complexity of feed generation from
callers. Behind `getFeed()`, it orchestrates timeline cache lookup, celebrity
post pull, merge, dedup, ranking, and cursor-based pagination. The caller
sees one method call.

### Ugly Anti-Pattern -- Caller Orchestrates Everything

```java
// UGLY: The API controller orchestrates the entire feed pipeline.
// 15+ lines of complex logic in the controller layer.
// Every new endpoint that needs a feed duplicates this.

@RestController
public class UglyFeedController {

    @GetMapping("/feed")
    public List<Post> getFeed(@RequestParam String userId,
                               @RequestParam int limit) {
        // Step 1: get timeline from cache
        List<String> postIds = redisTemplate.opsForZSet()
                .reverseRange("timeline:" + userId, 0, 200);

        // Step 2: hydrate posts
        List<Post> pushed = new ArrayList<>();
        for (String pid : postIds) {
            Post p = postRepository.findById(pid);
            if (p != null && !p.isDeleted()) pushed.add(p);
        }

        // Step 3: get celebrity followees
        Set<String> following = followRepo.getFollowing(userId);
        List<Post> pulled = new ArrayList<>();
        for (String fid : following) {
            User u = userRepo.findById(fid);
            if (u.isCelebrity()) {
                pulled.addAll(postRepository.findRecentByUser(fid, 10));
            }
        }

        // Step 4: merge and dedup
        Map<String, Post> seen = new LinkedHashMap<>();
        for (Post p : pushed) seen.putIfAbsent(p.getPostId(), p);
        for (Post p : pulled) seen.putIfAbsent(p.getPostId(), p);
        List<Post> merged = new ArrayList<>(seen.values());

        // Step 5: rank (inline)
        merged.sort((a, b) -> Double.compare(
                b.getLikeCount() * 1.0 + b.getCommentCount() * 2.0,
                a.getLikeCount() * 1.0 + a.getCommentCount() * 2.0));

        // Step 6: paginate
        return merged.subList(0, Math.min(limit, merged.size()));
    }
}
```

### Clean Solution -- FeedService Facade

```java
// CLEAN: FeedService encapsulates the entire feed pipeline.
// The controller calls one method. All complexity is hidden.

public class FeedService {

    private final TimelineRepository    timelineRepo;
    private final PostRepository        postRepo;
    private final FollowRepository      followRepo;
    private final EngagementRepository  engRepo;
    private final RankingStrategy       ranker;

    public FeedService(TimelineRepository timelineRepo,
                       PostRepository postRepo,
                       FollowRepository followRepo,
                       EngagementRepository engRepo,
                       RankingStrategy ranker) {
        this.timelineRepo = timelineRepo;
        this.postRepo     = postRepo;
        this.followRepo   = followRepo;
        this.engRepo      = engRepo;
        this.ranker       = ranker;
    }

    /**
     * Generate a ranked, paginated feed for the user.
     * This single method hides 7 steps of complexity.
     */
    public FeedPage getFeed(String userId, int limit, String cursor) {
        // 1. Get pre-computed timeline (fan-out-on-write items)
        List<FeedItem> pushed = getPushedItems(userId);

        // 2. Pull celebrity posts (fan-out-on-read)
        List<FeedItem> pulled = getCelebrityPosts(userId);

        // 3. Merge and dedup
        List<FeedItem> merged = mergeAndDedup(pushed, pulled);

        // 4. Rank
        List<FeedItem> ranked = ranker.rank(merged, userId);

        // 5. Apply cursor (pagination)
        List<FeedItem> page = applyCursor(ranked, cursor, limit);

        // 6. Build next cursor
        String nextCursor = buildNextCursor(page);

        return new FeedPage(page, nextCursor);
    }

    // ... private helper methods for each step
}

// Controller is now trivial
@RestController
public class CleanFeedController {

    private final FeedService feedService;

    @GetMapping("/feed")
    public FeedPage getFeed(@RequestParam String userId,
                            @RequestParam(defaultValue = "20") int limit,
                            @RequestParam(required = false) String cursor) {
        return feedService.getFeed(userId, limit, cursor);
    }
}
```

### ASCII Diagram

```
  Client: GET /feed?userId=user-42&limit=20&cursor=abc123
       |
       v
  +--------------------------------------------------+
  |              FeedService (Facade)                 |
  +--------------------------------------------------+
  | 1. timelineRepo.getTimeline(userId)              |  <-- Redis Sorted Set
  |    -> List<postId> from cache                    |
  |                                                  |
  | 2. postRepo.findById(each id)                   |  <-- Cassandra / Post cache
  |    -> List<FeedItem> pushed                      |
  |                                                  |
  | 3. followRepo.getFollowing(userId)               |  <-- Redis Set
  |    -> filter celebrities                         |
  |                                                  |
  | 4. postRepo.findRecentByUser(celebId, 10)        |  <-- Pull celebrity posts
  |    -> List<FeedItem> pulled                      |
  |                                                  |
  | 5. MERGE pushed + pulled                         |
  |    -> dedup by postId                            |
  |                                                  |
  | 6. ranker.rank(merged, userId)                   |  <-- Strategy! (algo/chrono)
  |                                                  |
  | 7. applyCursor(ranked, cursor, limit)            |  <-- Cursor pagination
  |    -> return FeedPage(items, nextCursor)          |
  +--------------------------------------------------+
       |
       v
  FeedPage { items: [...], nextCursor: "..." }
```

### Numbered Call Chain

```
1. Controller receives GET /feed?userId=user-42&limit=20&cursor=abc123
2. Controller calls feedService.getFeed("user-42", 20, "abc123")
3. FeedService reads timeline from cache (timelineRepo)
4. FeedService hydrates posts (postRepo)
5. FeedService finds celebrity followees (followRepo)
6. FeedService pulls celebrity posts (postRepo)
7. FeedService merges and dedups by postId
8. FeedService delegates to ranker.rank() (Strategy)
9. FeedService applies cursor-based pagination
10. FeedService returns FeedPage with items + next cursor
11. Controller returns FeedPage to client
```

### Interview One-Liner

> "FeedService is a Facade -- it hides the 7-step feed generation pipeline
> (cache read, celebrity pull, merge, dedup, rank, paginate, cursor) behind
> a single `getFeed()` call. The controller is one line."

### Cross-Reference

- **Strategy (Section 1):** FeedService delegates ranking to RankingStrategy
- **Repository (Section 5):** FeedService uses all four repositories
- **Iterator (Section 8):** FeedService uses FeedCursor for pagination
- **Mediator (Section 9):** FeedService also acts as a Mediator

---

## 7. Observer Pattern (Behavioral)

### Why

When a post is published or an engagement event occurs (like, comment, share),
multiple independent subsystems must react: fan-out to followers, update
engagement counts, send push notifications. Observer decouples the publisher
from all consumers.

### Ugly Anti-Pattern -- Direct Calls to Every Consumer

```java
// UGLY: PostService directly calls every downstream system.
// Adding a new consumer (e.g., analytics) requires editing PostService.
// If NotificationService is slow, it blocks post publishing.

public class UglyPostService {

    private final FanoutService fanoutService;
    private final NotificationService notificationService;
    private final EngagementService engagementService;
    private final SearchIndexer searchIndexer;
    private final AnalyticsService analyticsService;  // added later -- must edit!

    public void publishPost(Post post, User author) {
        postRepo.save(post);

        // Direct calls to every consumer -- tightly coupled
        List<String> followers = followRepo.getFollowers(author.getUserId());
        fanoutService.distribute(post, author, followers);
        notificationService.notifyFollowers(post, author);
        engagementService.initializeCounts(post);
        searchIndexer.index(post);
        analyticsService.trackPostCreation(post);  // had to add this line
        // Next consumer? Edit this method AGAIN.
    }
}
```

### Clean Solution -- Observer via Event Listeners

```java
// CLEAN: PostService publishes events. Consumers register as listeners.
// Adding a new consumer requires zero changes to PostService.

public interface PostEventListener {
    void onPostPublished(Post post, User author);
    void onPostLiked(String userId, Post post);
    void onPostCommented(String userId, Post post, String comment);
    void onPostShared(String userId, Post post);
}

public class PostPublisher {
    private final List<PostEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(PostEventListener listener) {
        listeners.add(listener);
    }

    public void publishPost(Post post, User author) {
        postRepo.save(post);
        // Notify all observers -- PostPublisher does not know who they are
        for (PostEventListener listener : listeners) {
            listener.onPostPublished(post, author);
        }
    }

    public void likePost(String userId, Post post) {
        post.incrementLikeCount();
        for (PostEventListener listener : listeners) {
            listener.onPostLiked(userId, post);
        }
    }
}

// NotificationService is just another observer
public class NotificationService implements PostEventListener {

    @Override
    public void onPostPublished(Post post, User author) {
        // Send push notification to close friends
        Set<String> closeFriends = getCloseFriends(author.getUserId());
        for (String friendId : closeFriends) {
            pushNotification(friendId, author.getName() + " posted: " + post.getContent());
        }
    }

    @Override
    public void onPostLiked(String userId, Post post) {
        // Notify post author that someone liked their post
        pushNotification(post.getAuthorId(), userId + " liked your post");
    }

    @Override
    public void onPostCommented(String userId, Post post, String comment) {
        pushNotification(post.getAuthorId(), userId + " commented on your post");
    }

    @Override
    public void onPostShared(String userId, Post post) {
        pushNotification(post.getAuthorId(), userId + " shared your post");
    }
}
```

### ASCII Diagram

```
  PostPublisher (Subject)
  +---------------------------+
  | - listeners: List<        |
  |     PostEventListener>    |
  +---------------------------+
  | + publishPost(post, user) |
  | + likePost(uid, post)     |
  | + commentPost(uid, post)  |
  | + addListener(listener)   |
  +---------------------------+
           |
           | event fired
           |
     +-----+------+-------+-----------+
     |            |        |           |
  FanoutSvc  NotifSvc  EngageSvc  SearchIndexer
  (push to    (push     (update    (index in
   timelines)  notif)    counts)    Elasticsearch)
```

### Events and Their Observers

| Subject | Event | Observers |
|---------|-------|-----------|
| PostPublisher | post.published | FanoutService, NotificationService, SearchIndexer |
| PostPublisher | post.liked | NotificationService, EngagementService |
| PostPublisher | post.commented | NotificationService, EngagementService |
| PostPublisher | post.shared | NotificationService, FanoutService (reshare) |
| PostPublisher | post.deleted | TimelineCache (ZREM), SearchIndexer (remove) |

### In Production: Kafka Replaces In-Process Observer

```
  Post Published
       |
       v
  +------------------+
  | Kafka Topic:     |
  | "post.events"    |
  +------------------+
       |
       +---> Consumer Group: fanout-service
       |     (partitioned by author userId)
       |
       +---> Consumer Group: notification-service
       |     (push notifications for close friends, likes, comments)
       |
       +---> Consumer Group: engagement-updater
       |     (update like/comment/share counts in Redis)
       |
       +---> Consumer Group: search-indexer
             (Elasticsearch bulk index)
```

### Numbered Call Chain -- Like Event

```
1. User taps "Like" on post-123
2. API calls PostPublisher.likePost("user-7", post)
3. PostPublisher increments post.likeCount
4. PostPublisher iterates listeners, calls onPostLiked("user-7", post)
5. NotificationService.onPostLiked() sends push to post author
6. EngagementService.onPostLiked() updates Redis counter: HINCRBY engagement:post-123 likes 1
7. EngagementService updates affinity score between user-7 and author
```

### Interview One-Liner

> "Post events (publish, like, comment, share) fire Observer notifications.
> NotificationService, FanoutService, EngagementService, and SearchIndexer
> consume independently. In production this becomes Kafka with consumer groups."

### Cross-Reference

- **Strategy (Section 1):** FanoutService (observer) uses FanoutStrategy to distribute
- **Facade (Section 6):** FeedService uses engagement data updated by EngagementService (observer)
- **Technologies:** See TECHNOLOGIES.md for Kafka topic design

---

## 8. Iterator Pattern (Behavioral)

### Why

Cursor-based pagination provides efficient, consistent pagination through
a feed that is constantly changing. Unlike offset-based pagination (OFFSET/LIMIT),
cursor-based does not suffer from duplicate or missed items when new posts
are inserted.

### Ugly Anti-Pattern -- Offset-Based Pagination

```java
// UGLY: Offset-based pagination breaks when new items are inserted.
// Page 2 may repeat items from page 1, or skip items entirely.

public class UglyPagination {

    public List<Post> getFeed(String userId, int page, int pageSize) {
        // OFFSET = page * pageSize
        List<Post> allPosts = getAllRankedPosts(userId);

        int offset = page * pageSize;
        int end = Math.min(offset + pageSize, allPosts.size());

        if (offset >= allPosts.size()) return Collections.emptyList();
        return allPosts.subList(offset, end);
    }
}

// PROBLEM: new posts inserted between page requests
//
// Time T1: User loads page 0 (items 0-19)
//   Feed: [A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]
//   User sees: [A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]
//
// Time T2: New post Z inserted at position 0
//   Feed: [Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]
//
// Time T3: User loads page 1 (offset=20)
//   User sees: [T] -- skipped nothing new, but T is a REPEAT from page 0!
```

### Clean Solution -- Cursor-Based Pagination (FeedCursor)

```java
// CLEAN: Cursor encodes the last seen item's score + postId.
// "Give me items scored LOWER than this cursor."
// Immune to insertions at the top of the feed.

public class FeedCursor {

    private final double lastScore;
    private final String lastPostId;

    public FeedCursor(double lastScore, String lastPostId) {
        this.lastScore  = lastScore;
        this.lastPostId = lastPostId;
    }

    // Encode cursor as opaque string for the client
    public String encode() {
        return Base64.getEncoder().encodeToString(
                (lastScore + "|" + lastPostId).getBytes());
    }

    // Decode cursor from client request
    public static FeedCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String decoded = new String(Base64.getDecoder().decode(encoded));
        String[] parts = decoded.split("\\|", 2);
        return new FeedCursor(Double.parseDouble(parts[0]), parts[1]);
    }

    public double getLastScore() { return lastScore; }
    public String getLastPostId() { return lastPostId; }
}

// FeedService uses the cursor
public class FeedService {

    public FeedPage getFeed(String userId, int limit, String cursorStr) {
        List<FeedItem> ranked = getRankedItems(userId);

        // Apply cursor: skip items at or above the cursor score
        FeedCursor cursor = FeedCursor.decode(cursorStr);
        List<FeedItem> page = applyCursor(ranked, cursor, limit);

        // Build next cursor from the last item in this page
        String nextCursor = null;
        if (!page.isEmpty()) {
            FeedItem last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(last.getScore(), last.getPostId()).encode();
        }

        return new FeedPage(page, nextCursor);
    }

    private List<FeedItem> applyCursor(List<FeedItem> items,
                                        FeedCursor cursor, int limit) {
        if (cursor == null) {
            // First page -- no cursor, return top N
            return items.subList(0, Math.min(limit, items.size()));
        }

        // Find items after the cursor position
        List<FeedItem> afterCursor = new ArrayList<>();
        boolean pastCursor = false;

        for (FeedItem item : items) {
            if (pastCursor) {
                afterCursor.add(item);
                if (afterCursor.size() >= limit) break;
            } else if (item.getScore() < cursor.getLastScore()
                    || (item.getScore() == cursor.getLastScore()
                        && item.getPostId().equals(cursor.getLastPostId()))) {
                pastCursor = true;
                // If score matches exactly, skip the cursor item itself
                if (item.getScore() < cursor.getLastScore()) {
                    afterCursor.add(item);
                    if (afterCursor.size() >= limit) break;
                }
            }
        }
        return afterCursor;
    }
}
```

### ASCII Diagram -- Cursor Pagination vs Offset Pagination

```
OFFSET-BASED (broken):

  Page 0 (offset=0):  [A(100), B(95), C(90), D(85), E(80)]
  -- new post Z(110) inserted --
  Page 1 (offset=5):  [E(80), F(75), G(70), H(65), I(60)]
                        ^
                        E is duplicated! User sees it twice.

CURSOR-BASED (correct):

  Page 0:             [A(100), B(95), C(90), D(85), E(80)]
  cursor = "80|E"      (score=80, postId=E)
  -- new post Z(110) inserted --
  Page 1 (score < 80): [F(75), G(70), H(65), I(60), J(55)]
                        No duplicates. Z appears on next refresh, not mid-scroll.
```

### Redis Implementation

```
Cursor pagination maps directly to Redis ZREVRANGEBYSCORE:

  First page:
    ZREVRANGEBYSCORE timeline:{userId} +inf -inf LIMIT 0 20
    -> Returns top 20 items by score

  Next page (cursor score = 80.0):
    ZREVRANGEBYSCORE timeline:{userId} (80.0 -inf LIMIT 0 20
    -> Returns next 20 items with score < 80.0
    -> The "(" prefix means exclusive (skip score 80.0 itself)

  Tie-breaking (two posts with same score):
    Use postId as secondary sort (lexicographic within same score)
    Or: add microsecond jitter to score to ensure uniqueness
```

### Numbered Call Chain

```
1. Client calls GET /feed?userId=user-42&limit=20
2. FeedService generates ranked feed, returns page + cursor "MTAwLjB8cG9zdC01"
3. Client calls GET /feed?userId=user-42&limit=20&cursor=MTAwLjB8cG9zdC01
4. FeedService decodes cursor: score=100.0, postId=post-5
5. FeedService generates ranked feed (same as step 2)
6. FeedService applies cursor: skip items with score >= 100.0 (or same score + same postId)
7. FeedService returns next page + new cursor
8. Client calls again with new cursor for page 3, etc.
9. When page is empty or cursor is null, client stops
```

### FeedPage Response Object

```java
public class FeedPage {
    private final List<FeedItem> items;
    private final String nextCursor;  // null if no more pages
    private final boolean hasMore;

    public FeedPage(List<FeedItem> items, String nextCursor) {
        this.items      = Collections.unmodifiableList(items);
        this.nextCursor = nextCursor;
        this.hasMore    = nextCursor != null;
    }
}
```

### Interview One-Liner

> "We use cursor-based pagination, not offset-based. The cursor encodes the
> last item's score and postId. This maps to Redis ZREVRANGEBYSCORE with an
> exclusive lower bound. Immune to new posts being inserted at the top."

### Cross-Reference

- **Facade (Section 6):** FeedService.getFeed() returns FeedPage with cursor
- **Technologies:** Redis ZREVRANGEBYSCORE implements the cursor natively (see TECHNOLOGIES.md)

---

## 9. Mediator Pattern (Behavioral)

### Why

FeedService mediates between FanoutService, TimelineService, and RankingService.
These three services do not communicate directly -- they all go through FeedService,
which coordinates the overall feed generation pipeline.

Note: Facade (Section 6) and Mediator overlap here. The Facade aspect hides
complexity from the caller. The Mediator aspect coordinates communication
between internal components that do not know about each other.

### Ugly Anti-Pattern -- Services Call Each Other Directly

```java
// UGLY: Services reference each other, creating a dependency web.
// Circular dependencies, unclear ownership, hard to test.

public class UglyFanoutService {
    private final UglyTimelineService timelineService;
    private final UglyRankingService rankingService;  // why does fanout know about ranking?

    public void distribute(Post post, User author) {
        List<String> followers = getFollowers(author.getUserId());
        for (String fid : followers) {
            timelineService.addToTimeline(fid, post);
            rankingService.recomputeScore(fid, post); // circular: fanout -> ranking
        }
    }
}

public class UglyRankingService {
    private final UglyTimelineService timelineService;
    private final UglyFanoutService fanoutService;  // circular: ranking -> fanout

    public void rerank(String userId) {
        List<Post> timeline = timelineService.getTimeline(userId);
        // rank them...
        // Does ranking need to know about fanout? No!
    }
}

// Dependency graph: Fanout <-> Ranking <-> Timeline -- circular mess
```

### Clean Solution -- FeedService as Mediator

```java
// CLEAN: FeedService mediates. Sub-services do not reference each other.
// Each sub-service has a single responsibility and no circular deps.

public class TimelineService {
    private final TimelineRepository timelineRepo;
    private final PostRepository postRepo;

    // TimelineService knows NOTHING about RankingService or FanoutService
    public List<FeedItem> getPushedItems(String userId) {
        List<String> postIds = timelineRepo.getTimeline(userId, 200);
        return postIds.stream()
                .map(postRepo::findById)
                .filter(Objects::nonNull)
                .filter(p -> !p.isDeleted())
                .map(p -> new FeedItem(p, 0.0))
                .collect(Collectors.toList());
    }
}

public class RankingService {
    private final RankingStrategy strategy;

    // RankingService knows NOTHING about TimelineService or FanoutService
    public List<FeedItem> rank(List<FeedItem> items, String userId) {
        return strategy.rank(items, userId);
    }
}

public class FanoutService {
    private final FanoutStrategy strategy;
    private final FollowRepository followRepo;

    // FanoutService knows NOTHING about RankingService or TimelineService
    public void distribute(Post post, User author) {
        Set<String> followers = followRepo.getFollowers(author.getUserId());
        strategy.fanout(post, author, new ArrayList<>(followers));
    }
}

// FeedService is the MEDIATOR -- it coordinates all three
public class FeedService {

    private final TimelineService timelineService;
    private final RankingService  rankingService;
    private final FanoutService   fanoutService;
    private final FollowRepository followRepo;
    private final PostRepository   postRepo;

    public FeedPage getFeed(String userId, int limit, String cursor) {
        // Mediate: get pushed items from TimelineService
        List<FeedItem> pushed = timelineService.getPushedItems(userId);

        // Mediate: get pulled celebrity items
        List<FeedItem> pulled = getCelebrityPosts(userId);

        // Merge + dedup
        List<FeedItem> merged = mergeAndDedup(pushed, pulled);

        // Mediate: delegate ranking to RankingService
        List<FeedItem> ranked = rankingService.rank(merged, userId);

        // Paginate
        return paginate(ranked, cursor, limit);
    }

    public void publishPost(Post post, User author) {
        // Mediate: delegate distribution to FanoutService
        fanoutService.distribute(post, author);
    }
}
```

### ASCII Diagram -- Mediator Eliminates Direct Dependencies

```
WITHOUT Mediator (dependency web):

  FanoutService <-------> RankingService
        |                      |
        v                      v
  TimelineService <----------->
  (circular dependencies everywhere)


WITH Mediator (star topology):

                 FeedService
                 (Mediator)
                /     |      \
               v      v       v
       FanoutSvc  TimelineSvc  RankingSvc
       (no refs   (no refs     (no refs
        to others) to others)   to others)
```

### Numbered Call Chain -- Full Feed Generation via Mediator

```
1. Controller calls feedService.getFeed("user-42", 20, cursor)
2. FeedService (mediator) calls timelineService.getPushedItems("user-42")
3. TimelineService reads Redis sorted set, hydrates posts from PostRepository
4. TimelineService returns List<FeedItem> to FeedService (mediator)
5. FeedService (mediator) calls followRepo.getFollowing("user-42")
6. FeedService filters celebrity followees
7. FeedService calls postRepo.findRecentByUser(celebId, 10) for each celebrity
8. FeedService merges pushed + pulled, dedups by postId
9. FeedService (mediator) calls rankingService.rank(merged, "user-42")
10. RankingService delegates to AlgorithmicRankingStrategy, returns ranked list
11. FeedService applies cursor pagination, returns FeedPage
```

### Interview One-Liner

> "FeedService is a Mediator -- TimelineService, RankingService, and
> FanoutService do not reference each other. FeedService coordinates:
> get pushed items, pull celebrities, merge, rank, paginate. Star topology
> instead of dependency web."

### Tradeoffs

| Pro | Con |
|-----|-----|
| No circular dependencies between sub-services | FeedService can become a god object if not careful |
| Each sub-service is independently testable | Adding a step means modifying FeedService |
| Clear single point of orchestration | All feed logic funnels through one class |
| Easy to add metrics/logging at coordination points | FeedService constructor grows with more dependencies |

### Cross-Reference

- **Facade (Section 6):** Same class, dual role. Facade hides complexity from caller; Mediator coordinates internal services
- **Strategy (Section 1):** RankingService delegates to RankingStrategy, FanoutService delegates to FanoutStrategy

---

## Pattern Interaction Map

```
  Post.Builder (3)          Observer (7)               Strategy (1a)
  builds Post -----> PostPublisher -----> FanoutService -----> HybridFanout (2)
                           |                                   /         \
                           |                          FanoutOnWrite   FanoutOnRead
                           |
                           +-----> NotificationService (Observer consumer)
                           +-----> EngagementService (Observer consumer)
                           +-----> SearchIndexer (Observer consumer)


  GET /feed
     |
     v
  FeedService (6-Facade, 9-Mediator)
     |
     +-- TimelineService ---- TimelineRepository (5) -- pushed items
     +-- PostRepository (5) ---- post content lookup
     +-- FollowRepository (5) ---- celebrity detection
     +-- RankingService ---- RankingStrategy (1b) -- score + sort
     +-- FeedCursor (8-Iterator) -- cursor pagination
     |
     v
  FeedPage { items, nextCursor }

  AppConfig (4-Factory) wires all of the above.
```

---

## Quick Reference Table

| Pattern | GoF Category | Where | Solves |
|---------|-------------|-------|--------|
| Strategy (FanoutStrategy) | Behavioral | Fan-out path selection | Celebrity problem |
| Strategy (RankingStrategy) | Behavioral | Feed scoring + ordering | Chrono vs. algorithmic ranking |
| Composite | Structural | HybridFanoutStrategy | Compose push + pull |
| Builder | Creational | Post | Complex object with validation + derived fields |
| Factory | Creational | AppConfig | Centralize wiring / composition root |
| Repository | Enterprise | Data access (4 repos) | Swap in-memory for production stores |
| Facade | Structural | FeedService | Hide 7-step feed pipeline behind one method |
| Observer | Behavioral | Post events | Decouple publish from consume (notifications, engagement) |
| Iterator | Behavioral | FeedCursor | Cursor-based pagination immune to insertions |
| Mediator | Behavioral | FeedService | Coordinate TimelineService, RankingService, FanoutService |

---

## Differentiator from Project 05 (Social Media Feed)

| Aspect | Project 05 | Project 12 (This Project) |
|--------|-----------|--------------------------|
| Ranking | EngagementRanker (likes + retweets + replies) | AlgorithmicRankingStrategy (affinity * recency * engagement * contentTypeWeight) |
| Pagination | Simple limit/trim | Cursor-based (FeedCursor, immune to insertions) |
| Observer events | tweet.published, tweet.deleted | post.published, post.liked, post.commented, post.shared |
| Repositories | 5 repos (incl. Trending) | 4 repos (incl. Engagement with affinity scoring) |
| Content types | Text only (280 char) | TEXT, IMAGE, VIDEO, LINK, POLL with ranking weights |
| Facade vs. Mediator | Mediator only (FeedService) | Both Facade + Mediator (same FeedService, dual role) |
| Notification depth | Mentioned briefly | Full NotificationService with event-specific handling |
| Production concerns | Basic | Cache warming, cursor pagination, ML ranking, real-time push |
