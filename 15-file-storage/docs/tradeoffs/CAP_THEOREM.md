# CAP Theorem -- File Storage System (Google Drive / Dropbox)

> Interview-ready analysis of consistency, availability, and partition tolerance
> tradeoffs for a cloud file storage platform. Covers the unique CAP challenges
> of file storage: metadata consistency, sync notifications, deduplication
> reference counting, conflict resolution, and storage quota reconciliation.
>
> **Key insight:** Unlike most systems that pick one CAP side globally,
> file storage uses **CP for metadata and dedup ref-counts** (file tree must be
> consistent, blocks must not be prematurely deleted) and **AP for sync
> notifications and quota** (delayed sync is tolerable, approximate quota is OK).

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

## The Split Strategy: CP for Metadata, AP for Sync

### Why Split?

File storage has fundamentally different data types with different consistency needs:

| Data Type | Consistency Need | Staleness Tolerance | CAP Choice |
|-----------|-----------------|--------------------|-----------| 
| File metadata (path, owner, size) | **Non-negotiable** -- orphan files and missing folders corrupt the tree | Zero -- inconsistency = data loss | **CP** |
| Dedup reference counts | **Non-negotiable** -- premature block deletion = permanent data loss | Zero -- must be accurate | **CP** |
| Sync notifications | Nice-to-have -- delayed sync is annoying but safe | 5-30 seconds stale is fine | **AP** |
| Storage quota | Approximate OK -- user sees "14.7 GB of 15 GB" | Minutes stale, reconcile periodically | **AP** |
| Share link validation | Important but cacheable | Seconds stale, TTL-based | **AP** |
| File version history | Important but read-heavy | Seconds stale, eventually consistent | **AP** |

### The Core Argument

```
Scenario: Network partition between data centers

  File metadata:
    WRONG: Alice deletes folder "/docs/" in DC-East while Bob uploads
           "report.pdf" to "/docs/" in DC-West.
           --> Partition heals: folder is deleted but file exists with
               no parent. Orphaned file. Navigation broken.
    RIGHT: Block metadata writes during partition. Return 503.
           --> Users retry after partition heals. No orphans. No corruption.

  Dedup reference counts:
    WRONG: Alice deletes "installer.iso" in DC-East (refCount 2 -> 1).
           Bob deletes his copy in DC-West (refCount 2 -> 1).
           Both think refCount = 1 after their delete.
           --> Partition heals: both decrements applied: refCount = 0.
               Block garbage collected. Charlie (who also shared the block)
               downloads a MISSING BLOCK. Permanent data loss.
    RIGHT: Serialize ref-count operations through single leader (CP).
           --> During partition, one DC cannot decrement. Annoying but safe.

  Sync notifications:
    WRONG: Block sync notifications during partition. Devices are frozen.
           --> Users cannot access their local files. Unacceptable.
    RIGHT: Allow stale sync state. Devices work with local copy.
           --> When partition heals, delta sync catches up. No data lost.
           --> Users experience a delay, not a failure.

  Storage quota:
    WRONG: Block file uploads if quota service is partitioned.
           --> Users cannot upload to their own cloud storage. Unacceptable.
    RIGHT: Allow approximate quota. Reconcile when partition heals.
           --> User might briefly exceed quota by a few MB. Bill later.
```

### ASCII Diagram -- Split CAP Strategy

