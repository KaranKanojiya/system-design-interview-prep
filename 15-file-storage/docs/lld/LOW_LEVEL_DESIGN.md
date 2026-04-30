# Low-Level Design: File Storage System (Google Drive / Dropbox)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Chunked Uploads, Content-Addressable Storage, Deduplication, Sync Conflicts, Version History, Sharing
> This is the distributed storage interview question. It tests your understanding of chunking
> strategies, content-addressable dedup, resumable uploads, sync conflict resolution, file
> versioning, hierarchical folder trees, permission-controlled sharing, and storage quota
> management -- all with concurrency-safe design.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Service Layer Design](#7-service-layer-design)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [SOLID Principles Applied](#9-solid-principles-applied)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: FileMetadata (Builder, fileId, name, path, size, hash, mimeType, ownerId, versions, isDirectory), FileChunk (chunkId, fileId, chunkIndex, hash SHA-256, sizeBytes, storageKey), FileVersion (versionId, fileId, versionNumber, chunkHashes, size, createdAt, createdBy), Folder (folderId, name, parentId, ownerId, children), ShareLink (linkId, fileId, permission, expiresAt, password optional, createdBy), SharePermission (enum: VIEW, DOWNLOAD, EDIT), SyncEvent (eventId, userId, fileId, eventType, timestamp, deviceId), SyncEventType (enum: CREATED, MODIFIED, DELETED, MOVED, RENAMED), StorageQuota (userId, usedBytes, totalBytes, fileCount), User (userId, name, email, storageQuota). |
| **Strategy (Chunking)** | `strategy/chunking/` | Pluggable file chunking: ChunkingStrategy interface with FixedSizeChunking (split into fixed 4MB chunks -- simple, predictable) and ContentDefinedChunking (Rabin fingerprint variable-size chunks -- dedup-friendly, only changed regions re-upload). Strategy pattern -- swap chunking algorithm without touching services. |
| **Strategy (Dedup)** | `strategy/dedup/` | Pluggable deduplication: DeduplicationStrategy interface with HashBasedDedup (SHA-256 hash -> content-addressable storage, store once reference many) and NoDedup (always stores, baseline comparison). Across-user dedup saves 30-50% storage in production. |
| **Strategy (Sync)** | `strategy/sync/` | Pluggable conflict resolution: ConflictStrategy interface with LastWriterWinsStrategy (most recent timestamp wins -- simple, Dropbox legacy) and KeepBothStrategy (rename conflicting file with "(conflict)" suffix -- Dropbox current model, no data loss). |
| **Service** | `service/` | Business logic: FileStorageService (Facade -- upload, download, sync, share, version), UploadService (chunked upload, resumable, checksum verification), DownloadService (reassemble chunks, range requests), MetadataService (file/folder CRUD, hierarchy, search), DeduplicationService (hash checking, reference counting), SyncService (change detection, push updates, conflict resolution), VersionService (create version, list versions, rollback, diff), SharingService (share links, permissions, access control), TrashService (soft delete, recovery, permanent purge). |
| **Store** | `store/` | Content-addressable block storage: BlockStore interface with InMemoryBlockStore (Map<hash, byte[]>). Blocks are immutable -- same hash always maps to same bytes. |
| **Repository** | `repository/` | Data access layer: FileRepository, FolderRepository, VersionRepository, UserRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like entry point: FileStorageController maps requests to FileStorageService. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | StorageStatsDisplay: storage usage, dedup savings, file counts, quota utilization. |
| **Exception** | `exception/` | Domain exceptions: FileStorageException (base), FileNotFoundException, StorageQuotaExceededException, ChunkMismatchException, PermissionDeniedException. |

### Why File Storage Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you chunk files for upload? (Not send the whole file at once)    --> Chunking
  2. Can uploads resume after failure? (Not restart from scratch)        --> Resumable Upload
  3. Do you deduplicate identical content? (SHA-256 content-addressable) --> Dedup
  4. How do you handle two devices editing the same file?                --> Sync Conflicts
  5. Is file versioning modeled? (Not just overwrite)                    --> Version History
  6. Are chunk/dedup/sync strategies pluggable?                          --> Strategy Pattern
  7. Is FileStorageService a clean Facade over sub-services?             --> Facade Pattern
  8. Do you separate metadata from block storage?                        --> Separation of Concerns
  9. Can you add S3 storage without changing services?                   --> Open-Closed
  10. Is concurrent upload/download thread-safe?                          --> Concurrency
```

---

## 2. Package Structure

```
com.systemdesign.filestorage
|
+-- model/
|   +-- FileMetadata.java        -- Builder, fileId, name, path, size, hash, mimeType, ownerId, versions, isDirectory
|   +-- FileChunk.java           -- chunkId, fileId, chunkIndex, hash (SHA-256), sizeBytes, storageKey
|   +-- FileVersion.java         -- versionId, fileId, versionNumber, chunkHashes, size, createdAt, createdBy
|   +-- Folder.java              -- folderId, name, parentId, ownerId, children (files + subfolders)
|   +-- ShareLink.java           -- linkId, fileId, permission, expiresAt, password (optional), createdBy
|   +-- SharePermission.java     -- enum: VIEW, DOWNLOAD, EDIT
|   +-- SyncEvent.java           -- eventId, userId, fileId, eventType, timestamp, deviceId
|   +-- SyncEventType.java       -- enum: CREATED, MODIFIED, DELETED, MOVED, RENAMED
|   +-- StorageQuota.java        -- userId, usedBytes, totalBytes, fileCount
|   +-- User.java                -- userId, name, email, storageQuota
|
+-- strategy/
|   +-- chunking/
|   |   +-- ChunkingStrategy.java       -- interface: chunk(byte[] data, int chunkSize) -> List<FileChunk>
|   |   +-- FixedSizeChunking.java      -- split into fixed 4MB chunks
|   |   +-- ContentDefinedChunking.java -- Rabin fingerprint variable-size chunks (dedup-friendly)
|   |
|   +-- dedup/
|   |   +-- DeduplicationStrategy.java  -- interface: isDuplicate(hash), store(chunk), getStorageKey(hash)
|   |   +-- HashBasedDedup.java         -- SHA-256 hash -> content-addressable storage
|   |   +-- NoDedup.java               -- always stores (baseline comparison)
|   |
|   +-- sync/
|       +-- ConflictStrategy.java       -- interface: resolve(local, remote) -> resolution
|       +-- LastWriterWinsStrategy.java  -- most recent timestamp wins
|       +-- KeepBothStrategy.java        -- rename conflicting file with "(conflict)" suffix
|
+-- service/
|   +-- FileStorageService.java   -- FACADE: upload, download, sync, share, version
|   +-- UploadService.java        -- chunked upload, resumable, checksum verification
|   +-- DownloadService.java      -- reassemble chunks, range requests
|   +-- MetadataService.java      -- file/folder CRUD, hierarchy, search
|   +-- DeduplicationService.java -- hash checking, reference counting
|   +-- SyncService.java          -- change detection, push updates, conflict resolution
|   +-- VersionService.java       -- create version, list versions, rollback, diff
|   +-- SharingService.java       -- share links, permissions, access control
|   +-- TrashService.java         -- soft delete, recovery, permanent purge
|
+-- store/
|   +-- BlockStore.java           -- interface: storeBlock(hash, data), getBlock(hash), deleteBlock(hash), exists(hash)
|   +-- InMemoryBlockStore.java   -- content-addressable: Map<hash, byte[]>
|
+-- repository/
|   +-- FileRepository.java, InMemoryFileRepository.java
|   +-- FolderRepository.java, InMemoryFolderRepository.java
|   +-- VersionRepository.java, InMemoryVersionRepository.java
|   +-- UserRepository.java, InMemoryUserRepository.java
|
+-- controller/
|   +-- FileStorageController.java
|
+-- config/
|   +-- AppConfig.java
|
+-- display/
|   +-- StorageStatsDisplay.java
|
+-- exception/
|   +-- FileStorageException.java
|   +-- FileNotFoundException.java
|   +-- StorageQuotaExceededException.java
|   +-- ChunkMismatchException.java
|   +-- PermissionDeniedException.java
|
+-- FileStorageApp.java  -- Main demo: wires everything, runs file storage scenarios
```

---

## 3. Class Diagram

```
+=====================================================================+
|             THE CORE PROBLEM: LARGE FILE STORAGE AT SCALE            |
+=====================================================================+

  Client (Laptop)         Cloud Storage           Client (Phone)
      |                       |                        |
      |--- Upload photo.jpg   |                        |
      |    (12MB) ----------->|                        |
      |                       |                        |
      |    Split into chunks: |                        |
      |    [chunk0: 4MB]      |                        |
      |    [chunk1: 4MB]      |                        |
      |    [chunk2: 4MB]      |                        |
      |                       |                        |
      |    SHA-256 each chunk |                        |
      |    hash0: "a3f2..."   |  Check: hash exists?   |
      |    hash1: "b7c1..."   |  hash0: NO  -> store   |
      |    hash2: "d4e9..."   |  hash1: YES -> dedup!  |
      |                       |  hash2: NO  -> store   |
      |                       |                        |
      |                       |--- Sync event -------->|
      |                       |    "photo.jpg created" |
      |                       |                        |
      |                       |    Phone downloads:    |
      |                       |    Reassemble chunks   |
      |                       |    hash0 + hash1 + hash2
      |                       |    = original 12MB file|
      |                       |                        |

  Without chunking: 12MB upload fails at 11MB -> restart from 0.
  With chunking: 12MB upload fails at 11MB -> resume from chunk2 only.
  With dedup: chunk1 already stored by another user -> skip upload, save 4MB.


+=====================================================================+
|     ANTI-PATTERN: NAIVE FILE STORAGE (DO NOT DO THIS)                |
+=====================================================================+

  // --- BAD: NaiveFileStorageService.java ---
  //
  // This is how a junior developer might build file storage.
  // It "works" for small files on localhost. It BREAKS in production.
  //
  //   class NaiveFileStorageService {
  //       private final Map<String, byte[]> files = new HashMap<>();  // <-- NOT thread-safe
  //
  //       public void upload(String fileName, byte[] data) {
  //           // Problem 1: Entire file in memory. 2GB video = OOM.
  //           // Problem 2: No chunking. Network drops at 99% -> restart from 0%.
  //           // Problem 3: No dedup. Same file uploaded by 100 users = 100 copies.
  //           // Problem 4: No versioning. Overwrite = data loss.
  //           // Problem 5: HashMap not thread-safe. Concurrent uploads corrupt state.
  //           // Problem 6: No metadata separated from data. Can't search without loading bytes.
  //           // Problem 7: No quota management. One user fills the disk.
  //           files.put(fileName, data);  // <-- stores ENTIRE file as one blob
  //       }
  //
  //       public byte[] download(String fileName) {
  //           return files.get(fileName);  // <-- loads ENTIRE file into memory
  //       }
  //   }

  Timeline showing WHY naive storage fails:

  t=0   User uploads "presentation.pptx" (50MB)
        Server: allocates 50MB byte array in heap
        Heap usage: 50MB

  t=1   10 concurrent users upload 50MB files
        Server: allocates 10 x 50MB = 500MB simultaneously
        Heap usage: 500MB (approaching OOM)

  t=2   User's network drops at 45MB uploaded
        Server: discards incomplete upload (no resumption)
        User must start over. On mobile, this is a deal-breaker.

  t=3   100 users upload the same "company-logo.png" (5MB)
        Server: 100 x 5MB = 500MB for identical bytes
        With dedup: 5MB + 99 references = ~5MB total

  +----------------------------------------------------------------+
  |  INTERVIEW RED FLAG: If you describe "store whole file as blob" |
  |  approach, the interviewer knows you don't understand the core  |
  |  challenges. Files must be CHUNKED, content-ADDRESSED, and     |
  |  metadata must be SEPARATED from block storage.                |
  +----------------------------------------------------------------+


+=====================================================================+
|          CLEAN SOLUTION: CHUNKED CONTENT-ADDRESSABLE STORAGE         |
+=====================================================================+

  +-------------------------------------------------------------------+
  |            <<interface>>  ChunkingStrategy                          |
  |-------------------------------------------------------------------|
  | + chunk(data: byte[], chunkSize: int): List<FileChunk>             |
  | + getStrategyName(): String                                        |
  +-------------------------------------------------------------------+
        ^                                    ^
        |                                    |
   implements                           implements
        |                                    |
  +-----+------------------+   +-------------+------------------+
  | FixedSizeChunking      |   | ContentDefinedChunking         |
  |------------------------|   |--------------------------------|
  | -defaultChunkSize: int |   | -windowSize: int               |
  |  (4MB)                 |   | -rabinPoly: long               |
  |------------------------|   |--------------------------------|
  | chunk():               |   | chunk():                       |
  |  Split into equal-size |   |  Rabin fingerprint rolling     |
  |  blocks. Last block    |   |  hash. Split at content-       |
  |  may be smaller.       |   |  defined boundaries.           |
  |  Simple. Predictable.  |   |  Avg 4MB, range [2MB, 8MB].   |
  |                        |   |                                |
  |  Drawback: insert 1    |   |  Key advantage: insert 1      |
  |  byte at start -> ALL  |   |  byte at start -> only the    |
  |  chunks change hash.   |   |  boundary chunk changes.       |
  |  Bad for dedup.        |   |  Other chunks keep same hash.  |
  |                        |   |  IDEAL for dedup.              |
  |  Used by: simple       |   |  Used by: Dropbox, rsync,     |
  |  backup tools          |   |  Google Drive (modified)       |
  +------------------------+   +--------------------------------+


  +-------------------------------------------------------------------+
  |            <<interface>>  DeduplicationStrategy                     |
  |-------------------------------------------------------------------|
  | + isDuplicate(hash: String): boolean                               |
  | + store(chunk: FileChunk, data: byte[]): String  (storageKey)      |
  | + getStorageKey(hash: String): String                              |
  | + getStrategyName(): String                                        |
  +-------------------------------------------------------------------+
        ^                                    ^
        |                                    |
   implements                           implements
        |                                    |
  +-----+------------------+   +-------------+------------------+
  | HashBasedDedup         |   | NoDedup                        |
  |------------------------|   |--------------------------------|
  | -hashIndex:            |   |                                |
  |  Map<hash, storageKey> |   | isDuplicate():                 |
  | -refCount:             |   |   return false (always store)  |
  |  Map<hash, int>        |   |                                |
  |------------------------|   | store():                       |
  | isDuplicate():         |   |   Store unconditionally.       |
  |  Check if hash exists  |   |   Return unique key.           |
  |  in index.             |   |                                |
  |                        |   | Use case: testing, compliance  |
  | store():               |   | where dedup is not permitted   |
  |  If exists: increment  |   | (e.g., legal hold requires    |
  |    refCount, return    |   |  per-user copies).             |
  |    existing storageKey |   |                                |
  |  If new: store in      |   |                                |
  |    BlockStore, add to  |   |                                |
  |    index, refCount = 1 |   |                                |
  |                        |   |                                |
  | Dropbox reported 75%   |   |                                |
  | storage savings from   |   |                                |
  | cross-user dedup.      |   |                                |
  +------------------------+   +--------------------------------+


  +-------------------------------------------------------------------+
  |            <<interface>>  ConflictStrategy                          |
  |-------------------------------------------------------------------|
  | + resolve(local: FileMetadata, remote: FileMetadata): Resolution   |
  | + getStrategyName(): String                                        |
  +-------------------------------------------------------------------+
        ^                                    ^
        |                                    |
   implements                           implements
        |                                    |
  +-----+------------------+   +-------------+------------------+
  | LastWriterWinsStrategy |   | KeepBothStrategy               |
  |------------------------|   |--------------------------------|
  | resolve():             |   | resolve():                     |
  |  Compare timestamps.   |   |  Rename the losing file:       |
  |  Most recent wins.     |   |  "report.docx" becomes         |
  |  Older version becomes |   |  "report (conflict from        |
  |  a previous version.   |   |   Alice's Laptop).docx"        |
  |                        |   |                                |
  |  PRO: simple, no user  |   |  PRO: zero data loss, user     |
  |  intervention needed.  |   |  decides which to keep.        |
  |                        |   |                                |
  |  CON: silent data loss  |   |  CON: confusing file clutter.  |
  |  if user doesn't check |   |  User must manually merge.     |
  |  version history.      |   |                                |
  |                        |   |  Used by: Dropbox (current),   |
  |  Used by: iCloud Drive |   |  OneDrive, Google Drive (files)|
  +------------------------+   +--------------------------------+


+=====================================================================+
|              FULL CLASS DEPENDENCY DIAGRAM                            |
+=====================================================================+

  FileStorageController
       |
       v
  FileStorageService  -------- <<Facade>>
       |
       +----> UploadService ------------> BlockStore
       |           |                          ^
       |           +----> ChunkingStrategy    |
       |           +----> DeduplicationService +
       |           +----> MetadataService
       |
       +----> DownloadService -----------> BlockStore
       |           |
       |           +----> MetadataService (get chunk list)
       |
       +----> MetadataService -----------> FileRepository
       |           |                       FolderRepository
       |           +----> StorageQuota tracking
       |
       +----> DeduplicationService ------> BlockStore
       |           |                       DeduplicationStrategy
       |           +----> ref counting
       |
       +----> SyncService ---------------> SyncEvent log
       |           |
       |           +----> ConflictStrategy (LastWriterWins or KeepBoth)
       |           +----> MetadataService (detect changes)
       |
       +----> VersionService ------------> VersionRepository
       |           |
       |           +----> MetadataService (snapshot chunk lists)
       |
       +----> SharingService
       |           |
       |           +----> Permission checks, share link generation
       |
       +----> TrashService
                   |
                   +----> Soft delete (metadata flag), recovery, purge


  AppConfig (wires everything)
       |
       +----> creates Repository instances (InMemory*)
       +----> creates BlockStore (InMemoryBlockStore)
       +----> creates ChunkingStrategy (FixedSizeChunking or ContentDefinedChunking)
       +----> creates DeduplicationStrategy (HashBasedDedup)
       +----> creates ConflictStrategy (KeepBothStrategy)
       +----> creates Services (injected with repos + strategies + BlockStore)
       +----> creates FileStorageService (injected with all services)
       +----> creates Controller (injected with FileStorageService)
```

---

## 4. Entity Design

### 4.1 FileMetadata.java (Builder Pattern)

```java
/**
 * Core metadata entity for a file (or directory). Metadata is SEPARATED from
 * actual file bytes -- this is the fundamental architectural decision.
 *
 * WHY SEPARATE METADATA FROM CONTENT?
 *   - Listing a folder with 1000 files should NOT load 1000 file blobs.
 *   - Searching by name/type/date operates on metadata only.
 *   - Metadata fits in a database (Postgres, DynamoDB). Blobs go to object storage (S3).
 *   - Metadata is small (< 1KB per file). Blobs can be gigabytes.
 *
 * Builder pattern because FileMetadata has 10+ fields, many optional.
 *
 * INTERVIEW TIP: Google Drive stores metadata in Spanner (relational) and
 * file bytes in Colossus (distributed file system). Never store blobs in
 * a relational database. Interviewers flag this immediately.
 */
public class FileMetadata {
    private final String fileId;               // UUID, immutable after creation
    private String name;                       // mutable: rename operations
    private String path;                       // mutable: move operations  "/docs/report.pdf"
    private long size;                         // total file size in bytes
    private String hash;                       // SHA-256 of entire file content
    private String mimeType;                   // "application/pdf", "image/png", etc.
    private final String ownerId;              // who created this file
    private final List<String> versionIds;     // ordered version IDs, latest last
    private final boolean isDirectory;         // true for folders (size=0, no chunks)
    private boolean isTrashed;                 // soft delete flag
    private Instant createdAt;
    private Instant lastModifiedAt;
    private String lastModifiedBy;             // userId who last modified

    // ---- Builder ----
    public static class Builder {
        private String fileId;
        private String name;
        private String path = "/";
        private long size = 0;
        private String hash;
        private String mimeType = "application/octet-stream";
        private String ownerId;
        private boolean isDirectory = false;

        public Builder fileId(String fileId)       { this.fileId = fileId; return this; }
        public Builder name(String name)           { this.name = name; return this; }
        public Builder path(String path)           { this.path = path; return this; }
        public Builder size(long size)             { this.size = size; return this; }
        public Builder hash(String hash)           { this.hash = hash; return this; }
        public Builder mimeType(String mimeType)   { this.mimeType = mimeType; return this; }
        public Builder ownerId(String ownerId)     { this.ownerId = ownerId; return this; }
        public Builder isDirectory(boolean dir)    { this.isDirectory = dir; return this; }

        public FileMetadata build() {
            Objects.requireNonNull(fileId, "fileId is required");
            Objects.requireNonNull(name, "name is required");
            Objects.requireNonNull(ownerId, "ownerId is required");
            return new FileMetadata(this);
        }
    }

    private FileMetadata(Builder builder) {
        this.fileId = builder.fileId;
        this.name = builder.name;
        this.path = builder.path;
        this.size = builder.size;
        this.hash = builder.hash;
        this.mimeType = builder.mimeType;
        this.ownerId = builder.ownerId;
        this.isDirectory = builder.isDirectory;
        this.isTrashed = false;
        this.versionIds = new ArrayList<>();
        this.createdAt = Instant.now();
        this.lastModifiedAt = this.createdAt;
        this.lastModifiedBy = builder.ownerId;
    }

    // --- Mutation methods ---

    public void rename(String newName) {
        this.name = newName;
        this.lastModifiedAt = Instant.now();
    }

    public void moveTo(String newPath) {
        this.path = newPath;
        this.lastModifiedAt = Instant.now();
    }

    public void updateContent(long newSize, String newHash, String modifiedBy) {
        this.size = newSize;
        this.hash = newHash;
        this.lastModifiedBy = modifiedBy;
        this.lastModifiedAt = Instant.now();
    }

    public void addVersion(String versionId) {
        this.versionIds.add(versionId);
    }

    public void trash()   { this.isTrashed = true; this.lastModifiedAt = Instant.now(); }
    public void restore() { this.isTrashed = false; this.lastModifiedAt = Instant.now(); }

    /**
     * Full path including filename: "/docs/reports/quarterly.pdf"
     */
    public String getFullPath() {
        if (path.endsWith("/")) return path + name;
        return path + "/" + name;
    }

    // --- Getters ---
    public String getFileId()          { return fileId; }
    public String getName()            { return name; }
    public String getPath()            { return path; }
    public long getSize()              { return size; }
    public String getHash()            { return hash; }
    public String getMimeType()        { return mimeType; }
    public String getOwnerId()         { return ownerId; }
    public List<String> getVersionIds() { return Collections.unmodifiableList(versionIds); }
    public boolean isDirectory()       { return isDirectory; }
    public boolean isTrashed()         { return isTrashed; }
    public Instant getCreatedAt()      { return createdAt; }
    public Instant getLastModifiedAt() { return lastModifiedAt; }
    public String getLastModifiedBy()  { return lastModifiedBy; }
}
```

### 4.2 FileChunk.java

```java
/**
 * Represents a single chunk of a file in content-addressable storage.
 *
 * KEY DESIGN DECISION: Files are NOT stored as single blobs.
 * A 100MB file is split into ~25 chunks of 4MB each.
 *
 * WHY CHUNK?
 *   1. RESUMABLE UPLOAD: If chunk 20 fails, re-upload chunk 20 only (not 80MB).
 *   2. DEDUPLICATION: Identical chunks across files share storage.
 *      If 100 users have the same 4MB logo, it's stored ONCE.
 *   3. PARALLEL TRANSFER: Upload/download 4 chunks simultaneously = 4x throughput.
 *   4. DELTA SYNC: Edit 1 byte in a 100MB file -> only the affected chunk re-uploads.
 *      With content-defined chunking, this is usually 1 of 25 chunks.
 *
 * storageKey: The key in BlockStore. For HashBasedDedup, this IS the hash.
 *             For other backends, it could be an S3 key or filesystem path.
 *
 * INTERVIEW TIP: Dropbox's "Infinite Storage" breakthrough came from chunk-level
 * dedup. A company with 10,000 employees sharing the same onboarding PDF saves
 * 9,999 copies. That's real money at scale.
 */
public class FileChunk {
    private final String chunkId;          // UUID, unique per chunk instance
    private final String fileId;           // which file this chunk belongs to
    private final int chunkIndex;          // 0-based position in the file
    private final String hash;             // SHA-256 of chunk bytes (content-addressable key)
    private final int sizeBytes;           // actual size of this chunk in bytes
    private final String storageKey;       // key in BlockStore (often == hash)

    public FileChunk(String chunkId, String fileId, int chunkIndex,
                     String hash, int sizeBytes, String storageKey) {
        this.chunkId = chunkId;
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.hash = hash;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
    }

    public String getChunkId()    { return chunkId; }
    public String getFileId()     { return fileId; }
    public int getChunkIndex()    { return chunkIndex; }
    public String getHash()       { return hash; }
    public int getSizeBytes()     { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
}
```

### 4.3 FileVersion.java

```java
/**
 * Snapshot of a file at a specific version. Stores the LIST OF CHUNK HASHES,
 * NOT the actual bytes. This is the key insight for efficient versioning.
 *
 * WHY STORE CHUNK HASHES, NOT BYTES?
 *   Version 1: [hash_A, hash_B, hash_C, hash_D]  (16MB file, 4 chunks)
 *   Version 2: [hash_A, hash_B, hash_E, hash_D]  (user edited chunk 2)
 *
 *   Chunks A, B, D are SHARED between versions. Only chunk E is new.
 *   Storage cost of version 2: 4MB (chunk E) + 4 hash pointers.
 *   Without chunk sharing: 16MB per version. 50 versions = 800MB.
 *   With chunk sharing: 16MB + 49 * ~4MB (average) = ~212MB.
 *
 * INTERVIEW TIP: Git uses the same principle (content-addressable objects).
 * Say "file versions reference chunks by hash, so unchanged chunks are
 * shared across versions -- same principle as Git's object store."
 */
public class FileVersion {
    private final String versionId;             // UUID
    private final String fileId;
    private final int versionNumber;            // monotonically increasing: 1, 2, 3...
    private final List<String> chunkHashes;     // ordered list of chunk hashes for this version
    private final long size;                    // total file size at this version
    private final Instant createdAt;
    private final String createdBy;             // userId who created this version

    public FileVersion(String versionId, String fileId, int versionNumber,
                       List<String> chunkHashes, long size, String createdBy) {
        this.versionId = versionId;
        this.fileId = fileId;
        this.versionNumber = versionNumber;
        this.chunkHashes = new ArrayList<>(chunkHashes);
        this.size = size;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    /**
     * Compute the diff between this version and another.
     * Returns the hashes that are DIFFERENT (changed/added chunks).
     * Used by delta sync: only transfer the changed chunks.
     */
    public List<String> diffFrom(FileVersion other) {
        List<String> changedHashes = new ArrayList<>();
        int maxLen = Math.max(this.chunkHashes.size(), other.chunkHashes.size());

        for (int i = 0; i < maxLen; i++) {
            String thisHash = i < this.chunkHashes.size() ? this.chunkHashes.get(i) : null;
            String otherHash = i < other.chunkHashes.size() ? other.chunkHashes.get(i) : null;

            if (!Objects.equals(thisHash, otherHash)) {
                if (thisHash != null) changedHashes.add(thisHash);
            }
        }
        return changedHashes;
    }

    public String getVersionId()          { return versionId; }
    public String getFileId()             { return fileId; }
    public int getVersionNumber()         { return versionNumber; }
    public List<String> getChunkHashes()  { return Collections.unmodifiableList(chunkHashes); }
    public long getSize()                 { return size; }
    public Instant getCreatedAt()         { return createdAt; }
    public String getCreatedBy()          { return createdBy; }
}
```

### 4.4 Folder.java

```java
/**
 * Hierarchical folder structure. Each folder knows its parent and children.
 *
 * TREE STRUCTURE:
 *   /                        (root, parentId = null)
 *   +-- Documents/           (parentId = root.folderId)
 *   |   +-- Work/            (parentId = Documents.folderId)
 *   |   +-- Personal/
 *   +-- Photos/
 *       +-- 2024/
 *       +-- 2025/
 *
 * WHY NOT JUST USE FILE PATHS?
 *   Path-based: rename "Documents" -> update paths for ALL descendants.
 *               For a folder with 10,000 files, that's 10,000 metadata updates.
 *   ID-based:   rename "Documents" -> update ONE record (the folder's name).
 *               Children reference parent by ID, which didn't change.
 *
 * Google Drive, Dropbox, and OneDrive all use ID-based hierarchies internally,
 * even though they DISPLAY path-like structures to users.
 */
public class Folder {
    private final String folderId;             // UUID
    private String name;                       // mutable: rename
    private String parentId;                   // null for root folder; mutable: move
    private final String ownerId;
    private final Set<String> childFileIds;    // file IDs directly in this folder
    private final Set<String> childFolderIds;  // subfolder IDs directly in this folder
    private final Instant createdAt;

    public Folder(String folderId, String name, String parentId, String ownerId) {
        this.folderId = folderId;
        this.name = name;
        this.parentId = parentId;
        this.ownerId = ownerId;
        this.childFileIds = new LinkedHashSet<>();
        this.childFolderIds = new LinkedHashSet<>();
        this.createdAt = Instant.now();
    }

    public void addFile(String fileId)         { childFileIds.add(fileId); }
    public void removeFile(String fileId)      { childFileIds.remove(fileId); }
    public void addSubfolder(String folderId)  { childFolderIds.add(folderId); }
    public void removeSubfolder(String folderId) { childFolderIds.remove(folderId); }
    public void rename(String newName)         { this.name = newName; }
    public void moveTo(String newParentId)     { this.parentId = newParentId; }

    /**
     * Total child count (files + subfolders). Used for display: "Documents (47 items)".
     */
    public int getChildCount() { return childFileIds.size() + childFolderIds.size(); }

    public String getFolderId()            { return folderId; }
    public String getName()                { return name; }
    public String getParentId()            { return parentId; }
    public String getOwnerId()             { return ownerId; }
    public Set<String> getChildFileIds()   { return Collections.unmodifiableSet(childFileIds); }
    public Set<String> getChildFolderIds() { return Collections.unmodifiableSet(childFolderIds); }
    public Instant getCreatedAt()          { return createdAt; }
}
```

### 4.5 ShareLink.java & SharePermission.java

```java
/**
 * A shareable link for a file or folder.
 *
 * Google Drive model: "Anyone with the link can view/edit"
 * Dropbox model: "Share link with optional password and expiry"
 *
 * SECURITY CONSIDERATIONS:
 *   - linkId is a UUID (not guessable, unlike sequential IDs)
 *   - Optional password for sensitive files
 *   - Expiration for temporary access (e.g., "link expires in 7 days")
 *   - Permission level: VIEW (read only), DOWNLOAD (can save locally), EDIT (full access)
 */
public class ShareLink {
    private final String linkId;               // UUID, forms part of the share URL
    private final String fileId;               // file or folder being shared
    private final SharePermission permission;
    private final Instant expiresAt;           // null = never expires
    private final String password;             // null = no password required
    private final String createdBy;            // userId who created the link
    private final Instant createdAt;
    private boolean isRevoked;                 // owner can revoke link

    public ShareLink(String linkId, String fileId, SharePermission permission,
                     Instant expiresAt, String password, String createdBy) {
        this.linkId = linkId;
        this.fileId = fileId;
        this.permission = permission;
        this.expiresAt = expiresAt;
        this.password = password;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.isRevoked = false;
    }

    /**
     * Check if this link is currently valid.
     * A link is invalid if it's revoked or expired.
     */
    public boolean isValid() {
        if (isRevoked) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        return true;
    }

    /**
     * Check if password matches (if password-protected).
     * Returns true if no password required OR password matches.
     */
    public boolean checkPassword(String providedPassword) {
        if (password == null) return true;
        return password.equals(providedPassword);
    }

    public void revoke() { this.isRevoked = true; }

    public String getLinkId()              { return linkId; }
    public String getFileId()              { return fileId; }
    public SharePermission getPermission() { return permission; }
    public Instant getExpiresAt()          { return expiresAt; }
    public boolean hasPassword()           { return password != null; }
    public String getCreatedBy()           { return createdBy; }
    public Instant getCreatedAt()          { return createdAt; }
    public boolean isRevoked()             { return isRevoked; }
}

/**
 * Permission levels for share links. Ordered from least to most privileged.
 *
 * VIEW:     Can see file metadata and preview (no download button)
 * DOWNLOAD: Can view + download a copy
 * EDIT:     Can view + download + modify the file (creates new version)
 */
public enum SharePermission {
    VIEW,
    DOWNLOAD,
    EDIT
}
```

### 4.6 SyncEvent.java & SyncEventType.java

```java
/**
 * Records a change event for sync across devices.
 *
 * SYNC MODEL: Event-sourced change log.
 *   Each device tracks a cursor (last eventId it processed).
 *   On reconnect, device asks "give me all events after cursor X."
 *   Server returns the delta: events the device hasn't seen yet.
 *
 * deviceId: Identifies WHICH device made the change.
 *   "alice-laptop" vs. "alice-phone" -- same user, different devices.
 *   Critical for conflict detection: if alice-laptop and alice-phone
 *   both edit the same file offline, that's a conflict.
 *
 * INTERVIEW TIP: Dropbox uses a "cursor" model for sync. Each client
 * maintains a cursor (event sequence number). On poll, the server
 * returns events after that cursor. This is exactly the event log pattern.
 */
public class SyncEvent {
    private final String eventId;              // UUID, globally unique
    private final String userId;
    private final String fileId;
    private final SyncEventType eventType;
    private final Instant timestamp;
    private final String deviceId;             // which device originated this change
    private final long sequenceNumber;         // monotonically increasing, for cursor tracking

    public SyncEvent(String eventId, String userId, String fileId,
                     SyncEventType eventType, String deviceId, long sequenceNumber) {
        this.eventId = eventId;
        this.userId = userId;
        this.fileId = fileId;
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.deviceId = deviceId;
        this.sequenceNumber = sequenceNumber;
    }

    public String getEventId()           { return eventId; }
    public String getUserId()            { return userId; }
    public String getFileId()            { return fileId; }
    public SyncEventType getEventType()  { return eventType; }
    public Instant getTimestamp()         { return timestamp; }
    public String getDeviceId()          { return deviceId; }
    public long getSequenceNumber()      { return sequenceNumber; }
}

/**
 * Types of sync events. Each maps to a user action.
 *
 * CREATED:  new file uploaded or new folder created
 * MODIFIED: file content changed (new version uploaded)
 * DELETED:  file moved to trash (soft delete)
 * MOVED:    file moved to different folder
 * RENAMED:  file name changed (but same location)
 */
public enum SyncEventType {
    CREATED,
    MODIFIED,
    DELETED,
    MOVED,
    RENAMED
}
```

### 4.7 StorageQuota.java

```java
/**
 * Tracks storage usage per user. Enforced on every upload.
 *
 * QUOTA MODEL:
 *   Free tier:  15GB (Google Drive), 2GB (Dropbox)
 *   Paid tier:  100GB, 2TB, etc.
 *
 * usedBytes: Sum of all file sizes owned by this user.
 *   NOTE: With dedup, usedBytes is the LOGICAL size (what user sees),
 *   not the physical storage. User uploaded 100MB -> quota shows 100MB used,
 *   even if 80MB was deduplicated and costs 20MB physically.
 *
 * WHY LOGICAL, NOT PHYSICAL?
 *   Users understand "I uploaded 100MB of files." They do NOT understand
 *   "You uploaded 100MB but we only stored 20MB because of dedup."
 *   Google Drive, Dropbox, and OneDrive all show logical usage.
 */
public class StorageQuota {
    private final String userId;
    private long usedBytes;                    // logical bytes used
    private long totalBytes;                   // quota limit
    private int fileCount;                     // total files owned

    public StorageQuota(String userId, long totalBytes) {
        this.userId = userId;
        this.totalBytes = totalBytes;
        this.usedBytes = 0;
        this.fileCount = 0;
    }

    /**
     * Check if uploading a file of 'size' bytes would exceed quota.
     * Called BEFORE starting the upload -- fail fast, don't waste bandwidth.
     */
    public boolean canUpload(long size) {
        return (usedBytes + size) <= totalBytes;
    }

    public void consumeBytes(long bytes) {
        this.usedBytes += bytes;
        this.fileCount++;
    }

    public void releaseBytes(long bytes) {
        this.usedBytes = Math.max(0, this.usedBytes - bytes);
        this.fileCount = Math.max(0, this.fileCount - 1);
    }

    public double getUsagePercentage() {
        if (totalBytes == 0) return 100.0;
        return (double) usedBytes / totalBytes * 100.0;
    }

    public long getRemainingBytes() { return Math.max(0, totalBytes - usedBytes); }

    public String getUserId()    { return userId; }
    public long getUsedBytes()   { return usedBytes; }
    public long getTotalBytes()  { return totalBytes; }
    public int getFileCount()    { return fileCount; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
}
```

### 4.8 User.java

```java
/**
 * User entity with associated storage quota.
 * Simple model -- authentication/password is out of scope for this design.
 */
public class User {
    private final String userId;               // UUID
    private final String name;
    private final String email;
    private final StorageQuota storageQuota;

    public User(String userId, String name, String email, long quotaBytes) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.storageQuota = new StorageQuota(userId, quotaBytes);
    }

    public String getUserId()            { return userId; }
    public String getName()              { return name; }
    public String getEmail()             { return email; }
    public StorageQuota getStorageQuota() { return storageQuota; }
}
```

---

## 5. Interface Contracts

### 5.1 ChunkingStrategy

```java
/**
 * Core interface for file chunking algorithms.
 *
 * CONTRACT:
 *   Given raw file bytes, split them into chunks. Each chunk gets a SHA-256 hash.
 *   Chunks can be stored, deduplicated, and reassembled independently.
 *
 * IMPLEMENTATIONS:
 *   - FixedSizeChunking:       split at fixed 4MB boundaries (simple, predictable)
 *   - ContentDefinedChunking:  split at content-defined boundaries using Rabin fingerprint
 *                              (variable size, dedup-friendly)
 *
 * INTERVIEW KEY POINT:
 *   Fixed chunking: insert 1 byte at the start -> ALL chunk boundaries shift -> ALL hashes change
 *                   -> 0% dedup on re-upload
 *   Content-defined: insert 1 byte -> only the local boundary shifts -> 1 hash changes
 *                    -> 95%+ dedup on re-upload
 *
 *   This is why Dropbox switched from fixed to content-defined chunking.
 */
public interface ChunkingStrategy {

    /**
     * Split file data into chunks.
     * @param fileId    the file these chunks belong to
     * @param data      raw file bytes
     * @param chunkSize target chunk size in bytes (used differently by each strategy)
     * @return ordered list of FileChunk objects (index 0 = first chunk of file)
     */
    List<FileChunk> chunk(String fileId, byte[] data, int chunkSize);

    /** Human-readable name for logging/metrics. */
    String getStrategyName();
}
```

### 5.2 DeduplicationStrategy

```java
/**
 * Determines whether a chunk is a duplicate and manages storage references.
 *
 * CONTENT-ADDRESSABLE STORAGE:
 *   The hash of a chunk's bytes IS its address. If two chunks have the same
 *   hash, they have the same content (with astronomically high probability --
 *   SHA-256 collision chance is 1 in 2^128 for birthday attack).
 *
 *   store(chunk) logic:
 *     hash = SHA-256(chunk.bytes)
 *     if hash in index:
 *         refCount[hash]++     // another file references the same block
 *         return existingKey   // DON'T store again
 *     else:
 *         blockStore.put(hash, chunk.bytes)
 *         refCount[hash] = 1
 *         return hash as storageKey
 *
 * REFERENCE COUNTING:
 *   When a file is deleted, we decrement refCount for each chunk hash.
 *   Only when refCount reaches 0 do we actually delete the block.
 *   This prevents deleting a block that's still referenced by other files.
 *
 * INTERVIEW TIP: This is the same principle behind Git's garbage collection.
 * Objects are only deleted when no references point to them.
 */
public interface DeduplicationStrategy {

    /**
     * Check if a chunk with this hash already exists in storage.
     * @return true if duplicate (no need to upload bytes)
     */
    boolean isDuplicate(String hash);

    /**
     * Store a chunk. If duplicate, increment reference count instead of storing.
     * @return the storageKey to use for retrieval
     */
    String store(FileChunk chunk, byte[] data);

    /**
     * Get the storage key for a hash (used during download/reassembly).
     * @throws FileNotFoundException if hash not in index
     */
    String getStorageKey(String hash);

    /**
     * Decrement reference count. If count reaches 0, delete the block.
     * Called when a file or file version is permanently deleted.
     */
    void decrementReference(String hash);

    /** Human-readable name for logging/metrics. */
    String getStrategyName();
}
```

### 5.3 ConflictStrategy

```java
/**
 * Determines how to resolve sync conflicts when two devices edit the same file.
 *
 * WHEN DO CONFLICTS OCCUR?
 *   Device A (offline): edits "report.docx", saves locally at t=10
 *   Device B (offline): edits "report.docx", saves locally at t=12
 *   Both come online at t=15.
 *   Server has version 3. Both devices think they are updating version 3.
 *   --> CONFLICT: two divergent edits on the same base version.
 *
 * IMPLEMENTATIONS:
 *   - LastWriterWinsStrategy:  keep the most recent (by timestamp). Simple but lossy.
 *   - KeepBothStrategy:        rename the losing file, keep both. No data loss.
 *
 * INTERVIEW TIP: "How do you handle conflicts?" is THE key sync question.
 * Never say "just overwrite." Say "we detect conflicts by comparing version numbers,
 * then apply a configurable strategy -- last-writer-wins for simplicity or
 * keep-both for zero data loss."
 */
public interface ConflictStrategy {

    /**
     * Resolve a conflict between local (device) and remote (server) versions.
     * @param localMetadata  the version from the conflicting device
     * @param remoteMetadata the current server version
     * @return resolution describing what action to take
     */
    ConflictResolution resolve(FileMetadata localMetadata, FileMetadata remoteMetadata);

    /** Human-readable name for logging/metrics. */
    String getStrategyName();
}

/**
 * Result of conflict resolution. Tells the SyncService what to do.
 *
 * WINNER:          which version becomes the "current" version
 * LOSER_ACTION:    what to do with the losing version
 *   - DISCARD:     throw it away (LastWriterWins)
 *   - KEEP_AS_COPY: save as "file (conflict from Device).ext" (KeepBoth)
 *   - MERGE:       merge contents (future strategy, not implemented here)
 */
public class ConflictResolution {
    private final FileMetadata winner;
    private final FileMetadata loser;
    private final LoserAction loserAction;
    private final String conflictFileName;     // null unless loserAction == KEEP_AS_COPY

    public enum LoserAction { DISCARD, KEEP_AS_COPY, MERGE }

    public ConflictResolution(FileMetadata winner, FileMetadata loser,
                              LoserAction loserAction, String conflictFileName) {
        this.winner = winner;
        this.loser = loser;
        this.loserAction = loserAction;
        this.conflictFileName = conflictFileName;
    }

    public FileMetadata getWinner()        { return winner; }
    public FileMetadata getLoser()         { return loser; }
    public LoserAction getLoserAction()    { return loserAction; }
    public String getConflictFileName()    { return conflictFileName; }
}
```

### 5.4 BlockStore

```java
/**
 * Low-level storage interface for raw byte blocks.
 *
 * This is the abstraction over the actual storage backend:
 *   - InMemoryBlockStore: for testing and demos (Map<hash, byte[]>)
 *   - S3BlockStore:       Amazon S3 (production)
 *   - GCSBlockStore:      Google Cloud Storage (production)
 *   - DiskBlockStore:     local filesystem (development)
 *
 * CONTENT-ADDRESSABLE: the hash IS the key.
 * Blocks are IMMUTABLE: you never update a block, only add or delete.
 * Same hash = same bytes, guaranteed by SHA-256.
 *
 * INTERVIEW TIP: Separating BlockStore from metadata storage is critical.
 * Metadata queries (list files, search, permissions) go to a database.
 * Block reads/writes go to object storage. Different access patterns,
 * different scaling strategies, different cost profiles.
 */
public interface BlockStore {

    /**
     * Store a block of bytes. Idempotent: storing the same hash twice is a no-op.
     */
    void storeBlock(String hash, byte[] data);

    /**
     * Retrieve a block by hash.
     * @throws FileNotFoundException if hash not found
     */
    byte[] getBlock(String hash);

    /**
     * Delete a block. Called only when reference count reaches 0.
     */
    void deleteBlock(String hash);

    /**
     * Check if a block exists (used by dedup before upload).
     */
    boolean exists(String hash);

    /**
     * Total number of blocks stored (for metrics/display).
     */
    long blockCount();

    /**
     * Total bytes stored (for metrics/display).
     */
    long totalStorageBytes();
}
```

### 5.5 Repository Interfaces

```java
/**
 * Data access for file metadata. Separated from BlockStore because
 * metadata access patterns differ from block access patterns.
 *
 * Metadata: frequent reads (list folder), indexed queries (search by name),
 *           small records (< 1KB each), needs transactions (rename folder).
 * Blocks:   large reads (4MB chunks), no queries (only key lookup),
 *           append-mostly, no transactions.
 */
public interface FileRepository {
    void save(FileMetadata file);
    Optional<FileMetadata> findById(String fileId);
    List<FileMetadata> findByOwnerId(String ownerId);
    List<FileMetadata> findByPath(String path);
    List<FileMetadata> searchByName(String namePattern);
    void delete(String fileId);
    boolean exists(String fileId);
}

public interface FolderRepository {
    void save(Folder folder);
    Optional<Folder> findById(String folderId);
    Optional<Folder> findByOwnerAndParentAndName(String ownerId, String parentId, String name);
    List<Folder> findByParentId(String parentId);
    void delete(String folderId);
}

public interface VersionRepository {
    void save(FileVersion version);
    Optional<FileVersion> findById(String versionId);
    List<FileVersion> findByFileId(String fileId);
    Optional<FileVersion> findLatestByFileId(String fileId);
    void deleteByFileId(String fileId);
}

public interface UserRepository {
    void save(User user);
    Optional<User> findById(String userId);
    Optional<User> findByEmail(String email);
}
```

---

## 6. Strategy Implementations

### 6.1 FixedSizeChunking

```java
/**
 * Splits files into fixed-size chunks (default 4MB).
 *
 * ALGORITHM:
 *   for i in range(0, fileSize, chunkSize):
 *       chunk[i] = data[i : i + chunkSize]
 *       hash[i]  = SHA-256(chunk[i])
 *
 * PROS: Simple. Predictable chunk count = ceil(fileSize / chunkSize).
 * CONS: BAD for dedup when files change. Insert 1 byte at position 0
 *       -> all chunk boundaries shift -> all hashes change -> 0% dedup.
 *
 *   BEFORE edit:  [AAAA][BBBB][CCCC][DDDD]  (4 chunks)
 *   Insert "X" at pos 0:
 *                  [XAAA][ABBB][BCCC][CDDD][D___]  (5 chunks, ALL different hashes)
 *
 * Use when: files are uploaded once and rarely modified (backup, archival).
 * Do NOT use when: files are frequently edited (documents, code).
 */
public class FixedSizeChunking implements ChunkingStrategy {

    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4MB

    @Override
    public List<FileChunk> chunk(String fileId, byte[] data, int chunkSize) {
        if (chunkSize <= 0) chunkSize = DEFAULT_CHUNK_SIZE;

        List<FileChunk> chunks = new ArrayList<>();
        int offset = 0;
        int index = 0;

        while (offset < data.length) {
            int end = Math.min(offset + chunkSize, data.length);
            byte[] chunkData = Arrays.copyOfRange(data, offset, end);

            // SHA-256 hash of this chunk's bytes -- the content-addressable key
            String hash = computeSHA256(chunkData);
            String chunkId = UUID.randomUUID().toString();

            chunks.add(new FileChunk(chunkId, fileId, index, hash, chunkData.length, hash));

            offset = end;
            index++;
        }

        return chunks;
    }

    /**
     * SHA-256 hash computation. This is the foundation of content-addressable storage.
     * Same bytes -> same hash -> same storage key -> dedup!
     */
    private String computeSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public String getStrategyName() { return "FixedSizeChunking"; }
}
```

### 6.2 ContentDefinedChunking (Rabin Fingerprint)

```java
/**
 * Variable-size chunking using Rabin fingerprint rolling hash.
 *
 * ALGORITHM (simplified):
 *   Slide a window of 48 bytes across the file.
 *   At each position, compute a rolling hash (Rabin fingerprint).
 *   If (hash % avgChunkSize == magicValue) -> CUT HERE (chunk boundary).
 *
 *   Average chunk size: 4MB. Min: 2MB. Max: 8MB.
 *
 * WHY THIS IS DEDUP-FRIENDLY:
 *   Chunk boundaries are determined by LOCAL CONTENT, not absolute position.
 *   Insert 1 byte at position 0:
 *     - Only the first chunk's boundary might shift slightly
 *     - Once the rolling hash re-synchronizes, all subsequent boundaries are IDENTICAL
 *     - Result: 1 chunk changed, 3 chunks reused = 75% dedup
 *
 *   BEFORE edit:  [AAAA][BBBB][CCCC][DDDD]  (boundaries at content-defined points)
 *   Insert "X" at pos 0:
 *                  [XAAAA][BBBB][CCCC][DDDD]  (first chunk slightly bigger, rest SAME hash!)
 *
 * INTERVIEW TIP: Rabin fingerprint is a ROLLING hash: O(1) per byte to update.
 * You don't recompute the hash for the whole window -- you slide it.
 *   hash_new = (hash_old - byte_leaving * factor + byte_entering) mod prime
 *
 * Dropbox, rsync, and LBFS all use content-defined chunking.
 */
public class ContentDefinedChunking implements ChunkingStrategy {

    private static final int WINDOW_SIZE = 48;          // rolling hash window
    private static final long MODULUS = 1_000_003L;     // prime for Rabin fingerprint
    private static final long BASE = 256L;              // byte range

    private static final int MIN_CHUNK_SIZE = 2 * 1024 * 1024;  // 2MB
    private static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024;  // 8MB

    @Override
    public List<FileChunk> chunk(String fileId, byte[] data, int avgChunkSize) {
        if (avgChunkSize <= 0) avgChunkSize = 4 * 1024 * 1024; // 4MB default

        List<FileChunk> chunks = new ArrayList<>();
        int chunkStart = 0;
        int index = 0;

        // Mask for boundary detection: hash & mask == 0 triggers a cut
        // Probability of cut at any position = 1 / avgChunkSize
        int maskBits = (int) (Math.log(avgChunkSize) / Math.log(2));
        long mask = (1L << maskBits) - 1;

        long fingerprint = 0;

        for (int i = 0; i < data.length; i++) {
            // Update rolling hash
            fingerprint = (fingerprint * BASE + (data[i] & 0xFF)) % MODULUS;

            int currentChunkSize = i - chunkStart + 1;

            // Boundary conditions:
            //  1. Reached min size AND hash matches (content-defined boundary)
            //  2. Reached max size (force cut to bound memory)
            //  3. End of file
            boolean isBoundary = (currentChunkSize >= MIN_CHUNK_SIZE && (fingerprint & mask) == 0)
                              || (currentChunkSize >= MAX_CHUNK_SIZE)
                              || (i == data.length - 1);

            if (isBoundary) {
                byte[] chunkData = Arrays.copyOfRange(data, chunkStart, i + 1);
                String hash = computeSHA256(chunkData);
                String chunkId = UUID.randomUUID().toString();

                chunks.add(new FileChunk(chunkId, fileId, index, hash, chunkData.length, hash));

                chunkStart = i + 1;
                index++;
                fingerprint = 0;  // reset for next chunk
            }
        }

        return chunks;
    }

    private String computeSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public String getStrategyName() { return "ContentDefinedChunking (Rabin)"; }
}
```

### 6.3 HashBasedDedup

```java
/**
 * Content-addressable deduplication using SHA-256 hashes.
 *
 * DATA STRUCTURES:
 *   hashIndex: Map<hash, storageKey>   -- lookup: "does this content exist?"
 *   refCount:  Map<hash, int>          -- how many files reference this block?
 *
 * FLOW:
 *   Upload chunk with hash "abc123":
 *     hashIndex contains "abc123"?
 *       YES -> refCount["abc123"]++, return existing storageKey (DEDUP!)
 *       NO  -> blockStore.store("abc123", bytes), refCount["abc123"] = 1
 *
 *   Delete file with chunk hashes ["abc123", "def456"]:
 *     refCount["abc123"]--
 *       refCount == 0? -> blockStore.delete("abc123"), hashIndex.remove("abc123")
 *       refCount > 0?  -> block still referenced by other files, keep it
 *
 * PRODUCTION STATS (Dropbox, circa 2018):
 *   - 75% of uploaded chunks were duplicates
 *   - Dedup saved ~400 petabytes of storage
 *   - ROI: SHA-256 computation cost << storage savings
 */
public class HashBasedDedup implements DeduplicationStrategy {

    private final BlockStore blockStore;
    private final ConcurrentHashMap<String, String> hashIndex;    // hash -> storageKey
    private final ConcurrentHashMap<String, AtomicInteger> refCount; // hash -> reference count

    public HashBasedDedup(BlockStore blockStore) {
        this.blockStore = blockStore;
        this.hashIndex = new ConcurrentHashMap<>();
        this.refCount = new ConcurrentHashMap<>();
    }

    @Override
    public boolean isDuplicate(String hash) {
        return hashIndex.containsKey(hash);
    }

    @Override
    public String store(FileChunk chunk, byte[] data) {
        String hash = chunk.getHash();

        // Atomic check-and-store: if hash already exists, just increment refCount
        if (hashIndex.containsKey(hash)) {
            refCount.get(hash).incrementAndGet();
            return hashIndex.get(hash);
        }

        // New content: store in block store
        blockStore.storeBlock(hash, data);
        hashIndex.put(hash, hash);  // for content-addressable, storageKey == hash
        refCount.put(hash, new AtomicInteger(1));

        return hash;
    }

    @Override
    public String getStorageKey(String hash) {
        String key = hashIndex.get(hash);
        if (key == null) {
            throw new FileStorageException("Block not found for hash: " + hash);
        }
        return key;
    }

    @Override
    public void decrementReference(String hash) {
        AtomicInteger count = refCount.get(hash);
        if (count == null) return;

        int newCount = count.decrementAndGet();
        if (newCount <= 0) {
            // No more references -> safe to delete the actual block
            blockStore.deleteBlock(hash);
            hashIndex.remove(hash);
            refCount.remove(hash);
        }
    }

    /**
     * Stats for display: how many unique blocks vs. total references.
     * dedup ratio = 1 - (uniqueBlocks / totalReferences)
     */
    public double getDedupRatio() {
        long totalRefs = refCount.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();
        long uniqueBlocks = refCount.size();
        if (totalRefs == 0) return 0.0;
        return 1.0 - ((double) uniqueBlocks / totalRefs);
    }

    @Override
    public String getStrategyName() { return "HashBasedDedup (SHA-256)"; }
}
```

### 6.4 NoDedup

```java
/**
 * No-op deduplication strategy. Always stores, never deduplicates.
 *
 * USE CASES:
 *   1. Compliance: legal hold requires per-user copies (e.g., HIPAA, SOX)
 *   2. Testing: verify upload logic without dedup complexity
 *   3. Baseline: measure how much storage dedup actually saves
 *
 * INTERVIEW TIP: Having NoDedup is a Strategy pattern showcase.
 * "We can disable dedup for compliance use cases without changing
 * any service code -- just swap the strategy in AppConfig."
 */
public class NoDedup implements DeduplicationStrategy {

    private final BlockStore blockStore;
    private final ConcurrentHashMap<String, String> keyMap = new ConcurrentHashMap<>();

    public NoDedup(BlockStore blockStore) {
        this.blockStore = blockStore;
    }

    @Override
    public boolean isDuplicate(String hash) {
        return false;  // never considers anything a duplicate
    }

    @Override
    public String store(FileChunk chunk, byte[] data) {
        // Always store, using a unique key (not hash-based)
        String storageKey = UUID.randomUUID().toString();
        blockStore.storeBlock(storageKey, data);
        keyMap.put(chunk.getHash(), storageKey);
        return storageKey;
    }

    @Override
    public String getStorageKey(String hash) {
        String key = keyMap.get(hash);
        if (key == null) throw new FileStorageException("Block not found: " + hash);
        return key;
    }

    @Override
    public void decrementReference(String hash) {
        String key = keyMap.remove(hash);
        if (key != null) {
            blockStore.deleteBlock(key);
        }
    }

    @Override
    public String getStrategyName() { return "NoDedup (always store)"; }
}
```

### 6.5 LastWriterWinsStrategy

```java
/**
 * Conflict resolution: most recent timestamp wins.
 *
 * ALGORITHM:
 *   if (local.lastModifiedAt > remote.lastModifiedAt)
 *       winner = local, loser = remote
 *   else
 *       winner = remote, loser = local
 *
 *   loser becomes a previous version (not deleted, just not current).
 *
 * CLOCK SKEW RISK: Device A's clock is 5 minutes ahead.
 * Device B makes the "real" latest edit, but A's timestamp is later.
 * A wins. B's edit is demoted to a previous version.
 * Mitigation: use server-assigned timestamps on sync, not client clocks.
 *
 * Used by: iCloud Drive, some Google Drive conflict cases.
 */
public class LastWriterWinsStrategy implements ConflictStrategy {

    @Override
    public ConflictResolution resolve(FileMetadata localMetadata, FileMetadata remoteMetadata) {
        FileMetadata winner;
        FileMetadata loser;

        // Compare last-modified timestamps
        if (localMetadata.getLastModifiedAt().isAfter(remoteMetadata.getLastModifiedAt())) {
            winner = localMetadata;
            loser = remoteMetadata;
        } else {
            winner = remoteMetadata;
            loser = localMetadata;
        }

        // Loser becomes a previous version (preserved in version history, not deleted)
        return new ConflictResolution(winner, loser,
            ConflictResolution.LoserAction.DISCARD, null);
    }

    @Override
    public String getStrategyName() { return "LastWriterWins"; }
}
```

### 6.6 KeepBothStrategy

```java
/**
 * Conflict resolution: keep both files, rename the conflicting one.
 *
 * ALGORITHM:
 *   winner = remote (server version stays as-is)
 *   loser  = local  (device version gets renamed)
 *
 *   Original: "report.docx"
 *   Conflict: "report (Alice's Laptop conflict on 2025-01-15).docx"
 *
 * This is the Dropbox model. ZERO data loss. User decides what to keep.
 *
 * WHY REMOTE ALWAYS WINS?
 *   The server version is already synced to other devices. Renaming it would
 *   cause cascading renames across all devices. Renaming only the conflicting
 *   local version affects just the one device that caused the conflict.
 */
public class KeepBothStrategy implements ConflictStrategy {

    @Override
    public ConflictResolution resolve(FileMetadata localMetadata, FileMetadata remoteMetadata) {
        // Remote (server) version stays as-is
        // Local (device) version gets a conflict name
        String conflictName = buildConflictName(localMetadata.getName(),
            localMetadata.getLastModifiedBy(),
            localMetadata.getLastModifiedAt());

        return new ConflictResolution(
            remoteMetadata,     // winner: server version
            localMetadata,      // loser: local version (will be saved with conflict name)
            ConflictResolution.LoserAction.KEEP_AS_COPY,
            conflictName
        );
    }

    /**
     * Build Dropbox-style conflict filename:
     *   "report.docx" -> "report (Alice's conflict on 2025-01-15).docx"
     */
    private String buildConflictName(String originalName, String userId, Instant timestamp) {
        String date = timestamp.toString().substring(0, 10); // "2025-01-15"
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) {
            String baseName = originalName.substring(0, dotIndex);
            String extension = originalName.substring(dotIndex);
            return baseName + " (" + userId + " conflict on " + date + ")" + extension;
        }
        return originalName + " (" + userId + " conflict on " + date + ")";
    }

    @Override
    public String getStrategyName() { return "KeepBoth (Dropbox model)"; }
}
```

---

## 7. Service Layer Design

### 7.1 UploadService (Chunked + Resumable)

```java
/**
 * Handles file uploads with chunking, dedup, and resumable support.
 *
 * UPLOAD FLOW:
 *   1. Client: "I want to upload report.pdf (12MB)"
 *   2. Server: "OK, your upload session ID is abc123"
 *   3. Client: chunks file into [chunk0, chunk1, chunk2] (4MB each)
 *   4. For each chunk:
 *      a. Client computes SHA-256 hash
 *      b. Client asks: "do you already have hash X?"
 *      c. Server checks dedup strategy:
 *         - YES: "I already have that chunk. Skip upload." (saves bandwidth!)
 *         - NO:  "Upload chunk bytes."
 *      d. Client uploads chunk bytes
 *      e. Server verifies hash matches, stores block
 *   5. Server assembles metadata: file with [hash0, hash1, hash2]
 *   6. Server creates FileVersion v1
 *
 * RESUMABLE UPLOAD:
 *   Upload fails at chunk 2. Client reconnects.
 *   Client: "Resume upload session abc123"
 *   Server: "I have chunks 0 and 1. Send chunk 2."
 *   Client uploads only chunk 2. Done.
 *
 *   Without resumable: 12MB file fails at 11MB -> re-upload 12MB.
 *   With resumable: 12MB file fails at 11MB -> re-upload 4MB (one chunk).
 *
 * INTERVIEW TIP: Google Drive, Dropbox, and YouTube all use resumable uploads.
 * The protocol is: start session -> upload chunks -> finalize.
 * If interrupted, ask the server which chunks it has, then resume.
 */
public class UploadService {

    private final ChunkingStrategy chunkingStrategy;
    private final DeduplicationService deduplicationService;
    private final MetadataService metadataService;
    private final VersionService versionService;
    private final BlockStore blockStore;

    // Active upload sessions: sessionId -> UploadSession
    private final ConcurrentHashMap<String, UploadSession> activeSessions
        = new ConcurrentHashMap<>();

    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4MB

    public UploadService(ChunkingStrategy chunkingStrategy,
                         DeduplicationService deduplicationService,
                         MetadataService metadataService,
                         VersionService versionService,
                         BlockStore blockStore) {
        this.chunkingStrategy = chunkingStrategy;
        this.deduplicationService = deduplicationService;
        this.metadataService = metadataService;
        this.versionService = versionService;
        this.blockStore = blockStore;
    }

    /**
     * Start an upload session. Returns a session ID for resumable upload.
     * The session tracks which chunks have been received.
     */
    public String startUpload(String userId, String fileName, String path,
                              long totalSize, String mimeType) {
        // Check quota BEFORE starting (fail fast)
        metadataService.checkQuota(userId, totalSize);

        String sessionId = UUID.randomUUID().toString();
        int expectedChunks = (int) Math.ceil((double) totalSize / DEFAULT_CHUNK_SIZE);

        UploadSession session = new UploadSession(sessionId, userId, fileName,
            path, totalSize, mimeType, expectedChunks);
        activeSessions.put(sessionId, session);

        return sessionId;
    }

    /**
     * Upload a single chunk. Dedup is checked before storing.
     * Returns true if the chunk was already stored (dedup hit -- no bytes transferred).
     */
    public boolean uploadChunk(String sessionId, int chunkIndex,
                               byte[] chunkData, String expectedHash) {
        UploadSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new FileStorageException("Upload session not found: " + sessionId);
        }

        // Verify chunk integrity: compute hash and compare with client's expected hash
        String actualHash = computeSHA256(chunkData);
        if (!actualHash.equals(expectedHash)) {
            throw new ChunkMismatchException(
                "Chunk " + chunkIndex + " hash mismatch. Expected: " + expectedHash
                + ", Actual: " + actualHash);
        }

        // Dedup check: if this chunk's content already exists, skip storage
        boolean deduplicated = deduplicationService.storeChunkIfNew(actualHash, chunkData);

        // Record chunk in session
        session.addCompletedChunk(chunkIndex, actualHash, chunkData.length);

        return deduplicated;
    }

    /**
     * Finalize upload: all chunks received, create metadata + version.
     * Called after the last chunk is uploaded.
     */
    public FileMetadata finalizeUpload(String sessionId) {
        UploadSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new FileStorageException("Upload session not found: " + sessionId);
        }

        if (!session.isComplete()) {
            throw new FileStorageException("Upload incomplete. Received "
                + session.getCompletedChunkCount() + "/" + session.getExpectedChunks() + " chunks.");
        }

        // Create file metadata
        String fileId = UUID.randomUUID().toString();
        String fileHash = computeFileHash(session.getChunkHashes());

        FileMetadata metadata = new FileMetadata.Builder()
            .fileId(fileId)
            .name(session.getFileName())
            .path(session.getPath())
            .size(session.getTotalSize())
            .hash(fileHash)
            .mimeType(session.getMimeType())
            .ownerId(session.getUserId())
            .build();

        // Create version 1
        FileVersion version = versionService.createVersion(
            fileId, 1, session.getChunkHashes(), session.getTotalSize(), session.getUserId());
        metadata.addVersion(version.getVersionId());

        // Save metadata and update quota
        metadataService.saveFile(metadata);
        metadataService.consumeQuota(session.getUserId(), session.getTotalSize());

        // Clean up session
        activeSessions.remove(sessionId);

        return metadata;
    }

    /**
     * Resume a failed upload. Returns which chunk indexes are still needed.
     */
    public List<Integer> getMissingChunks(String sessionId) {
        UploadSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new FileStorageException("Upload session not found: " + sessionId);
        }
        return session.getMissingChunkIndexes();
    }

    private String computeSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * File-level hash = hash of all chunk hashes concatenated.
     * This uniquely identifies the file content independent of chunk boundaries.
     */
    private String computeFileHash(List<String> chunkHashes) {
        String concatenated = String.join("", chunkHashes);
        return computeSHA256(concatenated.getBytes());
    }

    /**
     * Tracks the state of an in-progress upload.
     * Enables resumable uploads: if connection drops, we know which chunks arrived.
     */
    private static class UploadSession {
        private final String sessionId;
        private final String userId;
        private final String fileName;
        private final String path;
        private final long totalSize;
        private final String mimeType;
        private final int expectedChunks;
        private final Map<Integer, String> completedChunks;  // index -> hash
        private final Map<Integer, Integer> chunkSizes;      // index -> sizeBytes

        UploadSession(String sessionId, String userId, String fileName,
                      String path, long totalSize, String mimeType, int expectedChunks) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.fileName = fileName;
            this.path = path;
            this.totalSize = totalSize;
            this.mimeType = mimeType;
            this.expectedChunks = expectedChunks;
            this.completedChunks = new ConcurrentHashMap<>();
            this.chunkSizes = new ConcurrentHashMap<>();
        }

        void addCompletedChunk(int index, String hash, int size) {
            completedChunks.put(index, hash);
            chunkSizes.put(index, size);
        }

        boolean isComplete() { return completedChunks.size() >= expectedChunks; }
        int getCompletedChunkCount() { return completedChunks.size(); }
        int getExpectedChunks() { return expectedChunks; }

        List<String> getChunkHashes() {
            List<String> hashes = new ArrayList<>();
            for (int i = 0; i < expectedChunks; i++) {
                hashes.add(completedChunks.get(i));
            }
            return hashes;
        }

        List<Integer> getMissingChunkIndexes() {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < expectedChunks; i++) {
                if (!completedChunks.containsKey(i)) {
                    missing.add(i);
                }
            }
            return missing;
        }

        String getSessionId() { return sessionId; }
        String getUserId()    { return userId; }
        String getFileName()  { return fileName; }
        String getPath()      { return path; }
        long getTotalSize()   { return totalSize; }
        String getMimeType()  { return mimeType; }
    }
}
```

### 7.2 DownloadService

```java
/**
 * Reassembles file chunks into a complete file for download.
 *
 * DOWNLOAD FLOW:
 *   1. Client requests download of file "abc123"
 *   2. Server looks up FileMetadata -> gets latest FileVersion -> gets chunkHashes
 *   3. For each chunkHash, retrieve bytes from BlockStore
 *   4. Concatenate chunks in order -> complete file bytes
 *   5. Return to client
 *
 * RANGE REQUESTS:
 *   Client needs bytes [1000, 2000) only (e.g., video seeking).
 *   Server computes: which chunks contain byte range [1000, 2000)?
 *   Only those chunks are fetched from BlockStore. Others skipped.
 *   Saves bandwidth and latency for large files with partial reads.
 *
 * PARALLEL DOWNLOAD:
 *   In production, chunks can be fetched in parallel from S3/GCS:
 *   Thread 1: fetch chunk0, Thread 2: fetch chunk1, Thread 3: fetch chunk2
 *   Then assemble in order. 3x throughput for 3 chunks.
 */
