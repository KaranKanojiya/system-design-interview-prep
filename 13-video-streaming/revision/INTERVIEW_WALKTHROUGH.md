# Interview Walkthrough -- Video Streaming Platform (YouTube/Netflix)

> **Total time: ~35 minutes. The Transcoding Deep Dive + ABR/CDN are 60% of this interview.**
> This problem tests chunked upload pipelines, transcoding DAGs (resolution ladder + multi-codec), adaptive bitrate streaming (HLS/DASH + ABR algorithms), CDN architecture (multi-tier caching + Zipf distribution), storage tiering, and the custom CDN trade-off at scale. The hard part is explaining how a single uploaded video becomes 20+ streamable renditions served globally with sub-second startup time and zero rebuffering.

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "What's the scale? 1M DAU or 500M DAU? This determines CDN architecture -- managed CloudFront vs custom CDN like Netflix Open Connect."
- "Is this video-on-demand (YouTube), live streaming (Twitch), or both? VOD and live have very different transcoding pipelines."
- "What's the maximum video length and file size? A 10-minute video vs a 4-hour movie changes upload and transcoding strategy."
- "Do we need DRM/content protection? Netflix requires Widevine/FairPlay encryption. YouTube doesn't for most content."
- "What device coverage? Mobile, desktop, smart TV, game consoles? This determines codec support -- older devices only support H264."
- "Do we need recommendations? YouTube's recommendation engine drives 70% of watch time -- it's a core feature, not a nice-to-have."

### Clarified Scope

```
In scope:   Chunked video upload (resumable, parallel), transcoding pipeline
            (multi-resolution, multi-codec, parallel DAG), adaptive bitrate
            streaming (HLS/DASH, ABR algorithm), CDN architecture (multi-tier),
            video metadata, storage tiering, recommendations (basic),
            analytics (view count, watch time)
Out of scope: Live streaming (mention only), DRM/encryption (mention only),
              ads insertion, comments/social features, content moderation ML,
              creator monetization, subtitle/caption pipeline
```

### What This Signals

You understand this is a **transcoding + streaming + CDN problem** where the hard part is converting one uploaded file into 20+ renditions and serving the right quality to each viewer based on their bandwidth -- globally, with sub-second startup time. You're probing for scale (managed vs custom CDN) and live vs VOD (fundamentally different pipelines).

**Common follow-up:** "Why does live vs VOD matter so much?"

**Answer:** "VOD transcoding is offline and can take 30 minutes -- we transcode once, serve millions of times. Live transcoding must happen in real-time: ingest RTMP stream, transcode to multiple resolutions in < 2 seconds, package into HLS/DASH segments, push to CDN immediately. VOD can use expensive codecs like AV1 (10x encoding cost but 60% smaller files). Live is limited to H264 because there's no time for slow encoding. This fundamentally changes the architecture -- live needs dedicated GPU instances running 24/7, VOD uses batch MediaConvert jobs."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll design two separate pipelines: an **upload + transcoding pipeline** (write path) and a **streaming pipeline** (read path). The upload pipeline uses chunked upload with presigned S3 URLs -- the client uploads 5MB chunks in parallel directly to S3, bypassing the API server. When all chunks arrive, a Step Functions DAG orchestrates parallel transcoding into 20 renditions (5 resolutions * 4 codecs), then generates HLS/DASH manifests. The streaming pipeline serves video segments through a multi-tier CDN: CloudFront edge (< 5ms) -> origin shield (< 30ms) -> S3 origin. The player uses adaptive bitrate streaming -- it downloads a manifest, picks the best quality for the current bandwidth, and switches mid-stream at segment boundaries. The system is AP for streaming (serve from cache, tolerate staleness) and CP for upload state (chunk tracking must be consistent)."

### Draw This Diagram

