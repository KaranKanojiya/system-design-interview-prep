# Video Streaming Platform (YouTube/Netflix) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway (REST/HTTP) + WAF | API Management + Front Door | Cloud Endpoints + Cloud Armor | TLS termination, rate limiting, auth, upload URL signing |
| **Upload Service** | ECS/EKS (Fargate) + S3 Presigned URLs | AKS + Blob Storage SAS Tokens | GKE + Cloud Storage Signed URLs | Chunked upload orchestration, resumable uploads |
| **Object Storage (source)** | S3 (Standard) | Blob Storage (Hot) | Cloud Storage (Standard) | Source video files, chunked uploads, assembled originals |
| **Object Storage (transcoded)** | S3 (Standard + IA + Glacier) | Blob Storage (Hot + Cool + Archive) | Cloud Storage (Standard + Nearline + Coldline) | Tiered: hot for popular, cold for old/rarely-viewed |
| **Transcoding Pipeline** | MediaConvert (file) + Elemental (live) | Media Services | Transcoder API + Live Stream API | Multi-resolution, multi-codec parallel transcoding |
| **Transcoding Orchestration** | Step Functions + Lambda | Durable Functions | Cloud Workflows + Cloud Functions | DAG-based pipeline: validate -> split -> transcode -> package |
| **CDN** | CloudFront (400+ PoPs) | Azure CDN / Front Door | Cloud CDN | Multi-tier edge caching, video segment delivery |
| **Custom CDN (Netflix)** | N/A (Netflix uses Open Connect) | N/A | N/A | ISP-embedded appliances, 90%+ traffic served from ISP PoPs |
| **Video Metadata DB** | DynamoDB (on-demand) | Cosmos DB | Firestore / Bigtable | Video metadata: title, duration, resolution list, status |
| **User DB** | RDS Aurora PostgreSQL | Azure SQL | Cloud SQL / AlloyDB | User profiles, subscriptions, watch history |
| **Cache (metadata)** | ElastiCache Redis | Azure Cache for Redis | Memorystore (Redis) | Hot video metadata, manifest URLs, user session |
| **Cache (CDN origin shield)** | CloudFront Origin Shield | Azure Front Door caching | Cloud CDN origin shield | Reduce origin hits, consolidate cache fills |
| **Message Queue** | SQS + SNS / MSK (Kafka) | Event Hubs / Service Bus | Pub/Sub | Upload events, transcode completion, analytics events |
| **Thumbnail Generation** | Lambda (triggered by S3) | Azure Functions | Cloud Functions | Extract frames at intervals, generate sprite sheets |
| **Recommendation Engine** | SageMaker + Personalize | Azure ML + Personalizer | Vertex AI + Recommendations AI | Collaborative filtering, content-based, hybrid models |
| **Search** | OpenSearch Service | Azure AI Search | Vertex AI Search | Video title, description, tags, transcript search |
| **Live Streaming** | Elemental MediaLive + MediaPackage | Azure Media Services (live) | Live Stream API + Cloud CDN | RTMP ingest -> HLS/DASH packaging -> CDN distribution |
| **Analytics** | Kinesis Data Streams + Redshift | Event Hubs + Synapse | Dataflow + BigQuery | View counts, watch time, ABR quality metrics, buffer rates |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Transcode latency, rebuffer ratio, CDN cache hit rate |
| **DNS** | Route 53 (latency-based routing) | Traffic Manager | Cloud DNS | Multi-region, route to nearest CDN PoP |

---

## Video Upload + Transcoding Pipeline on AWS (Numbered)