public class DownloadService {

    private final MetadataService metadataService;
    private final VersionService versionService;
    private final BlockStore blockStore;
    private final DeduplicationService deduplicationService;

    public DownloadService(MetadataService metadataService, VersionService versionService,
                           BlockStore blockStore, DeduplicationService deduplicationService) {
        this.metadataService = metadataService;
        this.versionService = versionService;
        this.blockStore = blockStore;
        this.deduplicationService = deduplicationService;
    }

    /**
     * Download the latest version of a file.
     * @return complete file bytes (all chunks reassembled)
     */
    public byte[] download(String fileId) {
        FileMetadata metadata = metadataService.getFile(fileId);
        FileVersion latestVersion = versionService.getLatestVersion(fileId);

        return reassembleChunks(latestVersion.getChunkHashes());
    }

    /**
     * Download a specific version of a file.
     * Used by "Version History" -> "Download this version"
     */
    public byte[] downloadVersion(String fileId, int versionNumber) {
        FileVersion version = versionService.getVersion(fileId, versionNumber);
        return reassembleChunks(version.getChunkHashes());
    }

    /**
     * Download a byte range (partial content). Used for:
     *   - Video streaming (seek to 2:30 -> download bytes at that offset)
     *   - PDF preview (download first page only)
     *   - Large file inspection (download header bytes to detect format)
     */
    public byte[] downloadRange(String fileId, long startByte, long endByte) {
        FileVersion latestVersion = versionService.getLatestVersion(fileId);
        List<String> chunkHashes = latestVersion.getChunkHashes();

        // Determine which chunks overlap with the requested range
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        long currentOffset = 0;

        for (String hash : chunkHashes) {
            String storageKey = deduplicationService.getStorageKey(hash);
            byte[] chunkData = blockStore.getBlock(storageKey);
            long chunkEnd = currentOffset + chunkData.length;

            // Check if this chunk overlaps with requested range
            if (chunkEnd > startByte && currentOffset < endByte) {
                int copyStart = (int) Math.max(0, startByte - currentOffset);
                int copyEnd = (int) Math.min(chunkData.length, endByte - currentOffset);
                result.write(chunkData, copyStart, copyEnd - copyStart);
            }

            currentOffset = chunkEnd;
            if (currentOffset >= endByte) break;  // no more chunks needed
        }

        return result.toByteArray();
    }

