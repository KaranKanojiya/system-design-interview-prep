# Caching Strategy -- File Storage System (Google Drive / Dropbox)

> Every cache layer in the system, from the metadata cache to the block cache,
> dedup Bloom filter, sync cursor cache, share link cache, CDN, and client-side
> cache. Interview-ready with Redis commands, TTL policies, invalidation
> strategies, and the full request flow through all cache layers.
>
> **Key insight:** Unlike most systems where caching is about read performance,
> in file storage **caching serves three distinct purposes**: (1) metadata
> read performance (folder listings, file lookups), (2) dedup write performance
> (Bloom filter for fast "probably not duplicate" checks), and (3) block read
> performance (local SSD cache before hitting S3). The Bloom filter is THE
> unique cache layer -- it makes dedup O(1) instead of O(disk-seek).

---

## Cache Layer Overview

```
  User opens folder -> GET /api/folders/{id}/contents
       |
       v
  +---------------------------------------------------+
  | Metadata Cache (Redis String)                      |  LAYER 1
  | Key: meta:{fileId} or path:{path}                  |
  | Cached file/folder metadata as JSON                |
  | TTL: 5 minutes, invalidated on write               |
  +---------------------------------------------------+
       | miss
       v
  +---------------------------------------------------+
  | PostgreSQL files/folders tables                    |
  | Source of truth for file tree metadata             |
  +---------------------------------------------------+

  User downloads file -> GET /api/files/{id}/download
       |
       v
  +---------------------------------------------------+
  | CDN Edge Cache (CloudFront)                        |  LAYER 2
  | Key: chunk SHA-256 hash (content-addressable)      |
  | Cached at 200+ edge locations worldwide            |
  | TTL: 30 days (immutable content)                   |
  +---------------------------------------------------+
       | miss
       v
  +---------------------------------------------------+
  | Block Cache (Local SSD)                            |  LAYER 3
  | Recently accessed chunks cached on API server SSD  |
  | LRU eviction, 100 GB per server                    |
  | TTL: 1 hour                                        |
  +---------------------------------------------------+
       | miss
       v
  +---------------------------------------------------+
  | S3 Block Storage (Origin)                          |
  | Content-addressable storage: chunks/{sha256-hash}  |
  | 11 nines durability, 50-200ms latency              |
  +---------------------------------------------------+

  User uploads file -> POST /api/files/upload
       |
       v
  +---------------------------------------------------+
  | Dedup Bloom Filter (Redis)                         |  LAYER 4
  | Key: dedup:bloom                                   |
  | Probabilistic: "definitely NOT a duplicate" or     |
  |                "MAYBE a duplicate (check DB)"      |
  | False positive rate: 1%                            |
  | Size: ~1.2 GB for 1 billion chunks                 |
  +---------------------------------------------------+
       | "maybe duplicate" (1% false positive)
       v
  +---------------------------------------------------+
  | PostgreSQL chunk_refs table                        |
  | Authoritative dedup index: chunk_hash -> ref_count |
  +---------------------------------------------------+

  Device connects -> WebSocket sync
       |
       v
  +---------------------------------------------------+
  | Sync Cursor Cache (Redis String)                   |  LAYER 5
  | Key: cursor:{userId}:{deviceId}                    |
  | Last-synced version number per device              |
  | TTL: 24 hours                                      |
  +---------------------------------------------------+

  User shares link -> GET /api/shares/{linkId}
       |
       v
  +---------------------------------------------------+
  | Share Link Cache (Redis String)                    |  LAYER 6
  | Key: share:{linkId}                                |
  | Cached share permissions and file reference        |
  | TTL: matches link expiry (1 hour to 30 days)       |
  +---------------------------------------------------+

  Folder listing cache
       |
       v
  +---------------------------------------------------+
  | Folder Listing Cache (Redis Sorted Set)            |  LAYER 7
  | Key: folder:{folderId}                             |
  | Sorted by name, contains file/folder IDs + names   |
  | TTL: 2 minutes, invalidated on child add/remove    |
  +---------------------------------------------------+
```

---

## Layer 1: Metadata Cache (Redis)

### Purpose

Cache file and folder metadata to avoid hitting PostgreSQL on every
folder listing, file info request, or path resolution.

### Redis Commands

