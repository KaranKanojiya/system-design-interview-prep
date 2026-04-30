# CAP Theorem -- Video Streaming Platform (YouTube/Netflix)

> Interview-ready analysis of consistency, availability, and partition tolerance
> tradeoffs for a video streaming platform. Covers the unique CAP challenges of
> video: upload state tracking, view count propagation, CDN cache consistency,
> transcoding job idempotency, and how Netflix and YouTube approach CAP differently.

---

## CAP Recap

| Letter | Property | Meaning |
|--------|----------|---------|
| **C** | Consistency | Every read receives the most recent write |
| **A** | Availability | Every request receives a response (no timeouts) |
| **P** | Partition Tolerance | System continues operating despite network partitions |

In a distributed system, network partitions **will** happen. You must choose:

```
           C
          / \
         /   \
        /     \
      CP       CA  <-- not possible in distributed systems
      /         \
     /           \
    P ----------- A
          AP
```

**You can have CP or AP, never CA in a real distributed system.**

---

## Video Streaming = AP System (Overall)

### Why AP?

| Concern | Why Availability Wins |
|---------|----------------------|
| Playback continuity | A buffering spinner is worse than showing a slightly stale thumbnail |
| Revenue | Every failed playback = lost ad impression + subscriber churn |
| Scale | 500M+ daily active viewers (YouTube) -- strong consistency across all CDN edges is not feasible |
| Staleness tolerance | Metadata (title, description, view count) can be seconds stale without user impact |
| CDN architecture | CDN edge caches inherently serve stale content during propagation |
| Adaptive bitrate | ABR already tolerates imperfect data -- segment quality decisions are approximate |

### The Core Argument

```
User clicks play on a video:
     |
     v
Video starts in < 1s with slightly stale metadata    <-- AP: always available
     |
     vs.
     v
Video blocks for 3s waiting for globally consistent metadata  <-- CP: buffering
     |
     vs.
     v
"Video unavailable, try again later" during partition  <-- unacceptable
```

**Interview answer:** "A video streaming platform is an AP system. Users tolerate
a view count that is a few seconds stale, but they will NOT tolerate a video
that fails to play. Every playback failure is lost watch-time, lost ad revenue,
and a subscriber who might cancel. Availability is non-negotiable for the
read/playback path."

---

## Consistency Spectrum by Feature

Not everything is equally tolerant of staleness. Here is the full breakdown:

| Feature | Consistency Model | Staleness Tolerance | Why |
|---------|------------------|--------------------|----|
| Video playback | AP (eventual) | N/A -- cached at CDN | Segments served from edge cache |
| Video metadata | Eventual (seconds) | 5-10s | Title, description, thumbnail |
| View count | Eventual (seconds) | 10-60s | "1.2M views" -- approximate is fine |
| Like/dislike count | Eventual (seconds) | 10-30s | Approximate counts acceptable |
| Comment list | Eventual (seconds) | 2-5s | New comments can appear slightly delayed |
| Upload progress | **Strong (CP)** | 0s | Must track which chunks are uploaded |
| Transcoding state | **Strong (CP)** | 0s | Must not lose state machine transitions |
| Subscription status | **Strong (CP)** | 0s | Paying user must have immediate access |
| Search index | Eventual (seconds) | 5-30s | Newly uploaded video searchable after short delay |
| Recommendation | Eventual (hours) | 1-24h | ML model retrains periodically |
| Watch history | Eventual (seconds) | 1-5s | Slightly delayed write-back acceptable |
| CDN edge cache | Eventual (minutes) | TTL-based | Stale until TTL expires or invalidation |

---

## AP: Video Playback and Metadata

### Why Availability Over Consistency?

Video playback is the most critical user-facing operation. Netflix reports
that **every 100ms of additional latency** reduces viewer engagement. A failed
playback is infinitely worse than showing a view count that is 30 seconds stale.

