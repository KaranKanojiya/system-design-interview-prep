# Low-Level Design: Video Streaming Platform (YouTube/Netflix)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Adaptive Bitrate Streaming, Transcoding Pipeline, Chunked Upload, CDN Edge Caching, HLS/DASH Manifest Generation
> This is the media infrastructure interview question. It tests your understanding of video upload pipelines (chunked/resumable), transcoding strategies (parallel vs. sequential), adaptive bitrate streaming (ABR), CDN cache hit/miss routing, and recommendation algorithms -- all with concurrency-safe design.

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
| **Model** | `model/` | Domain entities: Video (Builder, videoId, title, description, uploaderId, status, resolutions, duration), VideoStatus (enum: UPLOADING, UPLOADED, TRANSCODING, READY, FAILED, DELETED), VideoChunk (chunkId, videoId, chunkIndex, sizeBytes, resolution, url), Resolution (enum: P240 through P4K with width, height, bitrate), Codec (enum: H264, H265, VP9, AV1 with compressionRatio, compatibility), StreamManifest (videoId, protocol HLS/DASH, List<StreamVariant>), StreamVariant (resolution, codec, bitrate, segmentUrls), TranscodeJob (jobId, videoId, sourceUrl, targetResolution, targetCodec, status, progress), TranscodeJobStatus (enum: QUEUED, PROCESSING, COMPLETED, FAILED), WatchHistory (userId, videoId, watchedAt, watchDurationSeconds, completionPercent), VideoMetadata (tags, category, language, uploadDate, viewCount, likeCount), User (userId, name, subscriberCount, uploadedVideos). |
| **Strategy (Transcoding)** | `strategy/transcoding/` | Pluggable transcoding: TranscodingStrategy interface with ParallelTranscodingStrategy (transcode all resolutions concurrently via ExecutorService) and SequentialTranscodingStrategy (one at a time, baseline comparison). Strategy pattern -- swap transcoding approach without touching TranscodingService. |
| **Strategy (Streaming/ABR)** | `strategy/streaming/` | Pluggable adaptive bitrate: ABRStrategy interface with ThroughputBasedABR (pick highest resolution fitting measured bandwidth) and BufferBasedABR (pick based on buffer fullness -- more conservative, avoids rebuffering). Strategy pattern -- swap ABR algorithm without changing StreamingService. |
| **Strategy (Recommendation)** | `strategy/recommendation/` | Pluggable recommendations: RecommendationStrategy interface with TrendingRecommendation (most viewed in last 24h) and PersonalizedRecommendation (based on watch history + category affinity scoring). Strategy pattern -- swap recommendation engine at runtime. |
| **Service** | `service/` | Business logic: VideoService (Facade -- upload, stream, manage video lifecycle), UploadService (chunked upload, resumable, progress tracking), TranscodingService (manages transcode jobs, parallel execution), StreamingService (generates manifests, serves chunks, ABR), CDNService (simulated CDN: cache hit/miss, edge/origin routing), RecommendationService (applies recommendation strategy), SearchService (search by title, tags, category), AnalyticsService (view counts, watch time, engagement metrics). |
| **Store** | `store/` | Object storage: VideoStore interface with InMemoryVideoStore (simulated S3 -- stores chunks, manifests, transcoded segments). |
| **Repository** | `repository/` | Data access layer: VideoRepository, UserRepository, WatchHistoryRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like API entry point: VideoController maps requests to VideoService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | StreamingStatsDisplay: streaming stats, CDN hit rates, transcoding progress, bandwidth metrics. |
| **Exception** | `exception/` | Domain exceptions: VideoStreamingException (base), UploadException, TranscodingException, VideoNotFoundException. |

### Why Video Streaming Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you understand chunked/resumable uploads?                  --> Upload Pipeline
  2. Can you explain HLS vs. DASH manifest generation?             --> Streaming Protocols
  3. Is transcoding parallel across resolutions?                   --> Concurrency
  4. Do you know adaptive bitrate streaming (ABR)?                 --> Client Experience
  5. Is CDN routing modeled (edge cache hit/miss → origin)?        --> CDN Architecture
  6. Are strategies pluggable (transcoding, ABR, recommendation)?  --> Strategy Pattern
  7. Is VideoService a clean Facade over multiple services?        --> Facade Pattern
  8. Do you separate upload from transcoding from streaming?       --> Separation of Concerns
  9. Can you add a new codec/resolution without changing services? --> Open-Closed
  10. Is transcoding thread-safe for concurrent job processing?     --> Concurrency
```

---

## 2. Package Structure

```
com.systemdesign.videostreaming
│
├── model/
│   ├── Video.java               -- Builder, videoId, title, description, uploaderId, status, resolutions, duration
│   ├── VideoStatus.java         -- enum: UPLOADING, UPLOADED, TRANSCODING, READY, FAILED, DELETED
│   ├── VideoChunk.java          -- chunkId, videoId, chunkIndex, sizeBytes, resolution, url
│   ├── Resolution.java          -- enum: P240(240,426,240), P360(360,640,360), P480(480,854,480),
│   │                                     P720(720,1280,720), P1080(1080,1920,1080), P4K(2160,3840,2160)
│   ├── Codec.java               -- enum: H264, H265, VP9, AV1 with compressionRatio, compatibility
│   ├── StreamManifest.java      -- videoId, protocol (HLS/DASH), List<StreamVariant> variants
│   ├── StreamVariant.java       -- resolution, codec, bitrate, segmentUrls
│   ├── TranscodeJob.java        -- jobId, videoId, sourceUrl, targetResolution, targetCodec, status, progress
│   ├── TranscodeJobStatus.java  -- enum: QUEUED, PROCESSING, COMPLETED, FAILED
│   ├── WatchHistory.java        -- userId, videoId, watchedAt, watchDurationSeconds, completionPercent
│   ├── VideoMetadata.java       -- tags, category, language, uploadDate, viewCount, likeCount
│   └── User.java                -- userId, name, subscriberCount, uploadedVideos
│
├── strategy/
│   ├── transcoding/
│   │   ├── TranscodingStrategy.java          -- interface: transcode(Video, Resolution, Codec) → TranscodeJob
│   │   ├── ParallelTranscodingStrategy.java  -- transcode all resolutions concurrently
│   │   └── SequentialTranscodingStrategy.java -- transcode one at a time (comparison baseline)
│   │
│   ├── streaming/
│   │   ├── ABRStrategy.java                  -- interface: selectResolution(bandwidth, bufferLevel) → Resolution
│   │   ├── ThroughputBasedABR.java           -- pick highest resolution fitting current bandwidth
│   │   └── BufferBasedABR.java               -- pick based on buffer fullness (conservative)
│   │
│   └── recommendation/
│       ├── RecommendationStrategy.java       -- interface: recommend(userId, limit) → List<Video>
│       ├── TrendingRecommendation.java       -- most viewed in last 24h
│       └── PersonalizedRecommendation.java   -- watch history + category affinity
│
├── service/
│   ├── VideoService.java        -- FACADE: upload, stream, manage video lifecycle
│   ├── UploadService.java       -- chunked upload, resumable, progress tracking
│   ├── TranscodingService.java  -- manages transcode jobs, parallel execution
│   ├── StreamingService.java    -- generates manifests, serves chunks, ABR
│   ├── CDNService.java          -- simulated CDN: cache hit/miss, edge/origin routing
│   ├── RecommendationService.java -- applies recommendation strategy
│   ├── SearchService.java       -- search by title, tags, category
│   └── AnalyticsService.java    -- view counts, watch time, engagement
│
├── store/
│   ├── VideoStore.java          -- interface: storeChunk, getChunk, getManifest
│   └── InMemoryVideoStore.java  -- simulated object storage (S3-like)
│
├── repository/
│   ├── VideoRepository.java, InMemoryVideoRepository.java
│   ├── UserRepository.java, InMemoryUserRepository.java
│   └── WatchHistoryRepository.java, InMemoryWatchHistoryRepository.java
│
├── controller/
│   └── VideoController.java     -- REST-like entry point
│
├── config/
│   └── AppConfig.java           -- factory wiring, pure constructor injection
│
├── display/
│   └── StreamingStatsDisplay.java -- formatted streaming/CDN/transcoding stats
│
├── exception/
│   ├── VideoStreamingException.java  -- base exception for all streaming errors
│   ├── UploadException.java          -- thrown when upload fails or is interrupted
│   ├── TranscodingException.java     -- thrown when transcoding job fails
│   └── VideoNotFoundException.java   -- thrown when video lookup fails
│
└── VideoStreamingApp.java       -- Main demo: wires everything, runs streaming scenarios
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                  VIDEO LIFECYCLE STATE MACHINE                                    ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌──────────────────────────────────────────────────────────────────┐
    │                                                                  │
    │   UPLOADING ──────► UPLOADED ──────► TRANSCODING ──────► READY  │
    │       │                                   │                      │
    │       │                                   │                      │
    │       ▼                                   ▼                      │
    │     FAILED                              FAILED                   │
    │                                                                  │
    │   Any state ──────────────────────────────────────────► DELETED  │
    │                                                                  │
    │   State transitions are the BACKBONE of the video pipeline.      │
    │   Every service checks current state before proceeding.          │
    │   Invalid transitions throw VideoStreamingException.             │
    └──────────────────────────────────────────────────────────────────┘

    UPLOAD FLOW:
    Client             UploadService          VideoStore          TranscodingService
      │                     │                     │                      │
      │──chunk[0]──────────►│                     │                      │
      │                     │──storeChunk(0)─────►│                      │
      │◄─────ack(0)─────────│                     │                      │
      │──chunk[1]──────────►│                     │                      │
      │                     │──storeChunk(1)─────►│                      │
      │◄─────ack(1)─────────│                     │                      │
      │       ...           │                     │                      │
      │──chunk[N]──────────►│                     │                      │
      │                     │──storeChunk(N)─────►│                      │
      │                     │──assembleVideo()───►│                      │
      │                     │                     │                      │
      │                     │──triggerTranscode()─────────────────────────►│
      │◄────upload complete──│                     │                      │
      │                     │                     │                      │


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                  TRANSCODING STRATEGY HIERARCHY (Strategy Pattern)                ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-------------------------------------------------------------------+
    |            <<interface>>  TranscodingStrategy                       |
    |-------------------------------------------------------------------|
    | + transcode(video: Video, resolution: Resolution,                  |
    |             codec: Codec): TranscodeJob                            |
    | + transcodeAll(video: Video, resolutions: List<Resolution>,        |
    |               codec: Codec): List<TranscodeJob>                    |
    | + getStrategyName(): String                                        |
    +-------------------------------------------------------------------+
          ^                                    ^
          |                                    |
     implements                           implements
          |                                    |
    +-----+------------------+   +-------------+------------------+
    | Parallel               |   | Sequential                     |
    |  TranscodingStrategy   |   |  TranscodingStrategy           |
    |------------------------|   |--------------------------------|
    | -executorService       |   |                                |
    |  :ExecutorService      |   |                                |
    | -threadPoolSize: int   |   |                                |
    |------------------------|   |--------------------------------|
    | transcodeAll():        |   | transcodeAll():                |
    |  submit ALL resolutions|   |  for each resolution:          |
    |  to thread pool at     |   |    transcode synchronously     |
    |  once. Each resolution |   |    wait for completion         |
    |  runs on its own       |   |    then start next             |
    |  thread.               |   |                                |
    |                        |   |  Total time = sum of all       |
    |  Total time = max of   |   |  individual transcode times    |
    |  individual times      |   |  (SLOW -- for comparison only) |
    |  (FAST -- production)  |   |                                |
    +------------------------+   +--------------------------------+


    PARALLEL vs. SEQUENTIAL (why it matters in interviews):

    ┌──────────────────────────────────────────────────────────────────┐
    │  Video: "cats.mp4" (1080p source, 45 min)                       │
    │                                                                  │
    │  SEQUENTIAL (naive):                                             │
    │    P240 ████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  2 min           │
    │    P360 ░░░░████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  3 min           │
    │    P480 ░░░░░░░░██████░░░░░░░░░░░░░░░░░░░░░░░  5 min           │
    │    P720 ░░░░░░░░░░░░░░████████░░░░░░░░░░░░░░░  8 min           │
    │    P1080░░░░░░░░░░░░░░░░░░░░░░████████████░░░  12 min          │
    │                                                                  │
    │    Total wall-clock time: 2+3+5+8+12 = 30 minutes               │
    │                                                                  │
    │  PARALLEL (production):                                          │
    │    P240 ██░░░░░░░░░░  2 min  ─┐                                 │
    │    P360 ███░░░░░░░░░  3 min   │                                 │
    │    P480 █████░░░░░░░  5 min   ├── all run simultaneously        │
    │    P720 ████████░░░░  8 min   │                                 │
    │    P1080████████████  12 min ─┘                                  │
    │                                                                  │
    │    Total wall-clock time: max(2,3,5,8,12) = 12 minutes          │
    │    Speedup: 2.5x                                                 │
    │                                                                  │
    │  YouTube uses parallel transcoding across distributed workers.   │
    │  Netflix pre-transcodes EVERYTHING before content goes live.     │
    └──────────────────────────────────────────────────────────────────┘


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              ADAPTIVE BITRATE (ABR) STRATEGY HIERARCHY                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-------------------------------------------------------------------+
    |            <<interface>>  ABRStrategy                               |
    |-------------------------------------------------------------------|
    | + selectResolution(bandwidthKbps: long,                            |
    |                    bufferLevelSeconds: double): Resolution          |
    | + getStrategyName(): String                                        |
    +-------------------------------------------------------------------+
          ^                                    ^
          |                                    |
     implements                           implements
          |                                    |
    +-----+------------------+   +-------------+------------------+
    | ThroughputBased        |   | BufferBased                    |
    |  ABR                   |   |  ABR                           |
    |------------------------|   |--------------------------------|
    | -safetyMargin: double  |   | -lowThreshold: double (5s)     |
    |  (0.7 = use 70% of    |   | -highThreshold: double (30s)   |
    |   measured bandwidth)  |   |                                |
    |------------------------|   |--------------------------------|
    | selectResolution():    |   | selectResolution():            |
    |  effectiveBW =         |   |  if buffer < lowThreshold:     |
    |    bandwidth * margin  |   |    pick LOWEST resolution      |
    |  pick HIGHEST          |   |  if buffer > highThreshold:    |
    |  resolution whose      |   |    pick HIGHEST resolution     |
    |  bitrate <= effectiveBW|   |  else:                         |
    |                        |   |    interpolate linearly        |
    |  AGGRESSIVE: maximizes |   |                                |
    |  quality, risks        |   |  CONSERVATIVE: avoids          |
    |  rebuffering           |   |  rebuffering, may sacrifice    |
    |                        |   |  quality                       |
    +------------------------+   +--------------------------------+

    ABR DECISION FLOW (what the video player does every few seconds):

    Player measures bandwidth
         │
         ▼
    ABR Strategy selects resolution
         │
         ├── bandwidth = 5 Mbps, buffer = 20s
         │     ThroughputBased → P1080 (bitrate=4.5Mbps, within 70% of 5Mbps=3.5Mbps... NO)
         │                       P720  (bitrate=2.5Mbps, within 3.5Mbps? YES) → P720
         │     BufferBased    → buffer 20s is between 5s and 30s
         │                       interpolate → P720
         │
         ├── bandwidth = 15 Mbps, buffer = 35s
         │     ThroughputBased → P4K (bitrate=12Mbps, within 70% of 15=10.5Mbps... NO)
         │                       P1080 (bitrate=4.5Mbps? YES) → P1080
         │     BufferBased    → buffer 35s > 30s highThreshold → P4K (aggressive!)
         │
         └── bandwidth = 800 Kbps, buffer = 3s
               ThroughputBased → P240 (bitrate=400Kbps, within 560Kbps? YES) → P240
               BufferBased    → buffer 3s < 5s lowThreshold → P240 (lowest, panic mode)


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              RECOMMENDATION STRATEGY HIERARCHY                                    ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-------------------------------------------------------------------+
    |            <<interface>>  RecommendationStrategy                    |
    |-------------------------------------------------------------------|
    | + recommend(userId: String, limit: int): List<Video>               |
    | + getStrategyName(): String                                        |
    +-------------------------------------------------------------------+
          ^                                    ^
          |                                    |
     implements                           implements
          |                                    |
    +-----+------------------+   +-------------+------------------+
    | Trending               |   | Personalized                   |
    |  Recommendation        |   |  Recommendation                |
    |------------------------|   |--------------------------------|
    | -timeWindowHours: int  |   | -watchHistoryRepo              |
    |  (default 24)          |   |  :WatchHistoryRepository       |
    | -analyticsService      |   | -analyticsService              |
    |  :AnalyticsService     |   |  :AnalyticsService             |
    |------------------------|   |--------------------------------|
    | recommend():           |   | recommend():                   |
    |  get all videos with   |   |  1. get user's watch history   |
    |  uploadDate in last    |   |  2. compute category affinity  |
    |  24h, sort by          |   |     (% of watch time per cat.) |
    |  viewCount desc,       |   |  3. score each unwatched video:|
    |  return top N          |   |     score = categoryAffinity   |
    |                        |   |           * recencyBoost       |
    |  Simple but effective  |   |           * popularityBoost    |
    |  for cold-start users  |   |  4. sort by score desc         |
    +------------------------+   +--------------------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    SERVICE LAYER (Facade Pattern)                                 ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────────────────────────────────────────────────────────────────┐
    │                    VideoController                                    │
    │   upload() │ stream() │ search() │ recommend() │ getStats()         │
    └────────┬──────────┬──────────┬──────────┬───────────┬──────────────┘
             │          │          │          │           │
             ▼          ▼          ▼          ▼           ▼
    ┌─────────────────────────────────────────────────────────────────────┐
    │                    VideoService (FACADE)                              │
    │   Orchestrates: upload → transcode → manifest → stream → analytics  │
    └──┬──────────┬───────────┬──────────┬───────────┬──────────┬────────┘
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Upload     Transcoding  Streaming   CDN      Recommend.  Analytics
    Service    Service      Service     Service  Service     Service
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Video      Transcoding  ABR        CDN       Recommend.  Watch
    Store      Strategy     Strategy   Cache     Strategy    History
               (pluggable)  (pluggable)          (pluggable)  Repo


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    CDN ARCHITECTURE (Edge/Origin Routing)                         ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Client requests chunk for video "vid-001", resolution P720, segment 5:

    Client ─────► CDN Edge (nearest PoP)
                      │
                      ├── Cache HIT? ──► Return chunk immediately (< 10ms)
                      │
                      └── Cache MISS? ──► CDN Origin
                                              │
                                              ├── Origin Cache HIT? ──► Return + cache at edge
                                              │
                                              └── Origin Cache MISS? ──► Object Storage (S3)
                                                                              │
                                                                              └──► Return + cache at
                                                                                   origin + edge

    ┌──────────────────────────────────────────────────────────────────┐
    │  CDN CACHE HIERARCHY                                             │
    │                                                                  │
    │  Layer          │ Latency  │ Hit Rate │ Capacity                │
    │  ───────────────┼──────────┼──────────┼─────────────────────────│
    │  Edge PoP       │ < 10ms   │ ~85%     │ Limited (hot content)   │
    │  Origin Shield  │ < 50ms   │ ~95%     │ Large (warm content)    │
    │  Object Storage │ < 200ms  │ 100%     │ Unlimited (all content) │
    │                                                                  │
    │  Popular videos → cached at edge → 85% of requests served       │
    │  Long-tail videos → fetched from origin or S3 on demand         │
    │                                                                  │
    │  YouTube: ~85% of views are served from CDN edge cache.         │
    │  Netflix: Open Connect appliances at ISP locations.             │
    └──────────────────────────────────────────────────────────────────┘
