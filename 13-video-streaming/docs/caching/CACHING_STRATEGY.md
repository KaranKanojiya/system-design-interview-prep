# Caching Strategy -- Video Streaming Platform (YouTube/Netflix)

> Every cache layer in the system, from the CDN edge (THE primary cache for video)
> to metadata caching, view count aggregation, recommendation caching, and
> cold-to-hot storage tiering. Interview-ready with Redis commands, TTL policies,
> cache warming strategies, and the full request flow through all cache layers.
>
> **Key insight:** Unlike most systems where Redis is "the cache," in video
> streaming **CDN is THE cache**. Redis handles metadata and counters, but the
> CDN edge is where 95% of bytes are served from.

---

## Cache Layer Overview

```
  GET /videos/vid-42/1080p/segment_005.ts
       |
       v
  +-------------------+
  | CDN Edge Cache    |  Tier 1: city-level PoP, 95% hit rate     <-- THE CACHE
  | (CloudFront/      |  100 TB per PoP, 1-year TTL for segments
  |  Akamai edge)     |
  +-------------------+
       | miss (5%)
       v
  +-------------------+
  | CDN Regional      |  Tier 2: continent-level, 99% cumulative hit rate
  | Shield            |  Aggregates misses from 20-50 edge PoPs
  +-------------------+
       | miss (1%)
       v
  +-------------------+
  | Origin (S3)       |  Tier 3: always has the data
  |                   |  Durability: 11 nines
  +-------------------+

  GET /api/videos/vid-42 (metadata)
       |
       v
  +-------------------+
  | Metadata Cache    |  Redis Hash, 5-min TTL
  | (Redis)           |  Key: meta:{videoId}
  +-------------------+
       | miss
       v
  +-------------------+
  | PostgreSQL        |  Source of truth for metadata
  +-------------------+

  GET /api/videos/vid-42/viewcount
       |
       v
  +-------------------+
  | View Count Cache  |  Redis String (counter), real-time
  | (Redis INCR)      |  Key: viewcount:{videoId}
  +-------------------+
       | flush every 30s
       v
  +-------------------+
  | PostgreSQL        |  Durable aggregate
  +-------------------+

  GET /api/recommendations?userId=user-42
       |
       v
  +-------------------+
  | Recommendation    |  Redis String (JSON), 1-hour TTL
  | Cache (Redis)     |  Key: recs:{userId}
  +-------------------+
       | miss
       v
  +-------------------+
  | ML Model Server   |  TF Serving / custom inference
  +-------------------+
```

---

## 1. CDN Edge Cache -- THE Primary Cache

### Why CDN is THE Cache for Video

In most systems (e-commerce, social media), the primary cache is Redis or
Memcached. In video streaming, **the CDN IS the cache**. Here is why:

```
Data volume comparison:

  Metadata request:  ~2 KB per video (JSON)
  Video segment:     ~2 MB per segment (4 seconds at 4 Mbps)

  Ratio: video data is 1000x larger than metadata
  -> 99.9% of bytes served are video segments
  -> CDN edge is where those bytes come from

  Redis caching metadata saves microseconds.
  CDN caching segments saves seconds and terabytes of bandwidth.
```

### CDN Cache Key Design

```
Cache Key Structure:

  /{videoId}/{resolution}_{codec}/segment_{N}.{ext}

  Examples:
    /vid-42/1080p_h264/segment_005.ts     (HLS segment)
    /vid-42/720p_h264/segment_005.ts
    /vid-42/1080p_vp9/segment_005.m4s     (DASH/CMAF segment)
    /vid-42/master.m3u8                    (HLS master manifest)
    /vid-42/1080p_h264/playlist.m3u8      (HLS media playlist)
    /vid-42/thumbnails/default.jpg         (thumbnail)

  Why this structure:
    - Resolution in path: CDN can cache each quality independently
    - Codec in path: different codecs served to different clients
    - Segment number: each segment is independently cacheable
    - videoId as prefix: easy to invalidate all content for a video
```

### CDN TTL Policy

