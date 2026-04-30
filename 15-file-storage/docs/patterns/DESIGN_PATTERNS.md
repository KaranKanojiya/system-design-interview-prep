# Design Patterns -- File Storage System (Google Drive / Dropbox)

> Quick reference for system design interviews. Each pattern includes the ugly
> anti-pattern first, then the clean solution, numbered call chain, ASCII diagram,
> and a one-liner you can drop in an interview.
>
> **Domain:** Cloud file storage where users upload, download, sync, share, and
> version files across devices. Three Strategy interfaces (ChunkingStrategy,
> DeduplicationStrategy, ConflictStrategy) make this a strategy-heavy project.
> Deduplication via content-addressable storage is THE core optimization --
> interviewers will ask you to walk through the chunking and dedup flow.
>
> **This is the FINAL project (15 of 15) in the system design series.**

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Strategy (x3) | Behavioral | ChunkingStrategy (FixedSize, ContentDefined), DeduplicationStrategy (HashBased, NoDedup), ConflictStrategy (LastWriterWins, KeepBoth) |
| 2 | Builder | Creational | FileMetadata.Builder with name, owner, size, checksum, immutable result |
| 3 | Factory | Creational | AppConfig wires strategies, repos, services |
| 4 | Repository (x4) | Structural (enterprise) | FileRepository, FolderRepository, VersionRepository, UserRepository |
| 5 | Facade | Structural | FileStorageService orchestrates upload -> chunk -> dedup -> store -> metadata -> version |
| 6 | Observer | Behavioral | SyncService observes file changes, notifies other devices |
| 7 | Flyweight | Structural | Deduplication -- same chunk content shared across files (content-addressable storage) |
| 8 | Composite | Structural | Folder hierarchy -- folders contain files AND subfolders (tree structure) |
| 9 | Memento | Behavioral | FileVersion stores file state snapshots for rollback |

---

## 1. Strategy Pattern (Behavioral) -- THE KEY PATTERN

**Three independent Strategy interfaces** power the core of this system.
This is the pattern interviewers care about most -- it directly answers
"How do you chunk large files?", "How do you deduplicate content?",
and "How do you resolve sync conflicts?"

### Strategy Interface A: ChunkingStrategy

Determines **how** a file is split into chunks for upload, dedup, and storage.

```java
public interface ChunkingStrategy {
    /**
     * Split a file's content into chunks for storage and deduplication.
     *
     * @param fileContent  the raw bytes of the file
     * @param fileName     the file name (for logging/diagnostics)
     * @return             ordered list of chunks
     */
    List<Chunk> chunkFile(byte[] fileContent, String fileName);
}
```

Two concrete strategies:

| Strategy | Algorithm | Use Case |
|----------|-----------|----------|
| FixedSizeChunkingStrategy | Split file into fixed N-byte blocks (e.g., 4 MB) | Simple, predictable, good for large media files |
| ContentDefinedChunkingStrategy | Rabin fingerprint rolling hash to find chunk boundaries | Handles insertions gracefully -- only changed chunks differ |

### Strategy Interface B: DeduplicationStrategy

Determines **how** duplicate chunks are detected to avoid storing the same bytes twice.

```java
public interface DeduplicationStrategy {
    /**
     * Check if this chunk already exists in storage.
     * If it does, return the existing chunk reference.
     * If not, return null (caller must store the new chunk).
     *
     * @param chunk  the chunk to check for duplicates
     * @return       existing ChunkReference if duplicate, null if new
     */
    ChunkReference findDuplicate(Chunk chunk);

    /**
     * Register a newly stored chunk in the dedup index.
     *
     * @param chunk      the chunk that was stored
     * @param reference  the storage reference (block ID, location)
     */
    void registerChunk(Chunk chunk, ChunkReference reference);
}
```

Two concrete strategies:

| Strategy | How It Works | Trade-off |
|----------|-------------|-----------|
| HashBasedDeduplicationStrategy | SHA-256 hash of chunk content; lookup in hash->blockId map | O(1) lookup, 32-byte key per chunk, collision-resistant |
| NoDeduplicationStrategy | Always returns null (no dedup) -- stores every chunk | Zero overhead, useful for encrypted files where dedup is impossible |

### Strategy Interface C: ConflictStrategy

Determines **how** conflicts are resolved when two devices edit the same file
offline and sync simultaneously.

```java
public interface ConflictStrategy {
    /**
     * Resolve a conflict between two versions of the same file.
     *
     * @param serverVersion  the current version on the server
     * @param clientVersion  the version the client is trying to upload
     * @param filePath       the file path (for naming conflict copies)
     * @return               resolution result (which version(s) to keep)
     */
    ConflictResolution resolve(FileVersion serverVersion,
                               FileVersion clientVersion,
                               String filePath);
}
```

Two concrete strategies:

| Strategy | Resolution Rule | When Used |
|----------|----------------|-----------|
| LastWriterWinsStrategy | Keep the version with the later timestamp; discard the other | Simple, risk of data loss -- Dropbox uses this for non-conflicting edits |
| KeepBothStrategy | Keep server version, save client version as "filename (conflict copy)" | No data loss, clutter risk -- Dropbox uses this for conflicting edits |

### Ugly Anti-Pattern -- Hardcoded Everything

```java
// UGLY: No chunking, no dedup, no conflict resolution.
// Upload entire file as one blob. Duplicate content everywhere.
// Sync conflicts? Overwrite and pray.

public class UglyFileStorageService {

    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, Long> timestamps = new HashMap<>();

    public void uploadFile(String path, byte[] content, String userId) {
        // No chunking -- store entire file as one blob
        // 2 GB video? One single entry. Good luck with network interruptions.
        files.put(path, content);
        timestamps.put(path, System.currentTimeMillis());
        System.out.println("Stored " + path + " (" + content.length + " bytes)");
        // If two users share a 500 MB ISO, it's stored TWICE
    }

    public byte[] downloadFile(String path) {
        return files.get(path);  // No resume, no partial download
    }

    public void syncFile(String path, byte[] clientContent, long clientTimestamp) {
        Long serverTimestamp = timestamps.get(path);
        if (serverTimestamp != null && serverTimestamp > clientTimestamp) {
            // "Conflict resolution": overwrite client silently
            System.out.println("CONFLICT: Server version newer, client changes LOST");
        } else {
            // Overwrite server version
            files.put(path, clientContent);
            timestamps.put(path, clientTimestamp);
        }
        // Cannot switch between LWW and keep-both -- hardcoded
    }

    // Cannot chunk files -- entire file re-uploaded on any change
    // Cannot deduplicate -- identical files stored multiple times
    // Cannot resume interrupted uploads -- no chunks to retry
    // Cannot version files -- no history, no rollback
    // Cannot share chunks between files -- monolithic blobs
}
```

**Problems:**
1. No chunking -- entire file re-uploaded on any edit, no resume on failure
2. No deduplication -- identical content stored N times (wastes storage, costs money)
3. Conflict resolution hardcoded as LWW -- cannot switch to keep-both
4. No versioning -- cannot rollback to previous file state
5. No folder hierarchy -- flat key-value store, no tree navigation
6. No sync notification -- other devices never learn about changes
7. Testing requires the full service -- no strategy injection

### Clean Solution -- Three Strategy Interfaces

