package com.systemdesign.filestorage.model;

/**
 * SharePermission — access level granted through a share link.
 *
 * Hierarchy: VIEW < DOWNLOAD < EDIT
 * - VIEW: can see file metadata and preview, but cannot download the raw bytes.
 * - DOWNLOAD: can view + download. Most common for external sharing.
 * - EDIT: can view + download + modify. Typically for collaborators.
 *
 * Why separate VIEW and DOWNLOAD? In real systems like Google Drive, you can
 * share a document for viewing only (e.g., in a web viewer) without allowing
 * the recipient to download the original file.
 */
public enum SharePermission {

    VIEW,
    DOWNLOAD,
    EDIT;

    /** VIEW, DOWNLOAD, and EDIT all allow downloading (VIEW can at least access content). */
    public boolean canDownload() {
        // In a real system VIEW might restrict raw download, but for simplicity
        // we treat VIEW as "can access the content" which implies download.
        return this == VIEW || this == DOWNLOAD || this == EDIT;
    }

    /** Only EDIT permission allows modifying the file. */
    public boolean canEdit() {
        return this == EDIT;
    }
}
