# Technologies -- File Storage System (Google Drive / Dropbox)

> Production technology stack for a cloud file storage platform.
> For each tech: why it fits, key operations, data model, complexity analysis,
> and how our Java implementation maps to the production version.
>
> **Domain-specific:** File storage has unique tech requirements --
> content-addressable object storage for dedup, chunking algorithms for
> efficient delta sync, WebSocket/long-polling for real-time notifications,
> and CDN for popular file distribution. This doc covers all of them.

---

## Technology Map

```
  +-------------------+     +-------------------+     +------------------------------+
  |   Client App      |---->|   API Gateway     |---->|   FileStorageService         |
  |   (Desktop/Mobile)|     |   (Load Balancer) |     |   (Facade)                   |
  +-------------------+     +-------------------+     +------------------------------+
        |                         |                       |       |       |       |
        | Sync events             |            +----------+--+    |    +--+---+   |
        | (WebSocket)             |            |             |    |    |      |   |
        v                         v            v             v    v    v      v   v
  +-----------+             +----------+ +----------+ +------+ +-----+ +--------+ +-------+
  | Local     |             | Redis    | | Kafka    | |Postgr| |Redis| |S3      | |Elastic|
  | File      |             | Sync     | | File     | |eSQL  | |Meta | |Block   | |search |
  | Cache     |             | Cursor   | | Events   | |File  | |Cache| |Storage | |File   |
  | (SSD)     |             | Per-user | | Async    | |Meta  | |Path | |Content | |Search |
  +-----------+             +----------+ +----------+ |data  | |Lookup| |Addr.  | +-------+
                                  |            |      +------+ +-----+ +--------+
                                  |            v                          |
                                  |      +-----------+              +----------+
                                  |      | Thumbnail |              | CDN      |
                                  |      | Generator |              | Popular  |
                                  |      | (async)   |              | Files    |
                                  |      +-----------+              +----------+
                                  v
                            +-----------+
                            | Bloom     |
                            | Filter    |
                            | Dedup     |
                            | Cache     |
                            +-----------+
```

---

## 1. Object Storage -- S3 / GCS / Azure Blob

### Content-Addressable Storage

The core insight: chunks are stored by their **content hash**, not by filename.
Two files with the same chunk content map to the same block.

| Property | Detail |
|----------|--------|
| Service | AWS S3, Google Cloud Storage (GCS), Azure Blob Storage |
| Key scheme | `chunks/{sha256-hash}` (content-addressable) |
| Durability | 99.999999999% (11 nines) for S3 Standard |
| Availability | 99.99% for S3 Standard |
| Consistency | Strong read-after-write (S3, as of Dec 2020) |
| Pricing | ~$0.023/GB/month (S3 Standard), ~$0.004/GB/month (S3 Glacier) |
| Max object size | 5 TB (S3), 5 TB (GCS), 4.75 TB (Azure) |

### Key Operations

```
  PUT chunk (content-addressable):
    Key:  chunks/sha256:a1b2c3d4e5f6...
    Body: [4 MB of chunk bytes]
    Headers: Content-Type: application/octet-stream
             x-amz-storage-class: STANDARD

  GET chunk:
    Key:  chunks/sha256:a1b2c3d4e5f6...
    Response: [4 MB of chunk bytes]
    Latency: 50-200ms first byte (S3)

  DELETE chunk (after GC):
    Key:  chunks/sha256:a1b2c3d4e5f6...
    (Only after ref-count = 0 AND 72-hour grace period)

  HEAD chunk (existence check):
    Key:  chunks/sha256:a1b2c3d4e5f6...
    Response: 200 OK (exists) or 404 (not found)
    Use: Verify dedup index is correct
```

### Storage Tiers

