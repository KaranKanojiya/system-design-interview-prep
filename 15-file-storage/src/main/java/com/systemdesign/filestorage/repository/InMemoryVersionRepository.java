package com.systemdesign.filestorage.repository;

import com.systemdesign.filestorage.model.FileVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryVersionRepository — ConcurrentHashMap-backed version store.
 */
public class InMemoryVersionRepository implements VersionRepository {

    private final Map<String, FileVersion> store = new ConcurrentHashMap<>();

    @Override
    public void save(FileVersion version) {
        store.put(version.getVersionId(), version);
    }

    @Override
    public Optional<FileVersion> findById(String versionId) {
        return Optional.ofNullable(store.get(versionId));
    }

    @Override
    public List<FileVersion> findByFileId(String fileId) {
        return store.values().stream()
                .filter(v -> fileId.equals(v.getFileId()))
                .sorted(Comparator.comparingInt(FileVersion::getVersionNumber))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<FileVersion> findByFileIdAndVersion(String fileId, int versionNumber) {
        return store.values().stream()
                .filter(v -> fileId.equals(v.getFileId()) && v.getVersionNumber() == versionNumber)
                .findFirst();
    }

    @Override
    public Optional<FileVersion> findLatestVersion(String fileId) {
        return store.values().stream()
                .filter(v -> fileId.equals(v.getFileId()))
                .max(Comparator.comparingInt(FileVersion::getVersionNumber));
    }

    @Override
    public void delete(String versionId) {
        store.remove(versionId);
    }

    @Override
    public List<FileVersion> findAll() {
        return new ArrayList<>(store.values());
    }
}
