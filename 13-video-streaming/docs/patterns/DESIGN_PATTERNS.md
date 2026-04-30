# Design Patterns -- Video Streaming Platform (YouTube/Netflix)

> Quick reference for system design interviews. Each pattern includes the ugly
> anti-pattern first, then the clean solution, numbered call chain, ASCII diagram,
> and a one-liner you can drop in an interview.
>
> **Domain:** Upload, transcode, store, and stream video at scale. Three Strategy
> interfaces (transcoding, adaptive bitrate, recommendation) make this the most
> strategy-heavy project in the repo.

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Strategy (x3) | Behavioral | TranscodingStrategy (Parallel, Sequential), ABRStrategy (Throughput, Buffer), RecommendationStrategy (Trending, Personalized) |
| 2 | Builder | Creational | Video.Builder with metadata, validation, immutable result |
| 3 | Factory | Creational | AppConfig wires strategies, repos, services |
| 4 | Repository | Structural (enterprise) | 3 repositories (Video, User, WatchHistory) |
| 5 | Facade | Structural | VideoService orchestrates upload -> transcode -> store -> stream |
| 6 | Observer | Behavioral | AnalyticsService observes video events (views, likes) |
| 7 | State | Behavioral | Video state machine (UPLOADING -> UPLOADED -> TRANSCODING -> READY) |
| 8 | Pipeline | Enterprise | Transcoding DAG: upload -> split -> parallel transcode -> stitch -> store |
| 9 | Template Method | Behavioral | TranscodingStrategy defines common flow, subclasses implement specifics |

---

## 1. Strategy Pattern (Behavioral) -- THE KEY PATTERN

**Three independent Strategy interfaces** power the core of this system.
This is the pattern interviewers care about most -- it directly answers
"How do you transcode efficiently?", "How does adaptive bitrate work?",
and "How do you recommend videos?"

### Strategy Interface A: TranscodingStrategy

Determines **how** a raw video is transcoded into multiple resolutions and codecs.

```java
public interface TranscodingStrategy {
    /**
     * Transcode a raw video into the target resolution profiles.
     * @param videoId   the video being transcoded
     * @param rawPath   path to the raw uploaded file in object storage
     * @param profiles  target resolution/codec profiles (e.g., 1080p H.264, 720p VP9)
     * @return          list of transcoded segment paths in object storage
     */
    List<String> transcode(String videoId, String rawPath, List<TranscodeProfile> profiles);
}
```

Two concrete strategies:

| Strategy | When Used | Cost Model |
|----------|-----------|------------|
| ParallelTranscodingStrategy | Default -- all profiles transcoded concurrently | O(1) wall-clock per profile (parallel), O(N) compute |
| SequentialTranscodingStrategy | Budget tier, low-priority uploads | O(N) wall-clock (one profile at a time), O(N) compute |

### Strategy Interface B: ABRStrategy (Adaptive Bitrate)

Determines **which quality level** the player requests next based on network conditions.

```java
public interface ABRStrategy {
    /**
     * Select the next video segment quality based on current network stats.
     * @param availableBitrates  sorted list of available bitrate levels (bps)
     * @param throughputBps      estimated network throughput (bps)
     * @param bufferLevelSec     current player buffer in seconds
     * @return                   selected bitrate for the next segment
     */
    long selectBitrate(List<Long> availableBitrates, long throughputBps, double bufferLevelSec);
}
```

Two concrete strategies:

| Strategy | Algorithm | Use Case |
|----------|-----------|----------|
| ThroughputBasedABR | Pick highest bitrate <= 0.85 * throughput | Simple, works for stable connections |
| BufferBasedABR | Map buffer level to quality (low buffer = low quality) | Better for fluctuating networks |

### Strategy Interface C: RecommendationStrategy

Determines **which videos** to suggest to a user.

```java
public interface RecommendationStrategy {
    /**
     * Generate video recommendations for a user.
     * @param userId        the user requesting recommendations
     * @param watchHistory  user's recent watch history
     * @param limit         max number of recommendations
     * @return              ranked list of recommended video IDs
     */
    List<String> recommend(String userId, List<WatchHistoryEntry> watchHistory, int limit);
}
```

Two concrete strategies:

| Strategy | Algorithm | Use Case |
|----------|-----------|----------|
| TrendingRecommendationStrategy | Rank by global view velocity (views/hour) | Cold-start users, trending page |
| PersonalizedRecommendationStrategy | Collaborative filtering + content similarity | Logged-in users with watch history |

### Ugly Anti-Pattern -- Hardcoded Transcoding + ABR + Recommendations

```java
// UGLY: Transcoding mode, ABR algorithm, and recommendation logic
// all hardcoded in one service. Adding a new approach requires editing
// this class. No way to A/B test different strategies per user cohort.

public class UglyVideoService {

    public void processUpload(String videoId, String rawPath) {
        // Hardcoded transcoding -- cannot swap parallel/sequential
        List<String> profiles = List.of("1080p", "720p", "480p", "360p");
        for (String profile : profiles) {
            // Always sequential -- no way to switch to parallel
            ffmpegTranscode(rawPath, profile);
            System.out.println("Transcoded " + videoId + " to " + profile);
        }
    }

    public String selectQuality(List<Long> bitrates, long throughput,
                                double bufferSec) {
        // Hardcoded ABR -- switch statement grows with every new algorithm
        // No way to swap between throughput-based and buffer-based
        long selected = 0;
        for (long br : bitrates) {
            if (br <= throughput * 0.85) {
                selected = br;
            }
        }
        return String.valueOf(selected);
    }

    public List<String> getRecommendations(String userId) {
        // Hardcoded recommendation -- cannot swap trending vs personalized
        // For new users with no history, still tries personalization (fails)
        return db.query("SELECT video_id FROM videos "
            + "ORDER BY view_count DESC LIMIT 20");
    }
}
```

**Problems:**
1. Transcoding is always sequential -- cannot swap to parallel without editing the method
2. ABR is a hardcoded formula -- adding buffer-based requires modifying `selectQuality()`
3. Recommendations ignore user context -- no way to swap trending vs. personalized
4. Cannot A/B test any of the three algorithms independently
5. Testing requires the full service with DB, FFmpeg, and network stats

### Clean Solution -- Three Strategy Interfaces

```java
// CLEAN: TranscodingStrategy, ABRStrategy, and RecommendationStrategy
// are all injected. Each can be swapped, tested, and A/B tested independently.

public class CleanVideoService {

    private final TranscodingStrategy      transcodingStrategy;
    private final ABRStrategy              abrStrategy;
    private final RecommendationStrategy   recommendationStrategy;
    private final VideoRepository          videoRepo;

    public CleanVideoService(TranscodingStrategy transcodingStrategy,
                             ABRStrategy abrStrategy,
                             RecommendationStrategy recommendationStrategy,
                             VideoRepository videoRepo) {
        this.transcodingStrategy    = transcodingStrategy;
        this.abrStrategy            = abrStrategy;
        this.recommendationStrategy = recommendationStrategy;
        this.videoRepo              = videoRepo;
    }

    public void processUpload(String videoId, String rawPath,
                              List<TranscodeProfile> profiles) {
        // Delegate transcoding entirely to the strategy
        List<String> segmentPaths = transcodingStrategy.transcode(
                videoId, rawPath, profiles);
        videoRepo.storeSegments(videoId, segmentPaths);
    }

    public long selectQuality(List<Long> bitrates, long throughput,
                              double bufferSec) {
        // Delegate ABR entirely to the strategy
        return abrStrategy.selectBitrate(bitrates, throughput, bufferSec);
    }

    public List<String> getRecommendations(String userId,
                                           List<WatchHistoryEntry> history,
                                           int limit) {
        // Delegate recommendations entirely to the strategy
        return recommendationStrategy.recommend(userId, history, limit);
    }
}
```

### ASCII Diagram -- Three Strategy Axes

```
  TranscodingStrategy               ABRStrategy                RecommendationStrategy
 (how video is transcoded)      (which quality next)          (which videos to suggest)
          |                            |                               |
    +-----+-----+              +------+------+                +-------+-------+
    |           |              |             |                |               |
 Parallel   Sequential   Throughput    BufferBased       Trending      Personalized
 (all at     (one by     Based         (map buffer       (global        (collab
  once)       one)       (85% of       level to          view           filtering +
                          throughput)   quality)          velocity)      content sim)
```

### Numbered Call Chain -- Video Upload + Transcode (Parallel)

