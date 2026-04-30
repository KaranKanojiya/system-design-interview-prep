package com.systemdesign.collaboration.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An atomic editing operation against a document.
 *
 * Every keystroke or paste in the client is turned into an Operation and
 * sent to the server.  The operation carries enough context for the server
 * to transform it (OT) or merge it (CRDT):
 *
 *   - baseVersion: the document version the client saw when creating this op.
 *     If the server's current version > baseVersion, the server must transform
 *     this op against all intervening ops before applying it.
 *
 *   - position: character index (0-based) where the operation acts.
 *
 *   - content: the text to insert (only meaningful for INSERT).
 *
 *   - length: number of characters to delete (only meaningful for DELETE).
 *
 * Interview note: In Google Docs' real protocol, ops are more fine-grained
 * (retain + insert + delete in a single compound op), but this simplified
 * model is sufficient to demonstrate OT transform logic.
 */
public class Operation {

    private final String opId;
    private final String docId;
    private final String userId;
    private final OperationType type;
    private int position;      // mutable — OT transform may adjust it
    private final String content;   // for INSERT
    private int length;        // for DELETE — mutable for overlapping-delete transform
    private final int baseVersion;  // doc version this op was created against
    private final LocalDateTime timestamp;

    public Operation(String docId, String userId, OperationType type,
                     int position, String content, int length, int baseVersion) {
        this.opId = UUID.randomUUID().toString().substring(0, 8);
        this.docId = docId;
        this.userId = userId;
        this.type = type;
        this.position = position;
        this.content = content;
        this.length = length;
        this.baseVersion = baseVersion;
        this.timestamp = LocalDateTime.now();
    }

    // ── Copy constructor — used by OT to build transformed operations ──
    public Operation(Operation source) {
        this.opId = UUID.randomUUID().toString().substring(0, 8);
        this.docId = source.docId;
        this.userId = source.userId;
        this.type = source.type;
        this.position = source.position;
        this.content = source.content;
        this.length = source.length;
        this.baseVersion = source.baseVersion;
        this.timestamp = source.timestamp;
    }

    // ── Getters ──

    public String getOpId()              { return opId; }
    public String getDocId()             { return docId; }
    public String getUserId()            { return userId; }
    public OperationType getType()       { return type; }
    public int getPosition()             { return position; }
    public String getContent()           { return content; }
    public int getLength()               { return length; }
    public int getBaseVersion()          { return baseVersion; }
    public LocalDateTime getTimestamp()   { return timestamp; }

    // ── Setters used by OT transform (position/length may be shifted) ──

    public void setPosition(int position) { this.position = position; }
    public void setLength(int length)     { this.length = length; }

    /**
     * Effective length of the content this operation introduces.
     * INSERT → content.length(), DELETE → 0, RETAIN → 0.
     */
    public int getInsertLength() {
        return (type == OperationType.INSERT && content != null) ? content.length() : 0;
    }

    @Override
    public String toString() {
        return switch (type) {
            case INSERT -> String.format("Op[%s %s INSERT pos=%d text='%s' baseV=%d user=%s]",
                    opId, docId, position, content, baseVersion, userId);
            case DELETE -> String.format("Op[%s %s DELETE pos=%d len=%d baseV=%d user=%s]",
                    opId, docId, position, length, baseVersion, userId);
            case RETAIN -> String.format("Op[%s %s RETAIN pos=%d len=%d baseV=%d user=%s]",
                    opId, docId, position, length, baseVersion, userId);
        };
    }
}
