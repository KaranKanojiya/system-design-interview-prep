package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.exception.FileStorageException;
import com.systemdesign.filestorage.exception.PermissionDeniedException;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.ShareLink;
import com.systemdesign.filestorage.model.SharePermission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SharingService — manages share links with permissions, passwords, and expiry.
 *
 * Share link lifecycle:
 *   1. Owner creates a share link for a file with permission + optional password/expiry.
 *   2. Anyone with the linkId can attempt to access.
 *   3. On access: validate password, check expiry, check access count limit.
 *   4. If valid: return the file metadata (caller then uses DownloadService to get content).
 *   5. Owner can revoke the link at any time.
 *
 * Real-world parallels:
 * - Google Drive: share with "Anyone with the link" + viewer/editor permission.
 * - Dropbox: share with password protection + expiry date.
 * - WeTransfer: share with download limit (maxAccesses).
 *
 * Call chain:
 *   Controller.handleShare → FileStorageService.shareFile → this.createShareLink(...)
 *   External user → Controller.handleAccessShareLink → this.accessShareLink(linkId, pwd)
 */
public class SharingService {

    /** In-memory store of all share links: linkId → ShareLink */
    private final Map<String, ShareLink> shareLinks = new ConcurrentHashMap<>();

    private final MetadataService metadataService;

    public SharingService(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /**
     * Create a share link for a file.
     *
     * @param fileId      the file to share
     * @param userId      the owner creating the link
     * @param permission  access level (VIEW, DOWNLOAD, EDIT)
     * @param expiresAt   when the link expires (null = never)
     * @param password    password to access (null = no password)
     * @return the created ShareLink
     */
    public ShareLink createShareLink(String fileId, String userId, SharePermission permission,
                                     Instant expiresAt, String password) {
        return createShareLink(fileId, userId, permission, expiresAt, password, 0);
    }

    /**
     * Create a share link with a maximum access count.
     */
    public ShareLink createShareLink(String fileId, String userId, SharePermission permission,
                                     Instant expiresAt, String password, int maxAccesses) {
        // Verify file exists
        metadataService.getFile(fileId);

        ShareLink link = new ShareLink(
                UUID.randomUUID().toString(),
                fileId,
                permission,
                userId,
                expiresAt,
                password,
                maxAccesses
        );

        shareLinks.put(link.getLinkId(), link);
        return link;
    }

    /**
     * Access a share link — validate and return the file metadata.
     *
     * Validation order:
     *   1. Link exists?
     *   2. Link expired?
     *   3. Access count within limit?
     *   4. Password correct (if required)?
     *   5. All good → increment access count, return file metadata.
     */
    public FileMetadata accessShareLink(String linkId, String password) {
        ShareLink link = shareLinks.get(linkId);
        if (link == null) {
            throw new FileStorageException("Share link not found: " + linkId);
        }

        // Check expiry
        if (link.isExpired()) {
            throw new PermissionDeniedException("Share link has expired");
        }

        // Check access count limit
        if (!link.hasRemainingAccesses()) {
            throw new PermissionDeniedException("Share link has reached maximum access count");
        }

        // Check password
        if (link.isPasswordProtected()) {
            if (password == null || !password.equals(link.getPassword())) {
                throw new PermissionDeniedException("Incorrect password for share link");
            }
        }

        // All validations passed — increment access count
        link.incrementAccess();

        // Return the shared file's metadata
        return metadataService.getFile(link.getFileId());
    }

    /**
     * Revoke a share link — it can no longer be used.
     */
    public void revokeShareLink(String linkId) {
        ShareLink removed = shareLinks.remove(linkId);
        if (removed == null) {
            throw new FileStorageException("Share link not found: " + linkId);
        }
    }

    /**
     * List all share links for a specific file.
     */
    public List<ShareLink> listShareLinks(String fileId) {
        return shareLinks.values().stream()
                .filter(link -> fileId.equals(link.getFileId()))
                .collect(Collectors.toList());
    }

    /** Get all share links (for stats/display). */
    public List<ShareLink> getAllShareLinks() {
        return new ArrayList<>(shareLinks.values());
    }

    /** Get a specific share link by ID. */
    public ShareLink getShareLink(String linkId) {
        ShareLink link = shareLinks.get(linkId);
        if (link == null) {
            throw new FileStorageException("Share link not found: " + linkId);
        }
        return link;
    }
}
