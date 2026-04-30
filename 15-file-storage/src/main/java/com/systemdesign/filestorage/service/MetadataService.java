package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.exception.FileNotFoundException;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.Folder;
import com.systemdesign.filestorage.repository.FileRepository;
import com.systemdesign.filestorage.repository.FolderRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MetadataService — manages file and folder metadata (CRUD, search, hierarchy).
 *
 * This is the metadata plane. It knows about file names, paths, ownership, and
 * folder hierarchy, but NOT about actual file content, chunks, or blocks.
 *
 * Separation of concerns:
 * - MetadataService: file/folder CRUD, search, path resolution
 * - DeduplicationService: chunk storage and dedup logic
 * - VersionService: version history
 * - UploadService: orchestrates all of the above
 *
 * Call chain:
 *   UploadService.uploadFile → this.createFile(metadata) → fileRepository.save()
 *   DownloadService.downloadFile → this.getFile(fileId) → fileRepository.findById()
 *   Controller.handleSearch → this.searchFiles(userId, query)
 */
public class MetadataService {

    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;

    public MetadataService(FileRepository fileRepository, FolderRepository folderRepository) {
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
    }

    // ── File Operations ──────────────────────────────────────────────

    public void createFile(FileMetadata metadata) {
        fileRepository.save(metadata);

        // If the file has a parent folder, add it to the folder's child list
        if (metadata.getParentFolderId() != null) {
            folderRepository.findById(metadata.getParentFolderId())
                    .ifPresent(folder -> folder.addFile(metadata.getFileId()));
        }
    }

    public FileMetadata getFile(String fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));
    }

    public Optional<FileMetadata> findFile(String fileId) {
        return fileRepository.findById(fileId);
    }

    public List<FileMetadata> searchFiles(String ownerId, String query) {
        return fileRepository.searchByName(ownerId, query);
    }

    public void moveFile(String fileId, String newParentFolderId) {
        FileMetadata file = getFile(fileId);

        // Remove from old parent folder's child list
        if (file.getParentFolderId() != null) {
            folderRepository.findById(file.getParentFolderId())
                    .ifPresent(folder -> folder.removeFile(fileId));
        }

        // Add to new parent folder's child list
        folderRepository.findById(newParentFolderId)
                .ifPresent(folder -> {
                    folder.addFile(fileId);
                    // Update the file's path based on new parent
                    file.setFilePath(folder.getPath() + file.getFileName());
                });

        file.setParentFolderId(newParentFolderId);
        fileRepository.save(file);
    }

    public void renameFile(String fileId, String newName) {
        FileMetadata file = getFile(fileId);
        String oldName = file.getFileName();
        file.setFileName(newName);

        // Update path: replace the filename portion
        if (file.getFilePath() != null) {
            String parentPath = file.getFilePath().substring(0,
                    file.getFilePath().length() - oldName.length());
            file.setFilePath(parentPath + newName);
        }

        fileRepository.save(file);
    }

    public String getFilePath(String fileId) {
        FileMetadata file = getFile(fileId);
        return file.getFilePath();
    }

    public List<FileMetadata> getFilesByOwner(String ownerId) {
        return fileRepository.findByOwnerId(ownerId);
    }

    public List<FileMetadata> getAllFiles() {
        return fileRepository.findAll();
    }

    // ── Folder Operations ────────────────────────────────────────────

    public Folder createFolder(Folder folder) {
        folderRepository.save(folder);

        // If it has a parent, add to parent's subfolder list
        if (folder.getParentId() != null) {
            folderRepository.findById(folder.getParentId())
                    .ifPresent(parent -> parent.addSubfolder(folder.getFolderId()));
        }

        return folder;
    }

    public Folder getFolder(String folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new FileNotFoundException("Folder not found: " + folderId));
    }

    /**
     * List contents of a folder: returns files and subfolders.
     * Returns a combined list description for display purposes.
     */
    public List<String> listFolder(String folderId) {
        Folder folder = getFolder(folderId);
        List<String> contents = new ArrayList<>();

        // List subfolders first (convention: directories before files)
        for (String subFolderId : folder.getChildFolderIds()) {
            folderRepository.findById(subFolderId).ifPresent(sub ->
                    contents.add("[DIR]  " + sub.getName() + "/"));
        }

        // Then list files
        for (String childFileId : folder.getChildFileIds()) {
            fileRepository.findById(childFileId).ifPresent(file ->
                    contents.add("[FILE] " + file.getFileName() + " (" + file.getSizeBytes() + " bytes)"));
        }

        return contents;
    }

    public List<Folder> getFoldersByOwner(String ownerId) {
        return folderRepository.findByOwnerId(ownerId);
    }

    public List<Folder> getSubfolders(String parentFolderId) {
        return folderRepository.findByParentId(parentFolderId);
    }

    public List<FileMetadata> getFilesInFolder(String folderId) {
        return fileRepository.findByParentFolderId(folderId);
    }
}