```
  +---------------------------------------------------------------------+
  |                       FILE STORAGE SYSTEM                           |
  |                                                                     |
  |   +---------------------------+   +-----------------------------+   |
  |   |    CP SUBSYSTEM           |   |    AP SUBSYSTEM             |   |
  |   |    (consistency first)    |   |    (availability first)     |   |
  |   |                           |   |                             |   |
  |   |  - File metadata          |   |  - Sync notifications      |   |
  |   |    (path, tree, perms)    |   |    (WebSocket push)        |   |
  |   |                           |   |                             |   |
  |   |  - Dedup ref counts       |   |  - Storage quota           |   |
  |   |    (block liveness)       |   |    (approximate, reconcile)|   |
  |   |                           |   |                             |   |
  |   |  - Folder hierarchy       |   |  - Share link cache        |   |
  |   |    (parent-child links)   |   |    (TTL-based)             |   |
  |   |                           |   |                             |   |
  |   |  Leader: single-leader    |   |  - File version history    |   |
  |   |  replication for metadata |   |    (eventually consistent) |   |
  |   |  DB (PostgreSQL)          |   |                             |   |
  |   |                           |   |  Multi-leader / leaderless |   |
  |   |  Tradeoff: unavailable    |   |  Tradeoff: stale data      |   |
  |   |  during partition         |   |  during partition           |   |
  |   +---------------------------+   +-----------------------------+   |
  +---------------------------------------------------------------------+
```

---

## CP Analysis: File Metadata

### Why CP for Metadata?

The file tree is a relational structure. Inconsistency causes structural corruption:

| Inconsistency Scenario | Consequence |
|------------------------|-------------|
| File exists but parent folder deleted | Orphan file -- invisible in UI, inaccessible |
| Two files with same path | Path collision -- which one is real? |
| File metadata says 3 chunks, only 2 stored | Corrupted file -- download fails |
| Permissions say "shared", ACL says "private" | Security vulnerability |
| File moved but old path still cached | Wrong file served on download |

### How CP Works for Metadata

```
  Write path (strong consistency):

  1. Client uploads file
  2. API server writes to PostgreSQL leader
  3. Leader writes to WAL (write-ahead log)
  4. Leader replicates to at least 1 follower (synchronous)
  5. Leader ACKs to API server
  6. API server returns 201 Created to client

  Read path (read-your-writes consistency):

  1. Client requests file metadata
  2. API server reads from PostgreSQL leader (not follower)
     OR routes to follower only if replication lag < threshold
  3. Returns consistent metadata

  During partition:
  +--------+         X         +-----------+
  | Leader |----partition------| Follower  |
  | (DC-1) |                   | (DC-2)    |
  +--------+                   +-----------+
       |                            |
  Writes succeed               Reads may be
  (leader available)           stale or blocked
                               (depends on config)
```

### Numbered Call Chain -- CP Metadata Write

```
1.  User uploads "budget.xlsx" to folder "/finance/"
2.  API server (DC-1) receives upload request
3.  API server writes FileMetadata to PostgreSQL leader
4.  PostgreSQL leader: BEGIN TRANSACTION
5.  INSERT INTO files (id, path, folder_id, ...) VALUES (...)
6.  INSERT INTO file_chunks (file_id, chunk_hash, position) VALUES (...)
7.  UPDATE folders SET modified_at = NOW() WHERE id = folder_id
8.  PostgreSQL leader replicates to synchronous follower (DC-2)
9.  Follower ACKs replication
10. PostgreSQL leader: COMMIT
11. API server returns 201 to client
12. If follower is unreachable (partition): write BLOCKS until follower recovers
13. Client retries after timeout -- annoying but no data corruption
```

---

## CP Analysis: Dedup Reference Counting

### Why CP for Reference Counts?

Reference counts control block garbage collection. An incorrect count means:

| Error | Consequence |
|-------|-------------|
| Count too low (undercounted) | Block garbage collected while still in use -> **permanent data loss** |
| Count too high (overcounted) | Block never garbage collected -> storage leak (wasteful but safe) |

**Undercounting is catastrophic.** This is why ref-count operations MUST be serialized.

### The Split-Brain Danger

