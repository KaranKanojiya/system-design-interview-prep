package com.systemdesign.collaboration.model;

import java.time.LocalDateTime;

/**
 * Maps a user to a role on a specific document.
 *
 * Permissions are checked on every operation:
 *   CollaborationService.processOperation()
 *     → PermissionService.canEdit(docId, userId)
 *       → looks up this Permission object
 *       → calls role.canEdit()
 *
 * Interview note: In production you'd store this in a DB and cache it.
 * Here we keep it in an in-memory map keyed by (docId, userId).
 */
public class Permission {

    private final String docId;
    private final String userId;
    private PermissionRole role;
    private final String grantedBy;
    private final LocalDateTime grantedAt;

    public Permission(String docId, String userId, PermissionRole role, String grantedBy) {
        this.docId = docId;
        this.userId = userId;
        this.role = role;
        this.grantedBy = grantedBy;
        this.grantedAt = LocalDateTime.now();
    }

    // ── Getters ──

    public String getDocId()             { return docId; }
    public String getUserId()            { return userId; }
    public PermissionRole getRole()      { return role; }
    public String getGrantedBy()         { return grantedBy; }
    public LocalDateTime getGrantedAt()  { return grantedAt; }

    public void setRole(PermissionRole role) { this.role = role; }

    @Override
    public String toString() {
        return "Permission{doc='" + docId + "', user='" + userId +
               "', role=" + role + ", grantedBy='" + grantedBy + "'}";
    }
}