```java
// CLEAN: ChunkingStrategy, DeduplicationStrategy, and ConflictStrategy
// are all injected. Each can be swapped, tested, and evolved independently.

public class CleanFileStorageService {

    private final ChunkingStrategy       chunkingStrategy;
    private final DeduplicationStrategy  dedupStrategy;
    private final ConflictStrategy       conflictStrategy;
    private final BlockStorageService    blockStorage;
    private final MetadataService        metadataService;
    private final VersionService         versionService;
    private final SyncService            syncService;

    public CleanFileStorageService(ChunkingStrategy chunkingStrategy,
                                   DeduplicationStrategy dedupStrategy,
                                   ConflictStrategy conflictStrategy,
                                   BlockStorageService blockStorage,
                                   MetadataService metadataService,
                                   VersionService versionService,
                                   SyncService syncService) {
        this.chunkingStrategy  = chunkingStrategy;
        this.dedupStrategy     = dedupStrategy;
        this.conflictStrategy  = conflictStrategy;
        this.blockStorage      = blockStorage;
        this.metadataService   = metadataService;
        this.versionService    = versionService;
        this.syncService       = syncService;
    }

    public UploadResult uploadFile(String path, byte[] content,
                                   String userId) {
        // 1. Chunk the file (Strategy A)
        List<Chunk> chunks = chunkingStrategy.chunkFile(content, path);

        // 2. Dedup each chunk (Strategy B)
        List<ChunkReference> references = new ArrayList<>();
        for (Chunk chunk : chunks) {
            ChunkReference existing = dedupStrategy.findDuplicate(chunk);
            if (existing != null) {
                references.add(existing);  // Reuse existing block
            } else {
                ChunkReference ref = blockStorage.store(chunk);
                dedupStrategy.registerChunk(chunk, ref);
                references.add(ref);
            }
        }

        // 3. Build metadata (Builder pattern)
        FileMetadata metadata = FileMetadata.builder()
            .path(path)
            .ownerId(userId)
            .sizeBytes(content.length)
            .checksum(computeChecksum(content))
            .chunkReferences(references)
            .build();

        // 4. Save metadata and create version (Memento)
        metadataService.save(metadata);
        versionService.createVersion(metadata);

        // 5. Notify other devices (Observer)
        syncService.notifyFileChanged(path, userId);

        return new UploadResult(metadata.getFileId(), references.size());
    }
}
```

### ASCII Diagram -- Three Strategy Axes

```
  ChunkingStrategy            DeduplicationStrategy         ConflictStrategy
 (how files are split)       (how duplicates detected)    (how sync conflicts resolve)
        |                            |                            |
  +-----+------+              +------+------+              +------+------+
  |            |              |             |              |             |
  FixedSize   ContentDefined  HashBased   NoDedup       LWW         KeepBoth
 (split at    (Rabin finger-  (SHA-256     (store        (latest     (save both,
  every N     print finds     hash map,    every         timestamp   rename
  bytes,      natural cut     O(1) lookup, chunk,        wins,       conflict
  simple)     points,         saves ~60%   for           risk of     copy,
              handles         storage)     encrypted     data loss)  no loss)
              insertions)                  files)
```

### Numbered Call Chain -- User Uploads a 10 MB File

```
1.  User drags "presentation.pptx" (10 MB) into the browser
2.  Client computes SHA-256 of the file, sends to server: "Do you have this?"
3.  Server checks FileRepository -- file hash not found, requests upload
4.  Client calls FileStorageService.uploadFile("presentation.pptx", bytes, "user-1")
5.  FileStorageService calls ChunkingStrategy.chunkFile(bytes, "presentation.pptx")
6.  ContentDefinedChunkingStrategy uses Rabin fingerprint with 4 MB average chunk size
7.  Returns 3 chunks: [chunk-A (3.8 MB), chunk-B (4.2 MB), chunk-C (2.0 MB)]
8.  For chunk-A: DeduplicationStrategy.findDuplicate(chunk-A)
9.  HashBasedDeduplicationStrategy computes SHA-256("chunk-A content") = "abc123..."
10. Looks up "abc123..." in dedup index -- NOT FOUND (new chunk)
11. BlockStorageService.store(chunk-A) -> writes to S3, returns ref "block-001"
12. DeduplicationStrategy.registerChunk(chunk-A, "block-001")
13. For chunk-B: findDuplicate(chunk-B) -> SHA-256 = "def456..." -> FOUND! (duplicate)
14. Reuses existing reference "block-077" -- ZERO bytes written to S3
15. For chunk-C: findDuplicate(chunk-C) -> NOT FOUND -> store -> "block-002"
16. FileMetadata.builder().path(...).chunkReferences([block-001, block-077, block-002]).build()
17. MetadataService saves metadata to PostgreSQL
18. VersionService.createVersion(metadata) -> version 1 saved (Memento)
19. SyncService.notifyFileChanged("presentation.pptx", "user-1")
20. SyncService pushes WebSocket event to user's laptop and phone
```

### Numbered Call Chain -- Sync Conflict Resolution

```
1.  User edits "report.docx" on laptop (offline) -> version 3 locally
2.  User edits "report.docx" on phone (offline) -> version 3 locally
3.  Laptop comes online, uploads version 3 -> server accepts, now at version 4
4.  Phone comes online, uploads version 3 -> server detects conflict (version 4 exists)
5.  FileStorageService calls ConflictStrategy.resolve(serverV4, phoneV3, "report.docx")
6.  KeepBothStrategy: keep server version as "report.docx"
7.  Save phone version as "report (conflict copy - 2026-04-26).docx"
8.  MetadataService saves conflict copy metadata
9.  VersionService creates version for conflict copy
10. SyncService notifies ALL devices: original file updated + conflict copy created
11. User sees both files, manually merges, deletes conflict copy
```

### Interview One-Liner

> "We inject three strategies -- ChunkingStrategy picks fixed-size vs.
> content-defined for splitting files, DeduplicationStrategy picks hash-based
> vs. no-dedup for eliminating redundant storage, and ConflictStrategy picks
> last-writer-wins vs. keep-both for sync conflicts. The Facade
> (FileStorageService) orchestrates all three plus block storage, metadata,
> versioning, and sync notifications."

**Cross-reference:**
- Facade orchestration: see Pattern 5
- Observer sync: see Pattern 6
- Flyweight dedup: see Pattern 7
- Builder metadata: see Pattern 2
- Memento versioning: see Pattern 9

---

## 2. Builder Pattern (Creational) -- FileMetadata.Builder

FileMetadata has many fields (path, owner, size, checksum, chunk references,
permissions, timestamps). The Builder ensures immutability and readable
construction.

### Ugly Anti-Pattern -- Telescoping Constructor

```java
// UGLY: 10-parameter constructor. Which String is which? Easy to swap arguments.

public class UglyFileMetadata {
    private String fileId;
    private String path;
    private String ownerId;
    private long sizeBytes;
    private String checksum;
    private String mimeType;
    private List<String> chunkRefs;
    private boolean shared;
    private long createdAt;
    private long modifiedAt;

    public UglyFileMetadata(String fileId, String path, String ownerId,
                            long sizeBytes, String checksum, String mimeType,
                            List<String> chunkRefs, boolean shared,
                            long createdAt, long modifiedAt) {
        // Did you swap ownerId and checksum? Compiler won't catch it.
        this.fileId = fileId;
        this.path = path;
        this.ownerId = ownerId;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.mimeType = mimeType;
        this.chunkRefs = chunkRefs;       // Mutable list exposed
        this.shared = shared;
        this.createdAt = createdAt;
        this.modifiedAt = modifiedAt;
    }

    // All fields mutable -- anyone can corrupt metadata after creation
    public void setPath(String path) { this.path = path; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
}
```

