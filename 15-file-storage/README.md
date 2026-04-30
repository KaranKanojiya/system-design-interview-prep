# File Storage System (Google Drive/Dropbox)

## Problem Summary

Design a **file storage and sync system** (like Google Drive or Dropbox) that supports 100M+ users with seamless cross-device synchronization. The core challenges are **chunked upload** -- split files into 4MB chunks, upload in parallel, resume on failure, and **deduplication** -- hash each chunk with SHA-256 and use the hash as the storage key (content-addressable storage), so identical content is stored exactly once across all users, saving 30-50% storage. **Delta sync** is the key efficiency mechanism: when a file changes, re-chunk it, compare hashes to the previous version, and upload ONLY the changed chunks -- editing a 100MB file might upload just 4MB. **Versioning** stores each version as a list of chunk hashes; unchanged chunks are shared between versions (copy-on-write), so 30 versions of a 20MB file don't cost 600MB -- they cost 20MB + deltas. **Sync** uses **long polling**: devices hold open connections to the server, which pushes change notifications immediately; devices then pull deltas using a **cursor** (opaque position marker) that tracks what each device has already seen. **Conflict resolution** has two schools: **last-writer-wins** (Google Drive -- simpler but risks data loss when two devices edit the same file simultaneously) vs **keep-both** (Dropbox -- creates a "conflict copy" file, no data loss but clutters the folder). The **folder hierarchy** uses the **Composite pattern** -- folders contain files AND subfolders, recursive operations (delete, move, share) propagate naturally. The system is **CP for metadata** (the file tree must be consistent -- a user must not see a file that was deleted) and **AP for sync notifications** (a 2-second delay in sync notification is invisible to users).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Chunked upload: split file into 4MB chunks, upload in parallel, resume on failure. SHA-256 checksum per chunk.** Client splits the file locally into fixed-size 4MB blocks. Each chunk gets a SHA-256 hash that serves dual purpose: integrity verification (detect corruption during upload) and deduplication key (same content = same hash = stored once). Chunks upload in parallel (4 concurrent connections) directly to S3 via presigned URLs, bypassing the API server for data transfer. If upload fails mid-way, the client resumes from the last unacknowledged chunk -- no need to re-upload completed chunks. A 100MB file = 25 chunks; with 4 parallel uploads, total upload time drops from ~16s sequential to ~4s parallel.
- **Deduplication: hash(chunk) = storage key. Same content stored once. Saves 30-50% storage. Content-addressable.** The SHA-256 hash of each chunk IS the storage key in S3: `s3://chunks/{sha256_hash}`. Before uploading, the client sends all chunk hashes to the server; the server checks the dedup index (DynamoDB) and responds with which chunks already exist. The client uploads ONLY missing chunks. At scale: 1000 users upload the same quarterly report -- stored once, referenced 1000 times. Reference counting tracks how many files point to each chunk; chunk deleted only when ref_count reaches 0. This is the same principle as Git's object storage.
- **Sync: long polling for change notifications. Delta sync: only transfer changed chunks, not entire file.** Client holds open an HTTP connection (long poll, 60s timeout). Server pushes immediately when a file changes. Client pulls delta: "give me all changes since cursor X." Response includes changed file IDs + new cursor. For each changed file, client compares local chunk hashes to server's chunk list -- downloads only missing chunks. Edit a 100MB presentation, change 1 slide: 1 chunk (4MB) transfers, not the full file. Bandwidth savings: 96%. Dropbox syncs 1.2B files/day using this approach.
- **Versioning: each version = list of chunk hashes. Versions share unchanged chunks (copy-on-write). Max 30 versions.** Version 1 of a 5-chunk file: [A, B, C, D, E]. User edits, changing chunk C to C'. Version 2: [A, B, C', D, E]. Storage cost of version 2: only chunk C' is new (4MB), not the full file (20MB). Chunks A, B, D, E are shared between versions. With 30 versions and 5% change per version: total storage = 1 file + 30 * 5% = 2.5x (not 30x). Rollback to any version: load chunk list, reassemble file from chunks. Delete old versions: decrement ref_count on unique chunks, delete when ref_count = 0.
- **Conflict resolution: last-writer-wins (Google Drive) vs keep-both (Dropbox). LWW risks data loss.** When two devices edit the same file simultaneously, the server detects a version mismatch (device sends oldVersion=3, server is at version=4). LWW: accept the later write, overwrite silently. Simple, but if Device A edited paragraphs 1-3 and Device B edited paragraphs 4-6, LWW loses one set of changes. Keep-both: create "report.docx" and "report (conflict copy).docx" -- user manually merges. No data loss, but clutters folders. Three-way merge (Git-style): compare both versions against common ancestor, auto-merge non-conflicting chunks, flag conflicts. Best but most complex.
- **Folder hierarchy: Composite pattern -- folders contain files AND subfolders.** `FileSystemNode` is the base (interface). `File` is a leaf node (has chunks, size, version). `Folder` is a composite (has children: files + subfolders). Recursive operations: `getSize()` on a folder sums all descendant files. `delete()` recursively deletes all children. `share()` propagates permissions down the tree. `move()` re-parents the node. This maps directly to what users see in Drive/Dropbox. Path resolution: traverse from root -> folder -> subfolder -> file.
- **CAP: CP for metadata (file tree consistency), AP for sync notifications.** File metadata must be CP: if User A deletes a file and User B reads the folder listing, B must not see the deleted file -- otherwise B might try to open a nonexistent file (broken UX). Version history must be CP: reading version 5 must always return the same chunk list. Sync notifications are AP: if a notification arrives 2 seconds late, the user simply sees the file update 2 seconds later -- not a problem. Long polling connections can reconnect and catch up via cursor. Dedup index is eventually consistent: worst case, a chunk is uploaded twice (stored once, dedup catches it on second write).