```
Video Playback Path (AP):

  User clicks "Play"
       |
       v
  1. CDN edge serves manifest (HLS/DASH)     <-- cached, possibly stale
       |
       v
  2. Player parses manifest, selects quality
       |
       v
  3. Player requests first segment from CDN   <-- cached at edge
       |
       v
  4. CDN edge hit? Serve immediately (< 50ms)
     CDN edge miss? Pull from regional -> origin -> S3
       |
       v
  5. Playback starts

  Metadata (title, view count, description):
  - Served from Redis cache with 5-min TTL
  - During partition: serve stale cache rather than fail
  - View count updated asynchronously (see below)
```

### View Count: The Classic Eventual Consistency Example

```
View Count Write Path:

  User watches video
       |
       v
  1. Streaming service increments local Redis counter
     INCR viewcount:{videoId}                    <-- O(1), local
       |
       v
  2. Background job flushes to DB every 30 seconds
     UPDATE videos SET view_count = view_count + {delta}
     WHERE video_id = ?
       |
       v
  3. Other Redis replicas receive update (eventually)
       |
       v
  4. CDN metadata cache refreshes on next TTL expiry

  Latency: User A's view may not appear in User B's count for 30-60 seconds
  Acceptable? YES -- "1,234,567 views" vs "1,234,568 views" is invisible
```

### Why NOT Strong Consistency for View Counts?

```
WRONG approach (strong consistency):

  Every view triggers:
    BEGIN TRANSACTION
      SELECT view_count FROM videos WHERE video_id = ? FOR UPDATE
      UPDATE videos SET view_count = view_count + 1 WHERE video_id = ?
    COMMIT

  Problem at YouTube scale:
    - "Despacito" gets 50,000 views/second
    - 50,000 row-level locks/second on ONE row
    - Database becomes the bottleneck
    - Latency spikes, cascading failures

CORRECT approach (eventual consistency):

  Every view triggers:
    INCR viewcount:{videoId}   (Redis, local to region)

  Background flush every 30s:
    delta = GETSET viewcount:{videoId} 0
    UPDATE videos SET view_count = view_count + delta

  50,000 views/second -> 50,000 O(1) Redis INCRs (trivial)
  Database sees 1 UPDATE per 30 seconds (instead of 50,000/s)
```

### Interview One-Liner (View Counts)

> "View counts use Redis INCR locally, flushed to the database every 30 seconds.
> This turns 50K writes/second into one batch UPDATE. The count is eventually
> consistent with up to 60 seconds of staleness, which is invisible to users
> seeing '1.2M views'."

---

## CP: Upload State Tracking -- The Exception

### Why Strong Consistency?

Upload progress is the **one place** where strong consistency is critical.
A video upload can be gigabytes, split into chunks. Losing track of which
chunks have been uploaded means:

1. Re-uploading already-uploaded chunks (wasted bandwidth)
2. Missing chunks that were marked as uploaded (corrupted video)
3. Duplicate transcoding jobs (wasted compute)

```
WRONG (eventual consistency on upload state):

  Client uploads chunk 47 of 100 at T=0
       |
       v
  Upload service writes "chunk 47 received" to replica A
       |
  Replication lag... (replica B has not received the write)
       |
  At T=1: Client connection drops, reconnects to replica B
       |
       v
  Resume logic reads from replica B: "chunk 47 NOT received"
       |
       v
  Client re-uploads chunk 47 -- WASTED 50MB of bandwidth
  OR worse: client skips to chunk 48, and chunk 47 is lost
```

```
CORRECT (strong consistency on upload state):

  Client uploads chunk 47 of 100 at T=0
       |
       v
  Upload service writes to PRIMARY with write-ahead log
  PostgreSQL: INSERT INTO upload_chunks (video_id, chunk_number, status)
              VALUES (?, 47, 'RECEIVED')
       |
       v
  Client connection drops at T=1, reconnects
       |
       v
  Resume logic reads from PRIMARY (or synchronous replica):
  SELECT MAX(chunk_number) FROM upload_chunks
  WHERE video_id = ? AND status = 'RECEIVED'
  -> Returns 47. Resume from chunk 48. Correct.
```