```
  +--------------------+    +--------------------+    +--------------------+
  | S3 Standard        |    | S3 Infrequent      |    | S3 Glacier         |
  | (hot storage)      |    | Access (warm)       |    | (cold archive)     |
  |                    |    |                     |    |                    |
  | Active files       |    | Files not accessed  |    | Files not accessed |
  | accessed in last   |    | in 30-90 days       |    | in 90+ days        |
  | 30 days            |    |                     |    |                    |
  |                    |    | 45% cheaper than    |    | 82% cheaper than   |
  | $0.023/GB/month    |    | Standard            |    | Standard           |
  +--------------------+    | $0.0125/GB/month    |    | $0.004/GB/month    |
                            +--------------------+    +--------------------+
                                                           |
                                                      Retrieval time:
                                                      1-5 minutes (expedited)
                                                      3-5 hours (standard)
                                                      5-12 hours (bulk)

  Lifecycle policy:
  1. Upload -> S3 Standard
  2. After 30 days no access -> move to Infrequent Access
  3. After 90 days no access -> move to Glacier
  4. On access: move back to Standard (async, warm-up delay)
```

### Our Java Implementation vs Production

| Our Implementation | Production |
|-------------------|------------|
| `InMemoryBlockStorageService` (ConcurrentHashMap) | AWS S3 / GCS / Azure Blob |
| `store(chunk)` -> HashMap.put(hash, bytes) | S3 PutObject with SHA-256 key |
| `retrieve(ref)` -> HashMap.get(hash) | S3 GetObject with range requests |
| No durability (in-memory) | 11 nines durability, cross-AZ replication |
| No storage tiers | Standard / IA / Glacier lifecycle |
| No encryption | SSE-S3 or SSE-KMS server-side encryption |

---

## 2. Metadata Database -- PostgreSQL

### Why PostgreSQL?

File metadata is **relational**: files belong to folders, folders have parents,
versions belong to files, permissions link users to files. Relational integrity
(foreign keys, transactions) prevents the structural corruption described in
CAP_THEOREM.md.

| Property | Detail |
|----------|--------|
| Engine | PostgreSQL 16 |
| Model | Relational with foreign keys |
| Consistency | ACID transactions, strong consistency |
| Replication | Synchronous streaming replication (1 leader + N followers) |
| Scale | Vertical (single leader handles metadata -- not as write-heavy as S3) |

### Schema (Core Tables)

```sql
-- Files table (core metadata)
CREATE TABLE files (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    path        VARCHAR(4096) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    folder_id   UUID REFERENCES folders(id) ON DELETE CASCADE,
    owner_id    UUID REFERENCES users(id) NOT NULL,
    size_bytes  BIGINT NOT NULL,
    checksum    VARCHAR(128) NOT NULL,
    mime_type   VARCHAR(255) DEFAULT 'application/octet-stream',
    is_deleted  BOOLEAN DEFAULT false,   -- soft delete
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    modified_at TIMESTAMPTZ DEFAULT NOW()
);

-- Folders table (tree structure)
CREATE TABLE folders (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    path        VARCHAR(4096) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    parent_id   UUID REFERENCES folders(id) ON DELETE CASCADE,
    owner_id    UUID REFERENCES users(id) NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    modified_at TIMESTAMPTZ DEFAULT NOW()
);

-- File-to-chunk mapping (ordered)
CREATE TABLE file_chunks (
    file_id     UUID REFERENCES files(id) ON DELETE CASCADE,
    chunk_hash  VARCHAR(128) NOT NULL,
    position    INTEGER NOT NULL,          -- chunk order within file
    chunk_size  BIGINT NOT NULL,
    PRIMARY KEY (file_id, position)
);

-- Chunk reference counts (for GC)
CREATE TABLE chunk_refs (
    chunk_hash  VARCHAR(128) PRIMARY KEY,
    ref_count   INTEGER NOT NULL DEFAULT 1,
    total_size  BIGINT NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- File versions (Memento pattern)
CREATE TABLE file_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id         UUID REFERENCES files(id) ON DELETE CASCADE,
    version_number  INTEGER NOT NULL,
    checksum        VARCHAR(128) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    modified_by     UUID REFERENCES users(id),
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (file_id, version_number)
);

-- Version-to-chunk mapping
CREATE TABLE version_chunks (
    version_id  UUID REFERENCES file_versions(id) ON DELETE CASCADE,
    chunk_hash  VARCHAR(128) NOT NULL,
    position    INTEGER NOT NULL,
    PRIMARY KEY (version_id, position)
);

-- Users table
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL UNIQUE,
    display_name    VARCHAR(255) NOT NULL,
    quota_bytes     BIGINT DEFAULT 15737418240,  -- 15 GB default
    used_bytes      BIGINT DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Sharing permissions
CREATE TABLE shares (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id     UUID REFERENCES files(id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    permission  VARCHAR(20) NOT NULL,  -- 'VIEW', 'EDIT', 'OWNER'
    shared_by   UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (file_id, user_id)
);

-- Indexes
CREATE INDEX idx_files_folder ON files(folder_id);
CREATE INDEX idx_files_owner ON files(owner_id);
CREATE INDEX idx_files_path ON files(path);
CREATE INDEX idx_folders_parent ON folders(parent_id);
CREATE INDEX idx_folders_path ON folders(path);
CREATE INDEX idx_versions_file ON file_versions(file_id, version_number);
CREATE INDEX idx_shares_user ON shares(user_id);
CREATE INDEX idx_shares_file ON shares(file_id);
```

