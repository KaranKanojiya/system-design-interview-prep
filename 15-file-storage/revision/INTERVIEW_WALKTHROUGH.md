# Interview Walkthrough -- File Storage System (Google Drive/Dropbox)

> **Total time: ~35 minutes. The Deduplication Deep Dive is 60% of this interview.**
> This problem tests chunked upload, content-addressable storage, deduplication, delta sync, conflict resolution, versioning, and scaling storage to exabyte scale. The hard part is explaining how files are split into chunks, deduplicated across users via SHA-256 hashing, and synchronized across devices using delta sync with cursor-based change tracking -- with concrete numbers and Dropbox's real architecture as reference.

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "How many users and what's the average storage per user? 100M users at 5GB each = 500 PB. This determines whether we use S3 or need custom storage like Dropbox's Magic Pocket."
- "What file sizes are we dealing with? Mostly small files (< 10MB photos, docs) or large files (1GB+ videos)? This affects chunk size and upload strategy."
- "Do we need real-time sync or eventual sync? Google Drive syncs within seconds; an archival system could batch hourly. This changes the notification architecture."
- "Is cross-device sync required? Desktop + mobile + web? Each platform has different file system watchers and network constraints."
- "What's the conflict resolution requirement? Last-writer-wins (simple, lossy) or keep-both (safe, cluttered)? Or do we need three-way merge?"
- "Do we need versioning? How many versions? 30 versions like Dropbox, or unlimited like Google Drive? Versioning interacts directly with chunk storage strategy."

### Clarified Scope

```
In scope:   File upload (chunked, parallel, resumable), download,
            deduplication (content-addressable, cross-user), delta sync
            (cursor-based, long polling), versioning (30 versions, chunk-level
            copy-on-write), conflict resolution (LWW + keep-both), folder
            hierarchy (Composite pattern), sharing + permissions
Out of scope: Real-time collaborative editing (that's Google Docs, different
              problem), full-text search inside files (mention only), preview/
              thumbnail generation (mention Lambda), offline editing (mention
              as extension), mobile-specific optimizations (mention throttling)
```

### What This Signals

You understand this is a **storage efficiency + sync problem** where the hard part is minimizing storage (dedup), minimizing bandwidth (delta sync), and keeping multiple devices in lockstep (cursor-based sync). You're probing for scale (S3 vs custom), file characteristics (chunk size), and consistency model (conflict resolution) because these fundamentally drive the architecture.

**Common follow-up:** "Why does chunk size matter so much?"

**Answer:** "Chunk size is a three-way trade-off. Too small (512KB): more chunks per file means more metadata overhead, more dedup index lookups, more S3 PUT operations (each costs $0.005 per 1000). Too large (16MB): coarser delta sync -- changing one paragraph re-uploads 16MB instead of 4MB. Also, larger chunks reduce dedup hit rate because a single byte change creates a completely different hash. 4MB is the sweet spot: Dropbox uses this. It balances metadata overhead (~25 chunks for a 100MB file), delta efficiency (a small edit re-uploads 4MB, not the full file), and dedup granularity (4MB blocks have good cross-file collision rates for common document formats)."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll separate this into two planes: **metadata** and **block storage**. Metadata (file tree, sharing, permissions, version lists) lives in **RDS Aurora PostgreSQL** for relational queries, while the chunk-to-file mapping and dedup index live in **DynamoDB** for high-throughput key-value lookups. Block storage is **S3** -- files are split into **4MB chunks**, each stored at `s3://chunks/{sha256_hash}` (content-addressable). The upload flow: client chunks the file locally, sends chunk hashes for a **dedup check**, gets **presigned URLs** for only the missing chunks, and uploads directly to S3 in parallel. A **Step Functions** pipeline orchestrates the post-upload work: validate hashes, register metadata, update dedup index, generate thumbnails, publish sync events. For sync, I'll use **long polling**: each device holds an open HTTP connection (60s timeout), the server pushes change notifications via **SNS/SQS**, and the device pulls deltas using a **cursor**. **Delta sync** means only changed chunks transfer -- editing a 100MB file might upload just 4MB. The system is **CP for metadata** (file tree must be consistent) and **AP for sync notifications** (a 2-second delay is invisible to users)."

### Draw This Diagram

