package com.systemdesign.collaboration.model;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Presence information for a user in a collaborative document session.
 *
 * Real-time collaboration needs to know:
 *   - Who is currently viewing/editing this document?
 *   - Where is their cursor?
 *   - Are they actually active or did they leave the tab open and walk away?
 *
 * The isStale() method enables a background cleanup routine to mark users
 * as inactive after a timeout (similar to how Google Docs shows "Viewing"
 * then eventually removes idle avatars).
 */
public class UserPresence {

    private final String userId;
    private final String userName;
    private final String docId;
    private boolean isActive;
    private LocalDateTime lastActiveAt;
    private CursorPosition cursorPosition;
    private final String cursorColor;

    public UserPresence(String userId, String userName, String docId, String cursorColor) {
        this.userId = userId;
        this.userName = userName;
        this.docId = docId;
        this.isActive = true;
        this.lastActiveAt = LocalDateTime.now();
        this.cursorColor = cursorColor;
        // Default cursor at the beginning of the document
        this.cursorPosition = new CursorPosition(userId, userName, docId, 0, cursorColor);
    }

    /**
     * Returns true if this user hasn't been active for longer than
     * {@code timeoutSeconds}.  Used by PresenceService.cleanupStalePresence().
     */
    public boolean isStale(long timeoutSeconds) {
        long secondsSinceActive = ChronoUnit.SECONDS.between(lastActiveAt, LocalDateTime.now());
        return secondsSinceActive > timeoutSeconds;
    }

    /** Mark this user as active right now (heartbeat). */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
        this.isActive = true;
    }

    // ── Getters ──

    public String getUserId()                { return userId; }
    public String getUserName()              { return userName; }
    public String getDocId()                 { return docId; }
    public boolean isActive()                { return isActive; }
    public LocalDateTime getLastActiveAt()   { return lastActiveAt; }
    public CursorPosition getCursorPosition(){ return cursorPosition; }
    public String getCursorColor()           { return cursorColor; }

    // ── Setters ──

    public void setActive(boolean active)                { this.isActive = active; }
    public void setLastActiveAt(LocalDateTime t)         { this.lastActiveAt = t; }
    public void setCursorPosition(CursorPosition cp)     { this.cursorPosition = cp; }

    @Override
    public String toString() {
        return "Presence{user='" + userName + "', doc='" + docId +
               "', active=" + isActive + ", cursor=" + cursorPosition + "}";
    }
}