```

---

## 4. Entity Design

### 4.1 Video (Builder Pattern)

> **Builder pattern** is essential for Video because it has many optional fields (description, metadata, resolutions) and requires validation. The Builder separates construction from representation and enforces required fields at build time.

#### Anti-Pattern: Telescoping Constructor

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: Telescoping Constructor                   │
     │                                                                  │
     │   public Video(String videoId, String title, String uploaderId) │
     │   public Video(String videoId, String title, String uploaderId, │
     │                String description)                               │
     │   public Video(String videoId, String title, String uploaderId, │
     │                String description, Duration duration)            │
     │   public Video(String videoId, String title, String uploaderId, │
     │                String description, Duration duration,            │
     │                VideoStatus status)                               │
     │   public Video(String videoId, String title, String uploaderId, │
     │                String description, Duration duration,            │
     │                VideoStatus status, Set<Resolution> resolutions)  │
     │                                                                  │
     │   // Caller side -- WHICH STRING IS WHICH?                       │
     │   new Video("vid-001", "Cats", "user-42", null, null,           │
     │             VideoStatus.UPLOADING, null);                        │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. Cannot tell which parameter is which at call site         │
     │     2. null for optional params is error-prone                   │
     │     3. Adding a new field requires yet another constructor       │
     │     4. No validation until all params are collected              │
     │     5. Immutability is hard to enforce                           │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Builder Pattern

```java
/**
 * A video on the platform.
 *
 * Built via Video.Builder to handle many optional fields cleanly.
 * Immutable after construction -- status transitions create new instances
 * (or use a mutable status field protected by synchronization).
 *
 * Builder enforces:
 *   - videoId, title, uploaderId are REQUIRED (build() throws if missing)
 *   - description, duration, resolutions are optional with sensible defaults
 *   - status defaults to UPLOADING
 *   - videoId is auto-generated if not provided
 *
 * Used by:
 *   - UploadService: creates Video in UPLOADING status
 *   - TranscodingService: transitions to TRANSCODING, then READY
 *   - StreamingService: reads Video to generate manifest
 *   - VideoRepository: stores/retrieves Video entities
 */
public class Video {

    private final String videoId;
    private final String title;
    private final String description;
    private final String uploaderId;
    private volatile VideoStatus status;           // mutable -- see Concurrency section
    private final Set<Resolution> resolutions;     // grows as transcoding completes
    private final Duration duration;
    private final Instant createdAt;

    private Video(Builder builder) {
        this.videoId = builder.videoId;
        this.title = builder.title;
        this.description = builder.description;
        this.uploaderId = builder.uploaderId;
        this.status = builder.status;
        this.resolutions = ConcurrentHashMap.newKeySet();
        if (builder.resolutions != null) {
            this.resolutions.addAll(builder.resolutions);
        }
        this.duration = builder.duration;
        this.createdAt = Instant.now();
    }

    // --- Getters ---
    public String getVideoId() { return videoId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getUploaderId() { return uploaderId; }
    public VideoStatus getStatus() { return status; }
    public Set<Resolution> getResolutions() { return Collections.unmodifiableSet(resolutions); }
    public Duration getDuration() { return duration; }
    public Instant getCreatedAt() { return createdAt; }

    // --- Status transitions (synchronized for thread safety) ---
    public synchronized void transitionTo(VideoStatus newStatus) {
        if (!status.canTransitionTo(newStatus)) {
            throw new VideoStreamingException(
                "Invalid transition: " + status + " → " + newStatus);
        }
        this.status = newStatus;
    }

    // --- Resolution management (concurrent set, safe for parallel transcoding) ---
    public void addResolution(Resolution resolution) {
        resolutions.add(resolution);
    }

    // --- Builder ---
    public static class Builder {
        private String videoId;
        private String title;          // REQUIRED
        private String description = "";
        private String uploaderId;     // REQUIRED
        private VideoStatus status = VideoStatus.UPLOADING;
        private Set<Resolution> resolutions;
        private Duration duration;

        public Builder(String title, String uploaderId) {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title is required");
            }
            if (uploaderId == null || uploaderId.isBlank()) {
                throw new IllegalArgumentException("uploaderId is required");
            }
            this.title = title;
            this.uploaderId = uploaderId;
            this.videoId = "vid-" + UUID.randomUUID().toString().substring(0, 8);
        }

        public Builder videoId(String videoId) {
            this.videoId = videoId;
            return this;
        }
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        public Builder status(VideoStatus status) {
            this.status = status;
            return this;
        }
        public Builder resolutions(Set<Resolution> resolutions) {
            this.resolutions = resolutions;
            return this;
        }
        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Video build() {
            return new Video(this);
        }
    }

    @Override
    public String toString() {
        return String.format("Video[id=%s, title='%s', status=%s, resolutions=%s]",
            videoId, title, status, resolutions);
    }
}
```

### 4.2 VideoStatus (State Machine Enum)

```java
/**
 * Status of a video in the processing pipeline.
 *
 * This enum encodes the valid state transitions as a state machine.
 * Each status knows which statuses it can transition TO.
 * This prevents invalid transitions like READY → UPLOADING.
 *
 * State machine:
 *   UPLOADING → UPLOADED → TRANSCODING → READY
 *   UPLOADING → FAILED
 *   TRANSCODING → FAILED
 *   Any → DELETED
 *
 * Used by:
 *   - Video.transitionTo(): validates before changing status
 *   - UploadService: UPLOADING → UPLOADED
 *   - TranscodingService: UPLOADED → TRANSCODING → READY or FAILED
 *   - VideoService: any → DELETED (soft delete)
 */
public enum VideoStatus {

    UPLOADING(Set.of("UPLOADED", "FAILED", "DELETED")),
    UPLOADED(Set.of("TRANSCODING", "DELETED")),
    TRANSCODING(Set.of("READY", "FAILED", "DELETED")),
    READY(Set.of("DELETED")),
    FAILED(Set.of("DELETED")),
    DELETED(Set.of());                             // terminal state

    private final Set<String> allowedTransitions;

    VideoStatus(Set<String> allowedTransitions) {
        this.allowedTransitions = allowedTransitions;
    }

    /**
     * Check if transitioning to the given status is valid.
     * Called by Video.transitionTo() before changing state.
     */
    public boolean canTransitionTo(VideoStatus target) {
        return allowedTransitions.contains(target.name());
    }
}
```

### 4.3 Resolution (Enum with Bitrate)

```java
/**
 * Video resolution levels supported by the platform.
 *
 * Each resolution encodes width, height, and recommended bitrate (Kbps).
 * The bitrate is used by ABR strategies to decide which resolution the
 * client can sustain given current network bandwidth.
 *
 * Bitrate values are approximate and match industry standards:
 *   - YouTube recommended bitrates for H264 at standard frame rate
 *   - Netflix per-title encoding may vary, but these are good defaults
 *
 * Used by:
 *   - ABRStrategy: compares resolution.bitrate against measured bandwidth
 *   - TranscodingService: transcodes source to each target resolution
 *   - StreamManifest: lists available resolutions in manifest file
 */
public enum Resolution {

    P240(240, 426, 240, 400),       // 426x240 @ 400 Kbps
    P360(360, 640, 360, 750),       // 640x360 @ 750 Kbps
    P480(480, 854, 480, 1_000),     // 854x480 @ 1 Mbps
    P720(720, 1280, 720, 2_500),    // 1280x720 @ 2.5 Mbps
    P1080(1080, 1920, 1080, 4_500), // 1920x1080 @ 4.5 Mbps
    P4K(2160, 3840, 2160, 12_000);  // 3840x2160 @ 12 Mbps

    private final int label;
    private final int width;
    private final int height;
    private final int bitrateKbps;

    Resolution(int label, int width, int height, int bitrateKbps) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.bitrateKbps = bitrateKbps;
    }

    public int getLabel() { return label; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getBitrateKbps() { return bitrateKbps; }

    /**
     * Find the highest resolution whose bitrate fits within the given bandwidth.
     * Returns P240 as minimum if nothing fits (graceful degradation).
     *
     * Called by ThroughputBasedABR.selectResolution().
     */
    public static Resolution highestFitting(long bandwidthKbps) {
        Resolution best = P240;
        for (Resolution r : values()) {
            if (r.bitrateKbps <= bandwidthKbps && r.label > best.label) {
                best = r;
            }
        }
        return best;
    }
}
```

### 4.4 Codec

```java
/**
 * Video codecs supported by the transcoding pipeline.
 *
 * Each codec has a compression ratio (relative to H264 baseline) and a
 * compatibility score (percentage of devices that support it).
 *
 * In production:
 *   - H264: universal support, baseline codec, used as fallback
 *   - H265/HEVC: ~40% better compression, but patent-encumbered
 *   - VP9: Google's open-source alternative, used by YouTube
 *   - AV1: next-gen open codec, ~30% better than VP9, growing support
 *
 * Used by:
 *   - TranscodeJob: specifies target codec for transcoding
 *   - StreamManifest: lists codec per variant for player compatibility
 */
public enum Codec {

    H264(1.0, 0.99),    // baseline: 100% compression ratio, 99% device support
    H265(0.6, 0.75),    // 40% smaller files, 75% device support
    VP9(0.65, 0.80),    // 35% smaller files, 80% device support (Chrome/Android)
    AV1(0.50, 0.45);    // 50% smaller files, 45% device support (growing)

    private final double compressionRatio;  // 1.0 = baseline (H264)
    private final double compatibility;     // fraction of devices supporting this

    Codec(double compressionRatio, double compatibility) {
        this.compressionRatio = compressionRatio;
        this.compatibility = compatibility;
    }

    public double getCompressionRatio() { return compressionRatio; }
    public double getCompatibility() { return compatibility; }

    /**
     * Effective bitrate for a given resolution using this codec.
     * Lower compression ratio = smaller file = lower effective bitrate needed.
     */
    public int effectiveBitrateKbps(Resolution resolution) {
        return (int) (resolution.getBitrateKbps() * compressionRatio);
    }
}
```

### 4.5 VideoChunk

```java
/**
 * A single chunk of an uploaded or transcoded video.
 *
 * During upload: large videos are split into fixed-size chunks (e.g., 5MB).
 * This enables resumable uploads -- if the connection drops at chunk 47,
 * the client resumes from chunk 47, not from the beginning.
 *
 * During streaming: transcoded video is segmented (e.g., 2-4 second segments).
 * The player requests one segment at a time, enabling ABR resolution switching
 * at segment boundaries.
 *
 * Used by:
 *   - UploadService: receives chunks from client, stores via VideoStore
 *   - StreamingService: reads chunks to serve to player
 *   - CDNService: caches popular chunks at edge locations
 *   - VideoStore: stores/retrieves chunks by (videoId, chunkIndex, resolution)
 */
