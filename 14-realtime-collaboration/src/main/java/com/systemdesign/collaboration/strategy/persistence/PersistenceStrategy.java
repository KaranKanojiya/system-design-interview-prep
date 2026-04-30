package com.systemdesign.collaboration.strategy.persistence;

import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.DocumentVersion;
import com.systemdesign.collaboration.model.Operation;

import java.util.List;

/**
 * Strategy interface for persisting document operations and snapshots.
 *
 * Two implementations:
 *   1. SnapshotPersistence    — periodic snapshots + operation log
 *   2. EventSourcedPersistence — every operation stored, full audit trail
 *
 * Interview note: This is the Storage layer decision in the system design.
 * Snapshots are more efficient for reads; event sourcing is better for
 * audit trails and time-travel debugging.
 */
public interface PersistenceStrategy {

    /** Persist a single operation (append to the operation log). */
    void saveOperation(Operation operation);

    /** Create a snapshot of the current document state. */
    void createSnapshot(Document document);

    /**
     * Retrieve version history for a document within a version range.
     *
     * @param docId       the document ID
     * @param fromVersion start version (inclusive)
     * @param toVersion   end version (inclusive)
     * @return list of DocumentVersion snapshots in that range
     */
    List<DocumentVersion> getHistory(String docId, int fromVersion, int toVersion);

    /** Get all stored operations for a document. */
    List<Operation> getOperations(String docId);

    /** Human-readable name for logging. */
    String getName();
}
