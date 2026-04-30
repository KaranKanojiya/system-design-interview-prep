# Video Streaming Platform (YouTube/Netflix)

## Problem Summary

Design a **video streaming platform** (like YouTube or Netflix) that handles 500M+ daily active users uploading 500K videos/day and serving 5B video views/day. The core challenges are **chunked upload** -- splitting large video files into 5MB chunks uploaded in parallel via presigned URLs, resumable on failure, bypassing the API server entirely. **Transcoding** converts each source video into a **resolution ladder** (240p through 4K) across **multiple codecs** (H264/H265/VP9/AV1) as a parallel DAG pipeline, producing 20+ renditions per video. Each rendition is segmented into 2-10 second chunks for **HLS/DASH adaptive bitrate streaming**, where the client downloads a manifest, picks the highest resolution fitting its bandwidth, and **switches quality mid-stream** using hybrid ABR (throughput-based + buffer-based). Segments are served via a **multi-tier CDN** (edge cache -> origin shield -> S3 origin), where **10% of videos account for 90% of views** (Zipf distribution), making caching extremely effective. Cache key is `videoId/resolution/segment`. At extreme scale, CDN bandwidth cost forces custom infrastructure like **Netflix Open Connect** (ISP-embedded appliances serving 90%+ of traffic). Storage is **tiered**: hot (S3 Standard) for new/popular content, warm (S3-IA) after 30 days, cold (Glacier) after 90 days. The system is **AP for streaming** (buffer ahead, tolerate brief staleness -- availability is non-negotiable) and **CP for upload state** (chunk tracking must be consistent to avoid data loss or corruption).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Transcoding: convert source to multiple resolutions (240p-4K) + codecs (H264/H265/VP9/AV1). Parallel pipeline.** A single uploaded video is transcoded into 20+ renditions (5 resolutions * 4 codecs). Each rendition is an independent job -- run all in parallel via a DAG (Step Functions). Each job produces segmented output (2-10 second chunks) for adaptive streaming. Total wall-clock time: 15-45 minutes regardless of rendition count. AV1 gives 60% smaller files than H264 but costs 10x more CPU to encode -- worth it for popular videos where bandwidth savings compound per view.
- **HLS/DASH: adaptive bitrate. Client downloads manifest -> picks resolution -> switches mid-stream based on bandwidth.** HLS (Apple) uses master.m3u8 listing sub-playlists per resolution. DASH (MPEG) uses manifest.mpd with AdaptationSets. Both work the same way: client fetches manifest, picks initial quality based on measured bandwidth, downloads segments sequentially, and switches resolution at segment boundaries if bandwidth changes. Seamless quality transitions -- no rebuffering.
- **CDN: multi-tier edge caching. 10% of videos = 90% of views (Zipf). Cache key = videoId/resolution/segment.** First tier: 400+ edge PoPs (< 5ms). Second tier: origin shield (consolidates cache fills, < 30ms). Third tier: S3 origin (< 100ms). Popular videos stay cached at the edge. Long-tail videos fall through to origin but get cached on return. Cache key includes resolution and segment number to avoid serving wrong quality.
- **Chunked upload: 5MB chunks, parallel upload, resumable. Presigned URLs bypass API server.** Client requests upload session -> server returns presigned S3 URLs for N chunks. Client uploads 5-10 chunks in parallel directly to S3 (API server never sees the bytes). If upload fails mid-way, client resumes from last successful chunk. S3 event notifications track completion. When all chunks arrive, Lambda triggers assembly + transcoding.
- **ABR: throughput-based (pick highest fitting bandwidth) vs buffer-based (conservative, prevent rebuffer).** Throughput-based: measure download speed of last segment, pick highest resolution where bitrate < 80% of bandwidth. Simple but reacts slowly to sudden drops. Buffer-based: if buffer < 5s, drop to lowest; if > 15s, try higher. Conservative but prevents rebuffering. Hybrid (Netflix/YouTube): combine both signals -- buffer level overrides throughput when low (safety first).
- **Storage tiered: hot (S3) -> warm (S3-IA) -> cold (Glacier) based on view frequency.** 80% of views happen in the first 7 days after upload. After 30 days, move to S3-IA (40% cheaper). After 90 days, move to Glacier (80% cheaper). Per-title encoding optimizes bitrate per content type (cartoons need fewer bits than sports). Lazy transcoding: only generate 4K/240p renditions on demand, not upfront.
- **CAP: AP for streaming (availability > consistency). CP for upload state.** During a network partition, serve video from CDN cache even if metadata is slightly stale -- a user seeing a view count that's 30 seconds old is invisible. But upload chunk tracking must be CP: if we lose track of which chunks were received, we corrupt the video. Upload state lives in DynamoDB with strong consistency reads.