```redis
-- Cache file metadata after DB read
SET meta:file-42 '{"id":"file-42","path":"/docs/report.pdf","size":10485760,...}' EX 300

-- Lookup by file ID
GET meta:file-42
  -> '{"id":"file-42","path":"/docs/report.pdf",...}'  (HIT)
  -> (nil)                                              (MISS -> query PostgreSQL)

-- Cache path -> fileId mapping
SET path:/docs/report.pdf file-42 EX 300

-- Lookup by path
GET path:/docs/report.pdf
  -> "file-42"  (HIT -> then GET meta:file-42)
  -> (nil)      (MISS -> query PostgreSQL by path)

-- Invalidation on write (file updated or deleted)
DEL meta:file-42
DEL path:/docs/report.pdf
```

### Invalidation Strategy

| Operation | Invalidation Action |
|-----------|-------------------|
| File uploaded | SET meta:{id}, SET path:{path} (populate cache) |
| File modified | DEL meta:{id}, DEL path:{path} (invalidate, re-populate on next read) |
| File deleted | DEL meta:{id}, DEL path:{path} |
| File moved/renamed | DEL meta:{id}, DEL path:{oldPath}, SET path:{newPath} |
| File permissions changed | DEL meta:{id} (permissions are part of metadata JSON) |

### Numbered Call Chain -- Metadata Cache Hit

```
1.  User opens file info for "report.pdf" (fileId = "file-42")
2.  API server calls MetadataService.getFile("file-42")
3.  MetadataService calls Redis: GET meta:file-42
4.  Redis returns: '{"id":"file-42","path":"/docs/report.pdf","size":10485760,...}'
5.  CACHE HIT -- deserialize JSON to FileMetadata object
6.  Return to API server, skip PostgreSQL entirely
7.  Latency: ~1ms (Redis) vs ~5ms (PostgreSQL)
```

### Numbered Call Chain -- Metadata Cache Miss

```
1.  User opens file info for "budget.xlsx" (fileId = "file-99")
2.  API server calls MetadataService.getFile("file-99")
3.  MetadataService calls Redis: GET meta:file-99
4.  Redis returns: (nil) -- CACHE MISS
5.  MetadataService queries PostgreSQL: SELECT * FROM files WHERE id = 'file-99'
6.  PostgreSQL returns file metadata row
7.  MetadataService populates cache: SET meta:file-99 '{...}' EX 300
8.  Also caches path: SET path:/finance/budget.xlsx file-99 EX 300
9.  Return FileMetadata to API server
10. Next request for file-99 will be a cache hit (within 5-minute TTL)
```

---

## Layer 2: CDN Edge Cache

### Purpose

Cache popular files at edge locations near users. For files with many viewers
(shared files, public links), CDN absorbs 99%+ of download traffic.

### Why Content-Addressable Keys Are Perfect for CDN

```
  Traditional CDN problem:
  - File updated -> CDN still serves stale version
  - Must send cache invalidation to 200+ edge locations
  - Invalidation takes 5-30 seconds to propagate globally
  - During propagation: some users see old version, some see new

  Content-addressable CDN (our approach):
  - File updated -> NEW chunks with NEW hashes
  - CDN URL: /chunks/sha256:abc123  (old version)
  - CDN URL: /chunks/sha256:def456  (new version)
  - Old and new versions coexist in CDN -- NO invalidation needed
  - Users always get the correct version (metadata points to right hash)

  Result: TTL = 30 days, ZERO invalidation, ZERO stale content
```

### CDN Cache Configuration

| Setting | Value | Rationale |
|---------|-------|-----------|
| Cache key | `chunks/{sha256-hash}` | Content-addressable: immutable key = immutable content |
| TTL | 30 days | Chunks never change; new content = new hash |
| Cache behavior | Cache everything | All chunks are cacheable (immutable binary data) |
| Compression | Disabled | Binary data -- gzip/brotli adds CPU, saves nothing |
| Range requests | Supported | Resume interrupted downloads |
| Signed URLs | Required for private files | Pre-signed URL with 15-minute expiry |
| Origin | S3 bucket | Fallback when CDN edge misses |

### Numbered Call Chain -- CDN Download (Cache Hit)

```
1.  User clicks "Download" for "report.pdf" in browser
2.  API server looks up FileMetadata for "report.pdf"
3.  FileMetadata has chunkReferences: [hash-A, hash-B, hash-C]
4.  API server generates signed CDN URLs for each chunk:
       https://cdn.example.com/chunks/sha256:hashA?sig=...&exp=...
       https://cdn.example.com/chunks/sha256:hashB?sig=...&exp=...
       https://cdn.example.com/chunks/sha256:hashC?sig=...&exp=...
5.  Client downloads chunks in parallel from CDN
6.  CDN edge (Tokyo): chunk hash-A -> CACHE HIT, serve from edge (2ms)
7.  CDN edge (Tokyo): chunk hash-B -> CACHE HIT, serve from edge (2ms)
8.  CDN edge (Tokyo): chunk hash-C -> CACHE MISS -> fetch from S3 origin (150ms)
9.  CDN caches hash-C at Tokyo edge for next 30 days
10. Client reassembles chunks into "report.pdf"
11. Total latency: max(2ms, 2ms, 150ms) = 150ms (parallel download)
12. Next download by anyone in Tokyo: ALL cache hits -> ~2ms total
```