public record VideoChunk(
    String chunkId,
    String videoId,
    int chunkIndex,
    long sizeBytes,
    Resolution resolution,
    String url
) {
    public VideoChunk {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId required");
        }
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("videoId required");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }

    /**
     * Generate a storage key for this chunk.
     * Format: videos/{videoId}/{resolution}/{chunkIndex}
     * This mirrors S3 key structure in production.
     */
    public String storageKey() {
        String resLabel = resolution != null ? resolution.name() : "source";
        return String.format("videos/%s/%s/%04d", videoId, resLabel, chunkIndex);
    }
}
```

### 4.6 StreamManifest and StreamVariant

> **HLS (HTTP Live Streaming)** and **DASH (Dynamic Adaptive Streaming over HTTP)** are the two dominant streaming protocols. Both use a manifest file that lists all available quality levels. The player reads the manifest, then requests segments from the appropriate quality level based on ABR decisions.

#### Anti-Pattern: Hardcoded Single-Quality Stream

```
     ┌──────────────────────────────────────────────────────────────────┐
     │      ANTI-PATTERN: Hardcoded Single-Quality Streaming            │
     │                                                                  │
     │   // Client requests video, server picks ONE quality             │
     │   public String getVideoUrl(String videoId) {                    │
     │       return "/videos/" + videoId + "/1080p.mp4";               │
     │   }                                                              │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. User on 3G gets 1080p → infinite buffering               │
     │     2. User on fiber gets 1080p → could have watched 4K         │
     │     3. No mid-stream quality switching                           │
     │     4. Network fluctuations cause stalls                        │
     │     5. Cannot serve different codecs to different devices       │
     │                                                                  │
     │   In 2024+, NO production video platform uses single-quality.   │
     │   ABR + manifest is the universal standard.                     │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Manifest with Multiple Variants

```java
/**
 * A stream manifest describing all available quality variants for a video.
 *
 * This is the Java representation of an HLS .m3u8 or DASH .mpd file.
 * The player downloads this first, then uses ABR logic to pick a variant.
 *
 * HLS manifest example (what this generates):
 *   #EXTM3U
 *   #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=426x240
 *   /videos/vid-001/P240/playlist.m3u8
 *   #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
 *   /videos/vid-001/P720/playlist.m3u8
 *   #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
 *   /videos/vid-001/P1080/playlist.m3u8
 *
 * Used by:
 *   - StreamingService: generates manifest when client starts playback
 *   - CDNService: caches manifest at edge (small file, frequently requested)
 *   - VideoController: returns manifest URL to client
 */
public class StreamManifest {

    private final String videoId;
    private final String protocol;           // "HLS" or "DASH"
    private final List<StreamVariant> variants;

    public StreamManifest(String videoId, String protocol, List<StreamVariant> variants) {
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("videoId required");
        }
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("manifest must have at least one variant");
        }
        this.videoId = videoId;
        this.protocol = protocol;
        this.variants = List.copyOf(variants);  // immutable copy
    }

    public String getVideoId() { return videoId; }
    public String getProtocol() { return protocol; }
    public List<StreamVariant> getVariants() { return variants; }

    /**
     * Find the variant matching a given resolution.
     * Called by StreamingService after ABR selects a resolution.
     */
    public Optional<StreamVariant> getVariantForResolution(Resolution resolution) {
        return variants.stream()
            .filter(v -> v.resolution() == resolution)
            .findFirst();
    }

    @Override
    public String toString() {
        return String.format("StreamManifest[video=%s, protocol=%s, variants=%d]",
            videoId, protocol, variants.size());
    }
}
```

```java
/**
 * A single quality variant within a stream manifest.
 *
 * Each variant represents one resolution+codec combination with its own
 * list of segment URLs. The player switches between variants at segment
 * boundaries (typically every 2-4 seconds).
 *
 * Used by:
 *   - StreamManifest: holds list of variants
 *   - StreamingService: selects variant based on ABR resolution choice
 *   - CDNService: caches segment URLs from variants
 */
public record StreamVariant(
    Resolution resolution,
    Codec codec,
    int bitrateKbps,
    List<String> segmentUrls
) {
    public StreamVariant {
        if (resolution == null) {
            throw new IllegalArgumentException("resolution required");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec required");
        }
        if (segmentUrls == null) {
            throw new IllegalArgumentException("segmentUrls required");
        }
    }
}
```

### 4.7 TranscodeJob

```java
/**
 * Represents a single transcoding job: convert a video to a target resolution + codec.
 *
 * A full transcoding pipeline creates N jobs (one per target resolution).
 * ParallelTranscodingStrategy submits all N jobs concurrently.
 *
 * Progress is tracked as a percentage (0-100). In production, this maps
 * to a Kafka event stream or polling endpoint for the upload dashboard.
 *
 * Used by:
 *   - TranscodingService: creates, submits, and monitors jobs
 *   - TranscodingStrategy: executes the actual transcode logic
 *   - VideoService: checks all jobs complete before marking video READY
 */
public class TranscodeJob {

    private final String jobId;
    private final String videoId;
    private final String sourceUrl;
    private final Resolution targetResolution;
    private final Codec targetCodec;
    private volatile TranscodeJobStatus status;
    private volatile int progress;             // 0-100 percent

    public TranscodeJob(String videoId, String sourceUrl,
                        Resolution targetResolution, Codec targetCodec) {
        this.jobId = "job-" + UUID.randomUUID().toString().substring(0, 8);
        this.videoId = videoId;
        this.sourceUrl = sourceUrl;
        this.targetResolution = targetResolution;
        this.targetCodec = targetCodec;
        this.status = TranscodeJobStatus.QUEUED;
        this.progress = 0;
    }

    // --- Getters ---
    public String getJobId() { return jobId; }
    public String getVideoId() { return videoId; }
    public String getSourceUrl() { return sourceUrl; }
    public Resolution getTargetResolution() { return targetResolution; }
    public Codec getTargetCodec() { return targetCodec; }
    public TranscodeJobStatus getStatus() { return status; }
    public int getProgress() { return progress; }

    // --- State transitions ---
    public synchronized void start() {
        if (status != TranscodeJobStatus.QUEUED) {
            throw new TranscodingException(
                "Cannot start job " + jobId + " in status " + status);
        }
        this.status = TranscodeJobStatus.PROCESSING;
    }

    public synchronized void updateProgress(int percent) {
        if (status != TranscodeJobStatus.PROCESSING) {
            throw new TranscodingException(
                "Cannot update progress for job " + jobId + " in status " + status);
        }
        this.progress = Math.min(100, Math.max(0, percent));
    }

    public synchronized void complete() {
        this.status = TranscodeJobStatus.COMPLETED;
        this.progress = 100;
    }

    public synchronized void fail(String reason) {
        this.status = TranscodeJobStatus.FAILED;
    }

    @Override
    public String toString() {
        return String.format("TranscodeJob[id=%s, video=%s, %s→%s, status=%s, progress=%d%%]",
            jobId, videoId, targetResolution, targetCodec, status, progress);
    }
}
```

### 4.8 TranscodeJobStatus

```java
/**
 * Status of a transcoding job.
 *
 * Linear lifecycle: QUEUED → PROCESSING → COMPLETED
 *                   QUEUED → PROCESSING → FAILED
 *
 * Used by:
 *   - TranscodeJob: tracks current state
 *   - TranscodingService: monitors job completion
 */
public enum TranscodeJobStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
```

### 4.9 WatchHistory

```java
/**
 * A record of a user watching a video.
 *
 * Tracks how much of the video was watched (duration + completion %).
 * This feeds the recommendation engine: if a user watched 90% of a
 * cooking video, they probably like cooking content.
 *
 * Completion percent is critical for recommendation quality:
 *   - 0-10%: user bounced, low signal (maybe clicked by accident)
 *   - 10-50%: partial watch, mild interest
 *   - 50-90%: strong interest in this content type
 *   - 90-100%: completed, strong positive signal
 *
 * Used by:
 *   - AnalyticsService: records watch events, computes watch time
 *   - PersonalizedRecommendation: builds category affinity from history
 *   - WatchHistoryRepository: stores/retrieves per-user history
 */
public record WatchHistory(
    String userId,
    String videoId,
    Instant watchedAt,
    int watchDurationSeconds,
    double completionPercent
) {
    public WatchHistory {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required");
        }
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("videoId required");
        }
        if (watchDurationSeconds < 0) {
            throw new IllegalArgumentException("watchDurationSeconds must be >= 0");
        }
        if (completionPercent < 0.0 || completionPercent > 100.0) {
            throw new IllegalArgumentException(
                "completionPercent must be 0-100, got: " + completionPercent);
        }
    }
}
```

### 4.10 VideoMetadata

```java
/**
 * Metadata associated with a video (separate from core Video entity).
 *
 * Separation rationale: Video contains identity + pipeline state.
 * VideoMetadata contains discovery + engagement data that changes
 * frequently (viewCount increments on every watch).
 *
 * In production, metadata is stored in a separate table/collection
 * with different caching and indexing strategies.
 *
 * Used by:
 *   - SearchService: indexes by tags, category, language
 *   - AnalyticsService: increments viewCount, likeCount
 *   - TrendingRecommendation: sorts by viewCount in time window
 *   - PersonalizedRecommendation: uses category for affinity scoring
 */
public class VideoMetadata {

    private final String videoId;
    private final List<String> tags;
    private final String category;
    private final String language;
    private final Instant uploadDate;
    private final AtomicLong viewCount;
    private final AtomicLong likeCount;

    public VideoMetadata(String videoId, List<String> tags, String category,
                         String language, Instant uploadDate) {
        this.videoId = videoId;
        this.tags = tags != null ? List.copyOf(tags) : List.of();
        this.category = category != null ? category : "Uncategorized";
        this.language = language != null ? language : "en";
        this.uploadDate = uploadDate;
        this.viewCount = new AtomicLong(0);
        this.likeCount = new AtomicLong(0);
    }

    // --- Getters ---
    public String getVideoId() { return videoId; }
    public List<String> getTags() { return tags; }
    public String getCategory() { return category; }
    public String getLanguage() { return language; }
    public Instant getUploadDate() { return uploadDate; }
    public long getViewCount() { return viewCount.get(); }
    public long getLikeCount() { return likeCount.get(); }

    // --- Thread-safe increments (AtomicLong for concurrent view tracking) ---
    public long incrementViewCount() { return viewCount.incrementAndGet(); }
    public long incrementLikeCount() { return likeCount.incrementAndGet(); }
}
```

### 4.11 User

```java
/**
 * A user on the video platform (viewer or content creator).
 *
 * Users can upload videos (content creators) and watch videos (viewers).
 * subscriberCount drives recommendation (popular creators get boosted).
 * uploadedVideos tracks the creator's content library.
 *
 * Used by:
 *   - UploadService: validates uploader exists
 *   - RecommendationService: uses subscriberCount for popularity boost
 *   - UserRepository: stores/retrieves users
 */
public class User {

    private final String userId;
    private final String name;
    private final AtomicLong subscriberCount;
    private final List<String> uploadedVideoIds;  // list of videoIds

    public User(String userId, String name) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        this.userId = userId;
        this.name = name;
        this.subscriberCount = new AtomicLong(0);
        this.uploadedVideoIds = new CopyOnWriteArrayList<>();
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public long getSubscriberCount() { return subscriberCount.get(); }
    public List<String> getUploadedVideoIds() { return Collections.unmodifiableList(uploadedVideoIds); }

    public long incrementSubscribers() { return subscriberCount.incrementAndGet(); }
    public long decrementSubscribers() { return subscriberCount.decrementAndGet(); }
    public void addUploadedVideo(String videoId) { uploadedVideoIds.add(videoId); }

    @Override
    public String toString() {
        return String.format("User[id=%s, name=%s, subscribers=%d, videos=%d]",
            userId, name, subscriberCount.get(), uploadedVideoIds.size());
    }
}
```

---

## 5. Interface Contracts

### 5.1 TranscodingStrategy

```java
/**
 * Strategy interface for transcoding videos to different resolutions.
 *
 * WHY AN INTERFACE:
 *   - ParallelTranscodingStrategy uses ExecutorService for concurrent transcoding
 *   - SequentialTranscodingStrategy processes one resolution at a time
 *   - In production, you might add DistributedTranscodingStrategy (across worker nodes)
 *   - Strategy pattern: TranscodingService calls strategy.transcodeAll() without
 *     knowing or caring whether it's parallel, sequential, or distributed
 *
 * CALL CHAIN:
 *   VideoService.uploadComplete(videoId)
 *     → TranscodingService.transcodeVideo(video, resolutions, codec)
 *       → TranscodingStrategy.transcodeAll(video, resolutions, codec)
 *         → [creates TranscodeJob per resolution, executes per strategy]
 */
public interface TranscodingStrategy {

    /**
     * Transcode a single video to one target resolution + codec.
     *
     * @param video     the source video (must be in UPLOADED or TRANSCODING status)
     * @param resolution target resolution (e.g., P720)
     * @param codec      target codec (e.g., H264)
     * @return TranscodeJob with status COMPLETED or FAILED
     * @throws TranscodingException if transcoding fails
     */
    TranscodeJob transcode(Video video, Resolution resolution, Codec codec);

    /**
     * Transcode a video to ALL target resolutions.
     * Implementation decides parallel vs. sequential execution.
     *
     * @param video       the source video
     * @param resolutions all target resolutions
     * @param codec       target codec
     * @return list of TranscodeJobs (one per resolution)
     */
    List<TranscodeJob> transcodeAll(Video video, List<Resolution> resolutions, Codec codec);

    /** Strategy name for logging/metrics. */
    String getStrategyName();
}
```

### 5.2 ABRStrategy

```java
/**
 * Strategy interface for Adaptive Bitrate Resolution selection.
 *
 * The video player periodically measures network bandwidth and buffer level,
 * then asks the ABR strategy: "What resolution should I download next?"
 *
 * WHY AN INTERFACE:
 *   - ThroughputBasedABR: aggressive, maximizes quality, used by YouTube
 *   - BufferBasedABR: conservative, avoids rebuffering, used by Netflix
 *   - In production, you might add HybridABR (combines both signals)
 *   - A/B testing: 50% of users get throughput-based, 50% get buffer-based
 *
 * CALL CHAIN:
 *   Player tick (every 2-4 seconds):
 *     StreamingService.getNextSegment(videoId, bandwidth, bufferLevel)
 *       → ABRStrategy.selectResolution(bandwidth, bufferLevel)
 *       → StreamManifest.getVariantForResolution(selectedResolution)
 *       → CDNService.fetchSegment(segmentUrl)
 */
public interface ABRStrategy {

    /**
     * Select the best resolution given current network conditions.
     *
     * @param bandwidthKbps  measured download bandwidth in Kbps
     * @param bufferLevelSeconds  seconds of video buffered ahead
     * @return the resolution to use for the next segment download
     */
    Resolution selectResolution(long bandwidthKbps, double bufferLevelSeconds);

    /** Strategy name for logging/metrics. */
    String getStrategyName();
}
```