    /**
     * Reassemble chunks into complete file bytes.
     * Chunks are fetched from BlockStore by hash and concatenated in order.
     */
    private byte[] reassembleChunks(List<String> chunkHashes) {
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();

        for (String hash : chunkHashes) {
            String storageKey = deduplicationService.getStorageKey(hash);
            byte[] chunkData = blockStore.getBlock(storageKey);
            assembled.write(chunkData, 0, chunkData.length);
        }

        return assembled.toByteArray();
    }
}
```

### 7.3 MetadataService

```java
/**
 * CRUD operations for file and folder metadata. Manages the file hierarchy,
 * search, and storage quota tracking.
 *
 * SEPARATION FROM BLOCK STORE:
 *   MetadataService: "What files exist? Where? Who owns them? How big?"
 *   BlockStore: "Here are the raw bytes for hash abc123."
 *
 *   In production:
 *     Metadata -> Postgres/DynamoDB (indexed, queryable, transactional)
 *     Blocks   -> S3/GCS (cheap, durable, no indexing needed)
 */
public class MetadataService {

    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;

    public MetadataService(FileRepository fileRepository, FolderRepository folderRepository,
                           UserRepository userRepository) {
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
    }

    // --- File Operations ---

    public void saveFile(FileMetadata file) {
        fileRepository.save(file);
    }