---

## Layer 3: Block Cache (Local SSD)

### Purpose

Cache recently accessed chunks on the API server's local SSD to avoid
hitting S3 (50-200ms latency) for frequently accessed files.

### Cache Design

```
  +------------------+     +------------------+     +------------------+
  | API Server 1     |     | API Server 2     |     | API Server 3     |
  | SSD Cache: 100GB |     | SSD Cache: 100GB |     | SSD Cache: 100GB |
  | LRU eviction     |     | LRU eviction     |     | LRU eviction     |
  +------------------+     +------------------+     +------------------+
         |                        |                        |
         | miss                   | miss                   | miss
         +------------------------+------------------------+
                                  |
                                  v
                          +------------------+
                          | S3 Block Storage |
                          | (origin)         |
                          +------------------+

  Each server caches independently:
  - No cache coherence between servers (too complex, not needed)
  - Consistent hashing routes same file to same server (maximize hits)
  - If server dies: misses increase temporarily, S3 handles load
```

### Eviction Policy

| Policy | Metric | Action |
|--------|--------|--------|
| LRU eviction | Least recently used | Evict oldest-accessed chunk when SSD is full |
| Size limit | 100 GB per server | Hard cap -- never exceed |
| TTL | 1 hour | Chunks expire even if not evicted (freshness, not correctness) |
| Admission | Frequency threshold | Only cache chunks accessed 2+ times (avoid one-hit polluters) |

### Why Admission Control Matters

```
  Without admission control:
  - User downloads 50 GB backup file (12,500 chunks)
  - ALL 12,500 chunks cached on SSD
  - Evicts ALL frequently-used smaller files
  - One-hit wonder pollutes entire cache
  - Hit rate drops from 70% to 5% until cache warms back up

  With admission control (frequency >= 2):
  - User downloads 50 GB backup file (12,500 chunks)
  - First access: chunks NOT cached (frequency = 1)
  - Second access: chunks cached (frequency = 2)
  - One-time downloads never enter cache
  - Hit rate stays at 70%

  This is called a "TinyLFU" admission policy.
```

### Numbered Call Chain -- Block Cache Hit

```
1.  User downloads "report.pdf" (cached on this server's SSD)
2.  API server looks up chunk hash-A in local SSD cache
3.  SSD cache: hash-A found, last accessed 10 minutes ago -> HIT
4.  Read chunk from SSD: 0.1ms (vs 150ms from S3)
5.  Update LRU timestamp for hash-A
6.  Repeat for hash-B and hash-C
7.  Assemble file from cached chunks
8.  Total latency: ~1ms (all SSD hits) vs ~450ms (all S3)
```

---

## Layer 4: Dedup Bloom Filter (Redis)

### Purpose

Fast probabilistic check: "Is this chunk hash already in our storage?"
Avoids a PostgreSQL query for every chunk during upload.

### How Bloom Filters Work

```
  Bloom filter: a bit array of m bits with k hash functions.

  Add chunk hash "abc123":
    h1("abc123") = 42  -> set bit 42 to 1
    h2("abc123") = 97  -> set bit 97 to 1
    h3("abc123") = 156 -> set bit 156 to 1

  Check chunk hash "def456":
    h1("def456") = 42  -> bit 42 is 1 (set by abc123)
    h2("def456") = 200 -> bit 200 is 0
    --> At least one bit is 0 -> DEFINITELY NOT in the filter
    --> Skip PostgreSQL query entirely!

  Check chunk hash "ghi789":
    h1("ghi789") = 42  -> bit 42 is 1
    h2("ghi789") = 97  -> bit 97 is 1
    h3("ghi789") = 156 -> bit 156 is 1
    --> All bits are 1 -> MAYBE in the filter (could be false positive)
    --> Query PostgreSQL to confirm

  Key properties:
  - False negative rate: 0% (if chunk IS stored, Bloom filter ALWAYS says "maybe")
  - False positive rate: ~1% (if chunk is NOT stored, 1% chance of "maybe")
  - Size: ~1.2 GB for 1 billion chunks at 1% FP rate
  - Lookup: O(k) where k = number of hash functions (~10)
```