### 5.3 RecommendationStrategy

```java
/**
 * Strategy interface for video recommendation.
 *
 * WHY AN INTERFACE:
 *   - TrendingRecommendation: simple, works for anonymous/new users (cold start)
 *   - PersonalizedRecommendation: requires watch history, better for logged-in users
 *   - In production: CollaborativeFilteringStrategy ("users like you watched...")
 *   - Strategy pattern: RecommendationService.recommend() delegates to strategy
 *
 * CALL CHAIN:
 *   VideoController.getRecommendations(userId, limit)
 *     → VideoService.getRecommendations(userId, limit)
 *       → RecommendationService.recommend(userId, limit)
 *         → RecommendationStrategy.recommend(userId, limit)
 */
public interface RecommendationStrategy {

    /**
     * Recommend videos for a user.
     *
     * @param userId  the user to recommend for (may be null for anonymous)
     * @param limit   maximum number of recommendations
     * @return ordered list of recommended videos (best first)
     */
    List<Video> recommend(String userId, int limit);

    /** Strategy name for logging/metrics. */
    String getStrategyName();
}
```

### 5.4 VideoStore

```java
/**
 * Interface for video object storage (simulates S3/GCS).
 *
 * Stores raw uploaded chunks, transcoded segments, and manifests.
 * Keyed by a hierarchical path: videos/{videoId}/{resolution}/{segment}.
 *
 * WHY AN INTERFACE:
 *   - InMemoryVideoStore: used for testing and demo
 *   - In production: S3VideoStore, GCSVideoStore, etc.
 *   - CDNService wraps VideoStore for caching layer
 *
 * CALL CHAIN:
 *   UploadService.uploadChunk(videoId, chunkIndex, data)
 *     → VideoStore.storeChunk(chunk)
 *   StreamingService.getSegment(videoId, resolution, segmentIndex)
 *     → CDNService.fetchSegment(key)
 *       → [cache miss] → VideoStore.getChunk(key)
 */
public interface VideoStore {

    /** Store a video chunk (upload or transcoded segment). */
    void storeChunk(VideoChunk chunk);

    /** Retrieve a chunk by its storage key. */
    Optional<VideoChunk> getChunk(String storageKey);

    /** Store a stream manifest for a video. */
    void storeManifest(StreamManifest manifest);

    /** Retrieve the manifest for a video. */
    Optional<StreamManifest> getManifest(String videoId);

    /** Get all chunks for a video at a given resolution, ordered by index. */
    List<VideoChunk> getChunksForResolution(String videoId, Resolution resolution);

    /** Delete all data for a video (chunks + manifest). */
    void deleteVideo(String videoId);
}
```

### 5.5 Repository Interfaces

```java
/**
 * Repository for Video entities.
 *
 * CALL CHAIN:
 *   VideoService.getVideo(videoId) → VideoRepository.findById(videoId)
 *   UploadService.createVideo(video) → VideoRepository.save(video)
 *   SearchService.search(query) → VideoRepository.findAll() + filter
 */
public interface VideoRepository {
    void save(Video video);
    Optional<Video> findById(String videoId);
    List<Video> findByUploaderId(String uploaderId);
    List<Video> findAll();
    void delete(String videoId);
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
 * Repository for WatchHistory records.
 *
 * CALL CHAIN:
 *   AnalyticsService.recordWatch(userId, videoId, duration, completion)
 *     → WatchHistoryRepository.save(watchHistory)
 *   PersonalizedRecommendation.recommend(userId, limit)
 *     → WatchHistoryRepository.findByUserId(userId)
 */
public interface WatchHistoryRepository {
    void save(WatchHistory history);
    List<WatchHistory> findByUserId(String userId);
    List<WatchHistory> findByVideoId(String videoId);
    List<WatchHistory> findAll();
}
```

---

## 6. Strategy Implementations

### 6.1 ParallelTranscodingStrategy

```java
/**
 * Transcodes a video to multiple resolutions IN PARALLEL using ExecutorService.
 *
 * This is the production strategy. When a 1080p video is uploaded, we need
 * P240, P360, P480, P720, P1080 variants. Running them sequentially wastes
 * time. Parallel transcoding reduces wall-clock time from SUM to MAX.
 *
 * Thread pool sizing:
 *   - One thread per resolution (typically 5-6 threads)
 *   - In production: distributed across worker nodes (not in-process)
 *   - For LLD demo: in-process ExecutorService is sufficient
 *
 * WIRING (AppConfig):
 *   ExecutorService executor = Executors.newFixedThreadPool(6);
 *   TranscodingStrategy strategy = new ParallelTranscodingStrategy(executor, videoStore);
 *   TranscodingService service = new TranscodingService(strategy, videoRepo);
 *
 * CALL CHAIN:
 *   TranscodingService.transcodeVideo(video, resolutions, codec)
 *     → ParallelTranscodingStrategy.transcodeAll(video, resolutions, codec)
 *       → executor.submit(() → transcode(video, P240, H264))  ──┐
 *       → executor.submit(() → transcode(video, P360, H264))    │ all submitted at once
 *       → executor.submit(() → transcode(video, P720, H264))    │
 *       → executor.submit(() → transcode(video, P1080, H264)) ──┘
 *       → CompletableFuture.allOf(futures).join()   // wait for ALL to complete
 *       → return list of completed TranscodeJobs
 */
public class ParallelTranscodingStrategy implements TranscodingStrategy {

    private final ExecutorService executorService;
    private final VideoStore videoStore;

    public ParallelTranscodingStrategy(ExecutorService executorService,
                                        VideoStore videoStore) {
        this.executorService = executorService;
        this.videoStore = videoStore;
    }

    @Override
    public TranscodeJob transcode(Video video, Resolution resolution, Codec codec) {
        // --- Create the job ---
        TranscodeJob job = new TranscodeJob(
            video.getVideoId(),
            "videos/" + video.getVideoId() + "/source",
            resolution,
            codec
        );

        // --- Simulate transcoding (in production: FFmpeg/MediaConvert) ---
        job.start();
        int segments = estimateSegmentCount(video, resolution);
        for (int i = 0; i < segments; i++) {
            // Simulate work: create transcoded segment
            VideoChunk segment = new VideoChunk(
                "seg-" + UUID.randomUUID().toString().substring(0, 8),
                video.getVideoId(),
                i,
                estimateSegmentSize(resolution, codec),
                resolution,
                String.format("videos/%s/%s/%04d.ts",
                    video.getVideoId(), resolution.name(), i)
            );
            videoStore.storeChunk(segment);
            job.updateProgress((i + 1) * 100 / segments);
        }
        job.complete();

        // --- Add resolution to video (thread-safe ConcurrentHashMap.KeySet) ---
        video.addResolution(resolution);
        return job;
    }

    @Override
    public List<TranscodeJob> transcodeAll(Video video, List<Resolution> resolutions,
                                            Codec codec) {
        // --- Submit all resolutions in parallel ---
        List<CompletableFuture<TranscodeJob>> futures = resolutions.stream()
            .map(resolution -> CompletableFuture.supplyAsync(
                () -> transcode(video, resolution, codec),
                executorService
            ))
            .toList();

        // --- Wait for ALL to complete, collect results ---
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    @Override
    public String getStrategyName() { return "PARALLEL"; }

    private int estimateSegmentCount(Video video, Resolution resolution) {
        long durationSeconds = video.getDuration() != null
            ? video.getDuration().getSeconds() : 300;  // default 5 min
        return (int) (durationSeconds / 4);             // 4-second segments
    }

    private long estimateSegmentSize(Resolution resolution, Codec codec) {
        // 4-second segment at given bitrate
        return (long) (resolution.getBitrateKbps() * codec.getCompressionRatio() * 4 / 8 * 1024);
    }
}
```

### 6.2 SequentialTranscodingStrategy

```java
/**
 * Transcodes a video to multiple resolutions ONE AT A TIME.
 *
 * This exists as a COMPARISON BASELINE to demonstrate why parallel
 * transcoding matters. In interviews, showing you understand the
 * performance difference proves you think about scalability.
 *
 * When to use sequential (rare):
 *   - Resource-constrained environment (single CPU)
 *   - Debugging transcoding issues (easier to reproduce)
 *   - Priority ordering: transcode P720 first so video is watchable sooner
 *
 * CALL CHAIN:
 *   TranscodingService.transcodeVideo(video, resolutions, codec)
 *     → SequentialTranscodingStrategy.transcodeAll(video, resolutions, codec)
 *       → transcode(video, P240, H264)  // wait...
 *       → transcode(video, P360, H264)  // wait...
 *       → transcode(video, P720, H264)  // wait...
 *       → transcode(video, P1080, H264) // wait... (SLOW!)
 */
public class SequentialTranscodingStrategy implements TranscodingStrategy {

    private final VideoStore videoStore;

    public SequentialTranscodingStrategy(VideoStore videoStore) {
        this.videoStore = videoStore;
    }

    @Override
    public TranscodeJob transcode(Video video, Resolution resolution, Codec codec) {
        // Same as ParallelTranscodingStrategy.transcode() -- identical single-job logic
        TranscodeJob job = new TranscodeJob(
            video.getVideoId(),
            "videos/" + video.getVideoId() + "/source",
            resolution, codec
        );
        job.start();
        int segments = (int) (video.getDuration() != null
            ? video.getDuration().getSeconds() / 4 : 75);
        for (int i = 0; i < segments; i++) {
            VideoChunk segment = new VideoChunk(
                "seg-" + UUID.randomUUID().toString().substring(0, 8),
                video.getVideoId(), i,
                (long) (resolution.getBitrateKbps() * codec.getCompressionRatio() * 4 / 8 * 1024),
                resolution,
                String.format("videos/%s/%s/%04d.ts",
                    video.getVideoId(), resolution.name(), i)
            );
            videoStore.storeChunk(segment);
            job.updateProgress((i + 1) * 100 / segments);
        }
        job.complete();
        video.addResolution(resolution);
        return job;
    }

    @Override
    public List<TranscodeJob> transcodeAll(Video video, List<Resolution> resolutions,
                                            Codec codec) {
        // --- Sequential: process one at a time, blocking ---
        List<TranscodeJob> jobs = new ArrayList<>();
        for (Resolution resolution : resolutions) {
            jobs.add(transcode(video, resolution, codec));
        }
        return jobs;
    }

    @Override
    public String getStrategyName() { return "SEQUENTIAL"; }
}
```

### 6.3 ThroughputBasedABR

```java
/**
 * ABR strategy that selects the highest resolution fitting current bandwidth.
 *
 * Algorithm:
 *   1. Apply safety margin (default 70%) to measured bandwidth
 *      - Network measurements are noisy; using 100% risks rebuffering
 *   2. Pick the highest resolution whose bitrate <= effective bandwidth
 *   3. Fall back to P240 if nothing fits (graceful degradation)
 *
 * This is YouTube's approach: aggressive quality maximization.
 * Pros: best visual quality when bandwidth is stable
 * Cons: more rebuffering events when bandwidth fluctuates
 *
 * WIRING (AppConfig):
 *   ABRStrategy abr = new ThroughputBasedABR(0.7);  // 70% safety margin
 *   StreamingService streaming = new StreamingService(abr, cdnService, videoStore);
 *
 * CALL CHAIN:
 *   StreamingService.getNextSegment(videoId, bandwidthKbps, bufferLevel)
 *     → ThroughputBasedABR.selectResolution(5000, 15.0)
 *       → effectiveBW = 5000 * 0.7 = 3500 Kbps
 *       → P720 (2500 Kbps) fits, P1080 (4500 Kbps) doesn't
 *       → return P720
 */
public class ThroughputBasedABR implements ABRStrategy {

    private final double safetyMargin;

    /**
     * @param safetyMargin fraction of measured bandwidth to use (0.0-1.0)
     *                     Default: 0.7 (use 70% of measured bandwidth)
     */
    public ThroughputBasedABR(double safetyMargin) {
        if (safetyMargin <= 0 || safetyMargin > 1.0) {
            throw new IllegalArgumentException(
                "safetyMargin must be (0, 1.0], got: " + safetyMargin);
        }
        this.safetyMargin = safetyMargin;
    }

    @Override
    public Resolution selectResolution(long bandwidthKbps, double bufferLevelSeconds) {
        // --- Apply safety margin to avoid overshooting ---
        long effectiveBandwidth = (long) (bandwidthKbps * safetyMargin);

        // --- Pick highest resolution that fits ---
        return Resolution.highestFitting(effectiveBandwidth);
    }

    @Override
    public String getStrategyName() { return "THROUGHPUT_BASED"; }
}
```

### 6.4 BufferBasedABR

```java
/**
 * ABR strategy that selects resolution based on buffer fullness.
 *
 * Algorithm:
 *   - Buffer < lowThreshold (5s):  pick LOWEST resolution (panic mode, avoid stall)
 *   - Buffer > highThreshold (30s): pick HIGHEST resolution (buffer is healthy)
 *   - Between: interpolate linearly between lowest and highest
 *
 * This is Netflix's approach (BBA -- Buffer-Based Approach).
 * Pros: very few rebuffering events, smooth playback
 * Cons: may not reach highest quality even on fast connections
 *
 * WIRING (AppConfig):
 *   ABRStrategy abr = new BufferBasedABR(5.0, 30.0);
 *   StreamingService streaming = new StreamingService(abr, cdnService, videoStore);
 *
 * CALL CHAIN:
 *   StreamingService.getNextSegment(videoId, bandwidthKbps, bufferLevel)
 *     → BufferBasedABR.selectResolution(5000, 3.0)
 *       → buffer 3.0 < lowThreshold 5.0 → PANIC → return P240
 *
 *   StreamingService.getNextSegment(videoId, bandwidthKbps, bufferLevel)
 *     → BufferBasedABR.selectResolution(5000, 35.0)
 *       → buffer 35.0 > highThreshold 30.0 → AGGRESSIVE → return P4K
 */
public class BufferBasedABR implements ABRStrategy {

    private final double lowThresholdSeconds;
    private final double highThresholdSeconds;

    public BufferBasedABR(double lowThresholdSeconds, double highThresholdSeconds) {
        if (lowThresholdSeconds >= highThresholdSeconds) {
            throw new IllegalArgumentException(
                "lowThreshold must be < highThreshold");
        }
        this.lowThresholdSeconds = lowThresholdSeconds;
        this.highThresholdSeconds = highThresholdSeconds;
    }

    @Override
    public Resolution selectResolution(long bandwidthKbps, double bufferLevelSeconds) {
        Resolution[] allResolutions = Resolution.values();
        int maxIndex = allResolutions.length - 1;

        if (bufferLevelSeconds <= lowThresholdSeconds) {
            // --- PANIC: buffer nearly empty, pick lowest to refill fast ---
            return allResolutions[0];  // P240
        }
        if (bufferLevelSeconds >= highThresholdSeconds) {
            // --- COMFORTABLE: buffer is full, pick highest quality ---
            return allResolutions[maxIndex];  // P4K
        }

        // --- INTERPOLATE: linearly map buffer level to resolution index ---
        double fraction = (bufferLevelSeconds - lowThresholdSeconds)
                        / (highThresholdSeconds - lowThresholdSeconds);
        int index = (int) (fraction * maxIndex);
        return allResolutions[Math.min(index, maxIndex)];
    }

    @Override
    public String getStrategyName() { return "BUFFER_BASED"; }
}
```