### Upload State: Data Model

```
Table: upload_chunks
+----------+----------+--------------+-----------+------------+
| video_id | chunk_no | chunk_size   | status    | uploaded_at|
+----------+----------+--------------+-----------+------------+
| vid-001  |        1 |     5242880  | RECEIVED  | 2024-01-15 |
| vid-001  |        2 |     5242880  | RECEIVED  | 2024-01-15 |
| vid-001  |        3 |     5242880  | RECEIVED  | 2024-01-15 |
| vid-001  |       47 |     5242880  | RECEIVED  | 2024-01-15 |
| vid-001  |       48 |              | PENDING   |            |
+----------+----------+--------------+-----------+------------+

Primary key: (video_id, chunk_no)
Index: video_id + status for resume queries
Storage: PostgreSQL with synchronous replication (CP)
```

### Interview One-Liner (Upload State)

> "Upload chunk tracking is CP -- we cannot lose which chunks have been received.
> A stale read means re-uploading gigabytes or, worse, producing a corrupted video.
> We use PostgreSQL with synchronous replication for the upload state table."

---

## CP: Transcoding State Machine

### Why Strong Consistency?

The video state machine (UPLOADING -> UPLOADED -> TRANSCODING -> READY) must
be strongly consistent. A stale read on the state can cause:

1. Duplicate transcoding jobs (state read as UPLOADED when already TRANSCODING)
2. Serving an untranscoded video (state read as READY when still TRANSCODING)
3. Lost state transitions (two services both transition from UPLOADED)

```
WRONG (eventual consistency on video state):

  Service A reads video state: UPLOADED
  Service B reads video state: UPLOADED (stale)
       |                           |
       v                           v
  Service A: transitionTo(TRANSCODING)  Service B: transitionTo(TRANSCODING)
       |                           |
       v                           v
  Two transcoding jobs for the same video!
  Double compute cost + potential conflicts when both write READY

CORRECT (strong consistency with optimistic locking):

  Service A reads video state: UPLOADED, version=5
  Service B reads video state: UPLOADED, version=5
       |                           |
       v                           v
  Service A:                   Service B:
    UPDATE videos                UPDATE videos
    SET status='TRANSCODING',    SET status='TRANSCODING',
        version=6                    version=6
    WHERE video_id=?             WHERE video_id=?
    AND version=5                AND version=5
       |                           |
       v                           v
  Rows affected: 1 (success)   Rows affected: 0 (conflict!)
  Proceeds with transcoding    Retries, sees TRANSCODING, skips
```

### Optimistic Locking Implementation

```java
public class VideoRepository {

    public boolean transitionState(String videoId, VideoStatus from,
                                   VideoStatus to) {
        // Optimistic lock: only succeeds if current state matches 'from'
        int updated = jdbcTemplate.update(
            "UPDATE videos SET status = ?, version = version + 1 "
            + "WHERE video_id = ? AND status = ? AND version = ?",
            to.name(), videoId, from.name(), currentVersion);
        return updated == 1;  // true = success, false = conflict
    }
}
```

### Interview One-Liner (Transcoding State)

> "Video state transitions use optimistic locking -- UPDATE ... WHERE status = ?
> AND version = ?. Only one service wins the race to start transcoding. Losers
> see rows_affected = 0 and back off. This prevents duplicate transcoding jobs."

---

## Eventual Consistency: CDN Cache

### CDN Consistency Model

CDN is inherently eventually consistent. Content at edge nodes becomes stale
until TTL expires or explicit invalidation occurs. This is acceptable because:

1. Video segments are **immutable** once transcoded (never change)
2. Metadata (title, thumbnail) changes are rare and can tolerate staleness

