package com.systemdesign.filestorage.controller;

import com.systemdesign.filestorage.exception.FileStorageException;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.FileVersion;
import com.systemdesign.filestorage.model.ShareLink;
import com.systemdesign.filestorage.model.SharePermission;
import com.systemdesign.filestorage.model.SyncEvent;
import com.systemdesign.filestorage.service.FileStorageService;
import com.systemdesign.filestorage.service.SyncService;

import java.time.Instant;
import java.util.List;

/**
 * FileStorageController — simulated REST controller.
 *
 * In a real system this would be a Spring MVC / JAX-RS controller with HTTP endpoints.
 * Here we simulate the REST layer with method calls that mirror HTTP operations.
 *
 * Mapping to real HTTP endpoints:
 *   handleUpload         → POST   /api/files
 *   handleDownload       → GET    /api/files/{fileId}/content
 *   handleDelete         → DELETE /api/files/{fileId}
 *   handleSync           → GET    /api/sync?cursor={cursor}
 *   handleShare          → POST   /api/files/{fileId}/share
 *   handleListVersions   → GET    /api/files/{fileId}/versions
 *   handleRollback       → POST   /api/files/{fileId}/versions/{versionNumber}/rollback
 *   handleSearch         → GET    /api/files?q={query}
 *   handleTrash          → POST   /api/files/{fileId}/trash
 *
 * Call chain:
 *   FileStorageApp (demo) → this.handleXxx() → FileStorageService.xxx() → sub-services
 */
public class FileStorageController {

    private final FileStorageService storageService;

    public FileStorageController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    // ── Upload ───────────────────────────────────────────────────────

    /** POST /api/files — Upload a new file. */
    public FileMetadata handleUpload(String userId, String fileName, String path, byte[] data) {
        try {
            FileMetadata result = storageService.uploadFile(userId, fileName, path, data);
            System.out.printf("   [201 CREATED] File uploaded: %s (%d bytes)%n",
                    result.getFileName(), result.getSizeBytes());
            return result;
        } catch (FileStorageException e) {
            System.out.printf("   [400 BAD REQUEST] Upload failed: %s%n", e.getMessage());
            throw e;
        }
    }

    /** POST /api/files — Upload into a folder. */
    public FileMetadata handleUpload(String userId, String fileName, String path, byte[] data,
                                     String parentFolderId) {
        try {
            FileMetadata result = storageService.uploadFile(userId, fileName, path, data, parentFolderId);
            System.out.printf("   [201 CREATED] File uploaded to folder: %s (%d bytes)%n",
                    result.getFileName(), result.getSizeBytes());
            return result;
        } catch (FileStorageException e) {
            System.out.printf("   [400 BAD REQUEST] Upload failed: %s%n", e.getMessage());
            throw e;
        }
    }

    /** PUT /api/files/{fileId} — Upload a new version. */
    public FileVersion handleUploadVersion(String fileId, String userId, byte[] data, String comment) {
        try {
            FileVersion version = storageService.uploadNewVersion(fileId, userId, data, comment);
            System.out.printf("   [200 OK] New version created: v%d%n", version.getVersionNumber());
            return version;
        } catch (FileStorageException e) {
            System.out.printf("   [400 BAD REQUEST] Version upload failed: %s%n", e.getMessage());
            throw e;
        }
    }

    // ── Download ─────────────────────────────────────────────────────

    /** GET /api/files/{fileId}/content — Download a file. */
    public byte[] handleDownload(String fileId, String userId) {
        try {
            byte[] data = storageService.downloadFile(fileId, userId);
            System.out.printf("   [200 OK] File downloaded: %d bytes%n", data.length);
            return data;
        } catch (FileStorageException e) {
            System.out.printf("   [404 NOT FOUND] Download failed: %s%n", e.getMessage());
            throw e;
        }
    }

    // ── Delete ───────────────────────────────────────────────────────

