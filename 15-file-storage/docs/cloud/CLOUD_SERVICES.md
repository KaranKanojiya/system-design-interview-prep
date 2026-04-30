# File Storage System (Google Drive/Dropbox) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway + WAF + CloudFront | API Management + Front Door | Cloud Endpoints + Apigee | Upload/download endpoints, auth, rate limiting |
| **Block Storage (files)** | S3 (Standard + IA + Glacier) | Blob Storage (Hot/Cool/Archive) | Cloud Storage (Standard/Nearline/Coldline) | Chunked file blocks, content-addressable by SHA-256 hash |
| **Metadata DB** | RDS Aurora PostgreSQL | Azure SQL | Cloud SQL / AlloyDB | File tree, folders, sharing, permissions, version metadata |
| **Chunk Metadata** | DynamoDB | Cosmos DB | Firestore / Bigtable | file_id -> [chunk_hashes], dedup index (hash -> S3 key) |
| **Cache** | ElastiCache Redis | Azure Cache for Redis | Memorystore (Redis) | Hot file metadata, user workspace tree, recent chunk lookups |
| **Sync Events** | SQS + SNS | Service Bus + Event Grid | Pub/Sub | Change notifications, sync cursor updates, device fan-out |
| **CDN** | CloudFront | Azure CDN / Front Door | Cloud CDN | Download acceleration, thumbnail delivery, preview serving |
| **Thumbnail Generation** | Lambda (triggered by S3 event) | Azure Functions (Blob trigger) | Cloud Functions (GCS trigger) | Generate thumbnails on upload for images/PDFs/videos |
| **Upload Pipeline** | Step Functions | Durable Functions | Cloud Workflows | Orchestrate: chunk upload -> dedup check -> store -> index -> notify |
| **Encryption** | KMS (envelope encryption) | Azure Key Vault | Cloud KMS | Server-side encryption (SSE-S3/SSE-KMS), client-side optional |
| **Search** | OpenSearch Service | Azure AI Search | Vertex AI Search | Full-text search across file names, content, and metadata |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Upload latency, dedup ratio, sync lag, storage utilization |
| **DNS** | Route 53 (latency-based) | Traffic Manager | Cloud DNS | Multi-region routing, health checks |
| **Long Polling / WebSocket** | API Gateway WebSocket + Lambda | SignalR Service | Firebase Realtime | Sync notifications: "file changed on another device" |

---

## File Upload + Storage Architecture on AWS (Numbered)