```
User uploads a video (e.g., 2GB source file, 1080p, H264)
    |
    1. Client requests upload URL:
       POST /v1/videos/upload-init
       -> Upload Service (ECS Fargate) generates:
          - videoId (Snowflake ID)
          - S3 presigned URLs for each 5MB chunk (400 URLs for 2GB file)
          - Upload session stored in DynamoDB:
            PK=videoId, status=UPLOADING, chunkCount=400, completedChunks=0
    |
    v
    2. Client uploads chunks in parallel (5-10 concurrent):
       PUT s3://uploads/{videoId}/chunk_001  (presigned URL, bypasses API server)
       PUT s3://uploads/{videoId}/chunk_002
       ... (400 chunks, each 5MB, parallel upload)
       |
       On each chunk completion:
         S3 Event Notification -> SQS -> Lambda:
           Increment completedChunks in DynamoDB
           If completedChunks == chunkCount -> trigger assembly
    |
    v
    3. Assembly (Lambda / Step Functions):
       S3 Multipart Upload Complete:
         Concatenate s3://uploads/{videoId}/chunk_* -> s3://source/{videoId}/original.mp4
       Validate:
         - File integrity (checksum)
         - Format detection (container, codec, resolution, duration)
         - Content moderation (Rekognition: nudity, violence scan)
       Update DynamoDB: status = UPLOADED
    |
    v
    4. Transcoding DAG triggered (Step Functions):
       |
       +-- Step 1: VALIDATE
       |     Lambda: verify source file, extract metadata
       |     Output: { duration: 3600s, sourceRes: 1080p, sourceCodec: H264 }
       |
       +-- Step 2: GENERATE RESOLUTION LADDER
       |     Determine target renditions based on source resolution:
       |       Source 1080p -> targets: [240p, 360p, 480p, 720p, 1080p]
       |       Source 4K    -> targets: [240p, 360p, 480p, 720p, 1080p, 4K]
       |     For each resolution, target codecs: [H264, H265, VP9, AV1]
       |     Total jobs: 5 resolutions * 4 codecs = 20 renditions
       |
       +-- Step 3: PARALLEL TRANSCODE (MediaConvert, 20 jobs)
       |     |
       |     +-- MediaConvert Job 1:  240p / H264 -> s3://transcoded/{videoId}/h264/240p/
       |     +-- MediaConvert Job 2:  240p / H265 -> s3://transcoded/{videoId}/h265/240p/
       |     +-- MediaConvert Job 3:  240p / VP9  -> s3://transcoded/{videoId}/vp9/240p/
       |     +-- MediaConvert Job 4:  240p / AV1  -> s3://transcoded/{videoId}/av1/240p/
       |     +-- MediaConvert Job 5:  360p / H264 -> ...
       |     +-- ... (20 parallel jobs)
       |     +-- MediaConvert Job 20: 1080p / AV1 -> s3://transcoded/{videoId}/av1/1080p/
       |     |
       |     Each job:
       |       Input:  s3://source/{videoId}/original.mp4
       |       Output: segmented video files (2-10 second segments)
       |               segment_00001.ts, segment_00002.ts, ... (HLS)
       |               segment_00001.m4s, segment_00002.m4s, ... (DASH)
       |       Time: 10-30 min per job (parallel = same wall-clock time)
       |
       +-- Step 4: GENERATE THUMBNAILS (parallel with transcoding)
       |     Lambda: extract frames at 0s, 10s, 30s, 60s, every 60s
       |     Generate sprite sheet for seek preview (1 image, 10x10 grid)
       |     Store: s3://thumbnails/{videoId}/thumb_001.jpg, sprite.jpg
       |
       +-- Step 5: PACKAGE MANIFESTS
       |     Generate HLS master playlist (master.m3u8):
       |       #EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=426x240
       |       h264/240p/playlist.m3u8
       |       #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
       |       h264/1080p/playlist.m3u8
       |     Generate DASH manifest (manifest.mpd):
       |       <AdaptationSet> with <Representation> per resolution
       |     Store: s3://manifests/{videoId}/master.m3u8, manifest.mpd
       |
       +-- Step 6: FINALIZE
             Update DynamoDB:
               status = READY
               manifestUrl = "https://cdn.example.com/{videoId}/master.m3u8"
               resolutions = [240p, 360p, 480p, 720p, 1080p]
               codecs = [H264, H265, VP9, AV1]
               duration = 3600
             Publish SNS event: VIDEO_READY (triggers notification, search index, recommendation update)
             Invalidate CloudFront cache if re-transcode
    |
    v
    5. Video is now playable.
       Total pipeline time: 15-45 minutes (depending on source length/quality)
       Cost per video: ~$0.05-$0.50 (depends on duration, resolution count)
```

---

## Streaming Architecture on AWS (Numbered)