| Content Type | TTL | Invalidation | Rationale |
|-------------|-----|-------------|-----------|
| Video segments (VOD) | 1 year | Never (immutable) | Transcoded segments never change |
| HLS master manifest (VOD) | 5 minutes | On quality addition | Rarely changes after initial transcode |
| HLS media playlist (VOD) | 5 minutes | On segment addition | Static after transcoding complete |
| HLS manifest (live) | 2-4 seconds | TTL-based only | Must update every segment duration |
| Thumbnails | 24 hours | Explicit on change | Rarely updated |
| Player JS/CSS | 1 year | Versioned URLs | Cache-bust via filename versioning |
| API responses (metadata) | 0 (no CDN cache) | N/A | Served from Redis, not CDN |

### Zipf Distribution and Cache Efficiency

```
Zipf Distribution (Power Law) for Video Views:

  Rank    | % of Total Views | Cumulative
  --------+------------------+-----------
  Top 1%  |      50%         |    50%
  Top 10% |      90%         |    90%
  Top 20% |      95%         |    95%
  Top 50% |      99%         |    99%
  Bottom 50% |    1%          |   100%

  Visualization:

  Views
  ^
  |*
  |*
  |**
  | **
  |  ***
  |    ****
  |       ********
  |              **********************
  +----------------------------------------> Video rank
   (most popular)            (least popular)

  Implication for CDN caching:
    Edge cache stores top 0.04% of videos (by storage)
    But serves 90% of all segment requests
    -> 95% edge cache hit rate with modest storage
```

### CDN Cache Hit Rate by Content Type

```
Content Type          | Edge Hit Rate | Regional Hit Rate | Why
----------------------+---------------+-------------------+------------------
Popular video segments|    99%        |      99.9%        | Zipf: head content
Long-tail segments    |    60%        |      95%          | Occasional access
Thumbnails (popular)  |    99%        |      99.9%        | Small, frequently accessed
Thumbnails (long-tail)|    70%        |      90%          | Large catalog
HLS manifests (VOD)   |    95%        |      99%          | Small file, frequent access
HLS manifests (live)  |    50%        |      80%          | Short TTL (2-4 seconds)
                      |               |                   |
Overall weighted      |    95%        |      99%          | Dominated by popular segments
```

### Interview One-Liner

> "CDN is THE cache for video streaming. 95% of segment requests are served
> from edge PoPs. Zipf distribution means 10% of videos generate 90% of views,
> so a 100 TB edge cache serves nearly all traffic. Video segments get 1-year
> TTL because they are immutable. Manifests get 5-minute TTL for VOD, 2-second
> TTL for live."

---

## 2. Video Metadata Cache -- Redis

### Data Model

```
Key:    meta:{videoId}
Type:   Redis Hash
TTL:    5 minutes
Fields:
  title         -> "How to Build a Video Streaming Platform"
  description   -> "System design interview prep..."
  uploaderId    -> "user-42"
  uploaderName  -> "Karan"
  duration      -> "600"
  viewCount     -> "12345"
  likeCount     -> "890"
  thumbnailUrl  -> "https://cdn.example.com/vid-42/thumb.jpg"
  status        -> "READY"
  uploadedAt    -> "2024-01-15T10:30:00Z"
  resolution    -> "1080p,720p,480p,360p"
```

### Cache Operations

| Operation | Redis Command | Complexity | Use Case |
|-----------|--------------|------------|----------|
| Get full metadata | `HGETALL meta:{videoId}` | O(N) fields | Video page load |
| Get single field | `HGET meta:{videoId} title` | O(1) | Search result rendering |
| Cache metadata | `HSET meta:{videoId} title "..." desc "..." ...` | O(N) fields | Cache population |
| Set TTL | `EXPIRE meta:{videoId} 300` | O(1) | 5-minute TTL |
| Check exists | `EXISTS meta:{videoId}` | O(1) | Cache hit check |
| Invalidate | `DEL meta:{videoId}` | O(1) | Metadata update |

### Cache-Aside Pattern

```
Metadata Read Path:

  1. GET request for video metadata
       |
       v
  2. HGETALL meta:{videoId}
       |
       +---> Cache hit? Return cached metadata  (< 1ms)
       |
       +---> Cache miss?
               |
               v
            3. SELECT * FROM videos WHERE video_id = ?  (5-50ms)
               |
               v
            4. HSET meta:{videoId} title "..." desc "..." ...
               EXPIRE meta:{videoId} 300
               |
               v
            5. Return metadata

  Cache invalidation on update:
    - User edits title/description -> DEL meta:{videoId}
    - Next read triggers cache population from DB
    - View count NOT invalidated (updated separately via INCR)
```