### 6.5 TrendingRecommendation

```java
/**
 * Recommends the most-viewed videos in the last N hours.
 *
 * This is the cold-start strategy: works for anonymous users, new users,
 * or anyone without sufficient watch history. Simple but effective --
 * popular content is popular for a reason.
 *
 * Algorithm:
 *   1. Get all videos uploaded in the time window (default 24h)
 *   2. Sort by viewCount descending
 *   3. Return top N
 *
 * WIRING (AppConfig):
 *   RecommendationStrategy strategy = new TrendingRecommendation(
 *       videoRepo, metadataStore, 24);
 *   RecommendationService recService = new RecommendationService(strategy);
 */
public class TrendingRecommendation implements RecommendationStrategy {

    private final VideoRepository videoRepository;
    private final Map<String, VideoMetadata> metadataStore;
    private final int timeWindowHours;

    public TrendingRecommendation(VideoRepository videoRepository,
                                   Map<String, VideoMetadata> metadataStore,
                                   int timeWindowHours) {
        this.videoRepository = videoRepository;
        this.metadataStore = metadataStore;
        this.timeWindowHours = timeWindowHours;
    }

    @Override
    public List<Video> recommend(String userId, int limit) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(timeWindowHours));

        return videoRepository.findAll().stream()
            .filter(v -> v.getStatus() == VideoStatus.READY)
            .filter(v -> {
                VideoMetadata meta = metadataStore.get(v.getVideoId());
                return meta != null && meta.getUploadDate().isAfter(cutoff);
            })
            .sorted((a, b) -> {
                long viewsA = metadataStore.getOrDefault(a.getVideoId(),
                    new VideoMetadata(a.getVideoId(), List.of(), "", "", Instant.now()))
                    .getViewCount();
                long viewsB = metadataStore.getOrDefault(b.getVideoId(),
                    new VideoMetadata(b.getVideoId(), List.of(), "", "", Instant.now()))
                    .getViewCount();
                return Long.compare(viewsB, viewsA);  // descending
            })
            .limit(limit)
            .toList();
    }

    @Override
    public String getStrategyName() { return "TRENDING"; }
}
```

### 6.6 PersonalizedRecommendation

```java
/**
 * Recommends videos based on user's watch history and category affinity.
 *
 * Algorithm:
 *   1. Get user's watch history
 *   2. Compute category affinity: fraction of watch time per category
 *      e.g., user watched 60% cooking, 30% gaming, 10% music
 *   3. For each unwatched video with status READY:
 *      score = categoryAffinity * recencyBoost * popularityBoost
 *   4. Sort by score descending, return top N
 *
 * Category affinity scoring:
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │  User "alice" watch history:                                     │
 *   │    "Pasta Recipe"    (Cooking, 15 min, 95% completion)          │
 *   │    "Sushi Tutorial"  (Cooking, 20 min, 80% completion)          │
 *   │    "Minecraft #42"   (Gaming, 45 min, 60% completion)           │
 *   │    "Jazz Playlist"   (Music, 5 min, 20% completion)             │
 *   │                                                                  │
 *   │  Category affinity (weighted by duration * completion):          │
 *   │    Cooking: (15*0.95 + 20*0.80) / total = 30.25 / 55.25 = 0.55│
 *   │    Gaming:  (45*0.60) / total = 27.00 / 55.25 = 0.49          │
 *   │    Music:   (5*0.20) / total = 1.00 / 55.25 = 0.02            │
 *   │                                                                  │
 *   │  Next recommendation: prioritize Cooking > Gaming >> Music      │
 *   └──────────────────────────────────────────────────────────────────┘
 *
 * WIRING (AppConfig):
 *   RecommendationStrategy strategy = new PersonalizedRecommendation(
 *       videoRepo, watchHistoryRepo, metadataStore);
 */
public class PersonalizedRecommendation implements RecommendationStrategy {

    private final VideoRepository videoRepository;
    private final WatchHistoryRepository watchHistoryRepository;
    private final Map<String, VideoMetadata> metadataStore;

    public PersonalizedRecommendation(VideoRepository videoRepository,
                                       WatchHistoryRepository watchHistoryRepository,
                                       Map<String, VideoMetadata> metadataStore) {
        this.videoRepository = videoRepository;
        this.watchHistoryRepository = watchHistoryRepository;
        this.metadataStore = metadataStore;
    }

    @Override
    public List<Video> recommend(String userId, int limit) {
        // --- Step 1: Build category affinity from watch history ---
        List<WatchHistory> history = watchHistoryRepository.findByUserId(userId);
        Map<String, Double> categoryAffinity = computeCategoryAffinity(history);

        // --- Step 2: Get set of already-watched videoIds ---
        Set<String> watchedIds = history.stream()
            .map(WatchHistory::videoId)
            .collect(Collectors.toSet());

        // --- Step 3: Score each unwatched video ---
        return videoRepository.findAll().stream()
            .filter(v -> v.getStatus() == VideoStatus.READY)
            .filter(v -> !watchedIds.contains(v.getVideoId()))
            .map(v -> {
                VideoMetadata meta = metadataStore.get(v.getVideoId());
                double score = scoreVideo(v, meta, categoryAffinity);
                return Map.entry(v, score);
            })
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * Compute category affinity: weighted fraction of watch time per category.
     * Weight = watchDuration * completionPercent / 100
     */
    private Map<String, Double> computeCategoryAffinity(List<WatchHistory> history) {
        Map<String, Double> categoryWeight = new HashMap<>();
        double totalWeight = 0;

        for (WatchHistory wh : history) {
            VideoMetadata meta = metadataStore.get(wh.videoId());
            if (meta == null) continue;
            double weight = wh.watchDurationSeconds() * wh.completionPercent() / 100.0;
            categoryWeight.merge(meta.getCategory(), weight, Double::sum);
            totalWeight += weight;
        }

        // Normalize to [0, 1]
        double total = totalWeight;
        if (total > 0) {
            categoryWeight.replaceAll((cat, w) -> w / total);
        }
        return categoryWeight;
    }

    /**
     * Score a candidate video for recommendation.
     * score = categoryAffinity * recencyBoost * popularityBoost
     */
    private double scoreVideo(Video video, VideoMetadata meta,
                               Map<String, Double> categoryAffinity) {
        if (meta == null) return 0.0;

        // Category affinity: how much does user like this category?
        double affinity = categoryAffinity.getOrDefault(meta.getCategory(), 0.01);

        // Recency boost: newer videos score higher (exponential decay)
        long hoursAge = Duration.between(meta.getUploadDate(), Instant.now()).toHours();
        double recency = 1.0 / (1.0 + hoursAge * 0.01);

        // Popularity boost: log of view count (diminishing returns)
        double popularity = Math.log1p(meta.getViewCount()) / 10.0;

        return affinity * recency * (1.0 + popularity);
    }

    @Override
    public String getStrategyName() { return "PERSONALIZED"; }
}
```

---

## 7. Service Layer Design

### 7.1 VideoService (Facade)

```java
/**
 * FACADE: Single entry point for all video operations.
 *
 * Orchestrates upload → transcode → manifest → stream → recommend → analytics.
 * Controllers call VideoService; VideoService delegates to specialized services.
 *
 * WHY FACADE:
 *   - VideoController doesn't need to know about 8 different services
 *   - Complex workflows (upload + transcode + manifest generation) are
 *     orchestrated in one place
 *   - Changing the internal service wiring doesn't affect the controller
 *
 * WIRING (AppConfig):
 *   VideoService videoService = new VideoService(
 *       uploadService, transcodingService, streamingService,
 *       cdnService, recommendationService, searchService,
 *       analyticsService, videoRepo
 *   );
 *
 * CALL CHAINS:
 *   upload():      Controller → VideoService → UploadService → VideoStore
 *   stream():      Controller → VideoService → StreamingService → CDNService → VideoStore
 *   recommend():   Controller → VideoService → RecommendationService → Strategy
 *   search():      Controller → VideoService → SearchService → VideoRepository
 *   recordWatch(): Controller → VideoService → AnalyticsService → WatchHistoryRepository
 */
public class VideoService {

    private final UploadService uploadService;
    private final TranscodingService transcodingService;
    private final StreamingService streamingService;
    private final CDNService cdnService;
    private final RecommendationService recommendationService;
    private final SearchService searchService;
    private final AnalyticsService analyticsService;
    private final VideoRepository videoRepository;

    public VideoService(UploadService uploadService,
                        TranscodingService transcodingService,
                        StreamingService streamingService,
                        CDNService cdnService,
                        RecommendationService recommendationService,
                        SearchService searchService,
                        AnalyticsService analyticsService,
                        VideoRepository videoRepository) {
        this.uploadService = uploadService;
        this.transcodingService = transcodingService;
        this.streamingService = streamingService;
        this.cdnService = cdnService;
        this.recommendationService = recommendationService;
        this.searchService = searchService;
        this.analyticsService = analyticsService;
        this.videoRepository = videoRepository;
    }

    /**
     * Upload a video: create entity → receive chunks → assemble → trigger transcode.
     *
     * Flow: UPLOADING → UPLOADED → TRANSCODING → READY
     */
    public Video uploadVideo(String title, String uploaderId, String description,
                              Duration duration, List<byte[]> chunks) {
        // --- 1. Create video entity ---
        Video video = new Video.Builder(title, uploaderId)
            .description(description)
            .duration(duration)
            .build();
        videoRepository.save(video);

        // --- 2. Upload chunks ---
        uploadService.uploadChunked(video, chunks);

        // --- 3. Trigger transcoding (async in production) ---
        List<Resolution> targets = List.of(
            Resolution.P240, Resolution.P360, Resolution.P480,
            Resolution.P720, Resolution.P1080
        );
        transcodingService.transcodeVideo(video, targets, Codec.H264);

        // --- 4. Generate manifest ---
        streamingService.generateManifest(video);

        return video;
    }

    /**
     * Stream a video: get manifest → ABR selects resolution → serve segment via CDN.
     */
    public Optional<VideoChunk> streamSegment(String videoId, int segmentIndex,
                                               long bandwidthKbps,
                                               double bufferLevelSeconds) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(
                "Video not found: " + videoId));

        if (video.getStatus() != VideoStatus.READY) {
            throw new VideoStreamingException(
                "Video not ready for streaming: " + video.getStatus());
        }

        return streamingService.getNextSegment(
            videoId, segmentIndex, bandwidthKbps, bufferLevelSeconds);
    }

    /** Get recommendations for a user. */
    public List<Video> getRecommendations(String userId, int limit) {
        return recommendationService.recommend(userId, limit);
    }

    /** Search videos by query. */
    public List<Video> search(String query) {
        return searchService.search(query);
    }

    /** Record a watch event for analytics. */
    public void recordWatch(String userId, String videoId,
                             int durationSeconds, double completionPercent) {
        analyticsService.recordWatch(userId, videoId, durationSeconds, completionPercent);
    }

    /** Delete a video (soft delete). */
    public void deleteVideo(String videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));
        video.transitionTo(VideoStatus.DELETED);
    }
}
```

### 7.2 UploadService

```java
/**
 * Handles chunked, resumable video uploads.
 *
 * Chunked upload protocol:
 *   1. Client splits file into fixed-size chunks (e.g., 5MB)
 *   2. Client uploads chunks one at a time (or in parallel)
 *   3. Server acknowledges each chunk
 *   4. If connection drops, client resumes from last acknowledged chunk
 *   5. After all chunks received, server assembles and transitions to UPLOADED
 *
 * WHY CHUNKED UPLOAD:
 *   - Large videos (>1GB) can't reliably upload in a single request
 *   - Resumability: don't re-upload 900MB because the last 100MB failed
 *   - Progress tracking: UI shows "47% uploaded" per-chunk
 *   - Parallel chunk upload: speed up with multiple connections
 *
 * WIRING (AppConfig):
 *   UploadService uploadService = new UploadService(videoStore);
 */
public class UploadService {

    private final VideoStore videoStore;
    // Tracks upload progress: videoId → set of received chunk indices
    private final ConcurrentHashMap<String, Set<Integer>> uploadProgress;

    public UploadService(VideoStore videoStore) {
        this.videoStore = videoStore;
        this.uploadProgress = new ConcurrentHashMap<>();
    }

    /**
     * Upload a video in chunks.
     *
     * CALL CHAIN:
     *   VideoService.uploadVideo()
     *     → UploadService.uploadChunked(video, chunks)
     *       → for each chunk: storeChunk() → ack
     *       → all chunks received → video.transitionTo(UPLOADED)
     */
    public void uploadChunked(Video video, List<byte[]> chunks) {
        String videoId = video.getVideoId();
        uploadProgress.put(videoId, ConcurrentHashMap.newKeySet());

        for (int i = 0; i < chunks.size(); i++) {
            uploadChunk(video, i, chunks.get(i));
        }

        // --- All chunks received, transition to UPLOADED ---
        video.transitionTo(VideoStatus.UPLOADED);
    }

    /**
     * Upload a single chunk. Idempotent: re-uploading the same chunk is safe.
     * This enables resumable uploads -- client retries failed chunks.
     */
    public void uploadChunk(Video video, int chunkIndex, byte[] data) {
        String videoId = video.getVideoId();

        // --- Idempotency check: skip if already received ---
        Set<Integer> received = uploadProgress.get(videoId);
        if (received != null && received.contains(chunkIndex)) {
            return;  // already uploaded, skip (resumable upload safety)
        }

        // --- Store the chunk ---
        VideoChunk chunk = new VideoChunk(
            "chunk-" + UUID.randomUUID().toString().substring(0, 8),
            videoId,
            chunkIndex,
            data.length,
            null,      // source resolution (not yet transcoded)
            String.format("videos/%s/source/%04d", videoId, chunkIndex)
        );
        videoStore.storeChunk(chunk);

        // --- Track progress ---
        if (received != null) {
            received.add(chunkIndex);
        }
    }

    /** Get upload progress as a fraction (0.0 to 1.0). */
    public double getProgress(String videoId, int totalChunks) {
        Set<Integer> received = uploadProgress.get(videoId);
        if (received == null || totalChunks == 0) return 0.0;
        return (double) received.size() / totalChunks;
    }

    /** Check if a specific chunk has been received (for resume). */
    public boolean isChunkReceived(String videoId, int chunkIndex) {
        Set<Integer> received = uploadProgress.get(videoId);
        return received != null && received.contains(chunkIndex);
    }
}
```

### 7.3 TranscodingService

