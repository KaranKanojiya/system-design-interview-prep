# High-Level Design: Video Streaming Platform (YouTube/Netflix/Hotstar)

> **Difficulty:** HARD | **Interview Time:** 40-50 minutes | **Focus:** CDN architecture, transcoding pipeline, adaptive bitrate streaming, chunked upload, storage at massive scale

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
10. [Video Upload Pipeline Deep Dive](#10-video-upload-pipeline-deep-dive)
11. [Transcoding Deep Dive](#11-transcoding-deep-dive)
12. [Adaptive Bitrate Streaming (ABR)](#12-adaptive-bitrate-streaming-abr)
13. [CDN and Caching](#13-cdn-and-caching)
14. [Concurrency](#14-concurrency)
15. [Scaling](#15-scaling)
16. [Database Choice](#16-database-choice)
17. [CAP Theorem](#17-cap-theorem)
18. [Cloud Services](#18-cloud-services)
19. [Tradeoffs Summary](#19-tradeoffs-summary)
20. [Interview Talking Points](#20-interview-talking-points)

---

## 1. Problem Statement

Design a **Video Streaming Platform** (like YouTube, Netflix, or Hotstar) that supports video upload, transcoding to multiple resolutions and codecs, adaptive bitrate streaming, content delivery via a global CDN, personalized recommendations, search, and social engagement features -- all at a scale of 2 billion registered users and 500 million daily active viewers.

**Why is it needed?**

- Video accounts for over 80% of global internet traffic. YouTube alone serves over 1 billion hours of video per day to 2+ billion monthly active users.
- The engineering challenge spans the full stack: ingesting petabytes of raw video uploads, processing them through computationally expensive transcoding pipelines, distributing the output across a global CDN, and delivering an adaptive playback experience that adjusts in real-time to viewer bandwidth.
- Netflix spends over $1 billion per year on cloud infrastructure, with the majority going to content delivery and transcoding. Efficiency improvements of even 1% translate to $10M+ in savings.
- Viewer expectations are unforgiving: buffering for more than a few seconds causes 25% of viewers to abandon the stream, and a 1-second increase in startup time reduces engagement by 10%.
- The long-tail distribution of content is extreme: the top 10% of videos drive 90% of views, but the remaining 90% still need to be stored, searchable, and streamable on demand.

**Core Workflow -- Video Upload:**

```
Creator uploads a 30-minute 4K video to the platform.

(1) Client splits the 12 GB source file into 5 MB chunks (2,400 chunks)
(2) Client requests presigned upload URLs from Upload Service
(3) Upload Service generates presigned S3 URLs for each chunk
(4) Client uploads chunks in parallel (20 concurrent uploads) directly to S3
(5) As each chunk lands in S3, Upload Service tracks completion
(6) If upload is interrupted (network failure), client resumes from last completed chunk
(7) Once all 2,400 chunks arrive, Upload Service triggers chunk assembly
(8) Assembly Service concatenates chunks into the original source file on S3
(9) Upload Service publishes "video_uploaded" event to Kafka
(10) Transcoding Orchestrator picks up the event
(11) Orchestrator creates a DAG of transcoding tasks:
     - Extract audio track
     - Split video into 10-second segments
     - Transcode each segment to 6 resolutions x 3 codecs = 18 variants
     - Package each variant into HLS (.ts) and DASH (.m4s) segments
     - Generate manifest files (.m3u8 for HLS, .mpd for DASH)
(12) Transcoding workers process tasks in parallel (~50 workers)
(13) Transcoded segments are written to S3 (organized: /videoId/resolution/codec/segment_N)
(14) Once all tasks complete, Orchestrator updates video status to "READY"
(15) CDN pre-warms the first few segments of popular creators' videos
(16) Video appears in creator's channel and becomes searchable
```

**Core Workflow -- Video Playback:**

```
Viewer clicks on a video to watch it.

(1) Client sends GET /videos/{id}/manifest to Streaming Service
(2) Streaming Service returns the master manifest (HLS .m3u8 or DASH .mpd)
    - Contains URLs to variant playlists for each resolution/codec combination
(3) Client ABR algorithm evaluates available bandwidth (~15 Mbps)
(4) Client selects 1080p H.264 variant and requests the variant playlist
(5) Variant playlist contains URLs to individual 10-second segments
(6) Client requests segment 0 from the nearest CDN edge node
(7) CDN edge has the segment cached (popular video) -> served in < 50ms
(8) Client begins playback immediately (startup latency < 2 seconds)
(9) Client continues prefetching next 2-3 segments while playing current one
(10) Network bandwidth drops to 5 Mbps mid-stream
(11) ABR algorithm detects bandwidth change, switches to 480p for next segment
(12) Viewer sees brief quality reduction but zero buffering
(13) Bandwidth recovers to 15 Mbps -> ABR switches back to 1080p
(14) Client reports playback analytics: watch time, quality switches, rebuffering events
(15) Analytics Service aggregates data for recommendation engine and creator dashboard
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Google, Netflix, Amazon, Meta, and every major tech company because it tests the broadest range of systems concepts in a single question:

| Skill Tested                     | What Interviewers Look For                                                      |
|----------------------------------|---------------------------------------------------------------------------------|
| **CDN Architecture**             | Multi-tier CDN (edge/regional/origin), cache keys, anycast routing, pre-warming |
| **Transcoding Pipeline**         | DAG-based task orchestration, parallel processing, codec ladder, cost awareness |
| **Adaptive Bitrate Streaming**   | HLS vs DASH, manifest structure, ABR algorithms, segment sizing tradeoffs       |
| **Chunked Upload**               | Resumable uploads, presigned URLs, parallel chunk upload, chunk assembly         |
| **Storage at Scale**             | Object storage organization, hot/warm/cold tiering, petabyte-scale costs        |
| **Data Modeling**                | Video metadata vs binary storage separation, denormalization for reads           |
| **Concurrency**                  | Concurrent uploads, parallel transcoding, view count aggregation races           |
| **Scale Estimation**             | Storage math (PB), bandwidth math (Tbps), transcoding compute math              |
| **Cost Awareness**               | Transcoding cost per video, CDN bandwidth costs, storage tiering savings         |
| **Production Awareness**         | Cold start, thundering herd on viral videos, DRM, live streaming concepts        |

> **Interview tip**: Start by stating the scale (2B users, 500M DAU, 1B hours/day), then immediately draw the **upload pipeline** (the write path) and the **streaming pipeline** (the read path) as two separate flows. Spend 40% of your time on the transcoding pipeline -- this is the star of this interview. Mention the DAG-based orchestration, resolution ladder, codec choices, and cost. Then walk through ABR streaming and the CDN. The interviewer is looking for depth on transcoding + CDN, not breadth across 20 features.

---

## 2. Scope

### In Scope

| Feature                          | Description                                                                |
|----------------------------------|----------------------------------------------------------------------------|
| Video Upload                     | Chunked, resumable upload with presigned URLs, parallel upload             |
| Transcoding Pipeline             | Multi-resolution, multi-codec transcoding with DAG orchestration           |
| Video Storage                    | Object storage organized by videoId/resolution/codec/segment               |
| Adaptive Bitrate Streaming       | HLS and DASH with manifest generation and ABR switching                    |
| CDN                              | Multi-tier CDN with edge caching, cache-miss fallback, pre-warming         |
| Streaming Service                | Manifest serving, segment delivery, playback session management            |
| Recommendations                  | Collaborative filtering, content-based, and ML-based recommendations       |
| Search                           | Full-text search on titles, descriptions, tags, and channel names          |
| Social Features                  | Like, dislike, comment, subscribe, watch history                           |
| Analytics                        | View counts, watch time, engagement metrics, creator dashboard             |
| Live Streaming (conceptual)      | High-level architecture for live streaming overlay                         |

### Out of Scope

| Feature                          | Reason                                                                     |
|----------------------------------|----------------------------------------------------------------------------|
| DRM (Digital Rights Management)  | Complex topic (Widevine, FairPlay); mention conceptually only              |
| Ad Insertion                     | Server-side ad insertion (SSAI) is a separate system                       |
| Content Moderation               | ML-based flagging pipeline; separate design                                |
| Creator Monetization / Payments  | Covered in Project 11 (Payment System)                                     |
| Notification System              | Covered in Project 03 (Notification System)                                |
| Chat / Live Comments             | Covered in Project 04 (Chat System)                                        |
| Shorts / Vertical Video          | Variant of same architecture; different UI, same backend                   |
| Offline Download                 | DRM-protected download is an extension of DRM scope                        |

---

## 3. Assumptions

### Platform Scale

| Parameter                        | Value                                      | Derivation                                         |
|----------------------------------|--------------------------------------------|---------------------------------------------------|
| Total registered users           | 2 billion                                  | Given (YouTube-scale)                              |
| Total videos on platform         | 1 billion                                  | Given                                              |
| Daily Active Users (DAU)         | 500 million (25% of total)                 | Given                                              |
| Hours of video watched per day   | 1 billion                                  | Given (YouTube reported this in 2023)              |
| Hours of video uploaded per minute| 500                                       | Given (YouTube reported 500 hrs/min in 2022)       |
| Average video duration           | 10 minutes                                 | Given                                              |
| Resolutions per video            | 5 (240p, 360p, 480p, 720p, 1080p)         | Given; 4K for premium content only                 |
| Codecs per video                 | 2 (H.264 + VP9)                            | Universal + Google's open-source                   |
| Segments per minute of video     | 6 (10-second segments)                     | Standard segment duration                          |

### Data Volume

| Parameter                        | Value                                      | Derivation                                         |
|----------------------------------|--------------------------------------------|---------------------------------------------------|
| Videos uploaded per day          | 720,000                                    | 500 hrs/min * 60 min/hr * 24 hrs / 10 min avg     |
| Average source video file size   | 1 GB (1080p, 10 min)                       | ~100 MB/min at 1080p before compression            |
| Daily raw upload volume          | ~720 TB                                    | 720K videos * 1 GB avg                             |
| Transcoded output per video      | 5 resolutions * 2 codecs = 10 variants     | Each variant ~30-80% of source size                |
| Average total storage per video  | ~3 GB (all variants combined)              | Sum of all resolution+codec combinations           |
| Daily transcoded storage added   | ~2.2 PB                                    | 720K * 3 GB                                        |
| Total storage (1B videos)        | ~3 exabytes (EB)                           | 1B * 3 GB average                                  |
| Segments per video               | 60 (10-min video / 10-sec segments)        | Per variant; 60 * 10 = 600 segments total          |
| Total segments on platform       | ~600 billion                               | 1B videos * 600 segments                           |

### Bandwidth

| Parameter                        | Value                                      | Derivation                                         |
|----------------------------------|--------------------------------------------|---------------------------------------------------|
| Average streaming bitrate        | 5 Mbps (mix of resolutions)                | Weighted average across all viewers                |
| Concurrent viewers (peak)        | ~50 million                                | 500M DAU, ~10% concurrent at peak                  |
| Peak egress bandwidth            | 250 Tbps                                   | 50M * 5 Mbps                                      |
| CDN cache hit ratio              | 95%                                        | Hot content served from edge                       |
| Origin bandwidth (cache misses)  | ~12.5 Tbps                                 | 5% of 250 Tbps                                    |

### Back-of-the-Envelope: Latency Budget

```
Video Playback Startup (end-to-end):    Target < 2 seconds
  (1) DNS resolution (CDN anycast):            50-100 ms
  (2) TCP + TLS handshake:                     50-100 ms
  (3) Manifest request + response:             50-100 ms
  (4) First segment request + response:       100-300 ms
  (5) Buffering (1-2 segments):              200-500 ms
  (6) Decoder initialization:                  50-100 ms
  (7) First frame rendered:                    50-100 ms
  ------------------------------------------------
  Total:                                      550-1300 ms (within 2s target)

Transcoding Latency:                    Target < 30 minutes per video
  (1) Event pickup from Kafka:                  100-500 ms
  (2) Audio extraction:                         10-30 seconds
  (3) Video segmentation (split into chunks):   10-30 seconds
  (4) Parallel transcoding (6 resolutions):     5-20 minutes
      (bottleneck: highest resolution 1080p)
  (5) Segment packaging (HLS + DASH):          30-60 seconds
  (6) Manifest generation:                      1-2 seconds
  (7) CDN pre-warm (for popular creators):     10-30 seconds
  ------------------------------------------------
  Total:                                       ~6-22 minutes

Upload Latency (1 GB video):            Target < 10 minutes
  (1) Client-side chunking (200 x 5MB):        5-10 seconds
  (2) Presigned URL generation (200 URLs):      200-500 ms
  (3) Parallel chunk upload (20 concurrent):    3-8 minutes (on 20 Mbps upload)
  (4) Chunk assembly:                           10-30 seconds
  ------------------------------------------------
  Total:                                       ~4-9 minutes
```

### Cost Estimates

| Parameter                        | Value                                      |
|----------------------------------|--------------------------------------------|
| Transcoding cost per 10-min video| ~$0.50-$1.50 (5 resolutions, 2 codecs)     |
| Daily transcoding cost           | ~$500K (720K videos * $0.70)               |
| Storage cost per GB/month (S3)   | $0.023 (standard), $0.004 (Glacier)        |
| Monthly storage cost (hot)       | ~$50M (2.2 PB/day * 30 days at S3 standard)|
| CDN cost per GB served           | $0.02-$0.08 (varies by region)             |
| Daily CDN egress                 | ~2.7 PB (1B hours * 5Mbps * 3600s / 8)    |
| Monthly CDN cost                 | ~$4M-$16M                                  |

---

## 4. Functional Requirements

### FR-1: Upload Video
Creators can upload video files up to 12 hours in length and 256 GB in size. The upload is chunked (5 MB per chunk) and resumable -- if the connection drops, only the failed chunks need to be retransmitted. The client uploads chunks directly to object storage using presigned URLs, bypassing the API server to avoid bandwidth bottlenecks. The creator can set title, description, tags, thumbnail, visibility (public/unlisted/private), and category during or after upload.

### FR-2: Stream Video
Viewers can stream any public or authorized video on demand. The system delivers video via HTTP-based adaptive bitrate streaming (HLS or DASH). The client receives a manifest file listing all available quality levels and segment URLs. Playback starts within 2 seconds. Seeking to any position is near-instant (segment-aligned).

### FR-3: Adaptive Bitrate Streaming
The player dynamically adjusts video quality based on the viewer's current network bandwidth and device capabilities. If bandwidth drops, the player switches to a lower resolution seamlessly with zero rebuffering. If bandwidth improves, the player upgrades quality. The viewer can also manually override quality selection.

### FR-4: Recommendations
The home page shows personalized video recommendations based on watch history, liked videos, subscriptions, trending content, and collaborative filtering (users who watched X also watched Y). Recommendations update as the user interacts with the platform. A "related videos" sidebar appears during playback.

### FR-5: Search
Viewers can search for videos by title, description, tags, channel name, and category. Search results are ranked by relevance (text match), popularity (view count, engagement), recency, and personalization. Autocomplete suggestions appear as the user types.

### FR-6: Like / Dislike / Comment
Viewers can like or dislike a video (toggle, idempotent). Viewers can post comments (threaded, up to 2 levels). Like/dislike counts and comment counts are visible on the video page. Engagement metrics are eventually consistent (a few seconds of delay is acceptable).

### FR-7: Watch History
The system records which videos each user has watched, including watch duration and completion percentage. Users can view their watch history, resume partially watched videos from where they left off, and clear their history.

### FR-8: Live Streaming (Conceptual)
The platform supports live streaming where creators broadcast in real-time. Live streams are transcoded on-the-fly to multiple resolutions, packaged into low-latency HLS/DASH segments (2-4 seconds), and distributed via CDN. Live streams can be archived as VOD (video on demand) after the broadcast ends. *(This is a conceptual extension -- the core design focuses on VOD.)*

---

## 5. Non-Functional Requirements

| Requirement                 | Target                              | Rationale                                                             |
|-----------------------------|-------------------------------------|-----------------------------------------------------------------------|
| **Playback Startup Latency**| < 2 seconds (p99)                   | 25% of viewers abandon if startup > 3 seconds                        |
| **Rebuffering Ratio**       | < 1% of playback time               | Rebuffering is the #1 cause of viewer churn                           |
| **Availability**            | 99.99% (52 min downtime/year)       | Global audience, always-on expectation                                |
| **Upload Success Rate**     | > 99.5% (with retries)              | Resumable uploads ensure success even on flaky connections            |
| **Transcoding Latency**     | < 30 minutes for a 10-min video     | Creator expects video to be available within ~30 min of upload        |
| **Search Latency**          | p99 < 200 ms                        | Users expect instant search results                                   |
| **Consistency**             | Eventually consistent (views, likes) | Exact counts not critical; staleness of 5-10 seconds acceptable      |
| **Durability**              | Zero data loss on uploaded videos    | Source video and all transcoded variants stored durably                |
| **Scalability**             | 500M DAU, 1B hours watched/day      | Must handle 10x spike on viral events                                 |
| **CDN Cache Hit Ratio**     | > 95% for video segments             | Miss ratio directly impacts origin bandwidth cost                     |
| **Cost Efficiency**         | Optimize storage tiering + CDN       | Video platform costs are dominated by storage and egress              |

---

## 6. API Design

### 6.1 Upload Video (Initiate)

```
POST /api/v1/videos/upload
Authorization: Bearer <jwt_token>
Content-Type: application/json

Request:
{
  "title": "My Yosemite Hiking Adventure",
  "description": "Exploring Half Dome trail in Yosemite National Park.",
  "tags": ["yosemite", "hiking", "nature", "4K"],
  "category": "TRAVEL",
  "visibility": "PUBLIC",
  "file_name": "yosemite_hike.mp4",
  "file_size_bytes": 1073741824,
  "content_type": "video/mp4",
  "chunk_size_bytes": 5242880
}

Response: 201 Created
{
  "video_id": "vid_8a3f7c2e1b4d",
  "upload_id": "upl_9x7m2k4p8r1w",
  "chunk_count": 205,
  "presigned_urls": [
    {
      "chunk_index": 0,
      "url": "https://storage.example.com/uploads/vid_8a3f7c2e1b4d/chunk_0?X-Amz-Signature=...",
      "expires_at": "2026-04-26T12:00:00Z"
    },
    {
      "chunk_index": 1,
      "url": "https://storage.example.com/uploads/vid_8a3f7c2e1b4d/chunk_1?X-Amz-Signature=...",
      "expires_at": "2026-04-26T12:00:00Z"
    }
  ],
  "status": "UPLOADING",
  "created_at": "2026-04-26T10:30:00Z"
}
```

**Request Parameters:**

| Parameter          | Type    | Required | Description                                        |
|--------------------|---------|----------|----------------------------------------------------|
| `title`            | String  | Yes      | Video title (max 200 chars)                        |
| `description`      | String  | No       | Video description (max 5000 chars)                 |
| `tags`             | List    | No       | Up to 30 tags for search and recommendations       |
| `category`         | Enum    | Yes      | MUSIC, GAMING, TRAVEL, EDUCATION, etc.             |
| `visibility`       | Enum    | No       | PUBLIC, UNLISTED, PRIVATE. Default: PRIVATE        |
| `file_name`        | String  | Yes      | Original file name for reference                   |
| `file_size_bytes`  | Long    | Yes      | Total file size (used to compute chunk count)      |
| `content_type`     | String  | Yes      | MIME type (video/mp4, video/webm, etc.)            |
| `chunk_size_bytes` | Integer | No       | Chunk size. Default: 5 MB (5,242,880 bytes)        |

### 6.2 Complete Upload

```
POST /api/v1/videos/{videoId}/upload/complete
Authorization: Bearer <jwt_token>
Content-Type: application/json

Request:
{
  "upload_id": "upl_9x7m2k4p8r1w",
  "chunk_checksums": [
    { "chunk_index": 0, "md5": "d41d8cd98f00b204e9800998ecf8427e" },
    { "chunk_index": 1, "md5": "a3c2f8e1b4d7c9a0e5f2b8d1c6a3e7f0" }
  ]
}

Response: 200 OK
{
  "video_id": "vid_8a3f7c2e1b4d",
  "status": "PROCESSING",
  "estimated_processing_time_minutes": 15,
  "message": "Video uploaded successfully. Transcoding in progress."
}
```

### 6.3 Stream Video (Get Manifest)

```
GET /api/v1/videos/{videoId}/manifest?protocol=HLS
Authorization: Bearer <jwt_token>

Response: 200 OK
Content-Type: application/vnd.apple.mpegurl

#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e"
https://cdn.example.com/vid_8a3f7c2e1b4d/360p/h264/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480,CODECS="avc1.4d401e"
https://cdn.example.com/vid_8a3f7c2e1b4d/480p/h264/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f"
https://cdn.example.com/vid_8a3f7c2e1b4d/720p/h264/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028"
https://cdn.example.com/vid_8a3f7c2e1b4d/1080p/h264/playlist.m3u8
```

**Query Parameters:**

| Parameter   | Type   | Required | Description                                       |
|-------------|--------|----------|---------------------------------------------------|
| `protocol`  | Enum   | No       | HLS or DASH. Default: HLS                         |
| `codec`     | Enum   | No       | H264, VP9, AV1. Default: auto-detect by client    |

### 6.4 Stream Video (Get Segment)

```
GET /api/v1/videos/{videoId}/stream?resolution=1080p&codec=h264&segment=5
Authorization: Bearer <jwt_token>

Response: 200 OK
Content-Type: video/mp2t
Content-Length: 2621440
[binary video segment data]
```

**Note:** In practice, the client fetches segments directly from CDN URLs listed in the manifest. The API server is not in the streaming hot path.

### 6.5 Get Video Metadata

```
GET /api/v1/videos/{videoId}
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "video_id": "vid_8a3f7c2e1b4d",
  "title": "My Yosemite Hiking Adventure",
  "description": "Exploring Half Dome trail in Yosemite National Park.",
  "channel": {
    "channel_id": "ch_creator_123",
    "name": "Adventure With Karan",
    "subscriber_count": 1250000,
    "is_subscribed": true
  },
  "duration_seconds": 625,
  "view_count": 1482930,
  "like_count": 87432,
  "dislike_count": 1203,
  "comment_count": 4521,
  "tags": ["yosemite", "hiking", "nature", "4K"],
  "category": "TRAVEL",
  "thumbnail_url": "https://cdn.example.com/vid_8a3f7c2e1b4d/thumb_720.jpg",
  "available_resolutions": ["240p", "360p", "480p", "720p", "1080p"],
  "available_codecs": ["h264", "vp9"],
  "published_at": "2026-04-25T14:00:00Z",
  "watch_progress": {
    "last_position_seconds": 312,
    "completion_percentage": 49.9
  }
}
```

### 6.6 Get Recommendations

```
GET /api/v1/recommendations?limit=20&cursor=rec_abc123
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "videos": [
    {
      "video_id": "vid_rec_001",
      "title": "Top 10 Hikes in California",
      "channel_name": "HikingPro",
      "duration_seconds": 845,
      "view_count": 3200000,
      "thumbnail_url": "https://cdn.example.com/vid_rec_001/thumb_720.jpg",
      "published_at": "2026-04-20T10:00:00Z",
      "recommendation_reason": "SIMILAR_TO_WATCHED"
    }
  ],
  "next_cursor": "rec_def456",
  "has_more": true
}
```

**Query Parameters:**

| Parameter   | Type    | Required | Description                                        |
|-------------|---------|----------|----------------------------------------------------|
| `limit`     | Integer | No       | Number of recommendations. Default: 20             |
| `cursor`    | String  | No       | Cursor for pagination                              |
| `context`   | Enum    | No       | HOME, RELATED, TRENDING. Default: HOME             |
| `video_id`  | String  | No       | If context=RELATED, the video to find related to   |

### 6.7 Like / Dislike Video

```
POST /api/v1/videos/{videoId}/like
Authorization: Bearer <jwt_token>
Content-Type: application/json

Request:
{
  "action": "LIKE"
}

Response: 200 OK
{
  "video_id": "vid_8a3f7c2e1b4d",
  "user_action": "LIKE",
  "like_count": 87433,
  "dislike_count": 1203
}
```

**Request Parameters:**

| Parameter | Type | Required | Description                               |
|-----------|------|----------|-------------------------------------------|
| `action`  | Enum | Yes      | LIKE, DISLIKE, NONE (removes prior action)|

### 6.8 Search Videos

```
GET /api/v1/search?q=yosemite+hiking&sort=RELEVANCE&limit=20
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "results": [
    {
      "video_id": "vid_8a3f7c2e1b4d",
      "title": "My Yosemite Hiking Adventure",
      "channel_name": "Adventure With Karan",
      "duration_seconds": 625,
      "view_count": 1482930,
      "thumbnail_url": "https://cdn.example.com/vid_8a3f7c2e1b4d/thumb_720.jpg",
      "published_at": "2026-04-25T14:00:00Z",
      "relevance_score": 0.94
    }
  ],
  "total_results": 14523,
  "next_cursor": "search_page2_abc",
  "has_more": true
}
```

---

## 7. Data Model

### 7.1 Entity Relationship Overview

```
+-------------------+       +-------------------+       +-------------------+
|      User         |       |      Video        |       |  VideoMetadata    |
+-------------------+       +-------------------+       +-------------------+
| user_id      (PK) |<----->| video_id     (PK) |<----->| video_id     (PK) |
| username           |  1:N  | user_id      (FK) |  1:1  | title              |
| email              |       | status             |       | description        |
| display_name       |       | created_at         |       | tags               |
| subscriber_count   |       | published_at       |       | category           |
| created_at         |       | duration_seconds   |       | thumbnail_url      |
+-------------------+       | source_url         |       | language           |
                             | visibility         |       | content_rating     |
                             +-------------------+       +-------------------+
                                    |
                                    | 1:N
                                    v
                             +-------------------+       +-------------------+
                             |   VideoChunk      |       |   Resolution      |
                             +-------------------+       +-------------------+
                             | chunk_id     (PK) |       | resolution_id (PK)|
                             | video_id     (FK) |       | video_id     (FK) |
                             | resolution         |       | resolution_name   |
                             | codec              |       | codec              |
                             | segment_index      |       | bitrate_kbps       |
                             | storage_url        |       | width              |
                             | size_bytes         |       | height             |
                             | duration_ms        |       | storage_url_prefix |
                             +-------------------+       | total_size_bytes   |
                                                         +-------------------+
                                    |
       +----------------------------+----------------------------+
       |                            |                            |
       v                            v                            v
+-------------------+  +-------------------+  +-------------------+
|  TranscodeJob     |  |  WatchHistory     |  |  Engagement       |
+-------------------+  +-------------------+  +-------------------+
| job_id       (PK) |  | user_id      (PK) |  | video_id     (PK) |
| video_id     (FK) |  | video_id     (PK) |  | like_count         |
| status             |  | last_position_sec |  | dislike_count      |
| resolution         |  | completion_pct    |  | comment_count      |
| codec              |  | watched_at        |  | share_count        |
| priority           |  | watch_duration_sec|  | view_count         |
| worker_id          |  +-------------------+  +-------------------+
| started_at         |
| completed_at       |
| error_message      |
+-------------------+
```

### 7.2 Video

```java
public class Video {
    private String videoId;          // Globally unique ID (e.g., Snowflake)
    private String userId;           // Creator's user ID (FK)
    private VideoStatus status;      // UPLOADING, PROCESSING, READY, FAILED, DELETED
    private String sourceUrl;        // S3 URL of the original uploaded file
    private int durationSeconds;     // Duration in seconds (set after processing)
    private Visibility visibility;   // PUBLIC, UNLISTED, PRIVATE
    private Instant createdAt;       // Upload initiation time
    private Instant publishedAt;     // Time when video became READY and visible
}

public enum VideoStatus {
    UPLOADING,     // Chunks still being uploaded
    UPLOADED,      // All chunks received, awaiting assembly
    ASSEMBLING,    // Chunks being concatenated
    PROCESSING,    // Transcoding in progress
    READY,         // All transcodes complete, video is playable
    FAILED,        // Transcoding or assembly failed
    DELETED        // Soft-deleted
}

public enum Visibility {
    PUBLIC,        // Visible to everyone, appears in search and recommendations
    UNLISTED,      // Accessible via direct link only, not in search/recs
    PRIVATE        // Only visible to the creator
}
```

### 7.3 VideoMetadata

```java
public class VideoMetadata {
    private String videoId;          // Same as Video.videoId (1:1)
    private String title;            // Max 200 characters
    private String description;      // Max 5000 characters
    private List<String> tags;       // Up to 30 tags
    private String category;         // MUSIC, GAMING, TRAVEL, EDUCATION, etc.
    private String thumbnailUrl;     // CDN URL for the thumbnail image
    private String language;         // ISO 639-1 (e.g., "en", "hi", "ja")
    private String contentRating;    // G, PG, PG-13, R, etc.
    private int viewCount;           // Approximate (eventually consistent)
    private int likeCount;
    private int dislikeCount;
    private int commentCount;
    private Instant updatedAt;
}
```

### 7.4 VideoChunk (Transcoded Segment)

```java
public class VideoChunk {
    private String chunkId;          // Unique ID for this segment
    private String videoId;          // FK to Video
    private String resolution;       // "240p", "360p", "480p", "720p", "1080p"
    private String codec;            // "h264", "vp9", "av1"
    private int segmentIndex;        // 0-based index of this segment in the variant
    private String storageUrl;       // S3 URL: s3://bucket/vid_xxx/1080p/h264/seg_005.ts
    private long sizeBytes;          // Size of this segment file
    private int durationMs;          // Duration in milliseconds (typically ~10,000)
    private String checksum;         // MD5 or SHA-256 for integrity verification
}
```

### 7.5 Resolution (Variant)

```java
public class Resolution {
    private String resolutionId;     // Unique ID
    private String videoId;          // FK to Video
    private String resolutionName;   // "240p", "360p", "480p", "720p", "1080p"
    private String codec;            // "h264", "vp9"
    private int bitrateKbps;         // Target bitrate (e.g., 800 for 360p H.264)
    private int width;               // Pixel width (e.g., 1920 for 1080p)
    private int height;              // Pixel height (e.g., 1080 for 1080p)
    private String storageUrlPrefix; // S3 prefix: s3://bucket/vid_xxx/1080p/h264/
    private long totalSizeBytes;     // Total size of all segments in this variant
    private int segmentCount;        // Number of segments
    private String manifestUrl;      // HLS/DASH variant playlist URL
}
```

### 7.6 TranscodeJob

```java
public class TranscodeJob {
    private String jobId;            // Unique job ID
    private String videoId;          // FK to Video
    private TranscodeStatus status;  // QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    private String resolution;       // Target resolution for this job
    private String codec;            // Target codec
    private int priority;            // Higher number = higher priority (premium creators)
    private String workerId;         // ID of the worker executing this job
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;     // Error details if FAILED
    private int retryCount;          // Number of retries so far
    private int maxRetries;          // Max retries before marking as FAILED (default: 3)
    private double progressPercent;  // 0.0 to 100.0
}

public enum TranscodeStatus {
    QUEUED,        // Waiting for a worker
    IN_PROGRESS,   // Worker is transcoding
    COMPLETED,     // Successfully transcoded
    FAILED,        // Failed after max retries
    CANCELLED      // Cancelled by user or system
}
```

### 7.7 User

```java
public class User {
    private String userId;           // Globally unique ID
    private String username;         // Unique handle
    private String email;
    private String displayName;
    private String avatarUrl;
    private String channelDescription;
    private long subscriberCount;    // Number of subscribers to this user's channel
    private long videoCount;         // Number of published videos
    private boolean isPremiumCreator;// Premium creators get priority transcoding
    private Instant createdAt;
}
```

### 7.8 WatchHistory

```java
public class WatchHistory {
    private String userId;           // Composite PK part 1
    private String videoId;          // Composite PK part 2
    private int lastPositionSeconds; // Resume point
    private double completionPercent;// 0.0 to 100.0
    private int watchDurationSeconds;// Total seconds watched (may exceed video length if rewatched)
    private Instant watchedAt;       // Last watched timestamp
}
```

### 7.9 Database Table Summary

| Entity          | Primary Store      | Reason                                                    |
|-----------------|--------------------|-----------------------------------------------------------|
| Video           | DynamoDB           | Key-value lookup by videoId; high throughput               |
| VideoMetadata   | DynamoDB           | Co-located with Video for single-read hydration            |
| VideoChunk      | S3 (object storage)| Binary data; stored as files, not DB rows                  |
| Resolution      | DynamoDB           | Metadata about each variant; queried during manifest gen   |
| TranscodeJob    | DynamoDB           | Job tracking; high write throughput during transcoding      |
| User            | DynamoDB           | Key-value lookup by userId                                 |
| WatchHistory    | DynamoDB           | Partition by userId, sort by watchedAt for recent history  |
| Engagement      | Redis (counters)   | High-throughput atomic increments for view/like counts     |
| Search Index    | Elasticsearch      | Full-text search on titles, descriptions, tags             |

---

## 8. High-Level Architecture

```
+-----------------------------------------------------------------------------------+
|                              VIDEO STREAMING PLATFORM                              |
+-----------------------------------------------------------------------------------+

                        UPLOAD PATH (Write)
                        ===================

+----------+     (1) Initiate Upload      +-----------------+
|          |----------------------------->|                 |
|  Creator |     (2) Presigned URLs       |  Upload Service |
|  Client  |<-----------------------------|  (API Server)   |
|          |                              +-----------------+
|          |     (3) Upload Chunks              |
|          |     (parallel, direct)             | (7) Trigger
|          |--------------------------+        |     Assembly
+----------+                          |        v
                                      |  +-----------------+
                                      +->|                 |
                                         |  Object Storage |
                                         |  (S3 / GCS)     |
                                         |                 |
                                         +-----------------+
                                               |
                                               | (8) "video_uploaded" event
                                               v
                                         +-----------------+
                                         |                 |
                                         |  Message Queue  |
                                         |  (Kafka)        |
                                         |                 |
                                         +-----------------+
                                               |
                                               | (9) Consume event
                                               v
                                    +----------------------+
                                    |                      |
                                    |  Transcoding         |
                                    |  Orchestrator        |
                                    |  (DAG Scheduler)     |
                                    |                      |
                                    +----------------------+
                                         |  |  |  |
                          +--------------+  |  |  +--------------+
                          |     (10)        |  |        (10)     |
                          v                 v  v                 v
                    +---------+      +---------+          +---------+
                    | Worker  |      | Worker  |   ...    | Worker  |
                    | (240p)  |      | (720p)  |          | (1080p) |
                    +---------+      +---------+          +---------+
                          |                 |                    |
                          +--------+--------+--------------------+
                                   |
                                   | (11) Store transcoded segments
                                   v
                             +-----------+
                             |           |
                             |  S3       |
                             |  (Output) |
                             |           |
                             +-----------+

                        STREAMING PATH (Read)
                        =====================

+----------+     (1) GET /manifest        +-----------------+
|          |----------------------------->|                 |
|  Viewer  |     (2) Manifest response    | Streaming       |
|  Client  |<-----------------------------| Service         |
|          |                              +-----------------+
|          |
|          |     (3) GET segment (from CDN URL in manifest)
|          |----------------------------+
|          |                            |
|          |     (6) Segment data       |
|          |<-----------+              |
+----------+            |              |
                        |              v
                   +----+----+   +-----------+   +-----------+   +-----------+
                   |         |   |           |   |           |   |           |
                   |  CDN    |<--| CDN       |<--| CDN       |<--| Origin    |
                   |  Edge   |   | Regional  |   | Shield    |   | (S3)     |
                   | (cache  |   | (cache    |   | (single   |   |           |
                   |  hit!)  |   |  miss)    |   |  origin   |   |           |
                   |         |   |           |   |  cache)   |   |           |
                   +---------+   +-----------+   +-----------+   +-----------+
                     (4) HIT       (5) MISS -> fetch from next tier


                        SUPPORTING SERVICES
                        ====================

+-----------------+  +-----------------+  +-----------------+  +-----------------+
|                 |  |                 |  |                 |  |                 |
| Recommendation  |  |  Search         |  |  Analytics      |  |  User           |
| Service         |  |  Service        |  |  Service        |  |  Service        |
|                 |  |  (Elasticsearch)|  |  (Kafka +       |  |                 |
| (ML Pipeline)   |  |                 |  |   ClickHouse)   |  |                 |
+-----------------+  +-----------------+  +-----------------+  +-----------------+
```

### Architecture Flow Summary

```
UPLOAD FLOW:
(1)  Creator initiates upload -> Upload Service validates, creates Video record (UPLOADING)
(2)  Upload Service generates presigned S3 URLs for each chunk
(3)  Creator client uploads chunks directly to S3 (parallel, resumable)
(4)  Upload Service tracks chunk completion via S3 event notifications
(5)  All chunks received -> Upload Service triggers assembly
(6)  Assembly merges chunks into source file on S3
(7)  Upload Service publishes "video_uploaded" event to Kafka
(8)  Transcoding Orchestrator creates DAG of transcoding tasks
(9)  Workers transcode video to 5 resolutions x 2 codecs = 10 variants in parallel
(10) Transcoded segments stored in S3 (organized: /videoId/resolution/codec/segment_N.ts)
(11) Orchestrator generates manifest files (.m3u8 / .mpd), updates status to READY
(12) Video metadata indexed in Elasticsearch for search

STREAMING FLOW:
(1)  Viewer requests manifest -> Streaming Service returns master manifest
(2)  Client ABR algorithm selects initial resolution based on bandwidth estimate
(3)  Client requests segments from CDN edge node
(4)  CDN edge cache HIT -> segment served in < 50ms
(5)  CDN edge cache MISS -> fetch from regional cache -> origin (S3)
(6)  Client plays segments, prefetches next 2-3 segments
(7)  ABR adjusts quality up/down based on measured bandwidth
(8)  Client reports analytics events (view, quality switch, rebuffer)
```

---

## 9. Component Deep Dive

### 9.1 Upload Service

The Upload Service handles the entire video ingestion pipeline from the creator's client to the object storage. It never touches the raw video bytes -- instead, it orchestrates the upload by generating presigned URLs that let the client upload directly to S3.

**Responsibilities:**
- Validate upload request (file size limits, content type, creator quota)
- Create the Video and VideoMetadata records in DynamoDB (status: UPLOADING)
- Generate presigned S3 URLs for each chunk (batch generation for efficiency)
- Track chunk upload progress via S3 event notifications or client callbacks
- Detect and handle upload abandonment (TTL on incomplete uploads)
- Trigger chunk assembly when all chunks are received
- Publish "video_uploaded" event to Kafka after assembly

**Upload Flow (detailed):**

```
+----------+                  +----------------+                  +-----------+
|  Creator |                  | Upload Service |                  |    S3     |
|  Client  |                  |                |                  |           |
+----+-----+                  +-------+--------+                  +-----+-----+
     |                                |                                 |
     | (1) POST /videos/upload        |                                 |
     |  {title, size, chunk_size}     |                                 |
     |------------------------------->|                                 |
     |                                |                                 |
     |                                | (2) Create Video record         |
     |                                |     (status: UPLOADING)         |
     |                                |                                 |
     |                                | (3) Generate presigned URLs     |
     |                                |     for N chunks                |
     |                                |                                 |
     | (4) Return {video_id,          |                                 |
     |     upload_id, presigned_urls} |                                 |
     |<-------------------------------|                                 |
     |                                |                                 |
     | (5) PUT chunk_0 (5MB)          |                                 |
     |  (parallel, direct to S3)      |                                 |
     |------------------------------------------------------->|        |
     |                                |                        |        |
     | (5) PUT chunk_1 (5MB)          |                        |        |
     |------------------------------------------------------->|        |
     |                                |                        |        |
     | (5) PUT chunk_2 (5MB)          |                        |        |
     |------------------------------------------------------->|        |
     |    ... (20 concurrent uploads) |                        |        |
     |                                |                                 |
     |                                | (6) S3 Event Notification       |
     |                                |     (chunk_0 uploaded)          |
     |                                |<--------------------------------|
     |                                |                                 |
     |                                | (6) S3 Event Notification       |
     |                                |     (chunk_1 uploaded)          |
     |                                |<--------------------------------|
     |                                |                                 |
     |                                | (7) All chunks received!        |
     |                                |     Trigger assembly            |
     |                                |                                 |
     |                                | (8) S3 multipart complete       |
     |                                |     or assembly job             |
     |                                |------------------------------->|
     |                                |                                 |
     |                                | (9) Publish to Kafka:           |
     |                                |     "video_uploaded"            |
     |                                |                                 |
     | (10) Status webhook / poll:    |                                 |
     |      "PROCESSING"             |                                 |
     |<-------------------------------|                                 |
     |                                |                                 |
```

**Presigned URL Strategy:**
- URLs are generated in batches (e.g., 50 at a time) with a 1-hour expiry
- If the client needs more time, it requests additional batches
- Each URL is scoped to a specific chunk index (prevents out-of-order writes)
- URLs use PUT with Content-MD5 header requirement for integrity

**Resumable Upload:**
- The Upload Service maintains a bitmap of received chunks in Redis
- If the client disconnects, it can query `GET /videos/{id}/upload/status` to get the list of missing chunks
- Only missing chunks need to be re-uploaded
- Incomplete uploads are garbage-collected after 24 hours (configurable TTL)

```
Resumable Upload State (Redis):

Key: upload:{upload_id}:chunks
Value: BitSet [1, 1, 1, 0, 0, 1, 1, 0, 0, 0, ...]
                ^  ^  ^  ^  ^
                |  |  |  |  |
            chunk: 0  1  2  3  4
            status: done done done pending pending

GET /videos/{videoId}/upload/status
Response:
{
  "upload_id": "upl_9x7m2k4p8r1w",
  "total_chunks": 205,
  "completed_chunks": 142,
  "missing_chunk_indices": [3, 4, 78, 79, 80, ...],
  "status": "UPLOADING"
}
```

### 9.2 Transcoding Service

The Transcoding Service is the **most compute-intensive and architecturally interesting** component. It converts the uploaded source video into multiple resolution and codec variants suitable for adaptive bitrate streaming.

**Responsibilities:**
- Receive "video_uploaded" events from Kafka
- Create a DAG (Directed Acyclic Graph) of transcoding tasks
- Schedule tasks onto a pool of transcoding workers
- Monitor task progress and handle failures (retry up to 3 times)
- Generate HLS/DASH manifests after all variants are transcoded
- Update video status to READY when complete

**Architecture:**

```
+-------------------+
|  Kafka Consumer   |
|  (video_uploaded) |
+--------+----------+
         |
         | (1) New video event
         v
+-------------------+
|                   |
|  Transcoding      |
|  Orchestrator     |
|  (DAG Scheduler)  |
|                   |
+--------+----------+
         |
         | (2) Create DAG of tasks
         v
+-------------------+     +-------------------+     +-------------------+
|                   |     |                   |     |                   |
|  Task Queue       |     |  Task Queue       |     |  Task Queue       |
|  (High Priority)  |     |  (Normal Priority)|     |  (Low Priority)   |
|  [Premium creators|     |  [Regular uploads]|     |  [Re-encodes,     |
|   live streams]   |     |                   |     |   backfills]      |
+--------+----------+     +--------+----------+     +--------+----------+
         |                         |                         |
         v                         v                         v
+-------------------+     +-------------------+     +-------------------+
|  Worker Pool      |     |  Worker Pool      |     |  Worker Pool      |
|  (GPU-enabled)    |     |  (CPU-based)      |     |  (Spot instances) |
|                   |     |                   |     |                   |
|  +-----+ +-----+ |     |  +-----+ +-----+ |     |  +-----+ +-----+ |
|  | W1  | | W2  | |     |  | W3  | | W4  | |     |  | W5  | | W6  | |
|  +-----+ +-----+ |     |  +-----+ +-----+ |     |  +-----+ +-----+ |
|  +-----+ +-----+ |     |  +-----+ +-----+ |     |  +-----+         |
|  | W7  | | W8  | |     |  | W9  | | W10 | |     |  | W11 |         |
|  +-----+ +-----+ |     |  +-----+ +-----+ |     |  +-----+         |
+-------------------+     +-------------------+     +-------------------+
         |                         |                         |
         +------------+------------+-------------------------+
                      |
                      | (3) Write transcoded segments
                      v
               +-------------+
               |             |
               |     S3      |
               |  (Output)   |
               |             |
               +-------------+
```

**Worker Task Execution:**

```
Worker picks up a TranscodeTask from the queue:

(1) Download source segment from S3 (e.g., segment_005 of source video)
(2) Invoke FFmpeg with target parameters:
    ffmpeg -i input_seg005.mp4 \
      -vf scale=1280:720 \         # Target resolution
      -c:v libx264 \               # Codec
      -b:v 2800k \                 # Target bitrate
      -preset medium \             # Speed/quality tradeoff
      -profile:v main \            # H.264 profile
      -g 60 \                      # Keyframe interval (matches segment boundary)
      -sc_threshold 0 \            # Disable scene-change keyframes
      -hls_time 10 \               # Segment duration
      output_seg005_720p_h264.ts
(3) Upload transcoded segment to S3: /vid_xxx/720p/h264/seg_005.ts
(4) Report task completion to Orchestrator
(5) Orchestrator checks if all tasks in the DAG are complete
(6) If yes -> generate manifests, update status to READY
```

### 9.3 Video Storage (Object Storage)

All video data (source files, transcoded segments, thumbnails, manifests) is stored in object storage (S3 or GCS). The storage is organized in a hierarchical key structure for efficient retrieval.

**Storage Organization:**

```
s3://video-platform-bucket/
  |
  +-- uploads/                          # Raw uploads (temporary)
  |   +-- vid_8a3f7c2e1b4d/
  |       +-- chunk_000
  |       +-- chunk_001
  |       +-- ...
  |       +-- chunk_204
  |
  +-- sources/                          # Assembled source files
  |   +-- vid_8a3f7c2e1b4d/
  |       +-- source.mp4                # Original file (kept for re-encoding)
  |
  +-- transcoded/                       # Transcoded output (THE main storage)
  |   +-- vid_8a3f7c2e1b4d/
  |       +-- 240p/
  |       |   +-- h264/
  |       |   |   +-- playlist.m3u8     # Variant playlist
  |       |   |   +-- seg_000.ts
  |       |   |   +-- seg_001.ts
  |       |   |   +-- ...
  |       |   +-- vp9/
  |       |       +-- playlist.m3u8
  |       |       +-- seg_000.webm
  |       |       +-- ...
  |       +-- 360p/
  |       |   +-- h264/
  |       |   +-- vp9/
  |       +-- 480p/
  |       |   +-- h264/
  |       |   +-- vp9/
  |       +-- 720p/
  |       |   +-- h264/
  |       |   +-- vp9/
  |       +-- 1080p/
  |       |   +-- h264/
  |       |   +-- vp9/
  |       +-- master.m3u8              # Master manifest (points to all variants)
  |       +-- master.mpd               # DASH manifest
  |
  +-- thumbnails/
      +-- vid_8a3f7c2e1b4d/
          +-- thumb_default.jpg         # Auto-generated or creator-uploaded
          +-- thumb_120.jpg             # Various sizes for different contexts
          +-- thumb_360.jpg
          +-- thumb_720.jpg
          +-- sprite_sheet.jpg          # Thumbnail sprite for seek preview
```

**Storage Tiering:**

```
+---------------------------------------------------------------------+
|                        STORAGE TIERING                               |
+---------------------------------------------------------------------+
|                                                                     |
|  HOT (S3 Standard)          WARM (S3 IA)          COLD (S3 Glacier) |
|  $0.023/GB/month            $0.0125/GB/month      $0.004/GB/month   |
|                                                                     |
|  +-------------------+    +-------------------+   +----------------+ |
|  | Videos viewed in  |    | Videos not viewed |   | Videos not     | |
|  | the last 30 days  |    | in 30-180 days    |   | viewed in      | |
|  |                   |    |                   |   | 180+ days      | |
|  | ~10% of videos    |    | ~30% of videos    |   | ~60% of videos | |
|  | ~90% of views     |    | ~8% of views      |   | ~2% of views   | |
|  +-------------------+    +-------------------+   +----------------+ |
|                                                                     |
|  Lifecycle rule: after 30 days without access -> move to IA         |
|  Lifecycle rule: after 180 days without access -> move to Glacier   |
|  On access: auto-restore from Glacier (takes 1-5 min for expedited) |
+---------------------------------------------------------------------+
```

### 9.4 CDN (Content Delivery Network)

The CDN is the primary delivery mechanism for all video segments. It brings content closer to viewers, reducing latency and offloading bandwidth from the origin servers.

**Multi-Tier CDN Architecture:**

```
                              VIEWER
                                |
                                | (1) DNS query: cdn.example.com
                                v
                        +---------------+
                        |  Anycast DNS  |
                        |  (Route53 /   |
                        |   Cloudflare) |
                        +-------+-------+
                                |
                                | (2) Resolves to nearest edge POP
                                v
            +-------------------------------------------+
            |              CDN EDGE LAYER               |
            |          (200+ global locations)           |
            |                                           |
            |  +--------+  +--------+  +--------+      |
            |  | Tokyo  |  | Mumbai |  | London |  ... |
            |  | POP    |  | POP    |  | POP    |      |
            |  +---+----+  +---+----+  +---+----+      |
            |      |           |           |            |
            +------+-----------+-----------+------------+
                   |           |           |
                   | (3) Cache MISS (not all POPs have all content)
                   v           v           v
            +-------------------------------------------+
            |          CDN REGIONAL LAYER               |
            |         (20-30 regional hubs)              |
            |                                           |
            |  +--------+  +--------+  +--------+      |
            |  | Asia   |  | India  |  | Europe |  ... |
            |  | Hub    |  | Hub    |  | Hub    |      |
            |  +---+----+  +---+----+  +---+----+      |
            |      |           |           |            |
            +------+-----------+-----------+------------+
                   |           |           |
                   | (4) Cache MISS (rare for popular content)
                   v           v           v
            +-------------------------------------------+
            |          CDN SHIELD / ORIGIN SHIELD       |
            |         (2-3 global locations)             |
            |                                           |
            |  Single point of contact with origin      |
            |  Prevents "thundering herd" on origin     |
            |                                           |
            +-------------------+-----------------------+
                                |
                                | (5) Cache MISS (very rare)
                                v
                        +---------------+
                        |               |
                        |  Origin (S3)  |
                        |               |
                        +---------------+

Cache Hit Rates by Tier:
  Edge:     ~85% hit rate (popular videos, recent segments)
  Regional: ~95% cumulative hit rate
  Shield:   ~99% cumulative hit rate
  Origin:   ~1% of requests actually reach S3
```

**Cache Key Structure:**

```
Cache Key = /{videoId}/{resolution}/{codec}/{segment_index}

Examples:
  /vid_8a3f7c2e1b4d/1080p/h264/seg_005.ts
  /vid_8a3f7c2e1b4d/720p/vp9/seg_012.webm
  /vid_8a3f7c2e1b4d/master.m3u8

TTL Strategy:
  - Video segments:    Long TTL (30 days) — immutable content, never changes
  - Manifest files:    Short TTL (60 seconds) — may update if new resolutions added
  - Thumbnails:        Medium TTL (24 hours) — rarely change after initial upload
  - Metadata (API):    Not cached on CDN — served by API servers with Redis cache
```

**CDN Pre-Warming:**

```
For popular creators (>100K subscribers):

(1) Transcoding completes for new video
(2) System identifies creator as "high-traffic"
(3) Pre-warm job pushes first 3 segments of each resolution to edge POPs
    in the creator's top 10 geographic regions
(4) When fans click the video, the first segments are already cached
(5) This reduces startup latency from ~500ms to ~100ms for popular content

Pre-warm Decision Logic:
  IF creator.subscriberCount > 100,000
    AND video.visibility == PUBLIC
  THEN pre-warm to top 10 regions by subscriber density
  ELSE rely on lazy caching (first viewer triggers cache fill)
```

### 9.5 Streaming Service

The Streaming Service is responsible for serving manifest files, managing playback sessions, and handling the control plane of video delivery. It does **not** serve the actual video bytes (that is the CDN's job).

**Responsibilities:**
- Generate and serve master manifests (HLS .m3u8, DASH .mpd)
- Validate viewer authorization (private/unlisted videos)
- Track playback sessions for analytics
- Handle seek operations (direct viewer to correct segment)
- Manage DRM token generation (conceptual)

**Manifest Generation Flow:**

```
+----------+                  +------------------+                +-----------+
|  Viewer  |                  | Streaming Service|                | DynamoDB  |
|  Client  |                  |                  |                |           |
+----+-----+                  +--------+---------+                +-----+-----+
     |                                 |                                |
     | (1) GET /videos/{id}/manifest   |                                |
     |  ?protocol=HLS                  |                                |
     |-------------------------------->|                                |
     |                                 |                                |
     |                                 | (2) Check video status         |
     |                                 |     and authorization          |
     |                                 |------------------------------->|
     |                                 |                                |
     |                                 | (3) Video READY + PUBLIC       |
     |                                 |<-------------------------------|
     |                                 |                                |
     |                                 | (4) Query available            |
     |                                 |     resolutions + codecs       |
     |                                 |------------------------------->|
     |                                 |                                |
     |                                 | (5) [240p/h264, 360p/h264,    |
     |                                 |      480p/h264, 720p/h264,    |
     |                                 |      1080p/h264, 720p/vp9,    |
     |                                 |      1080p/vp9]               |
     |                                 |<-------------------------------|
     |                                 |                                |
     |                                 | (6) Build master manifest      |
     |                                 |     with CDN URLs              |
     |                                 |                                |
     | (7) Return master manifest      |                                |
     |  (HLS .m3u8 with variant URLs)  |                                |
     |<--------------------------------|                                |
     |                                 |                                |
     | (8) Client ABR selects 720p     |                                |
     |     and fetches variant playlist|                                |
     |     directly from CDN           |                                |
     |                                 |                                |
     | (9) Client fetches segments     |                                |
     |     from CDN (no API involved)  |                                |
     |                                 |                                |
```

**Session Management:**

```java
public class PlaybackSession {
    private String sessionId;        // Unique per playback instance
    private String userId;
    private String videoId;
    private Instant startedAt;
    private String initialResolution; // Resolution selected at start
    private String deviceType;        // MOBILE, TABLET, DESKTOP, TV
    private String userAgent;
    private String ipAddress;
    private String geoLocation;       // Derived from IP for CDN routing
    private List<QualitySwitch> qualitySwitches;
    private List<RebufferEvent> rebufferEvents;
    private int watchDurationSeconds;
}
```

### 9.6 Recommendation Service

The Recommendation Service generates personalized video suggestions for each viewer based on their watch history, explicit signals (likes, subscriptions), implicit signals (watch time, completion rate), and collaborative filtering.

**Recommendation Pipeline:**

```
+-------------------+     +-------------------+     +-------------------+
|                   |     |                   |     |                   |
|  Watch History    |     |  Engagement Data  |     |  Video Metadata   |
|  (DynamoDB)       |     |  (Redis/Kafka)    |     |  (DynamoDB)       |
|                   |     |                   |     |                   |
+--------+----------+     +--------+----------+     +--------+----------+
         |                         |                         |
         +------------+------------+-------------------------+
                      |
                      v
         +---------------------------+
         |                           |
         |   Feature Extraction      |
         |   Pipeline                |
         |                           |
         |   (1) User features:      |
         |       - watch history     |
         |       - liked categories  |
         |       - subscription list |
         |       - time of day       |
         |       - device type       |
         |                           |
         |   (2) Video features:     |
         |       - category, tags    |
         |       - view count        |
         |       - engagement rate   |
         |       - freshness         |
         |       - creator metrics   |
         |                           |
         +-------------+-------------+
                       |
                       v
         +---------------------------+
         |                           |
         |   Candidate Generation    |
         |   (Retrieval Stage)       |
         |                           |
         |   (3) Collaborative       |
         |       filtering:          |
         |       "Users who watched  |
         |        X also watched Y"  |
         |                           |
         |   (4) Content-based:      |
         |       Same category,      |
         |       similar tags,       |
         |       same creator        |
         |                           |
         |   (5) Trending:           |
         |       High velocity of    |
         |       views in last hour  |
         |                           |
         |   -> 500 candidates       |
         +-------------+-------------+
                       |
                       v
         +---------------------------+
         |                           |
         |   Ranking Stage           |
         |   (ML Model Scoring)      |
         |                           |
         |   (6) Score each          |
         |       candidate:          |
         |                           |
         |   score = f(              |
         |     user_embedding,       |
         |     video_embedding,      |
         |     context_features      |
         |   )                       |
         |                           |
         |   -> Top 50 ranked        |
         +-------------+-------------+
                       |
                       v
         +---------------------------+
         |                           |
         |   Re-ranking + Filtering  |
         |                           |
         |   (7) Diversity:          |
         |       No >3 from same     |
         |       category in a row   |
         |                           |
         |   (8) Freshness boost:    |
         |       Recently uploaded   |
         |       from subscriptions  |
         |                           |
         |   (9) Filter:             |
         |       Already watched,    |
         |       blocked creators,   |
         |       content rating      |
         |                           |
         |   -> Final 20 results     |
         +---------------------------+
```

### 9.7 Search Service

The Search Service provides full-text search across video titles, descriptions, tags, channel names, and categories using Elasticsearch.

**Search Architecture:**

```
+----------+                +-----------------+               +----------------+
|  Viewer  |                | Search Service  |               | Elasticsearch  |
|  Client  |                |                 |               |                |
+----+-----+                +--------+--------+               +-------+--------+
     |                               |                                |
     | (1) GET /search?q=yosemite    |                                |
     |------------------------------>|                                |
     |                               |                                |
     |                               | (2) Build ES query:            |
     |                               |   - Multi-match on title,      |
     |                               |     description, tags          |
     |                               |   - Boost: title > tags >      |
     |                               |     description                |
     |                               |   - Function score:            |
     |                               |     view_count, recency        |
     |                               |   - Personalization:           |
     |                               |     preferred categories       |
     |                               |                                |
     |                               | (3) Query Elasticsearch        |
     |                               |------------------------------->|
     |                               |                                |
     |                               | (4) Return ranked results      |
     |                               |<-------------------------------|
     |                               |                                |
     | (5) Return search results     |                                |
     |<------------------------------|                                |
     |                               |                                |

Elasticsearch Index Mapping:
{
  "video_search": {
    "properties": {
      "video_id":    { "type": "keyword" },
      "title":       { "type": "text", "analyzer": "standard", "boost": 3.0 },
      "description": { "type": "text", "analyzer": "standard" },
      "tags":        { "type": "keyword", "boost": 2.0 },
      "channel_name":{ "type": "text", "boost": 1.5 },
      "category":    { "type": "keyword" },
      "view_count":  { "type": "long" },
      "like_count":  { "type": "long" },
      "published_at":{ "type": "date" },
      "duration_sec":{ "type": "integer" },
      "language":    { "type": "keyword" }
    }
  }
}
```

**Index Update Pipeline:**

```
(1) Video status changes to READY
(2) Metadata Service publishes "video_ready" event to Kafka
(3) Search Indexer (Kafka consumer) picks up the event
(4) Indexer reads full metadata from DynamoDB
(5) Indexer writes document to Elasticsearch
(6) Video becomes searchable within seconds
```

### 9.8 Analytics Service

The Analytics Service collects, aggregates, and serves engagement metrics (view counts, watch time, quality of experience) for both viewers and creators.

**Analytics Pipeline:**

```
+----------+                                                      +-----------+
|  Viewer  |                                                      | Creator   |
|  Client  |                                                      | Dashboard |
+----+-----+                                                      +-----+-----+
     |                                                                  ^
     | (1) Playback events:                                             |
     |   - video_start                                                  |
     |   - segment_downloaded                                           |
     |   - quality_switch                                               |
     |   - rebuffer_start/end                                           |
     |   - video_pause/resume                                           |
     |   - video_end                                                    |
     |   - seek                                                         |
     |                                                                  |
     v                                                                  |
+----+----------+     +-----------+     +------------+     +------------+
|               |     |           |     |            |     |            |
|  Analytics    |---->|  Kafka    |---->| Stream     |---->| ClickHouse |
|  Collector    |     |  (events) |     | Processor  |     | (OLAP DB)  |
|  (API)        |     |           |     | (Flink)    |     |            |
|               |     +-----------+     +------------+     +------------+
+---------------+                             |
                                              |
                                              v
                                    +-----------------+
                                    |                 |
                                    |  Redis          |
                                    |  (Real-time     |
                                    |   view counts)  |
                                    |                 |
                                    +-----------------+

View Count Aggregation:
  (1) Client sends "video_start" event to Analytics Collector
  (2) Event published to Kafka topic "playback_events"
  (3) Stream Processor (Flink) consumes events
  (4) For view counts: INCR in Redis (real-time, approximate)
  (5) For analytics: batch write to ClickHouse (accurate, delayed)
  (6) View count displayed on video page comes from Redis (fast, approximate)
  (7) Creator dashboard analytics come from ClickHouse (accurate, 5-min delay)
```

---

## 10. Video Upload Pipeline Deep Dive

### 10.1 Chunked Upload

Chunked upload is essential for large video files. Instead of uploading a multi-gigabyte file as a single HTTP request (which would fail on any network interruption), the client splits the file into small chunks and uploads each independently.

**Why Chunked Upload:**

```
Problem with single-file upload:
  - 1 GB file on 20 Mbps upload = ~7 minutes
  - If connection drops at 6 minutes = ENTIRE upload lost
  - Must restart from scratch

With chunked upload (5 MB chunks):
  - 1 GB file = 200 chunks of 5 MB each
  - Each chunk takes ~2 seconds to upload
  - If connection drops: only the in-flight chunk is lost
  - Resume by re-uploading only the missing chunks
  - 20 concurrent uploads = ~70 seconds total (vs 7 minutes sequential)
```

**Chunk Size Tradeoffs:**

```
+---------------+---------------------------+---------------------------+
| Chunk Size    | Advantages                | Disadvantages             |
+---------------+---------------------------+---------------------------+
| 1 MB          | Fast retry on failure     | Too many HTTP requests    |
|               | Low memory footprint      | High overhead per chunk   |
|               |                           | S3 multipart limit: 10K  |
+---------------+---------------------------+---------------------------+
| 5 MB (chosen) | Good balance              | Moderate retry cost       |
|               | Fits S3 multipart limits  |                           |
|               | Manageable parallelism    |                           |
+---------------+---------------------------+---------------------------+
| 25 MB         | Fewer HTTP requests       | Slow retry on failure     |
|               | Lower overhead            | Higher memory per chunk   |
|               |                           | Longer to detect failure  |
+---------------+---------------------------+---------------------------+
| 100 MB        | Minimal HTTP overhead     | Almost as bad as no       |
|               |                           | chunking on poor networks |
+---------------+---------------------------+---------------------------+

Optimal: 5 MB is the sweet spot for most use cases.
  - S3 multipart minimum is 5 MB (except last chunk)
  - 200 chunks for a 1 GB file (well under S3's 10,000 part limit)
  - Each chunk upload takes ~2 seconds on 20 Mbps
  - Retry cost is minimal (~2 seconds per failed chunk)
```

### 10.2 Resumable Upload

Resumable upload builds on chunked upload by tracking which chunks have been successfully received and allowing the client to resume from the point of failure.

**Resumable Upload Protocol:**

```
INITIAL UPLOAD:
(1) Client: POST /videos/upload -> receives upload_id + presigned URLs
(2) Client: uploads chunks 0..199 in parallel (20 concurrent)
(3) Network failure at chunk 142 (71% complete)

RESUME:
(4) Client: GET /videos/{id}/upload/status
    Response: { completed: [0..141], missing: [142..199] }
(5) Client: requests new presigned URLs for chunks 142..199
    (old URLs may have expired)
(6) Client: resumes uploading only chunks 142..199
(7) All chunks complete -> trigger assembly

STATE TRACKING (Server-side):

+---------------------------------------------------+
|  Redis: upload:{upload_id}                        |
|                                                   |
|  total_chunks: 200                                |
|  completed_chunks: 142                            |
|  chunk_bitmap: [1,1,1,...,1,0,0,...,0]            |
|                  ^-- 142 ones --^ ^-- 58 zeros    |
|  created_at: 2026-04-26T10:30:00Z                 |
|  expires_at: 2026-04-27T10:30:00Z (24h TTL)      |
|  status: UPLOADING                                |
+---------------------------------------------------+
```

### 10.3 Presigned URLs

Presigned URLs allow the client to upload directly to object storage without routing bytes through the API server. This is critical for scalability.

**Why Presigned URLs:**

```
WITHOUT presigned URLs:
  Client --[5MB chunk]--> API Server --[5MB chunk]--> S3
  
  Problems:
  - API server becomes bandwidth bottleneck
  - API server needs to buffer entire chunk in memory
  - API server CPU spent on data transfer instead of business logic
  - N concurrent uploads = N * 5MB memory on API server
  - Auto-scaling API servers is expensive (need high-bandwidth instances)

WITH presigned URLs:
  Client -------[5MB chunk]-------> S3 (direct)
  Client --[1KB request]--> API Server (presigned URL only)
  
  Benefits:
  - API server handles only lightweight metadata requests
  - S3 handles all bandwidth (designed for this)
  - API server can be small instances (no bandwidth needs)
  - S3 auto-scales to any upload volume
  - Client-to-S3 connection can use S3 Transfer Acceleration (edge locations)
```

**Presigned URL Generation (Java):**

```java
// Conceptual: generating a presigned URL for S3 upload
public class PresignedUrlGenerator {

    public String generatePresignedUploadUrl(
            String bucket, String key, int expirationMinutes) {
        // In production, use AWS SDK's S3Presigner
        // This is the conceptual flow:
        //
        // (1) Construct the canonical request for PUT
        // (2) Sign with AWS credentials (HMAC-SHA256)
        // (3) Return URL with signature embedded as query params
        //
        // URL format:
        // https://{bucket}.s3.amazonaws.com/{key}
        //   ?X-Amz-Algorithm=AWS4-HMAC-SHA256
        //   &X-Amz-Credential=...
        //   &X-Amz-Date=20260426T103000Z
        //   &X-Amz-Expires=3600
        //   &X-Amz-Signature=abcdef123456...

        String url = String.format(
            "https://%s.s3.amazonaws.com/%s?X-Amz-Expires=%d&X-Amz-Signature=%s",
            bucket, key, expirationMinutes * 60, computeSignature(bucket, key)
        );
        return url;
    }
}
```

### 10.4 Complete Upload Flow

```
+----------+              +----------------+            +--------+          +-------+
|  Creator |              | Upload Service |            |   S3   |          | Kafka |
|  Client  |              |                |            |        |          |       |
+----+-----+              +-------+--------+            +---+----+          +---+---+
     |                            |                         |                   |
     | (1) POST /videos/upload    |                         |                   |
     |  {title, size: 1GB,        |                         |                   |
     |   chunk_size: 5MB}         |                         |                   |
     |--------------------------->|                         |                   |
     |                            |                         |                   |
     |                            | (2) Validate:           |                   |
     |                            |  - File size < 256GB    |                   |
     |                            |  - Valid content type   |                   |
     |                            |  - Creator quota check  |                   |
     |                            |                         |                   |
     |                            | (3) Create Video record |                   |
     |                            |  (DynamoDB, UPLOADING)  |                   |
     |                            |                         |                   |
     |                            | (4) Init S3 multipart   |                   |
     |                            |     upload              |                   |
     |                            |------------------------>|                   |
     |                            |                         |                   |
     |                            | (5) Generate presigned  |                   |
     |                            |     URLs for 200 chunks |                   |
     |                            |                         |                   |
     | (6) Return {video_id,      |                         |                   |
     |     upload_id,             |                         |                   |
     |     presigned_urls[200]}   |                         |                   |
     |<---------------------------|                         |                   |
     |                            |                         |                   |
     | (7) PUT chunk_0 (direct)   |                         |                   |
     |----------------------------------------------->|     |                   |
     | (7) PUT chunk_1 (direct)   |                   |     |                   |
     |----------------------------------------------->|     |                   |
     | (7) PUT chunk_2 (direct)   |                   |     |                   |
     |----------------------------------------------->|     |                   |
     |    ... (20 concurrent)     |                   |     |                   |
     |                            |                         |                   |
     |                            | (8) S3 Event:           |                   |
     |                            |     chunk_0 uploaded    |                   |
     |                            |<------------------------|                   |
     |                            |                         |                   |
     |                            | (9) Update chunk bitmap |                   |
     |                            |     in Redis            |                   |
     |                            |                         |                   |
     |     ... all 200 chunks ... |                         |                   |
     |                            |                         |                   |
     | (10) POST /upload/complete |                         |                   |
     |  {upload_id, checksums[]}  |                         |                   |
     |--------------------------->|                         |                   |
     |                            |                         |                   |
     |                            | (11) Verify all chunks  |                   |
     |                            |      received + checksums|                  |
     |                            |                         |                   |
     |                            | (12) Complete S3        |                   |
     |                            |      multipart upload   |                   |
     |                            |------------------------>|                   |
     |                            |                         |                   |
     |                            | (13) Update Video       |                   |
     |                            |      status: PROCESSING |                   |
     |                            |                         |                   |
     |                            | (14) Publish event:     |                   |
     |                            |      "video_uploaded"   |                   |
     |                            |-------------------------------->|           |
     |                            |                         |       |           |
     | (15) Response:             |                         |       |           |
     |  {status: PROCESSING,     |                         |       |           |
     |   est_time: 15 min}       |                         |       |           |
     |<---------------------------|                         |       |           |
     |                            |                         |       |           |
```

---

## 11. Transcoding Deep Dive

This is **THE star of the video streaming interview**. Transcoding is the most compute-intensive, architecturally complex, and cost-significant component.

### 11.1 What is Transcoding?

Transcoding converts a source video file into multiple output variants that differ in resolution, codec, bitrate, and packaging format. This enables adaptive bitrate streaming, device compatibility, and bandwidth optimization.

```
SOURCE VIDEO                         TRANSCODED OUTPUT
+------------------+                 +------------------+
|                  |                 | 240p / H.264     | 400 Kbps
| 1080p            |   Transcoding   | 360p / H.264     | 800 Kbps
| H.264            | =============> | 480p / H.264     | 1.4 Mbps
| 5 Mbps           |   Pipeline     | 720p / H.264     | 2.8 Mbps
| 10 min           |                 | 1080p / H.264    | 5.0 Mbps
| ~375 MB          |                 +------------------+
+------------------+                 | 360p / VP9       | 600 Kbps
                                     | 480p / VP9       | 1.0 Mbps
                                     | 720p / VP9       | 2.0 Mbps
                                     | 1080p / VP9      | 3.5 Mbps
                                     +------------------+
                                     
                                     Total output: ~1.2 GB (10 variants)
                                     Total segments: 60 per variant = 600 segments
```

### 11.2 Resolution Ladder

The resolution ladder defines which output resolutions and bitrates to produce. The goal is to cover the widest range of viewers and network conditions.

```
+----------+----------+-----------+----------+----------+-------------------------+
|Resolution| Width x  | H.264     | VP9      | AV1      | Target Viewer           |
|          | Height   | Bitrate   | Bitrate  | Bitrate  |                         |
+----------+----------+-----------+----------+----------+-------------------------+
| 240p     | 426x240  | 400 Kbps  | 300 Kbps | 200 Kbps | 2G/3G mobile, very slow |
| 360p     | 640x360  | 800 Kbps  | 600 Kbps | 400 Kbps | 3G mobile, slow WiFi    |
| 480p     | 854x480  | 1.4 Mbps  | 1.0 Mbps | 700 Kbps | 4G mobile, basic WiFi   |
| 720p     | 1280x720 | 2.8 Mbps  | 2.0 Mbps | 1.4 Mbps | 4G/WiFi, most common    |
| 1080p    | 1920x1080| 5.0 Mbps  | 3.5 Mbps | 2.5 Mbps | WiFi, broadband         |
| 4K       | 3840x2160| 16 Mbps   | 12 Mbps  | 8 Mbps   | Fiber, smart TVs only   |
+----------+----------+-----------+----------+----------+-------------------------+

Notes:
- 4K is only transcoded for premium content (movies, original series)
- VP9 provides ~30-40% bitrate savings over H.264 at same quality
- AV1 provides ~50% savings over H.264, but encoding is 10x slower
- Most viewers (60%) watch at 720p; 25% at 1080p; 10% at 480p or below; 5% at 4K
```

### 11.3 Codecs

```
+----------+------------------+--------------+-------------+------------------+
| Codec    | Standard         | Compression  | Encoding    | Device Support   |
|          |                  | Efficiency   | Speed       |                  |
+----------+------------------+--------------+-------------+------------------+
| H.264    | MPEG, 2003       | Baseline     | Fast        | Universal (99%)  |
| (AVC)    |                  | (1x)         | (1x)        | Every device     |
+----------+------------------+--------------+-------------+------------------+
| H.265    | MPEG, 2013       | ~50% better  | 3-5x slower | Good (80%)       |
| (HEVC)   |                  | than H.264   | than H.264  | Modern devices   |
|          |                  |              |             | Patent issues    |
+----------+------------------+--------------+-------------+------------------+
| VP9      | Google, 2013     | ~30-40%      | 3-5x slower | Good (85%)       |
|          | (open-source)    | better than  | than H.264  | Chrome, Android, |
|          |                  | H.264        |             | smart TVs        |
+----------+------------------+--------------+-------------+------------------+
| AV1      | Alliance for     | ~50% better  | 10-20x      | Growing (40%)    |
|          | Open Media, 2018 | than H.264   | slower than  | Chrome, Firefox, |
|          | (royalty-free)   |              | H.264       | newer devices    |
+----------+------------------+--------------+-------------+------------------+

Strategy:
  - ALWAYS encode H.264: universal fallback, works on every device
  - ALWAYS encode VP9: significant savings for Chrome/Android (60%+ of traffic)
  - SELECTIVELY encode AV1: only for popular videos where bandwidth savings justify
    the 10-20x encoding cost (encode once, serve millions of times)
  - H.265/HEVC: avoid due to patent licensing complexity; VP9 covers same niche
```

### 11.4 Transcoding Pipeline (DAG)

The transcoding pipeline is organized as a **Directed Acyclic Graph (DAG)**, where each node is an independent task and edges define dependencies. This enables maximum parallelism.

```
                            +-------------------+
                            |   SOURCE VIDEO    |
                            |  (S3: source.mp4) |
                            +--------+----------+
                                     |
                        +------------+------------+
                        |                         |
                        v                         v
              +------------------+      +------------------+
              | TASK 1:          |      | TASK 2:          |
              | Extract Audio    |      | Video Probe      |
              | (AAC, 128kbps)   |      | (get metadata:   |
              |                  |      |  duration, fps,  |
              |                  |      |  resolution)     |
              +--------+---------+      +--------+---------+
                       |                         |
                       |              +----------+----------+
                       |              |                     |
                       |              v                     v
                       |    +------------------+  +------------------+
                       |    | TASK 3:          |  | TASK 4:          |
                       |    | Split into       |  | Generate         |
                       |    | 10-sec segments  |  | thumbnail        |
                       |    | (60 segments     |  | sprite sheet     |
                       |    |  for 10-min)     |  |                  |
                       |    +--------+---------+  +------------------+
                       |             |
                       |    +--------+----------+----------+----------+
                       |    |        |          |          |          |
                       |    v        v          v          v          v
                       | +------+ +------+  +------+  +------+  +------+
                       | |T5:   | |T6:   |  |T7:   |  |T8:   |  |T9:   |
                       | |240p  | |360p  |  |480p  |  |720p  |  |1080p |
                       | |H.264 | |H.264 |  |H.264 |  |H.264 |  |H.264 |
                       | |      | |      |  |      |  |      |  |      |
                       | |(all  | |(all  |  |(all  |  |(all  |  |(all  |
                       | | 60   | | 60   |  | 60   |  | 60   |  | 60   |
                       | | segs)| | segs) | | segs) | | segs) | | segs)|
                       | +--+---+ +--+---+  +--+---+  +--+---+  +--+---+
                       |    |        |          |          |          |
                       |    v        v          v          v          v
                       | +------+ +------+  +------+  +------+  +------+
                       | |T10:  | |T11:  |  |T12:  |  |T13:  |  |T14:  |
                       | |240p  | |360p  |  |480p  |  |720p  |  |1080p |
                       | |VP9   | |VP9   |  |VP9   |  |VP9   |  |VP9   |
                       | +--+---+ +--+---+  +--+---+  +--+---+  +--+---+
                       |    |        |          |          |          |
                       +----+--------+----------+----------+----------+
                                     |
                                     v
                          +---------------------+
                          | TASK 15:            |
                          | Package into        |
                          | HLS (.m3u8 + .ts)   |
                          | DASH (.mpd + .m4s)  |
                          +----------+----------+
                                     |
                                     v
                          +---------------------+
                          | TASK 16:            |
                          | Generate master     |
                          | manifest files      |
                          +----------+----------+
                                     |
                                     v
                          +---------------------+
                          | TASK 17:            |
                          | Update video status |
                          | to READY            |
                          | Notify creator      |
                          +---------------------+

DAG Properties:
  - Tasks 1 & 2: run in parallel (no dependencies)
  - Task 3: depends on Task 2 (need duration to calculate segment count)
  - Tasks 5-14: all run in parallel (independent of each other)
  - Each of Tasks 5-14 internally processes 60 segments in parallel
  - Task 15: depends on ALL of Tasks 5-14 (need all transcoded segments)
  - Task 16: depends on Task 15 + Task 1 (need packaged segments + audio)
  - Task 17: depends on Task 16
  
  Total parallelism: up to 10 * 60 = 600 segment transcodes running simultaneously
  (limited by worker pool size in practice)
```

### 11.5 Segment-Level Parallelism

Within each resolution/codec variant, each 10-second segment is transcoded independently. This is the key to achieving sub-30-minute transcoding for long videos.

```
Task T9 (1080p H.264) breakdown:

Source: 60 segments of 10 seconds each

+--------+--------+--------+--------+--------+     +--------+
| seg_00 | seg_01 | seg_02 | seg_03 | seg_04 | ... | seg_59 |
+---+----+---+----+---+----+---+----+---+----+     +---+----+
    |        |        |        |        |               |
    v        v        v        v        v               v
+---+----+---+----+---+----+---+----+---+----+     +---+----+
|Worker1 |Worker2 |Worker3 |Worker4 |Worker5 | ... |Worker60|
| ffmpeg | ffmpeg | ffmpeg | ffmpeg | ffmpeg |     | ffmpeg |
| 10 sec | 10 sec | 10 sec | 10 sec | 10 sec |    | 10 sec |
+---+----+---+----+---+----+---+----+---+----+     +---+----+
    |        |        |        |        |               |
    v        v        v        v        v               v
+---+----+---+----+---+----+---+----+---+----+     +---+----+
|seg_00  |seg_01  |seg_02  |seg_03  |seg_04  |     |seg_59  |
|.ts     |.ts     |.ts     |.ts     |.ts     |     |.ts     |
|(S3)    |(S3)    |(S3)    |(S3)    |(S3)    |     |(S3)    |
+--------+--------+--------+--------+--------+     +--------+

Without segment parallelism:
  1080p H.264 transcode of 10 min video = ~5 minutes (sequential)
  Total for 10 variants = ~50 minutes (way too slow)

With segment parallelism (60 workers per variant):
  Each segment transcode = ~5 seconds
  All 60 segments in parallel = ~5 seconds
  Total for 10 variants in parallel = ~5 seconds (with enough workers)
  
In practice (limited worker pool of ~50 workers):
  ~2-5 minutes for all variants of a 10-minute video
```

### 11.6 Transcoding Cost

```
Cost breakdown for transcoding a 10-minute 1080p video:

Compute (EC2 c5.2xlarge, 8 vCPU, $0.34/hr):
  - H.264 encoding (5 resolutions):
    240p: ~10 sec, 360p: ~15 sec, 480p: ~25 sec, 720p: ~45 sec, 1080p: ~90 sec
    Total: ~185 seconds = ~3.1 minutes
  - VP9 encoding (5 resolutions): ~5x slower than H.264
    Total: ~925 seconds = ~15.4 minutes
  - Combined: ~18.5 minutes of compute
  - With parallelism across 10 instances: ~2 minutes wall clock
  - Cost: 10 instances * 2 min * $0.34/hr / 60 = ~$0.11

Storage (S3):
  - Source file: 375 MB
  - Transcoded output (10 variants): ~1.2 GB
  - Total: ~1.6 GB at $0.023/GB/month = $0.037/month

S3 requests:
  - ~600 PUT requests (segments) + ~10 GET (source segments) = ~610 requests
  - At $0.005/1000 requests = ~$0.003

Total cost per 10-min video: ~$0.15 (first month)
  - Compute: $0.11
  - Storage: $0.037/month
  - S3 requests: $0.003

Daily cost (720K videos/day):
  - Compute: 720K * $0.11 = ~$79K/day
  - Storage (cumulative): grows by ~2.2 PB/day
  - Monthly compute: ~$2.4M
  - Monthly storage (first month): ~$1.5M (with tiering, much less)

YouTube's actual reported infrastructure spend: ~$5-7B/year
  (includes storage, CDN, compute, networking, data centers)
```

---

## 12. Adaptive Bitrate Streaming (ABR)

### 12.1 What is ABR?

Adaptive Bitrate Streaming dynamically adjusts video quality during playback based on the viewer's current network conditions. Instead of downloading the entire video at one quality level, the client downloads small segments and can switch quality between segments.

```
Traditional Download:              Adaptive Bitrate Streaming:

Download entire file               Download segment-by-segment
at one quality                      at varying qualities

+------------------+               +----+----+----+----+----+----+
|                  |               |1080|1080| 720| 480| 720|1080|
|    1080p         |               |  p |  p |  p |  p |  p |  p |
|    entire        |               +----+----+----+----+----+----+
|    video         |                 ^         ^    ^         ^
|                  |                 |         |    |         |
+------------------+                 |    bandwidth |    bandwidth
                                     |    drops     |    recovers
If bandwidth drops:                  |              |
  -> buffering (viewer leaves)   Seamlessly adapts quality
                                  -> no buffering, viewer stays
```

### 12.2 HLS (HTTP Live Streaming)

HLS is Apple's adaptive streaming protocol. It is the most widely supported protocol and the de facto standard for video streaming.

**HLS Architecture:**

```
MASTER MANIFEST (master.m3u8):
  Lists all available quality variants

#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=426x240,CODECS="avc1.42e00a"
240p/h264/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e"
360p/h264/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480,CODECS="avc1.4d401e"
480p/h264/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f"
720p/h264/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028"
1080p/h264/playlist.m3u8

VARIANT PLAYLIST (1080p/h264/playlist.m3u8):
  Lists all segments for one quality level

#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:10.0,
seg_000.ts
#EXTINF:10.0,
seg_001.ts
#EXTINF:10.0,
seg_002.ts
...
#EXTINF:10.0,
seg_059.ts
#EXT-X-ENDLIST

FILE FORMATS:
  Master manifest:    .m3u8 (text, ~500 bytes)
  Variant playlist:   .m3u8 (text, ~2 KB for 10-min video)
  Video segments:     .ts (MPEG-2 Transport Stream, ~2-5 MB each)
```

### 12.3 DASH (Dynamic Adaptive Streaming over HTTP)

DASH is the MPEG standard for adaptive streaming. It is similar to HLS but uses different file formats.

```
DASH MANIFEST (master.mpd):

<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     type="static"
     mediaPresentationDuration="PT10M0S"
     minBufferTime="PT2S">
  <Period>
    <AdaptationSet mimeType="video/mp4" contentType="video">
      <Representation id="240p" bandwidth="400000" width="426" height="240">
        <SegmentTemplate media="240p/h264/seg_$Number$.m4s"
                         initialization="240p/h264/init.mp4"
                         startNumber="0" duration="10000" timescale="1000"/>
      </Representation>
      <Representation id="1080p" bandwidth="5000000" width="1920" height="1080">
        <SegmentTemplate media="1080p/h264/seg_$Number$.m4s"
                         initialization="1080p/h264/init.mp4"
                         startNumber="0" duration="10000" timescale="1000"/>
      </Representation>
    </AdaptationSet>
    <AdaptationSet mimeType="audio/mp4" contentType="audio">
      <Representation id="audio" bandwidth="128000">
        <SegmentTemplate media="audio/seg_$Number$.m4a"
                         initialization="audio/init.mp4"
                         startNumber="0" duration="10000" timescale="1000"/>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>

FILE FORMATS:
  Manifest:          .mpd (XML, ~1-2 KB)
  Init segment:      .mp4 (contains codec/track info, ~1 KB)
  Media segments:    .m4s (fragmented MP4, ~2-5 MB each)
```

### 12.4 HLS vs DASH Comparison

```
+-----------------+-----------------------------+-----------------------------+
| Feature         | HLS                         | DASH                        |
+-----------------+-----------------------------+-----------------------------+
| Developed by    | Apple (2009)                | MPEG consortium (2012)      |
| Manifest format | .m3u8 (text playlist)       | .mpd (XML)                  |
| Segment format  | .ts (MPEG-2 TS)             | .m4s (fragmented MP4)       |
| Device support  | iOS (native), all browsers  | Android, most browsers      |
|                 | via MSE                     | NOT iOS Safari natively     |
| DRM support     | FairPlay (Apple)            | Widevine, PlayReady         |
| Low-latency     | LL-HLS (Apple, 2019)        | LL-DASH (CMAF)              |
| Codec agnostic  | Yes                         | Yes                         |
| Industry use    | YouTube, Netflix (for iOS)  | YouTube, Netflix (for web)  |
+-----------------+-----------------------------+-----------------------------+

Strategy: Generate BOTH HLS and DASH manifests for every video.
  - iOS/Safari: use HLS (required by Apple)
  - Android/Chrome/Web: use DASH (better standard, smaller overhead)
  - Smart TVs: use HLS (wider support in TV SDKs)
  
Modern approach: CMAF (Common Media Application Format)
  - Unified segment format (.m4s) that works with BOTH HLS and DASH manifests
  - Transcode segments once in .m4s format
  - Generate both .m3u8 and .mpd manifests pointing to the SAME segments
  - Reduces storage by ~40% compared to storing .ts AND .m4s
```

### 12.5 How ABR Works

```
ABR Playback Timeline:

Time:  0s    10s   20s   30s   40s   50s   60s   70s   80s   90s
       |     |     |     |     |     |     |     |     |     |
       v     v     v     v     v     v     v     v     v     v
      +-----+-----+-----+-----+-----+-----+-----+-----+-----+
Seg:  |  0  |  1  |  2  |  3  |  4  |  5  |  6  |  7  |  8  |
      +-----+-----+-----+-----+-----+-----+-----+-----+-----+
Qual: |1080p|1080p| 720p| 480p| 480p| 720p|1080p|1080p|1080p|
      +-----+-----+-----+-----+-----+-----+-----+-----+-----+
BW:   |15Mbps    | 5Mbps      | 3Mbps| 8Mbps    |15Mbps      |
      +----------+------------+------+----------+-------------+

Step-by-step:

(1) Player starts. Bandwidth unknown.
    -> Conservative start at 720p (mid-quality)
    -> Request segment 0 at 720p

(2) Segment 0 downloads in 3 seconds. Measured BW = 10 sec * 2.8 Mbps / 3 sec = ~9 Mbps
    -> Enough for 1080p (5 Mbps). Switch UP.
    -> Request segment 1 at 1080p

(3) Segment 1 downloads in 4 seconds. Measured BW = ~12.5 Mbps. Confirm 1080p.

(4) Network degrades. Segment 2 is slow. Buffer starts draining.
    -> ABR detects buffer < threshold (e.g., < 10 seconds of buffer)
    -> Switch DOWN to 720p for segment 3

(5) Network gets worse. Segment 3 still slow at 720p.
    -> Switch DOWN to 480p for segment 4

(6) Network improves. Segment 5 downloads quickly.
    -> ABR waits for buffer to rebuild above safe threshold
    -> Cautiously switch UP to 720p for segment 6

(7) Network fully recovered. Buffer healthy.
    -> Switch UP to 1080p for segment 7
```

### 12.6 ABR Algorithms

```
+-------------------+-----------------------------+-----------------------------+
| Algorithm         | How It Works                | Pros / Cons                 |
+-------------------+-----------------------------+-----------------------------+
| Throughput-based  | Measure download speed of   | + Simple, reactive          |
|                   | last N segments. Pick the   | - Oscillates on variable    |
|                   | highest quality that fits   |   bandwidth (WiFi, LTE)    |
|                   | within measured throughput.  | - Overreacts to spikes      |
+-------------------+-----------------------------+-----------------------------+
| Buffer-based      | Decide quality based on     | + Smooth quality changes    |
| (BBA)             | current buffer level:       | + No bandwidth measurement  |
|                   | - Buffer low -> lowest qual |   needed                    |
|                   | - Buffer medium -> mid qual | - Slow to react to sudden   |
|                   | - Buffer high -> max qual   |   bandwidth changes         |
+-------------------+-----------------------------+-----------------------------+
| Hybrid            | Combine throughput estimate | + Best of both worlds       |
| (most common)     | with buffer level:          | + Used by YouTube, Netflix  |
|                   | - Use throughput to pick    | - More complex to tune      |
|                   |   candidate quality         |                             |
|                   | - Use buffer to gate        |                             |
|                   |   switches (don't upgrade   |                             |
|                   |   unless buffer is healthy) |                             |
+-------------------+-----------------------------+-----------------------------+
| MPC (Model        | Optimize over next N        | + Optimal decisions         |
| Predictive        | segments using predicted    | + Minimizes rebuffering     |
| Control)          | bandwidth and buffer model. | - Computationally expensive |
|                   | Minimize: rebuffering +     | - Complex implementation    |
|                   | quality changes + quality   |                             |
|                   | penalty                     |                             |
+-------------------+-----------------------------+-----------------------------+
```

### 12.7 Segment Size Tradeoffs

```
+----------------+---------------------------+---------------------------+
| Segment Size   | Advantages                | Disadvantages             |
+----------------+---------------------------+---------------------------+
| 2 seconds      | Very fast quality          | High HTTP request         |
|                | switching (2s granularity) | overhead (1 req per 2s)   |
|                | Good for live streaming    | More manifest entries     |
|                | Lower startup latency      | Higher CDN load           |
+----------------+---------------------------+---------------------------+
| 4 seconds      | Good balance for live      | Moderate switching speed  |
|                | streaming                  |                           |
+----------------+---------------------------+---------------------------+
| 6 seconds      | Good balance overall       | Moderate everything       |
| (Netflix uses) |                           |                           |
+----------------+---------------------------+---------------------------+
| 10 seconds     | Lower HTTP overhead        | Slower quality switching  |
| (YouTube uses) | Fewer CDN cache entries    | (up to 10s delay)        |
|                | More efficient encoding    | Higher startup latency    |
|                | (longer GOPs)              | if first segment is large |
+----------------+---------------------------+---------------------------+
| 30 seconds     | Minimal overhead           | Unacceptable switching    |
|                |                           | delay for most use cases  |
+----------------+---------------------------+---------------------------+

Recommended: 6-10 seconds for VOD, 2-4 seconds for live streaming.
We use 10 seconds for VOD (aligns with YouTube's approach).
```

---

## 13. CDN and Caching

### 13.1 Multi-Tier CDN Architecture

```
                    GLOBAL CDN TOPOLOGY
                    ====================

                           ORIGIN
                      +------+-------+
                      |   S3 Bucket  |
                      |  (us-east-1) |
                      +------+-------+
                             |
              +--------------+--------------+
              |                             |
      +-------+-------+            +-------+-------+
      | Origin Shield  |            | Origin Shield  |
      | (us-east-1)   |            | (eu-west-1)   |
      +-------+-------+            +-------+-------+
              |                             |
    +---------+----------+        +---------+----------+
    |         |          |        |         |          |
+---+---+ +--+----+ +---+---+ +--+----+ +--+----+ +---+---+
|Region | |Region | |Region | |Region | |Region | |Region |
|US-East| |US-West| |LATAM  | |Europe | |India  | |APAC   |
+---+---+ +--+----+ +---+---+ +--+----+ +--+----+ +---+---+
    |         |          |        |         |          |
  +-+-+     +-+-+      +-+-+   +-+-+     +-+-+      +-+-+
  |   |     |   |      |   |   |   |     |   |      |   |
 NYC  BOS  LAX  SEA   SP  MX  LON PAR  MUM DEL    TOK SYD
 POP  POP  POP  POP   POP POP POP POP  POP POP    POP POP

 (200+ edge POPs worldwide, each with 10-100 TB cache)
```

### 13.2 Popular Video vs Long-Tail

```
VIDEO POPULARITY DISTRIBUTION (Power Law):

Views
  ^
  |
  |  *
  |  *
  |  **
  |   ***
  |     ****
  |        ********
  |                *******************************************
  +---------------------------------------------------------> Videos
  |<- 10% ->|<----------- 90% of videos ------------------>|
  | of videos|
  | (90% of  | (10% of views)
  |  views)  |
  
  HOT CONTENT (top 10%):                 LONG-TAIL (bottom 90%):
  - Cached at EDGE POPs                  - NOT cached at edge
  - Served in < 50ms                     - Cache miss -> regional -> origin
  - First segments pre-warmed            - First access: 200-500ms
  - ~6TB cache per edge POP              - Subsequent accesses: cached
    is sufficient for top 10%            - May be in cold storage (Glacier)
  
  Cache Strategy:
  (1) Edge POP has LRU cache (10-100 TB per POP)
  (2) Popular videos naturally stay in cache (frequently accessed)
  (3) Long-tail videos evicted from cache quickly
  (4) On cache miss: fetch from regional hub (which has larger cache)
  (5) Regional miss: fetch from origin shield (prevents thundering herd on S3)
  (6) Origin shield miss: fetch from S3
  (7) If video is in cold storage: async restore + serve placeholder message
```

### 13.3 Cache Miss Handling

```
Viewer requests segment that is NOT in edge cache:

+--------+          +---------+        +----------+       +---------+      +-----+
| Viewer |          |  Edge   |        | Regional |       | Shield  |      |  S3 |
|        |          |  POP    |        |   Hub    |       |         |      |     |
+---+----+          +----+----+        +----+-----+       +----+----+      +--+--+
    |                    |                  |                   |              |
    | (1) GET seg_042    |                  |                   |              |
    |------------------->|                  |                   |              |
    |                    |                  |                   |              |
    |                    | (2) Cache MISS   |                   |              |
    |                    |                  |                   |              |
    |                    | (3) Forward      |                   |              |
    |                    |----------------->|                   |              |
    |                    |                  |                   |              |
    |                    |                  | (4) Cache MISS    |              |
    |                    |                  |                   |              |
    |                    |                  | (5) Forward       |              |
    |                    |                  |------------------>|              |
    |                    |                  |                   |              |
    |                    |                  |                   | (6) Cache    |
    |                    |                  |                   |     MISS     |
    |                    |                  |                   |              |
    |                    |                  |                   | (7) GET      |
    |                    |                  |                   |----------->  |
    |                    |                  |                   |              |
    |                    |                  |                   | (8) Return   |
    |                    |                  |                   |<-----------  |
    |                    |                  |                   |              |
    |                    |                  | (9) Cache + return|              |
    |                    |                  |<------------------|              |
    |                    |                  |                   |              |
    |                    | (10) Cache +     |                   |              |
    |                    |      return      |                   |              |
    |                    |<-----------------|                   |              |
    |                    |                  |                   |              |
    | (11) Return segment|                  |                   |              |
    |<-------------------|                  |                   |              |
    |                    |                  |                   |              |

    Total latency: 200-500ms (cache-miss path)
    vs 20-50ms (cache-hit path)
    
    After this request: the segment is cached at Edge, Regional, and Shield
    Next viewer at the same POP: cache HIT, served in < 50ms
```

### 13.4 CDN Selection: Anycast DNS

```
How the viewer connects to the nearest CDN edge:

(1) Viewer's device resolves cdn.example.com via DNS
(2) DNS server (e.g., Route53) uses latency-based routing:
    - Has latency measurements to all edge POPs
    - Returns the IP of the POP with lowest latency to the viewer
(3) Alternative: Anycast IP addressing
    - ALL edge POPs advertise the SAME IP address via BGP
    - Internet routing naturally sends the request to the nearest POP
    
Anycast Advantage:
  - No DNS TTL issues (IP doesn't change, routing adapts in real-time)
  - Automatic failover (if a POP goes down, BGP re-routes to next nearest)
  - Works with HTTPS (unlike some DNS-based approaches with IP changes)

+----------+          DNS: cdn.example.com          +----------+
|  Viewer  |  -----> resolves to 203.0.113.1 -----> |  Edge    |
|  Mumbai  |         (same IP for all POPs)         |  Mumbai  |
+----------+         BGP routes to nearest          +----------+

+----------+          DNS: cdn.example.com          +----------+
|  Viewer  |  -----> resolves to 203.0.113.1 -----> |  Edge    |
|  Tokyo   |         (same IP, different POP)       |  Tokyo   |
+----------+                                         +----------+
```

### 13.5 Thundering Herd Prevention

When a viral video is first published, millions of viewers may try to watch it simultaneously. Without protection, every cache miss generates a request to the origin, overwhelming S3.

```
PROBLEM: Viral video, 1 million concurrent viewers, segment not yet cached

Without protection:
  1,000,000 viewers -> 200 edge POPs -> all miss -> 200 regional hubs -> all miss
  -> 3 origin shields -> all forward to S3 -> 200 concurrent requests to S3 for SAME segment
  -> S3 throttles, timeouts, cascade failure

WITH Origin Shield (request coalescing):

  1,000,000 viewers
       |
       v
  200 edge POPs (all miss)
       |
       v
  20 regional hubs (all miss, but each coalesces requests from ~10 edges)
  -> 20 requests to origin shield (not 200)
       |
       v
  1 origin shield (coalesces all 20 requests into 1)
  -> 1 request to S3
       |
       v
  S3 returns segment once
       |
       v
  Origin shield caches + fans out response to 20 regional hubs
       |
       v
  Regional hubs cache + fan out to 200 edge POPs
       |
       v
  Edge POPs cache + serve to 1,000,000 viewers

Result: 1 S3 request instead of 200. No thundering herd.

Implementation:
  - Origin shield uses request coalescing (a.k.a. "collapsed forwarding")
  - When multiple requests arrive for the same cache key simultaneously,
    only the FIRST request is forwarded to origin
  - All subsequent requests wait for the first to complete, then share the result
  - This is a standard CDN feature (Cloudflare, CloudFront, Fastly all support it)
```

---

## 14. Concurrency

### 14.1 Concurrent Chunk Uploads

Multiple chunks of the same video are uploaded in parallel. The Upload Service must correctly track all chunks without race conditions.

```
PROBLEM: 20 concurrent chunk uploads, each triggers an S3 event notification.
         Upload Service must accurately track which chunks are complete.

SOLUTION: Use Redis SETBIT for atomic bit-level tracking.

Thread 1 (chunk 0 uploaded):  SETBIT upload:{id}:chunks 0 1   -> atomic
Thread 2 (chunk 1 uploaded):  SETBIT upload:{id}:chunks 1 1   -> atomic
Thread 3 (chunk 2 uploaded):  SETBIT upload:{id}:chunks 2 1   -> atomic
...
Thread 20 (chunk 19 uploaded): SETBIT upload:{id}:chunks 19 1 -> atomic

Check completion: BITCOUNT upload:{id}:chunks
  If BITCOUNT == total_chunks -> all chunks received

Why Redis SETBIT works:
  - Each SETBIT is atomic (no read-modify-write race)
  - BITCOUNT is O(N) where N is the bit string length (fast for 200 bits)
  - No need for distributed locks
  - Memory efficient: 200 chunks = 25 bytes
```

### 14.2 Parallel Transcoding Task Assignment

```
PROBLEM: Multiple transcoding workers competing for tasks from the queue.
         Must ensure each task is assigned to exactly ONE worker.

SOLUTION: Use message queue with visibility timeout (SQS-style).

(1) Orchestrator pushes 600 tasks to the task queue
(2) Worker W1 calls "receive" -> gets Task T42, visibility timeout = 5 min
(3) Task T42 becomes INVISIBLE to other workers for 5 minutes
(4) W1 processes T42 (transcode segment)
(5) W1 calls "delete" (acknowledge) -> T42 permanently removed
(6) If W1 crashes (no delete within 5 min) -> T42 becomes visible again
(7) Worker W2 picks up T42 (retry)

Queue Behavior:
+---------------------------------------------------+
|  Task Queue (SQS / Kafka consumer group)          |
|                                                   |
|  [T1] [T2] [T3] [T4] [T5] ... [T600]            |
|   |    |    |                                     |
|   v    v    v                                     |
|  W1   W2   W3   (each worker gets unique tasks)   |
|                                                   |
|  Guarantee: at-least-once delivery                |
|  Idempotency: re-transcoding a segment is safe    |
|    (overwrites the same S3 key)                   |
+---------------------------------------------------+
```

### 14.3 View Count Aggregation

```
PROBLEM: Millions of concurrent viewers watching the same video.
         Each view triggers a "view_count + 1" operation.
         Naive approach: 1 million DB writes/second to the same row -> contention.

SOLUTION: Multi-level aggregation with Redis.

Level 1: Client-side batching
  - Client sends view event to Analytics Collector API
  - Collector buffers events in memory (100ms window)
  - Batch-writes to Kafka every 100ms

Level 2: Stream processing aggregation
  - Kafka consumer (Flink) reads view events
  - Aggregates counts per video per 1-second window
  - Instead of 1M individual increments: ~1K aggregated increments/second

Level 3: Redis atomic increment
  - Flink writes: INCRBY video:{id}:views <count>
  - Redis handles ~100K INCRBY/second per shard
  - View count reads: GET video:{id}:views (fast, from Redis)

Level 4: Periodic persistence
  - Background job reads Redis counters every 5 minutes
  - Batch-updates DynamoDB (accurate, durable count)
  - Redis is the source of truth for display; DynamoDB is the source of truth for durability

Flow:
  1M viewers     ->  Kafka     -> Flink        -> Redis          -> DynamoDB
  (1M events/s)    (buffered)   (aggregated)    (INCRBY, fast)   (batch, durable)
                                 (1K writes/s)   (~1K writes/s)   (1 write/5 min)
```

### 14.4 Concurrent Like/Dislike

```
PROBLEM: User rapidly toggles like/dislike. Must be idempotent.
         Multiple users like simultaneously. Must not lose counts.

SOLUTION: Per-user action stored in DynamoDB (idempotent); count in Redis (atomic).

(1) User likes video:
    - DynamoDB: PUT {user_id, video_id, action: LIKE, timestamp}
      Conditional: IF action != LIKE (only if not already liked)
    - If conditional succeeds: Redis INCR video:{id}:likes
    - If conditional fails: no-op (already liked, idempotent)

(2) User changes from LIKE to DISLIKE:
    - DynamoDB: UPDATE {user_id, video_id, action: DISLIKE}
      Conditional: IF action == LIKE
    - If conditional succeeds: 
      Redis DECR video:{id}:likes
      Redis INCR video:{id}:dislikes
    - If conditional fails: no-op (race condition, another request already changed it)

(3) User removes like (action: NONE):
    - DynamoDB: UPDATE {user_id, video_id, action: NONE}
      Conditional: IF action == LIKE
    - If conditional succeeds: Redis DECR video:{id}:likes

Why This Is Safe:
  - DynamoDB conditional writes prevent double-counting
  - Redis atomic INCR/DECR prevents lost updates
  - Eventual consistency between DynamoDB and Redis is acceptable
    (off by 1 for a few seconds is fine for like counts)
```

---

## 15. Scaling

### 15.1 Transcoding Workers Auto-Scale

```
SCALING STRATEGY: Auto-scale transcoding workers based on queue depth.

+---------------------------------------------------------------------+
|                 TRANSCODING WORKER SCALING                           |
+---------------------------------------------------------------------+
|                                                                     |
|  Queue Depth    Workers    Instance Type     Cost/hr                |
|  -----------    -------    -------------     -------                |
|  0-1,000        50         c5.2xlarge (CPU)  $17/hr                |
|  1,000-10,000   200        c5.2xlarge (CPU)  $68/hr                |
|  10,000-50,000  500        c5.4xlarge (CPU)  $340/hr               |
|  > 50,000       1,000      g4dn.xlarge (GPU) $526/hr               |
|                                                                     |
|  Scaling trigger: queue_depth / workers_active > 10 tasks/worker    |
|  Scale-up time: 2-5 minutes (EC2 launch + warm-up)                 |
|  Scale-down: gradual (drain existing tasks, then terminate)         |
|                                                                     |
|  Cost optimization:                                                 |
|  - Use Spot Instances for low-priority transcoding (70% cheaper)   |
|  - On Spot interruption: task returns to queue, another worker picks|
|    it up (idempotent transcoding means no wasted work)              |
|  - Use On-Demand for high-priority (premium creators, live)        |
+---------------------------------------------------------------------+

Daily Pattern:
  Uploads peak at 8-10 PM local time (creators publish evening content)
  Workers scale up 2x during peak, scale down during off-peak
  Night hours: use Spot Instances for backlog processing (re-encodes, new codecs)
```

### 15.2 CDN Global Distribution

```
CDN SCALING: No single point of failure, globally distributed.

+---------------------------------------------------------------------+
|                    CDN GLOBAL SCALING                                |
+---------------------------------------------------------------------+
|                                                                     |
|  Tier          Count    Cache Size    Bandwidth     Role            |
|  ----          -----    ----------    ---------     ----            |
|  Edge POPs     200+     10-100 TB     1-10 Gbps     Serve viewers   |
|  Regional Hubs 20-30    500 TB-1PB    100 Gbps      Aggregate edges |
|  Origin Shield 2-3      2-5 PB        500 Gbps      Protect origin  |
|                                                                     |
|  Total CDN storage: ~50 PB                                          |
|  Total CDN bandwidth: ~500 Tbps capacity                            |
|                                                                     |
|  Auto-scaling:                                                      |
|  - Edge POPs: add capacity by deploying to new ISP peering points  |
|  - Regional: horizontal scale by adding more cache servers          |
|  - Shield: rarely needs scaling (coalescing reduces load)           |
|                                                                     |
|  Failure handling:                                                  |
|  - Edge POP failure: DNS/anycast re-routes to next nearest POP     |
|  - Regional failure: edges fall back to alternate regional hub      |
|  - Shield failure: edges go directly to origin (temporarily)       |
+---------------------------------------------------------------------+
```

### 15.3 Storage Tiering

```
STORAGE SCALING: Lifecycle-based tiering to manage petabyte-scale costs.

+---------------------------------------------------------------------+
|                    STORAGE TIERING                                   |
+---------------------------------------------------------------------+
|                                                                     |
|  Tier              When                Cost/GB/mo   Access Latency  |
|  ----              ----                ----------   --------------  |
|  S3 Standard       Last accessed       $0.023       Instant         |
|                    within 30 days                                    |
|  S3 Infrequent     30-180 days since   $0.0125      Instant         |
|  Access (IA)       last access                      (higher per-req)|
|  S3 Glacier        180-365 days        $0.004       1-5 min         |
|  Instant Retrieval since last access                (expedited)     |
|  S3 Glacier        > 365 days          $0.00099     3-5 hours       |
|  Deep Archive      since last access                (bulk)          |
|                                                                     |
|  Cost impact:                                                       |
|  Without tiering: 3 EB * $0.023 = $69M/month                       |
|  With tiering:                                                      |
|    10% hot * $0.023 = $6.9M                                         |
|    30% warm * $0.0125 = $11.3M                                      |
|    50% cold * $0.004 = $6.0M                                        |
|    10% archive * $0.00099 = $0.3M                                   |
|    Total: ~$24.5M/month (65% savings)                               |
|                                                                     |
|  Implementation:                                                    |
|  - S3 Lifecycle Policies: automatic transition based on access age  |
|  - S3 Intelligent-Tiering: automatic per-object tiering ($0.0025   |
|    monitoring cost per 1000 objects)                                 |
|  - Tombstone source files: delete raw upload after transcoding      |
|    (keep only transcoded variants)                                  |
+---------------------------------------------------------------------+
```

### 15.4 Metadata and Search Scaling

```
+---------------------------------------------------------------------+
|  Service             Scaling Strategy                                |
+---------------------------------------------------------------------+
|  DynamoDB            On-demand capacity mode                        |
|  (metadata)          Auto-scales to any throughput                   |
|                      Partition key = videoId (uniform distribution)  |
|                      Global tables for multi-region reads            |
+---------------------------------------------------------------------+
|  Redis               Cluster mode with 50+ shards                   |
|  (view counts,       Each shard handles ~100K ops/sec               |
|  session data)       Total: ~5M ops/sec                              |
|                      Replicas for read scaling                       |
+---------------------------------------------------------------------+
|  Elasticsearch       20+ data nodes, 3 master nodes                 |
|  (search)            Sharded by video_id                             |
|                      Index: ~1B documents at ~1KB each = ~1TB        |
|                      Query latency: p99 < 50ms                       |
+---------------------------------------------------------------------+
|  Kafka               100+ partitions per topic                      |
|  (events)            Handles 1M+ events/second                       |
|                      3x replication for durability                   |
+---------------------------------------------------------------------+
```

---

## 16. Database Choice

### 16.1 Storage Technology Map

```
+-------------------+------------------+--------------------------------------+
| Data Type         | Technology       | Reason                               |
+-------------------+------------------+--------------------------------------+
| Video files       | S3 / GCS         | Object storage, unlimited scale,     |
| (segments,        | (Object Storage) | $0.023/GB, 11 nines durability,      |
| source, thumbs)   |                  | native CDN integration               |
+-------------------+------------------+--------------------------------------+
| Video metadata    | DynamoDB         | Key-value by videoId, single-digit   |
| (title, desc,     |                  | ms reads, auto-scaling, global       |
| tags, status)     |                  | tables for multi-region              |
+-------------------+------------------+--------------------------------------+
| User data         | DynamoDB         | Key-value by userId, same reasons    |
|                   |                  | as video metadata                    |
+-------------------+------------------+--------------------------------------+
| Watch history     | DynamoDB         | Partition: userId, Sort: watchedAt   |
|                   |                  | Efficient range queries for recent   |
|                   |                  | history                              |
+-------------------+------------------+--------------------------------------+
| View counts,      | Redis Cluster    | Atomic INCR, sub-ms latency,         |
| like counts       |                  | handles 100K+ ops/sec per shard     |
+-------------------+------------------+--------------------------------------+
| Transcode jobs    | DynamoDB         | Job tracking, conditional updates    |
|                   |                  | for worker assignment                |
+-------------------+------------------+--------------------------------------+
| Search index      | Elasticsearch    | Full-text search, relevance scoring, |
|                   |                  | faceted search on category/tags      |
+-------------------+------------------+--------------------------------------+
| Analytics events  | Kafka + Flink    | High-throughput event streaming,     |
|                   | + ClickHouse     | real-time aggregation, OLAP queries  |
+-------------------+------------------+--------------------------------------+
| Recommendations   | Feature Store    | Pre-computed user/video embeddings   |
| (ML features)     | (Redis / S3)     | for low-latency inference            |
+-------------------+------------------+--------------------------------------+
| Upload state      | Redis            | Chunk bitmap, TTL for abandoned      |
| (chunk tracking)  |                  | uploads, fast reads during upload    |
+-------------------+------------------+--------------------------------------+

Why NOT relational (PostgreSQL/MySQL)?
  - Video metadata is simple key-value (no complex joins needed)
  - 1 billion videos at high throughput exceeds single-node RDBMS capacity
  - DynamoDB provides auto-scaling, global distribution, and single-digit ms reads
  - The only "relational" data is user<->video (subscriptions, likes), which is
    modeled as separate items in DynamoDB with GSIs

Why NOT a single database for everything?
  - Different access patterns require different storage engines
  - Video segments: blob storage (S3), not database rows
  - View counts: in-memory (Redis), not disk-based DB
  - Search: inverted index (Elasticsearch), not B-tree
  - Analytics: columnar store (ClickHouse), not row-based
  - Polyglot persistence: use the right tool for each job
```

### 16.2 DynamoDB Table Design

```
VIDEO TABLE:
  Partition Key: video_id (String)
  Sort Key: none (single-item access)
  
  Attributes: user_id, status, source_url, duration, visibility,
              created_at, published_at
  
  GSI-1: user_id (PK) + published_at (SK)
    -> Query: "all videos by creator X, ordered by publish date"
  
  GSI-2: category (PK) + view_count (SK)
    -> Query: "top videos in category GAMING"

WATCH_HISTORY TABLE:
  Partition Key: user_id (String)
  Sort Key: watched_at (Number, epoch ms)
  
  Attributes: video_id, last_position, completion_pct, watch_duration
  
  Query: "last 50 videos watched by user X"
    -> Query(PK=user_id, ScanIndexForward=false, Limit=50)

TRANSCODE_JOB TABLE:
  Partition Key: video_id (String)
  Sort Key: job_id (String)
  
  Attributes: status, resolution, codec, worker_id, progress, error
  
  GSI-1: status (PK) + created_at (SK)
    -> Query: "all QUEUED jobs, oldest first" (for worker assignment)
```

---

## 17. CAP Theorem

### 17.1 CAP Analysis by Component

```
+-------------------------+------+----------------------------------------------+
| Component               | AP/CP| Reasoning                                    |
+-------------------------+------+----------------------------------------------+
| Video Streaming (CDN)   | AP   | Stale segment is fine (immutable content).   |
|                         |      | Availability is paramount: viewers must      |
|                         |      | never see a "service unavailable" error.     |
|                         |      | CDN nodes can serve cached content even if   |
|                         |      | origin is unreachable.                       |
+-------------------------+------+----------------------------------------------+
| Recommendations         | AP   | Stale recommendations are acceptable.        |
|                         |      | Showing yesterday's recommendations is       |
|                         |      | better than showing nothing.                 |
+-------------------------+------+----------------------------------------------+
| View Counts / Likes     | AP   | Approximate counts are acceptable.           |
|                         |      | "1,482,930 views" vs "1,482,935 views"       |
|                         |      | makes no difference to the viewer.           |
|                         |      | Eventual consistency within 5 seconds.       |
+-------------------------+------+----------------------------------------------+
| Search Results          | AP   | Slightly stale search results are fine.      |
|                         |      | New video appearing in search 30 seconds     |
|                         |      | late is acceptable.                          |
+-------------------------+------+----------------------------------------------+
| Upload State Tracking   | CP   | Must be consistent. If we report a chunk     |
|                         |      | as "not uploaded" when it was, the client    |
|                         |      | wastes bandwidth re-uploading. If we report  |
|                         |      | "uploaded" when it wasn't, the video is      |
|                         |      | corrupted.                                   |
+-------------------------+------+----------------------------------------------+
| Transcoding Job State   | CP   | Must be consistent. Double-assigning a job   |
|                         |      | wastes compute. Missing a job leaves the     |
|                         |      | video incomplete. Use conditional writes.    |
+-------------------------+------+----------------------------------------------+
| Video Metadata (writes) | CP   | Creator's changes to title/description must  |
|                         |      | be durable and consistent. Read-after-write  |
|                         |      | consistency for the creator.                 |
+-------------------------+------+----------------------------------------------+
| Video Metadata (reads)  | AP   | Viewers can tolerate stale metadata (old     |
|                         |      | title for a few seconds). Availability is    |
|                         |      | more important than instant consistency.     |
+-------------------------+------+----------------------------------------------+
| Watch History           | AP   | Losing a few seconds of watch progress is    |
|                         |      | acceptable. Availability > perfect accuracy. |
+-------------------------+------+----------------------------------------------+

Summary:
  - READ PATH (streaming, recs, search, counts): AP everywhere
    -> Viewers must ALWAYS be able to watch videos
    -> Staleness of seconds or even minutes is acceptable
  
  - WRITE PATH (upload, transcode, metadata updates): CP
    -> Data integrity is critical for the upload pipeline
    -> Losing an upload or corrupting a video is unacceptable
```

### 17.2 Partition Tolerance in Practice

```
Network Partition Scenario:
  US-East region loses connectivity to EU-West region.

CDN (AP):
  - EU viewers still served from EU edge caches
  - New videos uploaded in US won't appear in EU until partition heals
  - Acceptable: EU viewers watch existing content (99.99% of traffic)

DynamoDB (Global Tables, AP with conflict resolution):
  - Both regions can accept writes
  - Conflicting writes resolved by "last writer wins" (timestamp)
  - In practice, same video rarely updated from two regions simultaneously

Redis (AP with replication lag):
  - View counts may diverge between regions
  - After partition heals: counts reconcile (higher count wins)

Kafka (CP within region):
  - Transcoding events stay within the region
  - Cross-region replication paused during partition
  - Transcoding continues independently in each region
```

---

## 18. Cloud Services

### 18.1 AWS Implementation

```
+----------------------------+-------------------------------+
| Component                  | AWS Service                   |
+----------------------------+-------------------------------+
| Object Storage (videos)    | Amazon S3                     |
| CDN                        | Amazon CloudFront             |
| Video Metadata DB          | Amazon DynamoDB               |
| In-Memory Cache/Counters   | Amazon ElastiCache (Redis)    |
| Message Queue              | Amazon SQS / Amazon MSK       |
| Stream Processing          | Amazon Kinesis / Apache Flink  |
| Transcoding Compute        | Amazon EC2 (c5, g4dn)        |
| Managed Transcoding        | AWS Elemental MediaConvert    |
| Search                     | Amazon OpenSearch             |
| Analytics DB               | Amazon Redshift / ClickHouse  |
| DNS                        | Amazon Route 53               |
| Load Balancer              | Application Load Balancer     |
| Container Orchestration    | Amazon EKS                    |
| Monitoring                 | Amazon CloudWatch             |
| ML Recommendations         | Amazon SageMaker              |
| Live Streaming             | AWS Elemental MediaLive       |
+----------------------------+-------------------------------+
```

### 18.2 GCP Implementation

```
+----------------------------+-------------------------------+
| Component                  | GCP Service                   |
+----------------------------+-------------------------------+
| Object Storage (videos)    | Google Cloud Storage          |
| CDN                        | Cloud CDN + Media CDN         |
| Video Metadata DB          | Cloud Bigtable / Firestore    |
| In-Memory Cache/Counters   | Memorystore (Redis)           |
| Message Queue              | Cloud Pub/Sub                 |
| Stream Processing          | Cloud Dataflow (Apache Beam)  |
| Transcoding Compute        | Compute Engine (c2, a2)       |
| Managed Transcoding        | Transcoder API                |
| Search                     | Elasticsearch on GKE          |
| Analytics DB               | BigQuery                      |
| DNS                        | Cloud DNS                     |
| Load Balancer              | Cloud Load Balancing          |
| Container Orchestration    | Google Kubernetes Engine       |
| Monitoring                 | Cloud Monitoring              |
| ML Recommendations         | Vertex AI                     |
| Live Streaming             | Live Stream API               |
+----------------------------+-------------------------------+
```

### 18.3 Build vs Buy: Transcoding

```
+-------------------+----------------------------------+----------------------------------+
| Approach          | AWS MediaConvert (Managed)       | Self-Hosted FFmpeg (Custom)      |
+-------------------+----------------------------------+----------------------------------+
| Cost per min      | ~$0.024/min (1080p)              | ~$0.005/min (EC2 spot)           |
| Control           | Limited (preset-based)           | Full (any codec, any parameter)  |
| Scale             | Auto-managed                     | Self-managed (but auto-scalable) |
| Codec support     | H.264, H.265, VP8, VP9          | Anything FFmpeg supports (all)   |
| AV1 support       | Limited                          | Full (via libaom or SVT-AV1)     |
| DAG orchestration | Basic (job chains)               | Full custom DAG (our design)     |
| Monitoring        | CloudWatch metrics               | Custom (Prometheus + Grafana)    |
| Startup time      | Pay per use, no warm-up          | EC2 launch: 2-5 min             |
+-------------------+----------------------------------+----------------------------------+

Decision: At YouTube/Netflix scale, self-hosted is 5x cheaper and more flexible.
          At startup scale, use MediaConvert (faster to market, no ops overhead).
          
In an interview: mention both, explain the tradeoff, then design the custom pipeline
                  (shows deeper understanding).
```

---

## 19. Tradeoffs Summary

### 19.1 Key Design Decisions

```
+----+----------------------------------+----------------------------------+------------------+
| #  | Decision                         | Alternative                      | Why We Chose     |
+----+----------------------------------+----------------------------------+------------------+
| 1  | Chunked upload with presigned    | Server-proxy upload              | Presigned URLs   |
|    | URLs (client -> S3 direct)       | (client -> API -> S3)            | eliminate API     |
|    |                                  |                                  | bandwidth         |
|    |                                  |                                  | bottleneck        |
+----+----------------------------------+----------------------------------+------------------+
| 2  | DAG-based transcoding with       | Sequential transcoding           | Parallel DAG cuts|
|    | segment-level parallelism        | (one resolution at a time)       | transcoding time |
|    |                                  |                                  | from hours to    |
|    |                                  |                                  | minutes          |
+----+----------------------------------+----------------------------------+------------------+
| 3  | Both HLS and DASH (via CMAF)     | HLS only                         | DASH is more     |
|    |                                  |                                  | efficient; HLS   |
|    |                                  |                                  | required for iOS.|
|    |                                  |                                  | CMAF unifies     |
|    |                                  |                                  | segments.        |
+----+----------------------------------+----------------------------------+------------------+
| 4  | Multi-tier CDN (edge/regional/   | Single-tier CDN (edge only)      | Multi-tier       |
|    | shield/origin)                   |                                  | reduces origin   |
|    |                                  |                                  | load by 99%.     |
|    |                                  |                                  | Shield prevents  |
|    |                                  |                                  | thundering herd. |
+----+----------------------------------+----------------------------------+------------------+
| 5  | H.264 + VP9 (skip H.265)        | H.264 + H.265 + VP9 + AV1       | H.265 has patent |
|    |                                  |                                  | issues. AV1 too  |
|    |                                  |                                  | slow to encode   |
|    |                                  |                                  | for all videos.  |
|    |                                  |                                  | H.264 + VP9      |
|    |                                  |                                  | covers 99%.      |
+----+----------------------------------+----------------------------------+------------------+
| 6  | 10-second segments               | 2-second segments                | 10s reduces HTTP |
|    |                                  |                                  | overhead and CDN |
|    |                                  |                                  | entry count. OK  |
|    |                                  |                                  | for VOD.         |
+----+----------------------------------+----------------------------------+------------------+
| 7  | DynamoDB for metadata            | PostgreSQL for metadata          | DynamoDB auto-   |
|    |                                  |                                  | scales, no       |
|    |                                  |                                  | sharding needed, |
|    |                                  |                                  | global tables    |
|    |                                  |                                  | for multi-region.|
+----+----------------------------------+----------------------------------+------------------+
| 8  | Redis for view counts            | DynamoDB atomic counters         | Redis handles    |
|    | (approximate, fast)              | (exact, slower)                  | 100K+ INCR/sec   |
|    |                                  |                                  | per shard.       |
|    |                                  |                                  | Approximate is   |
|    |                                  |                                  | fine for counts. |
+----+----------------------------------+----------------------------------+------------------+
| 9  | Storage tiering                  | Keep everything in S3 Standard   | 65% cost savings |
|    | (Standard/IA/Glacier)            |                                  | at exabyte scale.|
|    |                                  |                                  | 60% of videos    |
|    |                                  |                                  | are never watched|
|    |                                  |                                  | again.           |
+----+----------------------------------+----------------------------------+------------------+
| 10 | Spot instances for transcoding   | On-demand only                   | 70% cheaper.     |
|    |                                  |                                  | Transcoding is   |
|    |                                  |                                  | idempotent,      |
|    |                                  |                                  | tolerates Spot   |
|    |                                  |                                  | interruptions.   |
+----+----------------------------------+----------------------------------+------------------+
```

### 19.2 Latency vs Throughput vs Cost

```
TRANSCODING:
  Fast + Cheap  = Low quality (fewer resolutions, single codec)
  Fast + Quality = Expensive (many GPU workers in parallel)
  Cheap + Quality = Slow (fewer workers, queue builds up)
  
  Our choice: Quality + Moderate Cost + Acceptable Speed
    -> All resolutions + 2 codecs, Spot instances, 15-30 min processing time

CDN:
  More edge POPs  = Lower latency, higher cost
  Fewer edge POPs = Higher latency, lower cost
  
  Our choice: 200+ POPs globally (video is latency-sensitive, justify the cost)

STORAGE:
  Keep all tiers = Higher availability, higher cost
  Aggressive tiering = Lower cost, occasional cold-start latency
  
  Our choice: Aggressive tiering with Glacier Instant Retrieval
    -> 1-5 min restore for cold content is acceptable
    -> Saves 65% on storage costs

CONSISTENCY:
  Strong consistency = Higher latency, lower throughput
  Eventual consistency = Lower latency, higher throughput
  
  Our choice: Eventual consistency for reads (streaming, counts)
              Strong consistency for writes (uploads, job state)
```

---

## 20. Interview Talking Points

### 20.1 Opening Statement (30 seconds)

> "I'll design a video streaming platform like YouTube. The key challenge is the dual pipeline: an upload/transcoding pipeline that converts creator videos into multiple resolution and codec variants, and a streaming pipeline that delivers those variants adaptively via a global CDN. At YouTube scale -- 2 billion users, 500 million DAU, 1 billion hours watched per day, and 500 hours uploaded per minute -- the system must handle petabytes of daily storage growth and hundreds of terabits per second of CDN egress. I'll start with the upload pipeline, dive deep into the transcoding DAG, then walk through adaptive bitrate streaming and CDN architecture."

### 20.2 Key Points to Hit

```
1. UPLOAD PIPELINE (2-3 minutes):
   - Chunked upload (5 MB) with presigned URLs -> direct to S3
   - Resumable: track chunks with Redis bitmap, resume on failure
   - Client never sends bytes through API server (scalability)

2. TRANSCODING (5-7 minutes, THE star):
   - DAG of tasks: extract audio -> split -> parallel transcode -> package
   - Resolution ladder: 240p through 1080p (5 levels)
   - Codecs: H.264 (universal) + VP9 (30% savings)
   - Segment-level parallelism: 60 segments * 10 variants = 600 parallel tasks
   - Cost: ~$0.15 per 10-min video, ~$2.4M/month at YouTube scale
   - Worker pools: Spot instances for cost, auto-scale on queue depth

3. ADAPTIVE BITRATE STREAMING (3-4 minutes):
   - HLS + DASH via CMAF (unified segments, dual manifests)
   - Client ABR algorithm: hybrid (throughput + buffer level)
   - 10-second segments for VOD, 2-4 second for live
   - Startup latency < 2 seconds

4. CDN (3-4 minutes):
   - Multi-tier: edge (200+ POPs) -> regional -> shield -> origin
   - Cache key: videoId/resolution/codec/segment
   - 95% hit rate at edge, 99% cumulative
   - Thundering herd prevention via origin shield (request coalescing)
   - Pre-warming for popular creators

5. SCALE NUMBERS (weave throughout):
   - 720K videos/day uploaded, 2.2 PB/day transcoded output
   - 50M concurrent viewers at peak, 250 Tbps CDN egress
   - 3 EB total storage, $24.5M/month with tiering (65% savings)
```

### 20.3 Common Follow-Up Questions

```
Q: "How do you handle a video that goes viral in 10 minutes?"
A: Origin shield with request coalescing prevents thundering herd.
   First viewer triggers cache fill at edge POP. Subsequent viewers at same POP
   get cache hit. CDN naturally handles viral content well because caching
   effectiveness INCREASES with popularity. The first few seconds are the hardest;
   after that, edge caches absorb the load.

Q: "How does live streaming differ from VOD?"
A: Three key differences:
   (1) Transcoding must be real-time (latency < 5 seconds, not 15 minutes)
       -> Use GPU-accelerated encoding, sacrifice some quality
   (2) Segments are smaller (2-4 seconds for lower latency)
   (3) Manifest is DYNAMIC (new segments appended every 2-4 seconds)
       -> Short manifest TTL (1-2 seconds) on CDN
   Architecture adds a "Live Ingest" service that receives RTMP from creator,
   transcodes in real-time, and writes segments to S3 + updates manifest.
   After broadcast ends, the live stream becomes VOD (manifest becomes static).

Q: "How do you handle DRM (Digital Rights Management)?"
A: DRM is a separate encryption layer applied during transcoding:
   (1) After transcoding, encrypt each segment with AES-128
   (2) Store encryption keys in a DRM license server
   (3) Client requests license key (after authentication)
   (4) Manifest includes encrypted segment URLs + license server URL
   Three main DRM systems: Widevine (Google), FairPlay (Apple), PlayReady (Microsoft)
   Use multi-DRM: encrypt once with CENC (Common Encryption), serve different
   licenses per platform.

Q: "How do you decide which codecs to use for a video?"
A: Encode ALL videos in H.264 (universal fallback). Encode popular videos
   additionally in VP9 (30% bandwidth savings for Chrome/Android users).
   Selective AV1 encoding for top 1% most-viewed videos (50% savings but
   20x encoding cost -- only worth it if video will be watched millions of times).
   The cost/benefit: AV1 encoding a 10-min video costs ~$3 extra but saves
   $0.001 per viewer in CDN bandwidth. Breakeven at ~3,000 viewers.

Q: "What about recommendation quality vs latency?"
A: Two-stage approach:
   (1) Pre-compute candidate pools offline (batch ML pipeline, runs hourly)
       -> Each user has ~1000 candidate videos pre-scored and cached in Redis
   (2) Real-time re-ranking at request time (lightweight model, < 50ms)
       -> Re-score top 100 candidates using fresh signals (time of day, recent watches)
   This gives ML-quality recommendations with < 100ms latency.

Q: "How do you estimate storage costs at this scale?"
A: Key insight: storage tiering is essential.
   - 1B videos * 3 GB average (all variants) = 3 EB total
   - WITHOUT tiering: 3 EB * $0.023/GB/month = $69M/month
   - WITH tiering (10% hot, 30% warm, 50% cold, 10% archive):
     $6.9M + $11.3M + $6M + $0.3M = $24.5M/month (65% savings)
   - Also: delete raw source files after transcoding (save ~30% more)
   - Also: only transcode AV1 for top videos (save encoding cost + storage)

Q: "What is the single most important metric for a streaming platform?"
A: Rebuffering ratio. Studies show that every 1% increase in rebuffering
   reduces viewer engagement by 8%. A viewer who experiences 3+ seconds
   of buffering is 25% likely to abandon the session. The entire architecture
   (CDN, ABR, segment sizing, prefetching) is optimized to keep rebuffering
   below 1% of playback time.
```

### 20.4 Architecture Diagram (Whiteboard-Friendly)

```
Draw this on the whiteboard in 2-3 minutes:

+--------+          +--------+          +--------+
| Upload |  chunks  |   S3   |  event   | Kafka  |
| Client |--------->| (raw)  |--------->|        |
+--------+  direct  +--------+          +---+----+
                                            |
                                    +-------v--------+
                                    |  Transcoding   |
                                    |  Orchestrator  |
                                    |  (DAG)         |
                                    +---+---+---+----+
                                        |   |   |
                                    +---v-+ | +-v---+
                                    |240p | | |1080p|  (parallel workers)
                                    +---+-+ | +-+---+
                                        |   |   |
                                    +---v---v---v----+
                                    |   S3 (output)  |
                                    |  /vid/res/seg  |
                                    +-------+--------+
                                            |
+--------+    manifest    +----------+      |
| Viewer |<-------------->| Streaming|      |
| Client |                | Service  |      |
+---+----+                +----------+      |
    |                                       |
    |  segments   +------+------+------+    |
    +------------>| Edge | Reg  |Shield|<---+
                  | POP  | Hub  |      |
                  +------+------+------+
                       CDN (multi-tier)
```

### 20.5 Time Allocation (45-minute Interview)

```
+-------+------------------------------------------+
| Time  | Topic                                    |
+-------+------------------------------------------+
| 0-2   | Clarify requirements, state scale numbers|
| 2-5   | High-level architecture (draw diagram)   |
| 5-8   | Upload pipeline (chunked, presigned URLs) |
| 8-18  | Transcoding deep dive (DAG, codecs,      |
|       | resolution ladder, parallelism, cost)    |
| 18-25 | ABR streaming (HLS/DASH, algorithms,     |
|       | segment sizing)                          |
| 25-32 | CDN architecture (multi-tier, caching,   |
|       | thundering herd, pre-warming)            |
| 32-38 | Data model, database choices, scaling     |
| 38-42 | Tradeoffs, CAP analysis                  |
| 42-45 | Answer follow-ups                        |
+-------+------------------------------------------+

Key: spend 40% of time on transcoding + ABR (sections 11-12).
     This is what differentiates a senior answer.
```

---

*This design targets a Senior SWE (7+ years) interview level. The transcoding pipeline, adaptive bitrate streaming, and CDN architecture are the three pillars that interviewers evaluate most deeply. Know the resolution ladder, codec tradeoffs, DAG orchestration, ABR algorithms, and multi-tier CDN inside out.*
