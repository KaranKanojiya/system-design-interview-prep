package com.systemdesign.filestorage.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ShareLink — a shareable link granting access to a file.
 *
 * Design decisions:
 * - expiresAt nullable: null means the link never expires (like Dropbox permanent links).
 * - password nullable: null means no password required (open link).
 * - accessCount is AtomicInteger for thread safety — multiple users may click simultaneously.
 * - maxAccesses of 0 means unlimited (no cap on how many times the link can be used).
 *
 * Real-world parallels:
 * - Google Drive: share links with viewer/editor, optional expiry (Workspace only).
 * - Dropbox: share links with password protection, expiry, download-only mode.
 *
 * Call chain:
 *   SharingService.createShareLink → stores ShareLink
 *   SharingService.accessShareLink(linkId, password) → validates expiry, password, access count
 */
public class ShareLink {

    private final String linkId;
    private final String fileId;
    private final SharePermission permission;
    private final String createdBy;            // userId
    private final Instant expiresAt;           // null = never expires
    private final String password;             // null = no password required
    private final AtomicInteger accessCount;
    private final int maxAccesses;             // 0 = unlimited
    private final Instant createdAt;

    public ShareLink(String linkId, String fileId, SharePermission permission,
                     String createdBy, Instant expiresAt, String password,
                     int maxAccesses) {
        this.linkId = linkId;
        this.fileId = fileId;
        this.permission = permission;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.password = password;
        this.accessCount = new AtomicInteger(0);
        this.maxAccesses = maxAccesses;
        this.createdAt = Instant.now();
    }

    /** Check if the link has passed its expiration time. */
    public boolean isExpired() {
        if (expiresAt == null) return false;   // never expires
        return Instant.now().isAfter(expiresAt);
    }

    /** Whether this link requires a password to access. */
    public boolean isPasswordProtected() {
        return password != null && !password.isEmpty();
    }

    /** Atomically increment the access count. Returns the new count. */
    public int incrementAccess() {
        return accessCount.incrementAndGet();
    }

    /** Check if the link still has remaining accesses (or is unlimited). */
    public boolean hasRemainingAccesses() {
        if (maxAccesses == 0) return true;     // unlimited
        return accessCount.get() < maxAccesses;
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getLinkId() { return linkId; }
    public String getFileId() { return fileId; }
    public SharePermission getPermission() { return permission; }
    public String getCreatedBy() { return createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getPassword() { return password; }
    public int getAccessCount() { return accessCount.get(); }
    public int getMaxAccesses() { return maxAccesses; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return String.format("ShareLink{id='%s', fileId='%s', perm=%s, accesses=%d/%s, expired=%b, password=%b}",
                linkId, fileId, permission, accessCount.get(),
                maxAccesses == 0 ? "unlimited" : String.valueOf(maxAccesses),
                isExpired(), isPasswordProtected());
    }
}