```
User opens video player and presses play:
    |
    1. Client requests video manifest:
       GET https://cdn.example.com/{videoId}/master.m3u8
       |
       v
       Route 53: latency-based routing -> nearest CloudFront PoP
       CloudFront edge cache:
         Cache HIT -> return manifest immediately (< 10ms)
         Cache MISS -> Origin Shield -> S3 origin -> cache + return
    |
    v
    2. Client (player) parses manifest:
       master.m3u8 lists available streams:
         240p @ 300 Kbps  (h264/240p/playlist.m3u8)
         360p @ 700 Kbps  (h264/360p/playlist.m3u8)
         480p @ 1.5 Mbps  (h264/480p/playlist.m3u8)
         720p @ 3 Mbps    (h264/720p/playlist.m3u8)
         1080p @ 6 Mbps   (h264/1080p/playlist.m3u8)
       |
       ABR algorithm selects initial quality:
         Measured bandwidth = 5 Mbps -> pick 720p (3 Mbps, safe margin)
    |
    v
    3. Client fetches sub-playlist for 720p:
       GET https://cdn.example.com/{videoId}/h264/720p/playlist.m3u8
       Response: list of segment URLs:
         #EXTINF:6.0
         segment_00001.ts
         #EXTINF:6.0
         segment_00002.ts
         ... (600 segments for a 1-hour video at 6s segments)
    |
    v
    4. Client downloads segments sequentially:
       GET https://cdn.example.com/{videoId}/h264/720p/segment_00001.ts
       |
       CloudFront edge cache:
         Popular video (top 10% by views) -> cache HIT (< 5ms, edge)
         Less popular -> Origin Shield cache HIT (< 30ms)
         Cold video -> S3 origin (< 100ms, cached on return)
       |
       Player decodes segment, displays first frame. Playback starts.
    |
    v
    5. ABR adaptation loop (continuous during playback):
       |
       For each downloaded segment:
         Measure: download_time, segment_size
         Compute: throughput = segment_size / download_time
         |
         +-- Throughput-based ABR:
         |     estimated_bandwidth = EWMA(throughput, alpha=0.3)
         |     Pick highest resolution where bitrate < 0.8 * estimated_bandwidth
         |     Example: bandwidth=8Mbps -> pick 1080p (6Mbps < 6.4Mbps threshold)
         |
         +-- Buffer-based ABR:
         |     buffer_level = seconds of video buffered ahead
         |     If buffer < 5s:  drop to lowest resolution (prevent rebuffer)
         |     If buffer 5-15s: maintain current resolution
         |     If buffer > 15s: try next higher resolution
         |
         +-- Hybrid ABR (Netflix, YouTube use this):
               Combine throughput + buffer signals
               Low buffer overrides throughput (safety first, prevent rebuffer)
       |
       v
    6. Quality switch (mid-stream):
       Bandwidth drops: 8 Mbps -> 2 Mbps (user switches to cellular)
       |
       ABR detects: throughput < current bitrate (6 Mbps > 2 Mbps)
       Buffer is draining (15s -> 10s -> 5s)
       |
       Switch to 480p (1.5 Mbps):
         Next segment fetch: h264/480p/segment_00127.ts (instead of 720p)
         Seamless switch: segments are independently decodable
         User sees brief quality drop but no rebuffer (buffering spinner)
    |
    v
    7. Analytics events (async, non-blocking):
       Every 30 seconds, client sends:
         POST /v1/analytics/heartbeat
         Body: {
           videoId, userId, currentTime, resolution, bufferHealth,
           rebufferCount, bitrateKbps, deviceType, connectionType
         }
       -> Kinesis Data Streams -> Lambda -> Redshift (batch analytics)
       -> Real-time: Kinesis -> Lambda -> CloudWatch (rebuffer rate alarm)

    8. Seek (user scrubs to 45:00):
       |
       Calculate target segment: 45*60 / 6 = segment_00450
       Fetch: GET cdn.example.com/{videoId}/h264/720p/segment_00450.ts
       |
       Thumbnail preview during seek:
         Client loads sprite sheet from CDN
         Maps seek position to sprite grid cell
         Shows thumbnail preview under scrubber
```