```
1. Client calls POST /videos/upload with raw video file
2. VideoController receives the request, validates auth + file size
3. VideoController calls VideoService.upload(rawFile, metadata)
4. VideoService builds Video via Video.Builder, sets status = UPLOADING
5. VideoService stores raw file in object storage (S3)
6. VideoService transitions video status: UPLOADING -> UPLOADED
7. VideoService calls TranscodingStrategy.transcode(videoId, rawPath, profiles)
8. ParallelTranscodingStrategy splits raw file into chunks
9. ParallelTranscodingStrategy submits each chunk x profile to thread pool
10. Each thread: ffmpeg encodes chunk at target resolution/codec
11. ParallelTranscodingStrategy stitches results, generates HLS manifest
12. VideoService transitions status: TRANSCODING -> READY
13. Observer: AnalyticsService.onVideoReady(videoId) fires
```

### Numbered Call Chain -- Video Playback (ABR)

```
1. Client requests GET /videos/{videoId}/manifest.m3u8
2. VideoService returns HLS master manifest listing available bitrates
3. Player estimates network throughput from previous segment download time
4. Player calls ABRStrategy.selectBitrate(available, throughput, bufferLevel)
5. ThroughputBasedABR picks highest bitrate <= 0.85 * throughput
6. Player requests the next 4-second segment at the selected bitrate
7. Segment served from CDN edge (cache hit) or origin (cache miss)
8. Player updates throughput estimate, repeats from step 3
```

### Numbered Call Chain -- Recommendations

```
1. Client requests GET /recommendations?userId=user-42&limit=20
2. VideoService calls RecommendationStrategy.recommend(userId, history, 20)
3. PersonalizedRecommendationStrategy loads user's watch history
4. Computes user embedding from watch history genres and tags
5. Finds top-N nearest videos by cosine similarity in embedding space
6. Filters out already-watched videos
7. Blends in 20% trending videos for diversity (explore/exploit)
8. Returns ranked list of 20 video IDs
```

### Concrete Strategy: ParallelTranscodingStrategy

```java
public class ParallelTranscodingStrategy implements TranscodingStrategy {

    private final ExecutorService executor;

    public ParallelTranscodingStrategy(int threadPoolSize) {
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
    }

    @Override
    public List<String> transcode(String videoId, String rawPath,
                                  List<TranscodeProfile> profiles) {
        // Submit all profiles in parallel
        List<Future<String>> futures = new ArrayList<>();
        for (TranscodeProfile profile : profiles) {
            futures.add(executor.submit(() -> {
                // In production: shell out to FFmpeg
                // ffmpeg -i rawPath -vf scale=profile.width:profile.height
                //        -c:v profile.codec -b:v profile.bitrate output.mp4
                String outputPath = String.format("s3://videos/%s/%s_%s.mp4",
                        videoId, profile.getResolution(), profile.getCodec());
                System.out.printf("[Transcode] %s -> %s (%s)%n",
                        videoId, profile.getResolution(), profile.getCodec());
                return outputPath;
            }));
        }

        // Collect results
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            try {
                results.add(f.get());
            } catch (Exception e) {
                throw new TranscodingException(
                        "Transcode failed for " + videoId, e);
            }
        }
        return results;
    }
}
```

### Concrete Strategy: ThroughputBasedABR

```java
public class ThroughputBasedABR implements ABRStrategy {

    private static final double SAFETY_MARGIN = 0.85;

    @Override
    public long selectBitrate(List<Long> availableBitrates,
                              long throughputBps,
                              double bufferLevelSec) {
        // Pick highest bitrate that fits within 85% of measured throughput
        long safeThroughput = (long) (throughputBps * SAFETY_MARGIN);
        long selected = availableBitrates.get(0); // lowest as fallback

        for (long bitrate : availableBitrates) {
            if (bitrate <= safeThroughput) {
                selected = bitrate;
            } else {
                break; // list is sorted ascending
            }
        }
        return selected;
    }
}
```

### Concrete Strategy: BufferBasedABR

```java
public class BufferBasedABR implements ABRStrategy {

    private static final double LOW_BUFFER_THRESHOLD  = 5.0;  // seconds
    private static final double HIGH_BUFFER_THRESHOLD = 30.0; // seconds

    @Override
    public long selectBitrate(List<Long> availableBitrates,
                              long throughputBps,
                              double bufferLevelSec) {
        int maxIndex = availableBitrates.size() - 1;

        if (bufferLevelSec < LOW_BUFFER_THRESHOLD) {
            // Buffer critically low -- drop to lowest quality
            return availableBitrates.get(0);
        }
        if (bufferLevelSec > HIGH_BUFFER_THRESHOLD) {
            // Buffer healthy -- go to highest quality
            return availableBitrates.get(maxIndex);
        }

        // Linear interpolation between low and high thresholds
        double ratio = (bufferLevelSec - LOW_BUFFER_THRESHOLD)
                     / (HIGH_BUFFER_THRESHOLD - LOW_BUFFER_THRESHOLD);
        int index = (int) (ratio * maxIndex);
        return availableBitrates.get(Math.min(index, maxIndex));
    }
}
```

### Interview One-Liner

> "We use **three Strategy interfaces** -- TranscodingStrategy picks parallel vs.
> sequential encoding, ABRStrategy picks throughput-based vs. buffer-based quality
> selection, and RecommendationStrategy picks trending vs. personalized. All three
> are injected, swappable, and A/B testable per user cohort."

### Why This Matters in Interviews

```
Interviewer: "How does adaptive bitrate work?"
You:         "ABRStrategy.selectBitrate() takes available bitrates, measured
              throughput, and buffer level. ThroughputBasedABR picks the highest
              bitrate under 85% of throughput. BufferBasedABR maps buffer level
              to quality -- low buffer means low quality to avoid rebuffering.
              The player calls this before every segment request."

Interviewer: "How do you handle transcoding at scale?"
You:         "ParallelTranscodingStrategy submits each resolution/codec pair
              to a thread pool. In production, each job is a Kubernetes pod
              running FFmpeg. The strategy is swappable -- SequentialStrategy
              for budget tier, ParallelStrategy for premium uploads."
```

### Tradeoffs

| Pro | Con |
|-----|-----|
| Three orthogonal strategy axes -- each independently swappable | Three abstractions to maintain |
| Parallel transcoding reduces latency from O(N) to O(1) wall-clock | Parallel requires more compute resources at peak |
| ABR strategy swap lets us experiment without changing player | Client-side ABR depends on accurate throughput estimation |
| Recommendation strategy swap handles cold-start elegantly | Personalized strategy needs ML model serving infra |

### Cross-Reference

- **Facade (Section 5):** VideoService uses all three strategies behind a single API
- **Pipeline (Section 8):** ParallelTranscodingStrategy is one node in the transcoding DAG
- **Template Method (Section 9):** TranscodingStrategy defines the hook; subclasses implement
- **Technologies:** See TECHNOLOGIES.md for FFmpeg commands, HLS/DASH formats

---

## 2. Builder Pattern (Creational)

### Why

A Video has required fields (videoId, uploaderId, title), optional fields
(description, tags, thumbnailUrl), derived fields (duration extracted after
transcoding), and a lifecycle status. The Builder enforces validation at
construction time and produces an immutable Video.

### Ugly Anti-Pattern -- Telescoping Constructors

```java
// UGLY: Multiple constructors, no validation, mutable state.
// Easy to swap arguments (videoId and uploaderId are both strings).
// No way to set optional fields without passing nulls.

public class UglyVideo {
    public String videoId;
    public String uploaderId;
    public String title;
    public String description;
    public List<String> tags;
    public String thumbnailUrl;
    public String rawFilePath;
    public long fileSizeBytes;
    public int durationSeconds;
    public String status;      // String, not enum -- typo-prone

    public UglyVideo(String videoId, String uploaderId, String title) {
        this.videoId = videoId;
        this.uploaderId = uploaderId;
        this.title = title;
        this.status = "uploading";  // String literal -- can misspell
    }

    public UglyVideo(String videoId, String uploaderId, String title,
                     String description, List<String> tags) {
        this(videoId, uploaderId, title);
        this.description = description;
        this.tags = tags;  // Mutable reference leak!
    }
}

// Caller: which String is which? Easy to swap.
UglyVideo v = new UglyVideo("user-42", "vid-1", "My Video"); // SWAPPED!
```

