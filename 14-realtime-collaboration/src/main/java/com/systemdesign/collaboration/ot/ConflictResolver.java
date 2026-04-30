package com.systemdesign.collaboration.ot;

import com.systemdesign.collaboration.model.Operation;

/**
 * Strategy interface for resolving concurrent-edit conflicts.
 *
 * Two implementations exist in this project:
 *   1. OTResolver  — Operational Transformation (Google Docs approach)
 *   2. CRDTResolver — Conflict-free Replicated Data Type (Figma approach)
 *
 * The Strategy pattern lets the system swap conflict-resolution algorithms
 * without changing the sync pipeline:
 *   CollaborationService → SyncStrategy → ConflictResolver.transform()
 *
 * Interview note: Being able to compare OT vs CRDT at the code level is
 * a strong signal in a system design interview.
 */
public interface ConflictResolver {

    /**
     * Transform two concurrent operations so that both can be applied
     * in either order and produce the same final document.
     *
     * @param local  the operation from one client
     * @param remote the operation from another client (concurrent)
     * @return a TransformResult containing the adjusted pair
     */
    TransformResult transform(Operation local, Operation remote);

    /** Human-readable name of this resolver for display/logging. */
    String getName();
}
