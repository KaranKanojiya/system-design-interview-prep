package com.systemdesign.collaboration.controller;

import com.systemdesign.collaboration.exception.CollaborationException;
import com.systemdesign.collaboration.exception.PermissionDeniedException;
import com.systemdesign.collaboration.model.*;
import com.systemdesign.collaboration.service.*;

import java.util.List;

/**
 * Simulated REST + WebSocket controller.
 *
 * In a real system this would be a Spring @RestController + @MessageMapping.
 * Here we simulate HTTP endpoints and WebSocket handlers with plain methods.
 *
 * Each method simulates:
 *   1. Parsing the "request" (method parameters)
 *   2. Delegating to the appropriate service
 *   3. Formatting the "response" (return value or console output)
 *   4. Handling errors gracefully
 *
 * Endpoints simulated:
 *   POST   /documents               → handleCreateDocument
 *   PUT    /documents/{id}/edit      → handleEditDocument
 *   GET    /documents/{id}           → handleGetDocument
 *   GET    /documents/{id}/history   → handleGetHistory
 *   POST   /documents/{id}/share     → handleShareDocument
 *   GET    /documents/{id}/users     → handleGetActiveUsers
 */
public class CollaborationController {

    private final CollaborationService collaborationService;
    private final DocumentService documentService;
    private final PermissionService permissionService;
    private final PresenceService presenceService;
    private final VersionService versionService;
    private final BroadcastService broadcastService;

    public CollaborationController(CollaborationService collaborationService,
                                   DocumentService documentService,
                                   PermissionService permissionService,
                                   PresenceService presenceService,
                                   VersionService versionService,
                                   BroadcastService broadcastService) {
        this.collaborationService = collaborationService;
        this.documentService = documentService;
        this.permissionService = permissionService;
        this.presenceService = presenceService;
        this.versionService = versionService;
        this.broadcastService = broadcastService;
    }

    // ── POST /documents ──

    /**
     * Create a new document.
     * Simulates: POST /documents { title, ownerId }
     */
    public Document handleCreateDocument(String title, String ownerId) {
        try {
            Document doc = documentService.createDocument(title, ownerId);
            System.out.println("   [201 Created] " + doc);
            return doc;
        } catch (CollaborationException e) {
            System.out.println("   [400 Bad Request] " + e.getMessage());
            return null;
        }
    }

    // ── PUT /documents/{id}/edit ──

    /**
     * Apply an edit operation to a document.
     * Simulates: PUT /documents/{id}/edit { operation }
     *
     * This is the core endpoint — it triggers the full OT pipeline.
     */
    public Document handleEditDocument(String docId, String userId, Operation operation) {
        try {
            Document doc = collaborationService.processOperation(docId, userId, operation);
            return doc;
        } catch (PermissionDeniedException e) {
            System.out.println("   [403 Forbidden] " + e.getMessage());
            return null;
        } catch (CollaborationException e) {
            System.out.println("   [400 Bad Request] " + e.getMessage());
            return null;
        }
    }

    // ── GET /documents/{id} ──

    /**
     * Get a document's current state.
     */
    public Document handleGetDocument(String docId) {
        try {
            Document doc = documentService.getDocument(docId);
            return doc;
        } catch (CollaborationException e) {
            System.out.println("   [404 Not Found] " + e.getMessage());
            return null;
        }
    }

    // ── GET /documents/{id}/history ──

    /**
     * Get version history for a document.
     */
    public List<DocumentVersion> handleGetHistory(String docId) {
        return versionService.getHistory(docId);
    }

    // ── POST /documents/{id}/share ──

    /**
     * Share a document with another user.
     * Simulates: POST /documents/{id}/share { userId, role, grantedBy }
     */
    public void handleShareDocument(String docId, String userId,
                                    PermissionRole role, String grantedBy) {
        try {
            permissionService.grantPermission(docId, userId, role, grantedBy);
            System.out.println("   [200 OK] Shared doc " + docId + " with " +
                               userId + " as " + role);
        } catch (CollaborationException e) {
            System.out.println("   [400 Bad Request] " + e.getMessage());
        }
    }

    // ── GET /documents/{id}/users ──

    /**
     * Get active users in a document.
     */
    public List<UserPresence> handleGetActiveUsers(String docId) {
        return presenceService.getActiveUsers(docId);
    }

    // ── WebSocket handlers ──

    /**
     * Handle a user joining a document (WebSocket connect).
     */
    public UserPresence handleJoinDocument(String userId, String userName, String docId) {
        UserPresence presence = presenceService.joinDocument(userId, userName, docId);
        broadcastService.registerConnection(userId, docId);
        return presence;
    }

    /**
     * Handle a user leaving a document (WebSocket disconnect).
     */
    public void handleLeaveDocument(String userId, String docId) {
        presenceService.leaveDocument(userId, docId);
        broadcastService.removeConnection(userId, docId);
    }
}