**Problems:**
1. `videoId` and `uploaderId` are both Strings -- easy to swap arguments
2. `status` is a String -- can misspell "uploading" as "uploding"
3. `tags` list is mutable -- caller can modify after construction
4. No validation -- blank title, negative file size all accepted
5. Telescoping constructors grow unmanageable with more fields

### Clean Solution -- Video.Builder

```java
public class Video {

    private final String videoId;
    private final String uploaderId;
    private final String title;
    private final String description;
    private final List<String> tags;
    private final String thumbnailUrl;
    private final String rawFilePath;
    private final long fileSizeBytes;
    private final LocalDateTime uploadedAt;

    private volatile VideoStatus status;      // Enum, not String
    private volatile int durationSeconds;     // Set after transcoding
    private volatile long viewCount;
    private volatile long likeCount;

    private Video(Builder builder) {
        this.videoId       = builder.videoId;
        this.uploaderId    = builder.uploaderId;
        this.title         = builder.title;
        this.description   = builder.description != null ? builder.description : "";
        this.tags          = Collections.unmodifiableList(
                                 new ArrayList<>(builder.tags));
        this.thumbnailUrl  = builder.thumbnailUrl;
        this.rawFilePath   = builder.rawFilePath;
        this.fileSizeBytes = builder.fileSizeBytes;
        this.uploadedAt    = builder.uploadedAt != null
                           ? builder.uploadedAt : LocalDateTime.now();
        this.status        = VideoStatus.UPLOADING;
        this.durationSeconds = 0;
        this.viewCount     = 0;
        this.likeCount     = 0;
    }

    // --- State Machine Transitions (see Section 7) ---
    public void transitionTo(VideoStatus newStatus) {
        // Guarded transition -- see State Pattern section
        if (!isValidTransition(this.status, newStatus)) {
            throw new IllegalStateException(
                "Cannot transition from " + this.status + " to " + newStatus);
        }
        this.status = newStatus;
    }

    // --- Builder ---
    public static class Builder {
        private String videoId;
        private String uploaderId;
        private String title;
        private String description;
        private List<String> tags = new ArrayList<>();
        private String thumbnailUrl;
        private String rawFilePath;
        private long fileSizeBytes;
        private LocalDateTime uploadedAt;

        public Builder videoId(String id)          { this.videoId = id; return this; }
        public Builder uploaderId(String id)       { this.uploaderId = id; return this; }
        public Builder title(String title)         { this.title = title; return this; }
        public Builder description(String desc)    { this.description = desc; return this; }
        public Builder tags(List<String> tags)     { this.tags = tags; return this; }
        public Builder thumbnailUrl(String url)    { this.thumbnailUrl = url; return this; }
        public Builder rawFilePath(String path)    { this.rawFilePath = path; return this; }
        public Builder fileSizeBytes(long size)    { this.fileSizeBytes = size; return this; }
        public Builder uploadedAt(LocalDateTime dt){ this.uploadedAt = dt; return this; }

        public Video build() {
            if (videoId == null || videoId.isBlank())
                throw new IllegalArgumentException("videoId is required");
            if (uploaderId == null || uploaderId.isBlank())
                throw new IllegalArgumentException("uploaderId is required");
            if (title == null || title.isBlank())
                throw new IllegalArgumentException("title is required");
            if (title.length() > 200)
                throw new IllegalArgumentException("title exceeds 200 chars");
            if (fileSizeBytes < 0)
                throw new IllegalArgumentException("fileSizeBytes cannot be negative");
            return new Video(this);
        }
    }
}
```

### ASCII Diagram

```
  Client Code
       |
       v
  Video.Builder
  +---------------------+
  | - videoId           |  required
  | - uploaderId        |  required
  | - title             |  required (max 200 chars)
  | - description       |  optional (default "")
  | - tags              |  optional (default empty)
  | - thumbnailUrl      |  optional
  | - rawFilePath       |  optional
  | - fileSizeBytes     |  optional (>= 0)
  | - uploadedAt        |  optional (defaults to now)
  +---------------------+
  | + videoId(id)       |  returns this
  | + uploaderId(uid)   |  returns this
  | + title(text)       |  returns this
  | + build()           | ---> validates, wraps tags as unmodifiable,
  +---------------------+      sets status = UPLOADING,
                               returns immutable Video
                                  |
                                  v
                           Video (immutable core)
                           - videoId, uploaderId, title
                           - tags (unmodifiable list)
                           - status: VideoStatus enum (volatile)
                           - viewCount, likeCount (volatile)
                           - uploadedAt
```

### Numbered Call Chain

```
1. Client calls new Video.Builder()
2. Fluent setters: .videoId("v-1").uploaderId("u-42").title("Cat Video")
3. Optional: .tags(List.of("cats", "funny")).fileSizeBytes(50_000_000)
4. .build() validates required fields (videoId, uploaderId, title)
5. build() checks title length <= 200 and fileSizeBytes >= 0
6. Video constructor wraps tags in unmodifiable list
7. Video constructor sets status = VideoStatus.UPLOADING
8. Video constructor sets uploadedAt to now() if null
9. Returns Video with immutable identity fields, volatile mutable counters
```

### Interview One-Liner

> "Video uses Builder because it has required fields with validation (videoId,
> uploaderId, title), optional fields with defaults (description, tags), and
> a lifecycle status enum. The resulting Video has immutable identity fields
> and volatile counters for thread-safe view/like increments."

### Cross-Reference

- **State (Section 7):** Video.transitionTo() enforces valid state transitions
- **Repository (Section 4):** VideoRepository.save() accepts the built Video
- **Observer (Section 6):** View/like count changes trigger AnalyticsService notifications

---

## 3. Factory Pattern (Creational)

### Why

AppConfig wires together all three strategies, three repositories, and the
services. It acts as a Simple Factory / Composition Root, keeping construction
logic in one place. In production this would be Spring Boot `@Configuration` + `@Bean`.

### Ugly Anti-Pattern -- Scattered Construction

```java
// UGLY: Every main() or test creates its own wiring.
// If TranscodingStrategy constructor changes, every call site breaks.
// No single place to see the full dependency graph.

public class UglyMain {

    public static void main(String[] args) {
        // Scattered construction -- repeated in every test, every main
        VideoRepository videoRepo = new InMemoryVideoRepository();
        UserRepository userRepo = new InMemoryUserRepository();
        WatchHistoryRepository watchRepo = new InMemoryWatchHistoryRepository();

        // Hardcoded strategy choices -- cannot swap without code change
        ParallelTranscodingStrategy transcoder =
                new ParallelTranscodingStrategy(4);
        ThroughputBasedABR abr = new ThroughputBasedABR();
        TrendingRecommendationStrategy recommender =
                new TrendingRecommendationStrategy(videoRepo);

        // Constructor call duplicated in every entry point
        VideoService videoService = new VideoService(
                transcoder, abr, recommender, videoRepo, userRepo, watchRepo);
        AnalyticsService analytics = new AnalyticsService(videoRepo);

        // If we add a new dependency to VideoService, fix ALL call sites
        videoService.upload(...);
    }
}
```

### Clean Solution -- AppConfig Factory

```java
public class AppConfig {

    // --- Strategies ---
    private final TranscodingStrategy    transcodingStrategy;
    private final ABRStrategy            abrStrategy;
    private final RecommendationStrategy recommendationStrategy;

    // --- Repositories ---
    private final VideoRepository        videoRepo;
    private final UserRepository         userRepo;
    private final WatchHistoryRepository watchHistoryRepo;

    // --- Services ---
    private final VideoService           videoService;
    private final AnalyticsService       analyticsService;
    private final StreamingService       streamingService;

    public AppConfig() {
        // 1. Repositories
        this.videoRepo        = new InMemoryVideoRepository();
        this.userRepo         = new InMemoryUserRepository();
        this.watchHistoryRepo = new InMemoryWatchHistoryRepository();

        // 2. Strategies (swappable here -- one line change)
        this.transcodingStrategy    = new ParallelTranscodingStrategy(
                Runtime.getRuntime().availableProcessors());
        this.abrStrategy            = new ThroughputBasedABR();
        this.recommendationStrategy = new PersonalizedRecommendationStrategy(
                watchHistoryRepo, videoRepo);

        // 3. Services (wired with strategies + repos)
        this.analyticsService = new AnalyticsService(videoRepo);
        this.videoService     = new VideoService(
                transcodingStrategy, abrStrategy, recommendationStrategy,
                videoRepo, userRepo, watchHistoryRepo, analyticsService);
        this.streamingService = new StreamingService(
                videoRepo, abrStrategy);
    }

    public VideoService     videoService()     { return videoService; }
    public AnalyticsService analyticsService() { return analyticsService; }
    public StreamingService streamingService() { return streamingService; }
}
```