    public FileMetadata getFile(String fileId) {
        return fileRepository.findById(fileId)
            .orElseThrow(() -> new FileNotFoundException("File not found: " + fileId));
    }

    public List<FileMetadata> listFiles(String ownerId, String path) {
        return fileRepository.findByOwnerId(ownerId).stream()
            .filter(f -> f.getPath().equals(path))
            .filter(f -> !f.isTrashed())
            .toList();
    }

    public List<FileMetadata> searchByName(String namePattern) {
        return fileRepository.searchByName(namePattern);
    }

    // --- Folder Operations ---

    public Folder createFolder(String name, String parentId, String ownerId) {
        // Check for duplicate folder name in same parent
        Optional<Folder> existing = folderRepository.findByOwnerAndParentAndName(
            ownerId, parentId, name);
        if (existing.isPresent()) {
            throw new FileStorageException("Folder '" + name + "' already exists in this location");
        }

        String folderId = UUID.randomUUID().toString();
        Folder folder = new Folder(folderId, name, parentId, ownerId);
        folderRepository.save(folder);

        // Add to parent's children
        if (parentId != null) {
            Folder parent = folderRepository.findById(parentId)
                .orElseThrow(() -> new FileNotFoundException("Parent folder not found: " + parentId));
            parent.addSubfolder(folderId);
        }

        return folder;
    }