```
User uploads a 100MB file "presentation.pptx" from their laptop.

    1. CLIENT CHUNKING (on device, before upload):
       Client splits file into 4MB chunks:
         Chunk 0: bytes[0..4MB]     -> SHA-256 hash = "a3f2b8..."
         Chunk 1: bytes[4MB..8MB]   -> SHA-256 hash = "7c91e4..."
         Chunk 2: bytes[8MB..12MB]  -> SHA-256 hash = "d5a017..."
         ...
         Chunk 24: bytes[96MB..100MB] -> SHA-256 hash = "1b8e33..."
       |
       Total: 25 chunks, each with a unique SHA-256 hash.
       Client sends chunk manifest to server BEFORE uploading any data.
    |
    v
    2. DEDUP CHECK (API Gateway -> Lambda / ECS):
       Client sends: { fileName: "presentation.pptx", chunks: ["a3f2b8...", "7c91e4...", ...] }
       |
       Server checks DynamoDB dedup index for each hash:
         DynamoDB: PK = chunk_hash
         "a3f2b8..." -> EXISTS (already stored by another user!) -> SKIP upload
         "7c91e4..." -> NOT FOUND -> needs upload
         "d5a017..." -> EXISTS -> SKIP
         ...
       |
       Server responds:
         { chunksNeeded: ["7c91e4...", "1b8e33..."], presignedUrls: {...} }
       |
       Result: 25 chunks, but only 8 need uploading (17 already exist).
       Savings: 68% bandwidth reduction from deduplication.
    |
    v
    3. PARALLEL CHUNK UPLOAD (client -> S3 via presigned URLs):
       For each chunk that needs uploading:
         Client uploads directly to S3 using presigned URL:
           PUT https://bucket.s3.amazonaws.com/chunks/7c91e4...
           Header: Content-SHA256: 7c91e4...
           Body: [4MB chunk data]
       |
       Parallel uploads: 4 concurrent connections (configurable).
       Each chunk: 4MB / 50Mbps = ~0.6 seconds.
       8 chunks, 4 parallel = 2 batches = ~1.2 seconds upload time.
       (vs 100MB sequential = ~16 seconds. 13x faster with chunking + dedup.)
    |
    v
    4. S3 STORES CHUNK (content-addressable):
       S3 bucket: s3://file-storage-chunks/{region}/
       Key: chunks/{sha256_hash}  (e.g., chunks/7c91e4...)
       |
       Storage class: S3 Standard (frequently accessed)
       Encryption: SSE-KMS (envelope encryption with customer-managed key)
       Versioning: DISABLED on chunk bucket (chunks are immutable, hash = content)
       |
       S3 event notification -> SNS topic "chunk-uploaded"
    |
    v
    5. STEP FUNCTIONS ORCHESTRATION (upload pipeline):
       Triggered by: all chunks uploaded (tracked in DynamoDB upload session)
       |
       Step 1: Validate all chunk hashes (re-compute SHA-256 on S3 objects via Lambda)
       Step 2: Register file in metadata DB (RDS Aurora):
                INSERT INTO files (file_id, name, size, owner_id, folder_id, created_at)
                VALUES ('file_001', 'presentation.pptx', 104857600, 'user_001', 'folder_003', NOW())
       Step 3: Register chunk mapping in DynamoDB:
                PK=file_001, chunks=["a3f2b8...", "7c91e4...", ..., "1b8e33..."], version=1
       Step 4: Update dedup index for new chunks:
                PK="7c91e4...", ref_count=1, s3_key="chunks/7c91e4..."
       Step 5: Trigger thumbnail generation (Lambda):
                Input: s3://chunks/... (first chunk or full file)
                Output: s3://thumbnails/file_001/thumb_256.jpg
       Step 6: Invalidate cache (ElastiCache):
                DEL folder:folder_003:listing  (bust folder cache)
       Step 7: Publish sync event (SNS -> SQS):
                { event: "FILE_CREATED", fileId: "file_001", userId: "user_001" }
    |
    v
    6. SYNC NOTIFICATION (SQS -> user devices):
       SNS topic fans out to per-device SQS queues:
         user_001_laptop: already has the file (uploader)
         user_001_phone:  { event: FILE_CREATED, fileId: file_001 } -> download metadata
         user_001_tablet: { event: FILE_CREATED, fileId: file_001 } -> download metadata
       |
       Shared folder members also notified:
         user_002 (collaborator): { event: FILE_CREATED, fileId: file_001, sharedBy: user_001 }
    |
    v
    7. CLIENT DOWNLOAD (when another device syncs):
       Device requests file metadata:
         GET /api/files/file_001 -> { name, size, chunks: ["a3f2b8...", ...] }
       |
       Device checks local chunk cache:
         "a3f2b8..." -> exists locally (from a previous file!) -> SKIP download
         "7c91e4..." -> not local -> download from CloudFront CDN
       |
       Download only missing chunks (delta sync on download too).
       Reassemble file from chunks in order.
```

---

## Sync Architecture (Numbered)