### ASCII Diagram

```
  AppConfig (Composition Root)
  +---------------------------------------------------------------+
  |                                                               |
  |  Repositories        Strategies           Services            |
  |  +-----------+       +-----------+        +----------------+  |
  |  | VideoRepo |       | Parallel  |------->| VideoService   |  |
  |  | UserRepo  |------>| Transcode |        | (Facade)       |  |
  |  | WatchHist |       +-----------+        +----------------+  |
  |  +-----------+       | Throughput|------->| StreamingService|  |
  |       |              | ABR       |        +----------------+  |
  |       |              +-----------+        | AnalyticsService|  |
  |       +------------->| Personal  |------->| (Observer)      |  |
  |                      | Recommend |        +----------------+  |
  |                      +-----------+                            |
  +---------------------------------------------------------------+
```

### Numbered Call Chain

```
1. Main calls new AppConfig()
2. AppConfig creates InMemoryVideoRepository, InMemoryUserRepository,
   InMemoryWatchHistoryRepository
3. AppConfig creates ParallelTranscodingStrategy with CPU core count
4. AppConfig creates ThroughputBasedABR
5. AppConfig creates PersonalizedRecommendationStrategy with repos
6. AppConfig creates AnalyticsService with videoRepo
7. AppConfig creates VideoService with all strategies + repos + analytics
8. AppConfig creates StreamingService with videoRepo + abrStrategy
9. Main calls appConfig.videoService() to get the fully wired service
```

### Interview One-Liner

> "AppConfig is our composition root -- one place to see the full dependency
> graph. Swap ParallelTranscodingStrategy for SequentialTranscodingStrategy
> in one line. In production, this is Spring's `@Configuration` with `@Bean` methods."

### Cross-Reference

- **Strategy (Section 1):** AppConfig selects which concrete strategy to inject
- **Facade (Section 5):** VideoService assembled here with all dependencies
- **Observer (Section 6):** AnalyticsService registered as observer here

---

## 4. Repository Pattern (Structural -- Enterprise)

### Why

Three repositories abstract data access. Our Java implementation uses in-memory
`ConcurrentHashMap`; production uses PostgreSQL/Cassandra/S3. The service layer
never knows which storage backend is in use.

### Three Repositories

| Repository | Entity | Key | Storage (Production) |
|-----------|--------|-----|---------------------|
| VideoRepository | Video | videoId | PostgreSQL (metadata) + S3 (segments) |
| UserRepository | User | userId | PostgreSQL |
| WatchHistoryRepository | WatchHistoryEntry | userId + videoId | Cassandra (time-series) |

### Ugly Anti-Pattern -- SQL in Service Layer

```java
// UGLY: SQL queries scattered in the service layer.
// Changing from PostgreSQL to Cassandra requires editing every service method.
// Cannot unit test without a database.

public class UglyVideoService {

    private final Connection dbConnection;

    public Video getVideo(String videoId) {
        // SQL directly in service layer -- tight coupling to schema
        PreparedStatement ps = dbConnection.prepareStatement(
            "SELECT video_id, uploader_id, title, status, view_count "
            + "FROM videos WHERE video_id = ?");
        ps.setString(1, videoId);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            Video v = new Video();
            v.videoId = rs.getString("video_id");
            v.title = rs.getString("title");
            v.status = rs.getString("status"); // String, not enum
            return v;
        }
        return null; // Returns null instead of Optional
    }

    public void incrementViewCount(String videoId) {
        // Another raw SQL query -- duplicated column names
        dbConnection.prepareStatement(
            "UPDATE videos SET view_count = view_count + 1 "
            + "WHERE video_id = ?")
            .setString(1, videoId);
        // Forgot to execute! Silent bug.
    }
}
```

### Clean Solution -- Repository Interface + In-Memory Implementation

```java
public interface VideoRepository {
    void save(Video video);
    Optional<Video> findById(String videoId);
    List<Video> findByUploaderId(String uploaderId);
    List<Video> findByStatus(VideoStatus status);
    void incrementViewCount(String videoId);
    void incrementLikeCount(String videoId);
    List<Video> findTopByViewCount(int limit);
}

public class InMemoryVideoRepository implements VideoRepository {

    private final ConcurrentHashMap<String, Video> store = new ConcurrentHashMap<>();

    @Override
    public void save(Video video) {
        store.put(video.getVideoId(), video);
    }

    @Override
    public Optional<Video> findById(String videoId) {
        return Optional.ofNullable(store.get(videoId));
    }

    @Override
    public List<Video> findByUploaderId(String uploaderId) {
        return store.values().stream()
                .filter(v -> v.getUploaderId().equals(uploaderId))
                .collect(Collectors.toList());
    }

    @Override
    public List<Video> findByStatus(VideoStatus status) {
        return store.values().stream()
                .filter(v -> v.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public void incrementViewCount(String videoId) {
        findById(videoId).ifPresent(Video::incrementViewCount);
    }

    @Override
    public List<Video> findTopByViewCount(int limit) {
        return store.values().stream()
                .sorted(Comparator.comparingLong(Video::getViewCount).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
}
```

```java
public interface WatchHistoryRepository {
    void record(String userId, String videoId, int watchedSeconds,
                int totalSeconds);
    List<WatchHistoryEntry> getHistory(String userId, int limit);
    Optional<WatchHistoryEntry> findByUserAndVideo(String userId,
                                                    String videoId);
}
```

### ASCII Diagram

```
  VideoService / StreamingService / AnalyticsService
       |              |                  |
       v              v                  v
  +--------------------------------------------------+
  |              Repository Interfaces                |
  |  VideoRepository  UserRepository  WatchHistoryRepo|
  +--------------------------------------------------+
       |              |                  |
       v              v                  v
  +--------------------------------------------------+
  |          In-Memory Implementations                |
  |  ConcurrentHashMap  ConcurrentHashMap  Concurrent |
  +--------------------------------------------------+
       |              |                  |
       v              v                  v
  +--------------------------------------------------+
  |          Production Implementations               |
  |  PostgreSQL + S3    PostgreSQL    Cassandra        |
  +--------------------------------------------------+
```

### Numbered Call Chain -- Save + Retrieve

```
1. VideoService builds Video via Video.Builder
2. VideoService calls videoRepo.save(video)
3. InMemoryVideoRepository.save() puts video in ConcurrentHashMap
4. Client requests GET /videos/{videoId}
5. VideoService calls videoRepo.findById(videoId)
6. InMemoryVideoRepository.findById() returns Optional<Video>
7. VideoService unwraps Optional, returns Video or throws NotFoundException
```

### Interview One-Liner

> "Three repositories abstract storage: VideoRepository (metadata + segments),
> UserRepository (profiles), and WatchHistoryRepository (time-series watch data).
> Our in-memory ConcurrentHashMap swaps to PostgreSQL + S3 + Cassandra in production."

### Cross-Reference

- **Factory (Section 3):** AppConfig creates and injects all three repositories
- **Facade (Section 5):** VideoService depends on all three via constructor injection
- **Technologies:** See TECHNOLOGIES.md for PostgreSQL schema, S3 layout, Cassandra model

---

## 5. Facade Pattern (Structural)

### Why

VideoService orchestrates the entire video lifecycle behind a single API:
upload, transcode, store, stream, recommend. Callers never interact with
repositories, strategies, or analytics directly.

### Ugly Anti-Pattern -- Client Orchestrates Everything

```java
// UGLY: The controller orchestrates upload, transcoding, storage,
// and analytics directly. Every endpoint duplicates orchestration.
// Adding a step (e.g., thumbnail generation) requires editing every caller.

public class UglyVideoController {

    private final VideoRepository videoRepo;
    private final S3Client s3;
    private final TranscodingService transcoder;
    private final AnalyticsService analytics;
    private final CDNService cdn;

    public void handleUpload(String videoId, byte[] rawFile) {
        // Step 1: Store raw file
        s3.putObject("raw-videos", videoId, rawFile);

        // Step 2: Create metadata
        Video video = new Video(videoId, "user-42", "title");
        video.status = "uploaded";
        videoRepo.save(video);

        // Step 3: Transcode (caller knows about every profile)
        for (String res : List.of("1080p", "720p", "480p")) {
            transcoder.transcode(videoId, res);
        }
        video.status = "ready";

        // Step 4: Invalidate CDN
        cdn.invalidate("/videos/" + videoId + "/*");

        // Step 5: Track analytics
        analytics.trackUpload(videoId);

        // Every new step must be added to every controller endpoint
    }
}
```