```
                  +---------------------------+
                  |      Client (Player)      |
                  | Upload: POST chunks to S3 |
                  | Stream: GET manifest + segs|
                  +------------+--------------+
                               |
              1. Upload: presigned S3 URLs (bypass API)
              2. Stream: HTTPS via CDN
                               |
          +--------------------+--------------------+
          |                                         |
    UPLOAD PATH                               STREAM PATH
          |                                         |
          v                                         v
  +----------------+                    +--------------------+
  | Upload Service |                    |   CloudFront CDN   |
  | (ECS Fargate)  |                    |   400+ edge PoPs   |
  | - init session |                    | - edge cache (5ms) |
  | - track chunks |                    | - origin shield    |
  | - trigger DAG  |                    |   (30ms)           |
  +-------+--------+                    +----+----------+----+
          |                                  |          |
  3. Chunks uploaded                   cache |    cache |
     directly to S3                     HIT  |     MISS |
          |                                  |          |
          v                                  v          v
  +----------------+              +-----------+  +----------+
  | S3 (source)    |              |  Origin   |  | S3       |
  | uploads/vid_001|              |  Shield   |  | transcoded|
  | /chunk_001...  |              | (regional)|  | segments |
  +-------+--------+              +-----------+  +----------+
          |
  4. All chunks received
     -> S3 Event -> SQS -> Lambda
          |
          v
  +------------------+
  | Step Functions   |
  | (Transcoding DAG)|
  +--+--+--+--+--+--+
     |  |  |  |  |
  5. 20 parallel transcoding jobs (MediaConvert)
     |  |  |  |  |
     v  v  v  v  v
  +--+--+--+--+--+--+
  | MediaConvert     |
  | Job 1: 240p/H264|   +-> s3://transcoded/vid_001/h264/240p/
  | Job 2: 240p/H265|   +-> s3://transcoded/vid_001/h265/240p/
  | ...              |   +-> ...
  | Job 20:1080p/AV1|   +-> s3://transcoded/vid_001/av1/1080p/
  +------------------+
     |
  6. All jobs done -> generate manifests
     master.m3u8 (HLS) + manifest.mpd (DASH)
     |
  7. Update DynamoDB: status = READY
     manifestUrl = cdn.example.com/vid_001/master.m3u8
     |
  8. SNS event: VIDEO_READY
     -> search indexer, recommendation update, notification

  STREAMING FLOW (when user presses play):

  9. GET cdn.example.com/vid_001/master.m3u8
     -> Client parses manifest, sees available qualities
     -> ABR picks initial resolution based on bandwidth

  10. GET cdn.example.com/vid_001/h265/720p/segment_00001.ts
      -> CDN edge serves from cache (popular video)
      -> Player starts playback

  11. ABR loop: measure throughput per segment
      -> bandwidth drops? switch to lower resolution
      -> bandwidth recovers? ramp up gradually
      -> buffer < 5s? emergency drop to lowest quality

  12. Analytics heartbeat every 30s:
      POST /v1/analytics -> Kinesis -> Redshift
      (videoId, resolution, bufferHealth, rebufferCount)
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| Upload Service | Init upload session, generate presigned URLs, track chunk completion, trigger assembly + transcoding DAG | CP (chunk state must be consistent -- losing a chunk = corrupted video) |
| Step Functions DAG | Orchestrate transcoding: validate -> build resolution ladder -> parallel transcode -> generate thumbnails -> package manifests -> finalize | CP (must track job completion accurately) |
| MediaConvert | Transcode one rendition (resolution + codec combination). 20 parallel jobs per video. Produces segmented output for HLS/DASH. | N/A (managed service, handles retries internally) |
| Streaming Service | Serve manifest URLs, route segment requests, select initial quality, record analytics | AP (serve from cache, tolerate stale metadata) |
| CloudFront CDN | Multi-tier caching: edge PoPs (< 5ms), origin shield (< 30ms), S3 origin fallback | AP (serve cached content during origin outage) |
| DynamoDB (metadata) | Video metadata: title, status, duration, manifest URL, available resolutions/codecs | AP for reads (stale metadata OK), CP for status updates |
| ElastiCache Redis | Hot metadata cache: manifest URLs, video info, user session, recommendation cache | AP (cache miss falls through to DynamoDB) |

### What This Signals

You clearly separate the **write path** (upload -> transcode -> store) from the **read path** (CDN -> stream -> ABR). You show the upload bypasses the API server (presigned URLs), transcoding is parallel (not sequential), and streaming goes through CDN (not your servers). This is the architecture Netflix and YouTube actually use.

**Common follow-up:** "Why presigned URLs instead of uploading through your API server?"

**Answer:** "Three reasons. First, throughput: a 2GB video upload at 10 Mbps takes 27 minutes. If 10K users upload simultaneously, that's 20 TB flowing through my API servers -- I'd need hundreds of instances just for byte proxying. Presigned URLs let S3 handle ingress directly. Second, cost: S3 upload is free (no data transfer charge for ingest), but EC2 data transfer costs $0.09/GB. Third, reliability: S3 multipart upload has built-in retry per chunk. If my API server restarts mid-upload, the client has to start over. With presigned URLs, only the failed chunk retries."

---

## Phase 3: Transcoding Deep Dive (8-10 min)

**This is the star section for video streaming interviews. Spend the most time here.**

### Part A: Resolution Ladder

> "A resolution ladder defines which qualities to produce for each video. The source determines the ceiling -- a 720p source should not be upscaled to 4K. Each resolution has a target bitrate that balances quality and file size."

```
RESOLUTION LADDER (Numbered):

  Source video: 1080p, H264, 7 minutes, 2 GB

      1. Determine resolution ceiling from source:
         Source = 1080p -> max output = 1080p
         (Never upscale: 720p source -> ladder stops at 720p)

      2. Build ladder (industry standard bitrates):
         +----------+--------+------------+------------+
         | Res      | Bitrate| H264 size  | AV1 size   |
         |          | (Kbps) | (7 min)    | (7 min)    |
         +----------+--------+------------+------------+
         | 240p     | 300    | 15.7 MB    | 6.3 MB     |
         | 360p     | 700    | 36.7 MB    | 14.7 MB    |
         | 480p     | 1,500  | 78.7 MB    | 31.5 MB    |
         | 720p     | 3,000  | 157.5 MB   | 63.0 MB    |
         | 1080p    | 6,000  | 315.0 MB   | 126.0 MB   |
         | 4K       | 15,000 | 787.5 MB   | 315.0 MB   |
         +----------+--------+------------+------------+

      3. Per-title encoding (Netflix innovation):
         Fixed bitrate ladder is WASTEFUL.
         A cartoon at 1080p looks great at 2 Mbps.
         A sports broadcast at 1080p needs 8 Mbps.

         Netflix approach:
           Encode each title at multiple bitrates.
           Measure quality (VMAF score, 0-100).
           Find minimum bitrate that achieves VMAF > 93.
           Result: cartoon gets 2 Mbps, sports gets 8 Mbps.

         Savings: 20-30% bandwidth reduction across catalog.

      4. Why multiple codecs?
         +--------+------------------+------------------+------------------+
         | Codec  | Compression      | Encode speed     | Device support   |
         +--------+------------------+------------------+------------------+
         | H264   | baseline (1.0x)  | fast             | everything       |
         | H265   | 50% smaller      | 2x slower        | modern devices   |
         | VP9    | 45% smaller      | 3x slower        | Chrome, Android  |
         | AV1    | 60% smaller      | 10x slower       | newest browsers  |
         +--------+------------------+------------------+------------------+

         Strategy: serve AV1 to modern browsers (60% bandwidth savings)
                   fall back to H264 for old devices
                   Encode cost is ONE-TIME; bandwidth savings are PER-VIEW
                   Popular video with 1M views: AV1 encoding cost paid back in hours