**Problems:**
1. 10-parameter constructor -- easy to swap String arguments silently
2. All fields mutable -- metadata can be corrupted after creation
3. Mutable list reference exposed -- external code can modify chunk refs
4. No validation -- can create metadata with null path or negative size
5. No fluent API -- construction is a wall of positional arguments

### Clean Solution -- Builder with Immutable Result

```java
// CLEAN: Builder pattern. Fluent, validated, immutable.

public class FileMetadata {
    private final String fileId;
    private final String path;
    private final String ownerId;
    private final long sizeBytes;
    private final String checksum;
    private final String mimeType;
    private final List<ChunkReference> chunkReferences;
    private final boolean shared;
    private final long createdAt;
    private final long modifiedAt;

    private FileMetadata(Builder builder) {
        this.fileId          = builder.fileId;
        this.path            = builder.path;
        this.ownerId         = builder.ownerId;
        this.sizeBytes       = builder.sizeBytes;
        this.checksum        = builder.checksum;
        this.mimeType        = builder.mimeType;
        this.chunkReferences = List.copyOf(builder.chunkReferences);
        this.shared          = builder.shared;
        this.createdAt       = builder.createdAt;
        this.modifiedAt      = builder.modifiedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String fileId = UUID.randomUUID().toString();
        private String path;
        private String ownerId;
        private long sizeBytes;
        private String checksum;
        private String mimeType = "application/octet-stream";
        private List<ChunkReference> chunkReferences = new ArrayList<>();
        private boolean shared = false;
        private long createdAt = System.currentTimeMillis();
        private long modifiedAt = System.currentTimeMillis();

        public Builder path(String path)       { this.path = path; return this; }
        public Builder ownerId(String ownerId)  { this.ownerId = ownerId; return this; }
        public Builder sizeBytes(long size)     { this.sizeBytes = size; return this; }
        public Builder checksum(String checksum) { this.checksum = checksum; return this; }
        public Builder mimeType(String mime)     { this.mimeType = mime; return this; }
        public Builder chunkReferences(List<ChunkReference> refs) {
            this.chunkReferences = refs; return this;
        }
        public Builder shared(boolean shared)   { this.shared = shared; return this; }

        public FileMetadata build() {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(ownerId, "ownerId is required");
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must be >= 0");
            Objects.requireNonNull(checksum, "checksum is required");
            return new FileMetadata(this);
        }
    }

    // Only getters -- no setters. Immutable after creation.
    public String getFileId()                    { return fileId; }
    public String getPath()                      { return path; }
    public String getOwnerId()                   { return ownerId; }
    public long getSizeBytes()                   { return sizeBytes; }
    public String getChecksum()                  { return checksum; }
    public List<ChunkReference> getChunkReferences() { return chunkReferences; }
}
```

### Numbered Call Chain -- Building FileMetadata After Upload

```
1.  FileStorageService finishes chunking and dedup, has 3 ChunkReferences
2.  Calls FileMetadata.builder() -- creates new Builder with UUID and timestamps
3.  Calls .path("/users/alice/docs/report.pdf") -- sets path
4.  Calls .ownerId("user-alice") -- sets owner
5.  Calls .sizeBytes(10_485_760) -- sets size (10 MB)
6.  Calls .checksum("sha256:abc123...") -- sets whole-file checksum
7.  Calls .mimeType("application/pdf") -- sets MIME type
8.  Calls .chunkReferences([ref-001, ref-077, ref-002]) -- sets chunk refs
9.  Calls .build() -- validates all required fields present and valid
10. Builder creates immutable FileMetadata with List.copyOf(chunkReferences)
11. MetadataService.save(metadata) -> INSERT INTO files (...) VALUES (...)
```

### Interview One-Liner

> "FileMetadata has 10+ fields so we use Builder for fluent construction with
> validation, and the result is fully immutable -- List.copyOf on chunk
> references prevents external mutation."

**Cross-reference:**
- Builder is called inside the Facade (Pattern 5) after chunking + dedup
- Chunk references come from DeduplicationStrategy (Pattern 1)

---

## 3. Factory Pattern (Creational) -- AppConfig

AppConfig is the central wiring point that creates all strategies, repositories,
and services. It decides which concrete strategies to use.

### Ugly Anti-Pattern -- Scattered `new` Calls

```java
// UGLY: Every class creates its own dependencies. Changing a strategy
// requires editing every class that uses it.

public class UglyMain {
    public static void main(String[] args) {
        // Dedup strategy hardcoded in 3 different places
        var uploadService = new UploadService(new SHA256Dedup());
        var downloadService = new DownloadService(new SHA256Dedup());
        var syncService = new SyncService(new SHA256Dedup());

        // Want to switch to NoDedup for encrypted files?
        // Edit 3 classes. Miss one? Inconsistent behavior.
    }
}
```

### Clean Solution -- Centralized Factory

```java
// CLEAN: AppConfig creates everything once. Switch strategy in ONE place.

public class AppConfig {

    public FileStorageService createFileStorageService() {
        // Strategies
        ChunkingStrategy chunking = new ContentDefinedChunkingStrategy(
            4 * 1024 * 1024  // 4 MB average chunk size
        );
        DeduplicationStrategy dedup = new HashBasedDeduplicationStrategy();
        ConflictStrategy conflict = new KeepBothStrategy();

        // Repositories
        FileRepository fileRepo = new InMemoryFileRepository();
        FolderRepository folderRepo = new InMemoryFolderRepository();
        VersionRepository versionRepo = new InMemoryVersionRepository();
        UserRepository userRepo = new InMemoryUserRepository();

        // Services
        BlockStorageService blockStorage = new InMemoryBlockStorageService();
        MetadataService metadataService = new MetadataService(fileRepo, folderRepo);
        VersionService versionService = new VersionService(versionRepo);
        SyncService syncService = new SyncService();

        return new FileStorageService(
            chunking, dedup, conflict,
            blockStorage, metadataService, versionService, syncService
        );
    }
}
```

### Numbered Call Chain -- Application Startup

```
1.  main() creates AppConfig
2.  AppConfig.createFileStorageService() called
3.  Creates ContentDefinedChunkingStrategy with 4 MB average chunk size
4.  Creates HashBasedDeduplicationStrategy with SHA-256
5.  Creates KeepBothStrategy for conflict resolution
6.  Creates 4 InMemory repositories (File, Folder, Version, User)
7.  Creates BlockStorageService, MetadataService, VersionService, SyncService
8.  Wires everything into FileStorageService constructor
9.  Returns fully assembled FileStorageService
10. All downstream code depends on interfaces, not concrete implementations
```

### Interview One-Liner

> "AppConfig is a Factory that wires all three strategy implementations,
> four repositories, and the service layer in one place -- swapping from
> hash-based dedup to no-dedup is a single line change."