### Clean Solution -- VideoService Facade

```java
public class VideoService {

    private final TranscodingStrategy      transcodingStrategy;
    private final ABRStrategy              abrStrategy;
    private final RecommendationStrategy   recommendationStrategy;
    private final VideoRepository          videoRepo;
    private final UserRepository           userRepo;
    private final WatchHistoryRepository   watchHistoryRepo;
    private final AnalyticsService         analyticsService;

    // Constructor injection -- all dependencies from AppConfig

    /** Upload: validate -> store raw -> build Video -> save metadata. */
    public Video upload(String uploaderId, String title,
                        String rawFilePath, long fileSizeBytes) {
        Video video = new Video.Builder()
                .videoId(generateId())
                .uploaderId(uploaderId)
                .title(title)
                .rawFilePath(rawFilePath)
                .fileSizeBytes(fileSizeBytes)
                .build();
        videoRepo.save(video);
        analyticsService.onEvent(new VideoEvent(
                video.getVideoId(), VideoEventType.UPLOADED));
        return video;
    }

    /** Transcode: transition state -> delegate to strategy -> update state. */
    public void transcode(String videoId, List<TranscodeProfile> profiles) {
        Video video = videoRepo.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
        video.transitionTo(VideoStatus.TRANSCODING);
        List<String> segments = transcodingStrategy.transcode(
                videoId, video.getRawFilePath(), profiles);
        video.transitionTo(VideoStatus.READY);
        videoRepo.save(video);
        analyticsService.onEvent(new VideoEvent(
                videoId, VideoEventType.TRANSCODED));
    }

    /** Stream: record view -> select ABR quality -> return manifest. */
    public StreamingSession startStream(String userId, String videoId) {
        Video video = videoRepo.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
        videoRepo.incrementViewCount(videoId);
        analyticsService.onEvent(new VideoEvent(
                videoId, VideoEventType.VIEW));
        return new StreamingSession(video, abrStrategy);
    }

    /** Recommend: delegate to strategy with user's watch history. */
    public List<Video> recommend(String userId, int limit) {
        List<WatchHistoryEntry> history =
                watchHistoryRepo.getHistory(userId, 100);
        List<String> videoIds =
                recommendationStrategy.recommend(userId, history, limit);
        return videoIds.stream()
                .map(videoRepo::findById)
                .flatMap(Optional::stream)
                .collect(Collectors.toList());
    }
}
```

### ASCII Diagram -- Facade Hides Complexity

```
  +-------------------+     +-------------------+     +-------------------+
  | VideoController   |     | VideoController   |     | VideoController   |
  | POST /upload      |     | GET /stream/{id}  |     | GET /recommend    |
  +-------------------+     +-------------------+     +-------------------+
           |                         |                         |
           +-------------------------+-------------------------+
                                     |
                                     v
                          +---------------------+
                          |   VideoService      |  <-- THE FACADE
                          |   (single entry     |
                          |    point for all     |
                          |    video operations) |
                          +---------------------+
                           /    |    |     |    \
                          v     v    v     v     v
                     Transcode ABR  Rec  Video  Analytics
                     Strategy  Str  Str  Repo   Service
                                         User   (Observer)
                                         Repo
                                         Watch
                                         Repo
```

### Numbered Call Chain -- Full Upload-to-Stream Flow

```
1.  Client calls POST /videos/upload with file + metadata
2.  VideoController delegates to VideoService.upload()
3.  VideoService builds Video via Video.Builder (status = UPLOADING)
4.  VideoService stores raw file path, saves Video to videoRepo
5.  VideoService fires UPLOADED event to AnalyticsService (Observer)
6.  VideoService calls transcode(videoId, profiles)
7.  Video.transitionTo(TRANSCODING) -- state machine validates transition
8.  TranscodingStrategy.transcode() produces segments for each resolution
9.  Video.transitionTo(READY)
10. VideoService saves updated Video to videoRepo
11. Client calls GET /videos/{videoId}/stream
12. VideoService.startStream() increments view count
13. VideoService fires VIEW event to AnalyticsService
14. VideoService creates StreamingSession with ABRStrategy
15. Player calls ABRStrategy.selectBitrate() before each segment request
```

### Interview One-Liner

> "VideoService is a Facade that orchestrates the entire video lifecycle:
> upload -> transcode -> store -> stream -> recommend. The controller
> has single-line delegates; all orchestration logic lives behind the facade."

### Cross-Reference

- **Strategy (Section 1):** VideoService holds all three strategy references
- **Observer (Section 6):** VideoService fires events that AnalyticsService consumes
- **State (Section 7):** VideoService calls Video.transitionTo() at each lifecycle step
- **Factory (Section 3):** AppConfig wires all dependencies into VideoService

---

## 6. Observer Pattern (Behavioral)

### Why

When a video is viewed, liked, uploaded, or transcoded, multiple subsystems
need to react: analytics tracking, view count aggregation, recommendation
model updates, CDN cache warming. The Observer pattern decouples the event
source (VideoService) from the event consumers.

### Ugly Anti-Pattern -- Direct Calls to Every Subsystem

```java
// UGLY: VideoService directly calls every subsystem on every event.
// Adding a new consumer (e.g., abuse detection) requires editing VideoService.
// If analytics is slow, it blocks the video view response.

public class UglyVideoService {

    private final AnalyticsTracker analytics;
    private final RecommendationEngine recommender;
    private final CDNWarmer cdnWarmer;
    private final AbuseDetector abuseDetector;  // Added later -- requires change

    public void recordView(String userId, String videoId) {
        incrementViewCount(videoId);

        // Direct calls -- VideoService knows about every consumer
        analytics.trackView(userId, videoId);         // 50ms
        recommender.updateModel(userId, videoId);     // 200ms
        cdnWarmer.checkPopularity(videoId);           // 100ms
        abuseDetector.checkForBotViews(userId, videoId); // Added later

        // Total latency: 350ms+ just for side effects!
        // Adding a new consumer requires modifying this method.
    }
}
```

### Clean Solution -- Event Bus with Observer

```java
public enum VideoEventType {
    UPLOADED, TRANSCODED, VIEW, LIKE, COMMENT, SHARE
}

public class VideoEvent {
    private final String videoId;
    private final VideoEventType type;
    private final String userId;    // null for system events
    private final LocalDateTime timestamp;

    public VideoEvent(String videoId, VideoEventType type) {
        this(videoId, type, null);
    }

    public VideoEvent(String videoId, VideoEventType type, String userId) {
        this.videoId   = videoId;
        this.type      = type;
        this.userId    = userId;
        this.timestamp = LocalDateTime.now();
    }
    // getters omitted
}

public interface VideoEventObserver {
    void onEvent(VideoEvent event);
}

public class AnalyticsService implements VideoEventObserver {

    private final VideoRepository videoRepo;
    private final Map<String, AtomicLong> viewCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> likeCounts = new ConcurrentHashMap<>();

    @Override
    public void onEvent(VideoEvent event) {
        switch (event.getType()) {
            case VIEW:
                viewCounts.computeIfAbsent(event.getVideoId(),
                        k -> new AtomicLong()).incrementAndGet();
                break;
            case LIKE:
                likeCounts.computeIfAbsent(event.getVideoId(),
                        k -> new AtomicLong()).incrementAndGet();
                break;
            case TRANSCODED:
                System.out.printf("[Analytics] Video %s transcoded at %s%n",
                        event.getVideoId(), event.getTimestamp());
                break;
            default:
                // Log other events
                break;
        }
    }

    public long getViewCount(String videoId) {
        return viewCounts.getOrDefault(videoId, new AtomicLong()).get();
    }
}

// VideoService fires events -- does not know who listens
public class VideoService {

    private final List<VideoEventObserver> observers = new ArrayList<>();

    public void addObserver(VideoEventObserver observer) {
        observers.add(observer);
    }

    private void fireEvent(VideoEvent event) {
        for (VideoEventObserver observer : observers) {
            observer.onEvent(event);  // In production: async via Kafka
        }
    }

    public void recordView(String userId, String videoId) {
        videoRepo.incrementViewCount(videoId);
        fireEvent(new VideoEvent(videoId, VideoEventType.VIEW, userId));
        // One line -- does not know about analytics, recommender, CDN, etc.
    }
}
```