```

### Part B: Transcoding DAG (Parallel Pipeline)

> "Transcoding is orchestrated as a directed acyclic graph (DAG) using Step Functions. The key insight is that all 20 renditions are independent -- they can all run in parallel. Wall-clock time equals the slowest single job, not the sum of all jobs."

```
TRANSCODING DAG (Numbered):

  Video vid_001 upload complete. Trigger transcoding pipeline.
      |
      1. VALIDATE (Lambda, 2 seconds):
         - Verify S3 object exists and is readable
         - Extract metadata: container (MP4), codec (H264), resolution (1080p),
           duration (7 min), frame rate (30fps), audio (AAC, 128 Kbps)
         - Content moderation: Rekognition scan for policy violations
         - Output: { sourceRes: 1080p, duration: 420s, fps: 30, audioCodec: AAC }
      |
      v
      2. BUILD RESOLUTION LADDER (Lambda, 1 second):
         - Source is 1080p -> targets: [240p, 360p, 480p, 720p, 1080p]
         - Codecs: [H264, H265, VP9, AV1]
         - Total jobs: 5 * 4 = 20 renditions
         - Priority: 720p/H264 and 1080p/H264 first (most common playback)
         - Output: List<TranscodingJob> with 20 entries
      |
      v
      3. PARALLEL TRANSCODE (Step Functions Parallel State):
         +--------------------------------------------------+
         |              Step Functions Map State             |
         |  Iterates over 20 TranscodingJob entries         |
         |  MaxConcurrency: 20 (all parallel)               |
         +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
            |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
            v  v  v  v  v  v  v  v  v  v  v  v  v  v  v
         MediaConvert Job per rendition:
           Input:  s3://source/vid_001/original.mp4
           Output: s3://transcoded/vid_001/{codec}/{resolution}/
                   segment_00001.ts, segment_00002.ts, ... (HLS)
                   segment_00001.m4s, segment_00002.m4s, ... (DASH)
                   playlist.m3u8 (HLS sub-playlist)

         Job timing (7-minute source):
           240p/H264:  1.5 min    |   240p/AV1:  6 min
           360p/H264:  2.0 min    |   360p/AV1:  8 min
           480p/H264:  2.5 min    |   480p/AV1:  10 min
           720p/H264:  3.5 min    |   720p/AV1:  12 min
           1080p/H264: 5.0 min    |   1080p/AV1: 15 min  <-- BOTTLENECK

         Wall-clock time: 15 min (= 1080p/AV1, the slowest)
         Sequential would be: 1.5 + 2.0 + ... + 15 = 120+ min
         Parallelism speedup: 8x
      |
      v
      4. GENERATE THUMBNAILS (Lambda, parallel with step 3):
         - Extract frames: 0s, 10s, 30s, 60s, 120s, ... (every 60s)
         - Generate poster image (most visually interesting frame via ML)
         - Generate sprite sheet (10x10 grid, 100 thumbnails for seek preview)
         - Store: s3://thumbnails/vid_001/poster.jpg, sprite.jpg
      |
      v
      5. PACKAGE MANIFESTS (Lambda, after all jobs complete):
         HLS master.m3u8:
           #EXTM3U
           #EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=426x240,CODECS="avc1.42e00a"
           h264/240p/playlist.m3u8
           #EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=640x360,CODECS="avc1.4d401e"
           h264/360p/playlist.m3u8
           #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480,CODECS="avc1.4d401f"
           h264/480p/playlist.m3u8
           #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS="avc1.640020"
           h264/720p/playlist.m3u8
           #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.640028"
           h264/1080p/playlist.m3u8

         (Separate master playlist per codec, or unified with CODECS attribute)

         DASH manifest.mpd:
           <MPD>
             <Period>
               <AdaptationSet mimeType="video/mp4" codecs="hev1.1.6.L120">
                 <Representation bandwidth="150000" width="426" height="240"/>
                 <Representation bandwidth="350000" width="640" height="360"/>
                 ...
               </AdaptationSet>
             </Period>
           </MPD>
      |
      v
      6. FINALIZE (Lambda):
         - DynamoDB: status = READY, manifestUrl, resolutions, codecs
         - SNS publish: VIDEO_READY event
         - CDN warm: push manifest + first 10 segments to edge cache
         - Search: index video in OpenSearch (title, tags, description)
         - Recommendation: update content features for ML model

  ERROR HANDLING:
    If a single job fails (e.g., 720p/AV1 OOM):
      Step Functions retry: exponential backoff, 3 attempts
      If still fails: mark that rendition as unavailable
      Video still READY with remaining 19 renditions
      Background job retries failed rendition later
      Never block the entire video for one failed rendition