### Why 5-Minute TTL?

```
Too short (30 seconds):
  - High miss rate on long-tail videos
  - More DB load for videos viewed once per minute
  - Each miss adds 5-50ms latency

Too long (1 hour):
  - Metadata updates (title change, description edit) take 1 hour to reflect
  - Users see stale data for too long after edits

5 minutes is the sweet spot:
  - Popular videos: cache hit rate > 99% (many reads within 5 min)
  - Long-tail: miss rate acceptable (1 read -> populate -> expire -> 1 miss/5min)
  - Staleness: 5 minutes max for metadata changes
  - DB protection: limits origin reads to 1 per 5 minutes per video
```

### Our Java Implementation vs Production

| Aspect | Our Java Code | Production |
|--------|--------------|------------|
| Storage | `ConcurrentHashMap<String, Video>` | Redis Hash with 5-min TTL |
| Hit | `map.get(videoId)` | `HGETALL meta:{videoId}` |
| Miss | Always hits (in-memory, all data present) | DB query + cache population |
| TTL | None (never expires) | `EXPIRE meta:{videoId} 300` |
| Invalidation | Direct update in map | `DEL meta:{videoId}` |

### Interview Talking Point

> "Video metadata is cached in Redis Hash with 5-minute TTL using cache-aside.
> HGETALL returns the full metadata in one round-trip. On edit, we invalidate
> with DEL and let the next read repopulate. View counts are NOT in this cache --
> they use a separate Redis INCR counter for real-time accuracy."

---

## 3. View Count Cache -- Redis INCR + Async Flush

### Why a Separate Counter?

View count is updated on every single video view -- potentially 50,000+/second
for viral videos. Putting it in the metadata hash and updating on every view
would cause write contention and TTL-based staleness. Instead, we use a
dedicated Redis counter.

### Data Model

```
Key:    viewcount:{videoId}
Type:   Redis String (integer)
TTL:    None (persisted, flushed to DB)

Key:    likecount:{videoId}
Type:   Redis String (integer)
TTL:    None

Key:    viewcount:flush:pending
Type:   Redis Set (set of videoIds with pending flushes)
TTL:    None
```

### Write Path

```
View Count Write Path:

  User watches video
       |
       v
  1. INCR viewcount:{videoId}              <-- O(1), < 0.1ms
       |
       v
  2. SADD viewcount:flush:pending {videoId} <-- Track dirty keys
       |
       v
  3. Return to caller immediately

  No DB write on the hot path!
  Total latency added: < 0.2ms
```

### Flush Path (Background)

```
Background Flush Job (every 30 seconds):

  1. SMEMBERS viewcount:flush:pending
     -> Returns set of videoIds with pending changes
       |
       v
  2. For each videoId in the pending set:
     GETSET viewcount:{videoId} 0          <-- Atomic: get current, reset to 0
     -> Returns delta (e.g., 1,247 views since last flush)
       |
       v
  3. Batch UPDATE:
     UPDATE videos SET view_count = view_count + ?
     WHERE video_id = ?
     (batched: 100 updates per SQL statement)
       |
       v
  4. SREM viewcount:flush:pending {videoId}
     (remove from pending set after successful DB write)
       |
       v
  5. If DB write fails:
     - Do NOT remove from pending set
     - Delta is LOST (GETSET already reset to 0)
     - Fix: use INCRBY negative to restore, or accept minor loss

  Race condition handling:
    Between GETSET (step 2) and next INCR by a viewer:
    - New INCRs accumulate from 0
    - No views are lost (GETSET is atomic)
    - At most one flush cycle of delay (30 seconds)
```

### Read Path

```
View Count Read Path:

  Client requests video metadata
       |
       v
  Option A: Read from Redis (most current)
    GET viewcount:{videoId}
    -> Returns real-time count (includes unflushed delta)
    -> Latency: < 0.1ms

  Option B: Read from DB (slightly stale)
    SELECT view_count FROM videos WHERE video_id = ?
    -> Returns last-flushed count (up to 30 seconds behind)
    -> Latency: 5-50ms

  Option C: Read from metadata cache (most stale)
    HGET meta:{videoId} viewCount
    -> Returns count from when metadata was cached (up to 5 min)
    -> Only use for non-critical display

  Production approach:
    Video page: Option A (Redis GET) for display
    Analytics dashboard: Option B (DB) for accuracy
    Search results: Option C (metadata cache) for performance
```