---

## Class Hierarchy

```
Video (domain entity)                   VideoSegment (value object)
  |-- videoId, uploaderId                 |-- segmentId, videoId
  |-- title, description, tags            |-- resolution (240p-4K)
  |-- status: UPLOADING/UPLOADED/         |-- codec (H264/H265/VP9/AV1)
  |    TRANSCODING/READY/FAILED/DELETED   |-- segmentIndex (0, 1, 2, ...)
  |-- sourceUrl (S3 path)                 |-- durationSeconds (2-10s)
  |-- duration, fileSizeBytes             |-- cdnUrl (CloudFront URL)
  |-- resolutions: List<Resolution>       |-- sizeBytes
  |-- codecs: List<Codec>                 |-- No setters (immutable)
  |-- manifestUrl (master.m3u8)
  |-- createdAt, updatedAt

TranscodingJob (domain entity)          UploadSession (domain entity)
  |-- jobId, videoId                      |-- sessionId, videoId, uploaderId
  |-- sourceResolution                    |-- totalChunks, completedChunks
  |-- targetResolution                    |-- chunkSizeBytes (5MB)
  |-- targetCodec                         |-- presignedUrls: Map<Integer, String>
  |-- status: QUEUED/RUNNING/DONE/FAILED  |-- status: IN_PROGRESS/COMPLETE/FAILED
  |-- progress (0-100%)                   |-- createdAt, expiresAt
  |-- outputPath (S3)                     |-- isComplete() -> boolean
  |-- startedAt, completedAt             |-- markChunkComplete(index) -> void

TranscodingStrategy (interface)         StreamingStrategy (interface)
  |-- ParallelTranscodingStrategy          |-- HLSStreamingStrategy
  |     (all renditions in parallel)       |     (master.m3u8 + sub-playlists)
  |-- PriorityTranscodingStrategy          |-- DASHStreamingStrategy
  |     (720p/1080p first, rest later)     |     (manifest.mpd + AdaptationSets)
  |-- LazyTranscodingStrategy              |-- StreamingStrategyFactory
  |     (transcode on first request)       |     (picks HLS or DASH by client)

RecommendationStrategy (interface)      ABRAlgorithm (interface)
  |-- CollaborativeFilteringStrategy       |-- ThroughputBasedABR
  |     (users who watched X also...)      |     (pick highest fitting bandwidth)
  |-- ContentBasedStrategy                 |-- BufferBasedABR
  |     (similar title/tags/genre)         |     (conservative, prevent rebuffer)
  |-- HybridRecommendationStrategy         |-- HybridABR
  |     (combines both approaches)         |     (Netflix/YouTube production choice)

UploadService                           TranscodingService
  |-- initUpload(uploaderId, metadata)    |-- submitTranscodingJob(videoId)
  |     -> UploadSession                  |-- buildResolutionLadder(sourceRes)
  |-- generatePresignedUrls(session)      |-- runParallelTranscode(videoId, jobs)
  |-- onChunkComplete(sessionId, index)   |-- generateManifest(videoId, renditions)
  |-- assembleChunks(sessionId)           |-- onJobComplete(jobId) -> check all done
  |-- validateVideo(videoId)              |-- retryFailedJob(jobId)

StreamingService                        CDNService
  |-- getManifest(videoId, clientInfo)    |-- getSegmentUrl(videoId, res, codec, idx)
  |-- getSegment(videoId, res, idx)       |-- warmCache(videoId, topPoPs)
  |-- selectInitialQuality(bandwidth)     |-- invalidateCache(videoId)
  |-- recordAnalytics(viewEvent)          |-- getOriginShieldUrl(videoId)

RecommendationService                   AppConfig (wiring)
  |-- getRecommendations(userId, n)       |-- creates services, strategies
  |-- updateWatchHistory(userId, videoId) |-- wires upload -> transcode -> stream
  |-- computeSimilarity(videoA, videoB)   |-- configures S3, CDN, DynamoDB, Redis
  |-- trainModel(interactionLogs)         |-- Lambda triggers, Step Functions
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Video` | Core domain entity. Tracks lifecycle from UPLOADING through READY. Holds metadata (title, duration, resolutions, codecs), source S3 path, and manifest URL. State machine with guarded transitions. |
| `VideoSegment` | Immutable value object representing one 2-10 second chunk of a transcoded rendition. Keyed by videoId/resolution/codec/segmentIndex. CDN URL for direct streaming. |
| `UploadSession` | Manages chunked upload state. Tracks which 5MB chunks have been received. Generates presigned S3 URLs. CP consistency -- must not lose chunk state. |
| `TranscodingJob` | One rendition of the transcoding DAG. E.g., "transcode videoId=123 to 720p/H265". 20 jobs per video, all run in parallel. Retryable on failure. |
| `TranscodingStrategy` | Strategy pattern: ParallelTranscodingStrategy (all at once), PriorityTranscodingStrategy (720p/1080p first for fast playback), LazyTranscodingStrategy (transcode on demand). |
| `StreamingStrategy` | Strategy pattern: HLS (Apple devices) vs DASH (Android/web). Factory selects based on client User-Agent. Both produce manifest + segment URLs. |
| `ABRAlgorithm` | Strategy pattern: ThroughputBasedABR (aggressive, maximize quality), BufferBasedABR (conservative, prevent rebuffer), HybridABR (production choice -- buffer overrides throughput when low). |
| `RecommendationStrategy` | Strategy pattern: CollaborativeFiltering (users who watched X also watched Y), ContentBased (similar tags/genre), Hybrid (combines both for cold-start handling). |
| `UploadService` | Orchestrates chunked upload: init session, generate presigned URLs, track chunks, assemble, validate, trigger transcoding. |
| `TranscodingService` | Orchestrates the DAG: determine resolution ladder, submit parallel MediaConvert jobs, generate manifests (HLS/DASH), finalize video status. |
| `StreamingService` | Serves video: return manifest URL, select initial quality, route segment requests through CDN, record analytics (view events, quality metrics). |
| `CDNService` | Manages CDN: build segment URLs, warm cache for popular/new releases, invalidate on re-transcode, origin shield routing. |
| `AppConfig` | Wires everything together. S3 clients, DynamoDB, Redis pools, Step Functions, Lambda triggers, CloudFront distributions. Single entry point for demo. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Upload strategy | Single file upload (simple) | Chunked upload (5MB chunks, parallel, resumable) | **Chunked** -- large video files (2GB+) need parallel upload and resume-on-failure. Presigned URLs bypass API server. |
| Transcoding timing | Eager (all renditions on upload) | Lazy (transcode on first request) | **Priority + lazy hybrid** -- transcode 480p/720p/1080p immediately (covers 90% of views). 240p and 4K on demand. |
| Streaming protocol | HLS (Apple ecosystem) | DASH (open standard, MPEG) | **Both** -- HLS for Apple devices, DASH for Android/web. Same segments, different manifests. Factory selects by client. |
| ABR algorithm | Throughput-based (maximize quality) | Buffer-based (prevent rebuffer) | **Hybrid** -- throughput picks quality, buffer overrides when low. Buffer < 5s forces lowest quality regardless of bandwidth. |
| Codec strategy | Single codec (H264, universal) | Multi-codec (H264 + H265 + VP9 + AV1) | **Multi-codec** -- serve AV1 to modern browsers (60% bandwidth savings), H264 fallback for old devices. Per-view savings compound. |
| CDN architecture | Managed CDN (CloudFront) | Custom CDN (Open Connect) | **CloudFront at small/medium scale**. Custom CDN at Netflix/YouTube scale (bandwidth cost forces it). |
| Storage | Single tier (S3 Standard) | Tiered (Standard -> IA -> Glacier) | **Tiered** -- 80% of views in first 7 days. Move to IA at 30d, Glacier at 90d. Saves 60%+ on storage. |
| Segment duration | Short (2s, fast quality switching) | Long (10s, fewer requests, better compression) | **6 seconds** -- balance between fast ABR switching and compression efficiency. Industry standard (Netflix uses 4-6s). |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | TranscodingStrategy (Parallel vs Priority vs Lazy) | Swap transcoding approach without changing pipeline orchestration |
| **Strategy** | StreamingStrategy (HLS vs DASH) | Swap manifest format based on client without changing segment storage |
| **Strategy** | ABRAlgorithm (Throughput vs Buffer vs Hybrid) | Swap quality selection logic without changing player core |
| **Strategy** | RecommendationStrategy (Collaborative vs ContentBased vs Hybrid) | Swap recommendation algorithm for A/B testing |
| **State Machine** | Video.status (UPLOADING -> UPLOADED -> TRANSCODING -> READY) | Enforce valid transitions, prevent invalid states (e.g., can't stream UPLOADING video) |
| **Factory** | StreamingStrategyFactory (picks HLS or DASH by User-Agent) | Encapsulate protocol selection logic, single creation point |
| **Observer** | S3 Event -> SQS -> Lambda (chunk completion, transcode triggers) | Decouple upload from transcoding; event-driven pipeline |
| **Builder** | TranscodingJob.Builder (videoId, resolution, codec, outputPath, priority) | Complex job config with many parameters, validation at build time |
| **Template Method** | Base transcoding pipeline: validate -> split -> transcode -> package -> finalize | Fixed sequence; subclasses override specific steps (e.g., live vs VOD transcoding) |
| **Repository** | VideoRepository, SegmentRepository, UploadSessionRepository | Abstract storage; swap DynamoDB/Redis/S3 implementations |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :13-video-streaming:run
```

---

## Demo Output Preview

```
========================================
  VIDEO STREAMING PLATFORM (YOUTUBE/NETFLIX) DEMO
========================================

--- Video Upload Demo ---
User U001 (Alice) uploads a video: "Java Design Patterns Tutorial" (2 GB, 1080p, H264)
  Initializing upload session...
  UploadSession{id='sess_001', videoId='vid_001', totalChunks=400, chunkSize=5MB}
  Generated 400 presigned S3 URLs (bypassing API server)

  Uploading chunks in parallel (10 concurrent):
    Chunk 001/400 -> s3://uploads/vid_001/chunk_001  [DONE]  (120ms)
    Chunk 002/400 -> s3://uploads/vid_001/chunk_002  [DONE]  (115ms)
    Chunk 003/400 -> s3://uploads/vid_001/chunk_003  [DONE]  (130ms)
    ... (simulating 400 chunks)
    Chunk 400/400 -> s3://uploads/vid_001/chunk_400  [DONE]  (118ms)

  All chunks received. Assembling...
  S3 Multipart Upload Complete: s3://source/vid_001/original.mp4 (2.0 GB)
  Video status: UPLOADING -> UPLOADED

--- Transcoding Pipeline Demo ---
Triggering transcoding DAG for vid_001...
  Source: 1080p, H264, 7 min duration
  Resolution ladder: [240p, 360p, 480p, 720p, 1080p]
  Codecs: [H264, H265, VP9, AV1]
  Total renditions: 5 resolutions x 4 codecs = 20 jobs

  Video status: UPLOADED -> TRANSCODING

  Running 20 transcoding jobs in parallel:
    Job 01: 240p/H264  -> s3://transcoded/vid_001/h264/240p/   [DONE] (2.1 min)
    Job 02: 240p/H265  -> s3://transcoded/vid_001/h265/240p/   [DONE] (3.0 min)
    Job 03: 240p/VP9   -> s3://transcoded/vid_001/vp9/240p/    [DONE] (2.8 min)
    Job 04: 240p/AV1   -> s3://transcoded/vid_001/av1/240p/    [DONE] (8.5 min)
    Job 05: 360p/H264  -> ...                                   [DONE] (2.5 min)
    ... (20 jobs, all parallel)
    Job 20: 1080p/AV1  -> s3://transcoded/vid_001/av1/1080p/   [DONE] (15.2 min)

  Parallel wall-clock time: 15.2 min (= slowest job, 1080p/AV1)

  Generating thumbnails:
    Extracted 7 thumbnails (one per minute)
    Generated sprite sheet (7x1 grid) for seek preview

  Generating manifests:
    HLS:  s3://manifests/vid_001/master.m3u8  (5 sub-playlists per codec)
    DASH: s3://manifests/vid_001/manifest.mpd  (5 AdaptationSets)

  Video status: TRANSCODING -> READY
  Manifest URL: https://cdn.example.com/vid_001/master.m3u8

--- Storage Cost Demo ---
  Source file:   2.0 GB  (S3 Standard: $0.046/month)
  Transcoded renditions (H264 baseline):
    240p:  H264=140MB, H265=70MB, VP9=77MB, AV1=56MB   (343 MB total)
    360p:  H264=294MB, H265=147MB, VP9=162MB, AV1=118MB  (721 MB total)
    480p:  H264=630MB, H265=315MB, VP9=347MB, AV1=252MB  (1.5 GB total)
    720p:  H264=1.26GB, H265=630MB, VP9=693MB, AV1=504MB (3.1 GB total)
    1080p: H264=2.52GB, H265=1.26GB, VP9=1.39GB, AV1=1.01GB (6.2 GB total)
  Total transcoded: 11.8 GB (all renditions)
  Total storage per video: 2.0 + 11.8 = 13.8 GB

  Cost comparison by codec (1080p, 7 min):
    H264: 2.52 GB * $0.085/GB CDN = $0.214 per 1000 views
    AV1:  1.01 GB * $0.085/GB CDN = $0.086 per 1000 views
    Savings: 60% bandwidth reduction with AV1

--- Adaptive Bitrate Streaming Demo ---
User U002 (Bob) plays vid_001...

  Step 1: Fetch manifest
    GET https://cdn.example.com/vid_001/master.m3u8
    Client: Chrome on desktop (supports H265)
    Available streams:
      240p @ 300 Kbps   (h265/240p/playlist.m3u8)
      360p @ 350 Kbps   (h265/360p/playlist.m3u8)
      480p @ 750 Kbps   (h265/480p/playlist.m3u8)
      720p @ 1500 Kbps  (h265/720p/playlist.m3u8)
      1080p @ 3000 Kbps (h265/1080p/playlist.m3u8)

  Step 2: ABR selects initial quality
    Measured bandwidth: 8 Mbps (WiFi)
    Throughput-based: pick 1080p (3 Mbps < 6.4 Mbps threshold = 80% of 8 Mbps)
    Buffer level: 0s (just started)
    Hybrid ABR: start conservatively at 720p, ramp up after 3 segments

  Step 3: Streaming segments
    Segment 1: 720p/h265 (6s, 1.1 MB) -> CDN edge HIT  [2ms]   buffer: 6s
    Segment 2: 720p/h265 (6s, 1.1 MB) -> CDN edge HIT  [3ms]   buffer: 12s
    Segment 3: 1080p/h265 (6s, 2.2 MB) -> ABR upgrades! [4ms]   buffer: 18s
    Segment 4: 1080p/h265 (6s, 2.2 MB) -> CDN edge HIT  [3ms]   buffer: 24s

  Step 4: Bandwidth drops (WiFi -> cellular)
    Measured bandwidth: 2 Mbps (dropped from 8 Mbps)
    Buffer draining: 24s -> 18s -> 12s -> 8s
    ABR detects: throughput (2 Mbps) < current bitrate (3 Mbps)
    Buffer approaching threshold (< 5s danger zone)
    ABR switches: 1080p -> 480p (750 Kbps, safe at 2 Mbps)
    Segment 10: 480p/h265 (6s, 562 KB) -> CDN edge HIT  [3ms]   buffer: recovering
    No rebuffer! Smooth quality transition.

  Step 5: Bandwidth recovers (back to WiFi)
    Measured bandwidth: 10 Mbps
    Buffer level: 20s (healthy)
    ABR ramps up: 480p -> 720p -> 1080p (over 3 segments, gradual)

--- CDN Cache Efficiency Demo ---
Video vid_001 has been viewed 50,000 times...
  Total segments: 70 (7 min / 6s per segment)
  CDN edge cache hit rate: 94.2% (Zipf distribution -- popular video)
  Origin shield hit rate:  5.1% (catches most edge misses)
  S3 origin hits:          0.7% (cold segments only)

  Bandwidth served:
    From edge PoPs:    47,100 views * 70 segments * 1.1 MB = 3.6 TB
    From origin shield:  2,550 views * 70 segments * 1.1 MB = 196 GB
    From S3 origin:        350 views * 70 segments * 1.1 MB = 27 GB

  Cache key format: vid_001/h265/1080p/segment_00042.ts
  (videoId / codec / resolution / segment -- avoids serving wrong quality)

--- Recommendation Demo ---
User U002 (Bob) finishes watching vid_001...
  Watch history updated: [vid_001] (watched 95% -- counts as "completed")

  Generating recommendations (hybrid strategy):
    Collaborative filtering: users who watched vid_001 also watched:
      vid_042 "Spring Boot Microservices" (85% overlap)
      vid_019 "Clean Code Principles"     (72% overlap)
    Content-based: similar tags (java, design-patterns, tutorial):
      vid_033 "SOLID Principles Deep Dive" (tag similarity: 0.89)
      vid_055 "Gang of Four Patterns"      (tag similarity: 0.82)
    Hybrid merge + rank:
      1. vid_042 (collaborative: 0.85, content: 0.71) -> score: 0.81
      2. vid_033 (collaborative: 0.45, content: 0.89) -> score: 0.72
      3. vid_019 (collaborative: 0.72, content: 0.55) -> score: 0.66

========================================
  DEMO COMPLETE -- PROJECT 13 FINISHED!
========================================
```

---

## Quick Reference

```
Chunked upload:     5MB chunks, parallel via presigned S3 URLs, resumable. Client never sends bytes through API server.
Transcoding:        Parallel DAG: 5 resolutions * 4 codecs = 20 renditions. Step Functions orchestrates. Wall-clock = slowest job.
Resolution ladder:  240p (300Kbps), 360p (700Kbps), 480p (1.5Mbps), 720p (3Mbps), 1080p (6Mbps), 4K (15Mbps).
Codecs:             H264 (most compatible), H265 (50% smaller), VP9 (Google), AV1 (60% smaller, newest, expensive to encode).
HLS/DASH:           Manifest -> sub-playlist per resolution -> 6-second segments. Client picks quality, switches at segment boundaries.
ABR:                Hybrid = throughput-based + buffer-based. Buffer < 5s overrides everything (drop to lowest, prevent rebuffer).
CDN:                Multi-tier: edge (< 5ms) -> origin shield (< 30ms) -> S3 (< 100ms). Cache key = videoId/res/codec/segment.
Zipf distribution:  10% of videos = 90% of views. Hot videos always cached at edge. Long-tail falls through to origin.
Open Connect:       Netflix custom CDN. ISP-embedded appliances, 15K+ boxes. 90% traffic served from ISP PoPs. Eliminates CDN cost.
Storage tiering:    S3 Standard (hot, 0-30d) -> S3-IA (warm, 30-90d) -> Glacier (cold, 90d+). 80% of views in first 7 days.
Per-title encoding: Netflix analyzes content complexity. Cartoons = fewer bits than sports at same quality. Saves 20-30% bandwidth.
Segment duration:   6 seconds. Short enough for fast ABR switching, long enough for good compression. Netflix uses 4-6s.
CAP choice:         AP for streaming (serve from cache, tolerate staleness). CP for upload state (chunk tracking must be consistent).
Live streaming:     RTMP ingest -> cloud transcoding -> HLS/DASH packaging -> CDN. 3-10 second latency (trade-off: lower latency = worse compression).
```

---

## What to Improve Later

- [ ] Full Video entity with state machine transitions and validation
- [ ] UploadSession with chunk tracking, presigned URL generation, and assembly
- [ ] TranscodingService with parallel DAG orchestration and priority queue
- [ ] StreamingService with HLS/DASH manifest generation and segment routing
- [ ] ABRAlgorithm implementations (ThroughputBased, BufferBased, Hybrid)
- [ ] CDNService with cache warming, invalidation, and origin shield routing
- [ ] RecommendationService with collaborative filtering and content-based strategies
- [ ] Analytics pipeline for view counts, watch time, rebuffer rates, ABR quality metrics
- [ ] Live streaming support (RTMP ingest, real-time transcoding, low-latency DASH)
- [ ] Content moderation pipeline (Rekognition scan before making video READY)
- [ ] DRM/encryption support (Widevine, FairPlay) for premium content