### Redis Bloom Filter Commands

```redis
-- RedisBloom module commands

-- Add chunk hash to Bloom filter (after storing in S3)
BF.ADD dedup:bloom "sha256:abc123def456..."

-- Check if chunk hash exists (before uploading)
BF.EXISTS dedup:bloom "sha256:abc123def456..."
  -> 0  (DEFINITELY NOT a duplicate -- skip PostgreSQL, store in S3)
  -> 1  (MAYBE a duplicate -- check PostgreSQL chunk_refs table)

-- Reserve with specific error rate and capacity
BF.RESERVE dedup:bloom 0.01 1000000000
  -- 1% false positive rate, 1 billion items
  -- Uses ~1.2 GB of memory

-- Info about the filter
BF.INFO dedup:bloom
  -- Capacity, error rate, number of items, memory used
```

### Dedup Decision Flow

```
  Upload chunk with hash "sha256:xyz..."
       |
       v
  BF.EXISTS dedup:bloom "sha256:xyz..."
       |
       +----> 0 (DEFINITELY NOT duplicate)
       |       |
       |       v
       |  Store chunk in S3
       |  BF.ADD dedup:bloom "sha256:xyz..."
       |  INSERT INTO chunk_refs (chunk_hash, ref_count) VALUES ('sha256:xyz...', 1)
       |
       +----> 1 (MAYBE duplicate)
              |
              v
         SELECT ref_count FROM chunk_refs WHERE chunk_hash = 'sha256:xyz...'
              |
              +----> Row found (TRUE duplicate)
              |       |
              |       v
              |  UPDATE chunk_refs SET ref_count = ref_count + 1
              |  WHERE chunk_hash = 'sha256:xyz...'
              |  --> ZERO bytes to S3 (Flyweight reuse!)
              |
              +----> Row NOT found (FALSE POSITIVE -- 1% of "maybe" cases)
                      |
                      v
                 Store chunk in S3 (it's actually new)
                 BF.ADD dedup:bloom "sha256:xyz..."  (already in filter, idempotent)
                 INSERT INTO chunk_refs (chunk_hash, ref_count) VALUES ('sha256:xyz...', 1)
```

### Numbered Call Chain -- Bloom Filter During Upload

```
1.  User uploads "presentation.pptx" (10 MB, 3 chunks after Rabin chunking)
2.  Chunk 1 hash: "sha256:aaa111..."
3.  Redis: BF.EXISTS dedup:bloom "sha256:aaa111..." -> 0 (definitely new)
4.  Store chunk 1 in S3 (4 MB uploaded)
5.  Redis: BF.ADD dedup:bloom "sha256:aaa111..."
6.  PostgreSQL: INSERT INTO chunk_refs ('sha256:aaa111...', 1)
7.  Chunk 2 hash: "sha256:bbb222..."
8.  Redis: BF.EXISTS dedup:bloom "sha256:bbb222..." -> 1 (maybe duplicate)
9.  PostgreSQL: SELECT ref_count FROM chunk_refs WHERE chunk_hash = 'sha256:bbb222...'
10. Row found! ref_count = 3 (three other files have this exact chunk)
11. TRUE DUPLICATE -- skip S3 upload entirely
12. PostgreSQL: UPDATE chunk_refs SET ref_count = 4 WHERE chunk_hash = 'sha256:bbb222...'
13. Chunk 3 hash: "sha256:ccc333..."
14. Redis: BF.EXISTS dedup:bloom "sha256:ccc333..." -> 0 (definitely new)
15. Store chunk 3 in S3 (2 MB uploaded)
16. Total uploaded: 6 MB instead of 10 MB (40% bandwidth savings)
17. Total S3 stored: 6 MB new + chunk 2 was already there (reused)
```

### Bloom Filter Sizing

| Chunks Stored | FP Rate | Memory (Redis) | Hash Functions |
|--------------|---------|----------------|----------------|
| 1 million | 1% | ~1.2 MB | 7 |
| 100 million | 1% | ~120 MB | 7 |
| 1 billion | 1% | ~1.2 GB | 7 |
| 1 billion | 0.1% | ~1.8 GB | 10 |
| 10 billion | 1% | ~12 GB | 7 |

```
  Formula:
  m = -n * ln(p) / (ln(2))^2    (bits needed)
  k = (m/n) * ln(2)              (optimal number of hash functions)

  Where:
  n = number of items (chunks)
  p = desired false positive rate
  m = number of bits
  k = number of hash functions
```