### Capacity Planning

```
Redis Memory for View Counts:

  Key:   "viewcount:vid-42" = ~20 bytes key + ~8 bytes value = ~28 bytes
  Videos with any views in last 30 days: ~50M videos
  Memory: 50M * 28 bytes = ~1.4 GB

  Trivial. View counts for the entire platform fit in one Redis node.

  Throughput:
    Peak: 500K views/second globally
    Redis INCR: 100K+ ops/second per shard
    Need: 5-10 Redis shards for view counts (with headroom)
```

### Interview One-Liner

> "View counts use Redis INCR on the hot path (O(1), <0.1ms) and flush to
> the database every 30 seconds in batch. GETSET atomically reads and resets
> the counter. This turns 50K writes/second into one batch UPDATE per video.
> Staleness is at most 30 seconds, which is invisible at '1.2M views'."

---

## 4. Manifest Cache -- CDN Edge

### VOD Manifest Caching

```
VOD Manifest Cache (HLS):

  master.m3u8 (master manifest)
    TTL: 5 minutes at CDN edge
    Size: ~500 bytes
    Changes: only when new resolution added (rare)
    Cache key: /vid-42/master.m3u8

  1080p/playlist.m3u8 (media playlist)
    TTL: 5 minutes at CDN edge
    Size: ~2 KB (lists all segments for this resolution)
    Changes: never after transcoding complete
    Cache key: /vid-42/1080p_h264/playlist.m3u8

  Why 5-minute TTL (not 1 year like segments)?
    - Manifest might be updated if we add AV1 variant later
    - 5 minutes is negligible staleness vs 1-year risk of stale manifest
    - Manifest is tiny (< 5 KB), so origin pulls are cheap
```

### Live Manifest Caching

```
Live Manifest Cache (HLS):

  master.m3u8 (master manifest)
    TTL: 30 seconds (quality levels rarely change mid-stream)
    Same as VOD master manifest

  1080p/playlist.m3u8 (media playlist -- THIS IS DIFFERENT)
    TTL: 2 seconds (MUST be < segment duration)
    Size: ~1 KB (sliding window of last 5-10 segments)
    Changes: every 4 seconds (new segment appended, oldest removed)

  Timeline:
    T=0: playlist.m3u8 v1 -> [seg-1, seg-2, seg-3, seg-4, seg-5]
    T=4: playlist.m3u8 v2 -> [seg-2, seg-3, seg-4, seg-5, seg-6]  (seg-6 new)
    T=8: playlist.m3u8 v3 -> [seg-3, seg-4, seg-5, seg-6, seg-7]  (seg-7 new)

  If TTL > segment duration:
    Player may get stale manifest that does not include newest segment
    -> Player stalls waiting for a segment that "does not exist" yet
    -> Rebuffering event -> bad user experience

  If TTL < segment duration / 2:
    Too many origin pulls (1 per edge PoP per TTL)
    For 600 edge PoPs and 2-second TTL: 300 origin pulls/second per stream
    At 10,000 concurrent live streams: 3M origin pulls/second (expensive)

  Sweet spot: TTL = segment_duration / 2 = 2 seconds
    Low enough to avoid staleness
    High enough to avoid origin overload with origin shield
```

### Interview One-Liner

> "VOD manifests get 5-minute TTL -- they are effectively immutable after
> transcoding. Live manifests get 2-second TTL because they change every
> segment duration (4 seconds). If the manifest TTL is longer than the segment
> duration, players stall on segments that 'do not exist yet' at the edge."

---

## 5. Thumbnail Cache -- CDN + Redis

### Thumbnail Caching Strategy

```
Thumbnail Cache Layers:

  Layer 1: CDN Edge
    Key: /vid-42/thumbnails/default.jpg
    TTL: 24 hours
    Size: ~30 KB per thumbnail
    Hit rate: 98% (thumbnails are frequently requested in search/browse)

  Layer 2: Redis (for API responses that include thumbnail URLs)
    Part of metadata cache (meta:{videoId} -> thumbnailUrl field)
    TTL: 5 minutes (same as metadata)

  Layer 3: S3 (origin)
    s3://video-platform-segments/videos/vid-42/thumbnails/default.jpg
    Always available

  Thumbnail types:
    default.jpg      Auto-generated at 5-second mark (320x180)
    custom.jpg       User-uploaded custom thumbnail (320x180)
    sprite.jpg       Thumbnail sprite sheet for video scrubbing (multiple frames)
    poster.jpg       Large poster image for video page (1280x720)
```