```
  Without CP (multi-leader ref counts):

  Time 0: Block "hash-X" has refCount = 2
          (Alice's file + Bob's file both reference it)

  Time 1: Alice deletes her file in DC-East
           DC-East: refCount = 2 - 1 = 1  (local decrement)

  Time 2: Bob deletes his file in DC-West (DURING PARTITION)
           DC-West: refCount = 2 - 1 = 1  (local decrement, doesn't see Alice's)

  Time 3: Partition heals, both decrements merge
           Merged refCount = 2 - 1 - 1 = 0
           GC runs: block "hash-X" DELETED from S3

  Time 4: Charlie (in DC-East) downloads his file that also references "hash-X"
           Block not found. PERMANENT DATA LOSS.
           Charlie's file is corrupted forever.

  With CP (single-leader ref counts):

  Time 1: Alice's delete routed to leader -> refCount = 2 - 1 = 1
  Time 2: Bob's delete blocked (leader unreachable due to partition)
  Time 3: Partition heals, Bob's delete processed -> refCount = 1 - 1 = 0
  Time 4: GC runs: block deleted. SAFE because Charlie was counted.
           Wait -- was Charlie counted? Let's check...
           Charlie's file was uploaded BEFORE Alice and Bob deleted.
           Charlie's upload incremented refCount from 1 to 2 (at some point).
           So actual count was 3 before deletes, now 3 - 1 - 1 = 1.
           Block NOT deleted. Charlie is safe.
```

### Defensive Strategy: Delayed Garbage Collection

Even with CP, add a safety margin:

```
  1. Block refCount reaches 0
  2. DO NOT delete immediately
  3. Mark block as "pending GC" with timestamp
  4. Wait 72 hours (grace period)
  5. Re-verify refCount is still 0 (full consistency check)
  6. THEN delete from S3

  This catches:
  - Late-arriving increments from slow replicas
  - Bugs in ref-count logic
  - Race conditions during concurrent uploads/deletes
  - Partition recovery scenarios
```

---

## AP Analysis: Sync Notifications

### Why AP for Sync?

Sync notifications tell devices "something changed, go fetch the update."
If a notification is delayed or lost, the device stays on a slightly old version.
That is annoying but NOT catastrophic -- the next sync cycle catches up.

| Failure Mode | Impact | Recovery |
|-------------|--------|----------|
| Notification delayed 30 seconds | User sees old version briefly | Auto-sync catches up |
| Notification lost entirely | User misses one update cycle | Periodic full-sync reconciles |
| Notification delivered out of order | User sees version 5 before version 4 | Version number ordering resolves |
| Duplicate notification | Device downloads update twice | Idempotent -- same chunks, no harm |

### How AP Works for Sync

```
  Sync notification flow (AP -- eventual consistency):

  1. File uploaded -> Kafka event published to "file-changes" topic
  2. SyncService consumes event
  3. SyncService pushes WebSocket notification to connected devices
  4. If device is offline: event stored in Redis per-user queue
  5. When device comes online: drain queue, fetch all missed updates

  During partition:
  +--------+         X         +-----------+
  | Kafka  |----partition------| SyncService|
  | (DC-1) |                   | (DC-2)     |
  +--------+                   +-----------+
       |                            |
  Events buffered              Devices in DC-2
  in Kafka                     don't get push
                               notifications
                               --> delta sync on
                                   reconnect
```

### Numbered Call Chain -- AP Sync During Partition

```
1.  Alice uploads "report.pdf" in DC-East
2.  Kafka event published: {file: "report.pdf", action: "MODIFIED", version: 5}
3.  SyncService in DC-East receives event, pushes to Alice's phone (DC-East)
4.  SyncService in DC-West is PARTITIONED from Kafka
5.  Bob's laptop (DC-West) does NOT receive push notification
6.  Bob continues working with version 4 of "report.pdf" -- stale but functional
7.  Partition heals after 2 minutes
8.  SyncService DC-West consumes buffered Kafka events
9.  Pushes notification to Bob's laptop: "report.pdf updated to version 5"
10. Bob's laptop fetches delta (only changed chunks) from block storage
11. Bob now has version 5. Total delay: ~2 minutes. No data lost.
```

---

## AP Analysis: Storage Quota

### Why AP for Quota?

Storage quota ("You're using 14.7 GB of 15 GB") can be approximate:

| Scenario | Exact Quota | Approximate Quota |
|----------|-------------|-------------------|
| User at 14.9 GB uploads 200 MB file | BLOCK upload: "quota exceeded" | ALLOW upload: user at 15.1 GB temporarily |
| Concurrent uploads from 3 devices | Serialize all 3 -- slow | Allow all 3 -- reconcile after |
| Quota service partitioned | ALL uploads blocked | Uploads continue, reconcile later |

### How AP Works for Quota

```
  Quota check flow (AP -- approximate):

  1. User uploads file (200 MB)
  2. API server checks Redis quota cache: "alice: 14.7 GB of 15.0 GB"
  3. 14.7 + 0.2 = 14.9 <= 15.0 -> ALLOW
  4. Upload proceeds
  5. After upload, async update: Redis quota = 14.9 GB
  6. PostgreSQL quota updated eventually (background job)

  Reconciliation (periodic):
  1. Background job runs every 10 minutes
  2. SELECT SUM(size_bytes) FROM files WHERE owner_id = 'alice'
  3. Actual usage: 14.95 GB (some uploads weren't counted yet)
  4. Update Redis cache: alice = 14.95 GB
  5. If user over quota: flag account, block future uploads
```

### Numbered Call Chain -- Quota Reconciliation

```
1.  QuotaReconciliationJob runs every 10 minutes (cron)
2.  Fetches all user IDs from UserRepository
3.  For each user: SELECT SUM(size_bytes) FROM files WHERE owner_id = ?
4.  Compares with cached quota in Redis
5.  User "alice": Redis says 14.7 GB, PostgreSQL says 14.95 GB
6.  Updates Redis: SET quota:alice 14.95 GB
7.  User "bob": Redis says 7.2 GB, PostgreSQL says 7.2 GB -> no update needed
8.  User "charlie": Redis says 15.3 GB, PostgreSQL says 15.3 GB -> OVER QUOTA
9.  Marks charlie's account as over-quota, sends notification email
10. Charlie's next upload attempt returns 413 Payload Too Large
```

---

## Conflict Resolution Deep Dive

### The Fundamental Problem

Two devices edit the same file offline and sync simultaneously:

```
  Time 0: File "report.docx" at version 3 on server, laptop, and phone

  Time 1: Laptop goes offline, edits report -> local version 4
  Time 2: Phone goes offline, edits report -> local version 4

  Time 3: Laptop comes online first
          Uploads version 4 -> server accepts -> server version = 4

  Time 4: Phone comes online
          Uploads version 4 -> server detects CONFLICT
          Server has version 4 (from laptop), phone is also claiming version 4
          --> ConflictStrategy.resolve(serverV4, phoneV4, "report.docx")
```

### Strategy A: Last Writer Wins (LWW)

```java
public class LastWriterWinsStrategy implements ConflictStrategy {
    @Override
    public ConflictResolution resolve(FileVersion serverVersion,
                                      FileVersion clientVersion,
                                      String filePath) {
        if (clientVersion.getModifiedAt().isAfter(serverVersion.getModifiedAt())) {
            // Client is newer -- overwrite server
            return ConflictResolution.replaceServer(clientVersion);
        } else {
            // Server is newer -- discard client version
            return ConflictResolution.keepServer();
        }
    }
}
```

| Pro | Con |
|-----|-----|
| Simple, no user intervention needed | **Data loss** -- one version is discarded |
| No clutter (no extra files) | Depends on clock accuracy (NTP skew) |
| Predictable behavior | User who "loses" is not notified in basic implementations |
| Good for non-critical files | Bad for documents with significant edits |

### Strategy B: Keep Both

```java
public class KeepBothStrategy implements ConflictStrategy {
    @Override
    public ConflictResolution resolve(FileVersion serverVersion,
                                      FileVersion clientVersion,
                                      String filePath) {
        String conflictName = generateConflictName(filePath);
        // e.g., "report (conflict copy - Alice's Laptop - 2026-04-26).docx"
        return ConflictResolution.keepBoth(
            serverVersion,    // stays as "report.docx"
            clientVersion,    // saved as conflict copy
            conflictName
        );
    }
}
```