---

## Layer 5: Sync Cursor Cache (Redis)

### Purpose

Track each device's last-synced version so the server knows which changes
to push on reconnect. Stored in Redis for fast read/write.

### Redis Commands

```redis
-- Device syncs, update cursor
SET cursor:alice:laptop-abc 42 EX 86400
  -- Alice's laptop has synced up to version 42
  -- TTL: 24 hours (device must re-sync if offline > 24h)

-- Device reconnects, check last cursor
GET cursor:alice:laptop-abc
  -> "42"  (resume sync from version 43)
  -> (nil) (cursor expired -- full sync required)

-- All of alice's device cursors
KEYS cursor:alice:*
  -> cursor:alice:laptop-abc  (42)
  -> cursor:alice:phone-def   (40)
  -> cursor:alice:tablet-ghi  (38)
```

### Sync Resume Flow

```
  Device offline for 2 hours, reconnects:

  1. Device connects via WebSocket
  2. Sends: { "type": "SYNC_CURSOR", "deviceId": "laptop-abc" }
  3. Server: GET cursor:alice:laptop-abc -> 42
  4. Server: SELECT * FROM file_changes WHERE user_id = 'alice' AND version > 42
  5. Returns 17 changes (files added, modified, deleted in last 2 hours)
  6. Server pushes 17 change events over WebSocket
  7. Device applies changes (downloads new/modified chunks, deletes removed files)
  8. Device updates cursor: SET cursor:alice:laptop-abc 59 EX 86400

  If cursor expired (offline > 24 hours):
  1. Device connects, sends cursor request
  2. Server: GET cursor:alice:laptop-abc -> (nil)
  3. Server: "Cursor expired, full sync required"
  4. Device lists all local files with hashes
  5. Server compares with current file tree
  6. Delta: files to add, update, delete
  7. Device applies delta, sets new cursor
```

### Numbered Call Chain -- Cursor Sync on Reconnect

```
1.  Alice's laptop reconnects after 2-hour offline period
2.  Laptop sends WebSocket: {"type": "SYNC_RESUME", "deviceId": "laptop-abc"}
3.  SyncService queries Redis: GET cursor:alice:laptop-abc -> 42
4.  SyncService queries PostgreSQL: changes WHERE version > 42 AND user = 'alice'
5.  PostgreSQL returns 17 file changes (5 modified, 10 new, 2 deleted)
6.  SyncService pushes changes over WebSocket in order:
7.    Event 1: "budget.xlsx" modified -> version 43, changed chunks [2]
8.    Event 2: "notes.txt" created -> version 44, all chunks new
9.    ...
10.   Event 17: "old-draft.docx" deleted -> version 59
11. Laptop processes each event:
12.   For modifications: download only changed chunks (delta sync)
13.   For new files: download all chunks
14.   For deletions: remove local file
15. Laptop sends: {"type": "CURSOR_UPDATE", "version": 59}
16. SyncService: SET cursor:alice:laptop-abc 59 EX 86400
17. Sync complete -- laptop is up to date
```

---

## Layer 6: Share Link Cache (Redis)

### Purpose

Cache share link metadata (permissions, file reference, expiry) to avoid
a PostgreSQL query on every share link access. Share links can go viral
(100K+ accesses) -- caching is critical.

### Redis Commands

```redis
-- Cache share link when created
SET share:link-abc123 '{"fileId":"file-42","permission":"VIEW","expiresAt":1745712000}' EX 3600
  -- TTL matches link expiry (1 hour in this case)

-- Check share link when accessed
GET share:link-abc123
  -> '{"fileId":"file-42","permission":"VIEW","expiresAt":1745712000}'  (HIT)
  -> (nil)  (MISS -> check PostgreSQL, may be expired)

-- Revoke share link
DEL share:link-abc123

-- Track access count (rate limiting / analytics)
INCR share-hits:link-abc123
EXPIRE share-hits:link-abc123 86400
```

### Numbered Call Chain -- Share Link Access (Cache Hit)

```
1.  External user clicks: https://drive.example.com/s/link-abc123
2.  API server calls ShareService.resolveLink("link-abc123")
3.  ShareService queries Redis: GET share:link-abc123
4.  Redis returns: {"fileId":"file-42","permission":"VIEW","expiresAt":1745712000}
5.  CACHE HIT -- check expiry: 1745712000 > now() -> link is valid
6.  ShareService calls MetadataService.getFile("file-42") (also cached, Layer 1)
7.  Return file info + download URL to user
8.  INCR share-hits:link-abc123 (track access count)
9.  Total latency: ~2ms (two Redis lookups, zero PostgreSQL)
```

