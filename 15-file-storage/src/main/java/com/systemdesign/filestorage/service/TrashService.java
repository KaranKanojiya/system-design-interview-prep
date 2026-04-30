package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.exception.FileNotFoundException;
import com.systemdesign.filestorage.exception.FileStorageException;
import com.systemdesign.filestorage.exception.PermissionDeniedException;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.FileVersion;
import com.systemdesign.filestorage.model.SyncEventType;
import com.systemdesign.filestorage.model.User;
import com.systemdesign.filestorage.repository.UserRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * TrashService — manages soft delete (trash) and permanent delete with reference counting.
 *
 * Two-phase deletion (like Google Drive):
 *   1. moveToTrash: soft delete — sets isDeleted=true. File is hidden but recoverable.
 *   2. permanentDelete: hard delete — removes metadata, removes chunk references.
 *      If a block's reference count reaches 0, the block is physically deleted.
 *
 * Why two-phase?
 * - Accidental deletions are recoverable without backup restoration.
 * - Google Drive auto-empties trash after 30 days; we don't auto-expire.
 *
 * Block store reference counting on permanent delete:
 *   For each chunk in every version of the file, we call blockStore.deleteBlock(hash).
 *   This DECREMENTS the reference count. If count reaches 0, the block is removed.
 *   If another file still references that block, it stays.
 *
 * Call chain:
 *   Controller.handleTrash → FileStorageService.deleteFile → this.moveToTrash()
 *   Controller.handlePermanentDelete → this.permanentDelete()
 *   Controller.handleEmptyTrash → this.emptyTrash(userId)
 */
public class TrashService {

    private final MetadataService metadataService;
    private final VersionService versionService;
    private final DeduplicationService deduplicationService;
    private final UserRepository userRepository;
    private final SyncService syncService;

    public TrashService(MetadataService metadataService,
                        VersionService versionService,
                        DeduplicationService deduplicationService,
                        UserRepository userRepository,
                        SyncService syncService) {
        this.metadataService = metadataService;
        this.versionService = versionService;
        this.deduplicationService = deduplicationService;
        this.userRepository = userRepository;
        this.syncService = syncService;
    }

    /**
     * Move a file to trash (soft delete).
     * The file is hidden from listings but can be restored.
     */
    public void moveToTrash(String fileId, String userId) {
        FileMetadata file = metadataService.getFile(fileId);

        // Check ownership
        if (!userId.equals(file.getOwnerId())) {
            throw new PermissionDeniedException("Only the file owner can delete: " + fileId);
        }

        if (file.isDeleted()) {
            throw new FileStorageException("File is already in trash: " + fileId);
        }

        file.setDeleted(true);

        // Record sync event
        syncService.recordChange(userId, fileId, file.getFileName(), SyncEventType.DELETED, "trash-service");
    }

    /**
     * Restore a file from trash.
     */
    public void restoreFromTrash(String fileId, String userId) {
        FileMetadata file = metadataService.getFile(fileId);

        if (!userId.equals(file.getOwnerId())) {
            throw new PermissionDeniedException("Only the file owner can restore: " + fileId);
        }

        if (!file.isDeleted()) {
            throw new FileStorageException("File is not in trash: " + fileId);
        }

        file.setDeleted(false);

        // Record sync event for restoration
        syncService.recordChange(userId, fileId, file.getFileName(), SyncEventType.CREATED, "trash-service");
    }

    /**
     * Permanently delete a file — removes metadata and decrements block references.
     *
     * For each version of the file, for each chunk hash, we call
     * deduplicationService.removeChunk(hash) which decrements the block store's
     * reference count. The block is only physically deleted when refCount reaches 0.
     */
    public void permanentDelete(String fileId) {
        FileMetadata file = metadataService.getFile(fileId);

        // Get all versions and their chunk hashes
        List<FileVersion> versions = versionService.getVersions(fileId);
        for (FileVersion version : versions) {
            for (String chunkHash : version.getChunkHashes()) {
                // Decrement reference count. If this was the last reference,
                // the block is physically deleted from the block store.
                deduplicationService.removeChunk(chunkHash);
            }
        }

        // Update user quota
        userRepository.findById(file.getOwnerId())
                .ifPresent(user -> user.getStorageQuota().removeUsage(file.getSizeBytes()));
    }

    /**
     * List all files in the trash for a user.
     */
    public List<FileMetadata> listTrash(String userId) {
        return metadataService.getFilesByOwner(userId).stream()
                .filter(FileMetadata::isDeleted)
                .collect(Collectors.toList());
    }

    /**
     * Empty the trash — permanently delete all trashed files for a user.
     */
    public int emptyTrash(String userId) {
        List<FileMetadata> trashed = listTrash(userId);
        for (FileMetadata file : trashed) {
            permanentDelete(file.getFileId());
        }
        return trashed.size();
    }
}
