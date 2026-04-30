package com.systemdesign.collaboration.service;

import com.systemdesign.collaboration.exception.CollaborationException;
import com.systemdesign.collaboration.exception.DocumentNotFoundException;
import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.DocumentVersion;
import com.systemdesign.collaboration.repository.DocumentRepository;
import com.systemdesign.collaboration.repository.OperationRepository;
import com.systemdesign.collaboration.repository.VersionRepository;

import java.util.List;
import java.util.UUID;

/**
 * Service for document version management: snapshots, history, and rollback.
 *
 * Call chains:
 *   CollaborationService.processOperation()
 *     → VersionService.createSnapshot(doc)          // periodic snapshots
 *
 *   Controller.handleGetHistory()
 *     → VersionService.getHistory(docId)
 *
 *   Controller.handleRollback()
 *     → VersionService.rollbackToVersion(docId, version)
 *       → finds the snapshot
 *       → resets the document content to the snapshot
 */
public class VersionService {

    private final VersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final OperationRepository operationRepository;

    public VersionService(VersionRepository versionRepository,
                          DocumentRepository documentRepository,
                          OperationRepository operationRepository) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.operationRepository = operationRepository;
    }

    /**
     * Create a snapshot of the current document state.
     */
    public DocumentVersion createSnapshot(Document doc) {
        int opCount = operationRepository.countByDocId(doc.getDocId());
        DocumentVersion version = new DocumentVersion(
                UUID.randomUUID().toString().substring(0, 8),
                doc.getDocId(),
                doc.getVersion(),
                doc.getContent(),
                opCount
        );
        versionRepository.save(version);
        return version;
    }

    /**
     * Get all version snapshots for a document.
     */
    public List<DocumentVersion> getHistory(String docId) {
        return versionRepository.findByDocId(docId);
    }

    /**
     * Rollback a document to a specific version.
     *
     * Finds the snapshot for that version and resets the document content.
     * In a real system, this would create a new version (not actually go back in time).
     */
    public Document rollbackToVersion(String docId, int targetVersion) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new DocumentNotFoundException(docId));

        DocumentVersion snapshot = versionRepository.findByDocIdAndVersion(docId, targetVersion)
                .orElseThrow(() -> new CollaborationException(
                        "No snapshot found for version " + targetVersion));

        // Reset document content to the snapshot
        // We do this by deleting all current content and inserting the snapshot
        synchronized (doc) {
            int currentLen = doc.getLength();
            if (currentLen > 0) {
                doc.applyDelete(0, currentLen);
            }
            doc.applyInsert(0, snapshot.getContentSnapshot());
            doc.incrementVersion();
        }

        return doc;
    }

    /**
     * Get a diff description between two versions.
     * Returns a human-readable summary of changes.
     */
    public String getVersionDiff(String docId, int fromVersion, int toVersion) {
        var fromSnap = versionRepository.findByDocIdAndVersion(docId, fromVersion);
        var toSnap = versionRepository.findByDocIdAndVersion(docId, toVersion);

        if (fromSnap.isEmpty() || toSnap.isEmpty()) {
            return "Cannot compute diff: snapshot(s) missing for versions "
                    + fromVersion + " and/or " + toVersion;
        }

        String fromContent = fromSnap.get().getContentSnapshot();
        String toContent = toSnap.get().getContentSnapshot();

        int lenDiff = toContent.length() - fromContent.length();
        String direction = lenDiff > 0 ? "+" + lenDiff + " chars"
                         : lenDiff < 0 ? lenDiff + " chars"
                         : "same length";

        return String.format("Version %d → %d: %s (from %d to %d chars)",
                fromVersion, toVersion, direction,
                fromContent.length(), toContent.length());
    }
}