---

## Netflix Open Connect Architecture

```
Netflix doesn't use AWS CloudFront for video delivery.
They built a CUSTOM CDN called "Open Connect" -- ISP-embedded appliances.

WHY CUSTOM CDN?
  - Video = 90%+ of Netflix traffic
  - CloudFront charges per GB transferred (~$0.02-$0.085/GB)
  - At Netflix scale (400+ Tbps peak): $100M+/month for CloudFront
  - Open Connect eliminates CDN bandwidth cost almost entirely

ARCHITECTURE:

  +------------------------------------------------------------------+
  |                     Netflix Backend (AWS)                         |
  |  API servers, recommendation engine, user data, billing           |
  |  (runs on AWS EC2, S3, DynamoDB -- everything EXCEPT video)       |
  +----------------------------+-------------------------------------+
                               |
         1. Nightly content    |    2. Control plane
            push (off-peak)    |       (manifests, routing decisions)
                               |
  +----------------------------v-------------------------------------+
  |                     Open Connect Appliances (OCAs)                |
  |                                                                    |
  |   +----------+     +----------+     +----------+     +----------+ |
  |   |  OCA     |     |  OCA     |     |  OCA     |     |  OCA     | |
  |   |  Comcast |     |  OCA     |     |  OCA     |     |  OCA     | |
  |   |  Chicago |     |  AT&T    |     |  Verizon |     |  BT      | |
  |   |          |     |  Dallas  |     |  NYC     |     |  London  | |
  |   | 100-200TB|     | 100-200TB|     | 100-200TB|     | 100-200TB| |
  |   | SSD+HDD  |     | SSD+HDD |     | SSD+HDD  |     | SSD+HDD | |
  |   +----------+     +----------+     +----------+     +----------+ |
  |                                                                    |
  |   15,000+ appliances in 6,000+ ISP locations worldwide            |
  |   Each: custom FreeBSD server, 100-200TB storage, 100 Gbps NIC   |
  |   Serves 90%+ of all Netflix video traffic                        |
  +------------------------------------------------------------------+

  HOW IT WORKS (Numbered):

      1. Content Preparation (AWS, nightly):
         Netflix encodes all new content into multiple renditions
         (same resolution ladder: 240p-4K, multiple codecs)
         Content stored in S3 as the source of truth

      2. Content Distribution (off-peak hours, 2-6 AM local):
         Popularity prediction model decides WHAT goes WHERE:
           - "Stranger Things S5" -> push to ALL 15,000 OCAs (global hit)
           - "Niche Korean drama" -> push to OCAs in Korea, Asian-diaspora cities
           - "Regional Bollywood film" -> push to India OCAs + UK/US-Indian-heavy OCAs
         BGP-based peer-fill: OCAs share content with nearby OCAs (avoid re-downloading from S3)

      3. User Presses Play:
         Client -> Netflix API (AWS):
           "I want to watch videoId=12345, I'm on Comcast Chicago"
         |
         v
      4. Steering Service (AWS) computes best OCA:
         Input: user's ISP, city, current OCA load, OCA health
         Output: ranked list of OCAs:
           1st: oca-comcast-chicago-01 (same ISP, same city -- 0 hops)
           2nd: oca-comcast-chicago-02 (backup, same location)
           3rd: oca-level3-chicago-01 (different ISP, same city)
           Fallback: AWS CloudFront (only if all OCAs fail -- rare)
         |
         v
      5. Client receives manifest with OCA URLs:
         master.m3u8 with segment URLs pointing to:
           https://oca-comcast-chicago-01.netflix.com/{videoId}/h265/1080p/seg_001.ts
         |
         v
      6. Client streams directly from OCA:
         Traffic stays WITHIN the ISP network (never crosses internet backbone)
         Latency: < 5ms (vs 30-100ms for CloudFront)
         Bandwidth: essentially free for Netflix (ISP hosts the hardware)
         ISP benefits: reduced peering traffic, better user experience

  COST COMPARISON:
    CloudFront at Netflix scale:
      - 400 Tbps peak * 3600 * 24 * 30 = ~130 EB/month
      - At $0.02/GB (bulk discount) = $2.6B/month (IMPOSSIBLE)

    Open Connect:
      - Hardware: $5,000-$10,000 per OCA (amortized over 5 years)
      - 15,000 OCAs * $10K / 60 months = ~$2.5M/month hardware
      - Electricity, maintenance: ~$5M/month
      - Total: ~$7.5M/month (vs BILLIONS for CDN)

  WHY YOUTUBE DOESN'T DO THIS:
    YouTube serves user-generated content (billions of unique videos)
    Netflix serves curated catalog (~15,000 titles)
    YouTube can't predict what's popular (long tail) -- needs centralized CDN
    Netflix can predict and pre-position content (small catalog, predictable demand)
```

