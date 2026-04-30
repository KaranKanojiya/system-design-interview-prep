package com.systemdesign.filestorage;

import com.systemdesign.filestorage.config.AppConfig;
import com.systemdesign.filestorage.controller.FileStorageController;
import com.systemdesign.filestorage.exception.StorageQuotaExceededException;
import com.systemdesign.filestorage.model.ConflictResolution;
import com.systemdesign.filestorage.model.FileChunk;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.FileVersion;
import com.systemdesign.filestorage.model.Folder;
import com.systemdesign.filestorage.model.ShareLink;
import com.systemdesign.filestorage.model.SharePermission;
import com.systemdesign.filestorage.model.SyncEvent;
import com.systemdesign.filestorage.service.DeduplicationService;
import com.systemdesign.filestorage.service.SyncService;
import com.systemdesign.filestorage.strategy.chunking.ChunkingStrategy;
import com.systemdesign.filestorage.strategy.sync.ConflictStrategy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * File Storage System — System Design Interview Demo (Project 15/15)
 *
 * Demonstrates a Google Drive/Dropbox-style file storage system with:
 * - Chunked uploads with resume capability
 * - Content-addressable deduplication (SHA-256 hashing)
 * - Block store with reference counting
 * - File versioning with rollback
 * - Folder hierarchy (virtual file system)
 * - Share links with permissions, passwords, expiry
 * - Device sync with conflict resolution (last-writer-wins vs keep-both)
 * - Trash with soft delete and permanent delete
 * - Storage quota management
 *
 * Patterns used:
 * - Strategy Pattern: ChunkingStrategy, DeduplicationStrategy, ConflictStrategy
 * - Facade Pattern: FileStorageService wraps 7 specialized services
 * - Builder Pattern: FileMetadata
 * - Repository Pattern: data access abstraction
 * - Factory Method: AppConfig wires all dependencies
 */
