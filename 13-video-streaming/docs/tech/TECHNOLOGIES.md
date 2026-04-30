# Technologies -- Video Streaming Platform (YouTube/Netflix)

> Production technology stack for a video streaming platform. For each tech:
> why it fits, key operations, data model, complexity analysis, and how our
> Java implementation maps to the production version.
>
> **Domain-specific:** Video streaming has unique tech requirements -- FFmpeg
> for transcoding, HLS/DASH for adaptive streaming, CDN for edge delivery,
> and ML for recommendations. This doc covers all of them.

---

## Technology Map

```
  +-------------------+     +-------------------+     +-------------------+
  |   Client (App)    |---->|   API Gateway /   |---->|   VideoService    |
  |   Player (HLS)    |     |   Load Balancer   |     |   (Facade)        |
  +-------------------+     +-------------------+     +-------------------+
        |                                                    |
        |  Segment requests                 +--------+-------+--------+--------+
        |                                   |        |       |        |        |
        v                                   v        v       v        v        v
  +----------+                        +--------+ +------+ +------+ +------+ +------+
  |   CDN    |<----- origin pull ---->|  S3    | |Redis | |Post  | |Cassan| |Elast |
  | (Edge    |                        | Video  | |View  | |greSQL| |dra   | |search|
  |  Cache)  |                        | Segs   | |Counts| |Meta  | |Watch | |Search|
  +----------+                        +--------+ +------+ +------+ |Hist  | |Index |
        |                                   ^                      +------+ +------+
        |                                   |
        |                             +----------+
        |                             |  FFmpeg  |
        |                             | Transcode|
        |                             | Workers  |
        |                             +----------+
        |                                   ^
        |                                   |
        |                             +----------+        +-----------+
        +<--- HLS/DASH segments ----->|  Kafka   |        |    ML     |
                                      | Job Queue|        | Recommend |
                                      +----------+        +-----------+
```

---

## 1. FFmpeg -- Transcoding Engine

**THE core technology for video processing.** Every video platform uses FFmpeg
(or a wrapper around it) for transcoding, format conversion, thumbnail
generation, and quality analysis.

### What FFmpeg Does

| Operation | Command | Use Case |
|-----------|---------|----------|
| Transcode resolution | `-vf scale=1280:720` | Generate 720p variant |
| Change codec | `-c:v libx264` | Convert to H.264 |
| Extract thumbnail | `-ss 5 -vframes 1` | Grab frame at 5 seconds |
| Split into segments | `-f segment -segment_time 4` | Create HLS/DASH segments |
| Concatenate | `-f concat -i filelist.txt` | Stitch chunks back together |
| Analyze quality | `-lavfi psnr` | Compare original vs transcoded PSNR |
| Generate manifest | `-f hls -hls_time 4` | Create HLS playlist |

### Key FFmpeg Commands for Video Streaming

#### Transcode to Multiple Resolutions

```bash
# Transcode raw upload to 1080p, 720p, 480p, 360p (H.264)
# In production: each runs on a separate worker node

# 1080p
ffmpeg -i raw_upload.mp4 \
  -vf scale=1920:1080 \
  -c:v libx264 -preset medium -crf 23 \
  -c:a aac -b:a 128k \
  -movflags +faststart \
  output_1080p.mp4

# 720p
ffmpeg -i raw_upload.mp4 \
  -vf scale=1280:720 \
  -c:v libx264 -preset medium -crf 23 \
  -c:a aac -b:a 96k \
  -movflags +faststart \
  output_720p.mp4

# 480p
ffmpeg -i raw_upload.mp4 \
  -vf scale=854:480 \
  -c:v libx264 -preset medium -crf 25 \
  -c:a aac -b:a 64k \
  -movflags +faststart \
  output_480p.mp4

# 360p
ffmpeg -i raw_upload.mp4 \
  -vf scale=640:360 \
  -c:v libx264 -preset medium -crf 28 \
  -c:a aac -b:a 48k \
  -movflags +faststart \
  output_360p.mp4
```

#### Generate HLS Segments

```bash
# Split transcoded video into 4-second HLS segments
ffmpeg -i output_1080p.mp4 \
  -c copy \
  -f hls \
  -hls_time 4 \
  -hls_list_size 0 \
  -hls_segment_filename "1080p/segment_%03d.ts" \
  1080p/playlist.m3u8

# Result:
# 1080p/playlist.m3u8    (HLS playlist)
# 1080p/segment_000.ts   (first 4-second segment)
# 1080p/segment_001.ts   (second 4-second segment)
# ...
```

