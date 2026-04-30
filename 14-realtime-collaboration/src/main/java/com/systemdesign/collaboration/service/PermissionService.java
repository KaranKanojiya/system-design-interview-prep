package com.systemdesign.collaboration.service;

import com.systemdesign.collaboration.model.Permission;
import com.systemdesign.collaboration.model.PermissionRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing document permissions (who can view/edit/own).
 *
 * Data structure: Map<docId, Map<userId, Permission>>
 *   Nested map for O(1) lookup.
 *
 * Call chain for permission check:
 *   CollaborationService.processOperation(docId, userId, op)
 *     → PermissionService.canEdit(docId, userId)
 *       → looks up Permission for (docId, userId)
 *       → calls permission.getRole().canEdit()
 *       → returns true/false
 *
 * If canEdit() returns false, a PermissionDeniedException is thrown.
 */
public class PermissionService {

    /** docId → (userId → Permission) */
    private final Map<String, Map<String, Permission>> permissions = new ConcurrentHashMap<>();

    /**
     * Grant a permission to a user on a document.
     */
    public void grantPermission(String docId, String userId,
                                PermissionRole role, String grantedBy) {
        Permission permission = new Permission(docId, userId, role, grantedBy);
        permissions.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
                   .put(userId, permission);
    }

    /**
     * Revoke a user's permission on a document.
     */
    public void revokePermission(String docId, String userId) {
        Map<String, Permission> docPerms = permissions.get(docId);
        if (docPerms != null) {
            docPerms.remove(userId);
        }
    }

    /**
     * Check what role a user has on a document.
     *
     * @return the PermissionRole, or null if the user has no access
     */
    public PermissionRole checkPermission(String docId, String userId) {
        Map<String, Permission> docPerms = permissions.get(docId);
        if (docPerms == null) return null;
        Permission perm = docPerms.get(userId);
        return perm != null ? perm.getRole() : null;
    }

    /**
     * Check if a user can edit a document (OWNER or EDITOR role).
     */
    public boolean canEdit(String docId, String userId) {
        PermissionRole role = checkPermission(docId, userId);
        return role != null && role.canEdit();
    }

    /**
     * Get all collaborators (users with any permission) on a document.
     */
    public List<Permission> getCollaborators(String docId) {
        Map<String, Permission> docPerms = permissions.get(docId);
        if (docPerms == null) return List.of();
        return new ArrayList<>(docPerms.values());
    }
}
