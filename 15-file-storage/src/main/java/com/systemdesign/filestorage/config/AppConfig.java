package com.systemdesign.filestorage.config;

import com.systemdesign.filestorage.controller.FileStorageController;
import com.systemdesign.filestorage.display.StorageStatsDisplay;
import com.systemdesign.filestorage.model.Folder;
import com.systemdesign.filestorage.model.StorageQuota;
import com.systemdesign.filestorage.model.User;
import com.systemdesign.filestorage.repository.FileRepository;
import com.systemdesign.filestorage.repository.FolderRepository;
import com.systemdesign.filestorage.repository.InMemoryFileRepository;
import com.systemdesign.filestorage.repository.InMemoryFolderRepository;
import com.systemdesign.filestorage.repository.InMemoryUserRepository;
import com.systemdesign.filestorage.repository.InMemoryVersionRepository;
import com.systemdesign.filestorage.repository.UserRepository;
import com.systemdesign.filestorage.repository.VersionRepository;
import com.systemdesign.filestorage.service.DeduplicationService;
import com.systemdesign.filestorage.service.DownloadService;
import com.systemdesign.filestorage.service.FileStorageService;
import com.systemdesign.filestorage.service.MetadataService;
import com.systemdesign.filestorage.service.SharingService;
import com.systemdesign.filestorage.service.SyncService;
import com.systemdesign.filestorage.service.TrashService;
import com.systemdesign.filestorage.service.UploadService;
import com.systemdesign.filestorage.service.VersionService;
import com.systemdesign.filestorage.store.BlockStore;
import com.systemdesign.filestorage.store.InMemoryBlockStore;
import com.systemdesign.filestorage.strategy.chunking.ChunkingStrategy;
import com.systemdesign.filestorage.strategy.chunking.ContentDefinedChunking;
import com.systemdesign.filestorage.strategy.chunking.FixedSizeChunking;
import com.systemdesign.filestorage.strategy.dedup.DeduplicationStrategy;
import com.systemdesign.filestorage.strategy.dedup.HashBasedDedup;
import com.systemdesign.filestorage.strategy.dedup.NoDedup;
import com.systemdesign.filestorage.strategy.sync.ConflictStrategy;
import com.systemdesign.filestorage.strategy.sync.KeepBothStrategy;
import com.systemdesign.filestorage.strategy.sync.LastWriterWinsStrategy;

/**
 * AppConfig — FACTORY. The ONLY place in the entire codebase where "new ConcreteClass()" appears.
 *
 * Dependency wiring graph:
 *
 *   BlockStore (InMemoryBlockStore)
 *       ↑
 *   DeduplicationStrategy (HashBasedDedup)
 *       ↑
 *   DeduplicationService ← (strategy + blockStore)
 *       ↑
 *   ChunkingStrategy (FixedSizeChunking)
 *       ↑
 *   ConflictStrategy (LastWriterWinsStrategy)
 *       ↑
 *   Repositories: FileRepo, FolderRepo, VersionRepo, UserRepo
 *       ↑
 *   MetadataService ← (FileRepo + FolderRepo)
 *   VersionService  ← (VersionRepo)
 *   SyncService     ← (ConflictStrategy)
 *   SharingService  ← (MetadataService)
 *       ↑
 *   UploadService   ← (ChunkingStrategy + DedupService + MetadataService + VersionService + UserRepo + SyncService)
 *   DownloadService ← (MetadataService + VersionService + DedupService)
 *   TrashService    ← (MetadataService + VersionService + DedupService + UserRepo + SyncService)
 *       ↑
 *   FileStorageService (FACADE) ← (all services above)
 *       ↑
 *   FileStorageController ← (FileStorageService)
 *       ↑
 *   StorageStatsDisplay ← (MetadataService + DedupService + VersionRepo + SharingService + UserRepo)
 *
 * Why centralize construction?
 * 1. Single place to swap implementations (e.g., FixedSizeChunking → ContentDefinedChunking).
 * 2. Makes the dependency graph explicit and auditable.
 * 3. In production, this would be replaced by a DI framework (Spring, Guice).
 */
public class AppConfig {

    // ── Shared state — same instances used across all services ────────

    private final BlockStore blockStore;
    private final DeduplicationStrategy dedupStrategy;
    private final ChunkingStrategy chunkingStrategy;
    private final ConflictStrategy conflictStrategy;

    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final VersionRepository versionRepository;
    private final UserRepository userRepository;

    private final MetadataService metadataService;
    private final DeduplicationService deduplicationService;
    private final VersionService versionService;
    private final SyncService syncService;
    private final SharingService sharingService;
    private final UploadService uploadService;
    private final DownloadService downloadService;
    private final TrashService trashService;
    private final FileStorageService fileStorageService;
    private final FileStorageController controller;
    private final StorageStatsDisplay statsDisplay;

