package com.systemdesign.collaboration.service;

import com.systemdesign.collaboration.exception.CollaborationException;
import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.Operation;
import com.systemdesign.collaboration.model.OperationType;
import com.systemdesign.collaboration.repository.OperationRepository;

import java.util.List;

/**
 * Service responsible for applying and validating operations on documents.
 *
 * This is the low-level operation engine.  It does NOT handle conflict resolution
 * (that's SyncStrategy + ConflictResolver).  It applies already-resolved
 * operations to the document and persists them.
 *
 * Call chain:
 *   CollaborationService.processOperation()
 *     → SyncStrategy.applyOperation()           // resolves conflicts
 *       → OTSyncStrategy calls doc.applyInsert/applyDelete internally
 *     → OperationService.saveOperation()         // persists
 */
public class OperationService {

    private final OperationRepository operationRepository;

    public OperationService(OperationRepository operationRepository) {
        this.operationRepository = operationRepository;
    }

    /**
     * Apply an operation to a document.
     * This is a direct application — no OT transform.  The caller must ensure
     * the operation has already been transformed if needed.
     */
    public void applyOperation(Document doc, Operation op) {
        validateOperation(doc, op);

        switch (op.getType()) {
            case INSERT -> doc.applyInsert(op.getPosition(), op.getContent());
            case DELETE -> doc.applyDelete(op.getPosition(), op.getLength());
            case RETAIN -> { /* no-op */ }
        }

        doc.incrementVersion();
    }

    /**
     * Validate that an operation can be applied to the current document state.
     *
     * Checks:
     *   - INSERT position must be in [0, doc.length]
     *   - DELETE range must be within [0, doc.length)
     *   - INSERT must have non-null content
     *   - DELETE must have positive length
     */
    public void validateOperation(Document doc, Operation op) {
        int docLen = doc.getLength();

        switch (op.getType()) {
            case INSERT -> {
                if (op.getContent() == null || op.getContent().isEmpty()) {
                    throw new CollaborationException("INSERT operation must have content");
                }
                if (op.getPosition() < 0 || op.getPosition() > docLen) {
                    throw new CollaborationException(
                            "INSERT position " + op.getPosition() +
                            " out of bounds [0, " + docLen + "]");
                }
            }
            case DELETE -> {
                if (op.getLength() <= 0) {
                    throw new CollaborationException("DELETE operation must have positive length");
                }
                if (op.getPosition() < 0 || op.getPosition() + op.getLength() > docLen) {
                    throw new CollaborationException(
                            "DELETE range [" + op.getPosition() + ", " +
                            (op.getPosition() + op.getLength()) +
                            ") out of bounds [0, " + docLen + ")");
                }
            }
            case RETAIN -> { /* always valid */ }
        }
    }

    /** Persist an operation to the repository. */
    public void saveOperation(Operation op) {
        operationRepository.save(op);
    }

    /**
     * Get all operations for a document since a given version.
     * Used by OT to find ops that need to be transformed against.
     */
    public List<Operation> getOperationsSince(String docId, int version) {
        return operationRepository.findByDocIdSinceVersion(docId, version);
    }

    /** Get all operations for a document. */
    public List<Operation> getAllOperations(String docId) {
        return operationRepository.findByDocId(docId);
    }

    /** Total operation count for a document. */
    public int getOperationCount(String docId) {
        return operationRepository.countByDocId(docId);
    }
}