### Thumbnail Sprite Sheet (Scrubbing Preview)

```
When user hovers over the timeline scrubber, they see a preview:

  Sprite Sheet Layout:
  +-------+-------+-------+-------+-------+
  | T=0s  | T=10s | T=20s | T=30s | T=40s |
  +-------+-------+-------+-------+-------+
  | T=50s | T=60s | T=70s | T=80s | T=90s |
  +-------+-------+-------+-------+-------+
  ...

  Each thumbnail: 160x90 pixels
  Grid: 10 columns x N rows
  Generated during transcoding: ffmpeg -i input -vf "fps=0.1,scale=160:90" sprite.jpg
  CSS: background-position to show the right frame

  CDN caching: 24-hour TTL (same as regular thumbnails)
  Size: ~500 KB for a 10-minute video (60 frames at ~8 KB each)
```

### Cache Invalidation on Thumbnail Update

```
User uploads a new custom thumbnail:

  1. New thumbnail stored in S3:
     s3://.../{videoId}/thumbnails/custom_v2.jpg

  2. Metadata updated in PostgreSQL:
     UPDATE videos SET thumbnail_url = '...custom_v2.jpg'
     WHERE video_id = ?

  3. Redis metadata cache invalidated:
     DEL meta:{videoId}

  4. CDN cache invalidation (explicit):
     POST /invalidation
     { "paths": ["/vid-42/thumbnails/*"] }
     (propagation: 2-10 seconds across all edge PoPs)

  5. Alternative: versioned URL (no invalidation needed)
     Old: /vid-42/thumbnails/custom.jpg
     New: /vid-42/thumbnails/custom.jpg?v=2
     CDN treats ?v=2 as a different cache key -> automatic cache miss
```

### Interview One-Liner

> "Thumbnails are cached at CDN edge with 24-hour TTL -- they rarely change
> and are requested frequently in browse/search pages. On thumbnail update,
> we use versioned URLs (append ?v=2) for instant cache busting without
> expensive CDN invalidation API calls."

---

## 6. Recommendation Cache -- Per-User, Redis

### Data Model

```
Key:    recs:{userId}
Type:   Redis String (JSON array of video IDs)
TTL:    1 hour
Value:  ["vid-42", "vid-17", "vid-88", "vid-3", "vid-201", ...]

Key:    recs:{userId}:version
Type:   Redis String (integer)
TTL:    1 hour
Value:  Incremented when new recommendations are generated
```

### Cache Strategy

```
Recommendation Cache Flow:

  User opens homepage -> GET /recommendations?userId=user-42
       |
       v
  1. GET recs:{userId}
       |
       +---> Cache hit? (1-hour TTL, 70-80% hit rate)
       |       |
       |       v
       |     Parse JSON, hydrate video metadata from meta:{videoId}
       |     Return recommendations (< 5ms total)
       |
       +---> Cache miss?
               |
               v
            2. Call ML model server (50-200ms)
               |
               v
            3. SET recs:{userId} '[...]' EX 3600
               |
               v
            4. Return recommendations

  Why 1-hour TTL?
    - Recommendations should feel "fresh" on each visit
    - But re-computing every request is too expensive (ML inference)
    - 1 hour balances freshness vs compute cost
    - If user watches a video, we can invalidate early:
      DEL recs:{userId}  (next request triggers fresh recommendations)
```

### Cache Warming for Recommendations

```
Proactive Recommendation Cache Warming:

  Problem:
    Cold cache at 7 AM when users wake up and open the app
    -> Millions of cache misses -> ML model overloaded
    -> Slow recommendations -> bad user experience

  Solution: Warm cache before peak hours

  Cron job at 6 AM (local time per timezone):
    1. Get list of daily active users (from analytics)
       SELECT user_id FROM daily_active_users
       WHERE last_active > NOW() - INTERVAL '7 days'

    2. For each user, compute recommendations:
       ML model: recommend(userId, history, 50)

    3. Store in Redis:
       SET recs:{userId} '[...]' EX 7200   (2-hour TTL for pre-warmed)

    4. Result: when users open app at 7 AM, 90%+ cache hit rate
       ML model load is spread over 1 hour of warming (not spike at 7 AM)

  Cost:
    50M daily active users * 100ms ML inference = 5M seconds = ~58 days
    With 1000 ML serving pods: 5M / 1000 = 5000 seconds = ~83 minutes
    Start at 5:30 AM, done by 7 AM. Fits.
```