#### Extract Thumbnail

```bash
# Grab a frame at the 5-second mark as thumbnail
ffmpeg -i raw_upload.mp4 \
  -ss 5 \
  -vframes 1 \
  -vf scale=320:180 \
  thumbnail.jpg
```

#### Multi-Output Single Pass (Production Optimization)

```bash
# Transcode to ALL resolutions in a single pass (saves decode time)
ffmpeg -i raw_upload.mp4 \
  -filter_complex "[0:v]split=4[v1][v2][v3][v4]; \
    [v1]scale=1920:1080[1080p]; \
    [v2]scale=1280:720[720p]; \
    [v3]scale=854:480[480p]; \
    [v4]scale=640:360[360p]" \
  -map "[1080p]" -c:v libx264 -crf 23 -map 0:a -c:a aac -b:a 128k 1080p.mp4 \
  -map "[720p]"  -c:v libx264 -crf 23 -map 0:a -c:a aac -b:a 96k  720p.mp4 \
  -map "[480p]"  -c:v libx264 -crf 25 -map 0:a -c:a aac -b:a 64k  480p.mp4 \
  -map "[360p]"  -c:v libx264 -crf 28 -map 0:a -c:a aac -b:a 48k  360p.mp4
```

### FFmpeg Parameters Explained

| Parameter | Meaning | Typical Value |
|-----------|---------|---------------|
| `-crf` | Constant Rate Factor (quality, lower = better) | 18-28 |
| `-preset` | Encoding speed vs compression tradeoff | ultrafast, fast, medium, slow, veryslow |
| `-c:v libx264` | Video codec: H.264 | libx264, libx265, libvpx-vp9, libaom-av1 |
| `-c:a aac` | Audio codec: AAC | aac, opus, mp3 |
| `-b:v` | Target video bitrate | 1M, 3M, 5M |
| `-vf scale=W:H` | Resize video | 1920:1080, 1280:720 |
| `-hls_time` | Segment duration (seconds) | 2-6 seconds |
| `-movflags +faststart` | Move moov atom to start for progressive playback | Always use for web |

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Transcoding | `System.out.println("transcoding...")` | `Runtime.exec("ffmpeg ...")` or Kubernetes job |
| Parallelism | `ExecutorService` thread pool | Kubernetes pods, one per resolution |
| Segment storage | In-memory `List<String>` | S3 objects |
| Manifest generation | String building | FFmpeg `-f hls` output |
| Quality analysis | Not implemented | FFmpeg VMAF/PSNR comparison |

### Interview Talking Point

> "FFmpeg is the transcoding workhorse. In production, each resolution is a
> separate Kubernetes pod running `ffmpeg -i input -vf scale=W:H -c:v libx264`.
> We use CRF mode (not constant bitrate) for perceptual quality. A 10-minute
> 1080p video takes ~2 minutes to transcode on a modern CPU."

---

## 2. HLS vs DASH -- Adaptive Streaming Protocols

### What Are They?

Both HLS (HTTP Live Streaming) and DASH (Dynamic Adaptive Streaming over HTTP)
split video into small segments and use a manifest file to tell the player
which segments are available at which quality levels.

### HLS (HTTP Live Streaming)

```
HLS Architecture:

  Master Playlist (master.m3u8)
       |
       +---> 1080p Playlist (1080p/playlist.m3u8)
       |       |
       |       +---> segment_000.ts (4 seconds)
       |       +---> segment_001.ts (4 seconds)
       |       +---> segment_002.ts (4 seconds)
       |
       +---> 720p Playlist (720p/playlist.m3u8)
       |       |
       |       +---> segment_000.ts (4 seconds)
       |       +---> segment_001.ts (4 seconds)
       |       +---> segment_002.ts (4 seconds)
       |
       +---> 480p Playlist (480p/playlist.m3u8)
               |
               +---> segment_000.ts (4 seconds)
               +---> segment_001.ts (4 seconds)
               +---> segment_002.ts (4 seconds)
```

#### Master Playlist (master.m3u8)

```
#EXTM3U
#EXT-X-VERSION:3

#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080p/playlist.m3u8

#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
720p/playlist.m3u8

#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480,CODECS="avc1.4d401e,mp4a.40.2"
480p/playlist.m3u8

#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2"
360p/playlist.m3u8
```

#### Media Playlist (1080p/playlist.m3u8)

```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0

#EXTINF:4.000,
segment_000.ts
#EXTINF:4.000,
segment_001.ts
#EXTINF:4.000,
segment_002.ts
#EXTINF:3.500,
segment_003.ts

#EXT-X-ENDLIST
```

