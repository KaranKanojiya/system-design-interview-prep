package com.systemdesign.collaboration.model;

import java.time.LocalDateTime;

/**
 * Core Document model — the shared artifact that multiple users edit concurrently.
 *
 * Design decisions:
 *   - Builder pattern for flexible construction (common in interview settings).
 *   - Content is a StringBuilder for O(1)-amortized inserts/deletes at arbitrary
 *     positions (vs. String concatenation which is O(n) per mutation).
 *   - All content mutations are synchronized so that the server-side document
 *     is always in a consistent state even when multiple operation-processing
 *     threads call applyInsert / applyDelete concurrently.
 *   - Version is an atomically incremented int — each successful operation bumps it.
 *     The version is the key to OT: clients send their "base version" with every op,
 *     and the server transforms the op against all ops that happened since that version.
 *
 * Call chain (typical):
 *   CollaborationService.processOperation()
 *     → OperationService.applyOperation(doc, op)
 *       → doc.applyInsert(pos, text) or doc.applyDelete(pos, length)
 *       → doc.incrementVersion()
 */
public class Document {

    private final String docId;
    private String title;
    private final StringBuilder content;    // mutable character buffer
    private int version;                     // monotonically increasing
    private final String ownerId;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── private constructor — use Builder ──
    private Document(Builder builder) {
        this.docId = builder.docId;
        this.title = builder.title;
        this.content = new StringBuilder(builder.content);
        this.version = builder.version;
        this.ownerId = builder.ownerId;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    // ── Content accessors ──

    /**
     * Returns a snapshot of the document content at this instant.
     * Thread-safe: synchronizes on the content buffer.
     */
    public String getContent() {
        synchronized (content) {
            return content.toString();
        }
    }

    /** Current length in characters. */
    public int getLength() {
        synchronized (content) {
            return content.length();
        }
    }

    // ── Mutation methods (synchronized) ──

    /**
     * Insert {@code text} at {@code position}.
     *
     * Why synchronized? Two users may submit INSERTs that the server processes
     * on different threads.  Without synchronization the StringBuilder could
     * be corrupted (interleaved chars, wrong offsets).
     *
     * @param position 0-based index; 0 = beginning, getLength() = end
     * @param text     the characters to insert
     * @throws IndexOutOfBoundsException if position is negative or > length
     */
    public synchronized void applyInsert(int position, String text) {
        if (position < 0 || position > content.length()) {
            throw new IndexOutOfBoundsException(
                    "Insert position " + position + " out of bounds [0, " + content.length() + "]");
        }
        content.insert(position, text);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Delete {@code length} characters starting at {@code position}.
     *
     * @param position 0-based start index
     * @param length   number of characters to remove
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    public synchronized void applyDelete(int position, int length) {
        if (position < 0 || position + length > content.length()) {
            throw new IndexOutOfBoundsException(
                    "Delete range [" + position + ", " + (position + length) +
                    ") out of bounds [0, " + content.length() + ")");
        }
        content.delete(position, position + length);
        this.updatedAt = LocalDateTime.now();
    }

    /** Bump the version after a successful operation. */
    public synchronized void incrementVersion() {
        this.version++;
        this.updatedAt = LocalDateTime.now();
    }

    // ── Getters ──

    public String getDocId()            { return docId; }
    public String getTitle()            { return title; }
    public int getVersion()             { return version; }
    public String getOwnerId()          { return ownerId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Document{docId='" + docId + "', title='" + title +
               "', version=" + version + ", length=" + getLength() +
               ", owner='" + ownerId + "'}";
    }

    // ══════════════════════════════════════════════
    //  Builder
    // ══════════════════════════════════════════════

    public static class Builder {
        private String docId;
        private String title = "Untitled";
        private String content = "";
        private int version = 0;
        private String ownerId;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();

        public Builder docId(String docId)              { this.docId = docId; return this; }
        public Builder title(String title)              { this.title = title; return this; }
        public Builder content(String content)          { this.content = content; return this; }
        public Builder version(int version)             { this.version = version; return this; }
        public Builder ownerId(String ownerId)          { this.ownerId = ownerId; return this; }
        public Builder createdAt(LocalDateTime t)       { this.createdAt = t; return this; }
        public Builder updatedAt(LocalDateTime t)       { this.updatedAt = t; return this; }

        public Document build() {
            if (docId == null || docId.isBlank())   throw new IllegalArgumentException("docId required");
            if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId required");
            return new Document(this);
        }
    }
}
