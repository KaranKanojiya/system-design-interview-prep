package com.systemdesign.collaboration.model;

import java.time.LocalDateTime;

/**
 * A snapshot of a document at a particular version number.
 *
 * Why snapshots?
 *   Replaying thousands of operations from version 0 to reconstruct the current
 *   content is expensive.  By periodically saving a snapshot, we only need to
 *   replay ops since the last snapshot.  This is the same approach Google Docs
 *   uses (snapshot + operation log).
 *
 * Fields:
 *   - contentSnapshot: full document text at this version
 *   - operationCount:  how many ops were applied between the previous snapshot
 *                      and this one (useful for deciding when to take the next snapshot)
 */
public class DocumentVersion {

    private final String versionId;
    private final String docId;
    private final int versionNumber;
    private final String contentSnapshot;
    private final int operationCount;
    private final LocalDateTime createdAt;

    public DocumentVersion(String versionId, String docId, int versionNumber,
                           String contentSnapshot, int operationCount) {
        this.versionId = versionId;
        this.docId = docId;
        this.versionNumber = versionNumber;
        this.contentSnapshot = contentSnapshot;
        this.operationCount = operationCount;
        this.createdAt = LocalDateTime.now();
    }

    // ── Getters ──

    public String getVersionId()        { return versionId; }
    public String getDocId()            { return docId; }
    public int getVersionNumber()       { return versionNumber; }
    public String getContentSnapshot()  { return contentSnapshot; }
    public int getOperationCount()      { return operationCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "DocumentVersion{doc='" + docId + "', v=" + versionNumber +
               ", ops=" + operationCount +
               ", snapshot='" + (contentSnapshot.length() > 40
                    ? contentSnapshot.substring(0, 40) + "..."
                    : contentSnapshot) + "'}";
    }
}