### Interview One-Liner

> "Recommendation cache is per-user in Redis with 1-hour TTL. We warm the cache
> before peak hours by pre-computing recommendations for daily active users.
> When a user watches a video, we invalidate their cache so the next visit
> gets fresh recommendations that account for the new watch."

---

## 7. Cache Warming -- CDN Pre-Push

### Why Cache Warming for Video?

```
Problem: CDN edge cache miss on first request

  New video uploaded and transcoded
       |
       v
  Segments stored in S3 (origin)
       |
  First viewer clicks play:
       |
       v
  CDN edge: CACHE MISS -> pull from regional -> pull from origin -> serve
  Latency: 200-500ms for first segment (vs < 50ms for cache hit)

  For a viral video shared on social media:
    - Thousands of users click within seconds
    - ALL hit CDN edge as cache miss (thundering herd)
    - Origin overwhelmed with concurrent pulls
    - Users experience buffering on first segment
```

### Cache Warming Strategies

```
Strategy 1: Pre-Push on Upload (for popular creators)

  Creator with 1M+ subscribers uploads a new video
       |
       v
  Transcoding completes
       |
       v
  System pushes first 10 segments of each resolution to:
    - Top 50 CDN edge PoPs (by subscriber geo-distribution)
    - All regional shields

  Method: CDN API "origin push" or synthetic requests to warm cache

  Cost: 10 segments * 4 resolutions * 50 PoPs = 2000 objects pushed
        2000 * 2 MB = 4 GB of proactive egress per video
        Worth it: prevents thundering herd for millions of viewers


Strategy 2: Predictive Warming (for trending content)

  Analytics detects video view velocity exceeding threshold
       |
       v
  View velocity = views in last 5 minutes / 5
  Threshold: > 100 views/minute AND accelerating
       |
       v
  Trigger cache warming for this video:
    - Push first 30 segments to top 100 edge PoPs
    - Increase origin shield cache priority
       |
       v
  Result: by the time the video goes viral, segments are at edge

  Implementation:
    AnalyticsService (Observer) tracks view velocity
    When threshold crossed: fire CACHE_WARM event
    CDN warming service consumes event, pushes segments


Strategy 3: Pre-Peak Warming (for scheduled events)

  Known event: Super Bowl halftime show, product launch, live stream
       |
       v
  Hours before event:
    - Pre-push static assets to all edge PoPs
    - Pre-warm manifest cache at regional shields
    - Scale origin shield capacity
       |
       v
  During event:
    - Edge PoPs serve cached content (near 100% hit rate)
    - Only live manifest updates hit origin (every 2-4 seconds)
```

### Interview One-Liner

> "We pre-push the first 10 segments of popular creator uploads to the top 50
> CDN edge PoPs before the first viewer arrives. For trending detection, if
> view velocity exceeds 100/minute and is accelerating, we proactively warm
> the next 30 segments across 100 PoPs. This prevents the thundering herd
> problem where a viral video overwhelms the origin."

---

## 8. Cold Storage Tiering

### Storage Tiers

```
Tiered Storage Based on View Frequency:

  Tier       | Storage Class     | Cost/GB/mo | Access Time | When
  -----------+-------------------+------------+-------------+-------------------
  Hot        | S3 Standard       | $0.023     | < 10ms      | Active videos
  Warm       | S3 Standard-IA    | $0.0125    | < 10ms      | 30+ days no views
  Cold       | S3 Glacier IR     | $0.004     | < 100ms     | 90+ days no views
  Archive    | S3 Glacier Deep   | $0.00099   | 12 hours    | 365+ days no views
  Deleted    | S3 Glacier Deep   | $0.00099   | 12 hours    | Soft-deleted (legal)

  Cost savings example (1 PB of video):
    All in S3 Standard:        1,000,000 GB * $0.023 = $23,000/month
    With tiering (typical):
      10% Hot (100 TB):        100,000 GB * $0.023  = $2,300
      20% Warm (200 TB):       200,000 GB * $0.0125 = $2,500
      30% Cold (300 TB):       300,000 GB * $0.004  = $1,200
      40% Archive (400 TB):    400,000 GB * $0.00099= $396
                                             Total  = $6,396/month
    Savings: $23,000 - $6,396 = $16,604/month (72% reduction!)
```