```
User edits "report.docx" on their laptop. Phone and tablet must sync.

    1. FILE CHANGE DETECTED (laptop client):
       File watcher (inotify on Linux, FSEvents on macOS, ReadDirectoryChangesW on Windows)
       detects: report.docx modified at 2026-04-26T14:30:00
       |
       Client re-chunks the modified file:
         Old chunks: ["aaa...", "bbb...", "ccc...", "ddd...", "eee..."]  (5 chunks)
         New chunks: ["aaa...", "bbb...", "XXX...", "ddd...", "eee..."]  (5 chunks)
         |
         Only chunk 2 changed: "ccc..." -> "XXX..."
         Delta: 1 chunk out of 5 = 20% of file data transferred.
    |
    v
    2. DELTA UPLOAD (laptop -> S3):
       Client sends to server:
         {
           fileId: "file_002",
           oldVersion: 3,
           newChunks: ["aaa...", "bbb...", "XXX...", "ddd...", "eee..."],
           changedChunks: ["XXX..."]
         }
       |
       Server: dedup check on "XXX..." -> NOT FOUND -> presigned URL for upload
       Client uploads only chunk "XXX..." (4MB instead of 20MB full file).
    |
    v
    3. NEW VERSION CREATED (server):
       DynamoDB version entry:
         PK=file_002, SK=v#4
         chunks=["aaa...", "bbb...", "XXX...", "ddd...", "eee..."]
         timestamp=2026-04-26T14:30:05
         userId=user_001
         device=laptop
       |
       Old version (v#3) preserved:
         PK=file_002, SK=v#3
         chunks=["aaa...", "bbb...", "ccc...", "ddd...", "eee..."]
       |
       Chunks "aaa...", "bbb...", "ddd...", "eee..." shared between versions (copy-on-write).
       Only "ccc..." is unique to v3 and "XXX..." is unique to v4.
    |
    v
    4. SYNC EVENT PUBLISHED (SNS -> SQS):
       {
         event: "FILE_UPDATED",
         fileId: "file_002",
         version: 4,
         changedChunks: ["XXX..."],
         removedChunks: ["ccc..."],
         userId: "user_001",
         device: "laptop"
       }
       |
       Fan-out to all user devices EXCEPT the originating device:
         user_001_phone -> SQS queue
         user_001_tablet -> SQS queue
    |
    v
    5. LONG POLLING (phone receives notification):
       Phone client has open long-poll connection:
         GET /api/sync?cursor=cursor_abc123&timeout=60s
       |
       Server holds connection open until:
         a. New event arrives in SQS queue -> return immediately
         b. 60-second timeout -> return empty, client reconnects
       |
       Response:
         {
           changes: [{ fileId: "file_002", version: 4, action: "UPDATED" }],
           newCursor: "cursor_def456"
         }
    |
    v
    6. DELTA DOWNLOAD (phone syncs):
       Phone has file_002 at version 3:
         Local chunks: ["aaa...", "bbb...", "ccc...", "ddd...", "eee..."]
       |
       Phone requests: GET /api/files/file_002/version/4
       Response: { chunks: ["aaa...", "bbb...", "XXX...", "ddd...", "eee..."] }
       |
       Phone compares:
         "aaa..." -> have it locally -> SKIP
         "bbb..." -> have it locally -> SKIP
         "XXX..." -> DON'T have it -> DOWNLOAD from CloudFront
         "ddd..." -> have it locally -> SKIP
         "eee..." -> have it locally -> SKIP
       |
       Download: 1 chunk (4MB) instead of full file (20MB). 80% savings.
       Reassemble: replace chunk 2, rebuild report.docx locally.
    |
    v
    7. CONFLICT DETECTION (concurrent edits):
       Phone user also edited report.docx while offline:
         Phone version: based on v3, modified chunks 4 and 5
         Server version: v4 (laptop modified chunk 2)
       |
       Phone sends upload with oldVersion=3, but server is at v4.
       |
       CONFLICT RESOLUTION STRATEGIES:
         a. LAST-WRITER-WINS (Google Drive):
              Server accepts phone's upload as v5.
              Laptop's v4 changes to chunk 2 are preserved.
              Phone's changes to chunks 4,5 are applied.
              Non-overlapping chunks -> auto-merge possible.
              If SAME chunk changed: latest timestamp wins. LWW risks data loss.
         |
         b. KEEP-BOTH (Dropbox):
              Server creates: "report.docx" (laptop's version)
                              "report (conflict copy).docx" (phone's version)
              User manually resolves.
              No data loss, but clutters folder.
         |
         c. THREE-WAY MERGE (Git-style):
              Base: v3 (common ancestor)
              Theirs: v4 (laptop's changes)
              Ours: phone's changes
              Merge at chunk level: non-conflicting chunks auto-merge.
              Conflicting chunks (same chunk changed on both): flag for user.
    |
    v
    8. CURSOR-BASED SYNC (efficient polling):
       Each device maintains a sync cursor:
         cursor = { lastSyncVersion: "2026-04-26T14:30:05", sequenceId: 12345 }
       |
       On reconnect: GET /api/sync?cursor=cursor_abc123
       Server returns ALL changes since cursor position:
         Changes since last sync: file_002 updated, file_007 deleted, file_011 created
       |
       Cursor is opaque to client (server determines position).
       Server uses cursor to query DynamoDB GSI on timestamp.
       Efficient: only returns changes the device hasn't seen.
```

---

## Cost Estimation (100M Users Scale)

### Assumptions