### Key Queries

```sql
-- List folder contents (Composite pattern)
SELECT f.id, f.name, f.size_bytes, 'FILE' as type, f.modified_at
FROM files f WHERE f.folder_id = $1 AND f.is_deleted = false
UNION ALL
SELECT d.id, d.name, 0 as size_bytes, 'FOLDER' as type, d.modified_at
FROM folders d WHERE d.parent_id = $1
ORDER BY type DESC, name ASC;

-- Get file with chunks (for download)
SELECT f.*, fc.chunk_hash, fc.position, fc.chunk_size
FROM files f
JOIN file_chunks fc ON f.id = fc.file_id
WHERE f.id = $1
ORDER BY fc.position;

-- Increment ref count (dedup -- new file references existing chunk)
UPDATE chunk_refs SET ref_count = ref_count + 1
WHERE chunk_hash = $1;

-- Decrement ref count (file deleted)
UPDATE chunk_refs SET ref_count = ref_count - 1
WHERE chunk_hash = $1;

-- Find GC candidates (ref_count = 0, older than 72 hours)
SELECT chunk_hash FROM chunk_refs
WHERE ref_count <= 0
AND created_at < NOW() - INTERVAL '72 hours';

-- Get user's storage usage
SELECT COALESCE(SUM(f.size_bytes), 0) as used_bytes
FROM files f WHERE f.owner_id = $1 AND f.is_deleted = false;

-- Version history
SELECT * FROM file_versions
WHERE file_id = $1
ORDER BY version_number DESC;
```

### Our Java Implementation vs Production

| Our Implementation | Production |
|-------------------|------------|
| `InMemoryFileRepository` (ConcurrentHashMap) | PostgreSQL with schema above |
| `InMemoryFolderRepository` (ConcurrentHashMap) | PostgreSQL with tree queries |
| `InMemoryVersionRepository` (ArrayList) | PostgreSQL with version_chunks join |
| No transactions | ACID transactions for metadata + chunks + ref-counts |
| No foreign keys | FK constraints prevent orphan files |
| No indexes | B-tree indexes on path, folder_id, owner_id |

---

## 3. Sync Infrastructure -- WebSocket + Kafka

### WebSocket (Real-time Push)

For immediate notification when a file changes on another device.

| Property | Detail |
|----------|--------|
| Protocol | WebSocket (RFC 6455) over TLS |
| Use | Push file-change events to connected devices |
| Connection | One persistent connection per device per user |
| Heartbeat | Ping every 30 seconds to detect disconnects |
| Fallback | Long-polling if WebSocket not supported |
| Scale | ~50K connections per server (with epoll/kqueue) |

### WebSocket Message Types