---

## Layer 7: Folder Listing Cache (Redis Sorted Set)

### Purpose

Cache the contents of a folder so repeated listing requests (very common --
users browse folders constantly) don't hit PostgreSQL.

### Redis Commands

```redis
-- Populate folder listing cache (after DB query)
ZADD folder:folder-42 0 '{"id":"file-1","name":"budget.xlsx","type":"FILE","size":2097152}'
ZADD folder:folder-42 0 '{"id":"file-2","name":"report.pdf","type":"FILE","size":10485760}'
ZADD folder:folder-42 0 '{"id":"folder-7","name":"archive","type":"FOLDER","size":0}'
EXPIRE folder:folder-42 120  -- 2-minute TTL

-- Get folder contents (sorted by score, which we use for ordering)
ZRANGEBYSCORE folder:folder-42 -inf +inf
  -> ["{"id":"file-1",...}", "{"id":"file-2",...}", "{"id":"folder-7",...}"]

-- Invalidate when child added or removed
DEL folder:folder-42

-- Paginated listing (large folders)
ZRANGEBYSCORE folder:folder-42 -inf +inf LIMIT 0 50   -- page 1 (items 0-49)
ZRANGEBYSCORE folder:folder-42 -inf +inf LIMIT 50 50  -- page 2 (items 50-99)
```

### Numbered Call Chain -- Folder Listing (Cache Hit)

```
1.  User navigates to "/docs/" in the file browser
2.  API server calls FolderService.listContents("folder-42")
3.  FolderService queries Redis: ZRANGEBYSCORE folder:folder-42 -inf +inf
4.  Redis returns 3 entries: [budget.xlsx, report.pdf, /archive/]
5.  CACHE HIT -- deserialize JSON, return to controller
6.  Controller returns JSON response to client
7.  Latency: ~1ms (Redis Sorted Set lookup)
```

### Invalidation on Child Changes

```
  File added to folder:
  1. User uploads "notes.txt" to folder-42
  2. FileStorageService saves metadata (PostgreSQL)
  3. After save: DEL folder:folder-42 (invalidate listing cache)
  4. Next listing request: MISS -> re-query PostgreSQL -> re-populate cache

  File moved between folders:
  1. User moves "report.pdf" from folder-42 to folder-99
  2. FileStorageService updates metadata (PostgreSQL)
  3. After move: DEL folder:folder-42 AND DEL folder:folder-99
  4. Both folder caches invalidated
```

---

## What NOT to Cache: Dedup Reference Counts

### Why Reference Counts Must Not Be Cached

Reference counts control garbage collection. A stale cached count can cause:

| Scenario | Cached Count | Real Count | Consequence |
|----------|-------------|-----------|-------------|
| Stale cache after delete | 2 | 1 | Block NOT garbage collected when it should be (storage leak -- wasteful but safe) |
| Stale cache after upload | 1 | 2 | Block garbage collected while still in use (**PERMANENT DATA LOSS**) |

```
  The danger of caching ref counts:

  Time 0: chunk "hash-X" has ref_count = 1 (real) and 1 (cached)
  Time 1: Bob uploads file referencing hash-X -> real ref_count = 2
          Cache NOT updated (write-behind or TTL hasn't expired)
  Time 2: Alice deletes her file -> GC checks cache: ref_count = 1
          GC decrements: 1 - 1 = 0 -> DELETE BLOCK
  Time 3: Bob downloads his file -> chunk "hash-X" NOT FOUND
          PERMANENT DATA LOSS

  Solution: ALWAYS read ref_count from PostgreSQL.
  - ref_count queries are infrequent (only during delete + GC)
  - Correctness >> performance for this operation
  - Add FOR UPDATE lock during GC to prevent race conditions
```

### Reference Count Read Pattern

```sql
-- During file delete (decrement)
BEGIN;
SELECT ref_count FROM chunk_refs
WHERE chunk_hash = $1
FOR UPDATE;  -- lock row to prevent concurrent modification

UPDATE chunk_refs SET ref_count = ref_count - 1
WHERE chunk_hash = $1;
COMMIT;

-- During GC (check if safe to delete)
BEGIN;
SELECT chunk_hash FROM chunk_refs
WHERE ref_count <= 0
AND created_at < NOW() - INTERVAL '72 hours'
FOR UPDATE;  -- lock to prevent concurrent increment

-- Delete from S3
-- DELETE FROM chunk_refs WHERE chunk_hash = $1
COMMIT;
```