```
CDN Cache Consistency Model:

  Content Type     | TTL        | Invalidation Strategy | Why
  -----------------+------------+-----------------------+------------------------
  Video segments   | 1 year     | Never (immutable)     | Segments never change
  HLS manifest     | 5 seconds  | Explicit invalidation | Live streams need fast updates
  Thumbnails       | 24 hours   | Explicit on update    | Rarely change
  Metadata JSON    | 5 minutes  | TTL-based only        | Low-stakes staleness
  Player JS/CSS    | 1 hour     | Versioned URLs        | Cache-bust on deploy
```

### Cache Invalidation vs TTL

```
Approach 1: TTL-Based (used for most content)

  Edge serves cached content until TTL expires
       |
       v
  TTL expires -> next request goes to origin -> edge updates cache
       |
       v
  Staleness window: 0 to TTL seconds

  Pros: Simple, no invalidation infra needed
  Cons: Stale content served for up to TTL duration

Approach 2: Explicit Invalidation (used for live + urgent updates)

  Origin pushes invalidation to CDN API
       |
       v
  CDN propagates invalidation to all edge nodes (2-10 seconds)
       |
       v
  Next request at any edge -> cache miss -> fetch from origin

  Pros: Near-instant update across all edges
  Cons: Expensive at scale ($0.005 per invalidation path on CloudFront)
        Rate-limited (1000 invalidations/month free on CloudFront)

Approach 3: Versioned URLs (used for player assets)

  player-v2.3.1.js -> player-v2.3.2.js on deploy
       |
       v
  Old URL still cached (fine, old clients use it)
  New URL is a cache miss -> fetched from origin
       |
       v
  No invalidation needed, long TTL (1 year)

  Pros: No invalidation cost, infinite TTL
  Cons: Only works for assets where URL can change
```

### Interview One-Liner (CDN Consistency)

> "Video segments are immutable -- 1 year TTL, never invalidated. HLS manifests
> get 5-second TTL for live streams. For urgent updates (takedown), we use
> explicit CDN invalidation which propagates to all edges in 2-10 seconds.
> Player assets use versioned URLs for zero-cost cache busting."

---

## Transcoding: At-Least-Once Processing

### Why At-Least-Once?

Transcoding jobs are expensive (minutes to hours of compute). In a distributed
system, we must handle:

1. Worker crashes mid-transcode
2. Network partition between scheduler and worker
3. Duplicate job submissions during retries

```
Exactly-Once is Impossible in Distributed Systems (FLP theorem):

  Scheduler sends "transcode video-42 at 1080p" to Worker A
       |
       v
  Worker A receives, starts transcoding (5 minutes)
       |
  Network partition!
       |
  Scheduler: "Did Worker A get the job? Did it finish? Unknown."
       |
  Options:
    1. Assume success -> job may be lost if Worker A crashed
    2. Retry to Worker B -> duplicate transcoding (at-least-once)
    3. Wait forever -> availability violated

  We choose option 2: at-least-once with idempotent jobs
```

### Idempotent Transcoding

```
Making transcoding idempotent:

  Output path is deterministic:
    s3://videos/{videoId}/{resolution}_{codec}/segment_{N}.ts

  If Worker A and Worker B both transcode the same job:
    Worker A writes: s3://videos/vid-42/1080p_h264/segment_001.ts
    Worker B writes: s3://videos/vid-42/1080p_h264/segment_001.ts
       |
       v
  Same path, same content -> S3 PutObject is idempotent
  Last writer wins, but output is identical
       |
       v
  No corruption, no duplicates in the manifest
  Cost: wasted compute (acceptable vs. data loss)
```

### Job Deduplication with Redis

```
Before starting a transcoding job:

  1. Worker tries to acquire a distributed lock:
     SET transcode:{videoId}:{resolution} {workerId} NX EX 600
     (NX = only if not exists, EX = 10 minute TTL)

  2. If SET returns OK -> this worker owns the job
     If SET returns nil -> another worker already has it, skip

  3. Worker completes transcoding:
     DEL transcode:{videoId}:{resolution}

  4. If worker crashes: lock expires after 10 minutes
     Scheduler retries, another worker acquires the lock

  Result: at-most-one active worker per job at any time
  Combined with idempotent output: effectively exactly-once semantics
```