```
Total users:                     100,000,000 (100M)
Daily Active Users:              20,000,000 (20M DAU, 20% active)
Average files per user:          500
Total files:                     50 billion
Average file size:               5 MB
Dedup savings:                   40% (many duplicate documents, photos, etc.)
Effective storage per file:      3 MB (after dedup)
Total storage:                   50B * 3 MB = 150 PB (petabytes)
Daily uploads:                   20M users * 2 files/day = 40M uploads
Daily downloads:                 20M users * 5 files/day = 100M downloads
Sync events per day:             20M users * 10 events = 200M events
Average chunk size:              4 MB
Chunks per file (avg):           1.25 (most files < 4MB, large files split)
Total chunk operations/day:      40M * 1.25 = 50M chunk uploads
```

### Monthly Cost Breakdown (AWS)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **S3 Storage (chunks)** | 150 PB, mixed Standard (10%) + S3 IA (60%) + Glacier (30%). Avg $8/TB/month blended | ~$1,200,000 |
| **S3 Data Transfer (downloads)** | 100M downloads * 5 MB = 500 TB/month. CloudFront reduces origin egress. $0.05/GB blended | ~$25,000 |
| **CloudFront CDN** | 500 TB delivery, 3B requests/month | ~$45,000 |
| **RDS Aurora PostgreSQL (metadata)** | Multi-AZ, db.r6g.4xlarge cluster, 20 read replicas, 10 TB storage | ~$50,000 |
| **DynamoDB (chunk index + versions)** | 50M writes/day + 150M reads/day. On-demand. 500 TB storage | ~$120,000 |
| **ElastiCache Redis (cache)** | 50 shards, r6g.2xlarge, 1 replica each. Hot metadata + dedup cache | ~$75,000 |
| **ECS Fargate (API + upload service)** | 200 tasks, 4 vCPU / 8 GB each. Upload processing, dedup, sync | ~$90,000 |
| **Lambda (thumbnails + events)** | 40M invocations/month, 512 MB, avg 2s execution | ~$15,000 |
| **Step Functions (upload pipeline)** | 40M executions/month, ~7 steps each | ~$10,000 |
| **SQS + SNS (sync events)** | 200M events/month, fan-out to ~3 devices each = 600M messages | ~$300 |
| **KMS (encryption)** | 200M API calls/month (encrypt/decrypt per chunk) | ~$600 |
| **CloudWatch + X-Ray** | Metrics, logs, traces, dashboards | ~$5,000 |
| **Route 53** | DNS, health checks, latency-based routing | ~$200 |
| **Total** | | **~$1,636,100/month** |

### Cost per User

| Scale | Users | Storage | Monthly Cost | Cost/User/Month |
|-------|-------|---------|-------------|-----------------|
| Startup | 100K | 150 TB | ~$8,000 | $0.08 |
| Growth | 1M | 1.5 PB | ~$35,000 | $0.035 |
| Scale | 10M | 15 PB | ~$200,000 | $0.02 |
| Dropbox-scale | 100M | 150 PB | ~$1,636,000 | $0.016 |

### Cost Optimization Strategies

1. **Intelligent Tiering** -- Files not accessed for 30 days move to S3 IA (45% cheaper). 90 days to Glacier (80% cheaper). Most files are write-once-read-rarely.
2. **Deduplication** -- Content-addressable storage with reference counting. Same photo uploaded by 1000 users stored ONCE. Saves 30-50% storage at scale.
3. **Delta Sync** -- Upload only changed chunks, not the full file. A 100 MB file with 1 changed page uploads 4 MB, not 100 MB. 96% bandwidth savings.
4. **CloudFront Caching** -- Popular shared files served from edge (cache hit rate ~85%). Reduces S3 egress costs by 85%.
5. **Reserved Capacity** -- RDS, ElastiCache, ECS: 1-year reserved instances save ~40% vs on-demand.
6. **Chunk Reference Counting** -- Delete chunks only when ref_count = 0 (no file references them). Shared chunks between versions and users are never duplicated.

---

## Dropbox's Actual Architecture

