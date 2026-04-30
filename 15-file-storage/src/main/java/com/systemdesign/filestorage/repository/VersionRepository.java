package com.systemdesign.filestorage.repository;

import com.systemdesign.filestorage.model.FileVersion;

import java.util.List;
import java.util.Optional;

/**
 * VersionRepository — data access interface for FileVersion entities.
 *
 * Call chain:
 *   VersionService → versionRepository.save() / findByFileId()
 *   VersionService.rollbackToVersion → finds old version, creates new version with old chunks
 */
public interface VersionRepository {

    void save(FileVersion version);

    Optional<FileVersion> findById(String versionId);

    /** All versions for a file, ordered by versionNumber ascending. */
    List<FileVersion> findByFileId(String fileId);

    /** Get a specific version of a file by version number. */
    Optional<FileVersion> findByFileIdAndVersion(String fileId, int versionNumber);

    /** Get the latest (highest versionNumber) version for a file. */
    Optional<FileVersion> findLatestVersion(String fileId);

    void delete(String versionId);

    List<FileVersion> findAll();
}
