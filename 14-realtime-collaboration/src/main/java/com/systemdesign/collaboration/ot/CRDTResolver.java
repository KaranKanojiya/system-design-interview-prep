package com.systemdesign.collaboration.ot;

import com.systemdesign.collaboration.model.Operation;

/**
 * Simplified CRDT (Conflict-free Replicated Data Type) resolver.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  HOW CRDTs DIFFER FROM OT
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * OT (Operational Transformation):
 *   - Server-centric: a central server transforms operations against each other.
 *   - Operations are position-based (character index).
 *   - Transform function is complex (see OTResolver — 4 cases, many sub-cases).
 *   - Used by: Google Docs, Microsoft Office Online.
 *
 * CRDT (Conflict-free Replicated Data Type):
 *   - Decentralized: no server needed to resolve conflicts.
 *   - Each character has a globally unique ID (userId + counter / Lamport timestamp).
 *   - Characters are ordered by their unique IDs, not by integer positions.
 *   - INSERT = create a new character with a unique ID between two existing IDs.
 *   - DELETE = mark a character as a "tombstone" (logically deleted, not removed).
 *   - Operations COMMUTE naturally — applying A then B gives the same result
 *     as applying B then A, WITHOUT any transform function.
 *   - Trade-off: more metadata per character (IDs, tombstones consume memory).
 *   - Used by: Figma, Yjs, Automerge.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  THIS IMPLEMENTATION
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This is a SIMPLIFIED simulation.  A real CRDT (like RGA or LSEQ) would:
 *   1. Assign each character a unique ID (e.g., "user1:42").
 *   2. Maintain a linked list ordered by these IDs.
 *   3. On INSERT: find the position between two IDs, create a new ID in between.
 *   4. On DELETE: find the ID and mark it as a tombstone.
 *
 * Because operations reference unique IDs rather than mutable positions,
 * the transform() method is trivial: both operations can be applied as-is.
 * No position adjustment is needed.
 *
 * In this demo we still use position-based operations (for compatibility with
 * the Document model), but the transform is a no-op to illustrate the key
 * CRDT property: commutativity.
 */
public class CRDTResolver implements ConflictResolver {

    /**
     * Counter for generating unique character IDs.
     * In a real CRDT, each client maintains its own counter (Lamport clock).
     */
    private int operationCounter = 0;

    @Override
    public String getName() {
        return "CRDT (Conflict-free Replicated Data Type)";
    }

    /**
     * CRDT transform is a NO-OP.
     *
     * Because CRDT operations reference globally unique character IDs,
     * they commute by design.  There's no need to adjust positions.
     *
     * In a position-based simulation like ours, this means we simply
     * return copies of the original operations unchanged.  The assumption
     * is that the underlying data structure (a CRDT sequence like RGA)
     * would handle ordering internally.
     *
     * Compare this to OTResolver.transform() which has ~200 lines of
     * position-adjustment logic.  This simplicity is the main advantage
     * of CRDTs.
     *
     * The trade-off: every character needs a unique ID, and deleted
     * characters leave tombstones that consume memory until garbage-collected.
     */
    @Override
    public TransformResult transform(Operation local, Operation remote) {
        operationCounter++;

        // No transformation needed — CRDTs commute.
        // Just return copies of the originals.
        Operation localPrime = new Operation(local);
        Operation remotePrime = new Operation(remote);

        // In a real CRDT, we'd assign unique IDs here:
        //   String localCharId  = local.getUserId() + ":" + operationCounter;
        //   String remoteCharId = remote.getUserId() + ":" + (operationCounter + 1);
        // These IDs determine the total order without needing position transforms.

        return new TransformResult(localPrime, remotePrime);
    }

    public int getOperationCounter() {
        return operationCounter;
    }
}
