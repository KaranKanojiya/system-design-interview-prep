package com.systemdesign.collaboration.exception;

/**
 * Thrown when a user attempts an action they don't have permission for
 * (e.g., a VIEWER trying to edit).
 */
public class PermissionDeniedException extends CollaborationException {

    public PermissionDeniedException(String userId, String docId, String action) {
        super("Permission denied: user '" + userId + "' cannot " + action +
              " on document '" + docId + "'");
    }
}