```json
// Server -> Client: File changed
{
    "type": "FILE_CHANGED",
    "fileId": "file-42",
    "path": "/docs/report.pdf",
    "version": 5,
    "action": "MODIFIED",
    "modifiedBy": "alice",
    "timestamp": 1745625600000,
    "changedChunks": [2]  // Only chunk at position 2 changed
}

// Server -> Client: Folder changed
{
    "type": "FOLDER_CHANGED",
    "folderId": "folder-7",
    "path": "/docs/",
    "action": "CHILD_ADDED",
    "childName": "budget.xlsx",
    "timestamp": 1745625660000
}

// Client -> Server: Sync cursor update
{
    "type": "SYNC_CURSOR",
    "deviceId": "laptop-abc",
    "lastSyncedVersion": 42
}
```

### Kafka (Async Event Processing)

For durable, ordered event processing of file changes -- consumed by
search indexers, thumbnail generators, virus scanners, and analytics.

| Property | Detail |
|----------|--------|
| Topic | `file-events` (partitioned by userId for ordering) |
| Partitions | 64 (scale with consumer groups) |
| Retention | 7 days (events replayed on consumer restart) |
| Key | userId (ensures per-user ordering) |
| Value | JSON event (same schema as WebSocket messages) |

### Kafka Event Flow

```
  File uploaded
       |
       v
  FileStorageService publishes to Kafka "file-events" topic
       |
       +------> Consumer Group 1: SyncService
       |         - Pushes WebSocket to connected devices
       |
       +------> Consumer Group 2: SearchIndexer
       |         - Updates Elasticsearch index with file name + metadata
       |
       +------> Consumer Group 3: ThumbnailGenerator
       |         - Generates preview thumbnails for images/videos/PDFs
       |
       +------> Consumer Group 4: VirusScanner
       |         - Scans uploaded content for malware
       |
       +------> Consumer Group 5: AnalyticsCollector
                 - Tracks upload/download patterns, storage growth
```

### Long Polling (Fallback)

When WebSocket is not available (corporate firewalls, older clients):

```
  Long-polling flow:
  1. Client: GET /api/sync/poll?cursor=42&timeout=30
  2. Server holds connection open for up to 30 seconds
  3. If changes arrive: respond immediately with changes + new cursor
  4. If no changes in 30 seconds: respond with empty result + same cursor
  5. Client immediately sends next poll request

  Compared to regular polling:
  - Regular polling: 2 requests/minute, 99% return empty
  - Long polling: ~1 request/minute (held open), nearly 0% empty responses
  - WebSocket: 0 requests (server pushes), lowest latency
```

### Delta Sync (rsync-like)

When a file changes, only the modified chunks are transferred:

```
  File "report.pdf" at version 4:
  Chunks: [hash-A, hash-B, hash-C, hash-D, hash-E]  (5 chunks, 20 MB)

  User edits page 3 (in chunk C area), saves as version 5:
  Chunks: [hash-A, hash-B, hash-C', hash-D, hash-E]  (chunk C changed)

  Delta sync:
  1. Server compares version 4 chunks with version 5 chunks
  2. hash-A: same -> skip
  3. hash-B: same -> skip
  4. hash-C vs hash-C': DIFFERENT -> transfer hash-C' (4 MB)
  5. hash-D: same -> skip
  6. hash-E: same -> skip

  Transfer: 4 MB instead of 20 MB (80% savings)

  This is why content-defined chunking matters:
  - Fixed-size: inserting 1 byte shifts ALL subsequent chunk boundaries
    -> every chunk after the edit looks "changed" -> re-upload everything
  - Content-defined (Rabin): boundaries are based on content patterns
    -> inserting 1 byte only changes 1-2 chunks -> minimal re-upload
```

### Our Java Implementation vs Production

| Our Implementation | Production |
|-------------------|------------|
| `SyncService` with in-memory listeners | WebSocket server (Netty/Undertow) + Kafka |
| `SyncListener` interface | WebSocket connections + Kafka consumers |
| Direct method call notification | Kafka publish -> consumer groups |
| No long-polling fallback | Long-polling for legacy clients |
| No delta sync | Delta sync comparing chunk lists per version |

---

## 4. Dedup Technologies -- SHA-256 + Rabin Fingerprint