---

## Multi-Region Video Serving

```
                         +-------------------------------+
                         |       Route 53 (DNS)          |
                         |  Latency-based routing:       |
                         |  US users -> us-east-1 CDN    |
                         |  EU users -> eu-west-1 CDN    |
                         |  Asia users -> ap-northeast-1  |
                         +------+--------+--------+-----+
                                |        |        |
              +-----------------v--+ +---v--------v-----------+
              |    us-east-1       | |  eu-west-1 / ap-ne-1   |
              |    (PRIMARY)       | |  (REGIONAL)             |
              |                    | |                          |
              |  API GW + WAF     | |  API GW + WAF            |
              |  Upload Service   | |  Upload Service           |
              |  Transcode Workers| |  Transcode Workers        |
              |  Metadata (Dynamo)| |  Metadata (Global Table) |
              |  ElastiCache Redis| |  ElastiCache Redis       |
              |  S3 (source of    | |  S3 (cross-region        |
              |   truth)          | |   replication)           |
              +--------------------+ +--------------------------+
                       |                          |
                       +--- CloudFront (global) --+
                       |   400+ PoPs worldwide    |
                       |   Origin: S3 in nearest  |
                       |   region                 |
                       +--------------------------+

MULTI-REGION FLOW (Numbered):

    1. UPLOAD: routed to nearest region
       User in London uploads video -> eu-west-1 (not us-east-1)
       Source file stored in S3 eu-west-1
       Transcoding happens in eu-west-1 (reduce data transfer cost)
       Transcoded files replicated to other regions via S3 Cross-Region Replication

    2. METADATA: DynamoDB Global Tables
       Video metadata written in eu-west-1 -> auto-replicated to us-east-1, ap-northeast-1
       Replication lag: < 1 second
       Any region can serve metadata reads locally

    3. STREAMING: served from nearest CloudFront PoP
       User in Tokyo plays video:
         DNS resolves to Tokyo CloudFront PoP
         Edge cache HIT (popular video) -> served from Tokyo edge (< 5ms)
         Edge cache MISS -> Origin Shield (ap-northeast-1) -> S3 ap-northeast-1
         Next request for same segment: Tokyo edge serves it (cached)

    4. CACHE WARMING for new releases:
       When a popular video goes READY:
         Proactively push segments to CloudFront edge caches in top 50 PoPs
         "Stranger Things S5 Episode 1" -> warm caches 30 minutes before release
         Prevents thundering herd on origin at launch time

    5. FAILOVER:
       If us-east-1 goes down:
         Route 53 health check detects failure (30 seconds)
         DNS failover to eu-west-1
         Uploads and transcoding continue in eu-west-1
         Streaming unaffected (CloudFront + S3 in eu-west-1 serve video)
         Metadata reads from DynamoDB Global Table in eu-west-1
```

---

## Cost Estimation at Scale (YouTube-scale: 500M DAU)

### Assumptions

```
Daily Active Users:           500,000,000 (500M DAU)
Video uploads/day:            500,000 (500K new videos)
Average video length:         7 minutes
Average source file size:     500 MB
Total upload storage/day:     500K * 500MB = 250 TB/day
Transcoded renditions:        20 per video (5 resolutions * 4 codecs)
Transcoded storage/day:       250TB * 3x (renditions avg) = 750 TB/day
Total storage growth/month:   (250 + 750) TB * 30 = 30 PB/month
Video views/day:              5,000,000,000 (5B views)
Average watch time/view:      4 minutes
Average bitrate:              3 Mbps (mix of resolutions)
Total bandwidth/day:          5B * 4min * 60s * 3Mbps / 8 = ~1.125 EB/day
CDN bandwidth/month:          ~33.75 EB/month
```