### Interview One-Liner (At-Least-Once)

> "Transcoding uses at-least-once delivery with idempotent output. The output
> S3 path is deterministic, so duplicate jobs produce identical segments.
> We prevent concurrent duplicates with a Redis distributed lock (SET NX EX).
> If the worker crashes, the lock TTL expires and the job is retried."

---

## Netflix vs YouTube: Architecture Choices

### Netflix

```
Netflix CAP Decisions:

  Component              | CAP Choice | Technology        | Why
  -----------------------+------------+-------------------+---------------------------
  Video catalog metadata | AP         | Cassandra (AP)    | Availability > consistency
  Subscriber state       | CP         | MySQL + EVCache   | Payment state must be correct
  Viewing history        | AP         | Cassandra         | Eventual consistency OK
  CDN (Open Connect)     | AP         | Custom appliances | Pre-positioned at ISPs
  Recommendation         | AP         | Offline ML batch  | Recomputed every few hours
  A/B test assignment    | CP         | Zuul + server     | Must be deterministic

  Key Insight: Netflix pre-transcodes everything (entire catalog).
  - No real-time transcoding -- all done offline
  - Every title has 100+ encoding profiles (Per-Title Encoding)
  - CDN servers pre-positioned inside ISP networks (Open Connect)
  - Result: playback is almost entirely served from ISP-local cache
```

```
Netflix Per-Title Encoding:

  Traditional approach:
    Same encoding ladder for all content:
    1080p @ 5 Mbps, 720p @ 3 Mbps, 480p @ 1.5 Mbps

  Netflix approach:
    Analyze each title's visual complexity:
    - Animated show: 1080p looks good at 2 Mbps
    - Action movie:  1080p needs 8 Mbps

    Result: 10-20% bandwidth savings, same visual quality
    Tradeoff: Higher offline compute cost, but CDN bandwidth is much more expensive
```

### YouTube

```
YouTube CAP Decisions:

  Component              | CAP Choice | Technology        | Why
  -----------------------+------------+-------------------+---------------------------
  Video metadata         | AP         | Vitess (MySQL)    | Availability critical
  Upload state           | CP         | Spanner (CP)      | Must not lose chunks
  View count             | AP         | Mesa (analytics)  | Approximate is fine
  Search index           | AP         | Custom (Caffeine) | Near-real-time indexing
  CDN                    | AP         | Google Global CDN | Multi-tier edge + regional
  Comments               | AP         | Spanner           | Slight delay acceptable
  Creator Studio metrics | Eventual   | Mesa + Dremel     | Analytics can be delayed

  Key Insight: YouTube must handle real-time uploads.
  - 500 hours of video uploaded per minute
  - Must transcode in near-real-time (users expect video ready in minutes)
  - Cannot pre-transcode like Netflix (user-generated content)
  - Result: massive transcoding pipeline, Borg orchestration
```

### Netflix vs YouTube: Side-by-Side

```
Dimension           | Netflix                    | YouTube
--------------------+----------------------------+---------------------------
Content type        | Professional, finite       | User-generated, infinite
Transcoding model   | Offline batch (all upfront)| Real-time (on upload)
Encoding strategy   | Per-title optimized        | Standard encoding ladder
CDN model           | Open Connect (ISP-embedded)| Google global edge network
Catalog size        | ~17,000 titles             | 800M+ videos
Upload rate         | ~100 titles/week           | 500 hours/minute
Consistency for     | AP (Cassandra)             | AP (Vitess)
  metadata          |                            |
Consistency for     | N/A (no real-time upload)  | CP (Spanner)
  upload state      |                            |
View counting       | Not a major feature        | Core feature (eventual)
Recommendation      | Offline ML batch           | Near-real-time + batch
Live streaming      | Limited (some sports)      | YouTube Live (major)
```

### Interview One-Liner (Netflix vs YouTube)