```

### Part C: Priority Transcoding (Fast Start)

> "Users don't want to wait 15 minutes before they can share their video. Priority transcoding produces the most common resolutions first (720p, 1080p in H264) so the video becomes playable in 3-5 minutes. The remaining renditions complete in the background."

```
PRIORITY TRANSCODING (Numbered):

      1. PHASE 1 -- FAST START (720p/H264 + 1080p/H264):
         Two highest-impact renditions, fastest codec
         Time: 3-5 minutes
         Video status: READY (partial -- 2 renditions available)
         User can share immediately. Player shows 720p and 1080p options.

      2. PHASE 2 -- CORE RENDITIONS (all resolutions in H264):
         240p, 360p, 480p in H264
         Time: additional 2-3 minutes
         Video now has full resolution ladder in H264

      3. PHASE 3 -- ADVANCED CODECS (H265, VP9, AV1):
         All resolutions in H265, VP9, AV1
         Time: additional 10-15 minutes
         Modern browsers start getting AV1 (60% bandwidth savings)
         Manifest updated dynamically as new renditions complete

  WHY THIS MATTERS:
    Without priority: video is READY after 15 min (all 20 renditions)
    With priority:    video is READY after 3 min (2 renditions)
    User experience:  "My video is live!" in 3 minutes vs 15 minutes
    The remaining renditions complete transparently in the background.
```

**Common follow-up:** "How do you handle transcoding at YouTube scale (500K videos/day)?"

**Answer:** "500K videos/day * 20 renditions = 10M transcoding jobs/day, or ~115 jobs/second. MediaConvert handles this with auto-scaling, but at YouTube's scale, they use custom transcoding clusters (Borg-managed) because managed services are too expensive. The key optimization is that 90% of videos get fewer than 100 views -- for these, we only transcode H264 at 3 resolutions (480p, 720p, 1080p). If a video crosses a view threshold (say 1000 views), we trigger additional renditions (AV1, 4K). This reduces transcoding volume by 70%."

---

## Phase 4: Adaptive Bitrate & CDN (5-7 min)

### Part A: HLS/DASH Deep Dive

> "HLS and DASH are the two standards for adaptive bitrate streaming. Both work the same way: the client downloads a manifest listing available qualities, picks one, downloads segments sequentially, and switches quality at segment boundaries. The difference is format: HLS uses .m3u8 playlists and .ts segments (Apple), DASH uses .mpd manifests and .m4s segments (MPEG standard)."

```
HLS STREAMING FLOW (Numbered):

      1. Client requests master playlist:
         GET https://cdn.example.com/vid_001/master.m3u8
         |
         Response:
           #EXTM3U
           #EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=426x240
           h264/240p/playlist.m3u8
           #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
           h264/720p/playlist.m3u8
           #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
           h264/1080p/playlist.m3u8

      2. ABR algorithm selects initial quality:
         Measured bandwidth: 8 Mbps
         Pick: 1080p (6 Mbps < 0.8 * 8 Mbps = 6.4 Mbps)

      3. Client fetches sub-playlist for 1080p:
         GET cdn.example.com/vid_001/h264/1080p/playlist.m3u8
         |
         Response:
           #EXTM3U
           #EXT-X-TARGETDURATION:6
           #EXTINF:6.0,
           segment_00001.ts
           #EXTINF:6.0,
           segment_00002.ts
           ... (70 segments for 7-minute video)
           #EXT-X-ENDLIST

      4. Client downloads segments sequentially:
         GET cdn.example.com/vid_001/h264/1080p/segment_00001.ts
         (decode, buffer, display -> playback starts after first segment)
         GET cdn.example.com/vid_001/h264/1080p/segment_00002.ts
         ...

      5. Quality switch at segment boundary:
         Bandwidth drops to 2 Mbps.
         ABR: switch to 480p (1.5 Mbps) starting at next segment.
         GET cdn.example.com/vid_001/h264/480p/segment_00015.ts
         (seamless: each segment is independently decodable)

  KEY DIFFERENCE: HLS vs DASH
    +-------------------+-------------------+-------------------+
    | Aspect            | HLS               | DASH              |
    +-------------------+-------------------+-------------------+
    | Manifest format   | .m3u8 (text)      | .mpd (XML)        |
    | Segment format    | .ts or .fmp4      | .m4s (fMP4)       |
    | Origin            | Apple             | MPEG (ISO standard)|
    | Browser support   | Safari native,    | All modern        |
    |                   | others via HLS.js | via dash.js       |
    | DRM               | FairPlay          | Widevine, PlayReady|
    | Segment duration  | 6s default        | 2-6s flexible     |
    +-------------------+-------------------+-------------------+

  INTERVIEW ANSWER:
    "I'd support both: HLS for Apple devices (Safari, iOS) and DASH for
     everything else (Chrome, Android, smart TVs). Same transcoded segments,
     different manifest format. The manifest generation is a thin layer
     on top of the same underlying segments."