| Pro | Con |
|-----|-----|
| **Zero data loss** -- both versions preserved | Folder clutter (conflict copies pile up) |
| User decides which to keep | Requires manual merge |
| Audit trail (both versions exist) | Confusing for non-technical users |
| Dropbox default for true conflicts | Requires notification to user |

### Comparison: How Real Services Handle Conflicts

| Service | Non-Overlapping Edits | True Conflicts | Offline Conflicts |
|---------|----------------------|----------------|-------------------|
| **Dropbox** | LWW (auto-merge) | Keep-both (conflict copy) | Keep-both with device name in filename |
| **Google Drive** | OT merge (real-time) | Keep-both for offline conflicts | Keep-both, shows "resolve conflict" dialog |
| **OneDrive** | LWW by default | Keep-both (conflict fork) | Keep-both, user manually merges |
| **iCloud** | LWW with "pick version" dialog | LWW with undo option | LWW, "pick a version" on next open |

### ASCII Diagram -- Conflict Resolution Flow

```
                          Device A                  Device B
                          (Laptop)                  (Phone)
                             |                         |
                         Edit offline              Edit offline
                             |                         |
                             v                         v
                     Local version 4            Local version 4
                             |                         |
                       Come online                Come online
                       (first)                    (second)
                             |                         |
                             v                         |
                     Upload v4 to server               |
                     Server: v3 -> v4                  |
                             |                         v
                             |                 Upload v4 to server
                             |                 Server: CONFLICT! (already at v4)
                             |                         |
                             |                         v
                             |              ConflictStrategy.resolve(serverV4, phoneV4)
                             |                         |
                             |               +---------+---------+
                             |               |                   |
                             |               v                   v
                             |         LWW Strategy         KeepBoth Strategy
                             |         "Phone version       "Save phone version
                             |          is newer?            as conflict copy,
                             |          Keep it.             keep server version
                             |          Discard laptop's."   as-is."
                             |               |                   |
                             v               v                   v
                        Sync to         Sync to all         Sync both files
                        all devices     devices             to all devices
```

---

## Eventual Consistency: Where It Works

### Sync Cursor (Per-User Sync State)

```
  Each device tracks: "I've synced up to server version X"

  Sync cursor in Redis:
    Key: sync-cursor:{userId}:{deviceId}
    Value: last-synced-version (e.g., 42)

  Consistency model: Eventually consistent
  - Device syncs, updates cursor to 42
  - Redis propagates cursor to replicas
  - If replica is stale, device re-fetches some already-seen changes
  - Idempotent: re-applying same changes is harmless (chunks already exist)

  Not CP because:
  - Stale cursor means re-downloading a few chunks (wasted bandwidth, not data loss)
  - Getting the cursor exactly right saves bandwidth but isn't critical
```

### Search Index

```
  Full-text search of file names and content:

  Consistency model: Eventually consistent (AP)
  - File uploaded -> Kafka event -> Search indexer updates Elasticsearch
  - Indexing delay: 1-5 seconds typical
  - User uploads "budget-2026.xlsx" but search doesn't find it for 3 seconds
  - Acceptable: user knows they just uploaded it

  Not CP because:
  - Missing a file in search for a few seconds is not data loss
  - User can navigate to the file via folder tree (which IS consistent)
```

### Thumbnail Generation

```
  Thumbnail for image/video preview:

  Consistency model: Eventually consistent (AP)
  - File uploaded -> Kafka event -> Thumbnail service generates preview
  - Delay: 2-10 seconds for images, 30-60 seconds for video
  - User sees placeholder until thumbnail ready
  - Acceptable: user just uploaded the file, they know what it looks like

  Not CP because:
  - Missing thumbnail is a cosmetic issue, not data integrity
  - Placeholder + lazy load is standard UX pattern
```

---

## Industry Comparison

### Dropbox Architecture