> "Netflix pre-transcodes its entire catalog offline with per-title encoding
> and serves from ISP-embedded CDN nodes -- almost pure AP. YouTube handles
> 500 hours/minute of uploads with real-time transcoding and uses CP (Spanner)
> for upload state tracking. Both use AP for metadata and eventual consistency
> for view counts."

---

## Consistency Patterns Summary

### Decision Tree

```
Is it the video playback path?
  |
  YES --> AP: serve from CDN edge, tolerate staleness
  |
  NO
  |
  v
Does data loss cause user-visible corruption?
  |
  YES --> CP: upload chunks, state machine transitions, subscriptions
  |
  NO
  |
  v
Is it a counter or aggregate?
  |
  YES --> Eventual: Redis INCR + async flush (view counts, like counts)
  |
  NO
  |
  v
Is it ML/analytics?
  |
  YES --> Eventual (hours): batch recompute (recommendations, trending)
  |
  NO
  |
  v
Default: AP with short TTL (5-60 seconds)
```

### Interview Cheat Sheet

| Component | CAP | Implementation | Staleness |
|-----------|-----|---------------|-----------|
| Video segments | AP | CDN edge, 1yr TTL, immutable | None (immutable) |
| Video metadata | AP | Redis cache, 5min TTL | 0-5min |
| View counts | AP | Redis INCR, 30s flush | 0-60s |
| Upload chunks | CP | PostgreSQL, sync replication | 0s |
| Video state machine | CP | Optimistic locking, version column | 0s |
| Subscription status | CP | PostgreSQL, sync replication | 0s |
| Transcoding jobs | At-least-once | Redis lock + idempotent output | N/A |
| Search index | AP | Elasticsearch, async indexing | 5-30s |
| Recommendations | AP | Offline ML, 1hr cache | 1-24hr |
| CDN manifest (live) | AP | 5s TTL, explicit invalidation | 0-5s |
| Comments | AP | Cassandra/Spanner, async replication | 2-5s |

---

## Production Patterns for CAP in Video Streaming

### Pattern 1: Write-Behind for Counters

```
Write-Behind Pattern (view counts, like counts):

  Hot path (low latency):
    INCR viewcount:{videoId}     <-- Redis, O(1), < 1ms

  Background flush (batch):
    Every 30 seconds:
      GETSET viewcount:{videoId} 0    <-- atomic get + reset
      UPDATE videos SET view_count = view_count + {delta}
        WHERE video_id = ?

  Read path:
    GET viewcount:{videoId}      <-- Redis (may include unflushed delta)
    OR: SELECT view_count FROM videos   <-- DB (may lag by up to 30s)

  Net result:
    - Write: O(1) Redis INCR
    - Read: O(1) Redis GET
    - DB sees 1 write/30s instead of 50K writes/s
    - Staleness: <= 30 seconds
```

### Pattern 2: Saga for Upload + Transcode

```
Upload-to-Ready Saga:

  Step 1: Create video record (status = UPLOADING)
    Compensate: DELETE video record

  Step 2: Upload chunks to S3
    Compensate: DELETE S3 objects

  Step 3: Assemble chunks (status = UPLOADED)
    Compensate: Revert to UPLOADING

  Step 4: Start transcoding (status = TRANSCODING)
    Compensate: Cancel transcoding jobs, revert to UPLOADED

  Step 5: Transcoding complete (status = READY)
    Compensate: Revert to TRANSCODING

  If any step fails:
    Execute compensating actions in reverse order
    Final state: FAILED (can retry from step that failed)
```

### Pattern 3: Circuit Breaker for CDN Origin

```
CDN -> Origin Circuit Breaker:

  CDN edge requests origin for cache miss
       |
       v
  Circuit Breaker checks state:
    CLOSED (normal): forward request to origin
    OPEN (tripped):  serve stale cache (if available) or 503
    HALF-OPEN:       allow 1 request through to test origin
       |
       v
  If origin fails > 5 times in 30 seconds:
    Trip breaker -> OPEN
    Serve stale content for 60 seconds
    Then HALF-OPEN: test one request
    If origin recovers: CLOSED
    If still failing: back to OPEN

  Result: CDN continues serving (possibly stale) during origin outage
  Better than: CDN sends all requests to failing origin (cascading failure)
```