### ASCII Diagram

```
  VideoService (Subject)
       |
       | fireEvent(VideoEvent)
       |
       +----> VideoEventObserver interface
              |
       +------+------+------+------+
       |      |      |      |      |
       v      v      v      v      v
  Analytics  Rec    CDN   Abuse   Future
  Service    Model  Warm  Detect  Observer
  (counts)  (retrain)(push)(scan) (no code
                                   change)
```

### Numbered Call Chain -- View Event

```
1. Player requests segment -> VideoService.recordView(userId, videoId)
2. VideoService increments view count in VideoRepository
3. VideoService calls fireEvent(new VideoEvent(videoId, VIEW, userId))
4. fireEvent iterates over registered observers
5. AnalyticsService.onEvent() increments in-memory AtomicLong counter
6. RecommendationUpdater.onEvent() adds to user's watch history
7. CDNWarmer.onEvent() checks if view velocity crossed threshold
8. VideoService returns immediately -- observers fire asynchronously in production
```

### Interview One-Liner

> "VideoService fires VideoEvents (VIEW, LIKE, TRANSCODED) to registered
> observers. AnalyticsService, recommendation updater, and CDN warmer all
> implement VideoEventObserver. Adding a new consumer is zero changes to
> VideoService -- just register a new observer."

### Cross-Reference

- **Facade (Section 5):** VideoService fires events at each lifecycle step
- **State (Section 7):** State transitions (TRANSCODING -> READY) fire TRANSCODED events
- **Caching:** See CACHING_STRATEGY.md for how CDN warmer uses view velocity events

---

## 7. State Pattern (Behavioral)

### Why

A Video has a strict lifecycle: UPLOADING -> UPLOADED -> TRANSCODING -> READY.
Invalid transitions (e.g., UPLOADING -> READY, skipping transcoding) must be
rejected. The State pattern encodes valid transitions as a state machine.

### Video State Machine

```
                    +----------+
                    | UPLOADING |
                    +----+-----+
                         |
                    upload complete
                         |
                         v
                    +----------+
                    | UPLOADED  |
                    +----+-----+
                         |
                    start transcode
                         |
                         v
                    +------------+
                    | TRANSCODING|
                    +----+--+----+
                         |  |
                   success  failure
                     |        |
                     v        v
                 +-------+ +--------+
                 | READY | | FAILED |
                 +-------+ +---+----+
                               |
                          retry (back to TRANSCODING)
                               |
                               v
                         +------------+
                         | TRANSCODING|  (retry)
                         +------------+

  Any state -----> DELETED (soft-delete)
```

### Ugly Anti-Pattern -- String-Based Status with No Validation

```java
// UGLY: Status is a String. No validation on transitions.
// Can set status to anything, including typos.
// Business logic scattered across methods with no state machine.

public class UglyVideo {
    public String status = "uploading";

    public void markReady() {
        // No validation -- can go from "uploading" directly to "ready"
        // skipping "uploaded" and "transcoding" entirely!
        this.status = "ready";
    }

    public void markTranscoding() {
        // What if status is "ready"? Can we go backwards?
        // No one knows -- there is no state machine.
        this.status = "trancoding";  // TYPO -- silent bug!
    }

    public void delete() {
        this.status = "deleted";
        // But what if we are mid-transcoding? Orphaned jobs!
    }
}
```

### Clean Solution -- Enum State Machine with Transition Map

```java
public enum VideoStatus {
    UPLOADING,
    UPLOADED,
    TRANSCODING,
    READY,
    FAILED,
    DELETED;

    // Valid transitions encoded as a map
    private static final Map<VideoStatus, Set<VideoStatus>> VALID_TRANSITIONS =
            Map.of(
                UPLOADING,   Set.of(UPLOADED, FAILED, DELETED),
                UPLOADED,    Set.of(TRANSCODING, DELETED),
                TRANSCODING, Set.of(READY, FAILED, DELETED),
                READY,       Set.of(DELETED),
                FAILED,      Set.of(TRANSCODING, DELETED),  // retry allowed
                DELETED,     Set.of()                        // terminal state
            );

    public boolean canTransitionTo(VideoStatus target) {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of())
                                .contains(target);
    }
}

// In Video.java:
public void transitionTo(VideoStatus newStatus) {
    if (!this.status.canTransitionTo(newStatus)) {
        throw new IllegalStateException(String.format(
            "Invalid transition: %s -> %s for video %s",
            this.status, newStatus, this.videoId));
    }
    VideoStatus oldStatus = this.status;
    this.status = newStatus;
    System.out.printf("[State] Video %s: %s -> %s%n",
            videoId, oldStatus, newStatus);
}
```

### ASCII Diagram -- Transition Map

```
  From \ To     UPLOADING  UPLOADED  TRANSCODING  READY  FAILED  DELETED
  -----------------------------------------------------------------------
  UPLOADING        -          Y          -          -       Y       Y
  UPLOADED         -          -          Y          -       -       Y
  TRANSCODING      -          -          -          Y       Y       Y
  READY            -          -          -          -       -       Y
  FAILED           -          -          Y          -       -       Y
  DELETED          -          -          -          -       -       -
```

### Numbered Call Chain -- Upload to Ready

```
1. Video created via Builder -> status = UPLOADING
2. Upload completes -> video.transitionTo(UPLOADED)
3. UPLOADING.canTransitionTo(UPLOADED) -> true, transition allowed
4. Transcoding starts -> video.transitionTo(TRANSCODING)
5. UPLOADED.canTransitionTo(TRANSCODING) -> true, transition allowed
6. Transcoding completes -> video.transitionTo(READY)
7. TRANSCODING.canTransitionTo(READY) -> true, transition allowed
8. Video is now playable
```

### Numbered Call Chain -- Failed + Retry

```
1. Video is in TRANSCODING state
2. FFmpeg fails -> video.transitionTo(FAILED)
3. TRANSCODING.canTransitionTo(FAILED) -> true
4. Retry logic kicks in -> video.transitionTo(TRANSCODING)
5. FAILED.canTransitionTo(TRANSCODING) -> true (retry allowed)
6. Transcoding succeeds -> video.transitionTo(READY)
7. TRANSCODING.canTransitionTo(READY) -> true
```

### Numbered Call Chain -- Invalid Transition Rejected

```
1. Video is in UPLOADING state
2. Bug tries video.transitionTo(READY) -- skipping transcode!
3. UPLOADING.canTransitionTo(READY) -> false
4. IllegalStateException thrown: "Invalid transition: UPLOADING -> READY"
5. Bug caught at development time, not in production
```

### Interview One-Liner

> "VideoStatus is an enum state machine with a transition map. UPLOADING can
> go to UPLOADED or FAILED, never directly to READY. FAILED can retry back to
> TRANSCODING. DELETED is terminal. transitionTo() validates before mutating."

### Cross-Reference

- **Builder (Section 2):** Video.Builder sets initial status = UPLOADING
- **Facade (Section 5):** VideoService calls transitionTo() at each lifecycle step
- **Observer (Section 6):** State transitions fire events (TRANSCODED when -> READY)
- **Pipeline (Section 8):** Transcoding pipeline moves video through TRANSCODING -> READY

---

## 8. Pipeline Pattern (Enterprise)

### Why

Transcoding a video is a multi-stage DAG (Directed Acyclic Graph), not a single
operation. The raw file must be split into chunks, each chunk transcoded at
multiple resolutions in parallel, results stitched together, manifests generated,
and segments stored. The Pipeline pattern models this as a chain of stages.

### Transcoding DAG

```
  Raw Video Upload
       |
       v
  +------------------+
  | 1. VALIDATE      |  Check format, duration, file size limits
  |    (single)      |
  +--------+---------+
           |
           v
  +------------------+
  | 2. SPLIT         |  Split into 4-second chunks (GOP-aligned)
  |    (single)      |
  +--------+---------+
           |
           v
  +------------------+
  | 3. TRANSCODE     |  Each chunk x each profile = parallel jobs
  |    (parallel)    |
  |                  |
  |  chunk-1 x 1080p |  chunk-1 x 720p  |  chunk-1 x 480p  |
  |  chunk-2 x 1080p |  chunk-2 x 720p  |  chunk-2 x 480p  |
  |  chunk-3 x 1080p |  chunk-3 x 720p  |  chunk-3 x 480p  |
  |  ...             |  ...             |  ...              |
  +--------+---------+
           |
           v
  +------------------+
  | 4. STITCH        |  Concatenate chunks per resolution
  |    (per-profile) |
  +--------+---------+
           |
           v
  +------------------+
  | 5. MANIFEST      |  Generate HLS/DASH manifest files
  |    (single)      |
  +--------+---------+
           |
           v
  +------------------+
  | 6. STORE         |  Upload segments + manifests to S3/CDN origin
  |    (parallel)    |
  +--------+---------+
           |
           v
  +------------------+
  | 7. NOTIFY        |  Fire TRANSCODED event, update status -> READY
  |    (single)      |
  +------------------+
```