### DASH (Dynamic Adaptive Streaming over HTTP)

```
DASH Architecture:

  MPD (Media Presentation Description) -- manifest.mpd
       |
       +---> AdaptationSet (video)
       |       |
       |       +---> Representation 1080p (5 Mbps)
       |       |       +---> segment_001.m4s
       |       |       +---> segment_002.m4s
       |       |
       |       +---> Representation 720p (3 Mbps)
       |       |       +---> segment_001.m4s
       |       |       +---> segment_002.m4s
       |       |
       |       +---> Representation 480p (1.5 Mbps)
       |               +---> segment_001.m4s
       |               +---> segment_002.m4s
       |
       +---> AdaptationSet (audio)
               |
               +---> Representation 128kbps (AAC)
                       +---> audio_001.m4s
                       +---> audio_002.m4s
```

#### MPD Manifest (simplified)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     type="static" mediaPresentationDuration="PT10M">
  <Period>
    <AdaptationSet mimeType="video/mp4" segmentAlignment="true">
      <Representation id="1080p" bandwidth="5000000" width="1920" height="1080">
        <SegmentTemplate media="1080p/segment_$Number$.m4s"
                         initialization="1080p/init.mp4"
                         duration="4000" timescale="1000"/>
      </Representation>
      <Representation id="720p" bandwidth="3000000" width="1280" height="720">
        <SegmentTemplate media="720p/segment_$Number$.m4s"
                         initialization="720p/init.mp4"
                         duration="4000" timescale="1000"/>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
```

### HLS vs DASH Comparison

| Feature | HLS | DASH |
|---------|-----|------|
| Developer | Apple | MPEG consortium (open standard) |
| Manifest format | `.m3u8` (text) | `.mpd` (XML) |
| Segment format | `.ts` (MPEG-TS) or `.fmp4` | `.m4s` (fragmented MP4) |
| Segment duration | 2-10 seconds (typically 4-6) | 2-10 seconds (typically 4) |
| DRM support | FairPlay (Apple) | Widevine (Google), PlayReady (Microsoft) |
| Browser support | Safari native, others via JS | All via JS (dash.js, Shaka) |
| iOS support | Native | Requires JS player |
| Latency | 15-30s (standard), 2-5s (LL-HLS) | 3-10s (standard), 1-3s (LL-DASH) |
| Adoption | YouTube, Twitch, most platforms | Netflix, most Android-first |
| Audio/video | Combined or separate tracks | Always separate (AdaptationSets) |

### Why Most Platforms Use HLS

```
Reason 1: iOS requires HLS
  - Apple mandates HLS for video in the App Store
  - 50%+ of mobile users are on iOS
  - No native DASH support on iOS

Reason 2: HLS has wider CDN support
  - Every CDN supports HLS out of the box
  - DASH requires specific CDN configuration

Reason 3: YouTube uses HLS for playback
  - YouTube adopted HLS for mobile delivery
  - Desktop uses their own DASH variant (YouTube DASH)

Production approach:
  - Transcode once, package for both HLS and DASH
  - Use CMAF (Common Media Application Format) for shared segments
  - fMP4 segments work with both HLS and DASH manifests
```

### Interview Talking Point

> "We use HLS for delivery because iOS mandates it and CDN support is universal.
> The master manifest lists available bitrates; the player picks the best one
> based on network conditions (ABR). Segments are 4 seconds, served from CDN
> edge. In production, we package both HLS and DASH from the same fMP4 segments
> using CMAF."

---

## 3. Video Codecs -- H.264, H.265, VP9, AV1

### Codec Comparison

| Codec | Standard | Compression | Encode Speed | Hardware Support | License |
|-------|----------|-------------|-------------|-----------------|---------|
| H.264 (AVC) | MPEG-4 Part 10 | Baseline | Fast | Universal | Royalty-bearing |
| H.265 (HEVC) | MPEG-H Part 2 | 25-50% better than H.264 | 3-5x slower | Good (newer devices) | Royalty-bearing (complex) |
| VP9 | Google | ~Same as H.265 | 5-10x slower | Good (Chrome, Android) | Royalty-free |
| AV1 | Alliance for Open Media | 30% better than H.265 | 10-50x slower | Limited (newest chips) | Royalty-free |

### Bitrate Comparison (Same Visual Quality, 1080p)

```
Codec Bitrate Ladder at ~Same VMAF Score (93):

  H.264:  5.0 Mbps  |============================|
  H.265:  3.0 Mbps  |================|
  VP9:    2.8 Mbps  |===============|
  AV1:    2.0 Mbps  |==========|

  H.264 = baseline
  H.265 = 40% savings
  VP9   = 44% savings
  AV1   = 60% savings

  But encode time (10-minute video, 1080p, single thread):
  H.264:  ~5 minutes   |====|
  H.265:  ~20 minutes  |==================|
  VP9:    ~40 minutes  |=====================================|
  AV1:    ~120 minutes |=========================================================...|
