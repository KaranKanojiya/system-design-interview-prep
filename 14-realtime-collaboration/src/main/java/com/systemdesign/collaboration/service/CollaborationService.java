package com.systemdesign.collaboration.service;

import com.systemdesign.collaboration.exception.DocumentNotFoundException;
import com.systemdesign.collaboration.exception.PermissionDeniedException;
import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.Operation;
import com.systemdesign.collaboration.model.UserPresence;
import com.systemdesign.collaboration.strategy.persistence.PersistenceStrategy;
import com.systemdesign.collaboration.strategy.sync.SyncStrategy;

import java.util.List;

/**
 * FACADE — the central entry point for all collaborative editing operations.
 *
 * This is the class an interviewer would expect you to walk through first.
 * It orchestrates the entire pipeline for processing an incoming edit:
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  processOperation(docId, userId, operation) — THE MAIN FLOW
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   1. VALIDATE PERMISSION
 *      → PermissionService.canEdit(docId, userId)
 *      → throws PermissionDeniedException if the user is a VIEWER
 *
 *   2. RETRIEVE DOCUMENT
 *      → DocumentService.getDocument(docId)
 *      → throws DocumentNotFoundException if missing
 *
 *   3. GET PENDING OPS (for OT transform)
 *      → OperationService.getOperationsSince(docId, op.baseVersion)
 *      → these are the ops that happened between the client's version
 *        and the server's current version
 *
 *   4. APPLY via SYNC STRATEGY (OT or CRDT)
 *      → SyncStrategy.applyOperation(doc, op, pendingOps)
 *      → internally transforms the op (OT) or applies directly (CRDT)
 *      → returns list of ops to broadcast
 *
 *   5. PERSIST OPERATION
 *      → OperationService.saveOperation(op)
 *      → PersistenceStrategy.saveOperation(op)
 *
 *   6. BROADCAST to connected users
 *      → BroadcastService.broadcastOperation(docId, op, excludeUserId)
 *
 *   7. UPDATE CURSOR for the editing user
 *      → PresenceService.updateCursor(...)
 *      → PresenceService.adjustCursors(...) for other users
 *
 *   8. CREATE SNAPSHOT (periodic)
 *      → VersionService.createSnapshot(doc) every N operations
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
public class CollaborationService {

    private final DocumentService documentService;
    private final OperationService operationService;
    private final PermissionService permissionService;
    private final PresenceService presenceService;
    private final VersionService versionService;
    private final BroadcastService broadcastService;
    private final SyncStrategy syncStrategy;
    private final PersistenceStrategy persistenceStrategy;

    /** Snapshot every N operations */
    private static final int SNAPSHOT_INTERVAL = 10;
    private int opsSinceSnapshot = 0;

    public CollaborationService(DocumentService documentService,
                                OperationService operationService,
                                PermissionService permissionService,
                                PresenceService presenceService,
                                VersionService versionService,
                                BroadcastService broadcastService,
                                SyncStrategy syncStrategy,
                                PersistenceStrategy persistenceStrategy) {
        this.documentService = documentService;
        this.operationService = operationService;
        this.permissionService = permissionService;
        this.presenceService = presenceService;
        this.versionService = versionService;
        this.broadcastService = broadcastService;
        this.syncStrategy = syncStrategy;
        this.persistenceStrategy = persistenceStrategy;
    }

    /**
     * Process an incoming operation — the main pipeline.
     *
     * @param docId  the document being edited
     * @param userId the user submitting the edit
     * @param op     the operation to apply
     * @return the document after the operation is applied
     */
    public Document processOperation(String docId, String userId, Operation op) {
        // Step 1: Validate permission
        if (!permissionService.canEdit(docId, userId)) {
            throw new PermissionDeniedException(userId, docId, "edit");
        }

        // Step 2: Retrieve the document
        Document doc = documentService.getDocument(docId);

        // Step 3: Get operations since the client's base version (for OT transform)
        List<Operation> pendingOps = operationService.getOperationsSince(docId, op.getBaseVersion());

        // Step 4: Apply via sync strategy (OT transforms or CRDT direct-apply)
        List<Operation> broadcastOps = syncStrategy.applyOperation(doc, op, pendingOps);

        // Step 5: Persist the operation
        operationService.saveOperation(op);
        persistenceStrategy.saveOperation(op);

        // Step 6: Broadcast to other connected users
        for (Operation broadcastOp : broadcastOps) {
            broadcastService.broadcastOperation(docId, broadcastOp, userId);
        }

        // Step 7: Update cursor positions
        // The editing user's cursor moves to the end of their edit
        int newCursorPos = op.getPosition() + op.getInsertLength();
        presenceService.updateCursor(userId, docId, newCursorPos);

        // Adjust OTHER users' cursors based on the applied operation
        for (Operation broadcastOp : broadcastOps) {
            presenceService.adjustCursors(docId, broadcastOp, userId);
        }

        // Step 8: Periodic snapshot
        opsSinceSnapshot++;
        if (opsSinceSnapshot >= SNAPSHOT_INTERVAL) {
            versionService.createSnapshot(doc);
            opsSinceSnapshot = 0;
        }

        return doc;
    }

    // ── Convenience delegation methods ──

    public Document getDocument(String docId) {
        return documentService.getDocument(docId);
    }

    public List<UserPresence> getActiveUsers(String docId) {
        return presenceService.getActiveUsers(docId);
    }

    public List<?> getHistory(String docId) {
        return versionService.getHistory(docId);
    }

    public SyncStrategy getSyncStrategy() {
        return syncStrategy;
    }
}