### SHA-256 (Chunk Hashing)

| Property | Detail |
|----------|--------|
| Algorithm | SHA-256 (Secure Hash Algorithm, 256-bit) |
| Output | 32 bytes (256 bits), hex-encoded = 64 characters |
| Collision probability | ~1 in 2^128 (birthday paradox) -- effectively zero |
| Speed | ~500 MB/s on modern CPU (with SHA-NI instructions) |
| Use | Content-addressable key for chunk storage |

```
  SHA-256 dedup flow:

  1. ChunkingStrategy splits file into chunks
  2. For each chunk: hash = SHA-256(chunk_bytes)
  3. Lookup hash in dedup index (Redis Bloom filter + PostgreSQL)
  4. If found: reuse existing block (ZERO bytes to S3)
  5. If not found: store chunk in S3 with key = hash

  Content-addressable guarantee:
  - Same bytes -> same hash -> same block
  - Different bytes -> different hash -> different block
  - Collision (different bytes, same hash): 1 in 2^128 chance
    (more likely to be hit by a meteor while winning the lottery)
```

### Rabin Fingerprint (Content-Defined Chunking)

| Property | Detail |
|----------|--------|
| Algorithm | Rabin-Karp rolling hash over a sliding window |
| Window size | 48-64 bytes (sliding window for fingerprint) |
| Chunk boundary | When fingerprint mod M == 0 (M controls avg chunk size) |
| Average chunk size | Configurable (typically 1-8 MB for file storage) |
| Min chunk size | M/4 (prevents tiny chunks) |
| Max chunk size | M*4 (prevents huge chunks) |

```
  Content-defined chunking with Rabin fingerprint:

  File bytes: [...........|...........|.........|..............|...]
                          ^           ^         ^              ^
                     boundary 1  boundary 2  boundary 3   boundary 4

  How boundaries are found:
  1. Slide a 48-byte window across the file, one byte at a time
  2. Compute Rabin fingerprint of the window (O(1) rolling update)
  3. If fingerprint % 4194304 == 0: this is a chunk boundary
     (4194304 = 4 MB, so avg chunk size = 4 MB)
  4. Enforce min (1 MB) and max (16 MB) chunk sizes

  Why this matters for delta sync:
  +---------------------------------------------------------------+
  |                    FIXED-SIZE CHUNKING                         |
  |                                                                |
  |  Original:  [chunk-1  ][chunk-2  ][chunk-3  ][chunk-4  ]      |
  |  Insert 1 byte at start:                                      |
  |  Modified:  [chunk-1' ][chunk-2' ][chunk-3' ][chunk-4' ]      |
  |             ^ ALL chunks shifted, ALL look different           |
  |  Delta sync: re-upload ALL 4 chunks (0% savings)              |
  +---------------------------------------------------------------+

  +---------------------------------------------------------------+
  |                 CONTENT-DEFINED CHUNKING                       |
  |                                                                |
  |  Original:  [chunk-A    ][chunk-B  ][chunk-C      ][chunk-D ] |
  |  Insert 1 byte at start:                                      |
  |  Modified:  [chunk-A'   ][chunk-B  ][chunk-C      ][chunk-D ] |
  |             ^ Only chunk-A changed (boundary is content-based) |
  |  Delta sync: re-upload ONLY chunk-A' (75% savings)            |
  +---------------------------------------------------------------+
```

### Rolling Hash Implementation

```java
// Rabin fingerprint rolling hash (simplified)
public class RabinFingerprint {

    private static final long PRIME = 31;
    private static final long MOD = (1L << 31) - 1;  // Mersenne prime
    private final int windowSize;
    private long fingerprint = 0;
    private long leadCoeff;  // PRIME^windowSize mod MOD

    public RabinFingerprint(int windowSize) {
        this.windowSize = windowSize;
        this.leadCoeff = modPow(PRIME, windowSize, MOD);
    }

    public void slide(byte outgoing, byte incoming) {
        // O(1) update: remove outgoing byte, add incoming byte
        fingerprint = ((fingerprint - outgoing * leadCoeff) * PRIME + incoming) % MOD;
        if (fingerprint < 0) fingerprint += MOD;
    }

    public boolean isBoundary(long avgChunkSize) {
        return fingerprint % avgChunkSize == 0;
    }
}
```