```

### When to Use Which Codec

| Codec | Use When | Platform |
|-------|----------|----------|
| H.264 | Default, maximum compatibility | Everything |
| H.265 | iOS/macOS, Apple TV, 4K content | Apple ecosystem |
| VP9 | YouTube, Chrome, Android, cost-sensitive | Google ecosystem |
| AV1 | Future-proofing, bandwidth-constrained | Netflix (new titles), YouTube (gradual rollout) |

### Production Encoding Strategy

```
Tier 1 (always, immediate):
  H.264 at 360p, 480p, 720p, 1080p
  Reason: universal compatibility, fast encode

Tier 2 (popular videos, within hours):
  VP9 at 720p, 1080p, 1440p, 4K
  Reason: 40% bandwidth savings for Chrome/Android users

Tier 3 (top 1% most-viewed, within days):
  AV1 at 720p, 1080p, 1440p, 4K
  Reason: 60% bandwidth savings for supporting devices

Decision tree:
  Video uploaded -> Tier 1 immediately
  Video reaches 1K views -> Tier 2 triggered
  Video reaches 100K views -> Tier 3 triggered
  (Netflix: all titles get all tiers upfront -- pre-encode everything)
```

### Interview Talking Point

> "We encode H.264 immediately for universal compatibility. When a video becomes
> popular (>1K views), we re-encode to VP9 for 40% bandwidth savings on
> Chrome/Android. Top 1% videos get AV1 for 60% savings. Netflix pre-encodes
> everything because their catalog is finite -- YouTube cannot because of
> 500 hours/minute upload rate."

---

## 4. CDN -- Content Delivery Network

### CDN Providers

| Provider | Strengths | Users | Edge PoPs |
|----------|-----------|-------|-----------|
| CloudFront | AWS integration, Lambda@Edge | Amazon Prime | 600+ |
| Akamai | Largest network, enterprise | Apple, major media | 4000+ |
| Fastly | Real-time config, Compute@Edge | GitHub, NYT, Stripe | 90+ |
| Cloudflare | DDoS protection, Workers, affordable | Broad adoption | 300+ |
| Google CDN | GCP integration, Interconnect | YouTube (custom CDN) | 200+ |
| Netflix OCA | ISP-embedded, custom hardware | Netflix only | 17,000+ |

### CDN Architecture for Video Streaming

```
Multi-Tier CDN Architecture:

  User Request: GET /videos/vid-42/1080p/segment_005.ts
       |
       v
  +------------------+
  | Edge PoP         |  Tier 1: Closest to user (< 50ms RTT)
  | (City-level)     |  ~600 locations worldwide
  |                  |
  | Cache hit?       |
  |  YES -> serve    |  95% of requests (popular videos)
  |  NO  -> pull     |
  +--------+---------+
           |
           v
  +------------------+
  | Regional Shield  |  Tier 2: Regional aggregation
  | (Continent)      |  ~20 locations
  |                  |
  | Cache hit?       |
  |  YES -> serve    |  4% of requests (long-tail videos)
  |  NO  -> pull     |
  +--------+---------+
           |
           v
  +------------------+
  | Origin           |  Tier 3: Source of truth
  | (S3 + Origin     |  1-3 locations
  |  Shield)         |
  |                  |
  | Always has data  |  1% of requests (very cold content)
  +------------------+
```

### CDN Hit Rate Analysis

```
Zipf Distribution for Video Streaming:

  10% of videos = 90% of views (head)
  Next 20%      = 8% of views  (torso)
  Bottom 70%    = 2% of views  (tail)

  CDN edge cache size: ~100 TB per PoP
  Average segment: 2 MB (4 seconds at 4 Mbps)
  Segments per video: ~150 (10-minute video)
  Unique videos cached: 100 TB / (150 * 2 MB) = ~333,000 videos

  YouTube has 800M+ videos
  333K / 800M = 0.04% of videos cached at edge
  But those 0.04% serve 90% of requests!

  Edge hit rate: ~95% (popular content)
  Regional hit rate: ~99% (edge misses caught here)
  Origin hit rate: 100% (all content in S3)