### Ugly Anti-Pattern -- Monolithic Transcode Method

```java
// UGLY: Every stage of the pipeline is in one method.
// Cannot retry individual stages. Cannot parallelize chunk transcoding.
// Cannot add a new stage (e.g., watermark) without editing the monolith.

public class UglyTranscoder {

    public void transcode(String videoId, String rawPath) {
        // Validate
        if (!isValidFormat(rawPath)) throw new RuntimeException("bad format");

        // Split -- tightly coupled to transcode
        List<String> chunks = splitIntoChunks(rawPath, 4);

        // Transcode -- sequential, one profile at a time
        for (String chunk : chunks) {
            for (String res : List.of("1080p", "720p", "480p")) {
                ffmpeg(chunk, res);  // Sequential! Slow!
            }
        }

        // Stitch -- coupled to the above loop's output format
        for (String res : List.of("1080p", "720p", "480p")) {
            stitchChunks(videoId, res);
        }

        // Manifest -- hardcoded format
        generateManifest(videoId);

        // Store -- no error handling per stage
        uploadToS3(videoId);

        // If stage 4 fails, we redo stages 1-3 too!
    }
}
```

### Clean Solution -- Pipeline with Stage Abstraction

```java
public interface TranscodingStage {
    String name();
    TranscodeContext execute(TranscodeContext context);
}

public class TranscodeContext {
    private final String videoId;
    private final String rawPath;
    private final List<TranscodeProfile> profiles;
    private List<String> chunks;           // set by SPLIT stage
    private Map<String, List<String>> transcodedChunks; // set by TRANSCODE
    private Map<String, String> stitchedPaths;          // set by STITCH
    private String manifestPath;                         // set by MANIFEST
    private List<String> storedPaths;                    // set by STORE
    // getters, setters, constructor
}

public class TranscodingPipeline {

    private final List<TranscodingStage> stages;

    public TranscodingPipeline(List<TranscodingStage> stages) {
        this.stages = stages;
    }

    public TranscodeContext execute(TranscodeContext context) {
        for (TranscodingStage stage : stages) {
            System.out.printf("[Pipeline] Stage: %s for video %s%n",
                    stage.name(), context.getVideoId());
            context = stage.execute(context);
        }
        return context;
    }
}

// Concrete stages
public class ValidateStage implements TranscodingStage {
    @Override public String name() { return "VALIDATE"; }

    @Override
    public TranscodeContext execute(TranscodeContext ctx) {
        if (ctx.getRawPath() == null || ctx.getRawPath().isBlank()) {
            throw new TranscodingException("Raw path is missing");
        }
        // Check file size, format, duration limits
        return ctx;
    }
}

public class SplitStage implements TranscodingStage {
    private final int chunkDurationSec;

    public SplitStage(int chunkDurationSec) {
        this.chunkDurationSec = chunkDurationSec;
    }

    @Override public String name() { return "SPLIT"; }

    @Override
    public TranscodeContext execute(TranscodeContext ctx) {
        // Split raw video into GOP-aligned chunks
        // ffmpeg -i input.mp4 -c copy -map 0 -segment_time 4
        //        -f segment chunk_%03d.mp4
        List<String> chunks = List.of(
                ctx.getRawPath() + "/chunk_001.mp4",
                ctx.getRawPath() + "/chunk_002.mp4",
                ctx.getRawPath() + "/chunk_003.mp4");
        ctx.setChunks(chunks);
        return ctx;
    }
}

public class ParallelTranscodeStage implements TranscodingStage {
    private final ExecutorService executor;

    @Override public String name() { return "TRANSCODE"; }

    @Override
    public TranscodeContext execute(TranscodeContext ctx) {
        Map<String, List<String>> results = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (String chunk : ctx.getChunks()) {
            for (TranscodeProfile profile : ctx.getProfiles()) {
                futures.add(executor.submit(() -> {
                    String output = transcodeChunk(chunk, profile);
                    results.computeIfAbsent(profile.getResolution(),
                            k -> new CopyOnWriteArrayList<>()).add(output);
                }));
            }
        }
        // Wait for all parallel jobs
        for (Future<?> f : futures) { f.get(); }
        ctx.setTranscodedChunks(results);
        return ctx;
    }
}
```

### Numbered Call Chain -- Full Pipeline

```
1.  VideoService calls transcodingPipeline.execute(context)
2.  Pipeline iterates stages: VALIDATE -> SPLIT -> TRANSCODE -> STITCH -> MANIFEST -> STORE -> NOTIFY
3.  ValidateStage checks file format, size, duration
4.  SplitStage splits raw video into 4-second chunks (GOP-aligned)
5.  ParallelTranscodeStage creates N_chunks x N_profiles parallel jobs
6.  Each job runs FFmpeg: ffmpeg -i chunk.mp4 -vf scale=W:H -c:v codec output.mp4
7.  StitchStage concatenates transcoded chunks per resolution
8.  ManifestStage generates HLS master playlist + per-resolution playlists
9.  StoreStage uploads segments + manifests to S3
10. NotifyStage fires TRANSCODED event via Observer, updates Video status -> READY
```

### Retry Semantics

```
Stage fails (e.g., TRANSCODE stage fails on chunk_002 at 720p):
  |
  v
Pipeline catches exception at stage level
  |
  v
Only the failed chunk x profile job is retried (not the whole pipeline)
  |
  v
TranscodeContext preserves completed work from previous stages
  |
  v
After max retries exhausted: Video.transitionTo(FAILED)
  |
  v
FAILED.canTransitionTo(TRANSCODING) -> true (allows retry of full pipeline)
```

### Interview One-Liner

> "Transcoding is a 7-stage pipeline: validate -> split -> parallel transcode ->
> stitch -> manifest -> store -> notify. Each stage is an independent class with
> a single execute() method. The parallel transcode stage creates N_chunks x
> N_profiles jobs on a thread pool. Failed stages can retry independently."

### Cross-Reference

- **Strategy (Section 1):** TranscodingStrategy may use the pipeline internally
- **State (Section 7):** Pipeline moves video through TRANSCODING -> READY/FAILED
- **Observer (Section 6):** NotifyStage fires TRANSCODED event
- **Technologies:** See TECHNOLOGIES.md for FFmpeg commands at each stage

---

## 9. Template Method Pattern (Behavioral)

### Why

Both ParallelTranscodingStrategy and SequentialTranscodingStrategy share
common steps: validate profiles, prepare output paths, run transcoding,
collect results. Only the **execution model** (parallel vs. sequential) differs.
Template Method defines the skeleton; subclasses override the hook.

### Ugly Anti-Pattern -- Duplicated Setup/Teardown

```java
// UGLY: Both strategies duplicate validation, path generation, and result
// collection. Only the loop differs (parallel vs sequential).
// A bug fix in validation must be applied to both classes.

public class UglyParallelTranscoder implements TranscodingStrategy {

    @Override
    public List<String> transcode(String videoId, String rawPath,
                                  List<TranscodeProfile> profiles) {
        // Validation -- DUPLICATED in both classes
        if (rawPath == null) throw new IllegalArgumentException("null path");
        if (profiles.isEmpty()) throw new IllegalArgumentException("no profiles");

        // Path generation -- DUPLICATED
        List<String> outputPaths = new ArrayList<>();
        for (TranscodeProfile p : profiles) {
            outputPaths.add("s3://videos/" + videoId + "/" + p.getResolution());
        }

        // Parallel execution -- the ONLY difference
        ExecutorService exec = Executors.newFixedThreadPool(profiles.size());
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            int idx = i;
            futures.add(exec.submit(() -> doTranscode(rawPath,
                    profiles.get(idx), outputPaths.get(idx))));
        }
        // Collect -- DUPLICATED error handling
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            try { results.add(f.get()); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        return results;
    }
}

public class UglySequentialTranscoder implements TranscodingStrategy {

    @Override
    public List<String> transcode(String videoId, String rawPath,
                                  List<TranscodeProfile> profiles) {
        // Same validation -- DUPLICATED
        if (rawPath == null) throw new IllegalArgumentException("null path");
        if (profiles.isEmpty()) throw new IllegalArgumentException("no profiles");

        // Same path generation -- DUPLICATED
        List<String> outputPaths = new ArrayList<>();
        for (TranscodeProfile p : profiles) {
            outputPaths.add("s3://videos/" + videoId + "/" + p.getResolution());
        }

        // Sequential execution -- the ONLY difference
        List<String> results = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            results.add(doTranscode(rawPath,
                    profiles.get(i), outputPaths.get(i)));
        }
        return results;
    }
}
```