**Cross-reference:**
- Creates strategies from Pattern 1
- Creates repositories from Pattern 4
- Assembles the Facade from Pattern 5

---

## 4. Repository Pattern (Structural) -- Four Repositories

Four repositories abstract persistence for the four core domain objects.

### Repository Summary

| Repository | Entity | Key Operations |
|-----------|--------|---------------|
| FileRepository | FileMetadata | save, findById, findByPath, findByFolder, delete |
| FolderRepository | Folder | save, findById, findByPath, findChildren, delete |
| VersionRepository | FileVersion | save, findByFileId, findByVersion, getLatest, deleteOlderThan |
| UserRepository | User | save, findById, findByEmail, updateQuota |

### Ugly Anti-Pattern -- SQL Scattered Everywhere

```java
// UGLY: SQL in business logic. Database schema change = edit every method.

public class UglyUploadService {

    private final Connection conn;

    public void uploadFile(String path, byte[] content, String userId) {
        try {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO files (id, path, owner_id, size_bytes, checksum, " +
                "mime_type, created_at, modified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            );
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, path);
            ps.setString(3, userId);
            ps.setLong(4, content.length);
            ps.setString(5, computeChecksum(content));
            ps.setString(6, guessMimeType(path));
            ps.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
            ps.setTimestamp(8, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
            // SQL is everywhere. Add a column? Edit 20 methods.
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
```

### Clean Solution -- Repository Interface

```java
// CLEAN: Repository hides SQL behind an interface.

public interface FileRepository {
    void save(FileMetadata file);
    Optional<FileMetadata> findById(String fileId);
    Optional<FileMetadata> findByPath(String path);
    List<FileMetadata> findByFolder(String folderId);
    void delete(String fileId);
    long getTotalSizeByOwner(String ownerId);
}

public class InMemoryFileRepository implements FileRepository {
    private final Map<String, FileMetadata> byId = new ConcurrentHashMap<>();
    private final Map<String, FileMetadata> byPath = new ConcurrentHashMap<>();

    @Override
    public void save(FileMetadata file) {
        byId.put(file.getFileId(), file);
        byPath.put(file.getPath(), file);
    }

    @Override
    public Optional<FileMetadata> findById(String fileId) {
        return Optional.ofNullable(byId.get(fileId));
    }

    @Override
    public Optional<FileMetadata> findByPath(String path) {
        return Optional.ofNullable(byPath.get(path));
    }

    @Override
    public List<FileMetadata> findByFolder(String folderId) {
        return byId.values().stream()
            .filter(f -> f.getFolderId().equals(folderId))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(String fileId) {
        FileMetadata removed = byId.remove(fileId);
        if (removed != null) byPath.remove(removed.getPath());
    }

    @Override
    public long getTotalSizeByOwner(String ownerId) {
        return byId.values().stream()
            .filter(f -> f.getOwnerId().equals(ownerId))
            .mapToLong(FileMetadata::getSizeBytes)
            .sum();
    }
}
```

### Numbered Call Chain -- FileRepository During Upload

```
1.  FileStorageService builds FileMetadata via Builder (Pattern 2)
2.  Calls MetadataService.save(metadata)
3.  MetadataService calls FileRepository.save(metadata)
4.  InMemoryFileRepository stores in byId map (key = fileId)
5.  Also stores in byPath map (key = "/users/alice/docs/report.pdf")
6.  Later: findByPath("/users/alice/docs/report.pdf") -> O(1) lookup
7.  Later: findByFolder("folder-123") -> stream filter on folderId
8.  In production: PostgresFileRepository executes INSERT INTO files (...)
```

### Interview One-Liner

> "Four repositories (File, Folder, Version, User) abstract persistence behind
> interfaces -- InMemory for tests, PostgreSQL for production. Business logic
> never sees SQL."

**Cross-reference:**
- Repositories created by Factory (Pattern 3)
- Used by the Facade (Pattern 5) and VersionService (Pattern 9)

---

## 5. Facade Pattern (Structural) -- FileStorageService

FileStorageService is the single entry point for all file operations. It
orchestrates the complex workflow: chunk -> dedup -> store -> metadata -> version -> sync.

### Ugly Anti-Pattern -- Client Orchestrates Everything

```java
// UGLY: The client (or controller) must know the exact order of operations.
// Miss a step? Data inconsistency. Change the flow? Edit every caller.

public class UglyFileController {

    public void handleUpload(String path, byte[] content, String userId) {
        // Client must know: chunk THEN dedup THEN store THEN metadata THEN version THEN sync
        var chunker = new FixedSizeChunker(4_000_000);
        List<byte[]> chunks = chunker.chunk(content);

        var dedup = new SHA256Dedup();
        List<String> blockIds = new ArrayList<>();
        for (byte[] chunk : chunks) {
            String hash = dedup.hash(chunk);
            if (!dedup.exists(hash)) {
                new S3Client().putObject("chunks/" + hash, chunk);
                dedup.register(hash);
            }
            blockIds.add(hash);
        }

        new PostgresClient().execute(
            "INSERT INTO files (path, blocks) VALUES (?, ?)",
            path, blockIds.toString()
        );

        new PostgresClient().execute(
            "INSERT INTO versions (file_path, version) VALUES (?, ?)",
            path, getNextVersion(path)
        );

        new WebSocketServer().broadcast(userId, "FILE_CHANGED:" + path);

        // Every controller endpoint repeats this 20-line flow
        // Forget the version insert? No rollback. Forget sync? Other devices stale.
    }
}
```

### Clean Solution -- Facade Orchestrates

```java
// CLEAN: FileStorageService is the Facade. One method call handles everything.

public class FileStorageService {

    private final ChunkingStrategy      chunkingStrategy;
    private final DeduplicationStrategy dedupStrategy;
    private final ConflictStrategy      conflictStrategy;
    private final BlockStorageService   blockStorage;
    private final MetadataService       metadataService;
    private final VersionService        versionService;
    private final SyncService           syncService;

    // Constructor injection (see Pattern 3: Factory)

    public UploadResult uploadFile(String path, byte[] content, String userId) {
        List<Chunk> chunks = chunkingStrategy.chunkFile(content, path);
        List<ChunkReference> refs = deduplicateAndStore(chunks);
        FileMetadata metadata = buildMetadata(path, userId, content, refs);
        metadataService.save(metadata);
        versionService.createVersion(metadata);
        syncService.notifyFileChanged(path, userId);
        return new UploadResult(metadata.getFileId(), refs.size());
    }

    public byte[] downloadFile(String fileId) {
        FileMetadata metadata = metadataService.getFile(fileId);
        List<byte[]> chunkData = new ArrayList<>();
        for (ChunkReference ref : metadata.getChunkReferences()) {
            chunkData.add(blockStorage.retrieve(ref));
        }
        return reassemble(chunkData);
    }

    public FileVersion rollbackFile(String fileId, int targetVersion) {
        FileVersion version = versionService.getVersion(fileId, targetVersion);
        metadataService.restoreFromVersion(version);
        syncService.notifyFileChanged(version.getPath(), "system");
        return version;
    }
}
```

### ASCII Diagram -- Facade Orchestration Flow