### Our Java Implementation vs Production

| Our Implementation | Production |
|-------------------|------------|
| `FixedSizeChunkingStrategy` | Content-defined chunking (Rabin) |
| `ContentDefinedChunkingStrategy` (simplified) | Optimized Rabin with SIMD, min/max bounds |
| SHA-256 via `MessageDigest` | Hardware-accelerated SHA-NI |
| In-memory dedup index (HashMap) | Redis Bloom filter + PostgreSQL |
| No collision handling | Collision detection + fallback (byte compare) |

---

## 5. CDN -- Content Delivery Network

### When CDN Helps

CDN caches popular files at edge locations near users. Useful for:

| Use Case | Why CDN? |
|----------|----------|
| Shared files with many viewers | 1000 people download same file -> CDN serves 999 from edge |
| Public share links | Viral shared file -> CDN absorbs traffic spike |
| Company-wide files (employee handbook) | Every employee downloads from nearest edge |
| Thumbnail/preview serving | Small, frequently accessed, highly cacheable |

### CDN Architecture

```
  User in Tokyo                User in NYC              User in London
       |                            |                        |
       v                            v                        v
  +----------+                +----------+             +----------+
  | CDN Edge |                | CDN Edge |             | CDN Edge |
  | Tokyo    |                | NYC      |             | London   |
  | (cache)  |                | (cache)  |             | (cache)  |
  +----------+                +----------+             +----------+
       |  miss                      |  miss                  |  miss
       +----------------------------+-----------+            |
                                                |            |
                                                v            v
                                         +-------------+
                                         | S3 Origin   |
                                         | us-east-1   |
                                         +-------------+

  Cache key: sha256-hash of chunk (content-addressable!)
  TTL: 30 days (chunks are immutable -- hash changes if content changes)
  Invalidation: never needed (content-addressable = immutable)
```

### CDN Configuration

| Setting | Value | Rationale |
|---------|-------|-----------|
| Cache key | SHA-256 chunk hash | Content-addressable -> immutable -> never stale |
| TTL | 30 days | Chunks never change; new content = new hash |
| Origin | S3 bucket | Single source of truth for chunks |
| Compression | Disabled for chunks | Already binary; compression wastes CPU |
| Range requests | Enabled | Client can resume partial downloads |
| HTTPS only | Yes | File content must be encrypted in transit |
| Signed URLs | Yes | Prevent unauthorized access to private files |

### Our Java Implementation vs Production

| Our Implementation | Production |
|-------------------|------------|
| No CDN | CloudFront / Cloud CDN / Akamai |
| Direct block storage read | CDN edge -> S3 origin on miss |
| No signed URLs | Pre-signed URLs with expiry for private files |
| No geo-distribution | Edge locations in 200+ cities worldwide |

---

## 6. Search -- Elasticsearch

### File Search Requirements

| Search Type | Example | Implementation |
|-------------|---------|---------------|
| File name search | "budget 2026" | Elasticsearch full-text on file name |
| Path search | "/finance/reports" | Elasticsearch prefix query on path |
| Content search | "Q4 revenue" | Elasticsearch full-text on extracted content |
| Metadata filter | "files > 10 MB owned by alice" | Elasticsearch range + term queries |
| Recent files | "Modified in last 7 days" | Elasticsearch date range query |

### Elasticsearch Index Schema

```json
{
    "mappings": {
        "properties": {
            "fileId":     { "type": "keyword" },
            "path":       { "type": "text", "analyzer": "path_analyzer" },
            "name":       { "type": "text", "analyzer": "standard",
                           "fields": { "keyword": { "type": "keyword" } } },
            "ownerId":    { "type": "keyword" },
            "mimeType":   { "type": "keyword" },
            "sizeBytes":  { "type": "long" },
            "content":    { "type": "text", "analyzer": "standard" },
            "tags":       { "type": "keyword" },
            "sharedWith": { "type": "keyword" },
            "createdAt":  { "type": "date" },
            "modifiedAt": { "type": "date" }
        }
    }
}
```