```
DROPBOX ARCHITECTURE EVOLUTION:

  2007-2014: ALL ON AWS S3
    - Every file chunk stored in S3
    - Metadata in MySQL (sharded)
    - Sync via notification servers (long polling)
    - Cost: S3 was ~40% of Dropbox's total spend
    - At 500 PB+ storage, S3 bills were enormous

  2015-2017: MIGRATION TO MAGIC POCKET (custom storage)
    - Dropbox built their own block storage system: "Magic Pocket"
    - Why: at their scale, building storage was cheaper than S3
    - Runs on custom hardware in Dropbox-owned data centers
    - Reduced storage costs by ~50% (estimated savings: $75M/year)

  MAGIC POCKET ARCHITECTURE (Numbered):

    1. BLOCK STORAGE LAYER:
       Files split into 4 MB blocks (chunks).
       Each block stored with content-addressable hash (SHA-256).
       Blocks written to custom storage cells:
         Cell = rack of storage servers with erasure coding
         Reed-Solomon erasure coding: split block into N data + K parity shards
         Can lose K servers and still reconstruct the block
         More storage-efficient than 3x replication (1.5x overhead vs 3x)

    2. METADATA LAYER (Edgestore):
       Custom distributed metadata store (replaced MySQL sharding)
       Stores: file -> chunk list, user -> file tree, sharing, permissions
       Built on top of MySQL but with custom sharding and routing layer
       Handles billions of metadata operations per day

    3. BLOCK INDEX:
       Maps: SHA-256 hash -> physical location (cell, server, offset)
       Enables deduplication: if hash exists, don't store again
       Reference counting: block deleted when last reference removed
       Billions of entries, served from memory + SSD

    4. NOTIFICATION SYSTEM:
       Long-polling based: client holds open HTTP connection
       Server pushes: "files changed in your namespace"
       Client pulls delta: "what changed since my last cursor?"
       ~1 billion notifications per day at Dropbox scale

    5. SYNC ENGINE (client-side):
       Watches local filesystem for changes (inotify/FSEvents)
       Computes chunk hashes on modified files
       Sends only changed chunks (delta sync)
       Conflict resolution: "keep both" (creates conflict copy)
       Bandwidth throttling: respects user-set upload/download limits

  KEY NUMBERS (Dropbox circa 2023):
    - 700+ million registered users
    - 17+ million paying users
    - 2+ exabytes (EB) total storage
    - 1.2+ billion files synced per day
    - 99.999999999% data durability (11 nines, matching S3)
```

---

## Data Durability: S3 Eleven Nines and Replication Strategies

```
AWS S3 DURABILITY: 99.999999999% (11 nines)

  What 11 nines means:
    If you store 10 million objects, expect to lose 1 object every 10,000 years.
    For practical purposes: your data will not be lost.

  HOW S3 ACHIEVES THIS (Numbered):

    1. AUTOMATIC REPLICATION:
       Every object replicated across minimum 3 Availability Zones (AZs)
       Each AZ is a physically separate data center (different power, cooling, networking)
       Object is durable after acknowledged write to all 3 AZs

    2. INTEGRITY CHECKING:
       MD5 checksums computed on upload (Content-MD5 header)
       Periodic background scrubbing: read every object, verify checksum
       If corruption detected: auto-repair from healthy replica
       Bit-rot detection: catches silent disk corruption before it spreads

    3. ERASURE CODING (for efficiency):
       Rather than 3 full copies (3x storage overhead):
       S3 uses erasure coding: split data into N fragments + K parity fragments
       Can reconstruct from any N of (N+K) fragments
       Storage overhead: ~1.5x instead of 3x (significant savings at exabyte scale)

    4. VERSIONING (optional but recommended):
       S3 versioning: every overwrite creates a new version
       Accidental deletion: previous version still accessible
       Ransomware protection: delete markers are soft-deletes, versions preserved

  REPLICATION STRATEGIES FOR FILE STORAGE:

    +---------------------------+----------------------------------+-----------------------------+
    | Strategy                  | Durability / Availability        | Cost / Trade-off            |
    +---------------------------+----------------------------------+-----------------------------+
    | S3 Standard (default)     | 11 nines durability, 99.99%      | $0.023/GB/month             |
    |                           | availability, 3 AZ replication   | Best for active files       |
    +---------------------------+----------------------------------+-----------------------------+
    | S3 Cross-Region           | 11 nines + geographic redundancy | 2x storage cost +           |
    | Replication (CRR)         | Survives entire region failure   | data transfer fees          |
    |                           | Async replication (seconds lag)  | Use for critical files only |
    +---------------------------+----------------------------------+-----------------------------+
    | S3 + Glacier Deep Archive | 11 nines durability, retrieval   | $0.00099/GB/month           |
    |                           | in 12-48 hours                   | 95% cheaper than Standard   |
    |                           |                                  | For file versions > 90 days |
    +---------------------------+----------------------------------+-----------------------------+
    | Custom (Magic Pocket)     | 11 nines via erasure coding      | 50% cheaper than S3 at      |
    |                           | on owned hardware                | exabyte scale. High upfront |
    |                           |                                  | investment. Dropbox's choice|
    +---------------------------+----------------------------------+-----------------------------+

  FOR OUR DESIGN:
    Active chunks:    S3 Standard (3 AZ, 11 nines)
    Older versions:   S3 Intelligent-Tiering -> IA after 30 days -> Glacier after 90 days
    Critical files:   S3 Cross-Region Replication (user-configurable per folder)
    Thumbnails:       S3 One Zone-IA (regenerable, lower durability acceptable)
```