### Clean Solution -- Abstract Base with Template Method

```java
public abstract class AbstractTranscodingStrategy implements TranscodingStrategy {

    /** Template method -- defines the skeleton. */
    @Override
    public final List<String> transcode(String videoId, String rawPath,
                                        List<TranscodeProfile> profiles) {
        // Step 1: Validate (common)
        validateInput(rawPath, profiles);

        // Step 2: Generate output paths (common)
        List<String> outputPaths = generateOutputPaths(videoId, profiles);

        // Step 3: Execute transcoding (HOOK -- subclasses override this)
        List<String> results = executeTranscoding(rawPath, profiles, outputPaths);

        // Step 4: Log completion (common)
        logCompletion(videoId, results);

        return results;
    }

    /** Hook method -- subclasses provide the execution model. */
    protected abstract List<String> executeTranscoding(
            String rawPath,
            List<TranscodeProfile> profiles,
            List<String> outputPaths);

    // --- Common methods (not overridden) ---

    private void validateInput(String rawPath, List<TranscodeProfile> profiles) {
        if (rawPath == null || rawPath.isBlank())
            throw new IllegalArgumentException("rawPath is required");
        if (profiles == null || profiles.isEmpty())
            throw new IllegalArgumentException("at least one profile required");
    }

    private List<String> generateOutputPaths(String videoId,
                                             List<TranscodeProfile> profiles) {
        return profiles.stream()
                .map(p -> String.format("s3://videos/%s/%s_%s.mp4",
                        videoId, p.getResolution(), p.getCodec()))
                .collect(Collectors.toList());
    }

    private void logCompletion(String videoId, List<String> results) {
        System.out.printf("[Transcode] Video %s: %d profiles completed%n",
                videoId, results.size());
    }
}

// Parallel: override only the hook
public class ParallelTranscodingStrategy extends AbstractTranscodingStrategy {

    private final ExecutorService executor;

    public ParallelTranscodingStrategy(int threads) {
        this.executor = Executors.newFixedThreadPool(threads);
    }

    @Override
    protected List<String> executeTranscoding(String rawPath,
                                              List<TranscodeProfile> profiles,
                                              List<String> outputPaths) {
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            int idx = i;
            futures.add(executor.submit(() ->
                    doTranscode(rawPath, profiles.get(idx), outputPaths.get(idx))));
        }
        return futures.stream().map(f -> {
            try { return f.get(); }
            catch (Exception e) { throw new TranscodingException(e); }
        }).collect(Collectors.toList());
    }
}

// Sequential: override only the hook
public class SequentialTranscodingStrategy extends AbstractTranscodingStrategy {

    @Override
    protected List<String> executeTranscoding(String rawPath,
                                              List<TranscodeProfile> profiles,
                                              List<String> outputPaths) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            results.add(doTranscode(rawPath, profiles.get(i), outputPaths.get(i)));
        }
        return results;
    }
}
```

### ASCII Diagram

```
  AbstractTranscodingStrategy
  +----------------------------------+
  | transcode() [FINAL - template]   |
  |   1. validateInput()     common  |
  |   2. generateOutputPaths() common|
  |   3. executeTranscoding()  HOOK <--- subclasses override this
  |   4. logCompletion()     common  |
  +----------------------------------+
              |
       +------+------+
       |             |
  Parallel        Sequential
  Strategy        Strategy
  +----------+   +----------+
  |executeTr.|   |executeTr.|
  |  submit  |   |  for-loop|
  |  to pool |   |  one by  |
  |  collect |   |  one     |
  +----------+   +----------+
```

### Numbered Call Chain -- Template Method Execution

```
1. VideoService calls transcodingStrategy.transcode(videoId, rawPath, profiles)
2. AbstractTranscodingStrategy.transcode() [final] begins
3. Step 1: validateInput() checks rawPath and profiles (common)
4. Step 2: generateOutputPaths() builds S3 paths (common)
5. Step 3: executeTranscoding() -- HOOK, dispatches to subclass
6a. ParallelTranscodingStrategy: submits all profiles to thread pool, collects futures
6b. SequentialTranscodingStrategy: iterates profiles one by one in a loop
7. Step 4: logCompletion() logs profile count (common)
8. Returns list of output paths
```

### Interview One-Liner

> "AbstractTranscodingStrategy is a Template Method: transcode() is final and
> defines the skeleton (validate -> generate paths -> execute -> log). Only
> executeTranscoding() is abstract -- ParallelStrategy submits to a thread pool,
> SequentialStrategy iterates. Validation and path generation are never duplicated."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Validation, path generation written once | Inheritance hierarchy (vs. composition) |
| Bug fix in common code applies to all strategies | Adding a strategy that does not fit the template is awkward |
| Subclasses only implement the varying part | Template method must be final to prevent override of skeleton |
| Consistent logging and error handling | Harder to test the hook in isolation (must go through template) |

### Cross-Reference

- **Strategy (Section 1):** Template Method refines how strategies share common logic
- **Pipeline (Section 8):** The TRANSCODE stage in the pipeline uses one of these strategies
- **Factory (Section 3):** AppConfig picks Parallel or Sequential and injects it

---

## Quick Reference -- All Patterns at a Glance

```
+-----------------------------------------------------------+
|                  Video Streaming Platform                   |
+-----------------------------------------------------------+
|                                                           |
|  CREATIONAL           STRUCTURAL          BEHAVIORAL      |
|  +---------+          +----------+        +-----------+   |
|  | Builder |          | Facade   |        | Strategy  |   |
|  | (Video) |          | (Video   |        | x3 (Trans,|   |
|  |         |          |  Service)|        |  ABR, Rec)|   |
|  +---------+          +----------+        +-----------+   |
|  | Factory |          | Repository|       | Observer  |   |
|  | (App    |          | x3 (Video,|       | (Analytics|   |
|  |  Config)|          |  User,    |       |  Service) |   |
|  +---------+          |  WatchHist|       +-----------+   |
|                       +----------+        | State     |   |
|                                           | (Video    |   |
|  ENTERPRISE                               |  Status)  |   |
|  +----------+                             +-----------+   |
|  | Pipeline |                             | Template  |   |
|  | (Trans-  |                             | Method    |   |
|  |  coding  |                             | (Abstract |   |
|  |  DAG)    |                             |  Transcode|   |
|  +----------+                             |  Strategy)|   |
|                                           +-----------+   |
+-----------------------------------------------------------+
```

### Interview Cheat Sheet

| Pattern | One-Liner |
|---------|-----------|
| Strategy x3 | "Three axes: how to transcode, which bitrate, which videos to recommend -- all injected, swappable, A/B testable" |
| Builder | "Video.Builder validates required fields, wraps tags immutably, sets initial state to UPLOADING" |
| Factory | "AppConfig is the composition root -- one place to swap Parallel for Sequential transcoding" |
| Repository x3 | "VideoRepo, UserRepo, WatchHistoryRepo abstract ConcurrentHashMap (demo) vs PostgreSQL+S3+Cassandra (prod)" |
| Facade | "VideoService orchestrates upload -> transcode -> store -> stream -> recommend behind one API" |
| Observer | "VideoService fires VIEW/LIKE/TRANSCODED events; AnalyticsService, recommender, CDN warmer consume them" |
| State | "VideoStatus enum with transition map -- UPLOADING -> UPLOADED -> TRANSCODING -> READY, FAILED can retry" |
| Pipeline | "7-stage DAG: validate -> split -> parallel transcode -> stitch -> manifest -> store -> notify" |
| Template Method | "AbstractTranscodingStrategy: validate + path gen are common; only executeTranscoding() is overridden" |
