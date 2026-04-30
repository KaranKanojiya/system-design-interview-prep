package com.systemdesign.collaboration.repository;

import com.systemdesign.collaboration.model.DocumentVersion;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for DocumentVersion (snapshot) storage.
 */
public interface VersionRepository {

    void save(DocumentVersion version);

    List<DocumentVersion> findByDocId(String docId);

    Optional<DocumentVersion> findByDocIdAndVersion(String docId, int versionNumber);

    List<DocumentVersion> findByDocIdInRange(String docId, int fromVersion, int toVersion);
}