```java
/**
 * Manages the transcoding pipeline: receives UPLOADED videos, creates
 * transcode jobs, delegates to TranscodingStrategy, tracks completion.
 *
 * This service does NOT do the actual transcoding -- it orchestrates.
 * The strategy handles execution (parallel vs. sequential).
 *
 * WIRING (AppConfig):
 *   TranscodingService transcodingService = new TranscodingService(
 *       transcodingStrategy, videoRepository);
 *
 * CALL CHAIN:
 *   VideoService.uploadVideo()
 *     → TranscodingService.transcodeVideo(video, resolutions, codec)
 *       → video.transitionTo(TRANSCODING)
 *       → strategy.transcodeAll(video, resolutions, codec)
 *       → [strategy creates and executes TranscodeJobs]
 *       → all jobs complete → video.transitionTo(READY)
 */
public class TranscodingService {

    private final TranscodingStrategy strategy;
    private final VideoRepository videoRepository;
    // Track all jobs for monitoring/querying
    private final ConcurrentHashMap<String, List<TranscodeJob>> jobsByVideoId;

    public TranscodingService(TranscodingStrategy strategy,
                               VideoRepository videoRepository) {
        this.strategy = strategy;
        this.videoRepository = videoRepository;
        this.jobsByVideoId = new ConcurrentHashMap<>();
    }

    /**
     * Transcode a video to all target resolutions.
     * Transitions: UPLOADED → TRANSCODING → READY (or FAILED).
     */
    public List<TranscodeJob> transcodeVideo(Video video, List<Resolution> resolutions,
                                              Codec codec) {
        // --- Validate state ---
        if (video.getStatus() != VideoStatus.UPLOADED) {
            throw new TranscodingException(
                "Video must be UPLOADED to transcode, got: " + video.getStatus());
        }

        // --- Transition to TRANSCODING ---
        video.transitionTo(VideoStatus.TRANSCODING);

        try {
            // --- Delegate to strategy (parallel or sequential) ---
            List<TranscodeJob> jobs = strategy.transcodeAll(video, resolutions, codec);
            jobsByVideoId.put(video.getVideoId(), jobs);

            // --- Check if all jobs succeeded ---
            boolean allCompleted = jobs.stream()
                .allMatch(j -> j.getStatus() == TranscodeJobStatus.COMPLETED);

            if (allCompleted) {
                video.transitionTo(VideoStatus.READY);
            } else {
                video.transitionTo(VideoStatus.FAILED);
            }

            return jobs;
        } catch (Exception e) {
            video.transitionTo(VideoStatus.FAILED);
            throw new TranscodingException("Transcoding failed for " + video.getVideoId(), e);
        }
    }

    /** Get all transcode jobs for a video. */
    public List<TranscodeJob> getJobs(String videoId) {
        return jobsByVideoId.getOrDefault(videoId, List.of());
    }
}
```

### 7.4 StreamingService

```java
/**
 * Generates stream manifests and serves video segments via ABR + CDN.
 *
 * Core responsibilities:
 *   1. Generate HLS/DASH manifest for a transcoded video
 *   2. Use ABR strategy to select resolution for next segment
 *   3. Fetch segment via CDN (cache hit) or origin (cache miss)
 *
 * WIRING (AppConfig):
 *   StreamingService streamingService = new StreamingService(
 *       abrStrategy, cdnService, videoStore);
 *
 * CALL CHAIN (manifest generation):
 *   VideoService.uploadVideo() [after transcoding]
 *     → StreamingService.generateManifest(video)
 *       → build StreamVariant per resolution
 *       → create StreamManifest
 *       → videoStore.storeManifest(manifest)
 *
 * CALL CHAIN (segment serving):
 *   VideoService.streamSegment(videoId, segmentIndex, bandwidth, buffer)
 *     → StreamingService.getNextSegment(...)
 *       → abrStrategy.selectResolution(bandwidth, buffer) → Resolution
 *       → cdnService.fetchSegment(videoId, resolution, segmentIndex)
 *         → [cache hit → return immediately]
 *         → [cache miss → fetch from videoStore, cache at CDN, return]
 */
public class StreamingService {

    private final ABRStrategy abrStrategy;
    private final CDNService cdnService;
    private final VideoStore videoStore;

    public StreamingService(ABRStrategy abrStrategy, CDNService cdnService,
                             VideoStore videoStore) {
        this.abrStrategy = abrStrategy;
        this.cdnService = cdnService;
        this.videoStore = videoStore;
    }

    /**
     * Generate a stream manifest listing all available quality variants.
     * Called once after transcoding completes.
     */
    public StreamManifest generateManifest(Video video) {
        List<StreamVariant> variants = new ArrayList<>();

        for (Resolution resolution : video.getResolutions()) {
            List<VideoChunk> segments = videoStore.getChunksForResolution(
                video.getVideoId(), resolution);

            List<String> segmentUrls = segments.stream()
                .map(VideoChunk::url)
                .toList();

            if (!segmentUrls.isEmpty()) {
                variants.add(new StreamVariant(
                    resolution, Codec.H264,
                    resolution.getBitrateKbps(),
                    segmentUrls
                ));
            }
        }

        StreamManifest manifest = new StreamManifest(
            video.getVideoId(), "HLS", variants);
        videoStore.storeManifest(manifest);
        return manifest;
    }

    /**
     * Get the next segment for playback, using ABR to select resolution.
     */
    public Optional<VideoChunk> getNextSegment(String videoId, int segmentIndex,
                                                long bandwidthKbps,
                                                double bufferLevelSeconds) {
        // --- ABR: select best resolution for current conditions ---
        Resolution selectedResolution = abrStrategy.selectResolution(
            bandwidthKbps, bufferLevelSeconds);

        // --- Fetch via CDN (handles caching) ---
        return cdnService.fetchSegment(videoId, selectedResolution, segmentIndex);
    }
}
```

### 7.5 CDNService

```java
/**
 * Simulated CDN with two-tier caching: edge cache + origin fallback.
 *
 * In production, this would be CloudFront / Akamai / Netflix Open Connect.
 * For LLD, we simulate the cache hierarchy with ConcurrentHashMaps.
 *
 * Cache hierarchy:
 *   Edge cache (hot content, limited capacity) → O(1) lookup
 *   Origin cache (warm content, larger capacity) → O(1) lookup
 *   VideoStore (cold storage, unlimited) → O(1) lookup (in-memory demo)
 *
 * Cache eviction: LRU at edge (bounded size), no eviction at origin.
 *
 * WIRING (AppConfig):
 *   CDNService cdnService = new CDNService(videoStore, 1000);  // edge capacity
 *
 * CALL CHAIN:
 *   StreamingService.getNextSegment(videoId, resolution, segmentIndex)
 *     → CDNService.fetchSegment(videoId, resolution, segmentIndex)
 *       → edge cache HIT?  → return chunk (fastest)
 *       → edge cache MISS → origin cache HIT? → cache at edge, return
 *       → origin MISS → VideoStore.getChunk() → cache at origin + edge, return
 */
public class CDNService {

    private final VideoStore videoStore;
    // Edge cache: limited capacity, LRU eviction
    private final LinkedHashMap<String, VideoChunk> edgeCache;
    // Origin cache: larger capacity, no eviction in demo
    private final ConcurrentHashMap<String, VideoChunk> originCache;
    private final int edgeCacheCapacity;

    // --- Metrics ---
    private final AtomicLong edgeHits = new AtomicLong(0);
    private final AtomicLong edgeMisses = new AtomicLong(0);
    private final AtomicLong originHits = new AtomicLong(0);
    private final AtomicLong originMisses = new AtomicLong(0);

    public CDNService(VideoStore videoStore, int edgeCacheCapacity) {
        this.videoStore = videoStore;
        this.edgeCacheCapacity = edgeCacheCapacity;
        this.originCache = new ConcurrentHashMap<>();

        // LRU edge cache: removes eldest entry when capacity exceeded
        this.edgeCache = new LinkedHashMap<>(edgeCacheCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, VideoChunk> eldest) {
                return size() > edgeCacheCapacity;
            }
        };
    }

    /**
     * Fetch a video segment, checking edge → origin → object storage.
     */
    public Optional<VideoChunk> fetchSegment(String videoId, Resolution resolution,
                                              int segmentIndex) {
        String key = String.format("videos/%s/%s/%04d", videoId, resolution.name(),
                                    segmentIndex);

        // --- Layer 1: Edge cache ---
        synchronized (edgeCache) {
            VideoChunk cached = edgeCache.get(key);
            if (cached != null) {
                edgeHits.incrementAndGet();
                return Optional.of(cached);
            }
        }
        edgeMisses.incrementAndGet();

        // --- Layer 2: Origin cache ---
        VideoChunk originCached = originCache.get(key);
        if (originCached != null) {
            originHits.incrementAndGet();
            // Promote to edge cache
            synchronized (edgeCache) {
                edgeCache.put(key, originCached);
            }
            return Optional.of(originCached);
        }
        originMisses.incrementAndGet();

        // --- Layer 3: Object storage (S3) ---
        Optional<VideoChunk> fromStorage = videoStore.getChunk(key);
        fromStorage.ifPresent(chunk -> {
            // Cache at both layers
            originCache.put(key, chunk);
            synchronized (edgeCache) {
                edgeCache.put(key, chunk);
            }
        });

        return fromStorage;
    }

    // --- Metrics getters ---
    public long getEdgeHits() { return edgeHits.get(); }
    public long getEdgeMisses() { return edgeMisses.get(); }
    public long getOriginHits() { return originHits.get(); }
    public long getOriginMisses() { return originMisses.get(); }

    public double getEdgeHitRate() {
        long total = edgeHits.get() + edgeMisses.get();
        return total == 0 ? 0.0 : (double) edgeHits.get() / total;
    }
}
```

### 7.6 RecommendationService

```java
/**
 * Applies the pluggable recommendation strategy.
 *
 * Thin wrapper that allows runtime strategy swapping (A/B testing).
 *
 * WIRING (AppConfig):
 *   RecommendationService recService = new RecommendationService(
 *       new PersonalizedRecommendation(videoRepo, watchHistoryRepo, metadataStore));
 */
public class RecommendationService {

    private volatile RecommendationStrategy strategy;

    public RecommendationService(RecommendationStrategy strategy) {
        this.strategy = strategy;
    }

    public List<Video> recommend(String userId, int limit) {
        return strategy.recommend(userId, limit);
    }

    /** Swap strategy at runtime (for A/B testing). */
    public void setStrategy(RecommendationStrategy strategy) {
        this.strategy = strategy;
    }

    public String getActiveStrategyName() {
        return strategy.getStrategyName();
    }
}
```

### 7.7 SearchService

```java
/**
 * Searches videos by title, tags, and category.
 *
 * Simple in-memory search for LLD. In production, this would be
 * Elasticsearch/OpenSearch with inverted indexes.
 *
 * CALL CHAIN:
 *   VideoController.search(query)
 *     → VideoService.search(query)
 *       → SearchService.search(query)
 *         → scan all videos, match title/tags/category
 *         → sort by relevance score
 */
public class SearchService {

    private final VideoRepository videoRepository;
    private final Map<String, VideoMetadata> metadataStore;

    public SearchService(VideoRepository videoRepository,
                          Map<String, VideoMetadata> metadataStore) {
        this.videoRepository = videoRepository;
        this.metadataStore = metadataStore;
    }

    /**
     * Search videos matching the query string.
     * Matches against title (weight 3), tags (weight 2), category (weight 1).
     */
    public List<Video> search(String query) {
        if (query == null || query.isBlank()) return List.of();

        String lowerQuery = query.toLowerCase();

        return videoRepository.findAll().stream()
            .filter(v -> v.getStatus() == VideoStatus.READY)
            .map(v -> {
                int score = computeRelevance(v, lowerQuery);
                return Map.entry(v, score);
            })
            .filter(e -> e.getValue() > 0)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .map(Map.Entry::getKey)
            .toList();
    }

    private int computeRelevance(Video video, String query) {
        int score = 0;

        // Title match (highest weight)
        if (video.getTitle().toLowerCase().contains(query)) {
            score += 3;
        }

        VideoMetadata meta = metadataStore.get(video.getVideoId());
        if (meta != null) {
            // Tag match
            for (String tag : meta.getTags()) {
                if (tag.toLowerCase().contains(query)) {
                    score += 2;
                    break;
                }
            }
            // Category match
            if (meta.getCategory().toLowerCase().contains(query)) {
                score += 1;
            }
        }

        return score;
    }
}
```

### 7.8 AnalyticsService

```java
/**
 * Tracks video engagement metrics: views, watch time, completion.
 *
 * Every video play creates a WatchHistory record. Metrics are aggregated
 * for recommendations (category affinity), trending (view counts), and
 * creator analytics dashboards.
 *
 * Thread safety: viewCount uses AtomicLong, WatchHistory repo is ConcurrentHashMap-backed.
 *
 * WIRING (AppConfig):
 *   AnalyticsService analyticsService = new AnalyticsService(
 *       watchHistoryRepo, metadataStore);
 */
public class AnalyticsService {

    private final WatchHistoryRepository watchHistoryRepository;
    private final Map<String, VideoMetadata> metadataStore;

    public AnalyticsService(WatchHistoryRepository watchHistoryRepository,
                             Map<String, VideoMetadata> metadataStore) {
        this.watchHistoryRepository = watchHistoryRepository;
        this.metadataStore = metadataStore;
    }

    /**
     * Record a watch event.
     *
     * CALL CHAIN:
     *   VideoController.recordWatch(userId, videoId, duration, completion)
     *     → VideoService.recordWatch(...)
     *       → AnalyticsService.recordWatch(...)
     *         → WatchHistoryRepository.save(watchHistory)
     *         → VideoMetadata.incrementViewCount()
     */
    public void recordWatch(String userId, String videoId,
                             int durationSeconds, double completionPercent) {
        // --- Store watch history ---
        WatchHistory history = new WatchHistory(
            userId, videoId, Instant.now(), durationSeconds, completionPercent);
        watchHistoryRepository.save(history);

        // --- Increment view count ---
        VideoMetadata meta = metadataStore.get(videoId);
        if (meta != null) {
            meta.incrementViewCount();
        }
    }

    /** Get total watch time for a video (in seconds). */
    public long getTotalWatchTime(String videoId) {
        return watchHistoryRepository.findByVideoId(videoId).stream()
            .mapToLong(WatchHistory::watchDurationSeconds)
            .sum();
    }

    /** Get average completion rate for a video. */
    public double getAverageCompletion(String videoId) {
        List<WatchHistory> watches = watchHistoryRepository.findByVideoId(videoId);
        if (watches.isEmpty()) return 0.0;
        return watches.stream()
            .mapToDouble(WatchHistory::completionPercent)
            .average()
            .orElse(0.0);
    }
}
```

---

## 8. Concurrency Considerations

### 8.1 Parallel Transcoding (ExecutorService + CompletableFuture)

#### Anti-Pattern: Single-Threaded Transcoding

