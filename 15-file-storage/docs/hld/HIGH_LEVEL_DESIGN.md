# High-Level Design: File Storage System (Google Drive / Dropbox / OneDrive)

> **Difficulty:** HARD | **Interview Time:** 40-50 minutes | **Focus:** Chunked upload, content-addressable deduplication, file sync, versioning, sharing permissions, metadata vs blob separation

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Data Model](#7-data-model)
8. [High-Level Architecture](#8-high-level-architecture)
9. [Component Deep Dive](#9-component-deep-dive)
10. [Chunked Upload Deep Dive](#10-chunked-upload-deep-dive)
11. [Deduplication Deep Dive](#11-deduplication-deep-dive)
12. [File Sync](#12-file-sync)
13. [Versioning](#13-versioning)
14. [Concurrency](#14-concurrency)
15. [Scaling](#15-scaling)
16. [Database Choice](#16-database-choice)
17. [CAP Theorem](#17-cap-theorem)
18. [Cloud Services](#18-cloud-services)
19. [Tradeoffs Summary](#19-tradeoffs-summary)
20. [Interview Talking Points](#20-interview-talking-points)

---

## 1. Problem Statement

Design a **File Storage System** (like Google Drive, Dropbox, or OneDrive) that allows users to upload, download, sync, share, and version files across multiple devices -- at a scale of 500 million registered users, 100 million daily active users, and 2.5 exabytes of total storage.

**Why is it needed?**

- Cloud file storage is one of the most fundamental infrastructure services on the internet. Dropbox alone stores over 500 billion pieces of content. Google Drive serves over 1 billion users. OneDrive powers Microsoft 365 for 345+ million paid seats.
- The engineering challenge is deceptively deep: a naive file upload/download API is trivial, but building a system that handles 10GB file uploads over flaky networks, deduplicates identical content across 500 million users, syncs file changes across multiple devices in near real-time, and maintains version history for every file -- that requires careful system design across storage, networking, consistency, and distributed systems.
- Dropbox's legendary engineering blog post "How We've Scaled Dropbox" revealed that **block-level deduplication** saved them over 75% of raw storage costs. Identical 4MB blocks across different users' files are stored only once. This one optimization is worth billions of dollars at scale.
- File sync is deceptively hard. When a user edits a file on their laptop, the change should appear on their phone within seconds. When two users edit the same file simultaneously on different devices, the system must detect the conflict and resolve it gracefully. Dropbox's sync engine has been rewritten three times over the company's history.

**Core Workflow -- File Upload:**

```
User uploads a 50 MB presentation file from their laptop.

(1)  Client computes SHA-256 hash of the entire file
(2)  Client splits file into 4 MB chunks (13 chunks, last chunk is 2 MB)
(3)  Client computes SHA-256 hash of each chunk
(4)  Client sends chunk hashes to Upload Service: POST /files/upload/chunked/init
(5)  Upload Service checks each hash against Block Storage index
     - 8 of 13 chunks already exist (from other users' files or previous versions)
     - Upload Service returns: "upload only chunks 3, 7, 9, 10, 12" (5 new chunks)
(6)  Client uploads only the 5 new chunks in parallel (4 concurrent uploads)
     - Each chunk uploaded with presigned URL directly to Object Storage
(7)  As each chunk arrives, Block Storage verifies SHA-256 checksum
(8)  If a chunk upload fails (network error), client retries just that chunk
(9)  Once all 5 new chunks are confirmed, Upload Service assembles file metadata
(10) Metadata Service creates file record pointing to all 13 chunk references
(11) Sync Service publishes "file_created" event to Kafka
(12) User's other devices receive sync notification within 5 seconds
(13) Storage used: 20 MB (5 new chunks) instead of 50 MB (38% of original)
```

**Core Workflow -- File Sync Across Devices:**

```
User edits a 20 MB spreadsheet on their laptop. Phone and tablet must sync.

(1)  Desktop client detects file change via filesystem watcher (inotify/FSEvents)
(2)  Client computes rolling hash (Rabin fingerprint) to find changed blocks
(3)  Only 2 of 5 chunks have changed (delta sync: 8 MB instead of 20 MB)
(4)  Client uploads the 2 changed chunks to Block Storage
(5)  Client notifies Metadata Service: new version with updated chunk list
(6)  Metadata Service creates new FileVersion record (old version preserved)
(7)  Sync Service detects the change and publishes event to Kafka
(8)  Phone and tablet have long-poll connections to Sync Service
(9)  Both devices receive change notification with sync cursor
(10) Phone requests: GET /sync/changes?cursor=<last_sync_cursor>
(11) Sync Service returns: "file X updated, chunks 2 and 4 changed"
(12) Phone downloads only the 2 changed chunks (8 MB, not 20 MB)
(13) Phone reconstructs file from: 3 cached chunks + 2 new chunks
(14) Total sync latency: ~8 seconds (detect + upload + notify + download)
```

**Core Workflow -- File Sharing:**

```
User shares a folder with a colleague, granting edit access.

(1)  Owner calls POST /files/{folderId}/share with recipientEmail and role=EDITOR
(2)  Sharing Service validates the owner has OWNER permission on the folder
(3)  Sharing Service creates SharePermission record for the recipient
(4)  Notification Service sends email: "Alice shared 'Q4 Reports' with you"
(5)  Recipient clicks link, Sharing Service validates their identity
(6)  Recipient's file tree now includes the shared folder
(7)  Recipient uploads a new file to the shared folder
(8)  Sharing Service checks: does recipient have EDITOR or OWNER role? Yes.
(9)  File is created under the shared folder with recipient as uploader
(10) Owner's devices sync and show the new file within 10 seconds
(11) Both users can now see each other's changes in near real-time
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Google, Dropbox, Microsoft, Meta, Amazon, and every major tech company because it tests a uniquely broad range of systems concepts in a single question:

| Skill Tested                         | What Interviewers Look For                                                                        |
|--------------------------------------|---------------------------------------------------------------------------------------------------|
| **Chunked Upload**                   | Can you design resumable, parallel uploads for large files? Presigned URLs? Checksum verification? |
| **Deduplication**                    | THE star: content-addressable storage, block-level dedup, rolling hash, storage savings analysis   |
| **File Sync**                        | Long polling vs WebSocket, delta sync, conflict resolution, sync cursor protocol                   |
| **Versioning**                       | Copy-on-write with shared chunks, version history, rollback, storage efficiency                    |
| **Sharing & Permissions**            | ACL model, share links, recursive folder permissions, permission inheritance                       |
| **Metadata vs Blob Separation**      | Why separate metadata (PostgreSQL) from file content (S3)? Different scaling patterns              |
| **Storage at Scale**                 | 2.5 EB total storage, object storage architecture, CDN for hot files                               |
| **Consistency vs Availability**      | File tree must be consistent (CP), sync notifications can be eventually consistent (AP)            |
| **Concurrency**                      | Concurrent uploads, sync conflicts, metadata race conditions on the same file                      |

> **Interview tip**: Start by stating the scale (500M users, 100M DAU, 2.5 EB storage, 100M uploads/day), then immediately explain the **core insight**: metadata and file content are separated because they have fundamentally different access patterns and scaling needs. Draw the chunked upload flow on the whiteboard -- then pivot to deduplication, which is the star of this interview. Spend 30-40% of your time on deduplication and chunked upload. Sync and versioning are important secondary topics.

---

## 2. Scope

### In Scope

| Feature                              | Description                                                                         |
|--------------------------------------|-------------------------------------------------------------------------------------|
| File Upload / Download               | Upload files of any size; download files with range support                          |
| Chunked Upload                       | Split large files into 4 MB chunks, upload in parallel, resume on failure            |
| File Sync                            | Detect local changes, push to server, notify other devices, delta sync               |
| File Versioning                      | Keep last N versions, diff between versions, rollback to any version                 |
| Sharing & Permissions                | Share files/folders with users, share links, role-based access (Owner/Editor/Viewer) |
| Folder Hierarchy                     | Nested folder structure, move/rename/delete folders recursively                       |
| Deduplication                        | Block-level content-addressable dedup across all users                                |
| Search                               | Search files by name, type, owner, modification date                                  |
| Trash & Recovery                     | Soft delete with 30-day recovery window, permanent purge after 30 days               |
| Storage Quota                        | Per-user storage limits, quota tracking, overage handling                              |

### Out of Scope

| Feature                              | Reason                                                                              |
|--------------------------------------|-------------------------------------------------------------------------------------|
| File preview / rendering             | Client-side concern (PDF viewer, image renderer, doc previewer)                      |
| Real-time collaborative editing      | Covered in Project 14 (Real-Time Collaboration)                                      |
| Notification system                  | Covered in Project 03 (Notification System)                                          |
| User authentication / SSO            | Assume authentication exists; focus on storage architecture                           |
| End-to-end encryption                | Mention conceptually; full design is a separate deep dive                             |
| Mobile offline mode                  | Same sync architecture; different client implementation                               |
| OCR / content indexing               | Separate ML pipeline; mention as extension only                                       |
| Compliance (GDPR, data residency)    | Mention geo-sharding conceptually; full compliance is out of scope                    |

---

## 3. Assumptions

### Back-of-Envelope Calculations

```
User Base:
  Total registered users:         500,000,000  (500M)
  Daily active users (DAU):       100,000,000  (100M)
  Average storage per user:       5 GB
  Total raw storage:              500M * 5 GB = 2,500,000,000 GB = 2.5 EB (exabytes)
  After deduplication (40% saved): 2.5 EB * 0.6 = 1.5 EB actual storage

Upload Traffic:
  File uploads per day:           100,000,000  (100M)
  Average file size:              1 MB
  Total upload volume per day:    100M * 1 MB = 100 TB/day
  Upload throughput:              100 TB / 86400 sec = ~1.16 GB/sec sustained
  Peak upload (3x average):       ~3.5 GB/sec

Download Traffic:
  File downloads per day:         300,000,000  (300M, 3x uploads -- reads dominate)
  Average download size:          2 MB (larger files downloaded more often)
  Total download volume per day:  600 TB/day
  Download throughput:            ~7 GB/sec sustained
  Peak download (3x average):     ~21 GB/sec

Chunk Math:
  Chunk size:                     4 MB
  Average file:                   1 MB -> 1 chunk
  Large file (1 GB):              256 chunks
  Very large file (10 GB):        2,560 chunks
  Total chunks in system:         ~500 billion (after dedup: ~300 billion unique)

Metadata:
  Files per user (average):       500
  Total file records:             500M * 500 = 250 billion
  Metadata per file:              ~500 bytes
  Total metadata storage:         250B * 500 B = 125 TB
  Folder records:                 ~50 billion (10% of file count)

Sync:
  Active sync connections:        100M DAU * 1.5 devices = 150M connections
  Sync events per second:         ~50,000 (file changes across all users)
  Notification fan-out:           average 1.5 devices per change = 75,000 notifications/sec

API Requests:
  Metadata API calls per day:     ~5 billion (list, search, move, rename, etc.)
  Metadata QPS:                   ~58,000 average, ~175,000 peak
```

---

## 4. Functional Requirements

```
+-----+----------------------------------+----------------------------------------------+
| FR  | Requirement                      | Details                                      |
+-----+----------------------------------+----------------------------------------------+
| FR1 | Upload file                      | Upload files of any size (up to 50 GB).      |
|     |                                  | Small files (<4 MB) via single PUT.          |
|     |                                  | Large files via chunked upload.              |
+-----+----------------------------------+----------------------------------------------+
| FR2 | Download file                    | Download entire file or byte range.          |
|     |                                  | Support partial downloads (resume).          |
|     |                                  | Serve from CDN for popular files.            |
+-----+----------------------------------+----------------------------------------------+
| FR3 | Chunked upload for large files   | Split into 4 MB chunks. Upload in parallel.  |
|     |                                  | Resumable: track uploaded chunks, retry only |
|     |                                  | failed ones. Checksum per chunk.             |
+-----+----------------------------------+----------------------------------------------+
| FR4 | File sync across devices         | Detect local file changes. Push to server.   |
|     |                                  | Notify other devices. Delta sync (only       |
|     |                                  | changed chunks). Conflict resolution.        |
+-----+----------------------------------+----------------------------------------------+
| FR5 | File versioning                  | Keep last N versions (default 30).           |
|     |                                  | View version history. Diff between versions. |
|     |                                  | Rollback to any previous version.            |
+-----+----------------------------------+----------------------------------------------+
| FR6 | Sharing with permissions         | Share files/folders with specific users.     |
|     |                                  | Share links (view-only or editable).         |
|     |                                  | Roles: Owner, Editor, Viewer.                |
|     |                                  | Folder permissions inherit to children.      |
+-----+----------------------------------+----------------------------------------------+
| FR7 | Folder hierarchy                 | Create/rename/move/delete folders.           |
|     |                                  | Nested folders up to 20 levels deep.         |
|     |                                  | List folder contents with pagination.        |
+-----+----------------------------------+----------------------------------------------+
| FR8 | Search                           | Search by file name, type, owner, date.      |
|     |                                  | Typeahead suggestions. Filter by folder.     |
+-----+----------------------------------+----------------------------------------------+
| FR9 | Trash and recovery               | Soft delete moves to trash. Files remain     |
|     |                                  | recoverable for 30 days. After 30 days,     |
|     |                                  | permanent purge (chunks garbage collected).  |
+-----+----------------------------------+----------------------------------------------+
| FR10| Storage quota                    | Per-user storage limit (free: 15 GB,         |
|     |                                  | paid: 2 TB). Track usage. Block uploads      |
|     |                                  | when quota exceeded.                         |
+-----+----------------------------------+----------------------------------------------+
```

---

## 5. Non-Functional Requirements

```
+------+---------------------------+-------------------+-------------------------------+
| NFR  | Requirement               | Target            | Rationale                     |
+------+---------------------------+-------------------+-------------------------------+
| NFR1 | Upload latency            | < 5 seconds for   | Users expect fast uploads.    |
|      |                           | 10 MB file        | Chunked + parallel makes this |
|      |                           |                   | achievable.                   |
+------+---------------------------+-------------------+-------------------------------+
| NFR2 | Download latency          | < 2 seconds for   | First byte from CDN edge.     |
|      |                           | first byte        | Full download depends on      |
|      |                           |                   | file size and bandwidth.      |
+------+---------------------------+-------------------+-------------------------------+
| NFR3 | Sync latency              | < 10 seconds      | File change on one device     |
|      |                           |                   | visible on others within 10s. |
+------+---------------------------+-------------------+-------------------------------+
| NFR4 | Durability                | 99.99% (4 nines)  | Files must never be lost.     |
|      |                           |                   | Replication + erasure coding. |
|      |                           |                   | (S3 offers 99.999999999%)     |
+------+---------------------------+-------------------+-------------------------------+
| NFR5 | Availability              | 99.9% (3 nines)   | ~8.7 hours downtime/year.     |
|      |                           |                   | Uploads/downloads must work   |
|      |                           |                   | even during partial outages.  |
+------+---------------------------+-------------------+-------------------------------+
| NFR6 | Throughput                | 100M uploads/day  | ~1,150 uploads/sec average.   |
|      |                           | 300M downloads/day| ~3,470 downloads/sec average. |
+------+---------------------------+-------------------+-------------------------------+
| NFR7 | Storage efficiency        | 30-50% savings    | Deduplication at block level  |
|      |                           | via deduplication | across all users.             |
+------+---------------------------+-------------------+-------------------------------+
| NFR8 | Consistency               | Strong for        | File tree must be consistent. |
|      |                           | metadata;         | Sync notifications can be     |
|      |                           | eventual for sync | eventually consistent.        |
+------+---------------------------+-------------------+-------------------------------+
```

---

## 6. API Design

### 6.1 File Upload (Small File -- Single PUT)

```
POST /api/v1/files/upload
Headers:
  Authorization: Bearer <token>
  Content-Type: multipart/form-data

Body:
  file:        <binary data>         (max 4 MB for single upload)
  parentId:    "folder_abc123"       (parent folder ID, "root" for root)
  name:        "report.pdf"          (file name)

Response: 201 Created
{
  "fileId":      "file_7x9k2m",
  "name":        "report.pdf",
  "size":        2048576,
  "mimeType":    "application/pdf",
  "parentId":    "folder_abc123",
  "version":     1,
  "checksum":    "sha256:a1b2c3d4...",
  "createdAt":   "2026-04-26T10:30:00Z",
  "downloadUrl": "https://cdn.example.com/files/file_7x9k2m"
}
```

### 6.2 Chunked Upload -- Initialize

```
POST /api/v1/files/upload/chunked/init
Headers:
  Authorization: Bearer <token>
  Content-Type: application/json

Body:
{
  "fileName":     "presentation.pptx",
  "fileSize":     52428800,                // 50 MB
  "parentId":     "folder_abc123",
  "totalChunks":  13,
  "chunkSize":    4194304,                 // 4 MB
  "fileChecksum": "sha256:e5f6g7h8...",
  "chunkChecksums": [
    "sha256:chunk0_hash...",
    "sha256:chunk1_hash...",
    "sha256:chunk2_hash...",
    ...
    "sha256:chunk12_hash..."
  ]
}

Response: 200 OK
{
  "uploadId":        "upload_m3n4o5",
  "existingChunks":  [0, 1, 2, 4, 5, 6, 8, 11],     // already deduplicated
  "missingChunks":   [3, 7, 9, 10, 12],               // need to upload these
  "presignedUrls": {
    "3": "https://storage.example.com/upload/chunk_3?sig=...",
    "7": "https://storage.example.com/upload/chunk_7?sig=...",
    "9": "https://storage.example.com/upload/chunk_9?sig=...",
    "10": "https://storage.example.com/upload/chunk_10?sig=...",
    "12": "https://storage.example.com/upload/chunk_12?sig=..."
  },
  "expiresAt": "2026-04-26T11:30:00Z"
}
```

### 6.3 Chunked Upload -- Upload Individual Chunk

```
PUT /api/v1/files/upload/chunked/{uploadId}/chunk/{index}
Headers:
  Authorization: Bearer <token>
  Content-Type: application/octet-stream
  Content-Length: 4194304
  X-Chunk-Checksum: sha256:chunk3_hash...

Body: <raw binary chunk data>

Response: 200 OK
{
  "uploadId":     "upload_m3n4o5",
  "chunkIndex":   3,
  "status":       "UPLOADED",
  "verified":     true
}
```

### 6.4 Chunked Upload -- Complete

```
POST /api/v1/files/upload/chunked/{uploadId}/complete
Headers:
  Authorization: Bearer <token>

Response: 201 Created
{
  "fileId":      "file_p6q7r8",
  "name":        "presentation.pptx",
  "size":        52428800,
  "version":     1,
  "checksum":    "sha256:e5f6g7h8...",
  "chunksUploaded": 5,
  "chunksDeduplicated": 8,
  "storageSaved": "62%",
  "createdAt":   "2026-04-26T10:35:00Z"
}
```

### 6.5 File Download

```
GET /api/v1/files/{fileId}/download
Headers:
  Authorization: Bearer <token>
  Range: bytes=0-4194303              (optional: partial download)

Response: 200 OK (or 206 Partial Content)
Headers:
  Content-Type: application/vnd.openxmlformats-officedocument.presentationml.presentation
  Content-Length: 52428800
  Content-Disposition: attachment; filename="presentation.pptx"
  Accept-Ranges: bytes
  ETag: "sha256:e5f6g7h8..."

Body: <binary file data>
```

### 6.6 File Version History

```
GET /api/v1/files/{fileId}/versions
Headers:
  Authorization: Bearer <token>

Query Parameters:
  limit:  20            (default 20, max 100)
  offset: 0

Response: 200 OK
{
  "fileId": "file_p6q7r8",
  "versions": [
    {
      "versionId":   "ver_001",
      "versionNum":  3,
      "size":        52428800,
      "checksum":    "sha256:e5f6g7h8...",
      "modifiedBy":  "user_alice",
      "modifiedAt":  "2026-04-26T10:35:00Z",
      "changeNote":  "Updated Q4 numbers"
    },
    {
      "versionId":   "ver_002",
      "versionNum":  2,
      "size":        51380224,
      "checksum":    "sha256:x9y0z1a2...",
      "modifiedBy":  "user_alice",
      "modifiedAt":  "2026-04-25T14:20:00Z",
      "changeNote":  null
    }
  ],
  "total": 3,
  "hasMore": false
}
```

### 6.7 Share File

```
POST /api/v1/files/{fileId}/share
Headers:
  Authorization: Bearer <token>
  Content-Type: application/json

Body:
{
  "recipientEmail": "bob@company.com",
  "role":           "EDITOR",            // VIEWER | EDITOR | OWNER
  "message":        "Please review Q4 numbers",
  "notifyByEmail":  true
}

Response: 201 Created
{
  "shareId":        "share_s1t2u3",
  "fileId":         "file_p6q7r8",
  "recipientEmail": "bob@company.com",
  "role":           "EDITOR",
  "sharedBy":       "user_alice",
  "sharedAt":       "2026-04-26T11:00:00Z"
}
```

### 6.8 Create Share Link

```
POST /api/v1/files/{fileId}/share-link
Headers:
  Authorization: Bearer <token>
  Content-Type: application/json

Body:
{
  "accessLevel":  "VIEWER",            // VIEWER | EDITOR
  "expiresIn":    "7d",                // 1h, 1d, 7d, 30d, never
  "password":     "optional_password"
}

Response: 201 Created
{
  "linkId":     "link_v4w5x6",
  "url":        "https://drive.example.com/s/v4w5x6",
  "accessLevel": "VIEWER",
  "expiresAt":  "2026-05-03T11:00:00Z",
  "password":   true
}
```

### 6.9 Sync Changes

```
GET /api/v1/sync/changes
Headers:
  Authorization: Bearer <token>

Query Parameters:
  cursor:  "sync_cursor_abc123"        (opaque token from last sync)
  limit:   100                         (max changes per request)

Response: 200 OK
{
  "changes": [
    {
      "type":      "FILE_MODIFIED",
      "fileId":    "file_p6q7r8",
      "name":      "presentation.pptx",
      "parentId":  "folder_abc123",
      "version":   3,
      "size":      52428800,
      "checksum":  "sha256:e5f6g7h8...",
      "modifiedAt": "2026-04-26T10:35:00Z",
      "changedChunks": [2, 4]          // only these chunks changed
    },
    {
      "type":      "FILE_CREATED",
      "fileId":    "file_y7z8a9",
      "name":      "budget.xlsx",
      "parentId":  "folder_abc123",
      "version":   1,
      "size":      1048576,
      "createdAt": "2026-04-26T10:40:00Z"
    },
    {
      "type":      "FILE_DELETED",
      "fileId":    "file_b0c1d2",
      "deletedAt": "2026-04-26T10:45:00Z"
    }
  ],
  "cursor":   "sync_cursor_def456",     // use this for next sync
  "hasMore":  false
}
```

### 6.10 Folder Operations

```
POST /api/v1/folders
Headers:
  Authorization: Bearer <token>
  Content-Type: application/json

Body:
{
  "name":      "Q4 Reports",
  "parentId":  "folder_abc123"
}

Response: 201 Created
{
  "folderId":   "folder_e3f4g5",
  "name":       "Q4 Reports",
  "parentId":   "folder_abc123",
  "path":       "/My Drive/Projects/Q4 Reports",
  "createdAt":  "2026-04-26T12:00:00Z"
}

---

GET /api/v1/folders/{folderId}/contents
Query Parameters:
  sortBy:     "name" | "modifiedAt" | "size"
  order:      "asc" | "desc"
  limit:      50
  pageToken:  "token_abc"

Response: 200 OK
{
  "folderId": "folder_abc123",
  "items": [
    { "type": "FOLDER", "id": "folder_e3f4g5", "name": "Q4 Reports", ... },
    { "type": "FILE",   "id": "file_p6q7r8",   "name": "presentation.pptx", ... }
  ],
  "nextPageToken": "token_def"
}
```

---

## 7. Data Model

### 7.1 Entity Relationship

```
+--------------------+          +--------------------+
|       User         |          |    StorageQuota    |
+--------------------+          +--------------------+
| userId       (PK)  |----1:1---| userId       (PK)  |
| email              |          | totalLimit         |
| displayName        |          | usedStorage        |
| createdAt          |          | planType           |
| status             |          | updatedAt          |
+--------------------+          +--------------------+
         |
         | 1:N
         v
+--------------------+          +--------------------+
|      Folder        |----1:N---|       File         |
+--------------------+          +--------------------+
| folderId     (PK)  |          | fileId       (PK)  |
| userId       (FK)  |          | userId       (FK)  |
| parentId     (FK)  |          | folderId     (FK)  |
| name               |          | name               |
| path               |          | size               |
| depth              |          | mimeType           |
| isShared           |          | currentVersion     |
| isDeleted          |          | checksum           |
| deletedAt          |          | isDeleted          |
| createdAt          |          | deletedAt          |
| updatedAt          |          | createdAt          |
+--------------------+          | updatedAt          |
                                +--------------------+
                                         |
                         +---------------+---------------+
                         | 1:N                           | 1:N
                         v                               v
              +--------------------+          +--------------------+
              |    FileVersion     |          |    ShareLink       |
              +--------------------+          +--------------------+
              | versionId    (PK)  |          | shareId      (PK)  |
              | fileId       (FK)  |          | fileId       (FK)  |
              | versionNum         |          | sharedByUser (FK)  |
              | size               |          | sharedWithUser(FK) |
              | checksum           |          | role               |
              | modifiedBy         |          | linkUrl            |
              | changeNote         |          | password           |
              | createdAt          |          | accessLevel        |
              +--------------------+          | expiresAt          |
                         |                   | createdAt          |
                         | 1:N               +--------------------+
                         v
              +--------------------+
              |    FileChunk       |
              +--------------------+
              | chunkId      (PK)  |
              | versionId    (FK)  |
              | chunkIndex         |
              | blockHash    (FK)  |----+
              | size               |    |
              +--------------------+    |
                                        |
                                        | N:1
                                        v
              +--------------------+          +--------------------+
              |    BlockStore      |          |    SyncCursor      |
              +--------------------+          +--------------------+
              | blockHash    (PK)  |          | cursorId     (PK)  |
              | storageUrl         |          | userId       (FK)  |
              | size               |          | deviceId           |
              | referenceCount     |          | cursorToken        |
              | createdAt          |          | lastSyncAt         |
              +--------------------+          | updatedAt          |
                                              +--------------------+
```

### 7.2 Table Definitions

```
File (250 billion rows, sharded by userId)
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| file_id          | VARCHAR(20) | PK, globally unique (KSUID or Snowflake ID)   |
| user_id          | VARCHAR(20) | FK -> User, shard key                         |
| folder_id        | VARCHAR(20) | FK -> Folder (null = root folder)             |
| name             | VARCHAR(255)| File name with extension                      |
| size             | BIGINT      | File size in bytes                            |
| mime_type        | VARCHAR(100)| MIME type (application/pdf, image/png, etc.)  |
| current_version  | INT         | Latest version number                         |
| checksum         | VARCHAR(64) | SHA-256 hash of entire file                   |
| is_deleted       | BOOLEAN     | Soft delete flag                              |
| deleted_at       | TIMESTAMP   | When file was moved to trash                  |
| created_at       | TIMESTAMP   | Creation timestamp                            |
| updated_at       | TIMESTAMP   | Last modification timestamp                   |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (file_id)
  - INDEX idx_user_folder (user_id, folder_id)       -- list folder contents
  - INDEX idx_user_name (user_id, name)               -- search by name
  - INDEX idx_deleted (user_id, is_deleted, deleted_at) -- trash listing + purge


FileVersion (750 billion rows, sharded by fileId)
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| version_id       | VARCHAR(20) | PK                                            |
| file_id          | VARCHAR(20) | FK -> File                                    |
| version_num      | INT         | Sequential version (1, 2, 3...)               |
| size             | BIGINT      | Size of this version                          |
| checksum         | VARCHAR(64) | SHA-256 of this version                       |
| modified_by      | VARCHAR(20) | FK -> User who made this version              |
| change_note      | VARCHAR(500)| Optional description of changes               |
| created_at       | TIMESTAMP   | When this version was created                 |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (version_id)
  - UNIQUE INDEX idx_file_version (file_id, version_num)
  - INDEX idx_file_created (file_id, created_at DESC)


FileChunk (mapping: which chunks belong to which version)
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| chunk_id         | VARCHAR(20) | PK                                            |
| version_id       | VARCHAR(20) | FK -> FileVersion                             |
| chunk_index      | INT         | Position in file (0, 1, 2...)                 |
| block_hash       | VARCHAR(64) | FK -> BlockStore (content-addressable key)    |
| size             | INT         | Chunk size in bytes (usually 4 MB)            |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (chunk_id)
  - INDEX idx_version_chunks (version_id, chunk_index)
  - INDEX idx_block_hash (block_hash)        -- for dedup reference counting


BlockStore (300 billion unique blocks, stored in object storage)
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| block_hash       | VARCHAR(64) | PK, SHA-256 of chunk content                  |
| storage_url      | VARCHAR(500)| URL in object storage (s3://bucket/hash)      |
| size             | INT         | Block size in bytes                           |
| reference_count  | INT         | Number of FileChunk records pointing here     |
| created_at       | TIMESTAMP   | When block was first stored                   |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (block_hash)
  - INDEX idx_ref_count (reference_count)    -- for garbage collection (ref=0)


Folder (50 billion rows, sharded by userId)
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| folder_id        | VARCHAR(20) | PK                                            |
| user_id          | VARCHAR(20) | FK -> User, shard key                         |
| parent_id        | VARCHAR(20) | FK -> Folder (null = root)                    |
| name             | VARCHAR(255)| Folder name                                   |
| path             | VARCHAR(2000)| Materialized path (/root/folder1/folder2)    |
| depth            | INT         | Nesting level (0 = root)                      |
| is_shared        | BOOLEAN     | Whether folder is shared with others          |
| is_deleted       | BOOLEAN     | Soft delete flag                              |
| deleted_at       | TIMESTAMP   | When folder was trashed                       |
| created_at       | TIMESTAMP   | Creation timestamp                            |
| updated_at       | TIMESTAMP   | Last modification timestamp                   |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (folder_id)
  - INDEX idx_user_parent (user_id, parent_id)  -- list subfolders
  - INDEX idx_path (user_id, path)               -- path-based lookup


ShareLink (shared files/folders)
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| share_id         | VARCHAR(20) | PK                                            |
| resource_id      | VARCHAR(20) | FK -> File or Folder                          |
| resource_type    | ENUM        | FILE | FOLDER                                 |
| shared_by_user   | VARCHAR(20) | FK -> User (owner who shared)                 |
| shared_with_user | VARCHAR(20) | FK -> User (null for link sharing)            |
| role             | ENUM        | VIEWER | EDITOR | OWNER                      |
| link_url         | VARCHAR(100)| Public share link (nullable)                  |
| password_hash    | VARCHAR(64) | Bcrypt hash of link password (nullable)       |
| access_level     | ENUM        | VIEWER | EDITOR                               |
| expires_at       | TIMESTAMP   | When share link expires (nullable)            |
| created_at       | TIMESTAMP   | When share was created                        |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (share_id)
  - INDEX idx_resource (resource_id, resource_type)
  - INDEX idx_shared_with (shared_with_user, resource_type)
  - INDEX idx_link_url (link_url)               -- unique, for public link lookup


SyncCursor (tracking sync position per device)
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| cursor_id        | VARCHAR(20) | PK                                            |
| user_id          | VARCHAR(20) | FK -> User                                    |
| device_id        | VARCHAR(50) | Unique device identifier                      |
| cursor_token     | VARCHAR(100)| Opaque token encoding sync position           |
| last_sync_at     | TIMESTAMP   | When this device last synced                  |
| updated_at       | TIMESTAMP   | Last cursor update                            |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (cursor_id)
  - UNIQUE INDEX idx_user_device (user_id, device_id)


StorageQuota
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| user_id          | VARCHAR(20) | PK, FK -> User                                |
| total_limit      | BIGINT      | Storage limit in bytes (15 GB free, 2 TB paid)|
| used_storage     | BIGINT      | Current storage used in bytes                 |
| plan_type        | ENUM        | FREE | BASIC | PREMIUM | ENTERPRISE          |
| updated_at       | TIMESTAMP   | Last quota update                             |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (user_id)


User
+------------------+-------------+-----------------------------------------------+
| Column           | Type        | Notes                                         |
+------------------+-------------+-----------------------------------------------+
| user_id          | VARCHAR(20) | PK                                            |
| email            | VARCHAR(255)| Unique email address                          |
| display_name     | VARCHAR(100)| User's display name                           |
| created_at       | TIMESTAMP   | Registration timestamp                        |
| status           | ENUM        | ACTIVE | SUSPENDED | DELETED                 |
+------------------+-------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (user_id)
  - UNIQUE INDEX idx_email (email)
```

---

## 8. High-Level Architecture

```
                                 +-------------------------------------------+
                                 |              Client Layer                  |
                                 |  (Desktop App, Mobile App, Web Browser)   |
                                 |                                           |
                                 |  - File system watcher (inotify/FSEvents) |
                                 |  - Chunking engine (split/hash/reassemble)|
                                 |  - Sync engine (delta detection)          |
                                 |  - Local cache / offline queue            |
                                 +-------------------+-----------------------+
                                                     |
                                           (1) HTTPS / WebSocket
                                                     |
                                                     v
                            +------------------------------------------------+
                            |              API Gateway / Load Balancer        |
                            |                                                |
                            |  - TLS termination                             |
                            |  - Authentication / JWT validation             |
                            |  - Rate limiting (per user, per IP)            |
                            |  - Request routing                             |
                            +------+------+------+------+------+------+------+
                                   |      |      |      |      |      |
           +-----------------------+      |      |      |      |      +----------+
           |              +---------------+      |      |      +------+          |
           v              v                      v      v             v          v
   +-------------+ +-------------+   +-------------+ +----------+ +----------+ +----------+
   |   Upload    | |  Download   |   |  Metadata   | |  Sync    | | Sharing  | |  Trash   |
   |  Service    | |  Service    |   |  Service    | | Service  | | Service  | | Service  |
   |             | |             |   |             | |          | |          | |          |
   | - Chunked   | | - Range req | | - File CRUD | | - Change | | - ACL    | | - Soft   |
   |   upload    | | - Presigned | | - Folder    | |   detect | | - Share  | |   delete |
   | - Checksum  | |   URLs      | | - Search    | | - Notify | |   links  | | - 30-day |
   | - Resume    | | - CDN route | | - Quota     | | - Delta  | | - Perms  | |   retain |
   +------+------+ +------+------+ +------+------+ +----+-----+ +----+-----+ +----+-----+
          |               |               |              |            |            |
          |               |               |              |            |            |
          v               v               v              v            v            v
   +------+---------------+---------------+--------------+------------+------------+-----+
   |                                                                                     |
   |                              Internal Service Bus                                   |
   |                                                                                     |
   +----------+---------------------+---------------------+-----------------------------+
              |                     |                     |
              v                     v                     v
   +-------------------+  +-------------------+  +-------------------+
   |  Deduplication    |  |   Versioning      |  |   Notification    |
   |  Service          |  |   Service         |  |   Service         |
   |                   |  |                   |  |                   |
   | - Hash lookup     |  | - Version create  |  | - Push to devices |
   | - Block reference |  | - Diff / rollback |  | - Email notify    |
   | - Garbage collect |  | - Chunk sharing   |  | - WebSocket push  |
   +--------+----------+  +--------+----------+  +-------------------+
            |                      |
            v                      v
   +------------------------------------------------+
   |              Block Storage Service              |
   |                                                 |
   |  - Content-addressable storage                  |
   |  - Store/retrieve chunks by SHA-256 hash        |
   |  - Reference counting                           |
   |  - Garbage collection (ref_count = 0)           |
   +------------------------+------------------------+
                            |
              +-------------+-------------+
              |                           |
              v                           v
   +-------------------+       +-------------------+
   |   Object Storage  |       |    PostgreSQL     |
   |   (S3 / GCS)      |       |   (Metadata DB)   |
   |                   |       |                   |
   | - File chunks     |       | - File records    |
   | - 2.5 EB total    |       | - Folder tree     |
   | - 11 nines        |       | - Versions        |
   |   durability      |       | - Permissions     |
   +-------------------+       | - Sync cursors    |
                               +-------------------+
              +-------------------+
              |      Redis        |
              |                   |
              | - Upload state    |
              | - Sync cursors    |
              | - Rate limits     |
              | - Block hash      |
              |   lookup cache    |
              +-------------------+

              +-------------------+
              |      Kafka        |
              |                   |
              | - File events     |
              | - Sync events     |
              | - Dedup events    |
              | - Purge events    |
              +-------------------+

              +-------------------+
              |       CDN         |
              |                   |
              | - Popular file    |
              |   downloads       |
              | - Static assets   |
              | - Edge caching    |
              +-------------------+
```

### Request Flow Summary

```
Upload Flow:
  (1) Client -> API Gateway -> Upload Service
  (2) Upload Service -> Deduplication Service (check which chunks exist)
  (3) Client -> Object Storage (presigned URL, only new chunks)
  (4) Upload Service -> Metadata Service (create file + version + chunk records)
  (5) Metadata Service -> Kafka (publish file_created event)
  (6) Kafka -> Sync Service (notify other devices)
  (7) Sync Service -> Notification Service -> Client devices

Download Flow:
  (1) Client -> API Gateway -> Download Service
  (2) Download Service -> Metadata Service (get file metadata + chunk list)
  (3) Download Service -> CDN / Object Storage (get chunks)
  (4) Download Service -> Client (stream reassembled file)

Sync Flow:
  (1) Client -> API Gateway -> Sync Service (GET /sync/changes?cursor=xxx)
  (2) Sync Service -> Metadata Service (changes since cursor)
  (3) Sync Service -> Client (list of changed files + changed chunks)
  (4) Client -> Download Service (fetch only changed chunks)
```

---

## 9. Component Deep Dive

### 9.1 Upload Service

```
Responsibility: Handle file uploads (small + chunked), validate checksums,
                coordinate with Dedup Service, manage upload state.

+-------------------------------------------------------------------+
|                        Upload Service                              |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Small Upload     |  | Chunked Upload   |  | Upload State     |  |
|  | Handler          |  | Handler          |  | Manager          |  |
|  |                  |  |                  |  |                  |  |
|  | - Files < 4 MB   |  | - Init session   |  | - Track chunks   |  |
|  | - Single PUT     |  | - Generate       |  | - Resume state   |  |
|  | - Hash + store   |  |   presigned URLs |  | - Timeout after  |  |
|  |                  |  | - Verify chunks  |  |   24 hours       |  |
|  +------------------+  | - Complete upload|  | - Stored in Redis|  |
|                        +------------------+  +------------------+  |
|                                                                    |
|  +------------------+  +------------------+                        |
|  | Checksum         |  | Quota            |                        |
|  | Verifier         |  | Enforcer         |                        |
|  |                  |  |                  |                        |
|  | - SHA-256 per    |  | - Check before   |                        |
|  |   chunk          |  |   upload starts  |                        |
|  | - Reject corrupt |  | - Reserve space  |                        |
|  |   chunks         |  | - Release on fail|                        |
|  +------------------+  +------------------+                        |
+-------------------------------------------------------------------+

Upload Flow (Chunked):

  Client                Upload Service          Dedup Service       Object Storage
    |                        |                       |                    |
    |  (1) POST /upload/     |                       |                    |
    |      chunked/init      |                       |                    |
    |  {chunkChecksums:[...]}|                       |                    |
    |----------------------->|                       |                    |
    |                        |  (2) Check which      |                    |
    |                        |      hashes exist      |                    |
    |                        |----------------------->|                    |
    |                        |                       |                    |
    |                        |  (3) Return: existing  |                    |
    |                        |       [0,1,4,5]        |                    |
    |                        |       missing [2,3,6]  |                    |
    |                        |<-----------------------|                    |
    |                        |                       |                    |
    |                        |  (4) Generate presigned|                    |
    |                        |      URLs for missing  |                    |
    |                        |      chunks only       |                    |
    |                        |                       |                    |
    |  (5) Return uploadId   |                       |                    |
    |      + presigned URLs  |                       |                    |
    |      + existing chunks |                       |                    |
    |<-----------------------|                       |                    |
    |                                                                     |
    |  (6) PUT chunk/2 directly to Object Storage via presigned URL       |
    |-------------------------------------------------------------------->|
    |  (7) PUT chunk/3 (parallel, up to 4 concurrent)                     |
    |-------------------------------------------------------------------->|
    |  (8) PUT chunk/6                                                    |
    |-------------------------------------------------------------------->|
    |                                                                     |
    |  (9) POST /upload/     |                       |                    |
    |      chunked/complete  |                       |                    |
    |----------------------->|                       |                    |
    |                        |  (10) Verify all      |                    |
    |                        |       chunks present  |                    |
    |                        |  (11) Create file     |                    |
    |                        |       metadata        |                    |
    |                        |  (12) Update block    |                    |
    |                        |       reference counts|                    |
    |  (13) Return fileId    |                       |                    |
    |<-----------------------|                       |                    |

Presigned URL Strategy:
  - Upload Service generates time-limited (1 hour) presigned URLs
  - Client uploads chunks directly to Object Storage (bypasses our servers)
  - Reduces bandwidth and CPU on Upload Service
  - Object Storage validates the signature and content length
  - After upload, Object Storage triggers notification to Upload Service

Upload State (in Redis, TTL = 24 hours):
  Key: upload:{uploadId}
  Value: {
    "userId":        "user_alice",
    "fileName":      "presentation.pptx",
    "totalChunks":   13,
    "uploadedChunks": [3, 7],          // updated as chunks arrive
    "chunkChecksums": {...},
    "startedAt":     "2026-04-26T10:30:00Z",
    "status":        "IN_PROGRESS"     // IN_PROGRESS | COMPLETED | EXPIRED
  }
```

### 9.2 Download Service

```
Responsibility: Serve file downloads, support range requests, route through CDN
                for popular files, reassemble chunks on the fly.

+-------------------------------------------------------------------+
|                       Download Service                             |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Full Download    |  | Range Request    |  | CDN Router       |  |
|  | Handler          |  | Handler          |  |                  |  |
|  |                  |  |                  |  | - Popular files  |  |
|  | - Get chunk list |  | - Parse Range    |  |   -> CDN edge    |  |
|  | - Stream from    |  |   header         |  | - Cold files     |  |
|  |   Object Storage |  | - Map byte range |  |   -> direct from |  |
|  | - Reassemble     |  |   to chunk range |  |   Object Storage |  |
|  +------------------+  | - Serve partial  |  +------------------+  |
|                        +------------------+                        |
+-------------------------------------------------------------------+

Download Flow:

  Client              Download Service       Metadata Service      Object Storage / CDN
    |                       |                      |                       |
    |  (1) GET /files/      |                      |                       |
    |      {id}/download    |                      |                       |
    |---------------------->|                      |                       |
    |                       |  (2) Get file        |                       |
    |                       |      metadata +      |                       |
    |                       |      chunk list      |                       |
    |                       |--------------------->|                       |
    |                       |                      |                       |
    |                       |  (3) Return: file    |                       |
    |                       |      metadata, chunks|                       |
    |                       |      [hash0, hash1,  |                       |
    |                       |       hash2, ...]    |                       |
    |                       |<---------------------|                       |
    |                       |                                              |
    |                       |  (4) Check: is this a popular file?          |
    |                       |      YES -> redirect to CDN                  |
    |                       |      NO  -> fetch chunks from Object Storage |
    |                       |                                              |
    |                       |  (5) Fetch chunks in parallel                |
    |                       |--------------------------------------------->|
    |                       |                                              |
    |                       |  (6) Stream chunks back                      |
    |                       |<---------------------------------------------|
    |                       |                                              |
    |  (7) Stream reassembled                                              |
    |      file to client   |                                              |
    |<----------------------|                                              |

Range Request Mapping:
  Client requests: Range: bytes=8388608-12582911  (bytes 8MB to 12MB)
  
  File chunks (4 MB each):
    Chunk 0: bytes 0 - 4,194,303
    Chunk 1: bytes 4,194,304 - 8,388,607
    Chunk 2: bytes 8,388,608 - 12,582,911   <-- this chunk
    Chunk 3: bytes 12,582,912 - 16,777,215
  
  Download Service:
    (1) Parse Range header: start=8388608, end=12582911
    (2) Calculate chunk range: start_chunk=2, end_chunk=2
    (3) Fetch only chunk 2 from Object Storage
    (4) Return: 206 Partial Content with chunk 2 data

CDN Routing Decision:
  - File accessed >10 times in last hour -> mark as "hot"
  - Hot files: return CDN URL (redirect 302 or proxy)
  - Cold files: serve directly from Object Storage
  - CDN TTL: 1 hour for hot files, cache eviction for cold
```

### 9.3 Metadata Service

```
Responsibility: CRUD operations on files, folders, and their relationships.
                Search. Quota management. The "brain" of the file tree.

+-------------------------------------------------------------------+
|                       Metadata Service                             |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | File CRUD        |  | Folder CRUD      |  | Search           |  |
|  |                  |  |                  |  |                  |  |
|  | - Create file    |  | - Create folder  |  | - By name        |  |
|  | - Get metadata   |  | - List contents  |  | - By type        |  |
|  | - Update name    |  | - Move folder    |  | - By owner       |  |
|  | - Move file      |  | - Rename         |  | - By date range  |  |
|  | - Delete (soft)  |  | - Delete (recur) |  | - Typeahead      |  |
|  +------------------+  +------------------+  +------------------+  |
|                                                                    |
|  +------------------+  +------------------+                        |
|  | Path Manager     |  | Quota Manager    |                        |
|  |                  |  |                  |                        |
|  | - Materialized   |  | - Check quota    |                        |
|  |   path updates   |  |   before upload  |                        |
|  | - Depth tracking |  | - Update usage   |                        |
|  | - Cycle detection|  |   after upload   |                        |
|  |                  |  | - Block if over  |                        |
|  +------------------+  +------------------+                        |
+-------------------------------------------------------------------+

Folder Move Operation (complex case):

  Client                 Metadata Service              PostgreSQL
    |                         |                             |
    |  (1) POST /folders/     |                             |
    |      {id}/move          |                             |
    |  {newParentId: "xyz"}   |                             |
    |------------------------>|                             |
    |                         |  (2) Validate: is newParent |
    |                         |      a descendant of {id}?  |
    |                         |      (cycle detection)      |
    |                         |---------------------------->|
    |                         |                             |
    |                         |  (3) If cycle detected:     |
    |                         |      return 400 Bad Request |
    |                         |                             |
    |                         |  (4) BEGIN TRANSACTION      |
    |                         |---------------------------->|
    |                         |                             |
    |                         |  (5) Update folder.parent_id|
    |                         |---------------------------->|
    |                         |                             |
    |                         |  (6) Update materialized    |
    |                         |      path for folder and ALL|
    |                         |      descendants            |
    |                         |---------------------------->|
    |                         |                             |
    |                         |  (7) COMMIT                 |
    |                         |---------------------------->|
    |                         |                             |
    |  (8) Return updated     |                             |
    |      folder metadata    |                             |
    |<------------------------|                             |

Materialized Path Strategy:
  - Each folder stores its full path: "/root/projects/Q4/reports"
  - Enables efficient ancestor/descendant queries
  - On folder move: UPDATE all descendants' paths (batch update)
  - Tradeoff: writes are expensive (move = update N rows), but
    reads are O(1) for path lookup -- reads dominate 100:1

Quota Management:
  - Quota tracked in StorageQuota table (per user)
  - On upload: check quota BEFORE accepting chunks
  - Deduped files: charge the user for the full logical file size
    (even if physical storage is less due to dedup)
  - Reason: dedup is an internal optimization, not a user-facing benefit
    (otherwise users could game the system by uploading popular files)
```

### 9.4 Block Storage Service

```
Responsibility: Store and retrieve file chunks by content hash.
                Content-addressable storage. Reference counting. Garbage collection.

+-------------------------------------------------------------------+
|                     Block Storage Service                          |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Block Writer     |  | Block Reader     |  | Reference        |  |
|  |                  |  |                  |  | Counter          |  |
|  | - Write chunk to |  | - Read chunk by  |  |                  |  |
|  |   Object Storage |  |   hash           |  | - Increment on   |  |
|  | - Key = SHA-256  |  | - Verify hash    |  |   new reference  |  |
|  |   of content     |  |   on read        |  | - Decrement on   |  |
|  +------------------+  +------------------+  |   version delete |  |
|                                              | - GC when ref=0  |  |
|  +------------------+                        +------------------+  |
|  | Garbage          |                                              |
|  | Collector        |                                              |
|  |                  |                                              |
|  | - Scan for       |                                              |
|  |   ref_count = 0  |                                              |
|  | - Grace period:  |                                              |
|  |   7 days after   |                                              |
|  |   ref drops to 0 |                                              |
|  | - Delete from    |                                              |
|  |   Object Storage |                                              |
|  +------------------+                                              |
+-------------------------------------------------------------------+

Content-Addressable Storage Model:

  The KEY for every block is the SHA-256 hash of its content.
  Identical content ALWAYS maps to the same key.

  Example:
    Chunk content: [binary data of a 4 MB block]
    SHA-256 hash:  "a1b2c3d4e5f6...64 hex chars"
    Storage key:   s3://file-storage-blocks/a1/b2/a1b2c3d4e5f6...

  Directory structure in S3 (first 4 chars as prefix for even distribution):
    s3://file-storage-blocks/
      a1/b2/a1b2c3d4e5f6...
      c3/d4/c3d4e5f6a7b8...
      e5/f6/e5f6a7b8c9d0...

Reference Counting:

  Block: "a1b2c3d4e5f6..."
    Created by: user_alice, file "report.pdf", version 1, chunk 0
    ref_count = 1

  Later: user_bob uploads "quarterly.pdf" which has an identical chunk
    Dedup detects hash already exists -> no upload needed
    ref_count = 2

  Later: user_alice deletes "report.pdf" permanently
    ref_count decremented -> ref_count = 1
    Block is NOT deleted (user_bob still references it)

  Later: user_bob deletes "quarterly.pdf" permanently
    ref_count decremented -> ref_count = 0
    Block enters garbage collection grace period (7 days)

  After 7 days with ref_count still 0:
    Garbage collector deletes block from Object Storage

Garbage Collection Strategy:
  (1) Nightly batch job scans BlockStore for ref_count = 0
  (2) Filters for blocks where ref_count dropped to 0 more than 7 days ago
  (3) Double-checks ref_count (avoid race with concurrent upload)
  (4) Deletes from Object Storage
  (5) Removes BlockStore record
  
  Why 7-day grace period?
    - Prevents race: upload in progress might reference a block being deleted
    - Allows recovery if ref_count was decremented by mistake
    - Storage cost of 7 extra days is negligible vs data safety
```

### 9.5 Deduplication Service

```
Responsibility: THE STAR OF THE INTERVIEW. Detect duplicate content at the block
                level. Eliminate redundant storage. Save 30-50% storage costs.

+-------------------------------------------------------------------+
|                     Deduplication Service                          |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Hash Lookup      |  | Dedup Decision   |  | Block Index      |  |
|  |                  |  | Engine           |  | (Bloom Filter +  |  |
|  | - Check if hash  |  |                  |  |  PostgreSQL)     |  |
|  |   exists in      |  | - If hash exists:|  |                  |  |
|  |   block index    |  |   skip upload,   |  | - Bloom filter   |  |
|  | - Bloom filter   |  |   add reference  |  |   for fast "no"  |  |
|  |   for fast       |  | - If hash is new:|  |   (false positive |  |
|  |   negative check |  |   upload chunk,  |  |   rate: 0.1%)    |  |
|  +------------------+  |   create block   |  | - DB for confirm |  |
|                        +------------------+  +------------------+  |
+-------------------------------------------------------------------+

Dedup Flow:

  Upload Service          Dedup Service           Block Index          Object Storage
       |                       |                      |                      |
       |  (1) Check hashes:    |                      |                      |
       |  [hash0, hash1, ...,  |                      |                      |
       |   hash12]             |                      |                      |
       |---------------------->|                      |                      |
       |                       |  (2) Check Bloom     |                      |
       |                       |  filter for each hash|                      |
       |                       |--------------------->|                      |
       |                       |                      |                      |
       |                       |  (3) Bloom says:     |                      |
       |                       |  hash0: MAYBE EXISTS |                      |
       |                       |  hash1: MAYBE EXISTS |                      |
       |                       |  hash2: NOT EXISTS   |                      |
       |                       |  hash3: NOT EXISTS   |                      |
       |                       |  ...                 |                      |
       |                       |<---------------------|                      |
       |                       |                      |                      |
       |                       |  (4) For MAYBE EXISTS|                      |
       |                       |  hashes: confirm in  |                      |
       |                       |  PostgreSQL          |                      |
       |                       |--------------------->|                      |
       |                       |                      |                      |
       |                       |  (5) PostgreSQL      |                      |
       |                       |  confirms:           |                      |
       |                       |  hash0: EXISTS       |                      |
       |                       |  hash1: EXISTS       |                      |
       |                       |<---------------------|                      |
       |                       |                      |                      |
       |  (6) Return:          |                      |                      |
       |  existing: [0, 1]     |                      |                      |
       |  missing:  [2, 3, ...]|                      |                      |
       |<----------------------|                      |                      |
       |                       |                      |                      |
       |  (7) After new chunks |                      |                      |
       |  uploaded, register   |                      |                      |
       |  new hashes           |                      |                      |
       |---------------------->|                      |                      |
       |                       |  (8) Add to Bloom    |                      |
       |                       |  filter + PostgreSQL |                      |
       |                       |--------------------->|                      |
       |                       |  (9) Increment       |                      |
       |                       |  ref_count for       |                      |
       |                       |  existing blocks     |                      |
       |                       |--------------------->|                      |

Bloom Filter Design:
  - 300 billion unique blocks
  - Target false positive rate: 0.1% (1 in 1000)
  - Optimal parameters: ~430 GB memory, 10 hash functions
  - Partitioned across Redis cluster (43 nodes * 10 GB each)
  - False positive just means an extra PostgreSQL lookup (cheap)
  - False negative: IMPOSSIBLE (Bloom filter guarantee)
    -> we never accidentally re-upload an existing block

Dedup Statistics (real-world):
  +------------------------------+--------------+
  | Scenario                     | Dedup Ratio  |
  +------------------------------+--------------+
  | Enterprise (shared docs)     | 40-60%       |
  | Personal (photos, videos)    | 10-20%       |
  | Software dev (code repos)    | 30-50%       |
  | Mixed workload (average)     | 30-40%       |
  +------------------------------+--------------+
  
  At our scale:
    Raw storage:    2.5 EB
    After dedup:    ~1.5 EB (40% savings)
    Storage saved:  ~1.0 EB
    Cost savings:   ~$23/TB/month * 1,000,000 TB = $23M/month
```

### 9.6 Sync Service

```
Responsibility: Detect file changes, push notifications to other devices,
                manage sync cursors, handle conflict resolution.

+-------------------------------------------------------------------+
|                         Sync Service                               |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Change Detector  |  | Notification     |  | Conflict         |  |
|  |                  |  | Dispatcher       |  | Resolver         |  |
|  | - File events    |  |                  |  |                  |  |
|  |   from Kafka     |  | - Long poll to   |  | - Last-writer-   |  |
|  | - Build change   |  |   connected      |  |   wins (default) |  |
|  |   feed per user  |  |   devices        |  | - Keep-both      |  |
|  | - Include shared |  | - WebSocket push |  |   (Dropbox mode) |  |
|  |   file changes   |  | - Email digest   |  | - Manual merge   |  |
|  +------------------+  +------------------+  +------------------+  |
|                                                                    |
|  +------------------+                                              |
|  | Cursor Manager   |                                              |
|  |                  |                                              |
|  | - Track position |                                              |
|  |   per user per   |                                              |
|  |   device         |                                              |
|  | - Opaque token   |                                              |
|  | - Delta since    |                                              |
|  |   last cursor    |                                              |
|  +------------------+                                              |
+-------------------------------------------------------------------+

Sync Protocol:

  Device A (laptop)        Sync Service         Device B (phone)
       |                       |                      |
       |                       |  (1) Device B opens  |
       |                       |  long-poll connection |
       |                       |  GET /sync/changes   |
       |                       |  ?cursor=cursor_xyz  |
       |                       |<---------------------|
       |                       |                      |
       |  (2) User edits file  |                      |
       |  on laptop            |                      |
       |                       |                      |
       |  (3) Desktop client   |                      |
       |  detects change,      |                      |
       |  uploads new chunks   |                      |
       |                       |                      |
       |  (4) Metadata Service |                      |
       |  creates new version, |                      |
       |  publishes event      |                      |
       |  to Kafka             |                      |
       |                       |                      |
       |                       |  (5) Sync Service    |
       |                       |  consumes event from |
       |                       |  Kafka               |
       |                       |                      |
       |                       |  (6) Finds all       |
       |                       |  devices for this    |
       |                       |  user (except source)|
       |                       |                      |
       |                       |  (7) Respond to      |
       |                       |  Device B's long-poll|
       |                       |  with change details |
       |                       |--------------------->|
       |                       |                      |
       |                       |                      |  (8) Device B
       |                       |                      |  downloads changed
       |                       |                      |  chunks only
       |                       |                      |
       |                       |                      |  (9) Device B
       |                       |                      |  reconstructs file
       |                       |                      |  from local cache +
       |                       |                      |  new chunks
       |                       |                      |
       |                       |  (10) Device B sends |
       |                       |  new long-poll with  |
       |                       |  updated cursor      |
       |                       |<---------------------|

Long Polling vs WebSocket Decision:
  +------------------+-----------------------+------------------------+
  | Approach         | Pros                  | Cons                   |
  +------------------+-----------------------+------------------------+
  | Long Polling     | Simple, stateless,    | Higher latency (up to  |
  |                  | works through proxies,| 30s poll timeout),     |
  |                  | easy to load balance  | more HTTP overhead     |
  +------------------+-----------------------+------------------------+
  | WebSocket        | Real-time (<1s),      | Stateful connections,  |
  |                  | low overhead, bidir   | sticky sessions needed,|
  |                  |                       | harder to scale        |
  +------------------+-----------------------+------------------------+
  
  Decision: Long polling for file sync (changes are infrequent -- seconds matter
  less than for chat or collab editing). WebSocket optional for "live" folder views.
```

### 9.7 Versioning Service

```
Responsibility: Create new versions on file change, track chunk differences,
                enable rollback, manage version retention.

+-------------------------------------------------------------------+
|                       Versioning Service                           |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Version Creator  |  | Version Differ   |  | Rollback Handler |  |
|  |                  |  |                  |  |                  |  |
|  | - New version on |  | - Compare chunk  |  | - Copy metadata  |  |
|  |   file update    |  |   lists between  |  |   from old       |  |
|  | - Link to chunks |  |   versions       |  |   version to new |  |
|  | - Copy-on-write  |  | - Show which     |  | - Increment      |  |
|  |   (share chunks) |  |   chunks changed |  |   version number |  |
|  +------------------+  +------------------+  | - Old chunks     |  |
|                                              |   still exist    |  |
|  +------------------+                        +------------------+  |
|  | Retention Manager|                                              |
|  |                  |                                              |
|  | - Keep last N    |                                              |
|  |   versions (30)  |                                              |
|  | - Purge older    |                                              |
|  |   versions       |                                              |
|  | - Decrement block|                                              |
|  |   ref_counts     |                                              |
|  +------------------+                                              |
+-------------------------------------------------------------------+

Version Creation (Copy-on-Write):

  Original file (Version 1): 5 chunks
    Chunk 0: hash_aaaa  ->  Block hash_aaaa (ref_count = 1)
    Chunk 1: hash_bbbb  ->  Block hash_bbbb (ref_count = 1)
    Chunk 2: hash_cccc  ->  Block hash_cccc (ref_count = 1)
    Chunk 3: hash_dddd  ->  Block hash_dddd (ref_count = 1)
    Chunk 4: hash_eeee  ->  Block hash_eeee (ref_count = 1)

  User modifies middle section (Version 2): chunks 1 and 2 changed
    Chunk 0: hash_aaaa  ->  Block hash_aaaa (ref_count = 2)  <-- SHARED
    Chunk 1: hash_ffff  ->  Block hash_ffff (ref_count = 1)  <-- NEW
    Chunk 2: hash_gggg  ->  Block hash_gggg (ref_count = 1)  <-- NEW
    Chunk 3: hash_dddd  ->  Block hash_dddd (ref_count = 2)  <-- SHARED
    Chunk 4: hash_eeee  ->  Block hash_eeee (ref_count = 2)  <-- SHARED

  Storage for 2 versions:
    Naive approach:  10 chunks * 4 MB = 40 MB
    Copy-on-write:   7 unique blocks * 4 MB = 28 MB (30% savings)

  With 30 versions where ~20% of chunks change per version:
    Naive:  30 * 5 * 4 MB = 600 MB
    CoW:    5 + (29 * 1 new chunk) = 34 unique blocks * 4 MB = 136 MB (77% savings)
```

### 9.8 Sharing Service

```
Responsibility: Manage file/folder permissions, share links, access control.

+-------------------------------------------------------------------+
|                       Sharing Service                              |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Permission       |  | Share Link       |  | Access Control   |  |
|  | Manager          |  | Manager          |  | Enforcer         |  |
|  |                  |  |                  |  |                  |  |
|  | - Grant/revoke   |  | - Create links   |  | - Check on every |  |
|  |   per-user perms |  | - Password prot  |  |   API call       |  |
|  | - Inheritance    |  | - Expiration     |  | - Cache in Redis |  |
|  |   from folder    |  | - View count     |  |   (60s TTL)      |  |
|  +------------------+  +------------------+  +------------------+  |
+-------------------------------------------------------------------+

Permission Hierarchy:

  Folder: /shared-project (shared with Bob as EDITOR)
    |
    +-- File: report.pdf          Bob inherits EDITOR
    +-- Folder: /attachments      Bob inherits EDITOR
    |   |
    |   +-- File: image.png       Bob inherits EDITOR
    |   +-- File: data.csv        Bob inherits EDITOR
    |
    +-- File: confidential.pdf    EXPLICIT OVERRIDE: Bob has VIEWER only

  Permission Check Algorithm:
    (1) Check explicit permission on the resource itself
    (2) If none found, walk up the folder tree
    (3) First permission found wins
    (4) If no permission found at any level, DENY

  Permission Check Flow:
    Client            Sharing Service         Redis Cache        PostgreSQL
      |                     |                     |                  |
      |  (1) Can user_bob   |                     |                  |
      |  edit file_xyz?     |                     |                  |
      |-------------------->|                     |                  |
      |                     |  (2) Check cache:   |                  |
      |                     |  perm:file_xyz:bob  |                  |
      |                     |-------------------->|                  |
      |                     |                     |                  |
      |                     |  (3) Cache MISS     |                  |
      |                     |<--------------------|                  |
      |                     |                     |                  |
      |                     |  (4) Check explicit |                  |
      |                     |  perm on file_xyz   |                  |
      |                     |------------------------------------->|
      |                     |                     |                  |
      |                     |  (5) No explicit    |                  |
      |                     |  perm found         |                  |
      |                     |<-------------------------------------|
      |                     |                     |                  |
      |                     |  (6) Get parent     |                  |
      |                     |  folder, check perm |                  |
      |                     |------------------------------------->|
      |                     |                     |                  |
      |                     |  (7) Found: EDITOR  |                  |
      |                     |  on parent folder   |                  |
      |                     |<-------------------------------------|
      |                     |                     |                  |
      |                     |  (8) Cache result   |                  |
      |                     |  TTL = 60 seconds   |                  |
      |                     |-------------------->|                  |
      |                     |                     |                  |
      |  (9) ALLOWED:       |                     |                  |
      |  role = EDITOR      |                     |                  |
      |<--------------------|                     |                  |
```

### 9.9 Trash Service

```
Responsibility: Soft delete, 30-day retention, permanent purge with chunk cleanup.

+-------------------------------------------------------------------+
|                         Trash Service                              |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Soft Delete      |  | Recovery         |  | Permanent Purge  |  |
|  | Handler          |  | Handler          |  | (Scheduled Job)  |  |
|  |                  |  |                  |  |                  |  |
|  | - Set is_deleted |  | - Unset flags    |  | - Scan for items |  |
|  |   = true         |  | - Restore to     |  |   deleted > 30   |  |
|  | - Set deleted_at |  |   original path  |  |   days ago       |  |
|  | - Recursive for  |  |   (or root if    |  | - Delete all     |  |
|  |   folders        |  |   path gone)     |  |   versions       |  |
|  +------------------+  +------------------+  | - Decrement block|  |
|                                              |   ref_counts     |  |
|                                              | - Update quota   |  |
|                                              +------------------+  |
+-------------------------------------------------------------------+

Purge Flow:

  Scheduled Job (daily)      Metadata DB        Block Storage      Object Storage
       |                         |                    |                  |
       |  (1) SELECT files WHERE |                    |                  |
       |  is_deleted = true AND  |                    |                  |
       |  deleted_at < now()-30d |                    |                  |
       |  LIMIT 10000            |                    |                  |
       |------------------------>|                    |                  |
       |                         |                    |                  |
       |  (2) For each file:     |                    |                  |
       |  get all versions +     |                    |                  |
       |  chunk references       |                    |                  |
       |------------------------>|                    |                  |
       |                         |                    |                  |
       |  (3) For each chunk:    |                    |                  |
       |  decrement ref_count    |                    |                  |
       |  in BlockStore          |                    |                  |
       |------------------------------------------>---|                  |
       |                         |                    |                  |
       |  (4) Delete FileChunk,  |                    |                  |
       |  FileVersion, File      |                    |                  |
       |  records                |                    |                  |
       |------------------------>|                    |                  |
       |                         |                    |                  |
       |  (5) Update user's      |                    |                  |
       |  StorageQuota           |                    |                  |
       |  (decrease used_storage)|                    |                  |
       |------------------------>|                    |                  |
       |                         |                    |                  |
       |  (6) Blocks with        |                    |                  |
       |  ref_count = 0 will be  |                    |                  |
       |  cleaned by Block       |                    |                  |
       |  Storage GC (Section    |                    |                  |
       |  9.4) after 7-day       |                    |                  |
       |  grace period           |                    |                  |
       |                         |                    |                  |
       |  (7) Publish purge      |                    |                  |
       |  event to Kafka for     |                    |                  |
       |  audit log              |                    |                  |
```

---

## 10. Chunked Upload Deep Dive

### 10.1 Why Chunked Upload?

```
Problem: Uploading a 10 GB file over a network connection that drops every 5 minutes.

Without chunked upload:
  - Upload starts, transfers 3 GB in 4 minutes
  - Network drops at minute 5
  - Connection lost -> ENTIRE upload must restart from byte 0
  - User waits another 15 minutes... drops again at 8 GB
  - User gives up

With chunked upload:
  - File split into 2,560 chunks of 4 MB each
  - Upload 4 chunks in parallel
  - Network drops after uploading 768 chunks (3 GB)
  - Connection restored -> resume from chunk 769
  - Only 1,792 remaining chunks to upload
  - Network drops again at chunk 2,048 -> resume from 2,049
  - Eventually all 2,560 chunks uploaded
  - Server assembles complete file from chunks
```

### 10.2 Chunk Size Selection

```
+---------------+-----------------------+-------------------------------------+
| Chunk Size    | Pros                  | Cons                                |
+---------------+-----------------------+-------------------------------------+
| 1 MB          | Fine-grained resume,  | Too many chunks for large files     |
|               | better dedup ratio    | (10 GB = 10,240 chunks).            |
|               |                       | High metadata overhead. Many HTTP   |
|               |                       | requests.                           |
+---------------+-----------------------+-------------------------------------+
| 4 MB          | SWEET SPOT.           | Moderate dedup granularity.         |
| (our choice)  | 10 GB = 2,560 chunks  | Acceptable resume granularity       |
|               | (manageable). Good    | (max 4 MB lost on failure).         |
|               | balance of dedup +    |                                     |
|               | performance.          |                                     |
+---------------+-----------------------+-------------------------------------+
| 8 MB          | Fewer chunks, fewer   | Coarser dedup (a 1-byte change     |
|               | HTTP requests         | invalidates 8 MB block).            |
|               |                       | 8 MB lost on network failure.       |
+---------------+-----------------------+-------------------------------------+
| 64 MB         | Very few chunks,      | Poor dedup, large resume penalty,   |
|               | minimal overhead      | high memory on client                |
+---------------+-----------------------+-------------------------------------+

Dropbox uses 4 MB. Google Drive uses 5 MB (for resumable uploads).
We choose 4 MB as the industry-standard sweet spot.
```

### 10.3 Parallel Upload Strategy

```
File: 50 MB (13 chunks of 4 MB, last chunk 2 MB)

Timeline with 4 concurrent upload slots:

  Time     Slot 1     Slot 2     Slot 3     Slot 4     Status
  ------   --------   --------   --------   --------   ------------------
  t=0      chunk 0    chunk 1    chunk 2    chunk 3    4 uploading
  t=1s     DONE       chunk 4    DONE       chunk 5    2 done, 4 uploading
  t=2s     chunk 6    DONE       chunk 7    DONE       4 done, 4 uploading
  t=3s     DONE       chunk 8    DONE       chunk 9    6 done, 4 uploading
  t=4s     chunk 10   DONE       chunk 11   DONE       8 done, 4 uploading
  t=5s     DONE       chunk 12   DONE       (idle)     10 done, 2 uploading
  t=6s     (idle)     DONE       (idle)     (idle)     13 done, COMPLETE

  Total: ~6 seconds for 50 MB = ~8.3 MB/s effective throughput
  Without parallelism: ~13 seconds (each chunk sequential)
  Speedup: ~2.2x (limited by network bandwidth, not parallelism slots)

  With dedup (8 of 13 chunks already exist):
    Only 5 chunks to upload
    Total: ~2 seconds (5 chunks with 4 parallel slots)
    Effective speedup: 6.5x vs non-dedup sequential
```

### 10.4 Resumable Upload Protocol

```
Scenario: Upload interrupted after chunk 7 of 13.

  Client                  Upload Service              Redis
    |                          |                         |
    |  (1) Resume request:     |                         |
    |  GET /upload/chunked/    |                         |
    |  {uploadId}/status       |                         |
    |------------------------->|                         |
    |                          |  (2) Lookup upload      |
    |                          |  state in Redis         |
    |                          |------------------------>|
    |                          |                         |
    |                          |  (3) Return state:      |
    |                          |  uploaded: [0-7]        |
    |                          |  missing:  [8-12]       |
    |                          |<------------------------|
    |                          |                         |
    |  (4) Return missing      |                         |
    |  chunks + new presigned  |                         |
    |  URLs for [8, 9, 10,     |                         |
    |  11, 12]                 |                         |
    |<-------------------------|                         |
    |                                                    |
    |  (5) Client uploads chunks 8-12 in parallel        |
    |  (6) Client calls POST /upload/chunked/complete    |

Upload State Machine:
  
  INITIATED --> IN_PROGRESS --> COMPLETING --> COMPLETED
       |              |                            ^
       |              |                            |
       |              +---- PAUSED (client gone) --+
       |                       |                   |
       |                       +--- EXPIRED -------+
       |                       (after 24h TTL)
       v
    CANCELLED
```

### 10.5 Checksum Verification

```
Three levels of checksum verification:

Level 1: Per-chunk checksum (client-side + server-side)
  - Client computes SHA-256 of each 4 MB chunk
  - Sends hash in X-Chunk-Checksum header
  - Server (Object Storage) re-computes SHA-256 after receiving chunk
  - If mismatch: reject chunk, client retries
  - Catches: network corruption, partial uploads

Level 2: Whole-file checksum (after assembly)
  - Client computes SHA-256 of entire file before chunking
  - After all chunks uploaded, server computes SHA-256 by hashing
    the concatenated chunks' hashes (Merkle-tree-like approach)
  - If mismatch: flag upload as corrupted, require re-upload
  - Catches: missing chunks, out-of-order assembly

Level 3: Periodic integrity check (background job)
  - Weekly scan: randomly sample 0.01% of blocks in Object Storage
  - Re-read block, compute SHA-256, compare to stored hash
  - If mismatch: block is corrupted -> restore from replica
  - Catches: bit rot, storage hardware failures

Checksum Computation Cost:
  SHA-256 throughput on modern hardware: ~500 MB/sec
  4 MB chunk: ~8 ms to hash
  50 MB file (13 chunks): ~104 ms total hashing
  Negligible compared to upload time (~6 seconds)
```

---

## 11. Deduplication Deep Dive

**This is THE interview star. Spend 30-40% of your time here.**

### 11.1 Content-Addressable Storage

```
Core Principle:
  The IDENTITY of a block is its CONTENT.
  Two blocks with identical content have identical hashes.
  Identical hashes -> store only once, reference many times.

  Traditional storage:      Content-addressable storage:
  
  file_a/chunk_0 -> [data]     hash("data") = abc123
  file_b/chunk_3 -> [data]     Store once:  abc123 -> [data]
  file_c/chunk_1 -> [data]     Reference 3x: file_a/0 -> abc123
                                              file_b/3 -> abc123
  Storage: 3 copies                           file_c/1 -> abc123
  = 12 MB                     Storage: 1 copy = 4 MB (67% savings)

Why SHA-256?
  - Collision probability: ~1 in 2^128 (birthday paradox)
  - For 300 billion blocks: probability of ANY collision = ~10^-20
  - You're more likely to be hit by a meteor while winning the lottery
  - Fast enough: 500 MB/sec on commodity hardware
  - Industry standard: Git, Docker, every blockchain uses SHA-256
```

### 11.2 Block-Level vs File-Level Dedup

```
File-Level Deduplication:
  - Compare entire file hashes
  - If two files are byte-for-byte identical, store only one copy
  - Simple to implement
  - BUT: if you change 1 byte in a 1 GB file, it's a completely new hash
  - Dedup ratio: ~10-15% (only exact duplicates)

Block-Level Deduplication (our approach):
  - Split files into 4 MB blocks, hash each block independently
  - Even if files differ overall, individual blocks may be identical
  - Change 1 byte in a 1 GB file: only 1 of 256 blocks changes
  - Dedup ratio: ~30-50% (much higher)

Example:
  Alice uploads: report_Q3.pdf (10 MB = 3 chunks)
    Chunk 0: hash_AAA  (header, common template)
    Chunk 1: hash_BBB  (Q3 data)
    Chunk 2: hash_CCC  (footer, common template)

  Bob uploads: report_Q4.pdf (10 MB = 3 chunks)
    Chunk 0: hash_AAA  (same header template!) -> DEDUP
    Chunk 1: hash_DDD  (Q4 data, different)    -> NEW
    Chunk 2: hash_CCC  (same footer template!) -> DEDUP

  File-level dedup: 0% savings (different files)
  Block-level dedup: 33% savings (2 of 3 chunks shared)

  Across 1000 employees using the same template:
  File-level dedup: 0% savings (each report is unique)
  Block-level dedup: ~60% savings (header + footer shared across all)
```

### 11.3 Fixed-Size vs Variable-Size Chunking

```
Fixed-Size Chunking (simple approach):
  Split file at every 4 MB boundary.
  
  File version 1: [    AAAA    |    BBBB    |    CCCC    |    DDDD    ]
                   chunk 0       chunk 1       chunk 2       chunk 3
  
  Insert 1 byte at beginning of file:
  File version 2: [X   AAAA   |    ABBB    |    BCCC    |    CDDD    | D]
                   chunk 0       chunk 1       chunk 2       chunk 3   chunk 4
  
  Result: ALL chunks shifted. 0 chunks deduplicated. Terrible!

Variable-Size Chunking (Rabin Fingerprint):
  Use a rolling hash to find chunk boundaries based on content.
  The boundary depends on a pattern in the data, not on position.
  
  Algorithm:
    (1) Slide a window of 48 bytes across the file
    (2) Compute Rabin fingerprint of the window
    (3) If fingerprint mod D == target: place chunk boundary here
    (4) D controls average chunk size (D = 4MB / average_window gives ~4 MB chunks)
  
  File version 1: [  AAAA  |   BBBBB   |  CCCC  |  DDDD  ]
                   boundary  boundary     boundary  boundary
                   (content  (content     (content  (content
                   pattern)  pattern)     pattern)  pattern)
  
  Insert 1 byte at beginning:
  File version 2: [X  AAAA  |   BBBBB   |  CCCC  |  DDDD  ]
                    boundary  boundary     boundary  boundary
                    (same!)   (same!)      (same!)   (same!)
  
  Result: Chunk 0 changed (has the new byte). Chunks 1, 2, 3 UNCHANGED.
  Dedup: 3 of 4 chunks deduplicated (75% savings) even with an insertion!

Why Rabin Fingerprint?
  - Rolling hash: O(1) to update when window slides by 1 byte
  - Boundaries are content-determined, not position-determined
  - Insertions/deletions only affect nearby chunks, not the whole file
  - Used by: Dropbox, rsync, LBFS (Low-Bandwidth File System)

Tradeoff: Fixed vs Variable
  +-------------------+--------------------------+---------------------------+
  | Aspect            | Fixed-Size (4 MB)        | Variable-Size (Rabin)     |
  +-------------------+--------------------------+---------------------------+
  | Implementation    | Trivial                  | Moderate complexity       |
  | Dedup on insert   | Poor (all chunks shift)  | Excellent (local impact)  |
  | Chunk size        | Exact 4 MB               | ~4 MB average (2-8 MB)   |
  | CPU cost          | Zero (just split)        | O(n) rolling hash         |
  | Best for          | New files, bulk uploads   | File versioning, sync     |
  +-------------------+--------------------------+---------------------------+

  Decision: Use FIXED-SIZE for initial upload (simple, fast).
            Use VARIABLE-SIZE (Rabin) for sync/delta detection (better dedup).
```

### 11.4 Deduplication Cost Analysis

```
Hashing Cost:
  SHA-256 throughput:       500 MB/sec per core
  100 TB uploads per day:   100,000,000 MB
  CPU cores needed:         100,000,000 / (500 * 86,400) = ~2.3 cores
  With 4x overhead:         ~10 cores dedicated to hashing
  Cost: negligible (< $100/month)

Storage Savings:
  Raw storage:              2.5 EB
  Dedup ratio:              40%
  Storage saved:            1.0 EB
  S3 cost:                  ~$23/TB/month
  Monthly savings:          1,000,000 TB * $23 = $23,000,000/month
  Annual savings:           ~$276,000,000/year

  ROI: 10 CPU cores ($100/month) saves $23M/month
       = 230,000x return on investment

Bloom Filter Cost:
  300 billion entries, 0.1% FP rate
  Memory:                   ~430 GB across cluster
  Redis cost:               ~$5,000/month (43 nodes * 10 GB)
  
  Still dwarfed by $23M/month savings.

Block Index (PostgreSQL) Cost:
  300 billion rows * 200 bytes = ~60 TB
  Sharded across 20 PostgreSQL instances
  Cost: ~$20,000/month

Total dedup infrastructure: ~$25,000/month
Total storage savings:      ~$23,000,000/month
Net savings:                ~$22,975,000/month
```

### 11.5 Dedup Decision Tree

```
New chunk arrives with hash H:

                    +---------------------+
                    | Check Bloom Filter  |
                    | for hash H          |
                    +----------+----------+
                               |
                    +----------+----------+
                    |                     |
                 PROBABLY              DEFINITELY
                 EXISTS                NOT EXISTS
                    |                     |
                    v                     v
           +----------------+    +------------------+
           | Check          |    | Upload chunk to  |
           | PostgreSQL     |    | Object Storage   |
           | BlockStore     |    | Key = hash H     |
           | for hash H     |    +--------+---------+
           +-------+--------+             |
                   |                      v
          +--------+--------+    +------------------+
          |                 |    | Insert into      |
        EXISTS          NOT EXISTS| BlockStore      |
          |                 |    | ref_count = 1    |
          v                 |    +------------------+
  +------------------+      |             |
  | Skip upload!     |      v             v
  | Increment        |  +------------------+
  | ref_count += 1   |  | Upload chunk to  |
  | Return existing  |  | Object Storage   |
  | block reference  |  | (Bloom FP case)  |
  +------------------+  +------------------+

False Positive Impact:
  - Bloom filter says "maybe exists" -> we check PostgreSQL
  - PostgreSQL says "does not exist" -> we upload the chunk
  - Extra cost: 1 unnecessary PostgreSQL query per false positive
  - At 0.1% FP rate and 100M uploads/day: ~100K extra DB queries/day
  - PostgreSQL handles this easily (< 1% of normal query load)
```

---

## 12. File Sync

### 12.1 Sync Architecture

```
                    Device A (Laptop)              Device B (Phone)
                    +----------------+             +----------------+
                    | File System    |             | File System    |
                    | Watcher        |             | Watcher        |
                    | (inotify /     |             | (inotify /     |
                    |  FSEvents)     |             |  FSEvents)     |
                    +-------+--------+             +-------+--------+
                            |                              |
                      (1) file change               (8) apply remote
                      detected                      change locally
                            |                              |
                            v                              v
                    +----------------+             +----------------+
                    | Local Sync     |             | Local Sync     |
                    | Engine         |             | Engine         |
                    |                |             |                |
                    | - Compute delta|             | - Download new |
                    | - Upload chunks|             |   chunks       |
                    | - Update server|             | - Reconstruct  |
                    +-------+--------+             +-------+--------+
                            |                              ^
                            |                              |
                   (2) upload changed            (7) respond with
                   chunks + new version         change details
                            |                              |
                            v                              |
                    +-------------------------------------+------+
                    |              Sync Service                   |
                    |                                             |
                    |  (3) Create new FileVersion                 |
                    |  (4) Publish event to Kafka                 |
                    |  (5) Kafka delivers to Sync Service         |
                    |  (6) Sync Service finds Device B's          |
                    |      long-poll connection                   |
                    +---------------------------------------------+
```

### 12.2 Sync Cursor Protocol

```
A sync cursor is an opaque token that encodes a position in the change stream.
Think of it like a bookmark: "I've seen all changes up to THIS point."

Implementation: cursor = Base64(timestamp + sequenceNumber + userId)

Example flow:

  Device B (phone)         Sync Service             Kafka (change log)
       |                       |                          |
       |  (1) First sync ever: |                          |
       |  GET /sync/changes    |                          |
       |  (no cursor)          |                          |
       |---------------------->|                          |
       |                       |  (2) Return ALL files    |
       |                       |  for this user           |
       |                       |  cursor = "c_2026042610" |
       |  (3) Full file list   |                          |
       |  + cursor              |                          |
       |<----------------------|                          |
       |                       |                          |
       |  ... time passes ...  |                          |
       |                       |                          |
       |  (4) Incremental sync:|                          |
       |  GET /sync/changes    |                          |
       |  ?cursor=c_2026042610 |                          |
       |---------------------->|                          |
       |                       |  (5) Query changes       |
       |                       |  since cursor position   |
       |                       |------------------------->|
       |                       |                          |
       |                       |  (6) Return: 3 changes   |
       |                       |  since cursor             |
       |                       |<-------------------------|
       |                       |                          |
       |  (7) 3 changes +      |                          |
       |  new cursor =         |                          |
       |  "c_2026042614"       |                          |
       |<----------------------|                          |
       |                       |                          |
       |  (8) Next sync:       |                          |
       |  GET /sync/changes    |                          |
       |  ?cursor=c_2026042614 |                          |
       |---------------------->|                          |

Long-Poll Optimization:
  - If no changes since cursor: hold the connection open for 30 seconds
  - If a change arrives during the hold: respond immediately
  - If 30 seconds pass with no changes: return empty response with same cursor
  - Client immediately opens a new long-poll connection
  - Result: near real-time notification with simple HTTP (no WebSocket needed)
```

### 12.3 Conflict Resolution

```
Conflict: Two devices edit the same file before syncing.

  Device A (laptop)                                Device B (tablet)
       |                                                |
       | (1) Edit file.txt                              |
       | (offline/not synced yet)                       |
       |                                                | (2) Edit file.txt
       |                                                | (different changes)
       |                                                |
       | (3) Comes online,                              |
       | uploads new version                            |
       |                                                |
       |             Sync Service                       |
       |                  |                             |
       |  (4) Accept      |                             |
       |  version 2       |                             |
       |                  |                             |
       |                  |  (5) Device B tries to      |
       |                  |  upload its version          |
       |                  |  (based on version 1)        |
       |                  |<----------------------------|
       |                  |                             |
       |                  |  (6) CONFLICT DETECTED:     |
       |                  |  Device B's base version (1)|
       |                  |  != current version (2)     |
       |                  |                             |
       |                  |  Resolution strategies:     |
       |                  +-----------------------------+

Strategy 1: Last-Writer-Wins (simplest)
  - Accept the most recent upload, discard the other
  - Simple but loses data
  - Used by: some enterprise systems

Strategy 2: Keep Both Copies (Dropbox approach)
  - Rename the conflicting file:
    file.txt                     (version from Device A)
    file (Karan's conflicted copy 2026-04-26).txt   (version from Device B)
  - No data loss
  - User resolves manually
  - Used by: Dropbox, OneDrive

Strategy 3: Auto-Merge (Google Drive approach for Google Docs)
  - Only works for structured documents (not arbitrary binary files)
  - Uses OT/CRDT to merge changes (see Project 14)
  - Not applicable to generic file storage

  Decision: Keep Both Copies (Strategy 2)
    - No data loss (critical for file storage)
    - User can see both versions and choose
    - Simple to implement
    - Industry standard (Dropbox has done this for 15+ years)

Conflict Detection Algorithm:
  (1) Client sends upload with base_version = N
  (2) Server checks: current_version == N?
      YES -> accept, create version N+1
      NO  -> conflict! current_version > N
  (3) On conflict: save as conflicted copy, notify user
```

### 12.4 Delta Sync

```
Delta sync: only transfer CHANGED chunks, not the entire file.

Traditional sync (without delta):
  File: 100 MB (25 chunks of 4 MB)
  Change: modify 2 paragraphs in the middle
  Transfer: entire 100 MB file re-uploaded
  Waste: 92 MB of unchanged data transferred

Delta sync (our approach):
  File: 100 MB (25 chunks of 4 MB)
  Change: modify 2 paragraphs (affects chunks 8 and 9)
  
  (1) Client re-hashes all chunks (or uses Rabin fingerprint for speed)
  (2) Compare new hashes with stored hashes:
      Chunk 0:  hash_old == hash_new  -> unchanged
      Chunk 1:  hash_old == hash_new  -> unchanged
      ...
      Chunk 8:  hash_old != hash_new  -> CHANGED
      Chunk 9:  hash_old != hash_new  -> CHANGED
      ...
      Chunk 24: hash_old == hash_new  -> unchanged
  
  (3) Upload only chunks 8 and 9 (8 MB instead of 100 MB)
  (4) Create new version: same chunk list except chunks 8 and 9 point to new blocks
  (5) Other devices download only chunks 8 and 9

  Transfer savings: 92% (8 MB vs 100 MB)
  Bandwidth: 12.5x reduction

Delta Sync with Rabin Fingerprint (advanced):
  - For variable-size chunking, use Rabin rolling hash
  - Fingerprint slides over file content, finding natural boundaries
  - After edit: re-run Rabin, most boundaries unchanged
  - Only chunks around the edit point have new boundaries
  - Even more efficient than fixed-size delta

  Client-side delta detection:
    (1) Keep local database of chunk hashes per file
    (2) On file change: re-hash only from first changed byte onward
    (3) Use file modification timestamp + size as quick-check
        (if neither changed, skip hashing entirely)
    (4) Optimization: if only file metadata changed (not content),
        skip chunk comparison entirely
```

---

## 13. Versioning

### 13.1 Version Model

```
Every file modification creates a new version.
A version is a snapshot of the file's chunk list at a point in time.

File: report.pdf (5 chunks)

  Version 1 (created 2026-04-20):
    chunk_list: [hash_A, hash_B, hash_C, hash_D, hash_E]
    size: 20 MB
    modified_by: user_alice

  Version 2 (created 2026-04-22, chunks 1 and 2 changed):
    chunk_list: [hash_A, hash_F, hash_G, hash_D, hash_E]
    size: 20 MB
    modified_by: user_alice

  Version 3 (created 2026-04-25, chunk 4 changed, file grew):
    chunk_list: [hash_A, hash_F, hash_G, hash_D, hash_H, hash_I]
    size: 24 MB
    modified_by: user_bob

Storage Analysis:
  Unique blocks across all 3 versions:
    hash_A (shared by v1, v2, v3)  -> ref_count = 3
    hash_B (v1 only)               -> ref_count = 1
    hash_C (v1 only)               -> ref_count = 1
    hash_D (shared by v1, v2, v3)  -> ref_count = 3
    hash_E (shared by v1, v2)      -> ref_count = 2
    hash_F (shared by v2, v3)      -> ref_count = 2
    hash_G (shared by v2, v3)      -> ref_count = 2
    hash_H (v3 only)               -> ref_count = 1
    hash_I (v3 only)               -> ref_count = 1

  Total unique blocks: 9
  Total storage: 9 * 4 MB = 36 MB

  Naive approach (full copy per version): 20 + 20 + 24 = 64 MB
  Copy-on-write approach: 36 MB (44% savings over naive)
```

### 13.2 Version Diff

```
Comparing two versions to see what changed:

  Version 1 chunks: [hash_A, hash_B, hash_C, hash_D, hash_E]
  Version 3 chunks: [hash_A, hash_F, hash_G, hash_D, hash_H, hash_I]

  Diff algorithm:
    (1) Compare chunk-by-chunk (by index alignment):
        Index 0: hash_A == hash_A  -> UNCHANGED
        Index 1: hash_B != hash_F  -> MODIFIED
        Index 2: hash_C != hash_G  -> MODIFIED
        Index 3: hash_D == hash_D  -> UNCHANGED
        Index 4: hash_E != hash_H  -> MODIFIED
        Index 5: (none)  != hash_I -> ADDED
    
    (2) Summary:
        Unchanged: chunks 0, 3 (8 MB)
        Modified:  chunks 1, 2, 4 (12 MB)
        Added:     chunk 5 (4 MB)
    
    (3) To download version diff (for rollback):
        Need to fetch: hash_B, hash_C, hash_E (from version 1)
        Can reuse from current: hash_A, hash_D (unchanged)

Version Diff Response:
{
  "fromVersion": 3,
  "toVersion":   1,
  "summary": {
    "unchanged": 2,
    "modified":  3,
    "added":     0,
    "removed":   1
  },
  "chunks": [
    { "index": 0, "status": "UNCHANGED" },
    { "index": 1, "status": "MODIFIED",  "oldHash": "hash_F", "newHash": "hash_B" },
    { "index": 2, "status": "MODIFIED",  "oldHash": "hash_G", "newHash": "hash_C" },
    { "index": 3, "status": "UNCHANGED" },
    { "index": 4, "status": "MODIFIED",  "oldHash": "hash_H", "newHash": "hash_E" },
    { "index": 5, "status": "REMOVED",   "oldHash": "hash_I" }
  ]
}
```

### 13.3 Rollback

```
Rollback: revert file to a previous version.

Rollback from Version 3 to Version 1:

  Current (v3): [hash_A, hash_F, hash_G, hash_D, hash_H, hash_I]
  Target  (v1): [hash_A, hash_B, hash_C, hash_D, hash_E]

  Rollback is NOT destructive. It creates a NEW version (v4)
  whose chunk list matches the target version (v1).

  Version 4 (rollback to v1):
    chunk_list: [hash_A, hash_B, hash_C, hash_D, hash_E]
    size: 20 MB
    modified_by: user_alice
    change_note: "Rolled back to version 1"

  Why create a new version instead of deleting v2 and v3?
    - Non-destructive: v2 and v3 still exist in history
    - User can "undo the rollback" by rolling back to v3
    - Audit trail preserved
    - No ref_count changes needed (all blocks already exist)

  Storage impact of rollback:
    - ZERO additional storage (v1's blocks already exist, ref_count > 0)
    - Only new metadata: one FileVersion record + chunk list
    - Rollback is essentially free (just a metadata operation)
```

### 13.4 Version Retention

```
Retention policy: keep last N versions (default: 30).

When version 31 is created:
  (1) Version 1 becomes eligible for purge
  (2) Versioning Service deletes FileVersion 1 and its FileChunk records
  (3) For each chunk in Version 1:
      - Decrement ref_count on the block
      - If ref_count drops to 0: block enters GC grace period
  (4) Most blocks will still have ref_count > 0
      (shared with other versions)

Version Retention Flow:

  Versioning Service          FileVersion Table        BlockStore
       |                           |                       |
       |  (1) Version 31 created   |                       |
       |  Check: count(versions    |                       |
       |  for fileId) > 30?        |                       |
       |-------------------------->|                       |
       |                           |                       |
       |  (2) YES: 31 versions     |                       |
       |  Delete oldest (v1)       |                       |
       |-------------------------->|                       |
       |                           |                       |
       |  (3) Get v1's chunk list: |                       |
       |  [hash_A, hash_B, hash_C, |                       |
       |   hash_D, hash_E]         |                       |
       |<--------------------------|                       |
       |                           |                       |
       |  (4) Decrement ref_count  |                       |
       |  for each block           |                       |
       |---------------------------------------------->    |
       |                           |                       |
       |  hash_A: 3 -> 2 (still   |                       |
       |  referenced by v2, v3)    |                       |
       |  hash_B: 1 -> 0 (only    |                       |
       |  in v1, enters GC queue)  |                       |
       |  hash_C: 1 -> 0 (GC)     |                       |
       |  hash_D: 3 -> 2 (still)  |                       |
       |  hash_E: 2 -> 1 (still)  |                       |
       |                           |                       |
       |  (5) Delete FileChunk     |                       |
       |  records for v1           |                       |
       |-------------------------->|                       |
       |                           |                       |
       |  (6) Delete FileVersion   |                       |
       |  record for v1            |                       |
       |-------------------------->|                       |

Configurable Retention:
  Free tier:    keep last 5 versions, 30-day max age
  Basic tier:   keep last 30 versions, 180-day max age
  Premium:      keep last 100 versions, 365-day max age
  Enterprise:   unlimited versions, unlimited retention
```

---

## 14. Concurrency

### 14.1 Concurrent Upload to Same File

```
Scenario: Two devices upload a new version of the same file simultaneously.

  Device A                 Metadata Service              Device B
    |                            |                           |
    |  (1) Upload new version    |                           |
    |  base_version = 5          |                           |
    |--------------------------->|                           |
    |                            |  (2) Upload new version   |
    |                            |  base_version = 5         |
    |                            |<--------------------------|
    |                            |                           |
    |                            |  (3) BEGIN TRANSACTION    |
    |                            |  SELECT current_version   |
    |                            |  FROM file                |
    |                            |  WHERE file_id = X        |
    |                            |  FOR UPDATE               |
    |                            |  (locks the row)          |
    |                            |                           |
    |                            |  (4) Device A's request   |
    |                            |  processed first:         |
    |                            |  current = 5 == base = 5  |
    |                            |  -> OK, create version 6  |
    |                            |  COMMIT                   |
    |                            |                           |
    |  (5) Success: version 6    |                           |
    |<---------------------------|                           |
    |                            |                           |
    |                            |  (6) Device B's request:  |
    |                            |  current = 6 != base = 5  |
    |                            |  -> CONFLICT              |
    |                            |                           |
    |                            |  (7) Keep Both Copies     |
    |                            |  Save as conflicted copy  |
    |                            |--------------------------->|
    |                            |                           |
    |                            |  (8) Notify Device B:     |
    |                            |  "conflict detected"      |
    |                            |--------------------------->|

Optimistic Locking Pattern:
  - No long-held locks (would block other operations)
  - Use version number as optimistic lock
  - Compare-and-swap: UPDATE ... WHERE version = expected_version
  - If 0 rows updated: conflict detected
  - Retry or save as conflicted copy
```

### 14.2 Concurrent Chunk Upload (Same Upload Session)

```
Scenario: 4 parallel upload slots writing chunks for the same file.

This is safe because:
  - Each chunk has a unique index and unique presigned URL
  - Chunks are written to independent Object Storage keys
  - No shared state between chunk uploads
  - Upload state tracker in Redis uses atomic operations:
    SADD upload:{id}:completed_chunks {chunk_index}

  Client Thread 1: PUT chunk/0 -> s3://blocks/hash_A  (independent)
  Client Thread 2: PUT chunk/1 -> s3://blocks/hash_B  (independent)
  Client Thread 3: PUT chunk/2 -> s3://blocks/hash_C  (independent)
  Client Thread 4: PUT chunk/3 -> s3://blocks/hash_D  (independent)

  No conflicts possible: different keys, different URLs.
  
  Redis tracking (atomic set operations):
    SADD upload:m3n4o5:completed 0    -> {0}
    SADD upload:m3n4o5:completed 2    -> {0, 2}
    SADD upload:m3n4o5:completed 1    -> {0, 1, 2}
    SADD upload:m3n4o5:completed 3    -> {0, 1, 2, 3}
    SCARD upload:m3n4o5:completed     -> 4 == totalChunks? YES -> ready to complete
```

### 14.3 Metadata Race Conditions

```
Scenario: User renames a file while another device is syncing it.

  Device A (rename)         Metadata Service        Device B (syncing)
       |                         |                        |
       |  (1) RENAME file_X      |                        |
       |  name = "new_name.pdf"  |                        |
       |------------------------>|                        |
       |                         |  (2) GET /sync/changes |
       |                         |  cursor = cursor_old   |
       |                         |<-----------------------|
       |                         |                        |
       |                         |  (3) Apply rename      |
       |                         |  in transaction        |
       |                         |                        |
       |  (4) Success            |                        |
       |<------------------------|                        |
       |                         |                        |
       |                         |  (5) Return changes    |
       |                         |  including rename      |
       |                         |  event for file_X      |
       |                         |----------------------->|

  This is safe because:
    - Rename is atomic (single row UPDATE in PostgreSQL)
    - Sync query happens after rename commits (read-after-write consistency)
    - If sync query happens BEFORE rename: rename will appear in NEXT sync
    - Sync cursor ensures no events are missed

Race: Move file + Delete file simultaneously
  - PostgreSQL serializable isolation prevents lost updates
  - One operation succeeds, the other sees updated state and adjusts
  - Move to deleted folder? Move applies, then delete applies (logical OR)
  - Delete then move? Delete wins, move fails with "file not found"
```

---

## 15. Scaling

### 15.1 Metadata Sharding

```
Shard metadata by hash(userId) % N shards.

Why userId?
  - Most queries are scoped to a single user (my files, my folders)
  - User's entire file tree on one shard = no cross-shard joins
  - Shared files: stored on owner's shard, referenced by share records

Shard Layout:
  +-------------+  +-------------+  +-------------+  +-------------+
  |  Shard 0    |  |  Shard 1    |  |  Shard 2    |  |  Shard N    |
  |             |  |             |  |             |  |             |
  | Users: 0-   |  | Users: 1-   |  | Users: 2-   |  | Users: N-   |
  | hash(uid)%N |  | hash(uid)%N |  | hash(uid)%N |  | hash(uid)%N |
  | = 0         |  | = 1         |  | = 2         |  | = N-1       |
  |             |  |             |  |             |  |             |
  | ~50M files  |  | ~50M files  |  | ~50M files  |  | ~50M files  |
  | ~10M folders|  | ~10M folders|  | ~10M folders|  | ~10M folders|
  +-------------+  +-------------+  +-------------+  +-------------+

  With 500M users / 10 shards = 50M users per shard
  50M users * 500 files = 25B files per shard
  25B * 500 bytes = 12.5 TB per shard (fits in PostgreSQL)

Cross-Shard Operations:
  - Sharing: ShareLink table has both owner's userId and recipient's userId
  - Solution: store ShareLink on BOTH shards (dual-write via Kafka)
  - Search across shared files: query recipient's shard for share records,
    then query owner's shard for file metadata
  - Accept eventual consistency for cross-shard share propagation (~1-2 seconds)
```

### 15.2 Object Storage Scaling

```
Object Storage (S3 / GCS) scales horizontally by design.

  2.5 EB total storage (1.5 EB after dedup):
    - S3 has no upper limit on storage
    - S3 scales to thousands of requests per second per prefix
    - Our prefix strategy (first 4 hex chars of hash) gives 65,536 prefixes
    - Each prefix handles ~5M blocks

  Upload throughput: ~3.5 GB/sec peak
    - S3 supports 5,500 PUT requests per second per prefix
    - Our 65,536 prefixes: 5,500 * 65,536 = ~360M PUTs/sec capacity
    - Actual need: ~3,500 PUTs/sec (100M uploads, 10 chunks avg, 86400 sec)
    - Massive headroom

  Download throughput: ~21 GB/sec peak
    - S3 supports 5,500 GET requests per second per prefix
    - CDN absorbs 80% of download traffic for popular files
    - S3 handles remaining 20%: ~4.2 GB/sec
    - Well within S3 capacity
```

### 15.3 CDN for Downloads

```
CDN Strategy:

  +--------------------------------------------------+
  |                    CDN Edge Nodes                  |
  |  (100+ PoPs globally)                             |
  |                                                    |
  |  Cache popular files at edge                       |
  |  TTL: 1 hour (files are immutable by hash)         |
  |  Cache hit rate: ~80% for downloads                |
  +----------------------------+-----------------------+
                               |
                          Cache MISS
                               |
                               v
  +--------------------------------------------------+
  |               Object Storage (Origin)             |
  |  s3://file-storage-blocks/                         |
  +--------------------------------------------------+

  CDN Cache Key: block hash (content-addressable = perfect cache key)
    - Same hash = same content = cache-friendly
    - File updates don't invalidate unchanged chunks in CDN
    - Dedup + CDN synergy: popular content cached and served from edge

  Popular file detection:
    - Track download count per file in Redis (sliding window, 1 hour)
    - Files with >10 downloads/hour -> prefetch to CDN
    - Files with >100 downloads/hour -> replicate to all edge PoPs

  Cost savings:
    - S3 egress: $0.09/GB
    - CDN egress: $0.02/GB (CloudFront) for committed traffic
    - 80% of 600 TB/day through CDN: 480 TB * ($0.09 - $0.02) = $33,600/day savings
    - Annual CDN savings: ~$12.3M
```

### 15.4 Sync Service Scaling

```
150M concurrent long-poll connections:

  Scaling strategy:
    - Each Sync Service instance handles ~50,000 connections
    - 150M / 50,000 = 3,000 instances
    - Stateless: connection state in Redis, no sticky sessions needed
    - Auto-scale based on active connection count

  Kafka consumer groups:
    - Sync Service instances form a Kafka consumer group
    - Each instance consumes a subset of file-change events
    - Instance receives event -> looks up which user is affected
    - Checks Redis for active long-poll connections for that user
    - Responds to the long-poll with the change

  Connection scaling:
    +------------------------------------------------------------------+
    | Active Connections | Sync Instances | Kafka Partitions | Redis    |
    +------------------------------------------------------------------+
    | 1M (launch)        | 20             | 50               | 3 nodes  |
    | 10M (growth)       | 200            | 100              | 10 nodes |
    | 50M (scale)        | 1,000          | 200              | 30 nodes |
    | 150M (full scale)  | 3,000          | 500              | 50 nodes |
    +------------------------------------------------------------------+
```

---

## 16. Database Choice

### 16.1 PostgreSQL -- File Metadata, Folders, Versions, Permissions

```
Why PostgreSQL?

  +---------------------------+----------------------------------------------+
  | Reason                    | Details                                      |
  +---------------------------+----------------------------------------------+
  | ACID transactions         | File create + version create + chunk link    |
  |                           | must be atomic. Partial writes corrupt the   |
  |                           | file tree.                                   |
  +---------------------------+----------------------------------------------+
  | Strong consistency        | File tree is the source of truth. Stale      |
  |                           | reads cause sync inconsistencies (missing    |
  |                           | files, wrong versions, broken paths).        |
  +---------------------------+----------------------------------------------+
  | Relational model          | File-Folder hierarchy is inherently          |
  |                           | relational. Foreign keys enforce integrity.  |
  |                           | Materialized paths for folder queries.       |
  +---------------------------+----------------------------------------------+
  | Rich indexing             | Composite indexes on (user_id, folder_id),   |
  |                           | (file_id, version_num), (block_hash).        |
  |                           | B-tree indexes for range queries.            |
  +---------------------------+----------------------------------------------+
  | Proven at scale           | Shardable by userId. Read replicas for       |
  |                           | listing and search queries.                  |
  +---------------------------+----------------------------------------------+

Tables stored in PostgreSQL:
  - File              (250B rows, ~125 TB, sharded by user_id)
  - FileVersion       (750B rows, ~150 TB, co-located with File)
  - FileChunk         (2T rows,   ~200 TB, co-located with FileVersion)
  - Folder            (50B rows,  ~25 TB,  sharded by user_id)
  - ShareLink         (10B rows,  ~5 TB,   dual-write to both shards)
  - BlockStore        (300B rows, ~60 TB,  separate cluster, sharded by hash)
  - StorageQuota      (500M rows, ~50 GB,  co-located with User)
  - User              (500M rows, ~100 GB, sharded by user_id)

Sharding strategy:
  Metadata cluster: hash(user_id) % 10 -> 10 shards
  Each shard: ~50M users, ~25B files, ~12.5 TB data
  
  Block index cluster: hash(block_hash) % 20 -> 20 shards
  Each shard: ~15B blocks, ~3 TB data
```

### 16.2 S3 / Object Storage -- File Chunks (Block Content)

```
Why Object Storage (S3)?

  +---------------------------+----------------------------------------------+
  | Reason                    | Details                                      |
  +---------------------------+----------------------------------------------+
  | Designed for blobs        | Files are unstructured binary data. Object   |
  |                           | storage is purpose-built for this.           |
  +---------------------------+----------------------------------------------+
  | Unlimited scale           | S3 has no storage limit. 1.5 EB+ is routine |
  |                           | for large customers.                         |
  +---------------------------+----------------------------------------------+
  | 11 nines durability       | 99.999999999% durability. Files are          |
  |                           | replicated across 3+ AZs automatically.      |
  +---------------------------+----------------------------------------------+
  | Content-addressable       | S3 keys = SHA-256 hashes. Perfect for our    |
  |                           | dedup model. Immutable objects.              |
  +---------------------------+----------------------------------------------+
  | Presigned URLs            | Clients upload directly to S3, bypassing our |
  |                           | servers. Reduces bandwidth and CPU.          |
  +---------------------------+----------------------------------------------+
  | Lifecycle policies        | Auto-transition cold blocks to S3 Glacier    |
  |                           | for cost savings on old versions.            |
  +---------------------------+----------------------------------------------+

Storage tiers:
  Hot (recently accessed):     S3 Standard          ~$0.023/GB/month
  Warm (old versions):         S3 Infrequent Access ~$0.0125/GB/month
  Cold (archived versions):    S3 Glacier           ~$0.004/GB/month
  
  Estimated distribution:
    Hot:  20% of 1.5 EB = 300 PB * $0.023 = $6.9M/month
    Warm: 50% of 1.5 EB = 750 PB * $0.0125 = $9.4M/month
    Cold: 30% of 1.5 EB = 450 PB * $0.004 = $1.8M/month
    Total: ~$18.1M/month for storage
    
    Without dedup: ~$30M/month (2.5 EB)
    With dedup + tiering: ~$18.1M/month (1.5 EB, tiered)
    Savings: ~$12M/month = ~$144M/year
```

### 16.3 Redis -- Upload State, Sync Cursors, Caches

```
Why Redis?

  +---------------------------+----------------------------------------------+
  | Reason                    | Details                                      |
  +---------------------------+----------------------------------------------+
  | Sub-millisecond latency   | Upload state checks, sync cursor lookups,    |
  |                           | and permission cache must be fast.           |
  +---------------------------+----------------------------------------------+
  | TTL / auto-expiry         | Upload sessions expire after 24h. Permission |
  |                           | cache expires after 60s. No manual cleanup.  |
  +---------------------------+----------------------------------------------+
  | Atomic set operations     | SADD for tracking uploaded chunks. SCARD for |
  |                           | checking completion. No race conditions.     |
  +---------------------------+----------------------------------------------+
  | Bloom filter hosting      | Redis supports probabilistic data structures |
  |                           | (RedisBloom module) for dedup filter.        |
  +---------------------------+----------------------------------------------+
  | Pub/Sub (optional)        | Can broadcast sync notifications to           |
  |                           | co-located Sync Service instances.           |
  +---------------------------+----------------------------------------------+

Redis data structures:
  - upload:{uploadId}              (Hash)       -- upload session state
  - upload:{uploadId}:completed    (Set)        -- completed chunk indices
  - sync_cursor:{userId}:{deviceId}(String)     -- current sync cursor
  - sync_conn:{userId}:{deviceId}  (String)     -- which Sync Service instance
  - perm:{resourceId}:{userId}     (String)     -- permission cache (60s TTL)
  - quota:{userId}                 (String)     -- storage quota cache
  - download_count:{fileId}        (Sorted Set) -- download frequency tracking
  - dedup_bloom                    (RedisBloom) -- Bloom filter for block hashes

Memory estimate:
  Upload state:    ~100K active uploads * 2 KB = ~200 MB
  Sync cursors:    150M devices * 200 bytes = ~30 GB
  Permission cache: ~50M cached entries * 100 bytes = ~5 GB
  Bloom filter:     ~430 GB (dedicated cluster)
  Download counts:  ~10M tracked files * 100 bytes = ~1 GB

  Total Redis: ~470 GB across ~50 nodes
```

### 16.4 Kafka -- Events, Sync Notifications, Audit

```
Why Kafka?

  +---------------------------+----------------------------------------------+
  | Reason                    | Details                                      |
  +---------------------------+----------------------------------------------+
  | Durable event log         | File events persisted for replay. Sync       |
  |                           | Service can replay missed events on restart. |
  +---------------------------+----------------------------------------------+
  | Ordered delivery          | Partition by userId ensures all of a user's  |
  |                           | file events are ordered.                     |
  +---------------------------+----------------------------------------------+
  | Fan-out to consumers      | Multiple consumers: Sync Service, Notification|
  |                           | Service, Audit Service, Analytics.           |
  +---------------------------+----------------------------------------------+
  | Decoupling                | Upload Service publishes events without      |
  |                           | knowing who consumes them.                   |
  +---------------------------+----------------------------------------------+
  | Backpressure handling     | If Sync Service is slow, events buffer in    |
  |                           | Kafka. No data loss.                         |
  +---------------------------+----------------------------------------------+

Kafka topics:
  - file-events:
      Partitions: 500 (by hash(userId) % 500)
      Retention: 7 days
      Events: file_created, file_modified, file_deleted, file_moved,
              file_renamed, file_shared, file_restored
      Throughput: ~50K events/sec

  - sync-notifications:
      Partitions: 200
      Retention: 24 hours
      Events: device_sync_request, sync_complete
      Throughput: ~75K events/sec

  - block-events:
      Partitions: 100
      Retention: 7 days
      Events: block_created, block_ref_incremented, block_ref_decremented,
              block_gc_eligible, block_deleted
      Throughput: ~100K events/sec

  - audit-log:
      Partitions: 50
      Retention: 365 days
      Events: all user actions for compliance
      Throughput: ~200K events/sec

Kafka sizing:
  ~425K events/sec * 500 bytes/event = ~210 MB/sec
  10 brokers with replication factor 3
  Each broker: ~63 MB/sec write throughput
```

---

## 17. CAP Theorem

```
+--------------------+------+------+------+------------------------------------------+
| Component          | C    | A    | P    | Strategy                                 |
+--------------------+------+------+------+------------------------------------------+
| File Metadata      | YES  | yes  | YES  | CP: File tree MUST be consistent.        |
| (files, folders,   |      |      |      | If two devices see different file trees,  |
| versions)          |      |      |      | sync is broken. During a partition,       |
|                    |      |      |      | reject writes rather than allow           |
|                    |      |      |      | inconsistent file trees.                  |
+--------------------+------+------+------+------------------------------------------+
| Block Storage      | yes  | YES  | YES  | AP: Object storage (S3) is AP by         |
| (file chunks in S3)|      |      |      | design. A chunk uploaded during a         |
|                    |      |      |      | partition may not be immediately           |
|                    |      |      |      | readable from all AZs. S3 provides        |
|                    |      |      |      | read-after-write consistency now, but     |
|                    |      |      |      | the design is fundamentally AP.           |
+--------------------+------+------+------+------------------------------------------+
| Sync Notifications | no   | YES  | YES  | AP: If a sync notification is delayed     |
|                    |      |      |      | by 30 seconds during a partition, that's  |
|                    |      |      |      | acceptable. Better to eventually sync     |
|                    |      |      |      | than to block all sync operations.        |
+--------------------+------+------+------+------------------------------------------+
| Permissions        | YES  | yes  | YES  | CP: Permission checks must be             |
|                    |      |      |      | consistent. A revoked user must NOT       |
|                    |      |      |      | be able to download files. Short-lived    |
|                    |      |      |      | cache (60s TTL) is acceptable, but        |
|                    |      |      |      | better to fail closed during partitions.  |
+--------------------+------+------+------+------------------------------------------+
| Dedup Index        | YES  | yes  | YES  | CP: Dedup index must be consistent.       |
| (Bloom + BlockStore)|      |      |      | A false "not exists" would cause           |
|                    |      |      |      | duplicate storage (wastes money, not      |
|                    |      |      |      | data loss). A false "exists" would        |
|                    |      |      |      | skip upload of a unique chunk (DATA       |
|                    |      |      |      | LOSS). Consistency is critical.           |
+--------------------+------+------+------+------------------------------------------+
| Storage Quota      | yes  | YES  | YES  | AP: Quota can be slightly stale.          |
|                    |      |      |      | If a user briefly exceeds quota by        |
|                    |      |      |      | 100 MB during a partition, that's         |
|                    |      |      |      | acceptable. Reconcile later.              |
+--------------------+------+------+------+------------------------------------------+

(YES = prioritized, yes = supported but secondary, no = sacrificed)

Key insight for interviews:
  "File metadata is CP because the file tree is the source of truth.
  If one device sees file X in /folder_A and another device sees it in /folder_B,
  sync is completely broken. Block storage is AP because S3 is designed for
  availability -- a missing chunk is retried automatically. Sync notifications
  are AP because a 30-second delay in sync is acceptable, but blocking all
  sync during a partition would make the product unusable."
```

---

## 18. Cloud Services

```
+----------------------------+------------------+--------------------------------+
| Component                  | AWS              | GCP                            |
+----------------------------+------------------+--------------------------------+
| API Gateway / LB           | ALB + API        | Cloud Load Balancing (L7)      |
|                            | Gateway          |                                |
+----------------------------+------------------+--------------------------------+
| Upload Service             | ECS Fargate      | Cloud Run / GKE                |
|                            | or EKS           |                                |
+----------------------------+------------------+--------------------------------+
| Download Service           | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Metadata Service           | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Sync Service               | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Sharing Service            | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Dedup Service              | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Versioning Service         | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Trash / Purge Service      | ECS Fargate +    | Cloud Run + Cloud Scheduler    |
|                            | EventBridge      |                                |
+----------------------------+------------------+--------------------------------+
| Object Storage (chunks)    | S3               | Cloud Storage (GCS)            |
+----------------------------+------------------+--------------------------------+
| PostgreSQL (metadata)      | RDS PostgreSQL   | Cloud SQL for PostgreSQL       |
|                            | or Aurora         | or AlloyDB                     |
+----------------------------+------------------+--------------------------------+
| Redis (cache, state)       | ElastiCache      | Memorystore for Redis          |
|                            | (Redis)          |                                |
+----------------------------+------------------+--------------------------------+
| Kafka (events)             | MSK (Managed     | Pub/Sub (or Confluent          |
|                            | Streaming for    | Cloud on GCP)                  |
|                            | Kafka)           |                                |
+----------------------------+------------------+--------------------------------+
| CDN (downloads)            | CloudFront       | Cloud CDN                      |
+----------------------------+------------------+--------------------------------+
| Bloom Filter               | ElastiCache +    | Memorystore + RedisBloom       |
|                            | RedisBloom module|                                |
+----------------------------+------------------+--------------------------------+
| Monitoring                 | CloudWatch       | Cloud Monitoring               |
+----------------------------+------------------+--------------------------------+
| Background Jobs            | Step Functions   | Cloud Workflows                |
| (GC, purge, retention)     | + Lambda         | + Cloud Functions              |
+----------------------------+------------------+--------------------------------+
```

---

## 19. Tradeoffs Summary

```
+-----+------------------------------------+------------------------------------+
| #   | Decision                           | Tradeoff                           |
+-----+------------------------------------+------------------------------------+
| T1  | 4 MB chunk size over 1 MB or 64 MB | 4 MB: good balance of dedup        |
|     |                                    | granularity, resume granularity,   |
|     |                                    | and metadata overhead. 1 MB: too   |
|     |                                    | many chunks (high overhead). 64 MB:|
|     |                                    | poor dedup, large resume penalty.  |
+-----+------------------------------------+------------------------------------+
| T2  | Block-level dedup over file-level   | Block-level: 30-50% savings vs     |
|     |                                    | 10-15% for file-level. But: higher |
|     |                                    | CPU cost (hash every chunk) and    |
|     |                                    | complex reference counting. CPU    |
|     |                                    | cost is negligible vs storage      |
|     |                                    | savings ($23M/month).              |
+-----+------------------------------------+------------------------------------+
| T3  | Content-addressable storage (CAS)  | CAS: dedup is free (same hash =   |
|     | over path-based storage            | same block). But: garbage          |
|     |                                    | collection is complex (reference   |
|     |                                    | counting + grace period). Path-    |
|     |                                    | based is simpler but no dedup.     |
+-----+------------------------------------+------------------------------------+
| T4  | Presigned URLs (direct to S3)      | Presigned: client uploads directly |
|     | over proxy uploads through servers  | to S3, bypasses our servers.       |
|     |                                    | But: less control over upload      |
|     |                                    | process. Proxy: full control but   |
|     |                                    | 2x bandwidth (client->server,      |
|     |                                    | server->S3). Presigned wins at     |
|     |                                    | scale (100M uploads/day).          |
+-----+------------------------------------+------------------------------------+
| T5  | Long polling over WebSocket for    | Long poll: stateless, easy to      |
|     | sync notifications                 | scale, works through proxies.      |
|     |                                    | But: higher latency (up to 30s).   |
|     |                                    | WebSocket: real-time but stateful, |
|     |                                    | needs sticky sessions. File sync   |
|     |                                    | tolerates 10s delay -> long poll.  |
+-----+------------------------------------+------------------------------------+
| T6  | Keep Both Copies conflict          | Keep both: no data loss, user      |
|     | resolution over last-writer-wins   | decides. But: creates "conflicted  |
|     |                                    | copy" clutter. LWW: simpler but    |
|     |                                    | silently loses data. For file      |
|     |                                    | storage, data loss is unacceptable.|
+-----+------------------------------------+------------------------------------+
| T7  | Copy-on-write versioning over      | CoW: unchanged chunks shared       |
|     | full-copy versioning               | across versions (77% savings for   |
|     |                                    | 30 versions). But: complex ref     |
|     |                                    | counting and GC. Full-copy:        |
|     |                                    | simple but 30x storage per file.   |
+-----+------------------------------------+------------------------------------+
| T8  | Separate metadata and blob storage | Metadata (PostgreSQL): small rows, |
|     | over unified storage               | relational queries, ACID. Blobs    |
|     |                                    | (S3): large objects, no queries,   |
|     |                                    | horizontal scale. Different access |
|     |                                    | patterns demand different stores.  |
|     |                                    | Unified: simpler ops but cannot    |
|     |                                    | optimize for either workload.      |
+-----+------------------------------------+------------------------------------+
| T9  | Fixed-size chunking for upload +   | Fixed: simple, fast, predictable.  |
|     | variable-size (Rabin) for sync     | Variable: better dedup on edits    |
|     |                                    | (insertion doesn't shift all       |
|     |                                    | chunks). Hybrid: best of both.     |
|     |                                    | But: two chunking strategies =     |
|     |                                    | more implementation complexity.    |
+-----+------------------------------------+------------------------------------+
| T10 | PostgreSQL over DynamoDB for       | PostgreSQL: ACID transactions for  |
|     | metadata                           | file+version+chunk creation. Rich  |
|     |                                    | queries (folder listing, search).  |
|     |                                    | But: requires manual sharding.     |
|     |                                    | DynamoDB: auto-scaling but limited |
|     |                                    | query patterns, no multi-item      |
|     |                                    | transactions.                      |
+-----+------------------------------------+------------------------------------+
| T11 | Bloom filter for dedup lookup      | Bloom: O(1) lookup, fits in        |
|     | over direct DB lookup              | memory, eliminates 99.9% of DB    |
|     |                                    | lookups. But: ~430 GB memory,      |
|     |                                    | false positives (0.1%) cause       |
|     |                                    | unnecessary DB queries. Direct DB: |
|     |                                    | accurate but 300B+ lookups/day     |
|     |                                    | would overwhelm PostgreSQL.        |
+-----+------------------------------------+------------------------------------+
| T12 | CP for metadata over AP            | Consistency is non-negotiable for  |
|     |                                    | file tree integrity. But: writes   |
|     |                                    | fail during partitions. Mitigated: |
|     |                                    | partitions are rare in managed     |
|     |                                    | cloud. AP for sync: a delayed      |
|     |                                    | notification is better than no     |
|     |                                    | sync at all.                       |
+-----+------------------------------------+------------------------------------+
```

---

## 20. Interview Talking Points

### Opening Statement (30 seconds)

> "I'll design a cloud file storage system like Google Drive or Dropbox. The system serves 500 million users with 100 million daily active users, storing 2.5 exabytes total. The core challenges are **chunked upload** for large files over unreliable networks, **content-addressable deduplication** to save 30-50% storage costs, **file sync** across devices with conflict resolution, and **versioning** with copy-on-write efficiency. I'll separate metadata (PostgreSQL) from blob storage (S3) because they have fundamentally different access patterns and scaling needs."

### Top 10 Points to Hit

```
+----+-----------------------------------+---------------------------------------------+
| #  | Talking Point                     | Key Phrase                                   |
+----+-----------------------------------+---------------------------------------------+
| 1  | Metadata vs blob separation       | "PostgreSQL for metadata (relational,        |
|    |                                   | ACID, queryable). S3 for chunks (blobs,     |
|    |                                   | horizontal scale, 11 nines durability).     |
|    |                                   | Different access patterns, different stores."|
+----+-----------------------------------+---------------------------------------------+
| 2  | Chunked upload (4 MB chunks)      | "Split into 4 MB chunks. Upload in parallel |
|    |                                   | (4 concurrent). Resumable on failure. SHA-  |
|    |                                   | 256 checksum per chunk. Presigned URLs to   |
|    |                                   | upload directly to S3."                      |
+----+-----------------------------------+---------------------------------------------+
| 3  | Content-addressable dedup         | "THE star. Hash(chunk content) = storage    |
|    | (the interview star)              | key. If hash exists, skip upload. Block-    |
|    |                                   | level dedup: 30-50% savings. Saves $23M/   |
|    |                                   | month at our scale. Bloom filter for fast   |
|    |                                   | lookup."                                     |
+----+-----------------------------------+---------------------------------------------+
| 4  | Delta sync                        | "Only transfer changed chunks, not entire   |
|    |                                   | file. 100 MB file with 2 changed chunks =  |
|    |                                   | 8 MB transfer. Rabin fingerprint for        |
|    |                                   | finding changed boundaries."                 |
+----+-----------------------------------+---------------------------------------------+
| 5  | Conflict resolution               | "Keep Both Copies (Dropbox approach). No    |
|    |                                   | data loss. Create 'conflicted copy' file.   |
|    |                                   | User resolves manually. Better than last-   |
|    |                                   | writer-wins which silently loses data."      |
+----+-----------------------------------+---------------------------------------------+
| 6  | Copy-on-write versioning          | "Versions share unchanged chunks. 30        |
|    |                                   | versions with 20% change per version =      |
|    |                                   | 77% savings over full-copy. Rollback is     |
|    |                                   | free (just metadata update)."                |
+----+-----------------------------------+---------------------------------------------+
| 7  | Reference counting + GC           | "Each block tracks ref_count. Decrement     |
|    |                                   | on version delete. GC after 7-day grace     |
|    |                                   | period when ref_count = 0. Prevents race    |
|    |                                   | conditions with concurrent uploads."         |
+----+-----------------------------------+---------------------------------------------+
| 8  | Long polling for sync             | "Long poll over WebSocket for file sync.    |
|    |                                   | Stateless, easy to scale, 150M connections. |
|    |                                   | File sync tolerates 10s latency. Sync       |
|    |                                   | cursor = opaque token for position."         |
+----+-----------------------------------+---------------------------------------------+
| 9  | CDN for popular downloads         | "80% of downloads served from CDN edge.     |
|    |                                   | Block hash = perfect cache key (immutable). |
|    |                                   | Saves $12M/year in egress costs."            |
+----+-----------------------------------+---------------------------------------------+
| 10 | CP for metadata, AP for sync      | "File tree must be consistent (CP). Sync    |
|    |                                   | notifications can be delayed (AP). Dedup    |
|    |                                   | index must be consistent (a false 'exists'  |
|    |                                   | = data loss, which is catastrophic)."        |
+----+-----------------------------------+---------------------------------------------+
```

### Common Follow-up Questions

```
Q: "How do you handle a 10 GB file upload that takes 30 minutes?"
A: "Chunked upload with resume. File split into 2,560 chunks of 4 MB.
   Upload state tracked in Redis with 24-hour TTL. If the connection drops,
   client calls GET /upload/{id}/status to find which chunks are missing,
   gets new presigned URLs, and uploads only the remaining chunks.
   We've seen this recover from 10+ network failures on a single upload."

Q: "What if two users upload the same file?"
A: "That's exactly what dedup handles. User A uploads report.pdf (10 chunks).
   User B uploads the same file. During init, Upload Service sends chunk
   hashes to Dedup Service. All 10 hashes already exist -> 0 chunks need
   upload. We just create metadata pointing to existing blocks and increment
   ref_counts. Upload completes in milliseconds with zero data transfer."

Q: "How do you handle a user with 1 million files?"
A: "Pagination is critical. GET /folders/{id}/contents uses cursor-based
   pagination (pageToken, limit=50). Folder listing queries use the composite
   index (user_id, folder_id) for O(log N) lookup. Search uses a separate
   Elasticsearch index for full-text search on file names and metadata.
   The user's entire file tree is on one shard (sharded by userId), so
   no cross-shard queries for their own files."

Q: "What happens if the dedup Bloom filter becomes corrupt?"
A: "The Bloom filter is a performance optimization, not a correctness
   requirement. If it's corrupt, we rebuild it by scanning the BlockStore
   table (300B rows, takes ~24 hours). During rebuild, every hash check
   falls through to PostgreSQL (slower but correct). We can also maintain
   a backup Bloom filter updated asynchronously."

Q: "How do you handle file encryption?"
A: "Two approaches: server-side encryption (SSE) and client-side encryption.
   SSE: S3 encrypts at rest with AES-256. Transparent to our system.
   Dedup still works because we encrypt after dedup check.
   Client-side: user encrypts before upload. Dedup BREAKS because
   encrypted data is random (no two encryptions of the same file match).
   Tradeoff: security vs storage savings. Enterprise customers may
   require client-side encryption and accept the storage cost."

Q: "How do you handle cross-region sync for global users?"
A: "Metadata: primary region with read replicas in other regions.
   Writes go to primary (strong consistency). Reads can go to replicas
   (eventual consistency, ~100ms lag). Blocks: S3 Cross-Region Replication
   for frequently accessed blocks. CDN for downloads. User's 'home region'
   determined by registration location -- their metadata shard lives there."

Q: "What about storage cost optimization beyond dedup?"
A: "Three strategies beyond dedup:
   (1) Tiered storage: move old versions to S3 Infrequent Access (45% cheaper)
       and eventually S3 Glacier (83% cheaper) based on access patterns.
   (2) Compression: compress chunks before storage. Text files compress 60-80%.
       Binary files (images, videos) compress <5% so skip them.
   (3) Smaller chunk size for tiny files: files <4 MB stored as a single block
       without chunking overhead. Saves metadata for the 80% of files that
       are <4 MB."
```

### Complexity Cheat Sheet

```
+-----------------------------------+-------------------+----------------------------+
| Operation                         | Time Complexity   | Notes                      |
+-----------------------------------+-------------------+----------------------------+
| Upload small file (<4 MB)         | O(1) + network    | Single PUT to S3           |
| Upload chunked (N chunks)         | O(N/P) + network  | P = parallel slots (4)     |
| Dedup check (1 chunk)             | O(1)              | Bloom filter + DB lookup   |
| Dedup check (N chunks)            | O(N)              | Batch Bloom + batch DB     |
| Download file (M chunks)          | O(M) + network    | Parallel chunk fetch       |
| List folder contents              | O(log N + K)      | B-tree index, K results    |
| Search by name                    | O(log N)          | Index on (userId, name)    |
| Permission check (cached)         | O(1)              | Redis lookup               |
| Permission check (uncached)       | O(D)              | D = folder depth (walk up) |
| Create version                    | O(C)              | C = chunks in file         |
| Version diff                      | O(C)              | Compare chunk lists        |
| Rollback                          | O(1) metadata     | Just copy chunk list       |
| Sync (get changes since cursor)   | O(K)              | K = number of changes      |
| SHA-256 hash (4 MB chunk)         | O(1) ~8ms         | Fixed chunk size           |
| Rabin fingerprint (N bytes)       | O(N)              | Rolling hash               |
| Garbage collection scan           | O(B)              | B = blocks with ref=0     |
+-----------------------------------+-------------------+----------------------------+
```

### Architecture Diagram for Whiteboard (Simplified)

```
Draw this on the whiteboard in the first 3 minutes:

  +--------+     +--------+     +--------+
  |Desktop |     | Phone  |     | Web    |
  | Client |     | Client |     | Client |
  +---+----+     +---+----+     +---+----+
      |              |              |
      | HTTPS        | HTTPS        | HTTPS
      v              v              v
  +--------------------------------------+
  |         API Gateway / LB             |
  +--+------+------+------+------+------+
     |      |      |      |      |
     v      v      v      v      v
  +------+ +------+ +----+ +----+ +------+
  |Upload| |Down- | |Meta| |Sync| |Share |
  |Svc   | |load  | |data| |Svc | |Svc   |
  +--+---+ +--+---+ +--+-+ +-+--+ +------+
     |        |        |     |
     v        v        v     v
  +------+  +------+  +--------+  +-------+
  |Dedup |  | CDN  |  |Postgres|  | Kafka |
  |Svc   |  |      |  |(meta)  |  |(events|
  +--+---+  +--+---+  +--------+  +-------+
     |         |
     v         v
  +--------------------------+
  |    S3 / Object Storage   |
  |    (file chunks, 1.5 EB) |
  +--------------------------+
  
  +-------+
  | Redis |
  |(cache,|
  | state)|
  +-------+

Then walk through the numbered flow:
  (1) Client splits file into 4 MB chunks, computes SHA-256 per chunk
  (2) Client sends hashes to Upload Service
  (3) Upload Service checks Dedup Service: which chunks already exist?
  (4) Dedup returns: "upload only chunks 3, 7, 9" (others already stored)
  (5) Client uploads only new chunks directly to S3 via presigned URLs
  (6) Upload Service creates file metadata in PostgreSQL
  (7) Kafka publishes file_created event
  (8) Sync Service notifies user's other devices via long poll
  (9) Other devices download only new/changed chunks from S3/CDN

KEY INSIGHT (say this explicitly):
  "The same block-level dedup mechanism powers THREE features:
   (1) Cross-user dedup (Bob uploads same file as Alice -> 0 chunks uploaded)
   (2) Delta sync (only changed chunks transferred between devices)
   (3) Copy-on-write versioning (versions share unchanged chunks)
   One mechanism, three benefits. This is the elegance of the design."
```

---

*This design covers the full scope of a cloud file storage system at interview depth. The star of this interview is Section 11 (Deduplication Deep Dive) -- practice walking through the content-addressable storage example on a whiteboard until it's second nature. The key insight is that dedup, delta sync, and versioning all benefit from the same block-level architecture. The separation of metadata (PostgreSQL) and blob storage (S3) is the architectural foundation that makes everything else possible. This is Project 15/15 -- the final system design in this series.*