```

### CDN Costs

```
CDN Cost Model:

  Component           | Cost (approximate)           | Scale Factor
  --------------------+------------------------------+------------------
  Bandwidth (egress)  | $0.02-0.085/GB               | Largest cost
  Requests            | $0.01/10,000 requests        | Minimal
  Invalidation        | $0.005/path                  | Rare for VOD
  Origin shield       | $0.01/GB origin pull         | Reduces origin load
  Lambda@Edge         | $0.60/million invocations    | For auth, redirects

  Example: 1M daily viewers, average 30 min/session, 4 Mbps bitrate
    Data per session: 30 min * 60 s * 4 Mbps / 8 = 900 MB
    Daily egress: 1M * 900 MB = 900 TB
    Monthly egress: 27 PB
    CDN cost at $0.02/GB: 27,000 TB * $0.02 = $540,000/month

  This is why Netflix built Open Connect (ISP-embedded CDN):
    Eliminate CDN vendor markup by placing hardware in ISPs
    Netflix serves ~15% of all internet traffic
    $540K/month at commercial CDN rates would be billions/year at Netflix scale
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| CDN | Not implemented (direct serve) | CloudFront / Akamai / Custom |
| Edge cache | N/A | 100 TB per PoP, Zipf-optimized |
| Origin | In-memory store | S3 behind origin shield |
| Cache key | N/A | `/{videoId}/{resolution}/segment_{N}.ts` |
| Invalidation | N/A | API call or TTL-based |

### Interview Talking Point

> "CDN IS the cache for video streaming. Multi-tier: edge (city) for 95% hit rate,
> regional shield for the next 4%, origin (S3) for the last 1%. Zipf distribution
> means 10% of videos serve 90% of views, so a relatively small edge cache covers
> most traffic. Netflix goes further with Open Connect -- custom servers embedded
> inside ISP networks."

---

## 5. Object Storage -- S3 / GCS

### S3 Layout for Video Segments

```
S3 Bucket Structure:

  s3://video-platform-segments/
  |
  +-- videos/
  |   +-- {videoId}/
  |       +-- raw/
  |       |   +-- original.mp4              (raw upload)
  |       +-- 1080p_h264/
  |       |   +-- playlist.m3u8             (HLS media playlist)
  |       |   +-- segment_000.ts            (4-second segment)
  |       |   +-- segment_001.ts
  |       |   +-- segment_002.ts
  |       +-- 720p_h264/
  |       |   +-- playlist.m3u8
  |       |   +-- segment_000.ts
  |       |   +-- segment_001.ts
  |       +-- 480p_h264/
  |       |   +-- playlist.m3u8
  |       |   +-- segment_000.ts
  |       +-- 360p_h264/
  |       |   +-- playlist.m3u8
  |       |   +-- segment_000.ts
  |       +-- thumbnails/
  |       |   +-- default.jpg               (auto-generated at 5s)
  |       |   +-- custom.jpg                (user-uploaded)
  |       |   +-- sprite.jpg                (thumbnail sprite for scrubbing)
  |       +-- master.m3u8                   (HLS master playlist)
  |
  +-- uploads/                               (temporary, pre-transcode)
      +-- {videoId}/
          +-- chunk_001.part
          +-- chunk_002.part
          +-- chunk_047.part                 (resumable upload chunks)
```

### S3 Storage Classes for Video

```
Tiered Storage Based on View Frequency:

  Video Popularity    | Storage Class    | Cost/GB/month | Access Time
  --------------------+------------------+---------------+------------
  Hot (>100 views/day)| S3 Standard      | $0.023        | < 10ms
  Warm (1-100/day)    | S3 Standard-IA   | $0.0125       | < 10ms
  Cold (<1 view/day)  | S3 Glacier IR    | $0.004        | < 100ms
  Archive (0 views)   | S3 Glacier Deep  | $0.00099      | 12 hours
  Deleted (legal hold)| S3 Glacier Deep  | $0.00099      | 12 hours

  S3 Lifecycle Policy:
    - After 30 days with no views: Standard -> Standard-IA
    - After 90 days with no views: Standard-IA -> Glacier IR
    - After 365 days with no views: Glacier IR -> Glacier Deep Archive
    - On first view after tiering: auto-restore + re-tier to Standard
```

### S3 Operations

