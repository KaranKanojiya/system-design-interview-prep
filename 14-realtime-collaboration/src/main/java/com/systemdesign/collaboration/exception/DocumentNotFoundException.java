package com.systemdesign.collaboration.exception;

/**
 * Thrown when a requested document does not exist.
 */
public class DocumentNotFoundException extends CollaborationException {

    public DocumentNotFoundException(String docId) {
        super("Document not found: " + docId);
    }
}