    public Folder getFolder(String folderId) {
        return folderRepository.findById(folderId)
            .orElseThrow(() -> new FileNotFoundException("Folder not found: " + folderId));
    }

    /**
     * Move a file to a different folder. Only updates metadata (not blocks).
     * O(1) because we use ID-based hierarchy, not path-based.
     */
    public void moveFile(String fileId, String newPath, String newFolderId) {
        FileMetadata file = getFile(fileId);
        file.moveTo(newPath);
        fileRepository.save(file);
    }

    // --- Quota Management ---

    public void checkQuota(String userId, long additionalBytes) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new FileStorageException("User not found: " + userId));

        if (!user.getStorageQuota().canUpload(additionalBytes)) {
            throw new StorageQuotaExceededException(
                "User " + userId + " quota exceeded. Used: "
                + user.getStorageQuota().getUsedBytes()
                + ", Limit: " + user.getStorageQuota().getTotalBytes()
                + ", Requested: " + additionalBytes);
        }
    }

    public void consumeQuota(String userId, long bytes) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new FileStorageException("User not found: " + userId));
        user.getStorageQuota().consumeBytes(bytes);
    }

    public void releaseQuota(String userId, long bytes) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new FileStorageException("User not found: " + userId));
        user.getStorageQuota().releaseBytes(bytes);
    }
}
```

### 7.4 DeduplicationService

```java
/**
 * Orchestrates deduplication using the configured DeduplicationStrategy.
 * Sits between UploadService and BlockStore, intercepting storage calls.
 *
 * REFERENCE COUNTING:
 *   Every chunk hash has a reference count: how many file-versions point to it.
 *   When a file is deleted: decrement refCount for each chunk.
 *   When refCount reaches 0: actually delete the block (garbage collection).
 *
 *   Why not delete immediately?
 *     File A: [hash1, hash2, hash3]
 *     File B: [hash1, hash4, hash5]  (shares hash1 with File A)
 *     Delete File A: hash1 refCount goes from 2 to 1. DO NOT delete hash1.
 *     File B still needs it.
 */
public class DeduplicationService {

    private final DeduplicationStrategy dedupStrategy;

    // Metrics
    private final AtomicLong totalChunksProcessed = new AtomicLong(0);
    private final AtomicLong duplicateChunksSkipped = new AtomicLong(0);

    public DeduplicationService(DeduplicationStrategy dedupStrategy) {
        this.dedupStrategy = dedupStrategy;
    }

    /**
     * Store a chunk if it's new, or increment reference if it's a duplicate.
     * @return true if the chunk was a duplicate (dedup hit), false if newly stored
     */
    public boolean storeChunkIfNew(String hash, byte[] data) {
        totalChunksProcessed.incrementAndGet();

        boolean isDuplicate = dedupStrategy.isDuplicate(hash);
        if (isDuplicate) {
            duplicateChunksSkipped.incrementAndGet();
        }

        // Store always calls strategy, which handles the dedup logic internally
        FileChunk tempChunk = new FileChunk(
            UUID.randomUUID().toString(), "temp", 0, hash, data.length, hash);
        dedupStrategy.store(tempChunk, data);

        return isDuplicate;
    }

    public String getStorageKey(String hash) {
        return dedupStrategy.getStorageKey(hash);
    }

    /**
     * Release all chunk references for a file version.
     * Called when a version is permanently purged (not just soft-deleted).
     */
    public void releaseChunks(List<String> chunkHashes) {
        for (String hash : chunkHashes) {
            dedupStrategy.decrementReference(hash);
        }
    }

    // --- Metrics ---
    public long getTotalChunksProcessed()    { return totalChunksProcessed.get(); }
    public long getDuplicateChunksSkipped()  { return duplicateChunksSkipped.get(); }
    public double getDedupHitRate() {
        long total = totalChunksProcessed.get();
        if (total == 0) return 0.0;
        return (double) duplicateChunksSkipped.get() / total * 100.0;
    }
}
```

### 7.5 SyncService

```java
/**
 * Handles multi-device synchronization. Detects changes, resolves conflicts,
 * pushes updates to other devices.
 *
 * SYNC MODEL (Dropbox-style cursor):
 *   Each device maintains a "cursor" -- the sequence number of the last
 *   event it processed. On sync:
 *     1. Device sends: "give me events after cursor 42"
 *     2. Server returns: events 43, 44, 45 (the delta)
 *     3. Device applies each event locally
 *     4. Device updates cursor to 45
 *
 * CONFLICT DETECTION:
 *   Device A (offline) edits file X (based on version 3)
 *   Device B (offline) edits file X (based on version 3)
 *   Both come online:
 *     A syncs first -> server version becomes 4
 *     B syncs -> B's base version is 3, but server is at 4 -> CONFLICT
 *     ConflictStrategy resolves: LastWriterWins or KeepBoth
 */
public class SyncService {

    private final ConflictStrategy conflictStrategy;
    private final MetadataService metadataService;

    // Event log: userId -> ordered list of sync events
    private final ConcurrentHashMap<String, List<SyncEvent>> eventLog
        = new ConcurrentHashMap<>();

    // Device cursors: deviceId -> last processed sequence number
    private final ConcurrentHashMap<String, Long> deviceCursors
        = new ConcurrentHashMap<>();

    // Global sequence counter for event ordering
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    public SyncService(ConflictStrategy conflictStrategy, MetadataService metadataService) {
        this.conflictStrategy = conflictStrategy;
        this.metadataService = metadataService;
    }

    /**
     * Record a change event. Called after every upload, edit, delete, move, rename.
     */
    public SyncEvent recordEvent(String userId, String fileId,
                                 SyncEventType eventType, String deviceId) {
        long seqNum = sequenceCounter.incrementAndGet();
        String eventId = UUID.randomUUID().toString();

        SyncEvent event = new SyncEvent(eventId, userId, fileId, eventType, deviceId, seqNum);

        eventLog.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(event);

        return event;
    }

    /**
     * Get events since the device's cursor position.
     * Returns the delta: events the device hasn't seen yet.
     */
    public List<SyncEvent> getEventsSinceCursor(String userId, String deviceId) {
        long cursor = deviceCursors.getOrDefault(deviceId, 0L);

        List<SyncEvent> userEvents = eventLog.getOrDefault(userId, List.of());

        return userEvents.stream()
            .filter(e -> e.getSequenceNumber() > cursor)
            .filter(e -> !e.getDeviceId().equals(deviceId))  // exclude device's own events
            .toList();
    }