| Operation | Use Case | Cost |
|-----------|----------|------|
| PutObject | Store transcoded segment | $0.005/1000 |
| GetObject | CDN origin pull | $0.0004/1000 |
| DeleteObject | Remove deleted video | $0.0004/1000 |
| Multipart Upload | Large raw file upload | Same as PutObject |
| CopyObject | Tier transition (in-place) | $0.005/1000 |
| HeadObject | Check if segment exists | $0.0004/1000 |

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Storage | `ConcurrentHashMap<String, byte[]>` | S3 with lifecycle policies |
| Upload | `map.put(key, bytes)` | S3 Multipart Upload (5 MB chunks) |
| Retrieve | `map.get(key)` | S3 GetObject behind CDN |
| Tiering | Not implemented | S3 Lifecycle Policies |
| Durability | JVM heap (lost on restart) | 11 nines (99.999999999%) |

### Interview Talking Point

> "Video segments live in S3, organized as `/{videoId}/{resolution}/segment_{N}.ts`.
> We use tiered storage: S3 Standard for hot videos, S3-IA after 30 days of
> inactivity, Glacier IR after 90 days. The CDN pulls from S3 on cache miss.
> Raw uploads go through S3 Multipart Upload for resumability."

---

## 6. Redis -- View Counts, Session Management, Caching

### Redis Data Structures for Video Streaming

| Data Structure | Use Case | Key Pattern | Operations |
|---------------|----------|-------------|------------|
| String (counter) | View count | `viewcount:{videoId}` | INCR, GET, GETSET |
| String (counter) | Like count | `likecount:{videoId}` | INCR, DECR, GET |
| Hash | Video metadata cache | `meta:{videoId}` | HSET, HGET, HGETALL |
| Sorted Set | Trending videos | `trending:global` | ZADD, ZREVRANGE |
| Sorted Set | User watch history | `history:{userId}` | ZADD, ZREVRANGE |
| Set | Active streaming sessions | `sessions:{videoId}` | SADD, SREM, SCARD |
| String + TTL | Distributed lock (transcode) | `lock:transcode:{videoId}` | SET NX EX, DEL |
| String + TTL | Recommendation cache | `recs:{userId}` | SET EX, GET |

### View Count Pipeline

```
Real-Time View Counting:

  1. View event arrives
       |
       v
  2. INCR viewcount:{videoId}           <-- O(1), < 1ms
       |
       v
  3. ZINCRBY trending:global 1 {videoId} <-- Update trending score
       |
       v
  4. Background job (every 30s):
       GETSET viewcount:{videoId} 0      <-- Atomic get + reset
       |
       v
  5. Batch UPDATE to PostgreSQL:
       UPDATE videos SET view_count = view_count + {delta}
       WHERE video_id IN (?, ?, ?, ...)
       |
       v
  6. Trending sorted set decays:
       ZRANGEBYSCORE trending:global -inf {threshold}
       ZREM (remove scores below threshold)
```

### Session Management

```
Concurrent Viewer Tracking:

  User starts watching:
    SADD sessions:{videoId} {sessionId}
    EXPIRE sessions:{videoId} 3600        (1 hour TTL)

  User stops watching:
    SREM sessions:{videoId} {sessionId}

  Current viewer count:
    SCARD sessions:{videoId}              <-- O(1)

  Use case:
    - "42,000 people are watching this live"
    - Rate limiting: max 3 concurrent streams per account
    - CDN capacity planning: pre-warm edges for popular live events
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| View counts | `AtomicLong` in memory | Redis INCR + DB flush |
| Trending | `TreeMap<Double, Set<String>>` | Redis Sorted Set |
| Session tracking | `ConcurrentHashMap<String, Set<String>>` | Redis Set with TTL |
| Metadata cache | Not implemented | Redis Hash with 5-min TTL |
| Persistence | Lost on restart | Redis AOF + RDB + replicas |

### Interview Talking Point

> "Redis handles all real-time counters: INCR for view counts (O(1) per view),
> ZINCRBY for trending scores, SADD/SCARD for concurrent viewer counts.
> View counts are flushed to PostgreSQL every 30 seconds in batch.
> This turns 50K writes/second into one batch UPDATE."

---

## 7. Elasticsearch -- Video Search

### Video Search Architecture

```
Search Pipeline:

  Video uploaded + transcoded
       |
       v
  Kafka event: VIDEO_READY
       |
       v
  Search indexer consumes event
       |
       v
  Index document in Elasticsearch:
  {
    "videoId": "vid-42",
    "title": "How to Build a Video Streaming Platform",
    "description": "System design interview prep...",
    "tags": ["system-design", "video", "streaming"],
    "uploaderId": "user-7",
    "uploaderName": "Karan",
    "uploadedAt": "2024-01-15T10:30:00Z",
    "duration": 600,
    "viewCount": 12345,
    "likeCount": 890,
    "resolution": ["1080p", "720p", "480p", "360p"]
  }
       |
       v
  User searches "video streaming design"
       |
       v
  Elasticsearch query:
  {
    "query": {
      "bool": {
        "must": {
          "multi_match": {
            "query": "video streaming design",
            "fields": ["title^3", "description^2", "tags^1.5"]
          }
        },
        "should": [
          { "rank_feature": { "field": "viewCount", "boost": 1.5 } },
          { "rank_feature": { "field": "likeCount", "boost": 1.2 } }
        ]
      }
    }
  }
