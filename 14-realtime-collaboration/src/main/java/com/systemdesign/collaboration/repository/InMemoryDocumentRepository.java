package com.systemdesign.collaboration.repository;

import com.systemdesign.collaboration.model.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of DocumentRepository.
 * Uses a ConcurrentHashMap for thread-safe access.
 */
public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<String, Document> store = new ConcurrentHashMap<>();

    @Override
    public void save(Document document) {
        store.put(document.getDocId(), document);
    }

    @Override
    public Optional<Document> findById(String docId) {
        return Optional.ofNullable(store.get(docId));
    }

    @Override
    public void deleteById(String docId) {
        store.remove(docId);
    }

    @Override
    public List<Document> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<Document> findByOwnerId(String ownerId) {
        return store.values().stream()
                .filter(doc -> doc.getOwnerId().equals(ownerId))
                .toList();
    }

    @Override
    public boolean existsById(String docId) {
        return store.containsKey(docId);
    }
}
