package com.systemdesign.collaboration.service;

import com.systemdesign.collaboration.model.CursorPosition;
import com.systemdesign.collaboration.model.Operation;
import com.systemdesign.collaboration.model.OperationType;
import com.systemdesign.collaboration.model.UserPresence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which users are active in each document and where their cursors are.
 *
 * In Google Docs, you see colored cursors and name tags for other editors.
 * This service maintains that state.
 *
 * Key operations:
 *   - joinDocument:   user opens the document
 *   - leaveDocument:  user closes the document
 *   - updateCursor:   user moves their cursor or changes selection
 *   - adjustCursors:  after a remote operation, shift all other users' cursors
 *   - cleanupStale:   remove users who've been inactive for too long
 *
 * Data structure: Map<docId, Map<userId, UserPresence>>
 *   Nested map for O(1) lookup by doc + user.
 */
public class PresenceService {

    /** Predefined cursor colors for users. */
    private static final String[] CURSOR_COLORS = {
            "#FF5733", "#33FF57", "#3357FF", "#FF33F5",
            "#33FFF5", "#F5FF33", "#FF8C33", "#8C33FF"
    };

    /** docId → (userId → UserPresence) */
    private final Map<String, Map<String, UserPresence>> presenceMap = new ConcurrentHashMap<>();

    /** Counter for assigning cursor colors. */
    private int colorIndex = 0;

    /**
     * User joins a document — add their presence entry.
     */
    public UserPresence joinDocument(String userId, String userName, String docId) {
        String color = CURSOR_COLORS[colorIndex % CURSOR_COLORS.length];
        colorIndex++;

        UserPresence presence = new UserPresence(userId, userName, docId, color);
        presenceMap.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
                   .put(userId, presence);

        return presence;
    }

    /**
     * User leaves a document — remove their presence entry.
     */
    public void leaveDocument(String userId, String docId) {
        Map<String, UserPresence> docPresence = presenceMap.get(docId);
        if (docPresence != null) {
            UserPresence presence = docPresence.get(userId);
            if (presence != null) {
                presence.setActive(false);
            }
            docPresence.remove(userId);
        }
    }

    /**
     * Update a user's cursor position.
     */
    public void updateCursor(String userId, String docId, int position) {
        Map<String, UserPresence> docPresence = presenceMap.get(docId);
        if (docPresence != null) {
            UserPresence presence = docPresence.get(userId);
            if (presence != null) {
                CursorPosition cursor = new CursorPosition(
                        userId, presence.getUserName(), docId, position, presence.getCursorColor());
                presence.setCursorPosition(cursor);
                presence.touch();
            }
        }
    }

    /**
     * Get all active users in a document.
     */
    public List<UserPresence> getActiveUsers(String docId) {
        Map<String, UserPresence> docPresence = presenceMap.get(docId);
        if (docPresence == null) return List.of();
        return docPresence.values().stream()
                .filter(UserPresence::isActive)
                .toList();
    }

    /**
     * After an operation is applied, adjust all OTHER users' cursor positions.
     *
     * If user A inserts text at position 5, all cursors at position >= 5
     * from other users must shift right by the insert length.
     *
     * Similarly, if user A deletes at position 5 with length 3, cursors
     * at position >= 8 shift left by 3; cursors inside [5,8) move to 5.
     */
    public void adjustCursors(String docId, Operation op, String excludeUserId) {
        Map<String, UserPresence> docPresence = presenceMap.get(docId);
        if (docPresence == null) return;

        for (UserPresence presence : docPresence.values()) {
            if (presence.getUserId().equals(excludeUserId)) continue;

            CursorPosition cursor = presence.getCursorPosition();
            if (cursor == null) continue;

            int cursorPos = cursor.getPosition();

            if (op.getType() == OperationType.INSERT) {
                // Shift cursor right if it's at or after the insert position
                if (cursorPos >= op.getPosition()) {
                    cursor.setPosition(cursorPos + op.getInsertLength());
                }
            } else if (op.getType() == OperationType.DELETE) {
                int deleteEnd = op.getPosition() + op.getLength();
                if (cursorPos >= deleteEnd) {
                    // Cursor is after the deleted range — shift left
                    cursor.setPosition(cursorPos - op.getLength());
                } else if (cursorPos > op.getPosition()) {
                    // Cursor is inside the deleted range — move to delete start
                    cursor.setPosition(op.getPosition());
                }
            }
        }
    }

    /**
     * Remove users who haven't been active for longer than the timeout.
     *
     * @param timeoutSeconds seconds of inactivity before marking stale
     * @return number of users cleaned up
     */
    public int cleanupStalePresence(long timeoutSeconds) {
        int cleaned = 0;
        for (Map<String, UserPresence> docPresence : presenceMap.values()) {
            List<String> staleUsers = new ArrayList<>();
            for (Map.Entry<String, UserPresence> entry : docPresence.entrySet()) {
                if (entry.getValue().isStale(timeoutSeconds)) {
                    staleUsers.add(entry.getKey());
                }
            }
            for (String userId : staleUsers) {
                docPresence.remove(userId);
                cleaned++;
            }
        }
        return cleaned;
    }

    /** Get the presence record for a specific user in a document. */
    public UserPresence getPresence(String userId, String docId) {
        Map<String, UserPresence> docPresence = presenceMap.get(docId);
        if (docPresence == null) return null;
        return docPresence.get(userId);
    }
}
