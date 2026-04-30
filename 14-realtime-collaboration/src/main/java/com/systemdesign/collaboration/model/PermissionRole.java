package com.systemdesign.collaboration.model;

/**
 * Roles that a user can have on a document.
 *
 * This mirrors Google Docs' sharing model:
 *   OWNER  — full control, can delete the doc and manage permissions
 *   EDITOR — can modify content and add comments
 *   VIEWER — read-only access
 *
 * Interview note: In a real system you'd also have COMMENTER and
 * possibly per-section permissions, but three roles are enough to
 * demonstrate the access-control layer.
 */
public enum PermissionRole {

    OWNER,
    EDITOR,
    VIEWER;

    /**
     * Returns true if this role allows editing the document content.
     * Only OWNER and EDITOR can modify; VIEWER is read-only.
     */
    public boolean canEdit() {
        return this == OWNER || this == EDITOR;
    }
}