### Lifecycle Policy

```
S3 Lifecycle Policy:

  Rule 1: Hot -> Warm
    Condition: No GET requests for 30 days
    Action: Transition to S3 Standard-IA
    Note: First access after transition has retrieval fee ($0.01/GB)

  Rule 2: Warm -> Cold
    Condition: No GET requests for 90 days (cumulative)
    Action: Transition to S3 Glacier Instant Retrieval
    Note: < 100ms access, but $0.03/GB retrieval fee

  Rule 3: Cold -> Archive
    Condition: No GET requests for 365 days (cumulative)
    Action: Transition to S3 Glacier Deep Archive
    Note: 12-hour restore time, $0.02/GB retrieval fee

  Rule 4: On First Access After Tiering
    Condition: GET request to Cold/Archive object
    Action:
      a) Glacier IR: serve immediately (< 100ms), schedule re-tier to Hot
      b) Glacier Deep: initiate restore (12 hours), serve fallback message
      c) After restore: move to S3 Standard, reset lifecycle timer
```

### Re-Tiering on Access

```
User Requests a Cold Video:

  Scenario: Video was popular 6 months ago, has been in Glacier IR for 3 months

  1. User clicks play on "old viral video"
       |
       v
  2. CDN edge: MISS -> regional: MISS -> origin pulls from S3
       |
       v
  3. S3 Glacier IR: serves in < 100ms (instant retrieval)
       |
       v
  4. Video plays successfully (user unaware of tiering)
       |
       v
  5. Background job: move video back to S3 Standard
     (anticipates more views -- "viral resurrection" pattern)
       |
       v
  6. Lifecycle timer resets -- 30 days before warm transition again

  For Glacier Deep Archive:
  1. User clicks play on video not viewed in 2 years
       |
       v
  2. S3 Glacier Deep: returns 403 (object must be restored first)
       |
       v
  3. Application shows: "This video is being restored. Try again in a few hours."
       |
       v
  4. Initiate restore: POST /.../restore (Glacier Deep: 12-hour restore)
       |
       v
  5. After 12 hours: object in S3 Standard, playable
       |
       v
  6. CDN warms the first segments proactively
```

### Interview One-Liner

> "We use S3 tiered storage: Standard for active videos, IA after 30 days with
> no views, Glacier IR after 90 days, Glacier Deep after a year. This saves
> 72% on storage costs. Glacier IR serves in <100ms so users do not notice.
> On first access, we re-tier back to Standard and reset the lifecycle timer."

---

## 9. Full Request Flow Through All Cache Layers

### Video Playback -- Complete Cache Flow

```
User clicks "Play" on video vid-42:

  1. Browser: GET /api/videos/vid-42 (metadata)
       |
       v
  2. Application server: HGETALL meta:vid-42 (Redis)
     HIT -> return metadata (title, description, duration, thumbnailUrl)
     MISS -> query PostgreSQL -> HSET meta:vid-42 ... EX 300 -> return
       |
       v
  3. Browser: GET /vid-42/master.m3u8 (from CDN URL in metadata)
       |
       v
  4. CDN edge: cache lookup for /vid-42/master.m3u8
     HIT -> serve manifest (< 10ms)
     MISS -> pull from regional -> pull from origin (S3) -> cache + serve
       |
       v
  5. Player parses manifest, selects 720p (based on ABR strategy)
       |
       v
  6. Player: GET /vid-42/720p_h264/segment_000.ts (from CDN)
       |
       v
  7. CDN edge: cache lookup for segment
     HIT -> serve segment (< 50ms, 2 MB)                   <-- 95% of time
     MISS -> pull from regional -> origin (S3) -> cache + serve
       |
       v
  8. Player starts playback, estimates throughput
       |
       v
  9. Application server: INCR viewcount:vid-42 (Redis)
     SADD viewcount:flush:pending vid-42
       |
       v
  10. Player requests segment_001, segment_002, ... (from CDN, 95%+ hit rate)
       |
       v
  11. Background (every 30s): flush view count to PostgreSQL

  Total cache layers hit:
    - Redis: metadata cache (step 2)
    - CDN edge: manifest (step 4) + segments (steps 7, 10)
    - CDN regional: miss fallback (steps 4, 7 on miss)
    - Redis: view count INCR (step 9)
    - PostgreSQL: only on cache miss (step 2 miss) or flush (step 11)
    - S3: only on CDN origin pull (step 7 miss at regional level)
```

