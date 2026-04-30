package com.systemdesign.collaboration.model;

/**
 * Tracks a single user's cursor (caret) position within a document.
 *
 * In Google Docs, every collaborator sees colored cursors of other editors.
 * This model carries enough data to render that:
 *   - position:       the caret index (where the next character would be inserted)
 *   - selectionStart / selectionEnd: if the user has highlighted a range
 *   - color:          hex color string so each user gets a unique cursor color
 *
 * Interview note: Cursor positions must also be transformed when remote
 * operations arrive — if another user inserts text before your cursor,
 * your cursor position must shift right.  That transformation is handled
 * in PresenceService.adjustCursors().
 */
public class CursorPosition {

    private final String userId;
    private final String userName;
    private final String docId;
    private int position;
    private int selectionStart;
    private int selectionEnd;
    private final String color; // hex, e.g. "#FF5733"

    public CursorPosition(String userId, String userName, String docId,
                          int position, int selectionStart, int selectionEnd,
                          String color) {
        this.userId = userId;
        this.userName = userName;
        this.docId = docId;
        this.position = position;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.color = color;
    }

    // Convenience constructor — no selection
    public CursorPosition(String userId, String userName, String docId,
                          int position, String color) {
        this(userId, userName, docId, position, position, position, color);
    }

    // ── Getters ──

    public String getUserId()        { return userId; }
    public String getUserName()      { return userName; }
    public String getDocId()         { return docId; }
    public int getPosition()         { return position; }
    public int getSelectionStart()   { return selectionStart; }
    public int getSelectionEnd()     { return selectionEnd; }
    public String getColor()         { return color; }

    public boolean hasSelection() {
        return selectionStart != selectionEnd;
    }

    // ── Setters — positions are mutable (adjusted by OT) ──

    public void setPosition(int position)           { this.position = position; }
    public void setSelectionStart(int start)         { this.selectionStart = start; }
    public void setSelectionEnd(int end)             { this.selectionEnd = end; }

    @Override
    public String toString() {
        String sel = hasSelection()
                ? ", selection=[" + selectionStart + "," + selectionEnd + "]"
                : "";
        return "Cursor{user='" + userName + "', pos=" + position + sel +
               ", color=" + color + "}";
    }
}