    /** DELETE /api/files/{fileId} — Move file to trash. */
    public void handleDelete(String fileId, String userId) {
        try {
            storageService.deleteFile(fileId, userId);
            System.out.printf("   [200 OK] File moved to trash: %s%n", fileId);
        } catch (FileStorageException e) {
            System.out.printf("   [400 BAD REQUEST] Delete failed: %s%n", e.getMessage());
            throw e;
        }
    }

    // ── Sync ─────────────────────────────────────────────────────────

    /** GET /api/sync?cursor={cursor} — Get changes since cursor. */
    public SyncService.SyncResult handleSync(String userId, Instant lastCursor) {
        SyncService.SyncResult result = storageService.syncChanges(userId, lastCursor);
        System.out.printf("   [200 OK] Sync: %d new events%n", result.getEvents().size());
        return result;
    }

    // ── Share ────────────────────────────────────────────────────────

    /** POST /api/files/{fileId}/share — Create share link. */
    public ShareLink handleShare(String fileId, String userId, SharePermission permission) {
        ShareLink link = storageService.shareFile(fileId, userId, permission);
        System.out.printf("   [201 CREATED] Share link created: %s (permission=%s)%n",
                link.getLinkId(), permission);
        return link;
    }

    /** POST /api/files/{fileId}/share — Create share link with password and expiry. */
    public ShareLink handleShare(String fileId, String userId, SharePermission permission,
                                 Instant expiresAt, String password) {
        ShareLink link = storageService.shareFile(fileId, userId, permission, expiresAt, password);
        System.out.printf("   [201 CREATED] Share link created: %s (permission=%s, password=%b, expiry=%s)%n",
                link.getLinkId(), permission, password != null, expiresAt);
        return link;
    }

    // ── Versions ─────────────────────────────────────────────────────

    /** GET /api/files/{fileId}/versions — List version history. */
    public List<FileVersion> handleListVersions(String fileId) {
        List<FileVersion> versions = storageService.getVersions(fileId);
        System.out.printf("   [200 OK] %d versions found%n", versions.size());
        return versions;
    }

    /** POST /api/files/{fileId}/versions/{versionNumber}/rollback — Rollback to version. */
    public FileVersion handleRollback(String fileId, int versionNumber) {
        try {
            FileVersion version = storageService.rollbackVersion(fileId, versionNumber);
            System.out.printf("   [200 OK] Rolled back to version %d (created as v%d)%n",
                    versionNumber, version.getVersionNumber());
            return version;
        } catch (FileStorageException e) {
            System.out.printf("   [400 BAD REQUEST] Rollback failed: %s%n", e.getMessage());
            throw e;
        }
    }

    // ── Search ───────────────────────────────────────────────────────

    /** GET /api/files?q={query} — Search files by name. */
    public List<FileMetadata> handleSearch(String userId, String query) {
        List<FileMetadata> results = storageService.getMetadataService().searchFiles(userId, query);
        System.out.printf("   [200 OK] Search '%s': %d results%n", query, results.size());
        return results;
    }

    // ── Trash ────────────────────────────────────────────────────────

    /** GET /api/trash — List trashed files. */
    public List<FileMetadata> handleListTrash(String userId) {
        List<FileMetadata> trashed = storageService.getTrashService().listTrash(userId);
        System.out.printf("   [200 OK] Trash: %d files%n", trashed.size());
        return trashed;
    }

    /** POST /api/trash/{fileId}/restore — Restore from trash. */
    public void handleRestore(String fileId, String userId) {
        try {
            storageService.getTrashService().restoreFromTrash(fileId, userId);
            System.out.printf("   [200 OK] File restored from trash: %s%n", fileId);
        } catch (FileStorageException e) {
            System.out.printf("   [400 BAD REQUEST] Restore failed: %s%n", e.getMessage());
            throw e;
        }
    }

    /** DELETE /api/trash/{fileId} — Permanent delete. */
    public void handlePermanentDelete(String fileId) {
        storageService.getTrashService().permanentDelete(fileId);
        System.out.printf("   [200 OK] File permanently deleted: %s%n", fileId);
    }

    // ── Expose service for advanced demos ────────────────────────────

    public FileStorageService getStorageService() {
        return storageService;
    }
}