---

## Client-Side Cache

### Local File Copy

The desktop client maintains a complete local copy of synced files:

```
  Client-side cache hierarchy:

  +----------------------------------+
  | Local File System                |  Tier 1: Full file copy
  | ~/Dropbox/ or ~/Google Drive/    |
  | Complete mirror of synced files  |
  | Updated via delta sync           |
  +----------------------------------+
       |
       v
  +----------------------------------+
  | Chunk Cache (hidden directory)   |  Tier 2: Chunk-level cache
  | ~/.filestorage/chunks/           |
  | Content-addressable chunk store  |
  | Used for: upload dedup,          |
  |           partial sync,          |
  |           conflict detection     |
  +----------------------------------+
       |
       v
  +----------------------------------+
  | Metadata Cache (SQLite)          |  Tier 3: Local metadata DB
  | ~/.filestorage/metadata.db       |
  | File tree, sync cursors,         |
  | chunk hashes, version info       |
  +----------------------------------+
```

### Delta Sync: Only Changed Chunks

```
  User edits "report.pdf" on laptop:

  1. Desktop client detects file change (OS file watcher)
  2. Client chunks the new file using Rabin fingerprint (same algorithm as server)
  3. New chunks: [hash-A, hash-B, hash-C', hash-D, hash-E]
  4. Old chunks: [hash-A, hash-B, hash-C,  hash-D, hash-E]
  5. Diff: only hash-C changed to hash-C'
  6. Client uploads ONLY hash-C' (4 MB instead of 20 MB)
  7. Server stores hash-C', updates metadata to version 6

  On other devices (phone, tablet):
  8.  SyncService pushes: "report.pdf v6, changedChunks: [position 2]"
  9.  Device downloads only hash-C' from server (4 MB)
  10. Device replaces chunk C with C' in local copy
  11. Reconstructs updated file: [A, B, C', D, E]
  12. Total transfer: 4 MB per device (not 20 MB)
```

### Numbered Call Chain -- Client-Side Upload with Local Dedup

```
1.  User saves "report.pdf" in ~/Drive/docs/
2.  OS file watcher detects modification event
3.  Client reads file, chunks with Rabin fingerprint -> 5 chunks
4.  For each chunk: compute SHA-256 hash
5.  Check local chunk cache (~/.filestorage/chunks/):
6.    hash-A: found locally AND matches server -> skip
7.    hash-B: found locally AND matches server -> skip
8.    hash-C': NOT found locally -> this is the changed chunk
9.    hash-D: found locally AND matches server -> skip
10.   hash-E: found locally AND matches server -> skip
11. Upload only hash-C' to server
12. Server: BF.EXISTS dedup:bloom "sha256:hashC'" -> 0 (new globally)
13. Server stores hash-C' in S3
14. Server updates FileMetadata: chunks now [A, B, C', D, E]
15. Server creates FileVersion 6 (Memento)
16. Server pushes sync event to other devices
17. Client updates local metadata.db: report.pdf -> version 6, chunks [A,B,C',D,E]
18. Client stores hash-C' in local chunk cache for future dedup
```

---

## Cache Warming Strategy

### On Server Start

```
  Server startup cache warming:

  1. Query top 1000 most-accessed files (by download count)
     SELECT id, path FROM files ORDER BY access_count DESC LIMIT 1000
  2. Populate metadata cache for each:
     SET meta:{id} '{...}' EX 300
     SET path:{path} {id} EX 300
  3. Query top 100 folders (by listing count)
  4. Populate folder listing cache for each
  5. Load Bloom filter from Redis (already persisted)
  6. Pre-warm SSD block cache: download top 100 files' chunks from S3

  Warming time: ~30 seconds
  Hit rate improvement: 0% -> ~40% immediately (vs waiting for organic traffic)
```

### On Device Connect

```
  Device sync cursor warming:

  1. Device connects via WebSocket
  2. Server: GET cursor:{userId}:{deviceId} from Redis
  3. If cursor exists: resume from that point (incremental sync)
  4. If cursor expired: full file tree comparison (expensive but rare)
  5. After sync: SET cursor:{userId}:{deviceId} {latestVersion} EX 86400
```

---

## Cache Hit Rate Analysis