```
              +-----------------------------------+
              |        Clients (Desktop/Mobile)   |
              | Laptop, Phone, Tablet, Web        |
              | File watcher: detect local changes|
              | Chunker: split files into 4MB     |
              | Dedup: hash chunks with SHA-256    |
              +----------------+------------------+
                               |
              1. Upload: chunk hashes for dedup check
              2. Server returns: which chunks to upload + presigned URLs
              3. Client uploads missing chunks directly to S3 (parallel)
              4. Long poll: hold connection for sync notifications
                               |
                               v
              +-----------------------------------+
              |     API Gateway + CloudFront      |
              |  REST: upload, download, metadata |
              |  Long poll: /sync?cursor=X        |
              +--------+--------------+-----------+
                       |              |
          metadata     |              |    sync events
          operations   |              |
                       v              v
              +----------------+  +----------------+
              |  ECS Fargate   |  |  ECS Fargate   |
              |  (API Service) |  |  (Sync Service)|
              |                |  |                 |
              | Upload flow:   |  | Long polling:   |
              |  dedup check   |  |  hold connection|
              |  presigned URL |  |  push on change |
              |  version mgmt  |  |  cursor tracking|
              | Download flow: |  |  conflict detect|
              |  chunk list    |  |                 |
              |  signed URLs   |  |                 |
              +-------+--------+  +--------+--------+
                      |                     |
         5. Persist metadata + chunk mappings
         6. Publish sync events to device queues
                      |                     |
           +----------v---------------------v----------+
           |                                           |
    +------v-------+  +--------v--------+  +-----------v--+
    | RDS Aurora   |  | DynamoDB        |  | S3           |
    | PostgreSQL   |  |                 |  | (chunks)     |
    |              |  | Chunk index:    |  |              |
    | files table  |  |  PK=sha256_hash |  | /chunks/     |
    | folders table|  |  ref_count, s3key|  |  {sha256}   |
    | sharing      |  | Version history:|  | /thumbnails/ |
    | permissions  |  |  PK=file_id     |  |  {file_id}/  |
    | users        |  |  SK=version     |  |              |
    +--------------+  |  chunks=[hashes]|  | SSE-KMS      |
                      +-----------------+  | encryption   |
           |                               +--------------+
    +------v-------+         +------------------+
    | ElastiCache  |         | SNS + SQS        |
    | Redis        |         |                  |
    |              |         | SNS topic:        |
    | folder cache |         |  file-changes     |
    | dedup cache  |         | SQS queues:       |
    | hot metadata |         |  per-device queues|
    +--------------+         +------------------+

  UPLOAD FLOW (when Alice uploads "report.pdf", 20MB):

    7. Client chunks file (5 chunks * 4MB):
       [hash_A, hash_B, hash_C, hash_D, hash_E]

    8. Client sends to API: POST /upload/init
       { fileName: "report.pdf", size: 20971520, chunks: [hash_A, ..., hash_E] }

    9. Server dedup check (DynamoDB):
       hash_A -> EXISTS (ref_count=3)  -> skip
       hash_B -> EXISTS (ref_count=1)  -> skip
       hash_C -> NOT FOUND             -> needs upload
       hash_D -> NOT FOUND             -> needs upload
       hash_E -> EXISTS (ref_count=7)  -> skip

    10. Server returns presigned URLs for missing chunks:
        { needed: [hash_C, hash_D],
          urls: { hash_C: "https://s3.../chunks/hash_C?X-Amz-Signature=...",
                  hash_D: "https://s3.../chunks/hash_D?X-Amz-Signature=..." } }

    11. Client uploads 2 chunks directly to S3 (parallel):
        PUT s3://chunks/hash_C [4MB]
        PUT s3://chunks/hash_D [4MB]
        (8 MB uploaded instead of 20 MB -- 60% savings from dedup)

    12. Client confirms: POST /upload/complete
        { fileId: "file_001", chunks: [hash_A, hash_B, hash_C, hash_D, hash_E] }

    13. Step Functions pipeline:
        -> Validate chunk integrity (SHA-256 re-check)
        -> Register file in RDS (files table)
        -> Store version in DynamoDB (PK=file_001, SK=v#1, chunks=[...])
        -> Update dedup index (ref_count++ for hash_C, hash_D)
        -> Generate thumbnail (Lambda)
        -> Bust cache (Redis DEL folder:work:listing)
        -> Publish sync event (SNS -> SQS per-device queues)
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| API Gateway + CloudFront | REST endpoints for upload/download/metadata. Presigned URL generation. Long poll endpoint for sync. CDN for popular file downloads. | N/A (routing/caching layer) |
| ECS Fargate (API Service) | Upload orchestration: dedup check, presigned URL generation, version management. Download: chunk list resolution, signed URL generation. Stateless -- scales horizontally. | N/A (compute layer) |
| ECS Fargate (Sync Service) | Long polling connections. Cursor tracking. Change notification delivery. Conflict detection. Stateless -- connections are HTTP, not sticky. | AP (sync notifications eventual) |
| RDS Aurora PostgreSQL (Metadata) | File tree: files, folders, sharing, permissions, users. Relational queries: "list my files", "who shared this with me", "search by name". | CP (file tree must be consistent -- deleted file must not appear) |
| DynamoDB (Chunk Index + Versions) | Dedup index: hash -> ref_count, s3_key. Version history: file_id -> version -> chunk list. High throughput: billions of lookups/day. | CP (dedup index must be consistent for ref_count accuracy) |
| S3 (Block Storage) | Content-addressable chunk storage: key = SHA-256 hash. Immutable objects. SSE-KMS encryption. 11 nines durability. Storage tiering: Standard -> IA -> Glacier. | CP (chunk content must be durable and consistent) |
| ElastiCache Redis | Hot metadata cache: folder listings, recent dedup lookups, file metadata. Reduces DynamoDB reads by ~80%. | AP (cache miss falls through to DynamoDB/RDS) |
| SNS + SQS (Sync Events) | Change event fan-out: SNS topic -> per-device SQS queues. Reliable, ordered, at-least-once delivery. Decouples file operations from sync. | AP (event delay acceptable, cursor catches up on reconnect) |

### What This Signals

You lead with the **metadata vs block storage separation** -- the defining architectural insight for file storage systems. You clearly separate the fast path (dedup check, presigned URL, direct-to-S3 upload) from the orchestration path (Step Functions pipeline). You mention content-addressable storage by name, showing you understand this is fundamentally a deduplication problem, not just a CRUD file system.

**Common follow-up:** "Why presigned URLs? Why not upload through your API server?"

**Answer:** "Because the API server becomes a bottleneck and a cost center if it proxies file data. A 100MB file upload through the API server means: (1) client -> API server (100MB network transfer), (2) API server -> S3 (another 100MB network transfer). That's 200MB of network I/O per upload, plus the API server needs enough memory to buffer the stream. With presigned URLs, the client uploads directly to S3 -- the API server only handles the 500-byte metadata request (chunk hashes, file name). At 40M uploads per day, this saves petabytes of unnecessary network transfer through the API tier. The presigned URL is cryptographically signed with an expiration time (15 minutes), so it's secure -- only the intended chunk can be uploaded to the intended S3 key."

---

## Phase 3: Deduplication Deep Dive (8-10 min)

**This is THE star section for file storage interviews. Spend the most time here.**

### Part A: Content-Addressable Storage

> "The fundamental insight is that the SHA-256 hash of a chunk's content IS the storage address. This is the same principle as Git's object storage. Two chunks with identical content produce the same hash, so they map to the same S3 key. Store once, reference many times."

```
CONTENT-ADDRESSABLE STORAGE (Numbered):

      1. HOW IT WORKS:
         File: "report.pdf" (20 MB)
         Chunking: split into 5 chunks of 4 MB each
         |
         Chunk 0: bytes[0..4MB]     -> SHA-256("...binary...") = "a3f2b8c1e4..."
         Chunk 1: bytes[4MB..8MB]   -> SHA-256("...binary...") = "7c91e4d2f7..."
         Chunk 2: bytes[8MB..12MB]  -> SHA-256("...binary...") = "d5a01733a9..."
         Chunk 3: bytes[12MB..16MB] -> SHA-256("...binary...") = "b8e245f100..."
         Chunk 4: bytes[16MB..20MB] -> SHA-256("...binary...") = "1b8e33f0cc..."
         |
         S3 storage:
           s3://chunks/a3f2b8c1e4...  [4 MB]
           s3://chunks/7c91e4d2f7...  [4 MB]
           s3://chunks/d5a01733a9...  [4 MB]
           s3://chunks/b8e245f100...  [4 MB]
           s3://chunks/1b8e33f0cc...  [4 MB]
         |
         File metadata (DynamoDB):
           PK=file_001, version=1
           chunks=["a3f2b8c1e4...", "7c91e4d2f7...", "d5a01733a9...",
                   "b8e245f100...", "1b8e33f0cc..."]

      2. DEDUP IN ACTION (User B uploads same file):
         User B uploads "quarterly_report.pdf" -- happens to be identical content.
         Client chunks it, computes hashes:
           Chunk 0: "a3f2b8c1e4..."  (identical to User A's chunk 0!)
           Chunk 1: "7c91e4d2f7..."  (identical!)
           ... all 5 hashes match.
         |
         Server dedup check:
           All 5 hashes exist in DynamoDB dedup index.
         Server response: { chunksNeeded: [] }  -- nothing to upload!
         |
         Server creates metadata ONLY:
           PK=file_002, version=1
           chunks=["a3f2b8c1e4...", "7c91e4d2f7...", ...]
           (same chunk hashes as file_001)
         |
         Dedup index ref_count updates:
           "a3f2b8c1e4..." -> ref_count: 1 -> 2
           "7c91e4d2f7..." -> ref_count: 1 -> 2
           ...
         |
         Storage used by User B's "copy": 0 bytes of chunk data.
         The 5 chunks are shared between file_001 and file_002.

      3. DEDUP INDEX (DynamoDB):
         +--------------------+-------------+----------------------------+
         | PK (chunk_hash)    | ref_count   | s3_key                     |
         +--------------------+-------------+----------------------------+
         | a3f2b8c1e4...      | 2           | chunks/a3f2b8c1e4...       |
         | 7c91e4d2f7...      | 2           | chunks/7c91e4d2f7...       |
         | d5a01733a9...      | 2           | chunks/d5a01733a9...       |
         | b8e245f100...      | 2           | chunks/b8e245f100...       |
         | 1b8e33f0cc...      | 2           | chunks/1b8e33f0cc...       |
         | ff02aa91c3...      | 47          | chunks/ff02aa91c3...       |
         +--------------------+-------------+----------------------------+
         |
         ff02aa91c3... has ref_count=47: this chunk appears in 47 different files.
         (Common: boilerplate PDF headers, standard image thumbnails, etc.)

      4. GARBAGE COLLECTION (when files are deleted):
         User A deletes file_001:
           For each chunk in file_001's chunk list:
             ref_count -= 1
           "a3f2b8c1e4..." -> ref_count: 2 -> 1  (still referenced by file_002)
           Chunk NOT deleted from S3 (ref_count > 0)
         |
         User B also deletes file_002:
           "a3f2b8c1e4..." -> ref_count: 1 -> 0
           ref_count = 0 -> schedule S3 DELETE (batch job, not synchronous)
         |
         Batch GC job (Lambda, hourly):
           Scan DynamoDB for ref_count = 0 entries older than 24 hours
           (24-hour grace period prevents race conditions during concurrent uploads)
           Delete from S3: s3://chunks/a3f2b8c1e4...
           Delete from DynamoDB dedup index

      5. SHA-256 COLLISION RISK:
         SHA-256 output: 256 bits = 2^256 possible hashes
         Probability of collision among 10^18 chunks: ~10^(-38)
         For comparison: probability of being struck by lightning twice: ~10^(-12)
         |
         Mitigation (belt and suspenders):
           Store chunk_size alongside hash in dedup index.
           If hash matches BUT sizes differ -> different content, store separately.
           In practice: SHA-256 collision has never been found. Not a real concern.
```

### Part B: Rabin Fingerprint Chunking (Content-Defined Boundaries)

> "Fixed-size chunking has a problem: if you insert 1 byte at the beginning of a file, EVERY chunk shifts by 1 byte, so ALL chunk hashes change. The entire file re-uploads. Rabin fingerprint chunking solves this by finding chunk boundaries based on content, not position."

```
RABIN FINGERPRINT CHUNKING (Numbered):

      1. THE PROBLEM WITH FIXED-SIZE CHUNKING:
         Original file: [AAAA|BBBB|CCCC|DDDD]  (4 chunks, | = 4MB boundary)
         Hashes: [hash_A, hash_B, hash_C, hash_D]

         User inserts 10 bytes at the beginning:
         Modified:     [XAAA|ABBB|BCCC|CDDD|D...]
         Hashes: [hash_X, hash_Y, hash_Z, hash_W, hash_V]

         ALL 4 hashes changed! Even though only 10 bytes were inserted.
         Delta sync: upload ALL 4 chunks (16 MB) instead of just the change.
         Dedup: zero matches because every chunk shifted.

      2. HOW RABIN FINGERPRINT WORKS:
         Instead of fixed boundaries, use a ROLLING HASH to find boundaries:
         |
         Slide a window (48 bytes) across the file content.
         At each position, compute Rabin fingerprint (rolling polynomial hash).
         If fingerprint % TARGET == 0, declare a chunk boundary.
         |
         TARGET = 4194304 (4 MB): on average, a boundary every 4 MB.
         But boundaries are determined by CONTENT, not position.
         |
         Original file: [AAAA|BBB|CCCCC|DDD]  (variable-size chunks)
         Boundaries found at content-dependent positions.
         Hashes: [hash_A, hash_B, hash_C, hash_D]

         Insert 10 bytes at beginning:
         Modified:     [XAAAA|BBB|CCCCC|DDD]
         Only the FIRST chunk changed! Boundaries for BBB, CCCCC, DDD unchanged.
         Hashes: [hash_X, hash_B, hash_C, hash_D]

         Delta sync: upload 1 chunk instead of 4. 75% savings.

      3. WHY "CONTENT-DEFINED":
         The boundary is where the content LOOKS like a boundary (hash % TARGET == 0).
         Inserting data before a boundary doesn't change the boundary position
         because the boundary is defined by the content AT that boundary.
         |
         Think of it like finding sentence breaks: inserting a word in paragraph 1
         doesn't change where paragraph 2 starts.

      4. CHUNK SIZE CONTROL:
         Pure Rabin can produce tiny chunks (100 bytes) or huge chunks (100 MB).
         Solution: enforce min/max:
           Minimum chunk size: 1 MB (skip Rabin check below this)
           Maximum chunk size: 8 MB (force boundary regardless of Rabin)
           Average chunk size: ~4 MB (tuned by TARGET value)
         |
         This gives variable-size chunks in the 1-8 MB range, averaging ~4 MB.

      5. FIXED vs RABIN -- WHEN TO USE WHICH:
         +---------------------+---------------------------+----------------------------+
         | Aspect              | Fixed-Size (4MB)          | Rabin Fingerprint          |
         +---------------------+---------------------------+----------------------------+
         | Chunk boundaries    | Every 4MB exactly         | Content-dependent          |
         | Dedup after insert  | ALL chunks change         | Only affected chunk changes|
         | Implementation      | Simple (offset arithmetic)| Complex (rolling hash)     |
         | CPU overhead        | None (just split)         | ~50MB/s hash throughput    |
         | Best for            | Append-only files (logs)  | Editable files (docs, code)|
         | Used by             | Most simple systems       | Dropbox, rsync             |
         +---------------------+---------------------------+----------------------------+

      DROPBOX USES RABIN:
         Dropbox chose Rabin fingerprinting because most files are edited
         (documents, spreadsheets, code), not just appended to.
         With fixed chunking, editing a 50MB PowerPoint re-uploads the entire file.
         With Rabin, only the changed slide's chunk re-uploads (~4MB).
         At 1.2 billion files synced per day, this saves PETABYTES of bandwidth.
```

### Part C: Dedup at Scale -- Real Numbers

> "Let me walk through the actual savings at Dropbox-like scale to show why dedup is the single most important architectural decision."

```
DEDUP SAVINGS AT SCALE (Numbered):

      1. CROSS-USER DEDUP:
         100M users. 50B files total.
         Average file size: 5 MB. Naive storage: 250 PB (petabytes).
         |
         Common duplicates:
           - Company logos, templates, boilerplate docs
           - Shared files (shared folder = N pointers, 1 copy)
           - Email attachments forwarded to many people
           - OS/app files synced by multiple users
           - Stock photos from common libraries
         |
         Measured dedup ratio at Dropbox: 30-50%.
         At 40% dedup: 250 PB -> 150 PB (100 PB saved!)
         At $0.02/GB/month (S3 blended): 100 PB saved = $2M/month savings.

      2. CROSS-VERSION DEDUP:
         User edits a 20 MB file 10 times (10 versions).
         Each edit changes ~2 chunks out of 5.
         |
         Without dedup: 10 versions * 20 MB = 200 MB stored.
         With chunk-level dedup:
           v1: [A, B, C, D, E]        5 unique chunks = 20 MB
           v2: [A, B, C', D, E]       1 new chunk (C') = 4 MB delta
           v3: [A, B', C', D, E]      1 new chunk (B') = 4 MB delta
           v4: [A, B', C', D', E]     1 new chunk (D') = 4 MB delta
           ... (10 versions, avg 1-2 new chunks each)
         Total: 20 MB + 9 * ~6 MB = ~74 MB (vs 200 MB naive)
         Savings: 63%

      3. INTRA-FILE DEDUP:
         Large ZIP files often contain repeated content.
         ISO images, VM snapshots, database dumps have repeated patterns.
         |
         1 GB database dump: many repeated page headers, empty pages.
         250 chunks (4 MB each), but only 180 unique hashes.
         Storage: 720 MB instead of 1 GB. 28% savings on a single file.

      4. DEDUP CHECK PERFORMANCE:
         DynamoDB dedup index: hash -> ref_count
         Single-digit millisecond reads per hash.
         BatchGetItem: 100 hashes per call -> ~5ms.
         Typical file (5 chunks): 1 BatchGetItem call -> 5ms total.
         |
         Redis cache layer in front:
           Hot chunks (recently uploaded, popular files): cached in Redis.
           Cache hit rate: ~60% (many uploads share recent chunks).
           Cache hit: < 1ms. Cache miss: fall through to DynamoDB (~5ms).
```

**Common follow-up:** "What if someone manipulates SHA-256 to create a hash collision and access another user's data?"

**Answer:** "First, SHA-256 collision has never been achieved -- it's computationally infeasible with current technology. But even if it were, there's no security risk because the dedup system only shares storage, not access. Each user has their own file metadata entry in RDS with their own permissions. The chunk in S3 is just raw bytes -- it has no user association. User A's file_001 points to chunk hash_X, and User B's file_002 also points to chunk hash_X, but User B can only access hash_X through their own file_002 metadata entry. They can't enumerate S3 chunk keys or bypass the API. The dedup is an internal storage optimization invisible to users."

---

## Phase 4: Sync & Conflict Resolution (5-7 min)

### Part A: Delta Sync with Cursor-Based Change Tracking

> "Sync is the second hardest part of file storage. The naive approach -- poll the server every 5 seconds for all files -- doesn't scale. Instead, we use cursor-based delta sync: each device maintains a cursor (an opaque position in the change log), and requests only changes since that cursor."

```
CURSOR-BASED DELTA SYNC (Numbered):

      1. SYNC ARCHITECTURE:
         Each file operation generates a change event:
           { changeId: 12345, type: "FILE_UPDATED", fileId: "file_001",
             version: 3, timestamp: "2026-04-26T14:30:05", userId: "user_001" }
         |
         Change events stored in DynamoDB:
           PK = namespace (userId or sharedFolderId)
           SK = changeId (monotonically increasing)
         |
         Each device has a cursor:
           device_cursor = { namespace: "user_001", lastChangeId: 12300 }
           "I've seen all changes up to changeId 12300"

      2. LONG POLLING FLOW:
         Device sends:
           GET /sync?cursor=12300&timeout=60
         |
         Server checks: any changes after changeId 12300 for user_001?
           YES -> return immediately:
             { changes: [
                 { changeId: 12345, type: FILE_UPDATED, fileId: file_001 },
                 { changeId: 12346, type: FILE_CREATED, fileId: file_015 }
               ],
               cursor: 12346,
               hasMore: false }
           |
           NO -> hold connection open (long poll):
             Wait up to 60 seconds for a new change to arrive.
             If change arrives within 60s -> return immediately.
             If 60s timeout -> return empty: { changes: [], cursor: 12300 }
             Client immediately reconnects with same cursor.

      3. DEVICE PROCESSES CHANGES:
         For FILE_UPDATED (file_001, version 3):
           Device has file_001 at version 2.
           Request: GET /files/file_001/chunks?version=3
           Response: { chunks: ["hash_A", "hash_B", "hash_X", "hash_D", "hash_E"] }
           |
           Local comparison:
             Local v2 chunks: ["hash_A", "hash_B", "hash_C", "hash_D", "hash_E"]
             Server v3 chunks: ["hash_A", "hash_B", "hash_X", "hash_D", "hash_E"]
             |
             Missing locally: "hash_X" (chunk 2 changed from C to X)
             Download: GET s3://chunks/hash_X (via CloudFront CDN) [4 MB]
             |
             Reassemble file: replace chunk 2 with hash_X content.
             Update local version to 3.
             Update cursor to 12345.

      4. MULTIPLE NAMESPACES (shared folders):
         Alice belongs to 3 namespaces:
           - personal: her own files
           - team_engineering: shared team folder
           - project_alpha: shared project folder
         |
         Device maintains 3 cursors, one per namespace.
         Long poll multiplexed: single connection, multiple namespaces.
         Server returns changes from ANY namespace that has new events.

      5. INITIAL SYNC (new device):
         Alice installs Dropbox on a new laptop.
         Cursor: 0 (start from beginning).
         |
         Server: "Here are ALL files in your namespace"
         Response: { changes: [all files], cursor: 99999, hasMore: true }
         |
         Device downloads file metadata first, then chunks (prioritized):
           Priority 1: recently modified files
           Priority 2: files in active folders
           Priority 3: older files (background sync)
         |
         Selective sync: user can choose which folders to sync locally.
         "Sync Work/ but not Archive/" -> only download Work/ chunks.

      6. BANDWIDTH EFFICIENCY:
         Without delta sync: "file changed" -> download entire file.
           100 MB file edited -> 100 MB downloaded per device.
         |
         With delta sync: "file changed, here are the new chunk hashes"
           Compare hashes, download only missing chunks.
           1 chunk changed -> 4 MB downloaded per device.
         |
         At scale: 1.2B files synced per day * 3 devices per user
           Without delta: catastrophic bandwidth
           With delta:    manageable (avg 2-3 chunks per sync = 8-12 MB)
```

### Part B: Conflict Resolution Deep Dive

> "Conflicts happen when two devices edit the same file while one is offline, or when network latency causes a race condition. There are three strategies, each with clear trade-offs."

```
CONFLICT RESOLUTION STRATEGIES (Numbered):

      1. LAST-WRITER-WINS (Google Drive):
         Device A edits report.docx (based on v2) at 14:30:00
         Device B edits report.docx (based on v2) at 14:30:05
         |
         Device A uploads first -> creates v3.
         Device B uploads with oldVersion=2, but server is at v3.
         |
         LWW resolution:
           Device B's timestamp (14:30:05) > Device A's (14:30:00)
           Device B's version becomes v4. Device A's v3 is preserved in history.
           Current file = Device B's content.
         |
         PROBLEM:
           Device A edited slides 1-5.
           Device B edited slides 6-10.
           LWW: current file has slides 6-10 edits only.
           Slides 1-5 edits are in v3 history but NOT in the current file.
           User must manually merge from version history.
         |
         WHEN LWW IS ACCEPTABLE:
           Single-user multi-device: "I edited on laptop, then switched to phone"
           Rare conflicts: < 0.1% of syncs have conflicts
           Speed: no user interaction needed, sync continues automatically

      2. KEEP-BOTH (Dropbox):
         Same scenario: Device A and B both edit report.docx based on v2.
         |
         Device A uploads first -> creates v3.
         Device B uploads with oldVersion=2, server at v3 -> CONFLICT.
         |
         Keep-both resolution:
           Preserve current file: "report.docx" = Device A's version (v3)
           Create conflict copy: "report (Alice's conflicted copy 2026-04-26).docx"
             = Device B's version (saved as new file)
         |
         User notified: "Conflicting changes detected on report.docx"
         User manually reviews both files and merges.
         |
         ADVANTAGES:
           Zero data loss. Both versions fully preserved.
           Simple to implement (just create a new file).
         |
         DISADVANTAGES:
           Folder clutter. "report (conflicted copy).docx" is ugly.
           User must manually merge. Most users ignore conflict copies.
           At scale: millions of conflict copies accumulate (Dropbox's real problem).

      3. THREE-WAY MERGE (Git-style, advanced):
         Base version: v2 (common ancestor)
         Theirs: v3 (Device A's changes)
         Ours: Device B's changes
         |
         Merge at CHUNK level:
           v2 chunks:  [A, B, C, D, E]
           v3 chunks:  [A, B, X, D, E]    (Device A changed chunk 2: C -> X)
           B's chunks: [A, B, C, D, Y]    (Device B changed chunk 4: E -> Y)
         |
         Non-conflicting: chunk 2 changed by A only -> use X.
                          chunk 4 changed by B only -> use Y.
         Merged result:   [A, B, X, D, Y]   -> auto-merged!
         |
         CONFLICTING: both changed chunk 2:
           v3: chunk 2 = X (Device A's version)
           B:  chunk 2 = Z (Device B's version)
           -> Cannot auto-merge. Flag chunk 2 as conflicted.
           -> Keep A's version, save B's chunk 2 as annotation.
           -> Notify user: "Chunk conflict in report.docx (pages 3-4)"

      4. CONFLICT DETECTION MECHANISM:
         Every upload includes: { fileId, oldVersion, newChunks }
         |
         Server check (DynamoDB conditional write):
           ConditionExpression: "currentVersion = :oldVersion"
           If condition passes: apply update, increment version
           If condition fails: CONFLICT -- another device updated first
         |
         This is optimistic concurrency control (same as database optimistic locking).
         No locks needed. Conflict is detected at write time, not prevented.

      5. CONFLICT RATE IN PRACTICE:
         Single-user (2-3 devices): < 0.1% of syncs conflict.
           Devices typically take turns (use laptop at work, phone at lunch).
         Multi-user (shared folders): < 1% of syncs conflict.
           Most users edit different files. Same-file conflicts are rare.
         |
         Dropbox reports: conflict copies are < 0.5% of total files.
         At 50 billion files: ~250 million conflict copies exist.
         Not great, not terrible. Users live with it.
```

**Common follow-up:** "How would you handle conflicts for binary files like images or videos where you can't do content merge?"

**Answer:** "For binary files, three-way merge is impossible -- you can't meaningfully merge two different JPEGs. Keep-both is the only safe option. Create a conflict copy and let the user choose which version to keep. In practice, binary file conflicts are even rarer than document conflicts because binary files are typically write-once (take a photo, upload it) rather than repeatedly edited. When they do conflict, it's usually because the user cropped the image on two devices -- they can visually compare and pick the better one."

---

## Phase 5: Scaling & Edge Cases (5-8 min)

### Part A: Scaling to 2.5 Exabytes (Dropbox Scale)

```
SCALING STRATEGY (Numbered):

      1. STORAGE SCALING:
         100M users * 5 GB/user = 500 PB naive storage.
         After dedup (40%): ~300 PB effective storage.
         After compression: ~250 PB.
         Dropbox (2023): 2+ EB (exabytes). They moved OFF S3 to Magic Pocket.
         |
         Our design (on S3):
           S3 automatically scales to exabytes. No sharding needed.
           Content-addressable keys: hash distribution is uniform (SHA-256).
           No hot partition problem: 2^256 possible keys, evenly distributed.
         |
         Storage tiering (automatic):
           Active files (accessed in 30 days): S3 Standard ($0.023/GB/month)
           Warm files (30-90 days): S3 IA ($0.0125/GB/month)
           Cold files (90+ days): Glacier ($0.004/GB/month)
           Deep archive (1+ year): Glacier Deep Archive ($0.00099/GB/month)
         |
         At 250 PB with 80% cold:
           50 PB Standard:  $1,150,000/month
           200 PB Glacier:  $800,000/month
           Total: ~$2M/month (vs $5.75M if all Standard)

      2. METADATA SCALING:
         50 billion files -> 50 billion rows in files table.
         RDS Aurora max: ~64 TB per cluster.
         |
         Sharding strategy: shard by userId hash.
           Shard 0: users 0-999999 (user_id % 100 = 0)
           Shard 1: users 1000000-1999999
           ...
           100 Aurora clusters, each holding ~500M files.
         |
         DynamoDB chunk index: auto-scales. No sharding needed.
           PK = chunk_hash (uniformly distributed by SHA-256)
           Billions of items, single-digit ms reads.

      3. UPLOAD THROUGHPUT:
         40M uploads/day = ~460 uploads/second average.
         Peak (Monday morning): 3x = ~1400 uploads/second.
         |
         Each upload:
           1 dedup check (DynamoDB BatchGetItem: ~5ms)
           N presigned URL generations (in-memory: < 1ms each)
           N S3 PUTs (client direct, doesn't hit our servers)
           1 Step Functions execution (async, doesn't block upload)
         |
         API tier:
           460 req/s average. 100 ECS tasks at 4 vCPU each.
           Each task handles ~5 req/s = well within capacity.
           Auto-scale to 300 tasks for peak.

      4. SYNC THROUGHPUT:
         20M DAU * 3 devices = 60M long-poll connections.
         Each connection: 1 HTTP request every ~30 seconds (average).
         Total: ~2M sync requests/second.
         |
         Sync service (stateless):
           Each request: check SQS queue for device, return changes.
           200 ECS tasks, each handling 10K req/s = 2M total.
         |
         SQS queues: 60M queues (one per device).
           SQS auto-scales. Cost: $0.40/million requests.
           2M req/s * 86400s * $0.40/M = ~$70K/month for SQS.
         |
         Alternative: shared SQS queues per user (not per device).
           20M queues instead of 60M. Device filters on client side.

      5. MAGIC POCKET (when S3 isn't enough):
         At 2+ exabytes, S3 cost: ~$20M/month (even with Glacier).
         Dropbox's calculation:
           Build own storage: $10M/month amortized (hardware + ops + data center)
           S3 cost: $20M/month
           Savings: $10M/month = $120M/year.
         |
         Magic Pocket uses:
           Commodity hardware (dense storage servers)
           Erasure coding: Reed-Solomon (12,4) -> any 12 of 16 shards reconstruct
             Storage overhead: 1.33x (vs S3's ~1.5x with erasure coding)
           Custom software for placement, replication, repair
           3 years to build and migrate (~2014-2017)
         |
         RULE OF THUMB:
           < 10 PB: use S3 (engineering cost of custom storage not worth it)
           10-100 PB: consider S3 + aggressive tiering
           > 100 PB: evaluate custom storage (Dropbox, Facebook, Google all build custom)
```

### Part B: Large File Handling

```
LARGE FILE EDGE CASES (Numbered):

      1. MULTI-GIGABYTE FILES (1 GB+ video):
         1 GB file = 250 chunks (4 MB each).
         |
         Parallel upload: 4 concurrent * 4 MB = 16 MB/s throughput.
         Time: 1 GB / 16 MB/s = ~63 seconds.
         |
         Resumable: if upload fails at chunk 100/250:
           Client resumes from chunk 100. Already-uploaded chunks skipped.
           Progress: 100/250 acknowledged, resume from 101.
         |
         Presigned URL expiration: 15 minutes per chunk.
           1 GB upload takes ~60 seconds. 15-minute URLs are sufficient.
           For extremely large files (10 GB+): generate URLs in batches.

      2. VERY SMALL FILES (< 4 MB):
         A 10 KB text file = 1 chunk (padded to fit, stored as-is).
         Dedup still works: hash of the 10 KB content.
         |
         Optimization: pack small files into aggregate chunks:
           10 files * 10 KB = 100 KB -> store as single S3 object.
           Reduces S3 PUT operations (each costs $0.005/1000).
           Trade-off: complicates deletion (must repack aggregate).
         |
         In practice: most systems store small files as-is.
         S3 PUT cost at 40M uploads/day: ~$200/day. Acceptable.

      3. ZERO-BYTE FILES:
         User creates empty file (common: .gitkeep, new document).
         No chunks to store. Metadata only.
         Hash of empty content: e3b0c44298fc... (well-known SHA-256 of empty string)
         Single dedup entry shared by ALL empty files globally.

      4. FILES THAT CHANGE COMPLETELY:
         User replaces entire content (overwrite, not edit).
         ALL chunks change. Zero dedup benefit for this file.
         |
         Delta sync still helps for OTHER files that haven't changed.
         This file's old version chunks: ref_count decremented.
         New chunks: stored fresh.
         |
         Version history preserves old chunks (ref_count > 0 from old version).
```

### Part C: Offline Sync and Device Management

```
OFFLINE SYNC (Numbered):

      1. DEVICE GOES OFFLINE:
         Alice's laptop loses WiFi.
         Long-poll connection drops.
         |
         Laptop continues working:
           Local file watcher still detects changes.
           Changes queued locally:
             [file_001 modified, file_015 created, file_003 deleted]
           No upload or sync possible.

      2. MEANWHILE, OTHER DEVICES EDIT:
         Alice's phone creates file_020.
         Bob (shared folder) modifies file_001.
         |
         Server creates change events:
           changeId 12347: file_020 created (phone)
           changeId 12348: file_001 updated (Bob)

      3. LAPTOP RECONNECTS:
         Step 1: Re-establish long-poll connection.
         Step 2: Send cursor: "last changeId I saw was 12300"
         Step 3: Server returns all changes since 12300:
           [12301...12348] = 48 changes to process.
         |
         Step 4: Process incoming changes:
           file_020 created by phone -> download metadata + chunks
           file_001 updated by Bob -> download delta chunks
         |
         Step 5: Upload queued local changes:
           file_001 modified locally (BUT Bob also modified it!) -> CONFLICT
           file_015 created -> upload chunks, register file
           file_003 deleted -> send delete to server

      4. CONFLICT ON FILE_001:
         Laptop's local version: based on v2, modified chunks 1,2
         Server version: v3 (Bob's changes to chunks 3,4)
         |
         Three-way merge at chunk level:
           Base (v2): [A, B, C, D, E]
           Bob (v3):  [A, B, X, Y, E]  (chunks 3,4 changed)
           Laptop:    [P, Q, C, D, E]  (chunks 1,2 changed)
         |
           Non-conflicting merge: [P, Q, X, Y, E]
           All changes preserved! No conflict copy needed.

      5. SELECTIVE SYNC:
         User setting: "Only sync Work/ folder on my phone"
         |
         Server tracks per-device sync scope:
           laptop: sync ALL folders
           phone: sync only folderId=folder_work
           tablet: sync Work/ and Personal/ (not Archive/)
         |
         Long-poll responses filtered by device's sync scope.
         Phone never receives changes for Archive/ folder.
         Saves bandwidth + storage on phone.
```

**Common follow-up:** "What happens if a user has 100,000 files and installs a new device?"

**Answer:** "Initial sync of 100K files would be terrible if we downloaded everything at once. Instead, we use progressive sync: (1) Download the file tree metadata first -- this is tiny (100K rows * 200 bytes = 20MB). User can immediately see their folder structure and file names. (2) Download thumbnails next -- visual preview without full file data. (3) Download file content on-demand: when the user opens a file, download its chunks. (4) Background sync: download the rest in priority order (recently modified first, then by folder). Dropbox calls this 'Smart Sync' -- files appear as placeholders locally (name + icon + size) but content is downloaded only when needed. This turns initial sync from 'download 500GB' to 'download 20MB metadata + on-demand chunks'."

---

## Phase 6: Tradeoffs (3-5 min)

### Fixed Chunking vs Rabin Fingerprint

| Aspect | Fixed-Size (4MB) | Rabin Fingerprint |
|--------|-------------------|-------------------|
| Chunk boundaries | Position-based (every 4MB) | Content-based (rolling hash) |
| Insert at beginning | ALL chunks change (all hashes invalidated) | Only affected chunk changes |
| Dedup after edits | Poor (shifted boundaries = new hashes) | Excellent (stable boundaries) |
| Implementation | Trivial (offset arithmetic) | Complex (rolling polynomial hash) |
| CPU overhead | Zero | ~50 MB/s throughput (acceptable) |
| Chunk size variance | Exactly 4MB (predictable) | 1-8MB variable (need min/max bounds) |
| Best for | Append-only files (logs, videos) | Editable files (docs, code, spreadsheets) |
| Used by | Most simple systems, early Google Drive | Dropbox (rsync-style), Restic backup |

**Say:** "I'd start with fixed-size chunking for simplicity -- it handles 90% of cases well because most file edits don't insert at the beginning. As the system matures, add Rabin fingerprint chunking for document types (DOCX, PPTX, code files) where insertion is common. The dedup index doesn't care about chunk size variance -- it's still hash -> S3 key regardless. Dropbox uses Rabin because at their scale, the bandwidth savings from better delta sync justify the implementation complexity."

### LWW vs Keep-Both vs Three-Way Merge

| Aspect | Last-Writer-Wins | Keep-Both | Three-Way Merge |
|--------|-----------------|-----------|-----------------|
| Data loss risk | YES (overwrites concurrent edits) | NONE (both preserved) | Minimal (auto-merge non-conflicting) |
| User action needed | None (automatic) | Manual merge | Only for true conflicts |
| Folder clutter | None | YES (conflict copies accumulate) | None |
| Implementation | Simple (timestamp compare) | Simple (create new file) | Complex (chunk-level diff + merge) |
| Binary files | Works (but lossy) | Works (safe) | Cannot merge (fallback to keep-both) |
| Used by | Google Drive | Dropbox | Git, some enterprise systems |

**Say:** "Keep-both is the safest default -- no data loss, and users can manually resolve. But it creates folder clutter that most users ignore. Three-way merge at the chunk level is the ideal: non-conflicting changes auto-merge (Device A edited slides 1-3, Device B edited slides 4-6 -> merge perfectly), and only truly conflicting chunks (both edited slide 3) create a conflict notification. I'd implement keep-both first for safety, then add three-way merge as an optimization. LWW is only appropriate for single-user multi-device scenarios where the 'other device' is always the same person."

### S3 vs Custom Storage (Magic Pocket)

| Aspect | S3 (Managed) | Custom Storage (Dropbox Magic Pocket) |
|--------|-------------|--------------------------------------|
| Durability | 11 nines (99.999999999%) | 11 nines (erasure coding on own hardware) |
| Cost at 1 PB | ~$23K/month (Standard) | ~$50K/month (amortized HW + ops + DC) |
| Cost at 100 PB | ~$2.3M/month | ~$1.2M/month (50% cheaper at this scale) |
| Engineering cost | Zero (managed) | $50M+ to build (3-year Dropbox project) |
| Scaling effort | Zero (S3 auto-scales) | Significant (capacity planning, hardware procurement) |
| Crossover point | Cheaper below ~50 PB | Cheaper above ~50 PB |
| Who uses this | Everyone else | Dropbox, Facebook (f4), Google (Colossus) |

**Say:** "Use S3 until you're spending $10M+/year on storage. Below that, the engineering cost of building custom storage is higher than the S3 bill. Above 50 PB, the math flips: Dropbox saves ~$120M/year by running Magic Pocket instead of S3. But only 5-10 companies in the world have enough storage to justify custom block storage. This is a great example of build vs buy: buy until the buy cost exceeds the build cost, including ongoing maintenance."

### CP vs AP: Where Each Applies

| Component | CAP Choice | Why |
|-----------|-----------|-----|
| File metadata (tree, names) | **CP** | Deleted file must not appear in listings. Moved file must appear in new folder. Consistency prevents broken UX. |
| Chunk storage (S3) | **CP** | Chunk content must be durable and correct. Corrupted chunk = corrupted file. S3 provides strong read-after-write consistency. |
| Dedup index | **CP** | ref_count must be accurate. Under-count = premature chunk deletion (data loss). Over-count = orphaned chunks (wasted storage, but safe). |
| Version history | **CP** | Version N must always return the same chunk list. Inconsistent versions confuse users and break rollback. |
| Sync notifications | **AP** | 2-second delay in sync notification is invisible. Device catches up via cursor on next poll. Availability > consistency. |
| Folder listings cache | **AP** | Stale listing for a few seconds is acceptable. Cache invalidation is best-effort. Source of truth is RDS. |
| Thumbnail cache | **AP** | Serving a stale thumbnail briefly is fine. Thumbnails are regenerable. CDN eventually refreshes. |

**Say:** "The pattern is: CP for anything where inconsistency causes data loss or broken operations (metadata, chunks, dedup index, versions), and AP for anything where a brief delay is invisible to the user (sync notifications, caches, thumbnails). The dedup index is an interesting edge case: if ref_count is inconsistent, an under-count during deletion could orphan or prematurely delete a chunk. We handle this with a 24-hour grace period before garbage collection -- if a concurrent upload increments ref_count within 24 hours, the chunk is saved."

---

## Red Flags (What NOT to Do)

- No chunking -- "upload the entire file to S3 as one object" breaks resume, kills delta sync, prevents dedup
- No deduplication -- "store every upload separately" wastes 30-50% storage at scale (petabytes wasted)
- Polling for sync -- "client checks for changes every 5 seconds" wastes bandwidth and adds latency (5s avg delay vs instant long-poll)
- Upload through API server -- "stream file data through ECS to S3" makes API server a bottleneck, doubles network transfer
- Full file download on change -- "when file changes, re-download entire file" wastes bandwidth (vs 4MB delta)
- No versioning -- "overwrite the file in S3" loses history, breaks rollback, prevents conflict detection
- Single DB for everything -- "put file tree AND chunk index AND versions in one MySQL" creates performance bottleneck (relational queries compete with high-throughput chunk lookups)
- Ignoring conflicts -- "just accept the latest upload" is LWW without even preserving the overwritten version in history

## Green Flags (What Interviewers Want to Hear)

- Lead with chunking + dedup: "Split into 4MB chunks, hash with SHA-256, use hash as storage key. Same content stored once."
- Content-addressable storage: "The hash IS the address. Like Git's object store."
- Delta sync with numbers: "Edit 100MB file, change 1 slide -> upload 4MB, not 100MB. 96% savings."
- Presigned URLs: "Client uploads directly to S3, bypassing API server. API handles only metadata."
- Version history via chunk lists: "Each version = list of chunk hashes. Shared chunks = copy-on-write. 30 versions cost 2x, not 30x."
- Cursor-based sync: "Long poll with opaque cursor. 'Give me changes since cursor X.' Instant notification, efficient catch-up."
- Conflict strategies by name: "LWW for simplicity (Google Drive), keep-both for safety (Dropbox), three-way merge for smart resolution."
- Rabin fingerprint: "Content-defined chunking for better dedup on edited files. Insertion doesn't shift all boundaries."
- Reference counting: "Chunk deleted only when ref_count=0. Shared across files, users, and versions."
- Real numbers: "Dropbox: 2+ EB storage, 1.2B files synced/day, 40% dedup ratio."

---

## 30-Second Elevator Pitch

> "For a file storage system like Google Drive, I'd split files into **4MB chunks**, hash each with **SHA-256**, and use the hash as the storage key in **S3** -- content-addressable storage. This gives us **deduplication for free**: same content = same hash = stored once, saving **30-50% storage**. Before uploading, the client sends chunk hashes for a **dedup check**; the server returns **presigned URLs** for only missing chunks, which upload **directly to S3** in parallel. **Delta sync**: when a file changes, only the modified chunks transfer -- editing a 100MB file uploads **4MB, not 100MB**. **Versioning** is a list of chunk hashes; unchanged chunks are shared between versions (**copy-on-write**). **Sync** uses **long polling** with a **cursor**: devices hold open connections, the server pushes instantly on changes, devices request deltas since their last cursor. **Conflict resolution**: **keep-both** as default (Dropbox -- create conflict copy, no data loss), with **LWW** option (Google Drive -- simpler but risks overwrite). At Dropbox scale (**2+ exabytes**), you'd replace S3 with custom block storage (**Magic Pocket** -- erasure coding, 50% cheaper at exabyte scale). System is **CP for metadata** (file tree must be consistent) and **AP for sync** (2-second notification delay is invisible)."

**Time: Under 30 seconds. Covers: chunking, dedup, presigned URLs, delta sync, versioning, cursor-based sync, conflict resolution, scaling, CAP trade-off.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements              2-3 min   (scale, file types, sync latency, conflict policy)
Phase 2:  High-Level Architecture            5-7 min   (metadata vs block storage, S3, dedup check, presigned URLs, sync)
Phase 3:  Deduplication Deep Dive            8-10 min  (content-addressable, SHA-256, Rabin fingerprint, ref counting, GC)
Phase 4:  Sync & Conflict Resolution         5-7 min   (cursor-based delta sync, long polling, LWW vs keep-both vs 3-way merge)
Phase 5:  Scaling & Edge Cases               5-8 min   (2.5 EB, Magic Pocket, large files, offline sync, selective sync)
Phase 6:  Tradeoffs Discussion               3-5 min   (fixed vs Rabin, LWW vs keep-both, S3 vs custom, CP vs AP)
-----------------------------------------------------------------------------------
Total:                                       ~35 min
```

If short on time, shorten Phase 5 (scaling/edge cases) and Phase 6 (tradeoffs). Never skip Phase 3 (deduplication deep dive) -- that IS the interview for this problem and what differentiates a senior answer from a generic one. Phase 4 (sync + conflict resolution) is the second priority -- it shows you understand the full product beyond just storage.