    /**
     * Update a device's cursor after it has processed events.
     */
    public void updateCursor(String deviceId, long newCursor) {
        deviceCursors.put(deviceId, newCursor);
    }

    /**
     * Detect and resolve a conflict between local and remote versions.
     * Called when a device tries to sync a file that was also modified on the server.
     */
    public ConflictResolution resolveConflict(FileMetadata localVersion,
                                              FileMetadata remoteVersion) {
        return conflictStrategy.resolve(localVersion, remoteVersion);
    }

    /**
     * Check if syncing a file will cause a conflict.
     * Conflict = file was modified on server AFTER the device's last sync.
     */
    public boolean willConflict(String fileId, String deviceId) {
        long cursor = deviceCursors.getOrDefault(deviceId, 0L);

        List<SyncEvent> events = eventLog.values().stream()
            .flatMap(List::stream)
            .filter(e -> e.getFileId().equals(fileId))
            .filter(e -> e.getSequenceNumber() > cursor)
            .filter(e -> !e.getDeviceId().equals(deviceId))
            .toList();

        // If any MODIFIED events exist after our cursor, it's a conflict
        return events.stream()
            .anyMatch(e -> e.getEventType() == SyncEventType.MODIFIED);
    }
}
```

### 7.6 VersionService

```java
/**
 * Manages file version history: create versions, list history, rollback, diff.
 *
 * VERSION MODEL:
 *   Each upload/edit creates a new FileVersion pointing to its chunk hashes.
 *   Versions share chunks via dedup -- only changed chunks are new.
 *
 *   Version 1: [hash_A, hash_B, hash_C]    (initial upload, 12MB)
 *   Version 2: [hash_A, hash_B, hash_D]    (chunk C changed to D, 4MB new storage)
 *   Version 3: [hash_A, hash_B, hash_D, hash_E]  (appended chunk E, 4MB new storage)
 *
 *   Total logical size: 12 + 12 + 16 = 40MB
 *   Total physical storage: 12 + 4 + 4 = 20MB (chunks A, B shared across versions)
 *
 * Google Drive keeps 100 versions for 30 days.
 * Dropbox keeps all versions for 30 days (180 days for paid).
 */
public class VersionService {

    private final VersionRepository versionRepository;

    public VersionService(VersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    /**
     * Create a new version for a file.
     * Called after a successful upload or file edit.
     */
    public FileVersion createVersion(String fileId, int versionNumber,
                                     List<String> chunkHashes, long size, String createdBy) {
        String versionId = UUID.randomUUID().toString();
        FileVersion version = new FileVersion(
            versionId, fileId, versionNumber, chunkHashes, size, createdBy);
        versionRepository.save(version);
        return version;
    }

    /**
     * Get the latest version of a file.
     * Used by DownloadService for "download current version."
     */
    public FileVersion getLatestVersion(String fileId) {
        return versionRepository.findLatestByFileId(fileId)
            .orElseThrow(() -> new FileNotFoundException(
                "No versions found for file: " + fileId));
    }

    /**
     * Get a specific version by number.
     * Used by "Version History" -> "Download version 3."
     */
    public FileVersion getVersion(String fileId, int versionNumber) {
        return versionRepository.findByFileId(fileId).stream()
            .filter(v -> v.getVersionNumber() == versionNumber)
            .findFirst()
            .orElseThrow(() -> new FileNotFoundException(
                "Version " + versionNumber + " not found for file: " + fileId));
    }

    /**
     * List all versions of a file (for version history UI).
     */
    public List<FileVersion> getVersionHistory(String fileId) {
        return versionRepository.findByFileId(fileId);
    }

    /**
     * Rollback a file to a previous version.
     * Creates a NEW version (does not rewrite history).
     *
     * Why create a new version instead of deleting later versions?
     *   1. Audit trail: "Alice rolled back to v3 at 2:30pm" is a version event
     *   2. Undo rollback: if the rollback was a mistake, previous versions still exist
     *   3. Consistency: version numbers only go up, never backwards
     */
    public FileVersion rollbackToVersion(String fileId, int targetVersionNumber, String userId) {
        FileVersion target = getVersion(fileId, targetVersionNumber);
        FileVersion latest = getLatestVersion(fileId);

        int newVersionNumber = latest.getVersionNumber() + 1;

        // Create a new version with the same chunks as the target version
        return createVersion(fileId, newVersionNumber,
            target.getChunkHashes(), target.getSize(), userId);
    }

    /**
     * Compute the diff between two versions.
     * Returns chunk hashes that changed. Used for delta sync and diff display.
     */
    public List<String> diffVersions(String fileId, int fromVersion, int toVersion) {
        FileVersion from = getVersion(fileId, fromVersion);
        FileVersion to = getVersion(fileId, toVersion);
        return to.diffFrom(from);
    }
}
```

### 7.7 SharingService

```java
/**
 * Manages file sharing: share links, permissions, access control.
 *
 * SHARING MODEL:
 *   Google Drive:  "Anyone with the link can view" + per-user permissions
 *   Dropbox:       "Share link" with optional password and expiry
 *   This design:   Supports both models via ShareLink + SharePermission
 *
 * PERMISSION HIERARCHY:
 *   EDIT > DOWNLOAD > VIEW
 *   EDIT   = can modify the file (creates new version)
 *   DOWNLOAD = can download a copy (but not modify the original)
 *   VIEW   = can see metadata and preview (no download button)
 */
public class SharingService {

    // fileId -> List<ShareLink>
    private final ConcurrentHashMap<String, List<ShareLink>> shareLinks
        = new ConcurrentHashMap<>();

    // fileId -> Map<userId, SharePermission>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SharePermission>> permissions
        = new ConcurrentHashMap<>();

    /**
     * Create a share link for a file.
     */
    public ShareLink createShareLink(String fileId, SharePermission permission,
                                     Instant expiresAt, String password, String createdBy) {
        String linkId = UUID.randomUUID().toString();
        ShareLink link = new ShareLink(linkId, fileId, permission,
            expiresAt, password, createdBy);

        shareLinks.computeIfAbsent(fileId, k -> new CopyOnWriteArrayList<>()).add(link);

        return link;
    }

    /**
     * Access a file via share link. Validates the link and returns permission level.
     * Throws PermissionDeniedException if link is invalid, expired, or password wrong.
     */
    public SharePermission accessViaShareLink(String linkId, String password) {
        ShareLink link = findShareLink(linkId);

        if (!link.isValid()) {
            throw new PermissionDeniedException(
                "Share link " + linkId + " is expired or revoked");
        }

        if (!link.checkPassword(password)) {
            throw new PermissionDeniedException("Invalid password for share link " + linkId);
        }

        return link.getPermission();
    }

    /**
     * Grant direct permission to a specific user (not via link).
     * "Share with alice@example.com as Editor"
     */
    public void grantPermission(String fileId, String userId, SharePermission permission) {
        permissions.computeIfAbsent(fileId, k -> new ConcurrentHashMap<>())
                   .put(userId, permission);
    }

    /**
     * Check if a user has the required permission for a file.
     */
    public void checkPermission(String fileId, String userId, SharePermission required) {
        ConcurrentHashMap<String, SharePermission> filePerms = permissions.get(fileId);
        if (filePerms == null || !filePerms.containsKey(userId)) {
            throw new PermissionDeniedException(
                "User " + userId + " has no access to file " + fileId);
        }

        SharePermission actual = filePerms.get(userId);
        if (actual.ordinal() < required.ordinal()) {
            throw new PermissionDeniedException(
                "User " + userId + " has " + actual + " but needs " + required
                + " for file " + fileId);
        }
    }

    public void revokeShareLink(String linkId) {
        ShareLink link = findShareLink(linkId);
        link.revoke();
    }

    private ShareLink findShareLink(String linkId) {
        return shareLinks.values().stream()
            .flatMap(List::stream)
            .filter(l -> l.getLinkId().equals(linkId))
            .findFirst()
            .orElseThrow(() -> new FileNotFoundException("Share link not found: " + linkId));
    }
}
```

### 7.8 TrashService

```java
/**
 * Soft delete with recovery and permanent purge.
 *
 * TRASH MODEL (Google Drive / Dropbox):
 *   1. User deletes "report.pdf" -> file.isTrashed = true (soft delete)
 *   2. File disappears from user's view but is NOT gone
 *   3. User can "Restore from Trash" -> file.isTrashed = false
 *   4. After 30 days (or manual "Empty Trash"): permanent purge
 *
 * PERMANENT PURGE:
 *   1. Remove all file versions
 *   2. Decrement chunk reference counts (via DeduplicationService)
 *   3. Release storage quota
 *   4. Delete metadata
 *
 *   Chunks are only physically deleted if refCount reaches 0
 *   (another file might reference the same chunks).
 */
public class TrashService {

    private final MetadataService metadataService;
    private final VersionService versionService;
    private final DeduplicationService deduplicationService;
    private final FileRepository fileRepository;

    private static final long TRASH_RETENTION_DAYS = 30;

    public TrashService(MetadataService metadataService, VersionService versionService,
                        DeduplicationService deduplicationService, FileRepository fileRepository) {
        this.metadataService = metadataService;
        this.versionService = versionService;
        this.deduplicationService = deduplicationService;
        this.fileRepository = fileRepository;
    }

    /**
     * Move file to trash (soft delete). Reversible.
     */
    public void moveToTrash(String fileId) {
        FileMetadata file = metadataService.getFile(fileId);
        file.trash();
        fileRepository.save(file);
    }

    /**
     * Restore file from trash. Reverses soft delete.
     */
    public void restoreFromTrash(String fileId) {
        FileMetadata file = metadataService.getFile(fileId);
        if (!file.isTrashed()) {
            throw new FileStorageException("File " + fileId + " is not in trash");
        }
        file.restore();
        fileRepository.save(file);
    }

    /**
     * Permanently delete a file. IRREVERSIBLE.
     * Releases chunks, quota, and metadata.
     */
    public void permanentlyDelete(String fileId) {
        FileMetadata file = metadataService.getFile(fileId);

        // Release chunk references for ALL versions
        List<FileVersion> versions = versionService.getVersionHistory(fileId);
        for (FileVersion version : versions) {
            deduplicationService.releaseChunks(version.getChunkHashes());
        }

        // Release storage quota
        metadataService.releaseQuota(file.getOwnerId(), file.getSize());

        // Delete metadata
        fileRepository.delete(fileId);
    }

    /**
     * List all trashed files for a user. Powers the "Trash" view.
     */
    public List<FileMetadata> listTrash(String ownerId) {
        return fileRepository.findByOwnerId(ownerId).stream()
            .filter(FileMetadata::isTrashed)
            .toList();
    }

    /**
     * Purge files that have been in trash longer than retention period.
     * Run as a scheduled background task (e.g., daily cron).
     */
    public int purgeExpiredTrash() {
        Instant cutoff = Instant.now().minus(TRASH_RETENTION_DAYS, ChronoUnit.DAYS);
        int purgedCount = 0;

        // Find all trashed files older than cutoff
        // In production, this would be a database query with index on trashedAt
        for (FileMetadata file : fileRepository.findByOwnerId("*")) { // simplified
            if (file.isTrashed() && file.getLastModifiedAt().isBefore(cutoff)) {
                permanentlyDelete(file.getFileId());
                purgedCount++;
            }
        }

        return purgedCount;
    }
}
```

### 7.9 FileStorageService (Facade)

```java
/**
 * FACADE: single entry point for all file storage operations.
 * Hides 8 sub-services behind a clean, unified API.
 *
 * The Controller calls FileStorageService methods.
 * FileStorageService orchestrates the sub-services.
 * Sub-services never call each other directly (prevents circular deps).
 *
 * CALL CHAIN FOR UPLOAD:
 *   Controller.uploadFile()
 *     -> FileStorageService.upload()
 *       -> UploadService.startUpload()         (create session)
 *       -> UploadService.uploadChunk() x N     (upload each chunk)
 *       -> UploadService.finalizeUpload()      (create metadata + version)
 *       -> SyncService.recordEvent()           (notify other devices)
 *
 * CALL CHAIN FOR DOWNLOAD:
 *   Controller.downloadFile()
 *     -> FileStorageService.download()
 *       -> SharingService.checkPermission()    (access control)
 *       -> DownloadService.download()          (reassemble chunks)
 *
 * WHY FACADE?
 *   Without Facade: Controller calls 4 services in sequence. Gets order wrong = bug.
 *   With Facade: Controller calls 1 method. Facade handles orchestration correctly.
 *   Reduces coupling: Controller depends on 1 class, not 8.
 */
public class FileStorageService {

    private final UploadService uploadService;
    private final DownloadService downloadService;
    private final MetadataService metadataService;
    private final DeduplicationService deduplicationService;
    private final SyncService syncService;
    private final VersionService versionService;
    private final SharingService sharingService;
    private final TrashService trashService;

    public FileStorageService(UploadService uploadService, DownloadService downloadService,
                              MetadataService metadataService, DeduplicationService deduplicationService,
                              SyncService syncService, VersionService versionService,
                              SharingService sharingService, TrashService trashService) {
        this.uploadService = uploadService;
        this.downloadService = downloadService;
        this.metadataService = metadataService;
        this.deduplicationService = deduplicationService;
        this.syncService = syncService;
        this.versionService = versionService;
        this.sharingService = sharingService;
        this.trashService = trashService;
    }

    // --- Upload Flow ---

    public String startUpload(String userId, String fileName, String path,
                              long totalSize, String mimeType) {
        return uploadService.startUpload(userId, fileName, path, totalSize, mimeType);
    }

    public boolean uploadChunk(String sessionId, int chunkIndex,
                               byte[] data, String expectedHash) {
        return uploadService.uploadChunk(sessionId, chunkIndex, data, expectedHash);
    }

    public FileMetadata finalizeUpload(String sessionId, String deviceId) {
        FileMetadata metadata = uploadService.finalizeUpload(sessionId);
        syncService.recordEvent(metadata.getOwnerId(), metadata.getFileId(),
            SyncEventType.CREATED, deviceId);
        return metadata;
    }

    // --- Download Flow ---

    public byte[] download(String fileId, String userId) {
        FileMetadata file = metadataService.getFile(fileId);
        // Owner always has access; otherwise check sharing permissions
        if (!file.getOwnerId().equals(userId)) {
            sharingService.checkPermission(fileId, userId, SharePermission.DOWNLOAD);
        }
        return downloadService.download(fileId);
    }

    public byte[] downloadVersion(String fileId, int versionNumber, String userId) {
        return downloadService.downloadVersion(fileId, versionNumber);
    }

    // --- Sync Flow ---

    public List<SyncEvent> sync(String userId, String deviceId) {
        return syncService.getEventsSinceCursor(userId, deviceId);
    }

    // --- Share Flow ---

    public ShareLink share(String fileId, String userId, SharePermission permission,
                           Instant expiresAt, String password) {
        FileMetadata file = metadataService.getFile(fileId);
        if (!file.getOwnerId().equals(userId)) {
            throw new PermissionDeniedException("Only the owner can create share links");
        }
        return sharingService.createShareLink(fileId, permission, expiresAt, password, userId);
    }

    // --- Version History Flow ---

    public List<FileVersion> getVersionHistory(String fileId) {
        return versionService.getVersionHistory(fileId);
    }

    public FileVersion rollback(String fileId, int targetVersion, String userId) {
        return versionService.rollbackToVersion(fileId, targetVersion, userId);
    }

    // --- Trash Flow ---

    public void moveToTrash(String fileId, String userId, String deviceId) {
        trashService.moveToTrash(fileId);
        syncService.recordEvent(userId, fileId, SyncEventType.DELETED, deviceId);
    }

    public void restoreFromTrash(String fileId) {
        trashService.restoreFromTrash(fileId);
    }

    // --- Folder Operations ---

    public Folder createFolder(String name, String parentId, String ownerId) {
        return metadataService.createFolder(name, parentId, ownerId);
    }