| Cache Layer | Expected Hit Rate | Justification |
|------------|-------------------|---------------|
| Metadata cache | 85-95% | Most files are read repeatedly (folder browsing) |
| CDN edge | 60-80% | Popular files (shared, company-wide) served from edge |
| Block SSD cache | 40-60% | Recently accessed files; TinyLFU prevents pollution |
| Bloom filter | 70% definite NO | 70% of chunks are unique; skip PostgreSQL for those |
| Sync cursor | 95%+ | Devices reconnect frequently; cursor rarely expires |
| Share link | 90%+ | Viral links accessed thousands of times |
| Folder listing | 80-90% | Users browse same folders repeatedly |

### Latency Comparison

```
  File download latency by cache layer:

  All CDN hits:        ~2ms    (edge location, cached chunks)
  CDN miss, SSD hit:   ~5ms    (local SSD, cached chunks)
  CDN miss, SSD miss:  ~150ms  (S3, cross-region)
  Cold start (no cache): ~500ms (metadata from PostgreSQL + chunks from S3)

  Metadata lookup latency:

  Redis hit:           ~1ms
  Redis miss, DB hit:  ~5ms
  Folder listing:      ~1ms (Redis) vs ~10ms (PostgreSQL with JOIN)
```

---

## ASCII Diagram -- Full Request Flow Through All Cache Layers

```
  User opens folder and downloads a file:

  Step 1: Folder listing
  +--------+     +-------+     +------------+     +-----------+
  | Client |---->| Redis |---->| PostgreSQL |     | (only on  |
  | GET    |     | Folder|     | folders +  |     |  miss)    |
  | /docs/ |     | Cache |     | files JOIN |     |           |
  +--------+     +-------+     +------------+     +-----------+
                  HIT: 1ms      MISS: 10ms

  Step 2: File metadata
  +--------+     +-------+     +------------+
  | Client |---->| Redis |---->| PostgreSQL |
  | GET    |     | Meta  |     | files      |
  | file-42|     | Cache |     | table      |
  +--------+     +-------+     +------------+
                  HIT: 1ms      MISS: 5ms

  Step 3: Chunk download
  +--------+     +-----+     +---------+     +-----+
  | Client |---->| CDN |---->| SSD     |---->| S3  |
  | GET    |     | Edge|     | Block   |     | Obj |
  | chunks |     |     |     | Cache   |     | Stor|
  +--------+     +-----+     +---------+     +-----+
                 HIT: 2ms    HIT: 5ms      MISS: 150ms

  Step 4: Dedup check (upload only)
  +--------+     +-------+     +------------+
  | Server |---->| Redis |---->| PostgreSQL |
  | upload |     | Bloom |     | chunk_refs |
  | chunk  |     | Filter|     | table      |
  +--------+     +-------+     +------------+
               "NOT dup": 0.5ms  "MAYBE dup": 5ms
               (skip DB!)        (confirm in DB)
```

---

## Interview Cheat Sheet

**"What do you cache in a file storage system?"**
> "Seven cache layers: (1) metadata in Redis for file/folder lookups, (2) CDN
> for popular file downloads with content-addressable keys (zero invalidation),
> (3) local SSD for recently accessed chunks with TinyLFU admission, (4) Bloom
> filter for O(1) dedup checks, (5) sync cursors per device, (6) share link
> permissions, (7) folder listings as Redis Sorted Sets."

**"Why use a Bloom filter for dedup?"**
> "70% of uploaded chunks are unique. The Bloom filter says 'definitely NOT a
> duplicate' for those 70% with zero false negatives, skipping a PostgreSQL
> query entirely. For the 30% it says 'maybe,' we confirm in the database.
> 1 billion chunks fit in 1.2 GB of Redis at 1% false positive rate."

**"What should you NOT cache?"**
> "Dedup reference counts. A stale cached count can cause premature garbage
> collection of blocks that are still in use -- permanent data loss. Ref-count
> queries are infrequent (only during delete and GC), so the performance
> penalty of always hitting PostgreSQL with FOR UPDATE locks is acceptable."

**"How does client-side caching work?"**
> "Three tiers: full file copy in the sync folder, a hidden chunk cache for
> upload dedup and delta sync, and a SQLite metadata DB for the file tree and
> sync cursors. Delta sync compares local chunk hashes with server -- only
> changed chunks are transferred, saving 80%+ bandwidth on edits."

**"How does CDN work with content-addressable storage?"**
> "Content-addressable keys are perfect for CDN because chunks are immutable --
> same hash always means same content. Set TTL to 30 days, never invalidate.
> When a file is updated, it gets new chunk hashes, so new CDN URLs. Old and
> new versions coexist in CDN with zero cache coherence issues."
