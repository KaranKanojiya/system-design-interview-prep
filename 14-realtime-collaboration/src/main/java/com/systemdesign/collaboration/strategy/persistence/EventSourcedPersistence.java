package com.systemdesign.collaboration.strategy.persistence;

import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.DocumentVersion;
import com.systemdesign.collaboration.model.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-sourced persistence: store EVERY operation — complete audit trail.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  STRATEGY
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   - Every single operation is stored permanently.
 *   - To reconstruct the document at any version: start from an empty document
 *     and replay operations 1 through N.
 *   - Snapshots are also stored (as optimizations), but the operations are
 *     the source of truth.
 *
 *   Trade-offs:
 *     + Complete audit trail (who changed what, when)
 *     + Can reconstruct ANY version by replaying ops
 *     + Perfect for debugging and compliance
 *     - More storage (every keystroke is saved)
 *     - Slower reads for old versions (must replay from beginning or nearest snapshot)
 *
 *   This is the Event Sourcing pattern from DDD (Domain-Driven Design).
 *   Used by systems that need full traceability.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
public class EventSourcedPersistence implements PersistenceStrategy {

    /** docId → ordered list of ALL operations (the event store) */
    private final Map<String, List<Operation>> eventStore = new ConcurrentHashMap<>();

    /** docId → list of snapshots (optimization, not the source of truth) */
    private final Map<String, List<DocumentVersion>> snapshots = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "Event-Sourced Persistence (full audit trail)";
    }

    @Override
    public void saveOperation(Operation operation) {
        // Append to the event store — this is the ONLY write path.
        // In a real system this would be an append-only log (Kafka, EventStore DB).
        eventStore.computeIfAbsent(operation.getDocId(), k -> new ArrayList<>())
                  .add(operation);
    }

    @Override
    public void createSnapshot(Document document) {
        // Snapshots are an optimization.  They let us skip replaying ops
        // from the beginning when we need to reconstruct a recent version.
        String docId = document.getDocId();
        int opCount = eventStore.getOrDefault(docId, List.of()).size();
        DocumentVersion version = new DocumentVersion(
                UUID.randomUUID().toString().substring(0, 8),
                docId,
                document.getVersion(),
                document.getContent(),
                opCount
        );
        snapshots.computeIfAbsent(docId, k -> new ArrayList<>()).add(version);
    }

    @Override
    public List<DocumentVersion> getHistory(String docId, int fromVersion, int toVersion) {
        List<DocumentVersion> all = snapshots.getOrDefault(docId, List.of());
        List<DocumentVersion> result = new ArrayList<>();
        for (DocumentVersion dv : all) {
            if (dv.getVersionNumber() >= fromVersion && dv.getVersionNumber() <= toVersion) {
                result.add(dv);
            }
        }
        return result;
    }

    @Override
    public List<Operation> getOperations(String docId) {
        return new ArrayList<>(eventStore.getOrDefault(docId, List.of()));
    }

    /** Total number of events stored across all documents. */
    public int getTotalEventCount() {
        return eventStore.values().stream().mapToInt(List::size).sum();
    }
}