```

### Part B: ABR Algorithms

> "The ABR algorithm is the player's brain -- it decides which quality to request for each segment. The goal is to maximize quality while preventing rebuffering (the buffering spinner). There are three approaches."

```
ABR ALGORITHMS COMPARED (Numbered):

  === 1. THROUGHPUT-BASED ABR ===

  Algorithm:
      For each downloaded segment:
        throughput = segment_size_bits / download_time_seconds
        smoothed_bw = EWMA(throughput, alpha=0.3)
        safety_margin = 0.8
        target_bitrate = smoothed_bw * safety_margin
        Pick highest resolution where bitrate <= target_bitrate

  Example:
      Segment downloaded: 1.1 MB in 0.15 seconds
      throughput = 1.1 * 8 / 0.15 = 58.7 Mbps
      EWMA (previous=8 Mbps): 0.3 * 58.7 + 0.7 * 8 = 23.2 Mbps
      Target: 23.2 * 0.8 = 18.6 Mbps
      Pick: 4K (15 Mbps) -- fits within target

  PROS: maximizes quality (always picks highest possible)
  CONS: reacts slowly to sudden drops (EWMA smoothing delays response)
        can cause rebuffer if bandwidth drops faster than EWMA adapts

  === 2. BUFFER-BASED ABR ===

  Algorithm:
      buffer_level = seconds of video buffered ahead
      If buffer < 5s:    EMERGENCY -- drop to lowest resolution
      If buffer 5-10s:   CAUTIOUS -- maintain or drop one level
      If buffer 10-20s:  STABLE -- maintain current resolution
      If buffer > 20s:   UPGRADE -- try next higher resolution

  Example:
      Buffer = 3 seconds (< 5s threshold)
      Action: drop to 240p immediately, regardless of bandwidth
      (prevent rebuffer at all costs)

      Buffer recovers to 15 seconds
      Action: maintain current resolution (stable zone)

      Buffer grows to 25 seconds
      Action: try upgrading one level (480p -> 720p)

  PROS: never rebuffers (buffer-first philosophy)
  CONS: conservative -- may show lower quality than bandwidth allows
        slow to ramp up quality after initial buffering

  === 3. HYBRID ABR (Netflix/YouTube -- PRODUCTION CHOICE) ===

  Algorithm:
      throughput_estimate = EWMA(recent_throughputs)
      buffer_level = current_buffer_seconds

      1. If buffer < 5s:
           -> OVERRIDE: drop to lowest resolution
           (safety first, prevent rebuffer no matter what)

      2. Else if buffer < 10s:
           -> Use throughput-based with conservative margin (0.6)
           target = throughput * 0.6
           Pick highest where bitrate <= target

      3. Else if buffer > 20s:
           -> Use throughput-based with aggressive margin (0.9)
           target = throughput * 0.9
           Pick highest where bitrate <= target

      4. Else (buffer 10-20s):
           -> Use throughput-based with normal margin (0.8)
           target = throughput * 0.8

  WHY HYBRID WINS:
    - Buffer override prevents ALL rebuffers (user satisfaction)
    - Throughput estimation maximizes quality when buffer is healthy
    - Graduated safety margins balance aggressiveness with safety
    - Netflix reports 0.5% rebuffer rate with hybrid ABR
```

### Part C: CDN Architecture (Multi-Tier Caching)

> "Video CDN is different from web CDN because of the Zipf distribution: 10% of videos account for 90% of views. This means a small hot set fits comfortably in edge caches, while the long tail falls through to origin. Multi-tier caching is essential to avoid hammering S3."

```
MULTI-TIER CDN ARCHITECTURE (Numbered):

    User presses play. Client requests a video segment.
        |
        1. DNS Resolution:
           cdn.example.com -> Route 53 latency-based routing
           -> Nearest CloudFront PoP (e.g., Tokyo edge if user is in Japan)
        |
        v
        2. TIER 1: EDGE CACHE (400+ CloudFront PoPs)
           Cache key: vid_001/h265/1080p/segment_00042.ts
           |
           +-- CACHE HIT (popular video, top 10%):
           |     Return segment immediately
           |     Latency: < 5ms (same city as user)
           |     This handles 80-90% of all requests (Zipf)
           |
           +-- CACHE MISS (less popular or new segment):
                 |
                 v
        3. TIER 2: ORIGIN SHIELD (regional, e.g., us-east-1)
           One designated PoP per region acts as mid-tier cache.
           Consolidates cache fills: 50 edge PoPs in EU share one origin shield.
           |
           +-- CACHE HIT:
           |     Return segment, populate requesting edge cache
           |     Latency: < 30ms (regional, not cross-region)
           |     Prevents thundering herd: 50 edge misses become 1 origin hit
           |
           +-- CACHE MISS:
                 |
                 v
        4. TIER 3: S3 ORIGIN (persistent storage)
           S3: s3://transcoded/vid_001/h265/1080p/segment_00042.ts
           Return segment, populate origin shield + requesting edge cache
           Latency: < 100ms
           This path is rare for popular content (< 1% of requests)

    CACHE KEY DESIGN:
      {videoId}/{codec}/{resolution}/segment_{index}.{ext}
      Example: vid_001/h265/1080p/segment_00042.ts

      WHY include codec + resolution in cache key?
        - Same video at different qualities = different files
        - Without resolution: edge might serve 240p segment to 1080p request
        - Without codec: H264 segment served to AV1-requesting client

    ZIPF DISTRIBUTION (why CDN works for video):
      Top 0.1% of videos (10K videos):  50% of all views
      Top 1% of videos   (100K videos): 75% of all views
      Top 10% of videos  (1M videos):   90% of all views
      Bottom 50% of videos:             2% of all views

      Edge cache holds: ~500 TB per PoP (SSD)
      At 1.1 MB per segment, 70 segments per video, 5 renditions:
        500 TB / (1.1 MB * 70 * 5) = ~1.3M videos fully cached per edge PoP
      More than enough for the hot set.

    CACHE WARMING (for new releases):
      "Stranger Things S5" drops at midnight:
        1. 30 minutes before release: push first 100 segments to top 50 PoPs
        2. Release time: millions of requests, all served from edge cache
        3. Without warming: thundering herd on S3 origin (millions of simultaneous misses)