```
                         FileStorageService (FACADE)
                                  |
          +----------+------------+------------+-----------+-----------+
          |          |            |            |           |           |
          v          v            v            v           v           v
     Chunking    Dedup       BlockStorage  Metadata   Version     Sync
     Strategy    Strategy    Service       Service    Service     Service
          |          |            |            |           |           |
          v          v            v            v           v           v
     Split file  Check hash   Write to S3  Save to    Create      Push
     into chunks lookup       (or local)   PostgreSQL snapshot    WebSocket
                                                                 event

  Upload flow (numbered):
  +---------+    +--------+    +--------+    +----------+    +---------+    +--------+
  | 1.Chunk |    | 2.Dedup|    | 3.Store|    | 4.Save   |    | 5.Version|   | 6.Sync |
  |  file   |--->| check  |--->| blocks |--->| metadata |--->| snapshot |--->| notify |
  +---------+    +--------+    +--------+    +----------+    +---------+    +--------+
```

### Numbered Call Chain -- Full Upload Through Facade

```
1.  Controller receives HTTP POST /api/files/upload with file bytes
2.  Controller calls FileStorageService.uploadFile(path, bytes, userId)
3.  Facade calls ChunkingStrategy.chunkFile(bytes, path) -> 3 chunks
4.  Facade loops through chunks, calls DeduplicationStrategy.findDuplicate(chunk)
5.  For new chunks: Facade calls BlockStorageService.store(chunk)
6.  For new chunks: Facade calls DeduplicationStrategy.registerChunk(chunk, ref)
7.  For duplicate chunks: Facade reuses existing ChunkReference (Flyweight)
8.  Facade calls FileMetadata.builder()...build() (Builder)
9.  Facade calls MetadataService.save(metadata) -> FileRepository.save()
10. Facade calls VersionService.createVersion(metadata) -> VersionRepository.save()
11. Facade calls SyncService.notifyFileChanged(path, userId) -> WebSocket push
12. Facade returns UploadResult to controller
13. Controller returns HTTP 201 Created with fileId
```

### Interview One-Liner

> "FileStorageService is a Facade that orchestrates the six-step upload pipeline:
> chunk, dedup, store, metadata, version, sync. Callers make one method call;
> the Facade handles the full workflow and transaction boundaries."

**Cross-reference:**
- Strategies injected: Pattern 1
- Metadata built: Pattern 2
- Wired by: Pattern 3
- Repositories used: Pattern 4
- Sync notification: Pattern 6
- Dedup sharing: Pattern 7

---

## 6. Observer Pattern (Behavioral) -- SyncService

SyncService observes file changes and notifies all of the user's devices
so they stay in sync. This is how Dropbox/Drive push changes in real time.

### Ugly Anti-Pattern -- Polling Only

```java
// UGLY: No push notifications. Every device polls every 30 seconds.
// 1 million users x 3 devices x 2 polls/min = 6 million polls/min
// 99% of polls return "no changes" -- pure waste.

public class UglySyncService {

    public List<FileChange> checkForChanges(String userId, long lastSyncTimestamp) {
        // Every device calls this every 30 seconds
        // Server queries DB every time: SELECT * FROM changes WHERE ...
        // Returns empty list 99% of the time
        return database.query(
            "SELECT * FROM file_changes WHERE user_id = ? AND timestamp > ?",
            userId, lastSyncTimestamp
        );
    }

    // No WebSocket, no push, no event stream
    // Sync delay = 0 to 30 seconds (average 15 seconds)
    // Database hammered with useless queries
}
```

### Clean Solution -- Observer with WebSocket Push

```java
// CLEAN: SyncService is an Observer. Devices register for updates.
// Changes are pushed instantly via WebSocket.

public class SyncService {

    private final Map<String, Set<SyncListener>> listeners = new ConcurrentHashMap<>();

    public void registerDevice(String userId, SyncListener device) {
        listeners.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                 .add(device);
    }

    public void unregisterDevice(String userId, SyncListener device) {
        Set<SyncListener> devices = listeners.get(userId);
        if (devices != null) {
            devices.remove(device);
        }
    }

    public void notifyFileChanged(String path, String userId) {
        FileChangeEvent event = new FileChangeEvent(path, userId,
            System.currentTimeMillis(), ChangeType.MODIFIED);

        // Notify all of this user's devices (except the one that made the change)
        Set<SyncListener> devices = listeners.get(userId);
        if (devices != null) {
            for (SyncListener device : devices) {
                device.onFileChanged(event);
            }
        }

        // Also notify shared-with users
        List<String> sharedUsers = getSharedUsers(path);
        for (String sharedUserId : sharedUsers) {
            Set<SyncListener> sharedDevices = listeners.get(sharedUserId);
            if (sharedDevices != null) {
                for (SyncListener device : sharedDevices) {
                    device.onFileChanged(event);
                }
            }
        }
    }
}

public interface SyncListener {
    void onFileChanged(FileChangeEvent event);
    void onFolderChanged(FolderChangeEvent event);
    void onShareChanged(ShareChangeEvent event);
}
```

### ASCII Diagram -- Observer Push Flow

```
  User edits file on Laptop
         |
         v
  FileStorageService.uploadFile(...)
         |
         v
  SyncService.notifyFileChanged("report.pdf", "alice")
         |
         +------> Alice's Phone (SyncListener)  ---> download changed chunks
         |
         +------> Alice's Tablet (SyncListener) ---> download changed chunks
         |
         +------> Bob's Laptop (SyncListener)   ---> shared file, download update
         |              (Bob has shared access)
         |
         +------> Kafka topic "file-changes"     ---> async consumers (search index,
                                                       thumbnail generation, virus scan)
```

### Numbered Call Chain -- File Change Notification

```
1.  Alice saves "budget.xlsx" on her laptop
2.  FileStorageService completes upload (chunk, dedup, store, metadata, version)
3.  FileStorageService calls SyncService.notifyFileChanged("budget.xlsx", "alice")
4.  SyncService looks up listeners for "alice" -> [phone, tablet]
5.  SyncService calls phone.onFileChanged(event) -> WebSocket push to phone
6.  SyncService calls tablet.onFileChanged(event) -> WebSocket push to tablet
7.  SyncService looks up shared users for "budget.xlsx" -> ["bob"]
8.  SyncService looks up listeners for "bob" -> [laptop]
9.  SyncService calls bob-laptop.onFileChanged(event) -> WebSocket push
10. Alice's phone receives event, calls FileStorageService.downloadFile()
11. Phone downloads only the changed chunks (delta sync), reconstructs file locally
12. Sync complete -- all 3 devices have the latest "budget.xlsx"
```

### Interview One-Liner

> "SyncService is an Observer -- devices register as SyncListeners, and file
> changes are pushed instantly via WebSocket instead of polling. Shared file
> users also get notified. Kafka handles async consumers like search indexing."

**Cross-reference:**
- Triggered by the Facade (Pattern 5) after every upload/delete/rename
- Conflict resolution (Pattern 1, Strategy C) happens when two devices sync simultaneously
- Delta sync uses ChunkingStrategy (Pattern 1) to identify which chunks changed

---

## 7. Flyweight Pattern (Structural) -- Deduplication as Flyweight

**This is a new pattern in the series.** Deduplication IS the Flyweight
pattern: chunk data (intrinsic state) is shared across all files that contain
the same content. Only the per-file chunk ordering (extrinsic state) differs.

### GoF Flyweight Mapping

