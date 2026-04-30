package com.systemdesign.filestorage.repository;

import com.systemdesign.filestorage.model.FileMetadata;

import java.util.List;
import java.util.Optional;

/**
 * FileRepository — data access interface for FileMetadata entities.
 *
 * Abstracts storage so the service layer doesn't care if data is in-memory,
 * in a database, or distributed across multiple nodes.
 *
 * Call chain:
 *   MetadataService → fileRepository.save() / findById() / findByOwnerId()
 *   UploadService → MetadataService → fileRepository.save()
 *   DownloadService → MetadataService → fileRepository.findById()
 */
public interface FileRepository {

    void save(FileMetadata file);

    Optional<FileMetadata> findById(String fileId);

    List<FileMetadata> findByOwnerId(String ownerId);

    List<FileMetadata> findByParentFolderId(String folderId);

    /** Search files by name substring (case-insensitive). */
    List<FileMetadata> searchByName(String ownerId, String query);

    void delete(String fileId);

    List<FileMetadata> findAll();
}