```

### Search Index Mapping

```json
{
  "mappings": {
    "properties": {
      "videoId":      { "type": "keyword" },
      "title":        { "type": "text", "analyzer": "english" },
      "description":  { "type": "text", "analyzer": "english" },
      "tags":         { "type": "keyword" },
      "uploaderId":   { "type": "keyword" },
      "uploaderName": { "type": "text" },
      "uploadedAt":   { "type": "date" },
      "duration":     { "type": "integer" },
      "viewCount":    { "type": "rank_feature" },
      "likeCount":    { "type": "rank_feature" },
      "resolution":   { "type": "keyword" }
    }
  }
}
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Search | `stream().filter(v -> v.getTitle().contains(query))` | Elasticsearch with analyzers, boosting |
| Indexing | On every save (synchronous) | Kafka -> async indexer |
| Ranking | Not implemented | TF-IDF + view count boost + recency |
| Autocomplete | Not implemented | Edge n-gram tokenizer |
| Latency | O(N) scan | O(log N) inverted index, < 50ms |

### Interview Talking Point

> "Video search uses Elasticsearch with title boosted 3x, description 2x,
> and tags 1.5x. View count and like count are rank features that boost
> popular videos. Index updates are async via Kafka -- a new video is
> searchable within 5-30 seconds of transcoding completion."

---

## 8. ML for Recommendations

### Recommendation Architecture

```
Recommendation Pipeline:

  +------------------+     +------------------+     +------------------+
  | Offline Training |     | Feature Store    |     | Online Serving   |
  | (daily/weekly)   |---->| (Redis + S3)     |---->| (Model Server)   |
  +------------------+     +------------------+     +------------------+
        |                        |                        |
        v                        v                        v
  Train on watch          User embeddings          GET /recommend?
  history, ratings,       Video embeddings         userId=user-42
  click-through data      Interaction features     |
        |                        |                 v
        v                        v           1. Candidate generation
  Collaborative          Real-time              (1000 candidates)
  filtering model        features:           2. Ranking model
  Content-based model    - time of day          (score each)
  Hybrid model           - device type       3. Re-ranking
                         - watch history        (diversity, freshness)
                         - session context   4. Return top 20
```

### Two-Stage Recommendation

```
Stage 1: Candidate Generation (fast, broad)

  Goal: From 800M+ videos, find ~1000 candidates relevant to this user
  Methods:
    a) Collaborative filtering: users who watched similar videos also watched X
    b) Content-based: videos with similar tags, genres, duration
    c) Trending: globally popular right now
    d) Following: from channels the user subscribes to

  Latency budget: < 50ms
  How: approximate nearest neighbor (ANN) search in embedding space
  Tools: FAISS (Facebook), ScaNN (Google), Annoy (Spotify)

Stage 2: Ranking (slower, precise)

  Goal: Score and rank the 1000 candidates
  Features:
    - User embedding (from watch history)
    - Video embedding (from metadata + visual features)
    - Context (time of day, device, session length)
    - Engagement prediction (P(click), P(watch>50%), P(like))

  Model: Deep neural network (YouTube uses a DNN with billions of params)
  Latency budget: < 100ms for 1000 candidates
  Output: ranked list of 1000 videos

Stage 3: Re-Ranking (business rules)

  Goal: Apply diversity and freshness constraints
  Rules:
    - No more than 3 videos from same channel in top 20
    - At least 1 new video (uploaded in last 24h) in top 10
    - Mix 80% personalized + 20% trending (explore/exploit)
    - Filter out already-watched videos

  Output: final 20 recommendations
```

### Collaborative Filtering (Simplified)