```
     ┌──────────────────────────────────────────────────────────────────┐
     │      ANTI-PATTERN: Single-Threaded Transcoding                   │
     │                                                                  │
     │   public void transcodeAll(Video video, List<Resolution> res) { │
     │       for (Resolution r : res) {                                │
     │           transcode(video, r);  // blocks until done            │
     │       }                                                          │
     │       // total time = sum of ALL transcode times                │
     │       // for a 1-hour video with 5 resolutions: ~150 minutes    │
     │   }                                                              │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. Wall-clock time is SUM not MAX of all jobs               │
     │     2. CPU cores sit idle while one resolution transcodes       │
     │     3. User waits 2.5x longer for video to be ready            │
     │     4. Cannot utilize distributed worker nodes                   │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Parallel with CompletableFuture

```java
/**
 * CORRECT: Submit all transcode jobs in parallel.
 *
 * Key concurrency constructs:
 *   - ExecutorService: thread pool for parallel execution
 *   - CompletableFuture.supplyAsync(): submit job to thread pool
 *   - CompletableFuture.allOf(): wait for ALL jobs to complete
 *   - Video.addResolution() uses ConcurrentHashMap.KeySet (thread-safe)
 *   - TranscodeJob.start()/complete() are synchronized
 *
 * Thread safety analysis:
 *   - Each thread operates on its own TranscodeJob (no sharing)
 *   - Video.resolutions is ConcurrentHashMap.KeySet (thread-safe add)
 *   - Video.status transitions are synchronized (one at a time)
 *   - VideoStore.storeChunk() writes to ConcurrentHashMap (thread-safe)
 */
public List<TranscodeJob> transcodeAll(Video video, List<Resolution> resolutions,
                                        Codec codec) {
    // --- Submit ALL resolutions in parallel ---
    List<CompletableFuture<TranscodeJob>> futures = resolutions.stream()
        .map(res -> CompletableFuture.supplyAsync(
            () -> transcode(video, res, codec),    // each runs on its own thread
            executorService                         // bounded thread pool
        ))
        .toList();

    // --- Wait for ALL to complete ---
    // allOf() creates a future that completes when ALL input futures complete.
    // join() blocks the calling thread until allOf completes.
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // --- Collect results ---
    return futures.stream()
        .map(CompletableFuture::join)    // safe: already completed
        .toList();
}
```

### 8.2 CDN Cache Thread Safety

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  CDN CACHE CONCURRENCY MODEL                                     │
     │                                                                  │
     │  Problem: thousands of concurrent requests hitting the CDN.      │
     │  Multiple threads reading/writing edge cache simultaneously.     │
     │                                                                  │
     │  Edge Cache (LinkedHashMap + synchronized block):                │
     │    - LinkedHashMap is NOT thread-safe                            │
     │    - We wrap ALL access in synchronized(edgeCache) blocks       │
     │    - Acceptable because edge cache ops are O(1) + fast          │
     │    - Alternative: ConcurrentLinkedHashMap (Guava/Caffeine)      │
     │                                                                  │
     │  Origin Cache (ConcurrentHashMap):                               │
     │    - ConcurrentHashMap IS thread-safe natively                  │
     │    - Lock-free reads, segment-level write locking               │
     │    - No synchronized blocks needed                               │
     │                                                                  │
     │  VideoStore (ConcurrentHashMap-backed):                          │
     │    - Thread-safe for concurrent reads and writes                │
     │                                                                  │
     │  Thread flow for cache miss:                                    │
     │    Thread-1: read edge (MISS) → read origin (MISS) →           │
     │             read store → write origin → write edge → return     │
     │                                                                  │
     │    Thread-2 (same key, moments later):                          │
     │             read edge (HIT!) → return                           │
     │                                                                  │
     │  Thundering herd mitigation (production):                        │
     │    - Use singleflight/coalesce: only ONE thread fetches from    │
     │      origin for a given key, others wait on the result          │
     │    - Not implemented in LLD demo for simplicity                 │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.3 View Count Atomicity

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  VIEW COUNT CONCURRENCY                                          │
     │                                                                  │
     │  Problem: popular video gets 1000 views/second.                 │
     │  If viewCount is a plain long, increment is NOT atomic:         │
     │    Thread-1: read viewCount (100) → compute 101 → write 101    │
     │    Thread-2: read viewCount (100) → compute 101 → write 101    │
     │    RESULT: 101 (should be 102) → LOST UPDATE                   │
     │                                                                  │
     │  Solution: AtomicLong.incrementAndGet()                         │
     │    Thread-1: CAS(100 → 101) → success                          │
     │    Thread-2: CAS(100 → 101) → fail → retry → CAS(101 → 102)  │
     │    RESULT: 102 (correct!)                                       │
     │                                                                  │
     │  AtomicLong uses CPU CAS instruction -- no locks, no blocking.  │
     │  Perfect for high-contention counters.                           │
     │                                                                  │
     │  In production at YouTube scale:                                │
     │    - Even AtomicLong becomes a bottleneck at 1M views/sec      │
     │    - Solution: per-shard counters → periodic aggregation        │
     │    - Each CDN edge maintains local count, syncs every 5 sec    │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.4 Upload Resumability

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  CHUNKED UPLOAD CONCURRENCY                                      │
     │                                                                  │
     │  uploadProgress: ConcurrentHashMap<String, Set<Integer>>        │
     │    - Outer map: videoId → set of received chunk indices         │
     │    - Set is ConcurrentHashMap.newKeySet() (thread-safe)         │
     │                                                                  │
     │  Scenario: client uploads chunks in parallel (3 connections):   │
     │    Thread-1: uploadChunk(video, 0, data0)                       │
     │    Thread-2: uploadChunk(video, 1, data1)                       │
     │    Thread-3: uploadChunk(video, 2, data2)                       │
     │                                                                  │
     │  All three safely add to the same Set<Integer>.                │
     │  ConcurrentHashMap.KeySet handles concurrent adds.              │
     │                                                                  │
     │  Resume scenario:                                                │
     │    - Client uploaded chunks 0-46, then connection dropped       │
     │    - Client reconnects, queries: isChunkReceived(video, 46)?    │
     │    - Answer: true → resume from chunk 47                        │
     │    - Idempotency: re-uploading chunk 46 is a no-op             │
     └──────────────────────────────────────────────────────────────────┘
```

---

## 9. SOLID Principles Applied

