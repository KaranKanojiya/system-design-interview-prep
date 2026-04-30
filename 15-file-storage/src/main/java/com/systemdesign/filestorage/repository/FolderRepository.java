package com.systemdesign.filestorage.repository;

import com.systemdesign.filestorage.model.Folder;

import java.util.List;
import java.util.Optional;

/**
 * FolderRepository — data access interface for Folder entities.
 *
 * Call chain:
 *   MetadataService → folderRepository.save() / findById()
 *   MetadataService.listFolder → folderRepository.findById(folderId) → resolve children
 */
public interface FolderRepository {

    void save(Folder folder);

    Optional<Folder> findById(String folderId);

    List<Folder> findByOwnerId(String ownerId);

    List<Folder> findByParentId(String parentId);

    void delete(String folderId);

    List<Folder> findAll();
}
