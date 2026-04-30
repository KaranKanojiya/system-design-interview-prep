package com.systemdesign.collaboration.repository;

import com.systemdesign.collaboration.model.Operation;

import java.util.List;

/**
 * Repository abstraction for Operation storage.
 *
 * Operations form the "event log" in an event-sourced system.
 * In production: Kafka, EventStore DB, or an append-only table.
 */
public interface OperationRepository {

    void save(Operation operation);

    List<Operation> findByDocId(String docId);

    /**
     * Find operations for a document with baseVersion > fromVersion.
     * Used by OT to retrieve ops that happened since the client's base version.
     */
    List<Operation> findByDocIdSinceVersion(String docId, int fromVersion);

    int countByDocId(String docId);
}