### Indexing Pipeline

```
  File uploaded
       |
       v
  Kafka event: FILE_CHANGED
       |
       v
  SearchIndexer (Kafka consumer)
       |
       +---> Extract metadata (name, path, size, mime, owner)
       |
       +---> Extract content (if applicable):
       |       PDF -> Apache Tika -> plain text
       |       DOCX -> Apache Tika -> plain text
       |       Image -> OCR (Tesseract) -> text (optional)
       |       Video -> skip content extraction
       |
       +---> Index to Elasticsearch
              PUT /files/_doc/{fileId}
              { "name": "budget.xlsx", "path": "/finance/...", ... }
```

### Our Java Implementation vs Production

| Our Implementation | Production |
|-------------------|------------|
| No search | Elasticsearch 8.x cluster |
| Linear scan of file names | Full-text search with analyzers |
| No content extraction | Apache Tika for PDF/DOCX content |
| No ranking | TF-IDF / BM25 relevance ranking |

---

## 7. Caching Layer -- Redis

Covered in detail in `CACHING_STRATEGY.md`. Summary of Redis uses:

| Cache | Redis Data Structure | Key Pattern | TTL |
|-------|---------------------|-------------|-----|
| Metadata cache | String (JSON) | `meta:{fileId}` | 5 min |
| Path lookup | String | `path:{path}` -> fileId | 5 min |
| Folder listing | Sorted Set | `folder:{folderId}` | 2 min |
| Sync cursor | String | `cursor:{userId}:{deviceId}` | 24 hours |
| Share link | String (JSON) | `share:{linkId}` | matches link expiry |
| Dedup Bloom filter | Bloom filter (RedisBloom) | `dedup:bloom` | never (rebuilt periodically) |
| Quota cache | String | `quota:{userId}` | 10 min |

---

## 8. Virus Scanning

### Why Virus Scan?

Users upload arbitrary files. Without scanning, the storage system becomes
a malware distribution platform.

| Property | Detail |
|----------|--------|
| Scan engine | ClamAV (open source) or commercial (Sophos, McAfee) |
| Trigger | Kafka event after file upload |
| Timing | Async -- file is available immediately, scan runs in background |
| On detection | Quarantine file (set is_quarantined flag), notify user |
| Scan target | Individual chunks (parallel scanning) |
| Rescan | Periodic rescan with updated virus definitions |

### Virus Scan Flow

```
  1. File uploaded -> chunks stored in S3
  2. Kafka event published: FILE_UPLOADED
  3. VirusScanConsumer picks up event
  4. Downloads chunks from S3 (or local SSD cache)
  5. Scans each chunk with ClamAV
  6. If clean: update file status to CLEAN
  7. If infected: quarantine file, notify user
  8. Quarantined files cannot be downloaded or shared

  Numbered call chain:
  1.  VirusScanConsumer receives Kafka event for "file-42"
  2.  Fetches FileMetadata from MetadataService
  3.  Downloads 3 chunks from BlockStorageService
  4.  Submits each chunk to ClamAV daemon (clamd)
  5.  Chunk 1: clean. Chunk 2: clean. Chunk 3: EICAR-TEST-FILE detected!
  6.  VirusScanConsumer calls MetadataService.quarantine("file-42")
  7.  MetadataService: UPDATE files SET is_quarantined = true WHERE id = ?
  8.  SyncService.notifyFileQuarantined("file-42", "alice")
  9.  Alice receives notification: "budget.xlsx was quarantined (malware detected)"
  10. File cannot be downloaded until manually reviewed or deleted
```

---

## Numbered Call Chain -- Full Upload Through Production Stack

