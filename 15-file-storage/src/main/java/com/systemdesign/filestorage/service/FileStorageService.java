package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.FileVersion;
import com.systemdesign.filestorage.model.ShareLink;
import com.systemdesign.filestorage.model.SharePermission;
import com.systemdesign.filestorage.model.SyncEvent;

import java.time.Instant;
import java.util.List;

/**
 * FileStorageService — FACADE pattern. Single entry point for all storage operations.
 *
 * This facade delegates to specialized services:
 *   uploadFile      → UploadService
 *   downloadFile    → DownloadService
 *   deleteFile      → TrashService
 *   syncChanges     → SyncService
 *   shareFile       → SharingService
 *   getVersions     → VersionService
 *   rollbackVersion → VersionService
 *
 * Why a facade?
 * - Controller only depends on ONE service, not seven.
 * - Internal service reorganization doesn't affect the controller.
 * - Clean API boundary for system design interviews: "here's the public API."
 *
 * Call chain:
 *   Controller.handleUpload → this.uploadFile → UploadService.uploadFile
 *   Controller.handleDownload → this.downloadFile → DownloadService.downloadFile
 */
public class FileStorageService {

    private final UploadService uploadService;
    private final DownloadService downloadService;
    private final MetadataService metadataService;
    private final VersionService versionService;
    private final SharingService sharingService;
    private final SyncService syncService;
    private final TrashService trashService;

    public FileStorageService(UploadService uploadService,
                              DownloadService downloadService,
                              MetadataService metadataService,
                              VersionService versionService,
                              SharingService sharingService,
                              SyncService syncService,
                              TrashService trashService) {
        this.uploadService = uploadService;
        this.downloadService = downloadService;
        this.metadataService = metadataService;
        this.versionService = versionService;
        this.sharingService = sharingService;
        this.syncService = syncService;
        this.trashService = trashService;
    }

    // ── Upload ───────────────────────────────────────────────────────

    /** Upload a new file. Returns the file metadata. */
    public FileMetadata uploadFile(String userId, String fileName, String path, byte[] data) {
        return uploadService.uploadFile(userId, fileName, path, data);
    }

    /** Upload a file into a specific folder. */
    public FileMetadata uploadFile(String userId, String fileName, String path, byte[] data,
                                   String parentFolderId) {
        return uploadService.uploadFile(userId, fileName, path, data, parentFolderId);
    }

    /** Upload a new version of an existing file. */
    public FileVersion uploadNewVersion(String fileId, String userId, byte[] data, String comment) {
        return uploadService.uploadNewVersion(fileId, userId, data, comment);
    }

    // ── Download ─────────────────────────────────────────────────────

    /** Download a file (latest version). */
    public byte[] downloadFile(String fileId, String userId) {
        return downloadService.downloadFile(fileId);
    }

    /** Download a specific version of a file. */
    public byte[] downloadVersion(String fileId, int versionNumber) {
        return downloadService.downloadVersion(fileId, versionNumber);
    }

    // ── Delete ───────────────────────────────────────────────────────

    /** Move file to trash (soft delete). */
    public void deleteFile(String fileId, String userId) {
        trashService.moveToTrash(fileId, userId);
    }

    // ── Sync ─────────────────────────────────────────────────────────

    /** Get sync changes since the given cursor. */
    public SyncService.SyncResult syncChanges(String userId, Instant lastSyncCursor) {
        return syncService.getChangesSince(userId, lastSyncCursor);
    }

    // ── Share ────────────────────────────────────────────────────────

    /** Create a share link for a file. */
    public ShareLink shareFile(String fileId, String userId, SharePermission permission) {
        return sharingService.createShareLink(fileId, userId, permission, null, null);
    }

    /** Create a share link with expiry and password. */
    public ShareLink shareFile(String fileId, String userId, SharePermission permission,
                               Instant expiresAt, String password) {
        return sharingService.createShareLink(fileId, userId, permission, expiresAt, password);
    }

    // ── Versions ─────────────────────────────────────────────────────

    /** Get version history for a file. */
    public List<FileVersion> getVersions(String fileId) {
        return versionService.getVersions(fileId);
    }

    /** Rollback a file to a previous version. */
    public FileVersion rollbackVersion(String fileId, int versionNumber) {
        return versionService.rollbackToVersion(fileId, versionNumber);
    }

    // ── Expose sub-services for advanced operations ──────────────────

    public UploadService getUploadService() { return uploadService; }
    public DownloadService getDownloadService() { return downloadService; }
    public MetadataService getMetadataService() { return metadataService; }
    public VersionService getVersionService() { return versionService; }
    public SharingService getSharingService() { return sharingService; }
    public SyncService getSyncService() { return syncService; }
    public TrashService getTrashService() { return trashService; }
}