```
  Metadata:     CP -- MySQL with Paxos-based replication (Edgestore)
  Block storage: AP -- S3-compatible object storage (Magic Pocket)
  Sync:          AP -- Notification server (long-polling, then WebSocket)
  Dedup:         CP -- Server-side dedup with ref counting
  Conflict:      KeepBoth for true conflicts, LWW for non-overlapping

  Key design choice: Metadata and block storage are SEPARATE services.
  Metadata is CP (strong consistency, single leader).
  Block storage is AP (eventually consistent, multi-region).
  This split lets them optimize each independently.
```

### Google Drive Architecture

```
  Metadata:     CP -- Spanner (globally consistent, TrueTime)
  Block storage: AP -- Colossus (Google's distributed file system)
  Sync:          AP -- Push notifications via Firebase Cloud Messaging
  Dedup:         CP -- Server-side, per-user dedup (not cross-user)
  Conflict:      OT for real-time (Docs), KeepBoth for offline (Drive)

  Key design choice: Spanner gives CP with high availability globally.
  TrueTime (atomic clocks + GPS) enables globally consistent reads
  without sacrificing availability in most cases.
  This is effectively CA during normal operation, CP during true partitions.
```

### OneDrive Architecture

```
  Metadata:     CP -- Azure SQL with geo-replication
  Block storage: AP -- Azure Blob Storage
  Sync:          AP -- Differential sync via REST API
  Dedup:         Limited -- per-file delta encoding, not cross-file dedup
  Conflict:      KeepBoth with user-facing merge UI in Office

  Key design choice: Tight integration with Office 365.
  Word/Excel/PowerPoint have built-in merge capabilities.
  OneDrive leverages Office's merge instead of generic conflict resolution.
```

### Comparison Table

| Dimension | Dropbox | Google Drive | OneDrive | Our Design |
|-----------|---------|-------------|----------|-----------|
| Metadata consistency | CP (Paxos) | CP (Spanner) | CP (Azure SQL) | CP (PostgreSQL leader) |
| Block storage | AP (Magic Pocket) | AP (Colossus) | AP (Azure Blob) | AP (S3) |
| Sync model | AP (long-poll) | AP (push notify) | AP (REST poll) | AP (WebSocket + Kafka) |
| Dedup scope | Cross-user | Per-user | Per-file delta | Cross-user (hash-based) |
| Conflict resolution | KeepBoth | OT + KeepBoth | KeepBoth + Office merge | Strategy (LWW or KeepBoth) |
| Ref-count GC | Delayed GC | Delayed GC | Not documented | Delayed GC (72h grace) |

---

## Interview Cheat Sheet

**"What CAP tradeoffs does your file storage system make?"**
> "We split CP and AP by data type. Metadata and dedup reference counts are
> CP -- file tree corruption or premature block deletion is catastrophic.
> Sync notifications and storage quota are AP -- delayed sync is tolerable,
> approximate quota is fine with periodic reconciliation."

**"Why CP for reference counts specifically?"**
> "Because undercounting causes permanent data loss. If two deletes happen
> during a partition and both decrement from 2 to 1 locally, the merged
> result is 0 and the block gets garbage collected while other files still
> reference it. We serialize decrements through a single leader and add a
> 72-hour GC grace period as defense in depth."

**"How do Dropbox and Google Drive differ?"**
> "Both use CP for metadata, but Google uses Spanner (globally consistent via
> TrueTime) while Dropbox uses MySQL with Paxos. For sync, Dropbox uses
> long-polling (now WebSocket), Google uses Firebase push notifications.
> For dedup, Dropbox does cross-user dedup while Google does per-user only."

**"Why not make everything CP?"**
> "Because CP means unavailability during partitions. If sync notifications
> were CP, devices couldn't access their local files during a network issue.
> Users expect to work offline -- AP for sync lets devices operate independently
> and reconcile later via delta sync."

**"How do you handle conflict resolution?"**
> "ConflictStrategy is a Strategy pattern -- inject LWW for simplicity or
> KeepBoth for safety. Dropbox uses KeepBoth for true conflicts and LWW
> for non-overlapping edits. We default to KeepBoth because data loss from
> LWW is worse than folder clutter from conflict copies."
