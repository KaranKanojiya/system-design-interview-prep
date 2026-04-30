package com.systemdesign.filestorage.repository;

import com.systemdesign.filestorage.model.Folder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryFolderRepository — ConcurrentHashMap-backed folder store.
 */
public class InMemoryFolderRepository implements FolderRepository {

    private final Map<String, Folder> store = new ConcurrentHashMap<>();

    @Override
    public void save(Folder folder) {
        store.put(folder.getFolderId(), folder);
    }

    @Override
    public Optional<Folder> findById(String folderId) {
        return Optional.ofNullable(store.get(folderId));
    }

    @Override
    public List<Folder> findByOwnerId(String ownerId) {
        return store.values().stream()
                .filter(f -> ownerId.equals(f.getOwnerId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Folder> findByParentId(String parentId) {
        return store.values().stream()
                .filter(f -> parentId.equals(f.getParentId()))
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String folderId) {
        store.remove(folderId);
    }

    @Override
    public List<Folder> findAll() {
        return new ArrayList<>(store.values());
    }
}