### Monthly Cost Breakdown (AWS -- without custom CDN)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **S3 (source storage)** | 250 TB/day * 30 = 7.5 PB new/month. Total: ~100 PB (with history). Tiered: 10 PB hot, 30 PB warm (IA), 60 PB cold (Glacier) | ~$1,500,000 |
| **S3 (transcoded storage)** | 750 TB/day * 30 = 22.5 PB new/month. Total: ~200 PB. Same tiering | ~$3,000,000 |
| **MediaConvert (transcoding)** | 500K videos/day * 20 renditions * 7 min avg = 70M transcode-minutes/day | ~$4,200,000 |
| **ECS Fargate (Upload Service)** | 200 tasks, 4 vCPU / 8 GB | ~$88,000 |
| **ECS Fargate (API + Metadata)** | 300 tasks, 4 vCPU / 8 GB | ~$132,000 |
| **Step Functions (transcoding orchestration)** | 500K workflows/day * 6 steps = 90M state transitions/month | ~$2,250 |
| **Lambda (thumbnails, chunk tracking)** | 500K * 400 chunks + 500K * 20 thumbnails = 210M invocations/month | ~$45,000 |
| **DynamoDB (video metadata)** | 500K writes/day + 5B reads/day, ~50 TB storage | ~$400,000 |
| **ElastiCache Redis (metadata cache)** | 50 shards, r6g.2xlarge, 1 replica each | ~$175,000 |
| **CloudFront (CDN)** | 33.75 EB/month at bulk pricing... | **$50,000,000+** |
| **SageMaker (recommendations)** | 100 ml.c5.4xlarge endpoints, real-time inference | ~$240,000 |
| **OpenSearch (search)** | 30 data nodes, r6g.2xlarge | ~$100,000 |
| **Kinesis + Redshift (analytics)** | 5B events/day ingestion + 100 TB warehouse | ~$300,000 |
| **Route 53 + CloudWatch** | DNS, monitoring, tracing | ~$50,000 |
| **Data transfer (inter-region)** | S3 cross-region replication, ~30 PB/month | ~$600,000 |
| **Total (with CloudFront CDN)** | | **~$60,000,000+/month** |

### Why Netflix / YouTube Build Custom CDN

```
CDN bandwidth dominates cost at video scale.

CloudFront at 33.75 EB/month:
  Even at $0.01/GB (extreme bulk discount):
  33.75 * 10^9 GB * $0.01 = $337,500,000/month

Netflix Open Connect:
  Hardware + ops: ~$7.5M/month
  Savings: $330M+/month

YouTube's strategy:
  Google owns the network (Google Global Cache at ISPs)
  Similar to Open Connect but leverages Google's ISP partnerships
  GGC (Google Global Cache) boxes sit in ISPs, serve YouTube + other Google content

LESSON FOR INTERVIEW:
  "At small scale (< 10M DAU), CloudFront is the right choice -- managed, no ops.
   At YouTube/Netflix scale, CDN bandwidth cost forces you to build custom infrastructure.
   This is a classic build-vs-buy trade-off driven by unit economics."
```

### Cost at Different Scales

| Scale | DAU | CDN Strategy | Monthly Cost | Cost/DAU |
|-------|-----|-------------|-------------|---------|
| Startup | 100K | CloudFront | ~$15,000 | $0.150 |
| Growth | 10M | CloudFront | ~$500,000 | $0.050 |
| Scale | 100M | CloudFront + Origin Shield | ~$8,000,000 | $0.080 |
| YouTube-scale | 500M | Custom CDN (mandatory) | ~$15,000,000 | $0.030 |
| Netflix-scale | 250M | Open Connect | ~$10,000,000 | $0.040 |

### Cost Optimization Strategies

