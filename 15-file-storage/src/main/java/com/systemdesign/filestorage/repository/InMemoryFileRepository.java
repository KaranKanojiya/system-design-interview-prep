package com.systemdesign.filestorage.repository;

import com.systemdesign.filestorage.model.FileMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryFileRepository — ConcurrentHashMap-backed file metadata store.
 *
 * In a real system: this would be a database (e.g., MySQL for metadata, with
 * the actual file content in object storage like S3). We use ConcurrentHashMap
 * for thread safety without external dependencies.
 */
public class InMemoryFileRepository implements FileRepository {

    private final Map<String, FileMetadata> store = new ConcurrentHashMap<>();

    @Override
    public void save(FileMetadata file) {
        store.put(file.getFileId(), file);
    }

    @Override
    public Optional<FileMetadata> findById(String fileId) {
        return Optional.ofNullable(store.get(fileId));
    }

    @Override
    public List<FileMetadata> findByOwnerId(String ownerId) {
        return store.values().stream()
                .filter(f -> ownerId.equals(f.getOwnerId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<FileMetadata> findByParentFolderId(String folderId) {
        return store.values().stream()
                .filter(f -> folderId.equals(f.getParentFolderId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<FileMetadata> searchByName(String ownerId, String query) {
        String lowerQuery = query.toLowerCase();
        return store.values().stream()
                .filter(f -> ownerId.equals(f.getOwnerId()))
                .filter(f -> f.getFileName().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String fileId) {
        store.remove(fileId);
    }

    @Override
    public List<FileMetadata> findAll() {
        return new ArrayList<>(store.values());
    }
}