| Flyweight Concept | File Storage Implementation |
|-------------------|---------------------------|
| Flyweight object | Stored chunk (content-addressable block in S3) |
| Intrinsic state (shared) | Chunk bytes -- immutable, identified by SHA-256 hash |
| Extrinsic state (per-context) | Chunk order within each file, file metadata, permissions |
| Flyweight factory | DeduplicationStrategy -- checks if chunk exists before creating |
| Client | FileMetadata -- holds list of ChunkReferences (pointers to shared chunks) |
| Pool | Dedup index (hash -> blockId map) and block storage (S3) |

### Ugly Anti-Pattern -- Every File Stores Its Own Copy

```java
// UGLY: No dedup. 100 users share the same 500 MB installer?
// 100 x 500 MB = 50 GB of identical bytes stored.

public class UglyBlockStorage {

    private final Map<String, byte[]> blocks = new HashMap<>();
    private int blockCounter = 0;

    public String storeFileContent(String fileId, byte[] content) {
        // Every file gets its own copy -- no sharing
        String blockId = "block-" + (blockCounter++);
        blocks.put(blockId, content);
        return blockId;
    }

    // 100 copies of the same installer = 100 block entries
    // Storage cost: $0.023/GB/month * 50 GB = $1.15/month for ONE file
    // Multiply by thousands of common files = storage bill explodes
}
```

### Clean Solution -- Content-Addressable Flyweight

```java
// CLEAN: Chunks are Flyweight objects. Same content = same block.
// 100 users with the same installer? Stored ONCE.

public class ContentAddressableBlockStorage {

    private final Map<String, byte[]> blocks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> refCounts = new ConcurrentHashMap<>();

    public ChunkReference store(Chunk chunk) {
        String hash = chunk.getHash();  // SHA-256 of content

        if (blocks.containsKey(hash)) {
            // FLYWEIGHT: Reuse existing block, increment reference count
            refCounts.get(hash).incrementAndGet();
            return new ChunkReference(hash, blocks.get(hash).length);
        }

        // New content -- store it
        blocks.put(hash, chunk.getData());
        refCounts.put(hash, new AtomicInteger(1));
        return new ChunkReference(hash, chunk.getData().length);
    }

    public void release(String hash) {
        AtomicInteger count = refCounts.get(hash);
        if (count != null && count.decrementAndGet() <= 0) {
            // No more references -- safe to garbage collect
            blocks.remove(hash);
            refCounts.remove(hash);
        }
    }

    public byte[] retrieve(ChunkReference ref) {
        return blocks.get(ref.getBlockHash());
    }
}
```

### ASCII Diagram -- Flyweight Sharing

```
  Alice's "report.pdf"                    Bob's "report-copy.pdf"
  (10 MB, 3 chunks)                      (10 MB, same content)
       |                                       |
       v                                       v
  FileMetadata                            FileMetadata
  chunkRefs: [hash-A, hash-B, hash-C]    chunkRefs: [hash-A, hash-B, hash-C]
       |         |         |                   |         |         |
       +----+----+         |                   +----+----+         |
            |              |                        |              |
            v              v                        v              v
       +---------+    +---------+              (SAME BLOCKS!)
       | block   |    | block   |
       | hash-A  |    | hash-B  |    +---------+
       | 3.8 MB  |    | 4.2 MB  |    | block   |
       | refCnt=2|    | refCnt=2|    | hash-C  |
       +---------+    +---------+    | 2.0 MB  |
                                     | refCnt=2|
                                     +---------+

  WITHOUT dedup: 10 MB + 10 MB = 20 MB stored
  WITH dedup:    10 MB total (3 shared blocks, refCount=2 each)
  Savings:       50% (and grows with more copies)
```

### Numbered Call Chain -- Flyweight Reuse During Upload

```
1.  Alice uploads "installer.iso" (500 MB) -> 125 chunks of 4 MB each
2.  HashBasedDeduplicationStrategy hashes each chunk with SHA-256
3.  All 125 hashes are NEW -> all 125 chunks stored in S3
4.  Dedup index: 125 entries (hash -> blockId), refCount=1 each
5.  Bob uploads the SAME "installer.iso" (identical bytes)
6.  ChunkingStrategy splits into 125 chunks (same boundaries, same content)
7.  For chunk 1: findDuplicate(chunk) -> SHA-256 matches! refCount 1 -> 2
8.  For chunk 2: findDuplicate(chunk) -> SHA-256 matches! refCount 1 -> 2
9.  ... all 125 chunks match existing blocks
10. ZERO new bytes written to S3 -- Bob's upload completes instantly
11. Bob's FileMetadata stores [ref-001, ref-002, ..., ref-125] (pointers only)
12. Storage saved: 500 MB (Bob's file is pure pointers to shared Flyweight objects)
```

### Reference Counting and Garbage Collection

```
  Delete Alice's "installer.iso":
  1.  FileStorageService.deleteFile("alice-installer")
  2.  For each ChunkReference in Alice's metadata:
  3.     BlockStorage.release(hash) -> decrement refCount
  4.  refCount goes from 2 to 1 (Bob still references these blocks)
  5.  NO blocks deleted -- Bob's file still works

  Delete Bob's "installer.iso":
  6.  For each ChunkReference in Bob's metadata:
  7.     BlockStorage.release(hash) -> decrement refCount
  8.  refCount goes from 1 to 0
  9.  Block is garbage collected -- bytes removed from S3
  10. Storage reclaimed: 500 MB
```

### Interview One-Liner

> "Deduplication IS the Flyweight pattern -- chunk content is the intrinsic
> state shared via content-addressable storage (SHA-256 hash as key), and
> each file holds extrinsic state (chunk order, metadata). Reference counting
> ensures blocks are garbage collected only when no file references them."

**Cross-reference:**
- Flyweight objects created by DeduplicationStrategy (Pattern 1)
- Reference counting is CP in CAP analysis (see CAP_THEOREM.md)
- Bloom filter cache speeds up "probably not duplicate" checks (see CACHING_STRATEGY.md)

---

## 8. Composite Pattern (Structural) -- Folder Hierarchy

Folders and files form a tree. The Composite pattern lets us treat a folder
(which contains files AND subfolders) uniformly with a single interface.

### Ugly Anti-Pattern -- Flat Path Strings

```java
// UGLY: No tree structure. Everything is flat path strings.
// "List folder contents" = scan ALL files and string-match paths.
// "Delete folder" = scan ALL files looking for path prefix.

public class UglyFolderService {

    private final Map<String, byte[]> files = new HashMap<>();

    public List<String> listFolder(String folderPath) {
        // O(N) scan of ALL files to find children of this folder
        List<String> children = new ArrayList<>();
        for (String path : files.keySet()) {
            if (path.startsWith(folderPath + "/")) {
                // But wait -- this includes grandchildren too!
                // "/docs/sub/file.txt" matches "/docs/"
                // Need string manipulation to filter direct children only
                String relative = path.substring(folderPath.length() + 1);
                if (!relative.contains("/")) {
                    children.add(path);
                }
            }
        }
        return children;
    }

    public void deleteFolder(String folderPath) {
        // O(N) scan, delete all matching paths
        files.keySet().removeIf(path -> path.startsWith(folderPath + "/"));
        // What about empty subfolders? They don't exist as keys. Lost.
        // What about shared subfolders? Permissions not checked. Broken.
    }

    public long getFolderSize(String folderPath) {
        // O(N) scan AGAIN to sum sizes
        return files.entrySet().stream()
            .filter(e -> e.getKey().startsWith(folderPath + "/"))
            .mapToLong(e -> e.getValue().length)
            .sum();
    }
}
```

