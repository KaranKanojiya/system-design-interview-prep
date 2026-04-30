package com.systemdesign.collaboration.strategy.sync;

import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.Operation;
import com.systemdesign.collaboration.model.OperationType;
import com.systemdesign.collaboration.ot.ConflictResolver;
import com.systemdesign.collaboration.ot.TransformResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-centric Operational Transformation sync strategy (Google Docs model).
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  HOW IT WORKS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * 1. Client sends an operation with a baseVersion (the doc version the client
 *    saw when creating the op).
 *
 * 2. Server checks: does op.baseVersion == doc.currentVersion?
 *
 *    YES → No conflict.  Apply the operation directly.
 *
 *    NO  → There are ops that happened between baseVersion and currentVersion.
 *          The server retrieves those ops (pendingOps) and transforms the
 *          incoming op against each of them sequentially:
 *
 *            op' = transform(op, pending[0]).transformedLocal
 *            op' = transform(op', pending[1]).transformedLocal
 *            ...
 *
 *          After all transforms, op' is safe to apply to the current document.
 *
 * 3. Apply the (possibly transformed) operation to the document.
 *
 * 4. Broadcast the operation to all other connected clients so they can
 *    apply it to their local copies (clients also run OT on their side).
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Interview note: The server is the single source of truth.  It maintains a
 * linear history of operations.  This avoids the need for TP2 (transformation
 * property 2) which is required for decentralized OT but notoriously hard to
 * implement correctly.
 */
public class OTSyncStrategy implements SyncStrategy {

    private final ConflictResolver conflictResolver;

    /** Track how many conflicts we've resolved for stats. */
    private int conflictsResolved = 0;

    public OTSyncStrategy(ConflictResolver conflictResolver) {
        this.conflictResolver = conflictResolver;
    }

    @Override
    public String getName() {
        return "OT Sync (Server-Centric)";
    }

    @Override
    public List<Operation> applyOperation(Document doc, Operation op, List<Operation> pendingOps) {
        List<Operation> broadcastOps = new ArrayList<>();
        Operation transformedOp = op;

        // Step 1: Check if we need to transform
        if (op.getBaseVersion() < doc.getVersion() && !pendingOps.isEmpty()) {
            // There are ops between the client's base version and the server's
            // current version.  Transform against each one sequentially.
            //
            // Why sequentially? Each transform adjusts positions based on the
            // previous transform's result.  Order matters.
            for (Operation pendingOp : pendingOps) {
                TransformResult result = conflictResolver.transform(transformedOp, pendingOp);
                // We want the incoming op adjusted for the pending op
                transformedOp = result.getTransformedLocal();
                conflictsResolved++;
            }
        }

        // Step 2: Apply the (possibly transformed) operation to the document
        applyToDocument(doc, transformedOp);

        // Step 3: Increment the document version
        doc.incrementVersion();

        // Step 4: The transformed operation is what we broadcast to other clients
        broadcastOps.add(transformedOp);

        return broadcastOps;
    }

    /**
     * Apply a single operation to the document's content.
     * This is the final step after all OT transforms have been applied.
     */
    private void applyToDocument(Document doc, Operation op) {
        switch (op.getType()) {
            case INSERT -> doc.applyInsert(op.getPosition(), op.getContent());
            case DELETE -> {
                if (op.getLength() > 0) {
                    doc.applyDelete(op.getPosition(), op.getLength());
                }
            }
            case RETAIN -> { /* no-op: retain doesn't modify content */ }
        }
    }

    public int getConflictsResolved() {
        return conflictsResolved;
    }
}
