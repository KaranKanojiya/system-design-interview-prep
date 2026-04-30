package com.systemdesign.collaboration.repository;

import com.systemdesign.collaboration.model.Document;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for Document storage.
 *
 * In production this would talk to a database (e.g., DynamoDB, PostgreSQL).
 * The in-memory implementation is used here for demo purposes.
 */
public interface DocumentRepository {

    void save(Document document);

    Optional<Document> findById(String docId);

    void deleteById(String docId);

    List<Document> findAll();

    List<Document> findByOwnerId(String ownerId);

    boolean existsById(String docId);
}
