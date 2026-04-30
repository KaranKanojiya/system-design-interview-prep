package com.systemdesign.filestorage.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Folder — represents a directory in the virtual file system.
 *
 * Design decisions:
 * - parentId is null for root folders (each user has one root folder).
 * - path is the full logical path, e.g. "/docs/projects/" — computed from parent chain.
 * - childFileIds and childFolderIds: we store IDs, not objects, to avoid circular references
 *   and keep the model lightweight. The service layer resolves IDs to objects when needed.
 *
 * Call chain:
 *   MetadataService.listFolder(folderId) → FolderRepository.findById() → returns Folder
 *     → then resolves childFileIds via FileRepository, childFolderIds via FolderRepository
 */
public class Folder {

    private final String folderId;
    private String name;
    private String parentId;         // null for root
    private final String ownerId;
    private String path;             // full logical path, e.g. "/docs/projects/"
    private final List<String> childFileIds;
    private final List<String> childFolderIds;
    private final Instant createdAt;

    public Folder(String folderId, String name, String parentId, String ownerId, String path) {
        this.folderId = folderId;
        this.name = name;
        this.parentId = parentId;
        this.ownerId = ownerId;
        this.path = path;
        this.childFileIds = new ArrayList<>();
        this.childFolderIds = new ArrayList<>();
        this.createdAt = Instant.now();
    }

    // ── Mutation methods ─────────────────────────────────────────────

    public void addFile(String fileId) {
        if (!childFileIds.contains(fileId)) {
            childFileIds.add(fileId);
        }
    }

    public void addSubfolder(String folderId) {
        if (!childFolderIds.contains(folderId)) {
            childFolderIds.add(folderId);
        }
    }

    public void removeFile(String fileId) {
        childFileIds.remove(fileId);
    }

    public void removeSubfolder(String folderId) {
        childFolderIds.remove(folderId);
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getFolderId() { return folderId; }
    public String getName() { return name; }
    public String getParentId() { return parentId; }
    public String getOwnerId() { return ownerId; }
    public String getPath() { return path; }
    public List<String> getChildFileIds() { return Collections.unmodifiableList(childFileIds); }
    public List<String> getChildFolderIds() { return Collections.unmodifiableList(childFolderIds); }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public void setPath(String path) { this.path = path; }

    @Override
    public String toString() {
        return String.format("Folder{id='%s', name='%s', path='%s', files=%d, subfolders=%d}",
                folderId, name, path, childFileIds.size(), childFolderIds.size());
    }
}