### 9.1 Single Responsibility (SRP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  Each service has ONE reason to change:                          │
     │                                                                  │
     │  UploadService         → upload logic changes                   │
     │  TranscodingService    → transcoding pipeline changes           │
     │  StreamingService      → manifest generation / ABR changes      │
     │  CDNService            → caching strategy changes               │
     │  RecommendationService → recommendation algorithm changes       │
     │  SearchService         → search/indexing logic changes          │
     │  AnalyticsService      → metrics/tracking changes               │
     │                                                                  │
     │  VIOLATION EXAMPLE:                                              │
     │    A "VideoManager" that uploads, transcodes, streams, caches,  │
     │    recommends, and searches all in one class.                    │
     │    → 7 reasons to change, ~2000-line god class                  │
     │                                                                  │
     │  CLEAN: Each service < 150 lines, one concern.                  │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.2 Open/Closed Principle (OCP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  OPEN for extension, CLOSED for modification:                    │
     │                                                                  │
     │  Adding a new transcoding strategy:                              │
     │    1. Create DistributedTranscodingStrategy implements           │
     │       TranscodingStrategy                                        │
     │    2. Change ONE line in AppConfig                               │
     │    3. ZERO changes to TranscodingService                        │
     │                                                                  │
     │  Adding a new ABR algorithm:                                    │
     │    1. Create HybridABR implements ABRStrategy                   │
     │    2. Change ONE line in AppConfig                               │
     │    3. ZERO changes to StreamingService                          │
     │                                                                  │
     │  Adding a new resolution (e.g., P8K):                           │
     │    1. Add P8K(4320, 7680, 4320, 40_000) to Resolution enum     │
     │    2. ZERO changes to any service, strategy, or controller      │
     │    3. All existing code auto-handles the new resolution         │
     │                                                                  │
     │  Adding a new codec (e.g., VVC):                                │
     │    1. Add VVC(0.40, 0.20) to Codec enum                        │
     │    2. ZERO changes to transcoding strategies or services        │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.3 Liskov Substitution (LSP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  Any TranscodingStrategy can replace any other without breaking: │
     │                                                                  │
     │  TranscodingService works identically with:                      │
     │    - ParallelTranscodingStrategy  (fast, multi-threaded)        │
     │    - SequentialTranscodingStrategy (slow, single-threaded)      │
     │    - [future] DistributedTranscodingStrategy                    │
     │                                                                  │
     │  Contract: transcodeAll() accepts video + resolutions + codec,  │
     │            returns List<TranscodeJob>.                           │
     │  ALL implementations honor this contract.                        │
     │  TranscodingService never checks which strategy it got.         │
     │                                                                  │
     │  Same for ABRStrategy:                                           │
     │    StreamingService works identically with:                      │
     │    - ThroughputBasedABR                                         │
     │    - BufferBasedABR                                             │
     │    Both return a Resolution. StreamingService doesn't care how. │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.4 Interface Segregation (ISP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  Interfaces are focused and cohesive:                            │
     │                                                                  │
     │  TranscodingStrategy: transcode, transcodeAll, getStrategyName  │
     │  ABRStrategy:         selectResolution, getStrategyName         │
     │  RecommendationStrategy: recommend, getStrategyName             │
     │  VideoStore:          storeChunk, getChunk, storeManifest, ...  │
     │  VideoRepository:     save, findById, findAll, delete           │
     │                                                                  │
     │  NO client depends on methods it doesn't use.                   │
     │  StreamingService uses ABRStrategy (2 methods), not a fat       │
     │  "VideoProcessingStrategy" with 20 methods.                     │
     │                                                                  │
     │  BAD: interface VideoProcessor {                                │
     │         transcode(); selectResolution(); recommend();           │
     │         search(); upload(); cache(); generateManifest();        │
     │       }                                                          │
     │  → Forces implementations to implement methods they don't need  │
     └──────────────────────────────────────────────────────────────────┘
```

### 9.5 Dependency Inversion (DIP)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │  High-level modules depend on ABSTRACTIONS, not concretions:    │
     │                                                                  │
     │  TranscodingService → depends on TranscodingStrategy (interface)│
     │                       NOT on ParallelTranscodingStrategy (impl) │
     │                                                                  │
     │  StreamingService   → depends on ABRStrategy (interface)        │
     │                       NOT on ThroughputBasedABR (impl)          │
     │                                                                  │
     │  StreamingService   → depends on VideoStore (interface)         │
     │                       NOT on InMemoryVideoStore (impl)          │
     │                                                                  │
     │  CDNService         → depends on VideoStore (interface)         │
     │                       NOT on InMemoryVideoStore (impl)          │
     │                                                                  │
     │  AppConfig wires concrete implementations:                      │
     │    VideoStore store = new InMemoryVideoStore();                  │
     │    TranscodingStrategy strat = new ParallelTranscoding...(store);│
     │    TranscodingService svc = new TranscodingService(strat, repo);│
     │                                                                  │
     │  → swap InMemoryVideoStore for S3VideoStore: 1 line in AppConfig│
     └──────────────────────────────────────────────────────────────────┘
```

---

## 10. Sample Workflows

### 10.1 Video Upload + Transcode + Publish

```
USER: Creator "alice" uploads "Cooking Tutorial" (1080p, 30 minutes)

  VideoController.upload("Cooking Tutorial", "alice", "Learn pasta", 30min, chunks)
    │
    ▼
  VideoService.uploadVideo("Cooking Tutorial", "alice", "Learn pasta", 30min, chunks)
    │
    ├── 1. CREATE VIDEO ENTITY
    │     Video video = new Video.Builder("Cooking Tutorial", "alice")
    │         .description("Learn pasta")
    │         .duration(Duration.ofMinutes(30))
    │         .build();
    │     → Video[id=vid-a1b2c3d4, title='Cooking Tutorial', status=UPLOADING]
    │     VideoRepository.save(video)
    │
    ├── 2. CHUNKED UPLOAD
    │     UploadService.uploadChunked(video, chunks)
    │       │
    │       ├── uploadChunk(video, 0, data[0])  → VideoStore.storeChunk(chunk-0)
    │       ├── uploadChunk(video, 1, data[1])  → VideoStore.storeChunk(chunk-1)
    │       ├── uploadChunk(video, 2, data[2])  → VideoStore.storeChunk(chunk-2)
    │       │   ... (N chunks)
    │       └── video.transitionTo(UPLOADED)
    │           → Video status: UPLOADING → UPLOADED
    │
    ├── 3. TRANSCODE TO MULTIPLE RESOLUTIONS
    │     TranscodingService.transcodeVideo(video, [P240,P360,P480,P720,P1080], H264)
    │       │
    │       ├── video.transitionTo(TRANSCODING)
    │       │   → Video status: UPLOADED → TRANSCODING
    │       │
    │       ├── ParallelTranscodingStrategy.transcodeAll(video, resolutions, H264)
    │       │   │
    │       │   ├── Thread-1: transcode(video, P240, H264)
    │       │   │     → TranscodeJob[P240, QUEUED → PROCESSING → 25% → 50% → 100% → COMPLETED]
    │       │   │     → video.addResolution(P240)
    │       │   │
    │       │   ├── Thread-2: transcode(video, P360, H264)
    │       │   │     → TranscodeJob[P360, QUEUED → PROCESSING → ... → COMPLETED]
    │       │   │     → video.addResolution(P360)
    │       │   │
    │       │   ├── Thread-3: transcode(video, P480, H264)      ← all run simultaneously
    │       │   │     → TranscodeJob[P480, QUEUED → PROCESSING → ... → COMPLETED]
    │       │   │     → video.addResolution(P480)
    │       │   │
    │       │   ├── Thread-4: transcode(video, P720, H264)
    │       │   │     → TranscodeJob[P720, QUEUED → PROCESSING → ... → COMPLETED]
    │       │   │     → video.addResolution(P720)
    │       │   │
    │       │   └── Thread-5: transcode(video, P1080, H264)
    │       │         → TranscodeJob[P1080, QUEUED → PROCESSING → ... → COMPLETED]
    │       │         → video.addResolution(P1080)
    │       │
    │       └── All jobs COMPLETED → video.transitionTo(READY)
    │           → Video status: TRANSCODING → READY
    │
    └── 4. GENERATE STREAM MANIFEST
          StreamingService.generateManifest(video)
            │
            ├── Build StreamVariant for each resolution:
            │     StreamVariant[P240, H264, 400 Kbps, [seg-0, seg-1, ..., seg-449]]
            │     StreamVariant[P360, H264, 750 Kbps, [seg-0, seg-1, ..., seg-449]]
            │     StreamVariant[P480, H264, 1000 Kbps, [...]]
            │     StreamVariant[P720, H264, 2500 Kbps, [...]]
            │     StreamVariant[P1080, H264, 4500 Kbps, [...]]
            │
            └── StreamManifest[video=vid-a1b2c3d4, protocol=HLS, variants=5]
                → VideoStore.storeManifest(manifest)

  RESULT: Video is READY for streaming with 5 quality levels.
```

### 10.2 Video Streaming with ABR + CDN

```
USER: Viewer "bob" watches "Cooking Tutorial" on a 5 Mbps connection

  INITIAL STATE:
    - bandwidth = 5000 Kbps, buffer = 0s (playback just started)
    - ABR strategy: ThroughputBasedABR(safetyMargin=0.7)

  SEGMENT 0 (cold start):
    VideoService.streamSegment("vid-a1b2c3d4", 0, 5000, 0.0)
      → StreamingService.getNextSegment("vid-a1b2c3d4", 0, 5000, 0.0)
        → ThroughputBasedABR.selectResolution(5000, 0.0)
          → effectiveBW = 5000 * 0.7 = 3500 Kbps
          → P720 (2500 Kbps) fits, P1080 (4500 Kbps) doesn't → P720
        → CDNService.fetchSegment("vid-a1b2c3d4", P720, 0)
          → Edge cache: MISS (first request ever)
          → Origin cache: MISS
          → VideoStore.getChunk("videos/vid-a1b2c3d4/P720/0000")
          → Cache at origin + edge
          → Return segment (latency: ~200ms)

  SEGMENT 1 (buffer building):
    bandwidth = 5000, buffer = 4s (one segment buffered)
      → ABR selects P720 again
      → CDN edge: MISS (different segment)
      → VideoStore → cache → return

  SEGMENT 50 (bandwidth drops):
    bandwidth = 1000 Kbps, buffer = 8s
      → effectiveBW = 1000 * 0.7 = 700 Kbps
      → P240 (400 Kbps) fits → DOWNGRADE to P240
      → CDN fetches P240 segment 50
      → User sees quality drop (but no stall!)

  SEGMENT 80 (bandwidth recovers):
    bandwidth = 8000 Kbps, buffer = 20s
      → effectiveBW = 8000 * 0.7 = 5600 Kbps
      → P1080 (4500 Kbps) fits → UPGRADE to P1080
      → User sees quality improve

  AFTER PLAYBACK:
    VideoService.recordWatch("bob", "vid-a1b2c3d4", 1800, 95.0)
      → AnalyticsService.recordWatch(...)
        → WatchHistoryRepository.save(WatchHistory["bob", "vid-a1b2c3d4", now, 1800, 95.0])
        → VideoMetadata.incrementViewCount() → AtomicLong CAS
```

### 10.3 Recommendation Flow

```
USER: "bob" opens the home page, wants recommendations

  VideoController.getRecommendations("bob", 10)
    │
    ▼
  VideoService.getRecommendations("bob", 10)
    │
    ▼
  RecommendationService.recommend("bob", 10)
    │
    ▼
  PersonalizedRecommendation.recommend("bob", 10)
    │
    ├── 1. GET WATCH HISTORY
    │     WatchHistoryRepository.findByUserId("bob")
    │       → [WatchHistory["bob", "vid-cook1", 1800s, 95.0%],     (Cooking)
    │          WatchHistory["bob", "vid-cook2", 900s, 80.0%],      (Cooking)
    │          WatchHistory["bob", "vid-game1", 2700s, 60.0%],     (Gaming)
    │          WatchHistory["bob", "vid-music1", 300s, 20.0%]]     (Music)
    │
    ├── 2. COMPUTE CATEGORY AFFINITY
    │     Cooking: (1800*0.95 + 900*0.80) / total = 2430 / 4380 = 0.555
    │     Gaming:  (2700*0.60) / total = 1620 / 4380 = 0.370
    │     Music:   (300*0.20) / total = 60 / 4380 = 0.014
    │     Other:   0.01 (default for unseen categories)
    │
    ├── 3. SCORE UNWATCHED VIDEOS
    │     "Sushi Masterclass" (Cooking, 2h ago, 500 views)
    │       → score = 0.555 * 0.98 * 1.62 = 0.881
    │     "Fortnite Tips" (Gaming, 5h ago, 1200 views)
    │       → score = 0.370 * 0.95 * 1.71 = 0.601
    │     "Jazz Concert" (Music, 1h ago, 50 views)
    │       → score = 0.014 * 0.99 * 1.39 = 0.019
    │
    └── 4. RETURN TOP 10 SORTED BY SCORE
          → [Sushi Masterclass, Fortnite Tips, ..., Jazz Concert]

  Bob sees cooking videos first (matches his watch history).
```

### 10.4 Resumable Upload (Connection Drop + Resume)

```
USER: Creator "alice" uploads a 2GB video, connection drops at 60%

  INITIAL UPLOAD (chunks 0-59 of 100):
    UploadService.uploadChunked(video, chunks[0..99])
      │
      ├── uploadChunk(video, 0, data[0])   → stored, progress: {0}
      ├── uploadChunk(video, 1, data[1])   → stored, progress: {0,1}
      │   ... (chunks 2-58 uploaded successfully)
      ├── uploadChunk(video, 59, data[59]) → stored, progress: {0..59}
      │
      └── CONNECTION DROPS at chunk 60!
          → UploadException: "Connection lost"
          → Video remains in UPLOADING status (not UPLOADED)
          → uploadProgress["vid-xyz"] = {0, 1, 2, ..., 59}

  CLIENT RECONNECTS AND RESUMES:
    // Client asks: which chunks do you already have?
    for (int i = 0; i < 100; i++) {
        if (!uploadService.isChunkReceived("vid-xyz", i)) {
            // Only upload missing chunks (60-99)
            uploadService.uploadChunk(video, i, data[i]);
        }
    }

    // Chunks 0-59: isChunkReceived → true → SKIP (idempotent)
    // Chunks 60-99: isChunkReceived → false → UPLOAD

    uploadChunk(video, 60, data[60]) → stored, progress: {0..60}
    uploadChunk(video, 61, data[61]) → stored, progress: {0..61}
    ... (chunks 62-99 uploaded)
    uploadChunk(video, 99, data[99]) → stored, progress: {0..99}

    → All 100 chunks received
    → video.transitionTo(UPLOADED)
    → Continue with transcoding pipeline

  SAVED: 60% of upload bandwidth (didn't re-upload chunks 0-59)
```

---

## 11. Design Patterns Used

```
┌──────────────────────┬──────────────────┬─────────────────────────────────────────┐
│  Pattern              │  Where Applied    │  Why                                   │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Strategy             │  TranscodingStrat│  Swap transcoding approach (parallel    │
│                       │  ABRStrategy     │  vs. sequential) without changing       │
│                       │  Recommendation  │  service layer. Core of the system.     │
│                       │  Strategy         │                                        │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Builder              │  Video.Builder    │  Complex object construction with       │
│                       │                  │  many optional fields (description,     │
│                       │                  │  duration, resolutions). Enforces       │
│                       │                  │  required fields at build time.         │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Facade               │  VideoService     │  Single entry point for video ops.      │
│                       │                  │  Orchestrates 7 internal services.      │
│                       │                  │  Controller doesn't know internals.     │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  State                │  VideoStatus      │  Video lifecycle (UPLOADING → UPLOADED  │
│                       │  TranscodeJob    │  → TRANSCODING → READY) with valid     │
│                       │  Status           │  transition enforcement. Invalid        │
│                       │                  │  transitions throw exceptions.          │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Repository           │  VideoRepository  │  Abstracts data access. InMemory for   │
│                       │  UserRepository  │  demo, swappable to DB/DynamoDB.       │
│                       │  WatchHistory    │                                        │
│                       │  Repository       │                                        │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Factory Method       │  AppConfig        │  Centralized object creation and        │
│  (via Config)         │                  │  dependency injection without a         │
│                       │                  │  framework. Pure constructor injection. │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Template Method      │  Transcoding      │  transcode() defines the algorithm     │
│  (implicit)           │  Strategy impls  │  skeleton: create job → start →         │
│                       │                  │  process segments → complete. Subclass  │
│                       │                  │  varies parallelism, not structure.     │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Proxy / Cache-Aside  │  CDNService       │  CDN acts as a caching proxy in front  │
│                       │                  │  of VideoStore. Transparently serves    │
│                       │                  │  cached content or fetches from origin. │
├──────────────────────┼──────────────────┼─────────────────────────────────────────┤
│  Record               │  VideoChunk,      │  Immutable data carriers using Java 16+│
│  (Java 16+)           │  StreamVariant,  │  records. Auto-generates equals,       │
│                       │  WatchHistory    │  hashCode, toString. Compact syntax.   │
└──────────────────────┴──────────────────┴─────────────────────────────────────────┘
```

### Pattern Interaction Map

```
    ┌─────────────────────────────────────────────────────────────────────┐
    │                                                                     │
    │   VideoController                                                   │
    │        │                                                            │
    │        ▼                                                            │
    │   VideoService ◄────────────── FACADE                               │
    │        │                                                            │
    │        ├──→ UploadService                                          │
    │        │         │                                                  │
    │        │         └──→ InMemoryVideoStore ◄───── REPOSITORY         │
    │        │                                                            │
    │        ├──→ TranscodingService                                     │
    │        │         │                                                  │
    │        │         └──→ ParallelTranscodingStrategy ◄── STRATEGY     │
    │        │                   │                                        │
    │        │                   └──→ ExecutorService (java.util.concurrent)│
    │        │                                                            │
    │        ├──→ StreamingService                                       │
    │        │         │                                                  │
    │        │         ├──→ ThroughputBasedABR ◄──────── STRATEGY        │
    │        │         └──→ CDNService ◄──────────────── PROXY/CACHE     │
    │        │                   │                                        │
    │        │                   └──→ VideoStore (interface) ◄── DIP     │
    │        │                                                            │
    │        ├──→ RecommendationService                                  │
    │        │         │                                                  │
    │        │         └──→ PersonalizedRecommendation ◄── STRATEGY      │
    │        │                                                            │
    │        └──→ AnalyticsService                                       │
    │                   │                                                 │
    │                   └──→ WatchHistoryRepository ◄──── REPOSITORY     │
    │                                                                     │
    │   Video.Builder ◄──────────────── BUILDER                          │
    │   VideoStatus   ◄──────────────── STATE MACHINE                    │
    │   AppConfig     ◄──────────────── FACTORY / DI CONTAINER           │
    │                                                                     │
    └─────────────────────────────────────────────────────────────────────┘
```

---

## 12. Extensibility Points

### 12.1 New Transcoding Strategy

```
SCENARIO: Add a DistributedTranscodingStrategy that uses worker nodes.

  1. Create DistributedTranscodingStrategy implements TranscodingStrategy
     - transcodeAll(): split resolutions across worker nodes via message queue
     - Each worker runs transcode() independently
     - Coordinator collects results via CompletableFuture
  2. Change ONE line in AppConfig:
     - TranscodingStrategy strategy = new DistributedTranscodingStrategy(workerPool, store);
  3. ZERO changes to: TranscodingService, VideoService, VideoController

  Files changed: 1 new + 1 modified (AppConfig)
```

### 12.2 New ABR Algorithm

```
SCENARIO: Add a HybridABR that combines throughput + buffer signals.

  1. Create HybridABR implements ABRStrategy
     - selectResolution(): 
         if buffer < critical → use BufferBased logic (safety first)
         else → use ThroughputBased logic (quality maximization)
  2. Change ONE line in AppConfig:
     - ABRStrategy abr = new HybridABR(0.7, 5.0, 30.0);
  3. ZERO changes to: StreamingService, VideoService

  Files changed: 1 new + 1 modified (AppConfig)
```

### 12.3 New Resolution or Codec

```
SCENARIO: Add 8K resolution and VVC codec.

  1. Add to Resolution enum:
     - P8K(4320, 7680, 4320, 40_000)   // 8K at 40 Mbps
  2. Add to Codec enum:
     - VVC(0.40, 0.20)                  // 60% better than H264, 20% device support
  3. ZERO changes to: any service, strategy, or controller
     - All code iterates Resolution.values() / Codec.values()
     - ABR strategies automatically consider new resolutions
     - Transcoding strategies automatically include new resolutions

  Files changed: 2 modified (Resolution enum, Codec enum)
```

### 12.4 New Storage Backend

```
SCENARIO: Replace InMemoryVideoStore with S3VideoStore.

  1. Create S3VideoStore implements VideoStore
     - storeChunk(): PUT to s3://bucket/videos/{videoId}/{resolution}/{segment}
     - getChunk(): GET from S3
     - storeManifest(): PUT manifest JSON to S3
  2. Change ONE line in AppConfig:
     - VideoStore store = new S3VideoStore(s3Client, "my-video-bucket");
  3. ZERO changes to: UploadService, StreamingService, CDNService, TranscodingService
     - All depend on VideoStore interface, not InMemoryVideoStore

  Files changed: 1 new + 1 modified (AppConfig)
```

### 12.5 New Recommendation Algorithm

```
SCENARIO: Add CollaborativeFilteringRecommendation ("users like you watched...").

  1. Create CollaborativeFilteringRecommendation implements RecommendationStrategy
     - recommend(): find users with similar watch history (cosine similarity)
     - recommend videos those similar users watched but target user hasn't
  2. Swap at runtime for A/B testing:
     - recommendationService.setStrategy(new CollaborativeFilteringRecommendation(...));
  3. ZERO changes to: RecommendationService, VideoService

  Files changed: 1 new + optionally 1 modified (AppConfig)
```

### 12.6 Live Streaming Extension

```
SCENARIO: Add live streaming support (HLS Live).

  CURRENT (VOD only):
    Upload → Transcode → Manifest → Stream (pre-recorded)

  EXTENDED (Live):
    1. Create LiveStreamService
       - Receives real-time video chunks from encoder (OBS/RTMP ingest)
       - Transcodes chunks in near-real-time (low-latency pipeline)
       - Appends segments to a LIVE manifest (sliding window)
    2. Create LiveManifest extends StreamManifest
       - Sliding window: only last N segments listed
       - EXT-X-ENDLIST absent (signals "still live" to player)
    3. CDNService: add TTL-based caching for live segments
       - Live segments cached for ~2 seconds (low-latency)
       - VOD segments cached indefinitely

  Implementation:
    - LiveStreamService uses existing TranscodingStrategy
    - Existing ABR strategies work for live playback (same selectResolution)
    - CDNService serves live and VOD segments identically

  Files changed: 2 new (LiveStreamService, LiveManifest) + CDNService modified
```

### 12.7 Extensibility Summary

```
     ┌──────────────────────────────────────────────────────────────────┐
     │   EXTENSIBILITY MATRIX                                           │
     │                                                                  │
     │   Change                          │ Files Modified │ Files New  │
     │   ────────────────────────────────┼───────────────┼────────────│
     │   New transcoding strategy        │ 1 (AppConfig) │ 1          │
     │   New ABR algorithm               │ 1 (AppConfig) │ 1          │
     │   New resolution (P8K)            │ 1 (enum)      │ 0          │
     │   New codec (VVC)                 │ 1 (enum)      │ 0          │
     │   New storage backend (S3)        │ 1 (AppConfig) │ 1          │
     │   New recommendation algorithm    │ 1 (AppConfig) │ 1          │
     │   Live streaming support          │ 1 (CDNSvc)    │ 2          │
     │   A/B test ABR strategies         │ 1 (StreamSvc) │ 0          │
     │                                                                  │
     │   EVERY extension modifies at most 1 existing file.             │
     │   This is the Open/Closed Principle in action.                  │
     └──────────────────────────────────────────────────────────────────┘
```

---