### Search + Browse -- Complete Cache Flow

```
User searches "system design" and clicks a result:

  1. Browser: GET /api/search?q=system+design
       |
       v
  2. Elasticsearch query (no Redis cache for search -- results are personalized)
     Returns: [vid-42, vid-17, vid-88] with metadata snippets
       |
       v
  3. Browser renders search results with thumbnails
     Each thumbnail: GET /vid-42/thumbnails/default.jpg (CDN)
     CDN edge HIT: 98% (thumbnails are small, frequently accessed)
       |
       v
  4. User clicks vid-42 -> full metadata load (step 2 of playback flow)
       |
       v
  5. Playback begins (steps 3-11 of playback flow above)

  Cache layers hit:
    - Elasticsearch: inverted index (not a traditional cache, but indexed)
    - CDN: thumbnails (step 3)
    - Redis: metadata on click (step 4)
    - CDN: video segments (step 5 onward)
```

---

## Cache Summary Table

| Cache Layer | Technology | Key Pattern | TTL | Hit Rate | What It Caches |
|------------|-----------|-------------|-----|----------|---------------|
| CDN Edge | CloudFront/Akamai | `/{videoId}/{res}/seg_{N}.ts` | 1 year (segments) | 95% | Video segments, manifests, thumbnails |
| CDN Regional | Origin Shield | Same as edge | Same | 99% (cumulative) | Fallback for edge misses |
| Metadata | Redis Hash | `meta:{videoId}` | 5 min | 90% | Title, description, thumbnailUrl |
| View Count | Redis String | `viewcount:{videoId}` | None (flush) | N/A (always write) | Real-time view count |
| Like Count | Redis String | `likecount:{videoId}` | None (flush) | N/A | Real-time like count |
| Recommendations | Redis String (JSON) | `recs:{userId}` | 1 hour | 70-80% | Per-user video recommendations |
| Thumbnails | CDN Edge | `/{videoId}/thumbnails/*.jpg` | 24 hours | 98% | Thumbnails and sprite sheets |
| Manifest (VOD) | CDN Edge | `/{videoId}/master.m3u8` | 5 min | 95% | HLS/DASH manifests |
| Manifest (Live) | CDN Edge | `/{videoId}/live.m3u8` | 2-4 sec | 50% | Live stream manifests |
| Session | Redis Set | `sessions:{videoId}` | 1 hour | N/A | Active viewer tracking |
| Transcode Lock | Redis String | `lock:transcode:{videoId}` | 10 min | N/A | Distributed lock |

---

## Interview Cheat Sheet

| Question | Answer |
|----------|--------|
| "What is the primary cache?" | "CDN edge -- 95% of bytes served are video segments from edge PoPs. Redis is secondary for metadata." |
| "How do you handle view counts?" | "Redis INCR per view (O(1)), batch flush to DB every 30 seconds. 50K/s -> 1 UPDATE/30s per video." |
| "How do you warm the cache?" | "Pre-push first 10 segments to top 50 PoPs for popular creators. Predictive warming when view velocity > 100/min." |
| "What about cold videos?" | "S3 tiered storage: Standard -> IA (30d) -> Glacier IR (90d) -> Deep Archive (365d). 72% cost savings." |
| "How do you handle live manifests?" | "2-second TTL at CDN edge (< segment duration of 4s). Prevents players from stalling on missing segments." |
| "Cache invalidation strategy?" | "Segments: never (immutable). Manifests: TTL-based. Thumbnails: versioned URLs. Metadata: DEL on update." |
| "Recommendation cache?" | "Per-user Redis, 1-hour TTL, pre-warmed before peak hours for daily active users." |
| "Thundering herd?" | "Origin shield absorbs concurrent edge misses. One origin pull serves all edges in a region." |
