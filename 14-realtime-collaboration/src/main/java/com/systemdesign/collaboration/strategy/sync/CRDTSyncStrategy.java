package com.systemdesign.collaboration.strategy.sync;

import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.Operation;

import java.util.ArrayList;
import java.util.List;

/**
 * CRDT-based sync strategy — apply operations directly without server-side transform.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  HOW IT WORKS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * In a CRDT model:
 *   - Each character has a globally unique ID.
 *   - Operations reference these unique IDs, NOT integer positions.
 *   - Because IDs never change, operations commute by design.
 *   - The server simply applies every incoming op directly — no transform needed.
 *
 * COMPARISON WITH OT:
 *   ┌──────────────────────┬──────────────────────┬──────────────────────┐
 *   │                      │ OT                   │ CRDT                 │
 *   ├──────────────────────┼──────────────────────┼──────────────────────┤
 *   │ Server complexity    │ High (transform fn)  │ Low (just apply)     │
 *   │ Client complexity    │ Medium               │ Higher (ID mgmt)     │
 *   │ Metadata per char    │ None                 │ Unique ID + tombstone│
 *   │ Memory overhead      │ Low                  │ Higher               │
 *   │ Decentralized?       │ No (needs server)    │ Yes (P2P possible)   │
 *   │ Correctness proof    │ Hard (TP1/TP2)       │ Easier (math proof)  │
 *   │ Used by              │ Google Docs          │ Figma, Yjs           │
 *   └──────────────────────┴──────────────────────┴──────────────────────┘
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This is a simplified simulation: we still use position-based operations and
 * apply them directly.  In a real CRDT, the underlying data structure (e.g., RGA)
 * would handle ordering via unique character IDs.
 */
public class CRDTSyncStrategy implements SyncStrategy {

    @Override
    public String getName() {
        return "CRDT Sync (Commutative)";
    }

    @Override
    public List<Operation> applyOperation(Document doc, Operation op, List<Operation> pendingOps) {
        List<Operation> broadcastOps = new ArrayList<>();

        // CRDT: no transform needed.  Apply directly.
        // In a real CRDT the underlying data structure ensures convergence.
        switch (op.getType()) {
            case INSERT -> doc.applyInsert(op.getPosition(), op.getContent());
            case DELETE -> {
                // Safely handle delete — in CRDT, a delete on an already-tombstoned
                // character is a no-op.  Here we just bounds-check.
                if (op.getLength() > 0 && op.getPosition() + op.getLength() <= doc.getLength()) {
                    doc.applyDelete(op.getPosition(), op.getLength());
                }
            }
            case RETAIN -> { /* no-op */ }
        }

        doc.incrementVersion();

        // Broadcast the operation as-is — clients with CRDT data structures
        // can apply it directly too.
        broadcastOps.add(op);

        return broadcastOps;
    }
}
