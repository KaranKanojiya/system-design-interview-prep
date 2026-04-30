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
 * Snapshot-based persistence: save a full document snapshot every N operations.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  STRATEGY
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   - Every {@link #SNAPSHOT_INTERVAL} operations, take a full snapshot.
 *   - Between snapshots, only store the operations (much smaller).
 *   - To reconstruct a version: find the nearest earlier snapshot, then
 *     replay operations forward from that snapshot.
 *
 *   Trade-offs:
 *     + Fast reads (snapshots are pre-computed)
 *     + Moderate storage (snapshot + ops between snapshots)
 *     - Snapshots take space (full document text)
 *     - Losing the operation log between snapshots means data loss
 *
 *   This is the approach Google Docs uses in practice.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
public class SnapshotPersistence implements PersistenceStrategy {

    /** Take a snapshot every 100 operations. */
    private static final int SNAPSHOT_INTERVAL = 100;

    /** docId → list of snapshots */
    private final Map<String, List<DocumentVersion>> snapshots = new ConcurrentHashMap<>();

    /** docId → list of operations since last snapshot */
    private final Map<String, List<Operation>> operationLog = new ConcurrentHashMap<>();

    /** docId → count of ops since the last snapshot */
    private final Map<String, Integer> opsSinceSnapshot = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "Snapshot Persistence (every " + SNAPSHOT_INTERVAL + " ops)";
    }

    @Override
    public void saveOperation(Operation operation) {
        String docId = operation.getDocId();
        operationLog.computeIfAbsent(docId, k -> new ArrayList<>()).add(operation);
        int count = opsSinceSnapshot.merge(docId, 1, Integer::sum);

        // Note: we don't automatically snapshot here because we don't have
        // the Document reference.  The caller (VersionService) decides when
        // to call createSnapshot().  But we track the count so the caller
        // can check shouldSnapshot().
    }

    @Override
    public void createSnapshot(Document document) {
        String docId = document.getDocId();
        DocumentVersion version = new DocumentVersion(
                UUID.randomUUID().toString().substring(0, 8),
                docId,
                document.getVersion(),
                document.getContent(),
                opsSinceSnapshot.getOrDefault(docId, 0)
        );
        snapshots.computeIfAbsent(docId, k -> new ArrayList<>()).add(version);
        opsSinceSnapshot.put(docId, 0); // reset counter
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
        return new ArrayList<>(operationLog.getOrDefault(docId, List.of()));
    }

    /** Check if it's time to take a snapshot for a document. */
    public boolean shouldSnapshot(String docId) {
        return opsSinceSnapshot.getOrDefault(docId, 0) >= SNAPSHOT_INTERVAL;
    }
}