1. **Storage Tiering** -- Move videos to S3-IA after 30 days, Glacier after 90 days. 80% of views happen in first 7 days. Saves 60%+ on storage.
2. **Per-Title Encoding (Netflix)** -- Analyze content complexity. Cartoons need fewer bits than sports. Reduce storage and bandwidth by 20-30%.
3. **Codec Selection** -- Serve AV1 to supporting browsers (60% smaller than H264). Save 40% bandwidth for modern clients.
4. **Lazy Transcoding** -- Don't transcode all resolutions immediately. Start with 480p/720p/1080p. Transcode 240p and 4K only if demand warrants.
5. **Spot Instances for Transcoding** -- Transcoding is batch, retry-safe. Use Spot for 60-70% compute savings.
6. **Intelligent Cache Warming** -- Pre-warm CDN for predicted popular videos. Avoid origin thundering herd.
7. **Regional Transcoding** -- Transcode in the region where the video was uploaded. Avoid cross-region data transfer for source files.
8. **Dedup at Upload** -- Hash-based dedup (content fingerprinting). Reject re-uploads of existing content. YouTube does this for copyright + cost savings.

---

## Interview Tip

> "For a YouTube-scale video streaming platform on AWS, I'd build a **chunked upload pipeline** using **S3 presigned URLs** (5MB chunks, parallel, resumable -- bypasses the API server entirely). Transcoding is orchestrated by **Step Functions** as a parallel DAG: the source video is transcoded into a **resolution ladder** (240p-4K) across **multiple codecs** (H264/H265/VP9/AV1) using **MediaConvert** -- 20 renditions in parallel. Each rendition is segmented into 6-second chunks for **HLS/DASH adaptive bitrate streaming**. The client downloads a **manifest** (master.m3u8), picks the highest resolution fitting its bandwidth, and **switches mid-stream** as conditions change (throughput-based + buffer-based hybrid ABR). Segments are served via **CloudFront CDN** with a multi-tier cache (edge -> origin shield -> S3). At extreme scale, CDN bandwidth cost forces a custom solution like **Netflix Open Connect** -- ISP-embedded appliances serving 90%+ of traffic. Storage is tiered: **S3 Standard** for hot content, **S3-IA** after 30 days, **Glacier** after 90 days. The system is **AP for streaming** (buffer a few extra segments, tolerate brief staleness) and **CP for upload state** (chunk tracking must be consistent to avoid data loss)."

This shows you understand **chunked upload, transcoding pipelines, adaptive bitrate streaming, CDN architecture, storage tiering, and the custom CDN trade-off** -- the six pillars of video streaming design.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Video upload | S3 + Presigned URLs | 5MB chunks, multipart upload | Bypass API server, parallel + resumable |
| Transcoding | MediaConvert | 20 jobs per video, parallel | Managed, pay-per-minute, multi-codec support |
| Transcoding orchestration | Step Functions | Parallel state for renditions | DAG-based, handles retries, tracks progress |
| Thumbnail generation | Lambda (S3 trigger) | Extract frames + sprite sheet | Event-driven, scales to zero, cheap |
| Video storage (hot) | S3 Standard | First 30 days | Low latency access for popular content |
| Video storage (warm) | S3 Infrequent Access | 30-90 days old | 40% cheaper, acceptable retrieval latency |
| Video storage (cold) | S3 Glacier | 90+ days old | 80% cheaper, minutes retrieval (acceptable for old content) |
| Segment delivery | CloudFront | 400+ PoPs, Origin Shield | Multi-tier edge caching, < 10ms for popular segments |
| Custom CDN (at scale) | Open Connect / GGC | ISP-embedded appliances | Eliminates CDN bandwidth cost at massive scale |
| Video metadata | DynamoDB (Global Tables) | PK=videoId, on-demand | Auto-scales, multi-region replication, low latency |
| Metadata cache | ElastiCache Redis | Manifest URLs, video info | Sub-ms metadata lookups, reduce DynamoDB reads |
| Recommendations | SageMaker + Personalize | Collaborative + content-based | Real-time personalized recommendations |
| Search | OpenSearch | Title, tags, transcript index | Full-text search with relevance ranking |
| Analytics | Kinesis + Redshift | Stream ingest + warehouse | Real-time quality metrics + batch analytics |
| Live streaming | Elemental MediaLive + MediaPackage | RTMP ingest -> HLS/DASH | Managed live transcoding + packaging |
