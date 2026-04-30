package com.systemdesign.collaboration.strategy.sync;

import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.Operation;

import java.util.List;

/**
 * Strategy interface for synchronizing concurrent operations on a document.
 *
 * Two implementations:
 *   1. OTSyncStrategy   — server transforms ops (Google Docs model)
 *   2. CRDTSyncStrategy — ops commute, no server transform needed (Figma model)
 *
 * Call chain:
 *   CollaborationService.processOperation()
 *     → syncStrategy.applyOperation(doc, incomingOp, pendingOps)
 *     → returns list of transformed ops to broadcast to other clients
 */
public interface SyncStrategy {

    /**
     * Apply an incoming operation to the document, resolving any conflicts
     * with pending (unacknowledged) operations from other clients.
     *
     * @param doc        the server's authoritative document
     * @param op         the incoming operation from a client
     * @param pendingOps operations that have been applied since op's baseVersion
     * @return list of transformed operations to broadcast to other clients
     */
    List<Operation> applyOperation(Document doc, Operation op, List<Operation> pendingOps);

    /** Human-readable name for logging. */
    String getName();
}