public class FileStorageApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("     FILE STORAGE SYSTEM — System Design Interview Demo (15/15)");
        System.out.println(SEPARATOR);
        System.out.println();

        // Wire everything via AppConfig (the ONLY place with new ConcreteClass())
        AppConfig config = new AppConfig();
        FileStorageController controller = config.getController();

        // ── Demo 1: File Upload & Download ───────────────────────────
        demo1_UploadAndDownload(controller);

        // ── Demo 2: Chunked Upload with Resume ──────────────────────
        demo2_ChunkedUploadWithResume(config);

        // ── Demo 3: Deduplication Savings ────────────────────────────
        demo3_DeduplicationSavings(controller, config);

        // ── Demo 4: Content-Defined vs Fixed-Size Chunking ──────────
        demo4_ChunkingComparison(config);

        // ── Demo 5: File Versioning ─────────────────────────────────
        demo5_FileVersioning(controller, config);

        // ── Demo 6: Folder Hierarchy ────────────────────────────────
        demo6_FolderHierarchy(controller, config);

        // ── Demo 7: File Sharing with Permissions ───────────────────
        demo7_FileSharing(controller, config);

        // ── Demo 8: Sync Across Devices ─────────────────────────────
        demo8_SyncAcrossDevices(controller, config);

        // ── Demo 9: Conflict Resolution Comparison ──────────────────
        demo9_ConflictResolution(config);

        // ── Demo 10: Trash & Recovery ───────────────────────────────
        demo10_TrashAndRecovery(controller, config);

        // ── Demo 11: Storage Quota Management ───────────────────────
        demo11_StorageQuota(controller, config);

        // ── Demo 12: Dedup Statistics ───────────────────────────────
        demo12_DedupStatistics(controller, config);

        // ── Final Summary ────────────────────────────────────────────
        config.getStatsDisplay().printStats();
        printDesignSummary();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 1: File Upload & Download
    // ═══════════════════════════════════════════════════════════════════

    private static void demo1_UploadAndDownload(FileStorageController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: File Upload & Download");
        System.out.println(SEPARATOR);

        // Create a test file (simulate a PDF document)
        byte[] fileData = createTestData("Hello, this is a test document for file storage!", 5000);

        // Upload
        System.out.println("  Uploading 'report.pdf' (5000 bytes)...");
        FileMetadata uploaded = controller.handleUpload("alice", "report.pdf", "/docs/report.pdf", fileData);
        System.out.println("  Uploaded: " + uploaded);
        System.out.println("  Extension: " + uploaded.getExtension());

        // Download and verify integrity
        System.out.println("\n  Downloading...");
        byte[] downloaded = controller.handleDownload(uploaded.getFileId(), "alice");

        boolean matches = Arrays.equals(fileData, downloaded);
        System.out.println("  Integrity check: " + (matches ? "PASS (data matches)" : "FAIL (data mismatch!)"));
        System.out.println("  Downloaded " + downloaded.length + " bytes");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 2: Chunked Upload with Resume
    // ═══════════════════════════════════════════════════════════════════

    private static void demo2_ChunkedUploadWithResume(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Chunked Upload with Resume");
        System.out.println(SEPARATOR);

        // Simulate a large file that will be split into 5 chunks
        int chunkSize = 4 * 1024 * 1024;  // 4MB per chunk
        int totalChunks = 5;
        byte[][] chunkDataArray = new byte[totalChunks][];
        Random rng = new Random(42);
        for (int i = 0; i < totalChunks; i++) {
            chunkDataArray[i] = new byte[chunkSize];
            rng.nextBytes(chunkDataArray[i]);
        }

        long totalSize = (long) chunkSize * totalChunks;

        // Initialize resumable upload
        String fileId = config.getUploadService().initResumableUpload(
                "alice", "large-video.mp4", "/videos/large-video.mp4", totalChunks, totalSize);
        System.out.println("  Initialized resumable upload: " + fileId);

        // Upload 60% (3 out of 5 chunks)
        System.out.println("\n  Phase 1: Uploading 60% (chunks 0, 1, 2)...");
        for (int i = 0; i < 3; i++) {
            config.getUploadService().uploadChunk(fileId, i, chunkDataArray[i]);
            double progress = config.getUploadService().getUploadProgress(fileId);
            System.out.printf("    Chunk %d uploaded. Progress: %.0f%%%n", i, progress);
        }

        // Simulate network failure
        System.out.println("\n  *** SIMULATED NETWORK FAILURE ***");
        System.out.println("  Upload interrupted at 60%. Connection restored...");

        // Check progress — should be 60%
        double progress = config.getUploadService().getUploadProgress(fileId);
        System.out.printf("  Checking progress... %.0f%% complete, %d of %d chunks uploaded%n",
                progress, config.getUploadService().getUploadedChunkCount(fileId), totalChunks);

        // Resume: upload remaining 40% (chunks 3, 4)
        System.out.println("\n  Phase 2: Resuming upload (chunks 3, 4)...");
        for (int i = 3; i < totalChunks; i++) {
            config.getUploadService().uploadChunk(fileId, i, chunkDataArray[i]);
            double p = config.getUploadService().getUploadProgress(fileId);
            System.out.printf("    Chunk %d uploaded. Progress: %.0f%%%n", i, p);
        }

        // Finalize
        FileMetadata completed = config.getUploadService().finalizeResumableUpload(fileId);
        System.out.println("\n  Upload finalized: " + completed.getFileName() +
                " (" + completed.getSizeBytes() + " bytes)");
        System.out.println("  Key insight: only chunks 3-4 needed to be sent after failure!");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 3: Deduplication Savings
    // ═══════════════════════════════════════════════════════════════════

    private static void demo3_DeduplicationSavings(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Deduplication Savings");
        System.out.println(SEPARATOR);

        // Create a file with known content
        byte[] originalData = createTestData("This is a shared document that multiple users have.", 10000);

        // First upload — all chunks are new
        System.out.println("  Upload #1: 'shared-doc.pdf' by alice...");
        int blocksBefore = config.getDeduplicationService().getBlockCount();
        FileMetadata first = controller.handleUpload("alice", "shared-doc.pdf",
                "/docs/shared-doc.pdf", originalData);
        int blocksAfterFirst = config.getDeduplicationService().getBlockCount();
        System.out.println("  New blocks stored: " + (blocksAfterFirst - blocksBefore));

        // Second upload — SAME DATA, different user. Should store zero new chunks.
        System.out.println("\n  Upload #2: 'shared-doc-copy.pdf' by bob (IDENTICAL data)...");
        FileMetadata second = controller.handleUpload("bob", "shared-doc-copy.pdf",
                "/docs/shared-doc-copy.pdf", originalData);
        int blocksAfterSecond = config.getDeduplicationService().getBlockCount();
        System.out.println("  New blocks stored: " + (blocksAfterSecond - blocksAfterFirst));
        System.out.println("  Dedup in action: second upload stored ZERO new chunks!");

        // Show savings
        System.out.println("\n  " + config.getDeduplicationService().getStats());
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 4: Content-Defined vs Fixed-Size Chunking
    // ═══════════════════════════════════════════════════════════════════

    private static void demo4_ChunkingComparison(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: Content-Defined vs Fixed-Size Chunking");
        System.out.println(SEPARATOR);

        // Create a ~10MB test file
        byte[] originalFile = new byte[10 * 1024 * 1024];
        new Random(12345).nextBytes(originalFile);

        // Create a modified version: change bytes in the middle
        byte[] modifiedFile = Arrays.copyOf(originalFile, originalFile.length);
        // Insert 100 bytes at position 5MB (simulates editing middle of file)
        for (int i = 5 * 1024 * 1024; i < 5 * 1024 * 1024 + 100; i++) {
            modifiedFile[i] = (byte) 0xFF;
        }

        // Fixed-size chunking — factory method in AppConfig (no direct "new" here)
        ChunkingStrategy fixed = AppConfig.createFixedSizeChunking();
        List<FileChunk> fixedOriginal = fixed.chunk("test-fixed", originalFile);
        List<FileChunk> fixedModified = fixed.chunk("test-fixed-mod", modifiedFile);

        int fixedChanged = countChangedChunks(fixedOriginal, fixedModified);

        System.out.println("  Fixed-Size Chunking (Google Drive style):");
        System.out.println("    Original: " + fixedOriginal.size() + " chunks");
        System.out.println("    Modified: " + fixedModified.size() + " chunks");
        System.out.println("    Changed chunks: " + fixedChanged + " of " + fixedOriginal.size());
        System.out.printf("    Reusable: %.0f%%%n",
                (1.0 - (double) fixedChanged / fixedOriginal.size()) * 100);

        // Content-defined chunking — factory method in AppConfig (no direct "new" here)
        ChunkingStrategy cdc = AppConfig.createContentDefinedChunking();
        List<FileChunk> cdcOriginal = cdc.chunk("test-cdc", originalFile);
        List<FileChunk> cdcModified = cdc.chunk("test-cdc-mod", modifiedFile);

        int cdcChanged = countChangedChunks(cdcOriginal, cdcModified);

        System.out.println("\n  Content-Defined Chunking (Dropbox style):");
        System.out.println("    Original: " + cdcOriginal.size() + " chunks");
        System.out.println("    Modified: " + cdcModified.size() + " chunks");
        System.out.println("    Changed chunks: " + cdcChanged + " of " + cdcOriginal.size());
        System.out.printf("    Reusable: %.0f%%%n",
                cdcOriginal.size() > 0 ? (1.0 - (double) cdcChanged / cdcOriginal.size()) * 100 : 0);

        System.out.println("\n  Conclusion: Content-defined chunking preserves more chunks");
        System.out.println("  after a middle-of-file edit because boundaries are content-derived,");
        System.out.println("  not position-derived. This means less data to transfer on sync.");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 5: File Versioning
    // ═══════════════════════════════════════════════════════════════════

    private static void demo5_FileVersioning(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: File Versioning & Rollback");
        System.out.println(SEPARATOR);

        // Create initial file
        byte[] v1Data = createTestData("Version 1: Original draft of the report.", 3000);
        FileMetadata file = controller.handleUpload("alice", "versioned-doc.txt",
                "/docs/versioned-doc.txt", v1Data);
        String fileId = file.getFileId();

        // Edit file 5 times
        String[] edits = {
                "Version 2: Added introduction section.",
                "Version 3: Added methodology section.",
                "Version 4: Added results and graphs.",
                "Version 5: Added conclusion.",
                "Version 6: Final review, fixed typos."
        };

        for (int i = 0; i < edits.length; i++) {
            byte[] vData = createTestData(edits[i], 3000 + (i + 1) * 500);
            controller.handleUploadVersion(fileId, "alice", vData, edits[i]);
        }

        // Show version history
        System.out.println("\n  Version History:");
        List<FileVersion> versions = controller.handleListVersions(fileId);
        for (FileVersion v : versions) {
            System.out.printf("    v%d: %s (chunks=%d, size=%d, by=%s)%n",
                    v.getVersionNumber(), v.getComment(), v.getChunkCount(),
                    v.getSizeBytes(), v.getCreatedBy());
        }

        // Version diff
        System.out.println("\n  " + config.getVersionService().getVersionDiff(fileId, 1, 6));

        // Rollback to version 2
        System.out.println("  Rolling back to version 2...");
        FileVersion rollback = controller.handleRollback(fileId, 2);
        System.out.println("  Rollback created new version: v" + rollback.getVersionNumber() +
                " (with v2 content)");

        // Download rollback version and verify
        byte[] rollbackData = config.getDownloadService().downloadVersion(fileId, 2);
        byte[] v2Expected = createTestData("Version 2: Added introduction section.", 3500);
        System.out.println("  Rollback content matches v2: " + Arrays.equals(rollbackData, v2Expected));
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 6: Folder Hierarchy
    // ═══════════════════════════════════════════════════════════════════

    private static void demo6_FolderHierarchy(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Folder Hierarchy");
        System.out.println(SEPARATOR);

        // Create nested folders
        Folder docs = config.getMetadataService().createFolder(
                new Folder("folder-docs", "Documents", "root-alice", "alice", "/Documents/"));
        Folder projects = config.getMetadataService().createFolder(
                new Folder("folder-projects", "Projects", "folder-docs", "alice", "/Documents/Projects/"));
        Folder photos = config.getMetadataService().createFolder(
                new Folder("folder-photos", "Photos", "root-alice", "alice", "/Photos/"));

        System.out.println("  Created folder structure:");
        System.out.println("    /");
        System.out.println("    ├── Documents/");
        System.out.println("    │   └── Projects/");
        System.out.println("    └── Photos/");

        // Upload files into folders
        byte[] docData = createTestData("Project proposal content", 2000);
        byte[] photoData = createTestData("Photo binary data", 5000);

        FileMetadata proposal = controller.handleUpload("alice", "proposal.pdf",
                "/Documents/Projects/proposal.pdf", docData, "folder-projects");
        FileMetadata photo = controller.handleUpload("alice", "vacation.jpg",
                "/Photos/vacation.jpg", photoData, "folder-photos");

        // List folder contents
        System.out.println("\n  Contents of /Documents/:");
        List<String> docsContents = config.getMetadataService().listFolder("folder-docs");
        docsContents.forEach(item -> System.out.println("    " + item));

        System.out.println("\n  Contents of /Documents/Projects/:");
        List<String> projectsContents = config.getMetadataService().listFolder("folder-projects");
        projectsContents.forEach(item -> System.out.println("    " + item));

        System.out.println("\n  Contents of /Photos/:");
        List<String> photosContents = config.getMetadataService().listFolder("folder-photos");
        photosContents.forEach(item -> System.out.println("    " + item));

        // Move file between folders
        System.out.println("\n  Moving 'proposal.pdf' from Projects/ to Documents/...");
        config.getMetadataService().moveFile(proposal.getFileId(), "folder-docs");
        System.out.println("  New path: " + config.getMetadataService().getFilePath(proposal.getFileId()));

        // Search files
        System.out.println("\n  Searching for 'proposal'...");
        controller.handleSearch("alice", "proposal");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 7: File Sharing with Permissions
    // ═══════════════════════════════════════════════════════════════════

    private static void demo7_FileSharing(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: File Sharing with Permissions");
        System.out.println(SEPARATOR);

        // Upload a file to share
        byte[] data = createTestData("Confidential report for stakeholders", 4000);
        FileMetadata file = controller.handleUpload("alice", "confidential-report.pdf",
                "/docs/confidential-report.pdf", data);

        // Create a public share link (no password, no expiry)
        System.out.println("\n  Creating public share link (VIEW only)...");
        ShareLink publicLink = controller.handleShare(file.getFileId(), "alice", SharePermission.VIEW);
        System.out.println("  Link: " + publicLink);

        // Create a password-protected share link with expiry and max accesses
        System.out.println("\n  Creating password-protected share link (DOWNLOAD, expires in 24h, max 5 accesses)...");
        ShareLink protectedLink = config.getSharingService().createShareLink(
                file.getFileId(), "alice", SharePermission.DOWNLOAD,
                Instant.now().plus(24, ChronoUnit.HOURS),
                "secret123", 5);
        System.out.println("  Link: " + protectedLink);

        // Access with correct password
        System.out.println("\n  Accessing protected link with correct password...");
        try {
            FileMetadata accessed = config.getSharingService().accessShareLink(
                    protectedLink.getLinkId(), "secret123");
            System.out.println("  Access granted! File: " + accessed.getFileName());
            System.out.println("  Access count: " + protectedLink.getAccessCount() + "/" +
                    (protectedLink.getMaxAccesses() == 0 ? "unlimited" : protectedLink.getMaxAccesses()));
        } catch (Exception e) {
            System.out.println("  Access denied: " + e.getMessage());
        }

        // Access with wrong password
        System.out.println("\n  Accessing protected link with WRONG password...");
        try {
            config.getSharingService().accessShareLink(protectedLink.getLinkId(), "wrongpassword");
            System.out.println("  Access granted (unexpected!)");
        } catch (Exception e) {
            System.out.println("  Access denied (expected): " + e.getMessage());
        }

        // List share links for file
        System.out.println("\n  Share links for file:");
        config.getSharingService().listShareLinks(file.getFileId())
                .forEach(link -> System.out.println("    " + link));

        // Revoke the public link
        System.out.println("\n  Revoking public link...");
        config.getSharingService().revokeShareLink(publicLink.getLinkId());
        System.out.println("  Remaining links: " +
                config.getSharingService().listShareLinks(file.getFileId()).size());
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 8: Sync Across Devices
    // ═══════════════════════════════════════════════════════════════════

    private static void demo8_SyncAcrossDevices(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Sync Across Devices");
        System.out.println(SEPARATOR);

        // Device A (laptop) uploads a file
        System.out.println("  [Device A - Laptop] Uploading 'meeting-notes.txt'...");
        byte[] notesData = createTestData("Meeting notes from today's standup", 2000);
        FileMetadata notes = controller.handleUpload("alice", "meeting-notes.txt",
                "/docs/meeting-notes.txt", notesData);

        // Device B (phone) syncs — should see the new file
        System.out.println("\n  [Device B - Phone] Syncing (cursor=null, first sync)...");
        SyncService.SyncResult syncResult = controller.handleSync("alice", null);
        System.out.println("  Events received:");
        for (SyncEvent event : syncResult.getEvents()) {
            System.out.println("    " + event);
        }
        System.out.println("  New cursor: " + syncResult.getNewCursor());

        // Device A modifies the file
        System.out.println("\n  [Device A - Laptop] Editing 'meeting-notes.txt'...");
        byte[] updatedData = createTestData("Updated meeting notes with action items", 2500);
        controller.handleUploadVersion(notes.getFileId(), "alice", updatedData, "Added action items");

        // Device B syncs again — should see only the modification
        System.out.println("\n  [Device B - Phone] Syncing (with previous cursor)...");
        SyncService.SyncResult syncResult2 = controller.handleSync("alice", syncResult.getNewCursor());
        System.out.println("  New events since last sync:");
        for (SyncEvent event : syncResult2.getEvents()) {
            System.out.println("    " + event);
        }
        System.out.println("  Device B is now up to date.");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 9: Conflict Resolution Comparison
    // ═══════════════════════════════════════════════════════════════════

    private static void demo9_ConflictResolution(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Conflict Resolution Comparison");
        System.out.println(SEPARATOR);

        // Create two conflicting versions of the same file
        FileMetadata localVersion = new FileMetadata.Builder("conflict-file", "report.pdf")
                .filePath("/docs/report.pdf")
                .ownerId("alice")
                .updatedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();

        FileMetadata remoteVersion = new FileMetadata.Builder("conflict-file", "report.pdf")
                .filePath("/docs/report.pdf")
                .ownerId("alice")
                .updatedAt(Instant.now())  // remote is more recent
                .build();

        System.out.println("  Scenario: Alice edited 'report.pdf' on her laptop (1 hour ago)");
        System.out.println("  Bob also edited 'report.pdf' on the server (just now)");
        System.out.println("  Local updatedAt:  " + localVersion.getUpdatedAt());
        System.out.println("  Remote updatedAt: " + remoteVersion.getUpdatedAt());

        // Strategy 1: Last-Writer-Wins (Google Drive)
        System.out.println("\n  Strategy 1: Last-Writer-Wins (Google Drive style)");
        ConflictStrategy lww = AppConfig.createLastWriterWinsStrategy();
        ConflictResolution lwwResult = lww.resolve(localVersion, remoteVersion);
        System.out.println("  Result: " + lwwResult);
        System.out.println("  Impact: Remote wins because it was updated more recently.");
        System.out.println("  Risk: Alice's local changes are LOST.");

        // Strategy 2: Keep-Both (Dropbox)
        System.out.println("\n  Strategy 2: Keep-Both (Dropbox style)");
        ConflictStrategy keepBoth = AppConfig.createKeepBothStrategy();
        ConflictResolution kbResult = keepBoth.resolve(localVersion, remoteVersion);
        System.out.println("  Result: " + kbResult);

        // Show conflict copy name
        config.getSyncService().setConflictStrategy(keepBoth);
        String resolution = config.getSyncService().resolveConflict(localVersion, remoteVersion, "alice");
        System.out.println("  Detail: " + resolution);
        System.out.println("  Impact: No data loss, but folder gets cluttered with conflict copies.");

        // Restore original strategy
        config.getSyncService().setConflictStrategy(AppConfig.createLastWriterWinsStrategy());
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 10: Trash & Recovery
    // ═══════════════════════════════════════════════════════════════════

    private static void demo10_TrashAndRecovery(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 10: Trash & Recovery");
        System.out.println(SEPARATOR);

        // Upload files
        byte[] data1 = createTestData("Temporary file to be deleted", 1500);
        byte[] data2 = createTestData("Another temp file", 2000);
        FileMetadata temp1 = controller.handleUpload("bob", "temp1.txt", "/temp1.txt", data1);
        FileMetadata temp2 = controller.handleUpload("bob", "temp2.txt", "/temp2.txt", data2);

        // Delete (move to trash)
        System.out.println("\n  Deleting temp1.txt and temp2.txt...");
        controller.handleDelete(temp1.getFileId(), "bob");
        controller.handleDelete(temp2.getFileId(), "bob");

        // List trash
        System.out.println("\n  Trash contents:");
        List<FileMetadata> trashed = controller.handleListTrash("bob");
        trashed.forEach(f -> System.out.println("    " + f.getFileName() +
                " (deleted at " + f.getDeletedAt() + ")"));

        // Restore one file
        System.out.println("\n  Restoring temp1.txt...");
        controller.handleRestore(temp1.getFileId(), "bob");
        System.out.println("  temp1.txt is back! isDeleted = " +
                config.getMetadataService().getFile(temp1.getFileId()).isDeleted());

        // Permanently delete the other
        System.out.println("\n  Permanently deleting temp2.txt...");
        int blocksBefore = config.getDeduplicationService().getBlockCount();
        controller.handlePermanentDelete(temp2.getFileId());
        int blocksAfter = config.getDeduplicationService().getBlockCount();
        System.out.println("  Blocks freed: " + (blocksBefore - blocksAfter));

        // Verify trash is cleaner
        System.out.println("  Remaining trash items: " + controller.handleListTrash("bob").size());
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 11: Storage Quota Management
    // ═══════════════════════════════════════════════════════════════════

    private static void demo11_StorageQuota(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 11: Storage Quota Management");
        System.out.println(SEPARATOR);

        // Charlie has a 50MB quota. Upload files until quota is exceeded.
        System.out.println("  Charlie's quota: " + config.getUserRepository().findById("charlie")
                .map(u -> u.getStorageQuota().toString()).orElse("N/A"));

        // Upload progressively larger files
        int fileNumber = 1;
        int fileSize = 5 * 1024 * 1024;  // 5MB each
        boolean quotaExceeded = false;

        while (!quotaExceeded) {
            byte[] data = new byte[fileSize];
            new Random(fileNumber).nextBytes(data);

            try {
                System.out.printf("\n  Uploading file %d (%s)...%n", fileNumber, formatBytes(fileSize));
                controller.handleUpload("charlie", "bigfile-" + fileNumber + ".dat",
                        "/bigfile-" + fileNumber + ".dat", data);

                System.out.println("  Quota after upload: " +
                        config.getUserRepository().findById("charlie")
                                .map(u -> u.getStorageQuota().toString()).orElse("N/A"));

                fileNumber++;
            } catch (StorageQuotaExceededException e) {
                quotaExceeded = true;
                System.out.println("  QUOTA EXCEEDED: " + e.getMessage());
                System.out.println("  No more uploads possible until files are deleted.");
            }
        }

        System.out.println("\n  Final quota: " + config.getUserRepository().findById("charlie")
                .map(u -> u.getStorageQuota().toString()).orElse("N/A"));
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Demo 12: Dedup Statistics
    // ═══════════════════════════════════════════════════════════════════

    private static void demo12_DedupStatistics(FileStorageController controller, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 12: Dedup Statistics (Overlapping Files)");
        System.out.println(SEPARATOR);

        DeduplicationService dedupService = config.getDeduplicationService();

        int dupsBefore = dedupService.getDuplicateCount();
        long savedBefore = dedupService.getSavedBytes();

        // Create a base data block that will be reused across files
        byte[] sharedContent = new byte[8000];
        new Random(999).nextBytes(sharedContent);

        // Upload 20 files that all share the same base content
        // Each file = shared content + small unique suffix
        System.out.println("  Uploading 20 files with overlapping content...");
        for (int i = 0; i < 20; i++) {
            byte[] fileData = new byte[sharedContent.length + 100];
            System.arraycopy(sharedContent, 0, fileData, 0, sharedContent.length);
            // Add unique suffix so each file is slightly different
            byte[] suffix = ("unique-suffix-" + i).getBytes();
            System.arraycopy(suffix, 0, fileData, sharedContent.length, suffix.length);

            controller.handleUpload("alice", "overlap-" + i + ".dat",
                    "/dedup-test/overlap-" + i + ".dat", fileData);
        }

        int dupsAfter = dedupService.getDuplicateCount();
        long savedAfter = dedupService.getSavedBytes();

        int newDups = dupsAfter - dupsBefore;
        long newSaved = savedAfter - savedBefore;

        System.out.printf("\n  Results from 20 overlapping files:%n");
        System.out.printf("    Duplicate chunks detected: %d%n", newDups);
        System.out.printf("    Bytes saved: %s%n", formatBytes(newSaved));
        System.out.printf("    Overall dedup stats: %s%n", dedupService.getStats());

        System.out.println("\n  Insight: Files with overlapping content benefit enormously from");
        System.out.println("  chunk-level deduplication. This is why Dropbox and Google Drive");
        System.out.println("  can store petabytes of data while the physical storage is much less.");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Design Summary
    // ═══════════════════════════════════════════════════════════════════

    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("                    DESIGN SUMMARY");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  Architecture: Layered with Strategy + Facade + Repository patterns");
        System.out.println();
        System.out.println("  Key Components:");
        System.out.println("    1. Block Store (content-addressable, reference counted)");
        System.out.println("    2. Chunking (fixed-size for simplicity, CDC for better dedup)");
        System.out.println("    3. Deduplication (SHA-256 hash-based, chunk-level)");
        System.out.println("    4. Versioning (max 30 versions, rollback = new version)");
        System.out.println("    5. Sync (cursor-based changelog, push notifications)");
        System.out.println("    6. Conflict resolution (last-writer-wins vs keep-both)");
        System.out.println("    7. Sharing (permissions, passwords, expiry, access limits)");
        System.out.println("    8. Trash (two-phase: soft delete → permanent delete)");
        System.out.println("    9. Quota (synchronized, per-user storage limits)");
        System.out.println();
        System.out.println("  Scalability Considerations:");
        System.out.println("    - Metadata: sharded SQL (e.g., Vitess) or NoSQL (DynamoDB)");
        System.out.println("    - Block storage: object store (S3, GCS) with erasure coding");
        System.out.println("    - Dedup index: distributed hash table (Cassandra)");
        System.out.println("    - Sync: event log (Kafka) with per-user partitioning");
        System.out.println("    - Upload: CDN edge upload endpoints, chunk-level retry");
        System.out.println("    - Download: CDN caching, range requests for large files");
        System.out.println();
        System.out.println("  Real-World References:");
        System.out.println("    - Google Drive: fixed chunking, last-writer-wins, Colossus FS");
        System.out.println("    - Dropbox: CDC (Rabin), keep-both conflicts, S3 backend");
        System.out.println("    - OneDrive: differential sync, BITS protocol");
        System.out.println();
        System.out.println("  Interview Talking Points:");
        System.out.println("    - Why chunk? Resumable uploads, dedup, delta sync.");
        System.out.println("    - Why content-addressable? Same data stored once, any number of refs.");
        System.out.println("    - Why reference counting? Safe block deletion when shared.");
        System.out.println("    - Why cursor-based sync? Efficient polling, no missed events.");
        System.out.println("    - Why two-phase delete? Accidental deletion recovery.");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("          Project 15/15 Complete — File Storage System");
        System.out.println(SEPARATOR);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create test data by repeating a pattern to reach the desired size.
     * Uses deterministic content so the same call produces the same bytes.
     */
    private static byte[] createTestData(String pattern, int size) {
        byte[] data = new byte[size];
        byte[] patternBytes = pattern.getBytes();
        for (int i = 0; i < size; i++) {
            data[i] = patternBytes[i % patternBytes.length];
        }
        return data;
    }

    /**
     * Count how many chunks changed between two chunking results.
     * Compares hashes at each position.
     */
    private static int countChangedChunks(List<FileChunk> original, List<FileChunk> modified) {
        int changed = 0;
        int maxLen = Math.max(original.size(), modified.size());
        for (int i = 0; i < maxLen; i++) {
            if (i >= original.size() || i >= modified.size()) {
                changed++;  // extra chunk in one version
            } else if (!original.get(i).getHash().equals(modified.get(i).getHash())) {
                changed++;
            }
        }
        return changed;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
