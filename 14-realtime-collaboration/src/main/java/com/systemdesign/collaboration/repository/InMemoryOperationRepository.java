package com.systemdesign.collaboration.repository;

import com.systemdesign.collaboration.model.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of OperationRepository.
 * Stores operations in per-document lists (append-only log).
 */
public class InMemoryOperationRepository implements OperationRepository {

    /** docId → ordered list of operations (append-only) */
    private final Map<String, List<Operation>> store = new ConcurrentHashMap<>();

    @Override
    public void save(Operation operation) {
        store.computeIfAbsent(operation.getDocId(), k -> new ArrayList<>())
             .add(operation);
    }

    @Override
    public List<Operation> findByDocId(String docId) {
        return new ArrayList<>(store.getOrDefault(docId, List.of()));
    }

    @Override
    public List<Operation> findByDocIdSinceVersion(String docId, int fromVersion) {
        return store.getOrDefault(docId, List.of()).stream()
                .filter(op -> op.getBaseVersion() >= fromVersion)
                .toList();
    }

    @Override
    public int countByDocId(String docId) {
        return store.getOrDefault(docId, List.of()).size();
    }
}