### Pattern 4: Read-Your-Writes for Uploaders

```
Problem: Uploader sees stale data after uploading

  User uploads video, metadata saved to primary DB
       |
       v
  User refreshes page, hits read replica (hasn't replicated yet)
       |
       v
  "Video not found" -- even though they just uploaded it!

Solution: Read-your-writes consistency

  On upload: set cookie/header with upload timestamp
  On subsequent reads: if timestamp < replication_lag_estimate
    Route to PRIMARY (not replica)
  After replication_lag_estimate passes:
    Route to replica (normal path)

  Result: uploader always sees their own video immediately
  Other users: eventual consistency (seconds) -- acceptable
```

---

## Deep Dive: CDN Consistency for Live Streaming

### Why Live Streaming is Harder

```
VOD (Video on Demand):
  - Segments are immutable (transcoded once, never change)
  - Manifest is static (lists all segments upfront)
  - TTL: 1 year for segments, 5 minutes for manifest
  - CDN consistency: trivial

Live Streaming:
  - New segments generated every 2-4 seconds
  - Manifest CHANGES every 2-4 seconds (new segment appended)
  - Old segments expire (sliding window, 30-60 seconds)
  - CDN must propagate new manifest quickly

  The challenge: manifest TTL must be < segment duration
  If TTL is 10 seconds but segments are 4 seconds:
    Player may request a segment that does not exist yet at the edge
    Or miss a segment that has already expired
```

### Live Manifest Consistency Protocol

```
1. Encoder produces new segment every 4 seconds
2. Encoder updates manifest on origin (appends new segment, removes oldest)
3. CDN edge serves manifest with TTL = 2 seconds (< segment duration)
4. Player polls manifest every segment duration
5. Player requests new segments listed in updated manifest

Timeline:
  T=0:  Manifest v1: [seg-1, seg-2, seg-3]     Origin + CDN edge
  T=4:  Manifest v2: [seg-2, seg-3, seg-4]     Origin updated
  T=4.5: Manifest v2 propagated to edge (TTL expired, re-fetched)
  T=5:  Player reads manifest v2, requests seg-4
  T=6:  CDN edge: seg-4 cache miss -> fetch from origin -> serve + cache

  Worst-case latency: segment_duration + manifest_TTL = 4 + 2 = 6 seconds
  This is the "glass-to-glass" latency for live streaming with CDN
```

### Interview One-Liner (Live CDN)

> "For live streaming, the manifest TTL must be shorter than the segment duration.
> We use 2-second TTL on manifests and 4-second segments, giving ~6 second
> glass-to-glass latency. VOD segments are immutable with 1-year TTL."

---

## Summary: CAP Trade-Off Decision Matrix

```
+------------------+------+-------------------------------------------+
|    Component     | CAP  |              Rationale                    |
+------------------+------+-------------------------------------------+
| Video playback   |  AP  | Availability = revenue. Stale OK.         |
| Video metadata   |  AP  | 5-min TTL in Redis. Stale title is fine.  |
| View counts      |  AP  | Redis INCR + 30s flush. "1.2M" is fine.  |
| Upload chunks    |  CP  | Data loss = corrupted video. Must be CP.  |
| State machine    |  CP  | Duplicate transcode = wasted $$. Lock it. |
| Subscriptions    |  CP  | Paying users must have immediate access.  |
| Transcoding jobs | AL1  | At-least-once + idempotent output.        |
| CDN (VOD)       |  AP  | Immutable segments, long TTL.             |
| CDN (live)      |  AP  | Short manifest TTL (2s), segment TTL (4s).|
| Search           |  AP  | Async indexing, 5-30s delay acceptable.   |
| Recommendations  |  AP  | Batch ML, 1-24hr recompute cycle.         |
+------------------+------+-------------------------------------------+
```