---

## Interview Tip

> "For a file storage system like Google Drive on AWS, I'd use **S3 for block storage** with content-addressable chunks -- each file is split into **4MB chunks**, hashed with **SHA-256**, and stored at `s3://chunks/{hash}`. This gives us **deduplication for free**: if two users upload the same file, the chunks already exist -- we just create a new metadata pointer. Dedup saves **30-50% storage** at scale. **Metadata lives in Aurora PostgreSQL** (file tree, sharing, permissions) and **DynamoDB** (chunk mappings, dedup index, version history). **Upload flow**: client chunks the file locally, sends chunk hashes for dedup check, gets presigned URLs for only the missing chunks, uploads in parallel directly to S3. **Step Functions** orchestrate the post-upload pipeline: validate hashes, register metadata, generate thumbnails via **Lambda**, publish sync events via **SNS/SQS**. **Sync uses long polling**: devices hold open connections, server pushes change notifications, devices pull deltas using a cursor. **Delta sync** means editing a 100MB file uploads only the changed 4MB chunk, not the entire file. **Versioning** is cheap: each version is a list of chunk hashes, and unchanged chunks are shared across versions (copy-on-write). **Conflict resolution**: last-writer-wins (Google Drive) or keep-both (Dropbox). Storage is tiered: **S3 Standard** for active files, **S3 IA** after 30 days, **Glacier** after 90 days. S3 provides **11 nines durability** via 3-AZ replication and erasure coding."

This shows you understand **content-addressable storage, chunk-level deduplication, delta sync, presigned URL uploads, version history via chunk lists, conflict resolution strategies, and storage tiering** -- the seven pillars of file storage system design.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| File chunk storage | S3 Standard | Content-addressable: key = SHA-256 hash, SSE-KMS encryption | Immutable chunks, 11 nines durability, dedup by hash |
| File metadata | RDS Aurora PostgreSQL | Multi-AZ, read replicas, 10 TB+ | File tree, folders, sharing, permissions, search |
| Chunk index + dedup | DynamoDB on-demand | PK=chunk_hash, ref_count, s3_key | Sub-ms dedup lookup, billions of chunks, auto-scaling |
| Version history | DynamoDB | PK=file_id, SK=version, chunks=[hashes] | Ordered versions, cheap storage, fast queries |
| Hot metadata cache | ElastiCache Redis | Cluster mode, multi-AZ | Folder listings, recent file metadata, dedup cache |
| File upload | S3 presigned URLs | Direct client-to-S3, 4MB chunk multipart | Bypass API servers for data, reduce network hops |
| Upload orchestration | Step Functions | 7-step pipeline per upload | Validate, index, thumbnail, cache bust, notify |
| Thumbnail generation | Lambda (S3 event trigger) | 512 MB memory, 30s timeout, Sharp/ImageMagick | On-demand, pay-per-use, auto-scales with uploads |
| Sync notifications | SNS + SQS | Fan-out to per-device queues | Reliable delivery, device-specific notification |
| Download acceleration | CloudFront | Regional edge caches, signed URLs | 85% cache hit for popular shared files |
| Encryption | KMS | Customer-managed CMK, envelope encryption | Compliance, key rotation, per-user encryption option |
| Monitoring | CloudWatch + X-Ray | Upload latency, dedup ratio, sync lag | Alert on upload failures, dedup ratio drops |