    public List<FileMetadata> listFiles(String ownerId, String path) {
        return metadataService.listFiles(ownerId, path);
    }

    public List<FileMetadata> search(String namePattern) {
        return metadataService.searchByName(namePattern);
    }
}
```

---

## 8. Concurrency Considerations

```
+=====================================================================+
|          CONCURRENCY MODEL FOR FILE STORAGE                          |
+=====================================================================+

PROBLEM:
  Multiple users uploading/downloading simultaneously.
  Same file edited from two devices.
  Dedup hash index accessed by many upload threads at once.
  Quota updates must be atomic (no over-provisioning).

APPROACH: Fine-grained locking at different levels

  +-----------------------------------------------------+
  |  LEVEL 1: Block Store (hash-level idempotency)       |
  |                                                      |
  |  ConcurrentHashMap<hash, byte[]>                     |
  |  storeBlock() is idempotent: same hash = same bytes  |
  |  Two threads storing the same hash concurrently:     |
  |    Thread-1: put("abc", data1)                       |
  |    Thread-2: put("abc", data1)  (same data!)         |
  |    Result: data1 stored once. Both succeed. Safe.    |
  |                                                      |
  |  WHY SAFE? Content-addressable = same hash means     |
  |  same bytes. Overwriting with identical data is a    |
  |  no-op. No lock needed for store operations.         |
  +-----------------------------------------------------+

  +-----------------------------------------------------+
  |  LEVEL 2: Dedup Index (AtomicInteger ref counts)     |
  |                                                      |
  |  refCount: ConcurrentHashMap<hash, AtomicInteger>    |
  |                                                      |
  |  Thread-1: refCount["abc"].incrementAndGet() -> 2    |
  |  Thread-2: refCount["abc"].incrementAndGet() -> 3    |
  |                                                      |
  |  AtomicInteger handles concurrent increments.        |
  |  No explicit lock needed for reference counting.     |
  +-----------------------------------------------------+

  +-----------------------------------------------------+
  |  LEVEL 3: Upload Sessions (per-session isolation)    |
  |                                                      |
  |  ConcurrentHashMap<sessionId, UploadSession>         |
  |                                                      |
  |  Each upload session is independent.                 |
  |  Two users uploading different files: zero contention|
  |  Same user resuming upload: sessionId is unique.     |
  |                                                      |
  |  Within a session: ConcurrentHashMap for chunk map.  |
  |  Chunks can arrive out of order and concurrently.    |
  +-----------------------------------------------------+

  +-----------------------------------------------------+
  |  LEVEL 4: Storage Quota (synchronized on user)       |
  |                                                      |
  |  Quota updates MUST be atomic:                       |
  |    Thread-1: checkQuota() -> 5GB free -> proceed     |
  |    Thread-2: checkQuota() -> 5GB free -> proceed     |
  |    Thread-1: consumeQuota(4GB)                       |
  |    Thread-2: consumeQuota(4GB) -> OVER QUOTA!        |
  |                                                      |
  |  Solution: synchronized(user) for check-then-update  |
  |  Or: AtomicLong for usedBytes with CAS loop          |
  +-----------------------------------------------------+

  +-----------------------------------------------------+
  |  LEVEL 5: Sync Events (append-only log)              |
  |                                                      |
  |  CopyOnWriteArrayList for event log:                 |
  |    - Appends are thread-safe                         |
  |    - Reads (sync queries) see consistent snapshot    |
  |    - No locking on reads                             |
  |                                                      |
  |  AtomicLong for sequence counter:                    |
  |    - Guarantees unique, monotonically increasing IDs |
  |    - No gaps, no duplicates                          |
  +-----------------------------------------------------+
```

### Thread-Safety by Component

```java
/**
 * CONCURRENCY MAP -- what protects what:
 *
 * +---------------------------+--------------------------------------+-------------------+
 * | Component                 | Concurrency Mechanism                | Why This Choice   |
 * +---------------------------+--------------------------------------+-------------------+
 * | InMemoryBlockStore        | ConcurrentHashMap<hash, byte[]>      | Idempotent writes |
 * | HashBasedDedup.hashIndex  | ConcurrentHashMap<hash, storageKey>  | Lock-free reads   |
 * | HashBasedDedup.refCount   | ConcurrentHashMap<hash, AtomicInt>   | Atomic increments |
 * | UploadService.sessions    | ConcurrentHashMap<sessionId, Session>| Per-session       |
 * | UploadSession.chunks      | ConcurrentHashMap<index, hash>       | Out-of-order safe |
 * | SyncService.eventLog      | CopyOnWriteArrayList                 | Append-mostly     |
 * | SyncService.seqCounter    | AtomicLong                           | Monotonic IDs     |
 * | SharingService.links      | ConcurrentHashMap + CopyOnWriteArray | Concurrent shares |
 * | StorageQuota.usedBytes    | synchronized or AtomicLong + CAS     | Atomic quota ops  |
 * | MetadataService (repos)   | ConcurrentHashMap-backed             | Lock-free lookups |
 * +---------------------------+--------------------------------------+-------------------+
 *
 * KEY INSIGHT: Content-addressable storage is naturally concurrent-friendly.
 * storeBlock("abc123", data) is idempotent -- calling it twice with the same
 * hash produces the same result. This eliminates the need for locks on writes
 * to the block store. The dedup layer handles the "should we store?" decision.
 */
```

### Why AtomicInteger for Reference Counts (Not synchronized)

```
+----------------------------------------------------------------------+
| AtomicInteger vs. synchronized for ref counting:                      |
|                                                                       |
| synchronized:                                                         |
|   synchronized(lock) {                                                |
|       int count = refCount.get(hash);                                 |
|       refCount.put(hash, count + 1);                                  |
|   }                                                                   |
|   PROBLEM: locks the ENTIRE map for one hash update.                  |
|   Other hashes blocked even though there's no contention.             |
|                                                                       |
| AtomicInteger:                                                        |
|   refCount.get(hash).incrementAndGet();                               |
|   ADVANTAGE: lock-free CAS operation. Only the ONE hash is affected.  |
|   Other hashes proceed in parallel with zero contention.              |
|                                                                       |
| For file storage with millions of chunks, the difference is massive:  |
|   synchronized: one upload blocks all other uploads during refCount   |
|   AtomicInteger: uploads to different chunks are fully parallel        |
+----------------------------------------------------------------------+
```

---

## 9. SOLID Principles Applied

```
+=====================================================================+
|                    SOLID IN THIS DESIGN                               |
+=====================================================================+

S - SINGLE RESPONSIBILITY
+----------------------------+------------------------------------------+
| Class                      | Single Responsibility                    |
+----------------------------+------------------------------------------+
| FileMetadata               | Hold file metadata (name, size, hash)    |
| FileChunk                  | Represent one chunk of a file            |
| FileVersion                | Snapshot a file at a point in time       |
| FixedSizeChunking          | Split files at fixed boundaries          |
| ContentDefinedChunking     | Split files at content-defined boundaries|
| HashBasedDedup             | Check and manage content dedup           |
| UploadService              | Chunked upload orchestration             |
| DownloadService            | Chunk reassembly for download            |
| MetadataService            | File/folder CRUD and quota               |
| DeduplicationService       | Dedup orchestration and metrics          |
| SyncService                | Multi-device sync and conflicts          |
| VersionService             | Version CRUD and rollback                |
| SharingService             | Share links and permissions              |
| TrashService               | Soft delete and purge                    |
| FileStorageService         | Orchestrate the full workflow (Facade)   |
+----------------------------+------------------------------------------+

O - OPEN/CLOSED
  Adding a new chunking algorithm:
    1. Create RabinKarpChunking implements ChunkingStrategy    <-- NEW file
    2. Wire in AppConfig                                        <-- ONE line change
    3. ZERO changes to UploadService, FileStorageService

  Adding a new dedup strategy:
    1. Create BloomFilterDedup implements DeduplicationStrategy <-- NEW file
    2. Wire in AppConfig                                        <-- ONE line change
    3. ZERO changes to DeduplicationService

  Adding a new conflict strategy:
    1. Create MergeStrategy implements ConflictStrategy         <-- NEW file
    2. Wire in AppConfig                                        <-- ONE line change
    3. ZERO changes to SyncService

L - LISKOV SUBSTITUTION
  FixedSizeChunking and ContentDefinedChunking are interchangeable.
  Both implement ChunkingStrategy. UploadService works identically with either.

  HashBasedDedup and NoDedup are interchangeable.
  Both implement DeduplicationStrategy. DeduplicationService works identically.

  Test: Swap FixedSizeChunking for ContentDefinedChunking in AppConfig.
        Upload same file. Download same file. Bytes match. LSP holds.

I - INTERFACE SEGREGATION
  ChunkingStrategy: chunk(), getStrategyName()
    -- Only chunking math. No storage, no dedup.

  DeduplicationStrategy: isDuplicate(), store(), getStorageKey(), decrementReference()
    -- Only dedup logic. No chunking, no sync.

  ConflictStrategy: resolve(), getStrategyName()
    -- Only conflict resolution. No upload, no download.

  BlockStore: storeBlock(), getBlock(), deleteBlock(), exists()
    -- Only raw byte storage. No metadata, no dedup logic.

  Each interface is small, focused, and used by exactly one consumer.

D - DEPENDENCY INVERSION
  UploadService depends on ChunkingStrategy (interface), not FixedSizeChunking.
  DeduplicationService depends on DeduplicationStrategy (interface), not HashBasedDedup.
  SyncService depends on ConflictStrategy (interface), not KeepBothStrategy.

  Dependency graph (all arrows point toward abstractions):

    UploadService -----------> ChunkingStrategy (interface)
                                    ^
                                    |
                              FixedSizeChunking (concrete)

    DeduplicationService -----> DeduplicationStrategy (interface)
                                    ^
                                    |
                              HashBasedDedup (concrete)

    SyncService --------------> ConflictStrategy (interface)
                                    ^
                                    |
                              KeepBothStrategy (concrete)

    DownloadService ----------> BlockStore (interface)
                                    ^
                                    |
                              InMemoryBlockStore (concrete)
```

---

## 10. Sample Workflows

### 10.1 Chunked Upload with Dedup (Happy Path)

```
SCENARIO: Alice uploads "presentation.pptx" (12MB) from her laptop.
          3 of the 4 chunks are new. 1 chunk (company logo) already exists
          from Bob's earlier upload (dedup hit).

  Alice's Client                   Server                          BlockStore
       |                              |                                |
       | POST /upload/start           |                                |
       |   name: "presentation.pptx"  |                                |
       |   size: 12582912 (12MB)      |                                |
       |   mimeType: "application/    |                                |
       |     vnd.openxmlformats..."   |                                |
       |---------------------------->  |                                |
       |                              |                                |
       |                              | MetadataService.checkQuota()   |
       |                              |   Alice: 2GB used / 15GB limit |
       |                              |   12MB fits? YES               |
       |                              |                                |
       |   <-- sessionId: "sess-001"  |                                |
       |                              |                                |
       | --- Chunk 0: 4MB ----------->|                                |
       |     hash: "a3f2c8..."        |                                |
       |                              | DeduplicationService:          |
       |                              |   isDuplicate("a3f2c8")? NO    |
       |                              |   Store in BlockStore -------->| put("a3f2c8", bytes)
       |                              |   refCount["a3f2c8"] = 1       |
       |   <-- stored (new)           |                                |
       |                              |                                |
       | --- Chunk 1: 4MB ----------->|                                |
       |     hash: "b7c1d4..."        |                                |
       |                              | DeduplicationService:          |
       |                              |   isDuplicate("b7c1d4")? YES!  |
       |                              |   (Bob uploaded this chunk      |
       |                              |    as company-logo already)    |
       |                              |   refCount["b7c1d4"]++ (2)     |
       |                              |   Skip storage. Save 4MB.     |
       |   <-- deduplicated (skip)    |                                |
       |                              |                                |
       | --- Chunk 2: 4MB ----------->|                                |
       |     hash: "d4e9f1..."        |                                |
       |                              | Store new block --------------->| put("d4e9f1", bytes)
       |   <-- stored (new)           |                                |
       |                              |                                |
       | POST /upload/finalize        |                                |
       |   sessionId: "sess-001"      |                                |
       |---------------------------->  |                                |
       |                              | Create FileMetadata:           |
       |                              |   fileId: "file-001"           |
       |                              |   name: "presentation.pptx"   |
       |                              |   size: 12582912               |
       |                              |   hash: SHA256("a3f2..b7c1..d4e9..")
       |                              |                                |
       |                              | Create FileVersion v1:         |
       |                              |   chunks: ["a3f2","b7c1","d4e9"]
       |                              |                                |
       |                              | Update quota: +12MB            |
       |                              | Record SyncEvent: CREATED      |
       |                              |                                |
       |   <-- FileMetadata           |                                |
       |       (upload complete)      |                                |

  RESULT:
    Physical storage used: 8MB (2 new chunks x 4MB, 1 deduped)
    Logical storage used:  12MB (what Alice's quota shows)
    Dedup savings:         4MB (33% on this upload)
    Bandwidth saved:       4MB (chunk 1 hash check only, no bytes sent)
```

### 10.2 Resumable Upload After Failure

```
SCENARIO: Alice uploads a 16MB file. Connection drops after chunk 1.
          She reconnects and resumes from chunk 2.

  Alice's Client                   Server
       |                              |
       | Start upload (16MB, 4 chunks)|
       |----------------------------->|
       |   <-- sessionId: "sess-002"  |
       |                              |
       | Upload chunk 0 ------------>| stored
       | Upload chunk 1 ------------>| stored
       | Upload chunk 2 --X  (connection drops at 50%)
       |                              |
       |                              | Session "sess-002" still active:
       |                              |   chunk 0: DONE
       |                              |   chunk 1: DONE
       |                              |   chunk 2: NOT RECEIVED
       |                              |   chunk 3: NOT RECEIVED
       |                              |
       |   ... time passes ...        |
       |                              |
       | GET /upload/resume/sess-002  |
       |----------------------------->|
       |                              |
       |   <-- missing: [2, 3]        | Session knows which chunks are missing
       |                              |
       | Upload chunk 2 ------------>| stored
       | Upload chunk 3 ------------>| stored
       |                              |
       | Finalize upload ----------->| all 4 chunks present, create metadata
       |   <-- FileMetadata           |
       |                              |

  WITHOUT RESUMABLE: 16MB uploaded, 8MB wasted, re-upload 16MB = 32MB total bandwidth
  WITH RESUMABLE:    8MB uploaded, resume 8MB = 16MB total bandwidth (50% savings)
  On mobile with spotty connection, this is a CRITICAL feature.
```

### 10.3 Multi-Device Sync with Conflict (KeepBoth)

```
SCENARIO: Alice edits "report.docx" on her laptop AND phone while offline.
          Both devices come online. Conflict detected.
          KeepBothStrategy renames the conflicting version.

  Alice's Laptop (offline)         Server (v3)           Alice's Phone (offline)
       |                              |                        |
       | Edit report.docx             |                        | Edit report.docx
       | (based on v3)                |                        | (based on v3)
       | local version: v3-laptop     |                        | local version: v3-phone
       |                              |                        |
       |  ... both come online ...    |                        |
       |                              |                        |
       | Sync: push v3-laptop ------->|                        |
       |                              | No conflict (server    |
       |                              | still at v3).          |
       |                              | Accept. Server -> v4   |
       |   <-- sync OK (v4)           |                        |
       |                              |                        |
       |                              |<--- Sync: push v3-phone
       |                              |                        |
       |                              | CONFLICT DETECTED:     |
       |                              |   Phone base: v3       |
       |                              |   Server current: v4   |
       |                              |   v3 != v4 -> conflict |
       |                              |                        |
       |                              | KeepBothStrategy:      |
       |                              |   winner = server (v4) |
       |                              |   loser = phone ver    |
       |                              |   rename loser:        |
       |                              |   "report (alice-phone |
       |                              |    conflict on         |
       |                              |    2025-01-15).docx"   |
       |                              |                        |
       |                              | Save conflict copy     |
       |                              | as new file.           |
       |                              | Server now has:        |
       |                              |   report.docx (v4)     |
       |                              |   report (conflict).docx
       |                              |                        |
       |                              |--- sync event -------->|
       |<---- sync event -------------|   "conflict resolved"  |
       |                              |                        |