```

**Common follow-up:** "What happens when a viral video goes from 0 to 10M views in an hour?"

**Answer:** "The first few hundred viewers experience cache misses -- segments come from origin via origin shield (100ms vs 5ms). But within minutes, the origin shield caches all segments. Within 10 minutes, edge PoPs in regions with high viewership cache the segments too. The origin shield is the critical protection: without it, 400 edge PoPs all miss simultaneously and hit S3 with 400x the load. With origin shield, 400 edge misses collapse into 1 origin shield miss, which fetches from S3 once. After that, all 400 edges are served from origin shield. This is why Origin Shield is non-negotiable for video CDN."

---

## Phase 5: Scaling & Edge Cases (5-8 min)

### Part A: Viral Video Handling

```
VIRAL VIDEO SCENARIO (Numbered):

  A video goes from 100 views to 10M views in 1 hour.
      |
      1. MINUTE 0-5 (100 views -> 10K views):
         Initial viewers hit cache misses at edge PoPs
         Origin shield caches all 70 segments (7-min video)
         Edge PoPs start caching from origin shield
         User experience: first segment latency 100ms (origin) -> 5ms (edge)
      |
      v
      2. MINUTE 5-15 (10K -> 100K views):
         Top 20 edge PoPs fully cached (NY, LA, London, Tokyo, ...)
         Cache hit rate: 85% (edge) + 12% (origin shield) = 97% from cache
         S3 origin: < 3% of requests
         No degradation in playback quality
      |
      v
      3. MINUTE 15-60 (100K -> 10M views):
         All 400+ edge PoPs cached for popular renditions (720p, 1080p)
         Cache hit rate: 95% edge, 4.5% origin shield, 0.5% S3
         CDN bandwidth: 10M views * 70 segments * 1.1 MB = ~770 TB
         S3 reads: ~3.85 TB (0.5% miss rate) -- trivial for S3
      |
      v
      4. POTENTIAL BOTTLENECK: transcoded variants
         If video was only transcoded in H264 (priority transcoding Phase 1):
           Modern browsers requesting AV1 get H264 fallback
           Background: trigger AV1 transcoding immediately (high priority)
           AV1 renditions available within 15 minutes
           CDN bandwidth drops 40% once AV1 is available
      |
      v
      5. RECOMMENDATION SPIKE:
         Viral video appears in recommendations for related viewers
         Recommendation service must handle 100x normal load
         Solution: cache recommendation results per user for 5 minutes
         Stale recommendations are acceptable (AP)
```

### Part B: Live Streaming (Mention for Bonus Points)

```
LIVE STREAMING vs VOD DIFFERENCES (Numbered):

  VOD (YouTube model):               Live (Twitch model):
    Upload -> transcode -> store       Ingest -> real-time transcode -> serve
    Transcode offline (30 min OK)      Transcode in < 2 seconds
    All segments available upfront     Segments produced in real-time
    Seek anywhere                      Can only seek in DVR window
    AV1 encoding (best compression)    H264 only (fast enough for real-time)
    CDN cache forever                  CDN cache for seconds (segments expire)

  LIVE STREAMING FLOW (Numbered):

      1. Streamer starts broadcast:
         OBS -> RTMP ingest endpoint (Elemental MediaLive)
         RTMP: low-latency push protocol, industry standard for ingest

      2. Real-time transcoding (Elemental MediaLive):
         RTMP stream -> transcode to 3-4 resolutions (H264 only)
         480p, 720p, 1080p in real-time
         Latency budget: < 2 seconds per segment

      3. Packaging (Elemental MediaPackage):
         Segments packaged as HLS/DASH (6-second segments for VOD, 2-second for live)
         Manifest updated every 2 seconds with new segment URL
         Client polls manifest every 2 seconds for new segments

      4. CDN delivery:
         Segments pushed to CloudFront immediately
         TTL: 2-6 seconds (segments expire quickly)
         Edge cache: critical for live (10K concurrent viewers on same stream)

      5. Client playback:
         Player fetches manifest -> gets latest segment URLs
         Downloads newest segments, plays in order
         Latency: 3-10 seconds behind live (trade-off: lower = worse compression)

  LOW-LATENCY LIVE (< 3 seconds):
    Use CMAF (Common Media Application Format) + chunked transfer encoding
    Segments split into 200ms "chunks" (vs 6-second segments)
    Player can start playing mid-segment
    Cost: 30x more HTTP requests, higher CDN cost
