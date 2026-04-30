package com.systemdesign.filestorage.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * FileVersion — immutable snapshot of a file at a point in time.
 *
 * Design decisions:
 * - chunkHashes list: instead of storing the actual data, we store references to
 *   content-addressed blocks. This means two versions that share chunks don't
 *   duplicate storage (dedup at the version level).
 * - versionNumber is a simple incrementing integer per file (not global).
 * - comment is optional — like a git commit message for the file edit.
 *
 * Call chain:
 *   UploadService.uploadFile → VersionService.createVersion(fileId, chunkHashes, ...)
 *   VersionService.rollbackToVersion → creates a NEW version with the OLD version's chunkHashes
 *   DownloadService.downloadFile → gets latest version → iterates chunkHashes → BlockStore.getBlock()
 */
public class FileVersion {

    private final String versionId;
    private final String fileId;
    private final int versionNumber;
    private final List<String> chunkHashes;   // ordered list of chunk hashes for this version
    private final long sizeBytes;
    private final String comment;             // optional description of the change
    private final Instant createdAt;
    private final String createdBy;           // userId who created this version

    public FileVersion(String versionId, String fileId, int versionNumber,
                       List<String> chunkHashes, long sizeBytes,
                       String comment, Instant createdAt, String createdBy) {
        this.versionId = versionId;
        this.fileId = fileId;
        this.versionNumber = versionNumber;
        this.chunkHashes = Collections.unmodifiableList(chunkHashes);
        this.sizeBytes = sizeBytes;
        this.comment = comment;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    /** Number of chunks that make up this version of the file. */
    public int getChunkCount() {
        return chunkHashes.size();
    }

    public String getVersionId() { return versionId; }
    public String getFileId() { return fileId; }
    public int getVersionNumber() { return versionNumber; }
    public List<String> getChunkHashes() { return chunkHashes; }
    public long getSizeBytes() { return sizeBytes; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }

    @Override
    public String toString() {
        return String.format("FileVersion{id='%s', fileId='%s', v%d, chunks=%d, size=%d, by='%s', comment='%s'}",
                versionId, fileId, versionNumber, getChunkCount(), sizeBytes, createdBy,
                comment != null ? comment : "");
    }
}