**Problems:**
1. O(N) scan for every folder operation (list, delete, size)
2. No distinction between files and folders -- both are path strings
3. Empty folders cannot exist (no entry in the map)
4. Recursive operations require string manipulation
5. No parent pointer -- "move folder" requires path rewriting for all children

### Clean Solution -- Composite Tree

```java
// CLEAN: FileSystemNode is the Component. Folder is the Composite.
// FileNode is the Leaf. Uniform interface for both.

public interface FileSystemNode {
    String getName();
    String getPath();
    long getSize();
    NodeType getType();  // FILE or FOLDER
    String getOwnerId();
    Instant getModifiedAt();
}

public class FileNode implements FileSystemNode {
    private final FileMetadata metadata;

    public FileNode(FileMetadata metadata) {
        this.metadata = metadata;
    }

    @Override public String getName()     { return metadata.getName(); }
    @Override public String getPath()     { return metadata.getPath(); }
    @Override public long getSize()       { return metadata.getSizeBytes(); }
    @Override public NodeType getType()   { return NodeType.FILE; }
    @Override public String getOwnerId()  { return metadata.getOwnerId(); }
    @Override public Instant getModifiedAt() { return metadata.getModifiedAt(); }
}

public class Folder implements FileSystemNode {
    private final String folderId;
    private final String name;
    private final String path;
    private final String ownerId;
    private final List<FileSystemNode> children = new ArrayList<>();

    // Composite: folder contains FileSystemNodes (files AND subfolders)

    public void addChild(FileSystemNode child) {
        children.add(child);
    }

    public void removeChild(FileSystemNode child) {
        children.remove(child);
    }

    public List<FileSystemNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public long getSize() {
        // Recursive: sum of all children sizes (files + subfolders)
        return children.stream()
            .mapToLong(FileSystemNode::getSize)
            .sum();
    }

    @Override public String getName()     { return name; }
    @Override public String getPath()     { return path; }
    @Override public NodeType getType()   { return NodeType.FOLDER; }
    @Override public String getOwnerId()  { return ownerId; }
    @Override public Instant getModifiedAt() {
        return children.stream()
            .map(FileSystemNode::getModifiedAt)
            .max(Instant::compareTo)
            .orElse(Instant.EPOCH);
    }
}
```

### ASCII Diagram -- Composite Tree Structure

```
  /users/alice/                      (Folder - Composite)
       |
       +-- /docs/                    (Folder - Composite)
       |     |
       |     +-- report.pdf          (FileNode - Leaf, 10 MB)
       |     +-- budget.xlsx         (FileNode - Leaf, 2 MB)
       |     +-- /archive/           (Folder - Composite)
       |           |
       |           +-- old-report.pdf (FileNode - Leaf, 8 MB)
       |
       +-- /photos/                  (Folder - Composite)
       |     |
       |     +-- vacation.jpg        (FileNode - Leaf, 5 MB)
       |
       +-- README.txt                (FileNode - Leaf, 1 KB)

  alice.getSize() = docs.getSize() + photos.getSize() + README.getSize()
                  = (10 + 2 + 8) + (5) + (0.001)
                  = 25.001 MB

  Each node (File or Folder) implements FileSystemNode.
  Folder.getSize() recursively sums children -- Composite in action.
```

### Numbered Call Chain -- List Folder Contents

```
1.  User opens "/users/alice/docs/" in the file browser UI
2.  Controller calls FolderService.listContents("/users/alice/docs/")
3.  FolderService calls FolderRepository.findByPath("/users/alice/docs/")
4.  Returns Folder object with folderId="folder-42"
5.  FolderService calls FileRepository.findByFolder("folder-42")
6.  Returns [report.pdf, budget.xlsx] as FileNode objects
7.  FolderService calls FolderRepository.findChildren("folder-42")
8.  Returns [/archive/] as Folder object
9.  Combines: children = [report.pdf, budget.xlsx, /archive/]
10. Sorts by name, returns to controller
11. Controller returns JSON array with type, name, size, modifiedAt for each node
12. UI renders file list with folder icon for /archive/, file icons for PDFs
```

### Interview One-Liner

> "The folder hierarchy uses Composite -- Folder contains FileSystemNodes
> which can be files (leaves) or subfolders (composites). Operations like
> getSize() and delete() recurse naturally through the tree."

**Cross-reference:**
- FolderRepository (Pattern 4) persists the tree structure
- Facade (Pattern 5) uses Composite for move, copy, delete operations
- Metadata cache (CACHING_STRATEGY.md) caches folder listings

---

## 9. Memento Pattern (Behavioral) -- FileVersion for Rollback

FileVersion captures a snapshot of a file's state (metadata + chunk references)
at a point in time. Users can browse version history and rollback to any
previous version -- just like Google Drive's "Manage versions."

### Ugly Anti-Pattern -- No Version History

```java
// UGLY: Overwrite in place. No history. "Undo" means re-uploading from memory.
// Accidentally deleted paragraph? Gone forever.

public class UglyFileService {

    private final Map<String, byte[]> files = new HashMap<>();

    public void updateFile(String path, byte[] newContent) {
        // Overwrite previous content -- no backup, no history
        files.put(path, newContent);
        // Previous version? What previous version?
        // User accidentally overwrites 3 hours of work with wrong file.
        // No rollback. No undo. Tough luck.
    }

    // No version list, no diff, no rollback
    // Regulatory compliance? Can't prove what the file contained last month.
}
```

### Clean Solution -- Memento with FileVersion

```java
// CLEAN: Every save creates a FileVersion (Memento).
// Version stores enough state to reconstruct the file at that point in time.

public class FileVersion {
    private final String versionId;
    private final String fileId;
    private final int versionNumber;
    private final String checksum;
    private final long sizeBytes;
    private final List<ChunkReference> chunkReferences;  // The memento state
    private final String modifiedBy;
    private final Instant createdAt;
    private final String changeDescription;

    // Private constructor -- only VersionService creates versions
    FileVersion(String fileId, int versionNumber, FileMetadata metadata,
                String modifiedBy, String description) {
        this.versionId       = UUID.randomUUID().toString();
        this.fileId          = fileId;
        this.versionNumber   = versionNumber;
        this.checksum        = metadata.getChecksum();
        this.sizeBytes       = metadata.getSizeBytes();
        this.chunkReferences = List.copyOf(metadata.getChunkReferences());
        this.modifiedBy      = modifiedBy;
        this.createdAt       = Instant.now();
        this.changeDescription = description;
    }

    // Getters only -- version is immutable
    public String getVersionId()                  { return versionId; }
    public int getVersionNumber()                 { return versionNumber; }
    public List<ChunkReference> getChunkReferences() { return chunkReferences; }
    // ...
}

public class VersionService {

    private final VersionRepository versionRepo;

    public FileVersion createVersion(FileMetadata metadata) {
        int nextVersion = versionRepo.getLatestVersionNumber(metadata.getFileId()) + 1;
        FileVersion version = new FileVersion(
            metadata.getFileId(), nextVersion, metadata,
            metadata.getOwnerId(), "Upload"
        );
        versionRepo.save(version);
        return version;
    }

    public FileVersion rollback(String fileId, int targetVersion) {
        FileVersion version = versionRepo.findByVersion(fileId, targetVersion)
            .orElseThrow(() -> new VersionNotFoundException(fileId, targetVersion));
        // Return the Memento -- caller restores file state from it
        return version;
    }

    public List<FileVersion> getVersionHistory(String fileId) {
        return versionRepo.findByFileId(fileId);
    }
}
```