```

### Part C: Storage Cost Management

```
STORAGE TIERING STRATEGY (Numbered):

  Video lifecycle and access pattern:
      |
      1. DAY 0-7 (HOT):
         80% of total lifetime views happen in first week
         Storage: S3 Standard ($0.023/GB/month)
         All renditions cached at CDN edge
         Full resolution + codec ladder available
      |
      v
      2. DAY 8-30 (WARM):
         15% of lifetime views
         Storage: still S3 Standard (might get occasional viral resurgence)
         CDN edge cache: evicted for most videos (Zipf)
         Origin shield: may still be cached
      |
      v
      3. DAY 31-90 (COOLING):
         4% of lifetime views
         Storage: S3 Infrequent Access ($0.0125/GB/month -- 46% cheaper)
         Keep: H264 renditions (most compatible)
         Delete: VP9, AV1 renditions (can re-transcode if needed)
         Savings: delete 50% of renditions + IA pricing = 73% cheaper
      |
      v
      4. DAY 91+ (COLD):
         1% of lifetime views
         Storage: S3 Glacier ($0.004/GB/month -- 83% cheaper)
         Keep: source file + 720p/H264 only
         Delete: all other renditions
         On access: restore from Glacier (minutes), re-transcode if needed
         Savings: keep only 15% of original storage + Glacier pricing = 97% cheaper

  COST IMPACT AT SCALE:
    Without tiering: 300 PB * $0.023/GB = $6.9M/month
    With tiering:
      Hot (10 PB):    $230K
      Warm (30 PB):   $690K
      Cool (60 PB):   $750K
      Cold (200 PB):  $800K
      Total:          $2.47M/month (64% savings)

  CONTENT DEDUPLICATION:
    Hash-based fingerprinting (perceptual hash, not byte hash)
    If same video uploaded twice: point to existing transcoded files
    YouTube uses Content ID for this (copyright + dedup)
    Savings: 10-20% storage reduction from re-uploads