```java
// Simplified collaborative filtering for our Java implementation
public class PersonalizedRecommendationStrategy
        implements RecommendationStrategy {

    private final WatchHistoryRepository watchRepo;
    private final VideoRepository videoRepo;

    @Override
    public List<String> recommend(String userId,
                                  List<WatchHistoryEntry> history,
                                  int limit) {
        // 1. Find users with similar watch history
        Set<String> watchedVideoIds = history.stream()
                .map(WatchHistoryEntry::getVideoId)
                .collect(Collectors.toSet());

        // 2. For each video the user watched, find other users who watched it
        Map<String, Integer> candidateScores = new HashMap<>();
        for (String videoId : watchedVideoIds) {
            List<String> otherViewers = videoRepo.findViewersByVideoId(videoId);
            for (String otherUserId : otherViewers) {
                if (otherUserId.equals(userId)) continue;
                // Get videos the other user watched that current user has not
                List<WatchHistoryEntry> otherHistory =
                        watchRepo.getHistory(otherUserId, 50);
                for (WatchHistoryEntry entry : otherHistory) {
                    if (!watchedVideoIds.contains(entry.getVideoId())) {
                        candidateScores.merge(entry.getVideoId(), 1,
                                Integer::sum);
                    }
                }
            }
        }

        // 3. Rank by co-occurrence count
        return candidateScores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Candidate generation | Full scan of watch histories | ANN search (FAISS/ScaNN) |
| Ranking model | Co-occurrence counting | Deep neural network (TF Serving) |
| Feature store | Not implemented | Redis + S3 (user/video embeddings) |
| Training | Not implemented | Spark/TensorFlow, daily batch |
| Serving latency | O(N * M) brute force | < 100ms (ANN + DNN inference) |
| A/B testing | Strategy swap | Experimentation platform |

### Interview Talking Point

> "Recommendations use a two-stage pipeline: candidate generation (ANN search
> in embedding space, ~1000 candidates in <50ms) and ranking (DNN scores each
> candidate, <100ms). Our Java implementation uses collaborative filtering
> with co-occurrence counting -- in production this is replaced by TensorFlow
> Serving with user/video embeddings trained on billions of watch events."

---

## 9. Our Java Simulation vs Production -- Full Comparison

```
+--------------------+----------------------------+----------------------------+
|     Component      |     Our Java Code          |     Production             |
+--------------------+----------------------------+----------------------------+
| Video storage      | ConcurrentHashMap          | S3 + tiered storage        |
| Metadata DB        | ConcurrentHashMap          | PostgreSQL (sharded)       |
| View counts        | AtomicLong                 | Redis INCR + DB flush      |
| Watch history      | ArrayList in-memory        | Cassandra (time-series)    |
| Transcoding        | Thread pool + print stmt   | Kubernetes + FFmpeg pods   |
| Streaming          | Return file paths          | HLS/DASH via CDN           |
| CDN                | Not implemented            | CloudFront/Akamai/Custom   |
| Search             | Stream.filter()            | Elasticsearch cluster      |
| Recommendations    | Co-occurrence counting     | ANN + DNN (TF Serving)     |
| Event bus          | Observer list in JVM       | Kafka topics               |
| Auth               | Not implemented            | JWT + OAuth 2.0            |
| Rate limiting      | Not implemented            | API Gateway + Redis        |
| Monitoring         | System.out.println()       | Prometheus + Grafana       |
| Load balancing     | Not implemented            | ALB + consistent hashing   |
+--------------------+----------------------------+----------------------------+

Why the simulation still matters for interviews:
  - Design patterns are IDENTICAL (Strategy, Builder, Observer, State, etc.)
  - Algorithm logic is the same (ABR selection, state machine transitions)
  - Data flow is the same (upload -> transcode -> store -> stream)
  - Swap ConcurrentHashMap for Redis/S3/PostgreSQL without changing service code
  - Repository pattern makes the swap transparent to the business logic
```

---

## Technology Decision Matrix

| Requirement | Technology | Why Not Alternatives |
|------------|-----------|---------------------|
| Transcoding | FFmpeg (workers) | Industry standard, codec support, CLI automation |
| Adaptive streaming | HLS (primary) + DASH (secondary) | iOS mandates HLS, DASH for Android |
| Video storage | S3 | 11 nines durability, lifecycle policies, CDN origin |
| Metadata | PostgreSQL | ACID for upload state, shardable via Vitess |
| Watch history | Cassandra | Time-series writes, eventual consistency OK |
| View counts | Redis | O(1) INCR, batch flush to DB |
| CDN | CloudFront / Akamai | Global edge network, HLS support, origin pull |
| Search | Elasticsearch | Full-text, relevance scoring, < 50ms |
| Recommendations | TF Serving + FAISS | Embedding-based, ANN for candidate gen |
| Event pipeline | Kafka | Durable, ordered, consumer groups for fanout |
| Container orchestration | Kubernetes | Auto-scale transcoding pods per demand |