```
1.  User selects "report.pdf" (10 MB) in desktop client
2.  Client computes SHA-256 of entire file: "filehash-xyz"
3.  Client: POST /api/files/check-exists { hash: "filehash-xyz" }
4.  Server checks FileRepository -> not found -> 404 (proceed with upload)
5.  Client splits file using local Rabin chunking (content-defined, 4 MB avg)
6.  Client: POST /api/files/upload/init { name: "report.pdf", chunks: 3 }
7.  Server returns upload session ID + pre-signed S3 URLs for each chunk
8.  Client uploads chunk 1 directly to S3 (bypasses API server)
9.  Client uploads chunk 2 directly to S3
10. Client uploads chunk 3 directly to S3
11. Client: POST /api/files/upload/complete { sessionId: "...", chunks: [...] }
12. Server verifies all chunks exist in S3 (HEAD requests)
13. Server runs dedup: checks SHA-256 of each chunk against dedup index
14. If duplicate: increment ref-count, do NOT re-store
15. Server creates FileMetadata (Builder) and saves to PostgreSQL (in transaction)
16. Server creates FileVersion (Memento) in same transaction
17. Server publishes Kafka event: FILE_UPLOADED
18. Kafka consumers: SyncService pushes WebSocket to other devices
19. Kafka consumers: SearchIndexer updates Elasticsearch
20. Kafka consumers: ThumbnailGenerator creates preview
21. Kafka consumers: VirusScanner scans chunks
22. CDN: future downloads served from edge (cache key = chunk hash)
```

---

## Technology Decision Matrix

| Decision | Options | We Chose | Why |
|----------|---------|----------|-----|
| Block storage | S3 / GCS / Azure Blob | S3 | 11 nines durability, content-addressable, lifecycle policies |
| Metadata DB | PostgreSQL / MySQL / DynamoDB | PostgreSQL | Relational integrity for file tree, ACID transactions |
| Sync transport | WebSocket / Long-poll / SSE | WebSocket + long-poll fallback | Lowest latency push, with fallback for corporate firewalls |
| Event bus | Kafka / RabbitMQ / SQS | Kafka | Ordered events per user, replay for consumer recovery, high throughput |
| Chunking | Fixed-size / Content-defined | Content-defined (Rabin) | 75%+ bandwidth savings on delta sync for edited files |
| Hashing | SHA-256 / SHA-1 / MD5 | SHA-256 | Collision-resistant, hardware-accelerated, industry standard |
| Search | Elasticsearch / Solr / DB LIKE | Elasticsearch | Full-text + content search, horizontal scaling |
| CDN | CloudFront / Cloud CDN / Akamai | CloudFront | Native S3 integration, signed URLs, global edge network |
| Cache | Redis / Memcached | Redis | Data structures (Sorted Sets, Bloom filter), pub/sub for invalidation |
| Virus scan | ClamAV / Sophos / McAfee | ClamAV | Open source, daemon mode, chunk-level scanning |

---

## Interview Cheat Sheet

**"What storage backend would you use?"**
> "S3 for block storage -- content-addressable with SHA-256 keys, 11 nines
> durability, lifecycle policies to move cold chunks to Glacier. PostgreSQL
> for metadata -- relational integrity for the file tree, ACID transactions
> for ref-count operations."

**"How does sync work?"**
> "WebSocket for real-time push to connected devices, Kafka for durable async
> event processing (search indexing, thumbnail generation, virus scanning).
> Long-polling as fallback for corporate firewalls. Delta sync transfers only
> changed chunks using content-defined chunking boundaries."

**"Why content-defined chunking over fixed-size?"**
> "Content-defined chunking (Rabin fingerprint) finds boundaries based on
> content patterns, not byte offsets. When you insert a byte at the start
> of a file, fixed-size chunking shifts every boundary (re-upload everything),
> but content-defined only changes 1-2 chunks (re-upload ~10%). This gives
> 75%+ bandwidth savings on delta sync."

**"How does your Java implementation differ from production?"**
> "Our implementation uses InMemory repositories and strategy interfaces to
> demonstrate the patterns. Production swaps in PostgreSQL, S3, Redis, Kafka,
> and Elasticsearch. The strategy interfaces are identical -- only the
> concrete implementations change, which is exactly the point of Strategy."