```

**Common follow-up:** "What about videos that go viral months after upload?"

**Answer:** "We detect the view spike via Kinesis analytics. When a cold video's view rate crosses a threshold (say 100 views/hour after being at 1 view/day), we trigger: (1) restore from Glacier to S3 Standard, (2) re-transcode missing renditions (AV1, other resolutions deleted during tiering), (3) warm CDN caches proactively. The video is fully available in H264/720p immediately (kept in Glacier). Full rendition set is restored within 30-60 minutes. This is rare enough that the operational cost is trivial."

---

## Phase 6: Tradeoffs (3-5 min)

### Managed CDN vs Custom CDN

| Aspect | CloudFront (Managed) | Open Connect (Custom) |
|--------|---------------------|----------------------|
| Cost at 1 PB/month | ~$85,000 | ~$50,000 (amortized hardware) |
| Cost at 1 EB/month | ~$10,000,000+ | ~$500,000 |
| Ops complexity | Zero (managed) | Full team (hardware, ISP partnerships) |
| Setup time | Minutes | 2-3 years (ISP negotiations) |
| Control | Limited (AWS configs) | Full (custom routing, caching) |
| Best for | < 100M DAU | > 100M DAU (Netflix, YouTube) |

**Say:** "At interview scale (designing for growth), start with CloudFront. It's managed, scales automatically, 400+ PoPs worldwide. But mention: at Netflix/YouTube scale, CDN bandwidth is the #1 cost. Netflix built Open Connect because CloudFront at their volume would cost billions per month. Open Connect costs them ~$7.5M/month. The key trade-off is operational complexity for cost savings -- and it only makes sense above 100M+ DAU."

### Eager vs Lazy Transcoding

| Aspect | Eager (all renditions upfront) | Lazy (transcode on demand) |
|--------|-------------------------------|---------------------------|
| Time to playable | 15-45 min (wait for all) | 3-5 min (priority renditions) |
| Cost per upload | High ($0.50 for 20 renditions) | Low ($0.10 for 3 renditions) |
| Quality variety | All resolutions + codecs from day 1 | Limited initially, grows on demand |
| Wasted compute | 90% of videos get < 100 views (wasted 4K/AV1 transcode) | Only transcode what's watched |
| Best for | Netflix (small catalog, all content watched) | YouTube (long tail, most videos rarely watched) |

**Say:** "YouTube receives 500 hours of video per minute. Eagerly transcoding all 20 renditions for every upload is wasteful -- 90% of videos get fewer than 100 views and never need 4K or AV1. I'd use priority transcoding: produce 480p/720p/1080p in H264 immediately (3-5 minutes, covers 90% of playback), then lazily transcode additional renditions when the video crosses view thresholds. Netflix, with ~15,000 titles that are all professionally produced and widely watched, can justify eager transcoding because every rendition will be used."

### HLS vs DASH

| Aspect | HLS | DASH |
|--------|-----|------|
| Origin | Apple (proprietary) | MPEG (open standard) |
| Browser support | Safari native, others via HLS.js | Chrome/Firefox/Edge native via dash.js |
| DRM | FairPlay (Apple only) | Widevine (Google), PlayReady (Microsoft) |
| Segment format | .ts (MPEG-TS) or .fmp4 | .m4s (fMP4) |
| Live streaming | Good | Better (lower latency possible) |
| Market share | ~50% (iOS dominance) | ~50% (Android + web) |

**Say:** "Support both. Same transcoded segments stored in fMP4 format, which is compatible with both HLS (via CMAF) and DASH. Generate two manifests per video: master.m3u8 for HLS clients and manifest.mpd for DASH clients. The manifest generation is trivial -- it's the same underlying segments with different metadata. Let the client type determine which manifest to serve (factory pattern based on User-Agent)."

### AP vs CP: Where Each Applies

| Component | CAP Choice | Why |
|-----------|-----------|-----|
| Video streaming (segment delivery) | **AP** | Serve from CDN cache even if origin is down. Brief staleness (old view count) is invisible to viewer. |
| Video metadata (title, description) | **AP** | A title update taking 2 seconds to propagate is fine. Availability > consistency. |
| Upload chunk tracking | **CP** | Missing a chunk = corrupted video. Must use strong consistency reads on DynamoDB. |
| View count | **AP** | View count eventual consistency is standard. YouTube shows "1,234 views" even if actual is 1,250. |
| Subscription/billing | **CP** | Payment and subscription state must be strongly consistent. Use RDS with transactions. |
| Recommendation cache | **AP** | Stale recommendations for 5 minutes are perfectly acceptable. |

**Say:** "Almost everything in video streaming is AP. The only CP components are upload chunk tracking (lose a chunk = corrupted video) and subscription/billing (double-charge is unacceptable). For streaming, the CDN can serve cached segments even during a complete origin outage -- viewers watching a video don't notice if the metadata service is down. This is why Netflix separates its control plane (AWS, can go down) from its data plane (Open Connect, independent) -- a control plane outage means you can't browse for new videos, but you can keep watching what you started."

---

## Red Flags (What NOT to Do)

- No chunked upload -- "client uploads the full file to the API server" is a non-starter for multi-GB files
- Sequential transcoding -- "transcode 240p, then 360p, then 480p..." turns 15 minutes into 2 hours
- Single codec -- "just use H264 for everything" wastes 60% bandwidth for modern browsers
- No CDN -- "serve segments from our servers" cannot handle millions of concurrent viewers
- Offset-based segment numbering without proper ABR -- "always serve 1080p" ignores bandwidth variation
- Ignoring storage costs -- not mentioning tiering for a platform with petabytes of video
- No manifest -- "client knows which segment URL to request" ignores the adaptive bitrate protocol
- Polling for upload status -- "client polls every second to check if upload is done" instead of event-driven

## Green Flags (What Interviewers Want to Hear)

- Lead with two pipelines: "upload + transcoding" and "streaming + CDN"
- Chunked upload with presigned URLs: "5MB chunks, parallel, resumable, bypasses API server"
- Resolution ladder with concrete numbers: "240p @ 300Kbps through 4K @ 15Mbps"
- Multi-codec with trade-off: "AV1 is 60% smaller but 10x encoding cost -- worth it for popular videos"
- ABR with three algorithms: "throughput-based, buffer-based, hybrid (buffer overrides throughput when low)"
- CDN multi-tier: "edge (5ms) -> origin shield (30ms) -> S3 (100ms)"
- Zipf distribution: "10% of videos = 90% of views, perfect for caching"
- Open Connect mention: "at Netflix scale, CDN bandwidth forces custom CDN"
- Storage tiering: "S3 Standard -> IA -> Glacier based on view frequency"
- AP for streaming: "serve from cache during outage, CP only for upload state"

---

## 30-Second Elevator Pitch

> "For a YouTube-scale video streaming platform, I'd build two pipelines. **Upload**: chunked upload via **presigned S3 URLs** (5MB chunks, parallel, resumable -- API server never touches the bytes). **Transcoding**: a **parallel DAG** (Step Functions + MediaConvert) producing 20 renditions (5 resolutions * 4 codecs) -- wall-clock time equals the slowest single job, not the sum. Priority transcoding makes the video playable in 3-5 minutes; remaining renditions complete in the background. **Streaming**: **HLS/DASH adaptive bitrate** -- client downloads a manifest, picks quality based on bandwidth, switches mid-stream using **hybrid ABR** (buffer overrides throughput when low, preventing rebuffer). Segments served via a **multi-tier CDN**: edge cache (< 5ms) -> origin shield (< 30ms) -> S3. **10% of videos = 90% of views** (Zipf), so edge caching is extremely effective. At Netflix scale, CDN bandwidth forces **Open Connect** -- ISP-embedded appliances serving 90%+ of traffic. Storage is **tiered**: S3 Standard -> IA -> Glacier. System is **AP for streaming, CP for uploads**."

**Time: Under 30 seconds. Covers: chunked upload, transcoding DAG, ABR, multi-tier CDN, Zipf caching, Open Connect, storage tiering, CAP.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements            2-3 min   (scale, VOD vs live, codecs, DRM, devices)
Phase 2:  High-Level Architecture          5-7 min   (upload pipeline, streaming pipeline, CDN)
Phase 3:  Transcoding Deep Dive            8-10 min  (resolution ladder, codecs, parallel DAG, priority)
Phase 4:  Adaptive Bitrate & CDN           5-7 min   (HLS/DASH, ABR algorithms, multi-tier cache, Zipf)
Phase 5:  Scaling & Edge Cases             5-8 min   (viral video, live streaming, storage tiering)
Phase 6:  Tradeoffs Discussion             3-5 min   (managed vs custom CDN, eager vs lazy, AP vs CP)
-----------------------------------------------------------------------------------
Total:                                     ~35 min
```

If short on time, shorten Phase 5 (scaling/edge cases) and Phase 6 (tradeoffs). Never skip Phase 3 (transcoding deep dive) or Phase 4 (ABR/CDN) -- those are the core of the interview and what differentiates this from generic system design answers.
