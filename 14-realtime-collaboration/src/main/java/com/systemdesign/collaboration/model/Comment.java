package com.systemdesign.collaboration.model;

import java.time.LocalDateTime;

/**
 * A comment anchored to a specific position in a document.
 *
 * Google Docs allows users to highlight a range and attach a comment.
 * For simplicity this model anchors to a single position (the start of the
 * highlighted range).
 *
 * Comments can be resolved (like marking a TODO as done) and track
 * the resolution timestamp.
 */
public class Comment {

    private final String commentId;
    private final String docId;
    private final String userId;
    private final String userName;
    private final String content;
    private int anchorPosition;     // mutable — shifts when text is inserted/deleted before it
    private boolean isResolved;
    private final LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    public Comment(String commentId, String docId, String userId,
                   String userName, String content, int anchorPosition) {
        this.commentId = commentId;
        this.docId = docId;
        this.userId = userId;
        this.userName = userName;
        this.content = content;
        this.anchorPosition = anchorPosition;
        this.isResolved = false;
        this.createdAt = LocalDateTime.now();
    }

    /** Mark this comment as resolved. */
    public void resolve() {
        this.isResolved = true;
        this.resolvedAt = LocalDateTime.now();
    }

    // ── Getters ──

    public String getCommentId()         { return commentId; }
    public String getDocId()             { return docId; }
    public String getUserId()            { return userId; }
    public String getUserName()          { return userName; }
    public String getContent()           { return content; }
    public int getAnchorPosition()       { return anchorPosition; }
    public boolean isResolved()          { return isResolved; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }

    public void setAnchorPosition(int pos) { this.anchorPosition = pos; }

    @Override
    public String toString() {
        return "Comment{id='" + commentId + "', by='" + userName +
               "', pos=" + anchorPosition + ", resolved=" + isResolved +
               ", text='" + content + "'}";
    }
}