### ASCII Diagram -- Version History as Mementos

```
  "report.pdf" version history:

  Version 1 (Memento)          Version 2 (Memento)          Version 3 (Memento)
  +-----------------------+    +-----------------------+    +-----------------------+
  | versionId: "v-001"    |    | versionId: "v-002"    |    | versionId: "v-003"    |
  | fileId: "file-42"     |    | fileId: "file-42"     |    | fileId: "file-42"     |
  | versionNumber: 1      |    | versionNumber: 2      |    | versionNumber: 3      |
  | checksum: "abc123"    |    | checksum: "def456"    |    | checksum: "ghi789"    |
  | sizeBytes: 10 MB      |    | sizeBytes: 10.5 MB    |    | sizeBytes: 11 MB      |
  | chunks: [A, B, C]     |    | chunks: [A, B', C]    |    | chunks: [A, B', C, D] |
  | modifiedBy: "alice"   |    | modifiedBy: "alice"   |    | modifiedBy: "bob"     |
  | createdAt: 9:00 AM    |    | createdAt: 10:30 AM   |    | createdAt: 2:00 PM    |
  | desc: "Initial upload"|    | desc: "Updated intro"  |    | desc: "Added appendix"|
  +-----------------------+    +-----------------------+    +-----------------------+

  Rollback to version 1:
  - Retrieve Memento v-001
  - Restore chunks [A, B, C] as current file state
  - Chunks A, B, C still exist in block storage (Flyweight refCount > 0)
  - Create version 4 pointing to same chunks as version 1
```

### Numbered Call Chain -- Rollback to Previous Version

```
1.  User clicks "Manage versions" for "report.pdf" in the UI
2.  Controller calls VersionService.getVersionHistory("file-42")
3.  VersionService queries VersionRepository.findByFileId("file-42")
4.  Returns [version-1, version-2, version-3] with timestamps and sizes
5.  UI displays version list. User clicks "Restore" on version 1.
6.  Controller calls FileStorageService.rollbackFile("file-42", 1)
7.  Facade calls VersionService.rollback("file-42", 1)
8.  VersionService loads FileVersion (Memento) for version 1
9.  Returns Memento with chunkReferences: [hash-A, hash-B, hash-C]
10. Facade calls MetadataService.restoreFromVersion(versionMemento)
11. MetadataService updates FileMetadata to point to version-1 chunks
12. Facade calls VersionService.createVersion(restoredMetadata) -> version 4
13. Facade calls SyncService.notifyFileChanged("report.pdf", "system")
14. All devices sync to the restored state
15. Chunks [A, B, C] were never deleted (Flyweight refCount kept them alive)
```

### Interview One-Liner

> "Every file save creates an immutable FileVersion (Memento) that captures
> the chunk references at that moment. Rollback simply restores the Memento's
> chunk list as the current state -- the actual blocks still exist in
> content-addressable storage thanks to Flyweight reference counting."

**Cross-reference:**
- Memento chunks are Flyweight objects (Pattern 7) -- blocks shared across versions
- VersionRepository (Pattern 4) persists the version chain
- Facade (Pattern 5) orchestrates rollback workflow
- Version cache in CACHING_STRATEGY.md for fast history loading

---

## Pattern Interaction Map

```
                                AppConfig (FACTORY)
                                     |
                    creates & wires all components
                                     |
                 +-------------------+-------------------+
                 |                   |                   |
                 v                   v                   v
          ChunkingStrategy    DeduplicationStrategy  ConflictStrategy
          (STRATEGY A)        (STRATEGY B)           (STRATEGY C)
                 |                   |                   |
                 +--------+  +------+------+   +--------+
                          |  |             |   |
                          v  v             v   v
                   FileStorageService (FACADE)
                          |
          +------+--------+--------+--------+--------+
          |      |        |        |        |        |
          v      v        v        v        v        v
       Chunk   Dedup   Block    Metadata  Version   Sync
       file    check   Storage  Service   Service   Service
          |      |     (S3)        |         |        |
          |      |        |        |         |        |
          |      v        |        v         v        v
          |   Flyweight   |   Repository  Memento  Observer
          |   (shared     |   (x4)        (File     (push to
          |    blocks)    |               Version)   devices)
          |               |
          v               v
     Composite        Content-
     (Folder          Addressable
      tree)           Storage

  Builder: FileMetadata.builder() called inside Facade after chunking
```

---

## Quick Reference: All Patterns at a Glance

| # | Pattern | GoF | One-Liner |
|---|---------|-----|-----------|
| 1 | Strategy x3 | Behavioral | Chunk, dedup, and conflict resolution all swappable |
| 2 | Builder | Creational | FileMetadata with 10+ fields, immutable, validated |
| 3 | Factory | Creational | AppConfig wires strategies + repos + services in one place |
| 4 | Repository x4 | Structural | File, Folder, Version, User -- DB hidden behind interfaces |
| 5 | Facade | Structural | FileStorageService orchestrates 6-step upload pipeline |
| 6 | Observer | Behavioral | SyncService pushes changes to all devices via WebSocket |
| 7 | Flyweight | Structural | Content-addressable dedup: same bytes = same block, shared |
| 8 | Composite | Structural | Folders contain files + subfolders, recursive getSize/delete |
| 9 | Memento | Behavioral | FileVersion captures chunk refs for rollback to any point |

---

## Interview Cheat Sheet

**"Walk me through a file upload."**
> "File goes through the Facade (FileStorageService): ChunkingStrategy splits
> it into content-defined chunks, DeduplicationStrategy checks each chunk's
> SHA-256 hash against the Flyweight pool, new chunks go to S3, metadata is
> built via Builder, persisted via Repository, a Memento (FileVersion) is
> created, and Observer (SyncService) pushes notifications to all devices."

**"How does deduplication work?"**
> "It's the Flyweight pattern: chunk content is the intrinsic state, stored
> once in content-addressable storage keyed by SHA-256 hash. Multiple files
> point to the same block. Reference counting ensures blocks are garbage
> collected only when no file references them."

**"How do you handle sync conflicts?"**
> "ConflictStrategy -- we inject either LastWriterWins (simple, risk of data
> loss) or KeepBoth (save conflict copy, no data loss). Dropbox uses KeepBoth
> for actual conflicts and LWW for non-overlapping edits."

**"How is the folder structure stored?"**
> "Composite pattern -- FileSystemNode interface with FileNode (leaf) and
> Folder (composite). Folder contains children (files + subfolders).
> Operations like getSize() recurse naturally through the tree."

**"How does version history work?"**
> "Memento pattern -- every save creates an immutable FileVersion that captures
> the chunk references. Rollback restores the Memento's chunk list as current
> state. Blocks are never deleted prematurely thanks to Flyweight reference
> counting."