    /**
     * Wire everything together. This constructor is the ENTIRE dependency graph.
     */
    public AppConfig() {
        // ── Layer 1: Infrastructure ──────────────────────────────────
        // Block store: content-addressable storage with reference counting
        this.blockStore = new InMemoryBlockStore();

        // Strategies: can be swapped by changing these lines
        this.dedupStrategy = new HashBasedDedup();          // vs new NoDedup()
        this.chunkingStrategy = new FixedSizeChunking();    // vs new ContentDefinedChunking()
        this.conflictStrategy = new LastWriterWinsStrategy(); // vs new KeepBothStrategy()

        // ── Layer 2: Repositories ────────────────────────────────────
        this.fileRepository = new InMemoryFileRepository();
        this.folderRepository = new InMemoryFolderRepository();
        this.versionRepository = new InMemoryVersionRepository();
        this.userRepository = new InMemoryUserRepository();

        // ── Layer 3: Core services ───────────────────────────────────
        this.deduplicationService = new DeduplicationService(dedupStrategy, blockStore);
        this.metadataService = new MetadataService(fileRepository, folderRepository);
        this.versionService = new VersionService(versionRepository);
        this.syncService = new SyncService(conflictStrategy);
        this.sharingService = new SharingService(metadataService);

        // ── Layer 4: Orchestration services ──────────────────────────
        this.uploadService = new UploadService(
                chunkingStrategy, deduplicationService, metadataService,
                versionService, userRepository, syncService);

        this.downloadService = new DownloadService(
                metadataService, versionService, deduplicationService);

        this.trashService = new TrashService(
                metadataService, versionService, deduplicationService,
                userRepository, syncService);

        // ── Layer 5: Facade ──────────────────────────────────────────
        this.fileStorageService = new FileStorageService(
                uploadService, downloadService, metadataService,
                versionService, sharingService, syncService, trashService);

        // ── Layer 6: Controller ──────────────────────────────────────
        this.controller = new FileStorageController(fileStorageService);

        // ── Layer 7: Display ─────────────────────────────────────────
        this.statsDisplay = new StorageStatsDisplay(
                metadataService, deduplicationService, versionRepository,
                sharingService, userRepository);

        // ── Seed data ────────────────────────────────────────────────
        seedUsers();
        seedRootFolders();
    }

    /**
     * Create demo users with storage quotas.
     * Quotas are intentionally small for demo purposes (50MB instead of 15GB).
     */
    private void seedUsers() {
        // 50MB quota for demo (small enough to demonstrate quota exceeded)
        long fiftyMB = 50L * 1024 * 1024;

        User alice = new User("alice", "Alice", "alice@example.com",
                new StorageQuota("alice", fiftyMB));
        User bob = new User("bob", "Bob", "bob@example.com",
                new StorageQuota("bob", fiftyMB));
        User charlie = new User("charlie", "Charlie", "charlie@example.com",
                new StorageQuota("charlie", fiftyMB));

        userRepository.save(alice);
        userRepository.save(bob);
        userRepository.save(charlie);
    }

    /**
     * Create root folders for each user (like "My Drive" in Google Drive).
     */
    private void seedRootFolders() {
        Folder aliceRoot = new Folder("root-alice", "My Drive", null, "alice", "/");
        Folder bobRoot = new Folder("root-bob", "My Drive", null, "bob", "/");
        Folder charlieRoot = new Folder("root-charlie", "My Drive", null, "charlie", "/");

        folderRepository.save(aliceRoot);
        folderRepository.save(bobRoot);
        folderRepository.save(charlieRoot);
    }

    // ── Accessors for demo code ──────────────────────────────────────

    public FileStorageController getController() { return controller; }
    public FileStorageService getFileStorageService() { return fileStorageService; }
    public StorageStatsDisplay getStatsDisplay() { return statsDisplay; }
    public MetadataService getMetadataService() { return metadataService; }
    public DeduplicationService getDeduplicationService() { return deduplicationService; }
    public VersionService getVersionService() { return versionService; }
    public SyncService getSyncService() { return syncService; }
    public SharingService getSharingService() { return sharingService; }
    public UploadService getUploadService() { return uploadService; }
    public DownloadService getDownloadService() { return downloadService; }
    public TrashService getTrashService() { return trashService; }
    public UserRepository getUserRepository() { return userRepository; }
    public FolderRepository getFolderRepository() { return folderRepository; }
    public VersionRepository getVersionRepository() { return versionRepository; }
    public BlockStore getBlockStore() { return blockStore; }
    public ChunkingStrategy getChunkingStrategy() { return chunkingStrategy; }

    // ── Factory methods for alternative configurations ────────────────

    /**
     * Create an AppConfig-like setup with content-defined chunking (Dropbox style).
     * Returns just the chunking strategy — caller uses it for comparison demos.
     */
    public static ChunkingStrategy createContentDefinedChunking() {
        return new ContentDefinedChunking();
    }

    /** Create a no-dedup strategy for comparison. */
    public static DeduplicationStrategy createNoDedup() {
        return new NoDedup();
    }

    /** Create a keep-both conflict strategy (Dropbox style). */
    public static ConflictStrategy createKeepBothStrategy() {
        return new KeepBothStrategy();
    }

    /** Create a fixed-size chunking strategy (Google Drive style). */
    public static ChunkingStrategy createFixedSizeChunking() {
        return new FixedSizeChunking();
    }

    /** Create a last-writer-wins conflict strategy (Google Drive style). */
    public static ConflictStrategy createLastWriterWinsStrategy() {
        return new LastWriterWinsStrategy();
    }
}
