package com.systemdesign.collaboration.service;

import com.systemdesign.collaboration.exception.DocumentNotFoundException;
import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.PermissionRole;
import com.systemdesign.collaboration.repository.DocumentRepository;

import java.util.List;
import java.util.UUID;

/**
 * Service for document CRUD operations.
 *
 * This service does NOT handle collaborative editing (that's CollaborationService).
 * It handles creation, retrieval, and deletion of documents.
 *
 * Call chain:
 *   Controller.handleCreateDocument()
 *     → DocumentService.createDocument(title, ownerId)
 *       → builds Document via Builder
 *       → saves to DocumentRepository
 *       → grants OWNER permission via PermissionService
 */
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final PermissionService permissionService;

    public DocumentService(DocumentRepository documentRepository,
                           PermissionService permissionService) {
        this.documentRepository = documentRepository;
        this.permissionService = permissionService;
    }

    /**
     * Create a new document and grant OWNER permission to the creator.
     *
     * @return the created Document
     */
    public Document createDocument(String title, String ownerId) {
        String docId = "doc-" + UUID.randomUUID().toString().substring(0, 8);

        Document doc = new Document.Builder()
                .docId(docId)
                .title(title)
                .ownerId(ownerId)
                .build();

        documentRepository.save(doc);

        // Auto-grant OWNER permission to the creator
        permissionService.grantPermission(docId, ownerId, PermissionRole.OWNER, ownerId);

        return doc;
    }

    /**
     * Retrieve a document by ID.
     *
     * @throws DocumentNotFoundException if not found
     */
    public Document getDocument(String docId) {
        return documentRepository.findById(docId)
                .orElseThrow(() -> new DocumentNotFoundException(docId));
    }

    /** Delete a document by ID. */
    public void deleteDocument(String docId) {
        if (!documentRepository.existsById(docId)) {
            throw new DocumentNotFoundException(docId);
        }
        documentRepository.deleteById(docId);
    }

    /** Get the current content of a document. */
    public String getDocumentContent(String docId) {
        return getDocument(docId).getContent();
    }

    /** List all documents. */
    public List<Document> getAllDocuments() {
        return documentRepository.findAll();
    }
}
