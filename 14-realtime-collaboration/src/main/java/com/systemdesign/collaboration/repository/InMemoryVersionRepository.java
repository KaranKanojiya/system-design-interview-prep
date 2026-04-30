package com.systemdesign.collaboration.repository;

import com.systemdesign.collaboration.model.DocumentVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of VersionRepository.
 * Stores document version snapshots per document.
 */
public class InMemoryVersionRepository implements VersionRepository {

    private final Map<String, List<DocumentVersion>> store = new ConcurrentHashMap<>();

    @Override
    public void save(DocumentVersion version) {
        store.computeIfAbsent(version.getDocId(), k -> new ArrayList<>())
             .add(version);
    }

    @Override
    public List<DocumentVersion> findByDocId(String docId) {
        return new ArrayList<>(store.getOrDefault(docId, List.of()));
    }

    @Override
    public Optional<DocumentVersion> findByDocIdAndVersion(String docId, int versionNumber) {
        return store.getOrDefault(docId, List.of()).stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst();
    }

    @Override
    public List<DocumentVersion> findByDocIdInRange(String docId, int fromVersion, int toVersion) {
        return store.getOrDefault(docId, List.of()).stream()
                .filter(v -> v.getVersionNumber() >= fromVersion && v.getVersionNumber() <= toVersion)
                .toList();
    }
}