---

## Class Hierarchy

```
File (domain entity)                       Folder (domain entity, Composite)
  |-- fileId (UUID)                          |-- folderId (UUID)
  |-- name ("report.docx")                   |-- name ("Documents")
  |-- size (bytes)                            |-- parentId (null for root)
  |-- ownerId                                 |-- ownerId
  |-- folderId (parent folder)                |-- children: List<FileSystemNode>
  |-- chunks: List<ChunkHash>                 |-- permissions: Map<UserId, Role>
  |-- currentVersion (int)                    |-- createdAt, updatedAt
  |-- status: ACTIVE | DELETED | TRASHED      |-- status: ACTIVE | DELETED | TRASHED
  |-- mimeType ("application/pdf")            |-- getSize() -> sum of all descendant files
  |-- createdAt, updatedAt                    |-- No setters (immutable once created,
  |-- No setters (immutable value updates)    |    new version for changes)

Chunk (value object)                       FileVersion (value object)
  |-- hash (SHA-256, content-addressable)    |-- fileId
  |-- size (bytes, max 4MB)                  |-- version (monotonic counter)
  |-- s3Key ("chunks/{sha256}")              |-- chunks: List<ChunkHash>
  |-- refCount (number of files referencing) |-- size (total bytes)
  |-- createdAt                              |-- userId (who created this version)
  |-- No setters (immutable by design)       |-- timestamp
                                             |-- No setters (immutable)

ChunkingStrategy (interface)               DeduplicationStrategy (interface)
  |-- FixedSizeChunkingStrategy               |-- HashBasedDeduplicationStrategy
  |     (4MB fixed blocks, simple, fast)      |     (SHA-256 hash lookup in dedup index)
  |-- RabinFingerprintChunkingStrategy        |-- RabinFingerprintDeduplicationStrategy
  |     (content-defined, better dedup)       |     (variable-size chunks, higher dedup ratio)
  |-- ChunkingStrategyFactory                 |-- DeduplicationStrategyFactory
  |     (picks by file type/size)             |     (picks by storage tier/performance)

SyncStrategy (interface)                   ConflictResolutionStrategy (interface)
  |-- LongPollingSyncStrategy                 |-- LastWriterWinsStrategy
  |     (HTTP long poll, 60s timeout)         |     (latest timestamp wins, Google Drive)
  |-- WebSocketSyncStrategy                   |-- KeepBothStrategy
  |     (persistent connection, real-time)    |     (create conflict copy, Dropbox)
  |-- SyncStrategyFactory                     |-- ThreeWayMergeStrategy
  |     (picks by client capability)          |     (chunk-level merge, Git-style)

FileService                                SyncService
  |-- upload(userId, folderId, file)         |-- getChanges(userId, cursor) -> ChangeList
  |     -> chunk, dedup, store, version      |-- subscribe(userId, deviceId)
  |-- download(userId, fileId) -> File       |     -> long poll connection
  |-- delete(userId, fileId)                 |-- publishChange(fileId, changeType)
  |-- move(userId, fileId, newFolderId)      |-- getCursor(userId, deviceId) -> Cursor
  |-- getVersions(fileId) -> List<Version>   |-- resolveConflict(fileId, strategy)
  |-- rollback(fileId, version)

ChunkService                               FolderService
  |-- chunkFile(file) -> List<Chunk>         |-- create(userId, parentId, name) -> Folder
  |-- dedupCheck(hashes) -> List<needed>     |-- list(userId, folderId) -> List<FileSystemNode>
  |-- storeChunk(chunk) -> s3Key             |-- move(userId, folderId, newParentId)
  |-- getChunk(hash) -> byte[]               |-- share(userId, folderId, targetUser, role)
  |-- deleteChunk(hash) (when refCount=0)    |-- delete(userId, folderId) (recursive)
  |-- updateRefCount(hash, delta)            |-- getPath(folderId) -> String

AppConfig (wiring)
  |-- creates services, strategies
  |-- wires upload pipeline: chunk -> dedup -> store -> version -> sync
  |-- configures S3, RDS, DynamoDB, Redis, SQS/SNS
  |-- selects chunking strategy (fixed vs Rabin)
  |-- selects conflict resolution strategy (LWW vs keep-both)
```