  RESULT:
    report.docx = laptop version (v4, winner because it synced first)
    report (alice-phone conflict on 2025-01-15).docx = phone version (kept as copy)
    ZERO data loss. Alice can manually merge and delete the conflict copy.
```

### 10.4 Version History and Rollback

```
SCENARIO: Bob has edited "budget.xlsx" 5 times. He realizes version 3
          was the correct one and wants to roll back.

  Bob's Client                     Server
       |                              |
       | GET /files/file-007/versions |
       |----------------------------->|
       |                              |
       |   <-- versions:              |
       |     v1: [h_A, h_B, h_C]     | 2025-01-10, 12MB
       |     v2: [h_A, h_D, h_C]     | 2025-01-11, 12MB (chunk B changed to D)
       |     v3: [h_A, h_D, h_E]     | 2025-01-12, 12MB (chunk C changed to E)
       |     v4: [h_F, h_D, h_E]     | 2025-01-13, 12MB (chunk A changed to F)
       |     v5: [h_F, h_G, h_E]     | 2025-01-14, 12MB (chunk D changed to G)
       |                              |
       | POST /files/file-007/rollback|
       |   targetVersion: 3           |
       |----------------------------->|
       |                              |
       |                              | VersionService.rollbackToVersion():
       |                              |   target = v3: [h_A, h_D, h_E]
       |                              |   latest = v5
       |                              |   Create v6: [h_A, h_D, h_E]  <-- same chunks as v3
       |                              |
       |                              |   Physical cost: 0 new bytes!
       |                              |   Chunks h_A, h_D, h_E already exist in BlockStore.
       |                              |   Just create a new version pointing to them.
       |                              |   refCount[h_A]++, refCount[h_D]++, refCount[h_E]++
       |                              |
       |   <-- v6 created             |
       |       (content matches v3)   |
       |                              |

  KEY INSIGHT: Rollback creates a NEW version, not deleting old ones.
    - Version history preserved: v1, v2, v3, v4, v5, v6
    - v6 has same content as v3 but is a distinct version
    - "Undo rollback" = rollback to v5 (everything is reversible)
    - Physical storage cost of rollback = 0 bytes (shared chunks)
```

### 10.5 Share Link with Password and Expiry

```
SCENARIO: Alice shares "confidential-report.pdf" with an external partner.
          Link has a password, expires in 7 days, and allows download only.

  Alice's Client                   Server                     Partner's Browser
       |                              |                              |
       | POST /files/file-010/share   |                              |
       |   permission: DOWNLOAD       |                              |
       |   expiresAt: +7 days         |                              |
       |   password: "s3cure2025"     |                              |
       |----------------------------->|                              |
       |                              | SharingService:              |
       |                              |   Create ShareLink:          |
       |                              |     linkId: "lnk-abc123"    |
       |                              |     permission: DOWNLOAD     |
       |                              |     expires: 2025-01-22      |
       |                              |     password: "s3cure2025"   |
       |                              |                              |
       |   <-- share URL:             |                              |
       |   /share/lnk-abc123         |                              |
       |                              |                              |
       | (Alice emails URL to partner)|                              |
       |                              |                              |
       |                              |  GET /share/lnk-abc123 <----|
       |                              |    password: "s3cure2025"    |
       |                              |                              |
       |                              |  SharingService:             |
       |                              |    link.isValid()? YES       |
       |                              |      (not expired, not revoked)
       |                              |    link.checkPassword()? YES |
       |                              |    permission: DOWNLOAD      |
       |                              |                              |
       |                              |  DownloadService:            |
       |                              |    Reassemble chunks         |
       |                              |    Return file bytes         |
       |                              |                              |
       |                              |  --> file bytes ------------>|
       |                              |                              |

  Day 8: Partner tries link again
       |                              |  GET /share/lnk-abc123 <----|
       |                              |  link.isValid()? NO          |
       |                              |    (past expiresAt)          |
       |                              |  --> 403 Forbidden           |
```

---

## 11. Design Patterns Used

```
+=====================================================================+
|                    DESIGN PATTERNS SUMMARY                           |
+=====================================================================+

+-------------------+------------------------+------------------------------------------+
| Pattern           | Where                  | Why                                      |
+-------------------+------------------------+------------------------------------------+
| Strategy          | ChunkingStrategy       | Swap Fixed <-> ContentDefined chunking   |
|                   | DeduplicationStrategy  | Swap HashBased <-> NoDedup               |
|                   | ConflictStrategy       | Swap LastWriterWins <-> KeepBoth         |
+-------------------+------------------------+------------------------------------------+
| Facade            | FileStorageService     | Single entry point hides 8 sub-services. |
|                   |                        | Controller calls one method, not eight.  |
+-------------------+------------------------+------------------------------------------+
| Builder           | FileMetadata           | Flexible construction with 10+ fields.   |
|                   |                        | Readable test setup. Optional fields.    |
+-------------------+------------------------+------------------------------------------+
| Repository        | *Repository interfaces | Separate domain logic from data access.  |
|                   |                        | Swap InMemory for DynamoDB in production. |
+-------------------+------------------------+------------------------------------------+
| Content-          | BlockStore +           | Hash IS the address. Same hash = same    |
| Addressable       | HashBasedDedup         | bytes. Enables dedup, versioning, and    |
| Storage           |                        | integrity checking in one model.         |
+-------------------+------------------------+------------------------------------------+
| Observer          | SyncService events     | Devices subscribe to change events.      |
|                   |                        | Upload triggers sync notification to all |
|                   |                        | other devices.                           |
+-------------------+------------------------+------------------------------------------+
| Factory           | AppConfig              | Centralizes object creation and wiring.  |
|                   |                        | Pure constructor injection, no framework.|
+-------------------+------------------------+------------------------------------------+
| Composite         | Folder + FileMetadata  | Folder contains files and sub-folders.   |
|                   |                        | Hierarchical tree structure.             |
+-------------------+------------------------+------------------------------------------+
```

### Pattern Interaction Diagram

```
  Controller
      |
      | calls
      v
  FileStorageService [Facade]
      |
      | delegates to
      +------+----------+----------+----------+----------+
      |      |          |          |          |          |
      v      v          v          v          v          v
  Upload  Download  Metadata   Dedup     Sync      Sharing
  Service Service   Service    Service   Service   Service
      |      |          |          |          |          |
      |      |          |          |          |          |
      |      |          |          |          v          |
      |      |          |          |     ConflictStrategy [Strategy]
      |      |          |          |          |
      |      |          |          |          +---> KeepBothStrategy
      |      |          |          |          +---> LastWriterWinsStrategy
      |      |          |          |
      |      |          |          v
      |      |          |     DeduplicationStrategy [Strategy]
      |      |          |          |
      |      |          |          +---> HashBasedDedup
      |      |          |          +---> NoDedup
      |      |          |
      v      v          v
  ChunkingStrategy [Strategy]     *Repository [Repository]
      |                                |
      +---> FixedSizeChunking          +---> InMemoryFileRepository
      +---> ContentDefinedChunking     +---> InMemoryFolderRepository
                                       +---> InMemoryVersionRepository
      |
      v
  BlockStore [Repository]
      |
      +---> InMemoryBlockStore
      +---> (S3BlockStore -- production)

  FileMetadata [Builder]
      |
      +---> new FileMetadata.Builder().fileId("...").name("...").build()
```

---

## 12. Extensibility Points

```
+=====================================================================+
|                    EXTENSIBILITY POINTS                               |
+=====================================================================+

Each extensibility point requires ZERO changes to existing services.
Only new files + one-line wiring change in AppConfig.

1. NEW CHUNKING ALGORITHM
   +------------------------------------------------------------------+
   | Example: Add Gear chunking (faster than Rabin for large files)   |
   |                                                                    |
   | Step 1: Create GearChunking implements ChunkingStrategy            |
   | Step 2: Wire in AppConfig:                                         |
   |           ChunkingStrategy strategy = new GearChunking();          |
   | Step 3: Done. UploadService, FileStorageService unchanged.         |
   +------------------------------------------------------------------+

2. NEW DEDUP STRATEGY
   +------------------------------------------------------------------+
   | Example: Add Bloom filter pre-check (reduce hash index lookups)  |
   |                                                                    |
   | Step 1: Create BloomFilterDedup implements DeduplicationStrategy    |
   |         (Bloom filter for fast "definitely not duplicate" check,   |
   |          fall back to hash index for "probably duplicate" check)   |
   | Step 2: Wire in AppConfig                                          |
   | Step 3: Done. DeduplicationService, UploadService unchanged.       |
   +------------------------------------------------------------------+

3. NEW CONFLICT RESOLUTION
   +------------------------------------------------------------------+
   | Example: Add three-way merge (diff3) for text files              |
   |                                                                    |
   | Step 1: Create ThreeWayMerge implements ConflictStrategy           |
   |         (compute diff between base, local, remote; auto-merge     |
   |          non-overlapping changes; flag overlapping as conflict)    |
   | Step 2: Wire in AppConfig                                          |
   | Step 3: Done. SyncService unchanged.                               |
   +------------------------------------------------------------------+

4. NEW STORAGE BACKEND
   +------------------------------------------------------------------+
   | Example: Add Amazon S3 block store                                |
   |                                                                    |
   | Step 1: Create S3BlockStore implements BlockStore                  |
   |           storeBlock() -> s3Client.putObject(bucket, hash, data)  |
   |           getBlock()   -> s3Client.getObject(bucket, hash)        |
   | Step 2: Wire in AppConfig:                                         |
   |           BlockStore store = new S3BlockStore(s3Client, "my-bucket");
   | Step 3: Done. UploadService, DownloadService unchanged.            |
   +------------------------------------------------------------------+

5. NEW REPOSITORY BACKEND
   +------------------------------------------------------------------+
   | Example: Add PostgreSQL metadata storage                          |
   |                                                                    |
   | Step 1: Create PostgresFileRepository implements FileRepository    |
   | Step 2: Create PostgresFolderRepository implements FolderRepository|
   | Step 3: Wire in AppConfig                                          |
   | Step 4: Done. MetadataService, all services unchanged.             |
   +------------------------------------------------------------------+

6. ADD ENCRYPTION AT REST
   +------------------------------------------------------------------+
   | Example: AES-256 encryption for stored blocks                     |
   |                                                                    |
   | Step 1: Create EncryptedBlockStore implements BlockStore           |
   |           storeBlock() -> encrypt(data) -> delegate.storeBlock()  |
   |           getBlock()   -> delegate.getBlock() -> decrypt(data)    |
   |         Decorator pattern over InMemoryBlockStore or S3BlockStore  |
   | Step 2: Wire in AppConfig:                                         |
   |           BlockStore inner = new InMemoryBlockStore();              |
   |           BlockStore store = new EncryptedBlockStore(inner, key);   |
   | Step 3: Done. All services see plain BlockStore interface.         |
   +------------------------------------------------------------------+

7. ADD THUMBNAIL GENERATION
   +------------------------------------------------------------------+
   | Example: Auto-generate thumbnails for image/PDF uploads           |
   |                                                                    |
   | Step 1: Create ThumbnailService (new service)                      |
   | Step 2: Inject into FileStorageService                             |
   | Step 3: After finalizeUpload(), call thumbnailService.generate()   |
   | Step 4: Store thumbnail as a separate file with reference          |
   | Step 5: Existing services untouched except Facade (new delegation) |
   +------------------------------------------------------------------+
```

### AppConfig Wiring (How It All Connects)

```java
/**
 * Pure constructor injection. No framework, no annotations, no magic.
 * Every dependency is explicit and visible in this one file.
 *
 * INTERVIEW TIP: When the interviewer asks "how do you wire this up?",
 * point to this class. It shows you understand dependency injection
 * without needing Spring. Shows you understand the PRINCIPLE, not just the tool.
 */
public class AppConfig {

    // --- Block Store ---
    private final BlockStore blockStore = new InMemoryBlockStore();

    // --- Strategies (swap implementations here) ---
    private final ChunkingStrategy chunkingStrategy = new ContentDefinedChunking();
    // Alternative: new FixedSizeChunking();

    private final DeduplicationStrategy dedupStrategy = new HashBasedDedup(blockStore);
    // Alternative: new NoDedup(blockStore);

    private final ConflictStrategy conflictStrategy = new KeepBothStrategy();
    // Alternative: new LastWriterWinsStrategy();

    // --- Repositories ---
    private final FileRepository fileRepository = new InMemoryFileRepository();
    private final FolderRepository folderRepository = new InMemoryFolderRepository();
    private final VersionRepository versionRepository = new InMemoryVersionRepository();
    private final UserRepository userRepository = new InMemoryUserRepository();

    // --- Services ---
    private final MetadataService metadataService =
        new MetadataService(fileRepository, folderRepository, userRepository);

    private final DeduplicationService deduplicationService =
        new DeduplicationService(dedupStrategy);

    private final VersionService versionService =
        new VersionService(versionRepository);

    private final UploadService uploadService =
        new UploadService(chunkingStrategy, deduplicationService,
                          metadataService, versionService, blockStore);

    private final DownloadService downloadService =
        new DownloadService(metadataService, versionService, blockStore, deduplicationService);

    private final SyncService syncService =
        new SyncService(conflictStrategy, metadataService);

    private final SharingService sharingService = new SharingService();

    private final TrashService trashService =
        new TrashService(metadataService, versionService, deduplicationService, fileRepository);

    // --- Facade ---
    private final FileStorageService fileStorageService =
        new FileStorageService(uploadService, downloadService, metadataService,
                               deduplicationService, syncService, versionService,
                               sharingService, trashService);

    // --- Controller ---
    private final FileStorageController controller =
        new FileStorageController(fileStorageService);

    // --- Getters ---
    public FileStorageController getController()          { return controller; }
    public FileStorageService getFileStorageService()     { return fileStorageService; }

    /**
     * To switch from ContentDefined to Fixed chunking, change ONE line:
     *   chunkingStrategy = new FixedSizeChunking();
     *
     * To disable dedup (compliance mode), change ONE line:
     *   dedupStrategy = new NoDedup(blockStore);
     *
     * To switch from KeepBoth to LastWriterWins, change ONE line:
     *   conflictStrategy = new LastWriterWinsStrategy();
     *
     * To add S3 storage, change ONE line:
     *   blockStore = new S3BlockStore(s3Client, "my-bucket");
     *
     * Everything else stays the same. That is the power of Strategy + DI.
     */
}
```

### Chunking Strategy Decision Matrix (Interview Cheat Sheet)

```
+=====================================================================+
|       WHEN TO USE WHICH CHUNKING STRATEGY (INTERVIEW ANSWER)         |
+=====================================================================+

  Choose Fixed-Size Chunking when:
    [x] Files are write-once (backup, archival, media)
    [x] Simplicity is paramount
    [x] Dedup is not critical (or files rarely change)
    [x] You need predictable chunk count for progress bars
    [x] Example: AWS Glacier, simple backup tools

  Choose Content-Defined Chunking (Rabin) when:
    [x] Files are frequently edited (documents, code, databases)
    [x] Cross-version dedup is critical (storage cost matters)
    [x] Delta sync is needed (only upload changed parts)
    [x] You can accept variable chunk sizes and complexity
    [x] Example: Dropbox, rsync, LBFS, Google Drive

  Choose No Chunking (whole file) when:
    [x] Files are small (< 1MB) -- chunking overhead > benefit
    [x] Files are always read in full (no range requests)
    [x] Simplicity > efficiency tradeoff is acceptable
    [x] Example: config files, small assets, profile pictures

+----------------------------------------------------------------------+
|  "Dropbox uses content-defined chunking with Rabin fingerprints      |
|   because their users frequently edit documents, and CDC ensures     |
|   only the modified regions re-upload. Fixed-size chunking would     |
|   cause ALL chunks after an insertion to shift, destroying dedup.    |
|   This is the core technical insight behind Dropbox's storage        |
|   efficiency."                                                        |
|                                                                       |
|   -- This paragraph will impress most interviewers.                  |
+----------------------------------------------------------------------+
```