---

## Key Components

| Component | Role |
|-----------|------|
| `File` | Core domain entity. Represents a stored file with name, size, owner, current version, and chunk list. Lives in a folder. Immutable -- edits create new versions. |
| `Folder` | Composite pattern entity. Contains files and subfolders. Recursive operations: getSize(), delete(), share(). Root folder per user. Shared folders appear in multiple users' trees. |
| `Chunk` | Immutable value object. 4MB block of file data, stored in S3 at key=SHA-256 hash. Reference counted -- shared across files, users, and versions. Deleted when ref_count=0. |
| `FileVersion` | Immutable snapshot of a file at a point in time. Stores ordered list of chunk hashes. Unchanged chunks shared between versions (copy-on-write). Max 30 versions per file. |
| `ChunkingStrategy` | Strategy pattern: FixedSizeChunking (simple 4MB blocks) vs RabinFingerprint (content-defined boundaries, better dedup ratio for files with insertions). Factory selects by file type. |
| `DeduplicationStrategy` | Strategy pattern: HashBased (SHA-256 lookup in DynamoDB index) vs RabinFingerprint (variable-size chunks for higher dedup ratio). Core storage savings mechanism. |
| `SyncStrategy` | Strategy pattern: LongPolling (HTTP, 60s timeout, widely compatible) vs WebSocket (persistent, sub-second latency). Factory picks by client capability. |
| `ConflictResolutionStrategy` | Strategy pattern: LastWriterWins (simple, risks data loss), KeepBoth (safe, clutters folder), ThreeWayMerge (smart, complex). Configurable per deployment. |
| `FileService` | Core orchestrator: upload (chunk + dedup + store + version + notify), download (get chunks + reassemble), delete, move, version history, rollback. |
| `SyncService` | Manages cross-device sync. Cursor-based change tracking. Long polling connections. Publishes change events via SNS/SQS. Conflict detection on version mismatch. |
| `ChunkService` | Handles chunking, dedup checks, S3 storage, reference counting. The dedup engine: checks DynamoDB index before storing, returns presigned URLs for needed chunks only. |
| `FolderService` | CRUD for folders. Composite tree operations: list contents, recursive delete, share propagation, path resolution. Uses RDS Aurora for relational folder queries. |
| `AppConfig` | Wires everything together. S3 buckets, RDS tables, DynamoDB indexes, Redis clusters, SQS queues, SNS topics. Single entry point for demo simulation. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Chunking strategy | Fixed-size (4MB blocks) | Content-defined (Rabin fingerprint) | **Fixed-size** -- simpler, predictable chunk sizes, good enough for most files. Rabin is better when files have frequent insertions (text docs) because boundaries shift with content, but adds CPU overhead. Dropbox uses Rabin for better dedup. |
| Dedup scope | Per-user dedup only | Global cross-user dedup | **Global dedup** -- if 1000 users upload the same PDF, stored once. Reference counting tracks ownership. Privacy: chunks are hashed, not inspectable. 30-50% storage savings at scale. |
| Conflict resolution | Last-writer-wins (LWW) | Keep-both (conflict copy) | **Keep-both** as default -- no data loss. LWW available as user preference. Three-way merge for advanced use. Dropbox's choice for safety. |
| Sync mechanism | Polling (every N seconds) | Long polling (server push) | **Long polling** -- near-instant notification without WebSocket complexity. Client holds HTTP connection open for 60s; server responds immediately on change or timeout. Simple, firewall-friendly, Dropbox uses this. |
| Storage backend | S3 (managed) | Custom block storage (Magic Pocket) | **S3** -- 11 nines durability, zero ops overhead. Custom storage only makes sense at 100+ PB (Dropbox's scale). Below that, S3 is cheaper when you factor in engineering cost. |
| Version storage | Full file copy per version | Chunk list per version (copy-on-write) | **Chunk list** -- each version is a list of chunk hashes. Unchanged chunks shared. 30 versions of 20MB file: ~25MB total, not 600MB. Massive storage savings. |
| Metadata DB | Single DB for all metadata | Split: relational (RDS) + key-value (DynamoDB) | **Split** -- RDS Aurora for file tree, folders, sharing, permissions (relational queries). DynamoDB for chunk index, dedup lookups, version lists (high throughput, key-value access). Each DB plays to its strengths. |
| Encryption | Server-side only (SSE-S3) | Server-side + client-side option | **Server-side default (SSE-KMS)** with optional client-side encryption for sensitive files. KMS manages keys, supports rotation. Client-side encryption breaks dedup (encrypted chunks never match) -- trade-off clearly documented. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Composite** | Folder contains Files and sub-Folders (FileSystemNode hierarchy) | Recursive operations (size, delete, share, move) propagate naturally through the tree |
| **Strategy** | ChunkingStrategy (Fixed vs Rabin fingerprint) | Swap chunking algorithm without changing upload pipeline |
| **Strategy** | DeduplicationStrategy (Hash-based vs Rabin-based) | Swap dedup approach based on storage tier or file type |
| **Strategy** | SyncStrategy (LongPolling vs WebSocket) | Swap sync mechanism based on client capability |
| **Strategy** | ConflictResolutionStrategy (LWW vs KeepBoth vs ThreeWayMerge) | Swap conflict policy without changing sync service |
| **Observer** | File change -> SyncService -> device notifications | Decouple file operations from sync delivery |
| **Factory** | ChunkingStrategyFactory, SyncStrategyFactory | Encapsulate strategy selection, single creation point |
| **Repository** | FileRepository, ChunkRepository, FolderRepository | Abstract storage: swap RDS/DynamoDB/S3 implementations |
| **Template Method** | Upload pipeline: chunk -> dedup -> store -> version -> index -> notify | Fixed sequence; strategies customize individual steps |
| **Iterator** | Cursor-based sync: getChanges(cursor) returns next batch + new cursor | Stateless pagination through an append-only change log |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :15-file-storage:run
```

---

## Demo Output Preview

```
========================================
  FILE STORAGE SYSTEM (GOOGLE DRIVE/DROPBOX) DEMO
========================================

--- File Upload Demo ---
User U001 (Alice) uploads "presentation.pptx" (100 MB) to folder "Work"

  Step 1: Client-side chunking
    File size: 104,857,600 bytes (100 MB)
    Chunk size: 4,194,304 bytes (4 MB)
    Total chunks: 25
    Hashing chunks with SHA-256...
      Chunk  0: hash = a3f2b8c1... (4 MB)
      Chunk  1: hash = 7c91e4d2... (4 MB)
      Chunk  2: hash = d5a01733... (4 MB)
      ...
      Chunk 24: hash = 1b8e33f0... (4 MB)

  Step 2: Deduplication check
    Sending 25 chunk hashes to server...
    Server response:
      Already exists (skip upload): 17 chunks  [68% dedup hit!]
      Needs upload:                  8 chunks
    Bandwidth saved: 68 MB out of 100 MB (68% reduction)

  Step 3: Parallel chunk upload (4 concurrent)
    Uploading chunk 7c91e4d2... -> s3://chunks/7c91e4d2...  [4 MB] OK (0.6s)
    Uploading chunk 1b8e33f0... -> s3://chunks/1b8e33f0...  [4 MB] OK (0.6s)
    Uploading chunk e2f19a87... -> s3://chunks/e2f19a87...  [4 MB] OK (0.6s)
    Uploading chunk 44bc01de... -> s3://chunks/44bc01de...  [4 MB] OK (0.5s)
    ... (8 chunks, 2 batches of 4)
    Total upload time: 1.2 seconds (vs 16s without chunking + dedup)

  Step 4: File registered
    File{id='file_001', name='presentation.pptx', size=104857600, version=1}
    Folder: Work/ -> [presentation.pptx]
    Chunks: 25 (17 deduplicated, 8 new)
    Thumbnail generated: thumb_256.jpg

  Step 5: Sync notification
    Published: { event: FILE_CREATED, fileId: file_001 }
    Notified devices: [Alice's phone, Alice's tablet]

--- Delta Sync Demo ---
Alice edits "presentation.pptx" on laptop (changes 2 slides)

  Re-chunking modified file...
    Old chunks: [a3f2b8..., 7c91e4..., d5a017..., ..., 1b8e33...]  (25 chunks)
    New chunks: [a3f2b8..., 7c91e4..., XX1111..., ..., XX2222...]  (25 chunks)
    Changed: 2 chunks out of 25

  Delta upload: 2 chunks (8 MB) instead of full file (100 MB)
    Upload chunk XX1111... -> OK
    Upload chunk XX2222... -> OK
    Upload time: 0.3 seconds (vs 16s for full file)

  New version created:
    FileVersion{fileId='file_001', version=2, chunks=[a3f2b8..., 7c91e4..., XX1111..., ...]}
    Version 1 preserved: [a3f2b8..., 7c91e4..., d5a017..., ..., 1b8e33...]
    Shared chunks between v1 and v2: 23 out of 25 (copy-on-write)

  Sync to phone (long polling):
    Phone receives: { event: FILE_UPDATED, fileId: file_001, version: 2 }
    Phone compares chunk lists:
      Already have locally: 23 chunks -> SKIP
      Need to download: 2 chunks (XX1111..., XX2222...)
    Download: 8 MB instead of 100 MB (92% savings)
    File reassembled on phone. Sync complete.

--- Deduplication Demo ---
User U002 (Bob) uploads the SAME "presentation.pptx" file

  Chunking: 25 chunks, all hashes match existing chunks
  Dedup check: ALL 25 chunks already exist in storage
  Upload: 0 bytes transferred!
  Metadata only: new file entry pointing to existing chunks
    Chunk ref_count updates:
      a3f2b8... ref_count: 1 -> 2
      7c91e4... ref_count: 1 -> 2
      ... (all 25 chunks ref_count incremented)

  Storage used by Bob's copy: 0 bytes additional (metadata only ~500 bytes)
  Without dedup: would have stored 100 MB again.

--- Conflict Resolution Demo ---
Alice edits presentation.pptx on laptop (offline, based on v2)
Alice also edits on phone (offline, based on v2)

  Laptop comes online first:
    Upload delta (3 changed chunks) -> version 3 created

  Phone comes online:
    Upload delta (2 changed chunks), oldVersion=2
    Server: current version is 3, expected 2 -> CONFLICT DETECTED

  Resolution: Keep-Both (Dropbox strategy)
    Created: "presentation.pptx" (laptop version, v3)
    Created: "presentation (conflict copy).pptx" (phone version)
    Alice notified: "Conflicting changes detected. Please review."

  Alternative: Last-Writer-Wins (Google Drive strategy)
    Phone version would overwrite laptop version as v4
    Laptop changes to chunks not modified by phone: preserved (non-overlapping)
    Laptop changes to chunks ALSO modified by phone: LOST (phone wins)

--- Version History Demo ---
Alice requests version history for presentation.pptx:

  Versions:
    v1: 2026-04-26 10:00 - Created (25 chunks, 100 MB)
    v2: 2026-04-26 14:30 - Edited 2 slides (2 chunks changed)
    v3: 2026-04-26 16:00 - Edited 3 slides (3 chunks changed)

  Storage efficiency:
    Naive (full copies): 3 versions * 100 MB = 300 MB
    With chunk sharing:  100 MB + 8 MB + 12 MB = 120 MB (60% savings)

  Rollback to v1:
    Load chunk list for v1: [a3f2b8..., 7c91e4..., d5a017..., ...]
    All chunks still in S3 (ref_count > 0)
    Reassemble file from v1 chunks -> download
    Or: create v4 with v1's chunk list (rollback = new version pointing to old chunks)

--- Folder Hierarchy Demo (Composite Pattern) ---
Alice's file tree:

  Root/
    |-- Work/
    |     |-- presentation.pptx (100 MB)
    |     |-- report.docx (20 MB)
    |     |-- Designs/
    |           |-- mockup.fig (50 MB)
    |-- Personal/
          |-- vacation.jpg (5 MB)

  Composite operations:
    Root.getSize()   -> 175 MB (recursive sum)
    Work.getSize()   -> 170 MB (presentation + report + mockup)
    Root.delete()    -> recursively deletes all files, decrements chunk ref_counts
    Work.share(Bob, EDITOR) -> Bob gains EDITOR access to Work/ and all children

========================================
  DEMO COMPLETE -- PROJECT 15 FINISHED!
  SYSTEM DESIGN INTERVIEW PREP: 15/15 COMPLETE!
========================================
```

---

## Quick Reference

```
Chunking:           Split files into 4MB blocks. SHA-256 hash per chunk. Upload in parallel (4 concurrent). Resume on failure.
Deduplication:      hash(chunk) = S3 key. Content-addressable. Same content stored once. Ref counting. 30-50% storage savings.
Delta sync:         Only transfer changed chunks. Edit 100MB file, change 1 slide = 4MB upload. 96% bandwidth savings.
Versioning:         Version = list of chunk hashes. Unchanged chunks shared (copy-on-write). Max 30 versions. Rollback = new version with old chunk list.
Conflict:           LWW (Google Drive): simple, risks data loss. Keep-both (Dropbox): safe, clutters folder. Three-way merge: smart, complex.
Sync:               Long polling (60s timeout). Cursor-based: "give me changes since cursor X." Server pushes immediately on change.
Folder hierarchy:   Composite pattern. Folder contains Files + Folders. Recursive size/delete/share/move.
Storage:            S3 (11 nines durability). Standard for active, IA after 30 days, Glacier after 90 days.
Metadata:           RDS Aurora (file tree, sharing, permissions) + DynamoDB (chunk index, dedup, versions).
Upload pipeline:    Client chunks -> dedup check -> presigned URL -> parallel S3 upload -> Step Functions orchestration -> sync notify.
Encryption:         SSE-KMS (server-side default). Client-side optional (breaks dedup -- encrypted chunks never match).
Rabin fingerprint:  Content-defined chunking. Variable-size chunks (avg 4MB). Better dedup for files with insertions. Dropbox uses this.
CAP:                CP for metadata (file tree consistency). AP for sync notifications (2s delay OK). CP for version history.
Magic Pocket:       Dropbox's custom storage (2016). Erasure coding, 1.5x overhead (vs S3's 3x). Saves 50% at exabyte scale.
```

---

## What to Improve Later

- [ ] Full File and Folder entities with Composite pattern (FileSystemNode interface, recursive operations)
- [ ] FixedSizeChunkingStrategy with 4MB block splitting and SHA-256 hashing
- [ ] RabinFingerprintChunkingStrategy with content-defined chunk boundaries
- [ ] HashBasedDeduplicationStrategy with DynamoDB dedup index and reference counting
- [ ] LongPollingSyncStrategy with cursor-based change tracking and 60s timeout
- [ ] LastWriterWinsStrategy and KeepBothStrategy for conflict resolution
- [ ] ThreeWayMergeStrategy with chunk-level merge against common ancestor
- [ ] FileVersion management with copy-on-write chunk sharing
- [ ] Upload pipeline: chunk -> dedup check -> presigned URL -> store -> index -> notify
- [ ] Folder sharing with permission propagation (Composite pattern)
- [ ] Storage tiering simulation (Standard -> IA -> Glacier lifecycle)
- [ ] Offline edit queue with conflict detection on reconnect
