# High-Level Design: Real-Time Collaboration Tool (Google Docs / Notion / Figma)

> **Difficulty:** HARD | **Interview Time:** 40-50 minutes | **Focus:** Operational Transform, CRDT, WebSocket, conflict resolution, real-time sync, causal consistency

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
10. [Operational Transform (OT) Deep Dive](#10-operational-transform-ot-deep-dive)
11. [CRDT Deep Dive](#11-crdt-deep-dive)
12. [Cursor & Presence](#12-cursor--presence)
13. [Version History](#13-version-history)
14. [Concurrency](#14-concurrency)
15. [Scaling](#15-scaling)
16. [Database Choice](#16-database-choice)
17. [CAP Theorem](#17-cap-theorem)
18. [Cloud Services](#18-cloud-services)
19. [Tradeoffs Summary](#19-tradeoffs-summary)
20. [Interview Talking Points](#20-interview-talking-points)

---

## 1. Problem Statement

Design a **Real-Time Collaboration Tool** (like Google Docs, Notion, or Figma) where multiple users can simultaneously edit the same document, see each other's cursors and selections in real-time, and have all edits merge correctly without conflicts -- at a scale of 100 million documents with 10 million daily active users.

**Why is it needed?**

- Real-time collaboration has become the default expectation for productivity tools. Google Docs alone has over 1 billion users, and teams expect sub-100ms sync latency between editors.
- The core engineering challenge is **conflict resolution**: when two users type at the same position at the same time, the system must deterministically merge their edits so every client converges to the same final document state -- without data loss, without manual merge dialogs, and without locking.
- This problem has deep roots in distributed systems theory. Google Docs uses **Operational Transform (OT)**, which Google pioneered with Google Wave in 2009. Newer tools like Figma and Notion use **CRDTs (Conflict-free Replicated Data Types)**, which offer stronger theoretical guarantees but come with different tradeoffs.
- The real-time aspect adds a WebSocket dimension: the system must maintain millions of persistent connections, broadcast edits with minimal latency, and handle ungraceful disconnections.
- Beyond text editing, the system must track cursor positions, user presence ("who's online"), version history, comments, and permissions -- all in real-time.

**Core Workflow -- Collaborative Editing:**

```
Two users (Alice and Bob) are editing the same document simultaneously.

Document state: "Hello World"

(1)  Alice connects to document via WebSocket (wss://collab.example.com/ws/doc/doc_123)
(2)  Bob connects to the same document via a separate WebSocket connection
(3)  Server sends both users the current document state + revision number (rev=5)
(4)  Alice types "Beautiful " at position 6 (between "Hello " and "World")
     - Alice's local state: "Hello Beautiful World"
     - Alice sends operation: INSERT(pos=6, text="Beautiful ", baseRevision=5)
(5)  Concurrently, Bob deletes "World" (positions 6-10) and types "Earth"
     - Bob's local state: "Hello Earth"
     - Bob sends operation: DELETE(pos=6, len=5, baseRevision=5) + INSERT(pos=6, text="Earth", baseRevision=5)
(6)  Server receives Alice's operation first (network timing)
     - Server applies INSERT(pos=6, text="Beautiful ") to revision 5
     - Server state: "Hello Beautiful World" (rev=6)
     - Server broadcasts Alice's op to Bob
(7)  Server receives Bob's operations (based on revision 5, but server is now at 6)
     - Server must TRANSFORM Bob's operations against Alice's operation
     - Bob's DELETE(pos=6, len=5) is transformed: position shifts to 16 (6 + 10 chars from Alice's insert)
     - Transformed: DELETE(pos=16, len=5) -- deletes "World" from "Hello Beautiful World"
     - Bob's INSERT(pos=6, text="Earth") is transformed: position shifts to 16
     - Transformed: INSERT(pos=16, text="Earth")
     - Server state: "Hello Beautiful Earth" (rev=7)
     - Server broadcasts transformed ops to Alice
(8)  Alice receives Bob's transformed ops, applies them to her local state
     - Alice's state: "Hello Beautiful Earth" (rev=7)
(9)  Bob receives Alice's ops, transforms his pending ops, applies
     - Bob's state: "Hello Beautiful Earth" (rev=7)
(10) Both clients converge to the same state: "Hello Beautiful Earth"
```

**Core Workflow -- Cursor and Presence:**

```
Three users are viewing/editing document doc_456.

(1) Alice opens doc_456 -- Presence Service registers her as "active"
(2) Server broadcasts to all connected clients: "Alice joined (3 viewers)"
(3) Alice clicks at position 42 in the document
(4) Client sends cursor update: CURSOR(userId=alice, pos=42, selection=null)
(5) Server broadcasts cursor position to Bob and Charlie
(6) Bob sees Alice's blue cursor blinking at position 42
(7) Alice selects text from position 42 to 67
(8) Client sends: CURSOR(userId=alice, pos=42, selection={start:42, end:67})
(9) Bob and Charlie see Alice's blue highlight over the selected text
(10) Charlie closes the tab -- Presence Service detects WebSocket disconnect
(11) After 5-second grace period (reconnection window), server marks Charlie as offline
(12) Server broadcasts: "Charlie left (2 viewers)"
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Google, Meta, Figma, Notion, Atlassian, and every collaborative software company because it tests the deepest distributed systems concepts:

| Skill Tested                         | What Interviewers Look For                                                                       |
|--------------------------------------|--------------------------------------------------------------------------------------------------|
| **Conflict Resolution (OT/CRDT)**   | Can you explain how concurrent edits merge? Can you walk through a transform step-by-step?       |
| **WebSocket Architecture**          | Persistent connection management, heartbeat, reconnection, session affinity                       |
| **Real-Time Sync**                  | Operation-based sync, client-server round-trip, optimistic local apply, server acknowledgment     |
| **Consistency Models**              | Causal consistency, eventual consistency, convergence, operation ordering                          |
| **Distributed Systems Theory**      | Lamport timestamps, vector clocks, happened-before relation, causal ordering                      |
| **Data Modeling**                   | Document, operation log, versioning, cursor state -- understanding what's persistent vs ephemeral |
| **Scaling WebSockets**              | Sticky sessions, connection limits per server, fan-out via pub/sub                                |
| **Tradeoff Analysis**               | OT vs CRDT -- when to use which, what are the engineering costs of each approach                  |
| **Production Awareness**            | Undo/redo, offline mode, cursor interpolation, operational compaction                              |

> **Interview tip**: Start by stating the scale (100M documents, 10M DAU, 50 ops/sec per active doc), then immediately explain the **core problem**: concurrent edits must converge. Draw the OT transform example on the whiteboard -- this is the star of the interview. Then sketch the architecture (WebSocket Gateway -> Collaboration Service -> Transform Engine -> Broadcast). Spend 40% of your time on OT/CRDT -- interviewers want depth on conflict resolution, not breadth across 20 features.

---

## 2. Scope

### In Scope

| Feature                              | Description                                                                         |
|--------------------------------------|-------------------------------------------------------------------------------------|
| Document CRUD                        | Create, read, update, delete documents with rich text support                       |
| Real-Time Collaborative Editing      | Multiple users editing the same document simultaneously with conflict resolution     |
| Operational Transform (OT)           | Centralized OT engine for transforming concurrent operations (Google Docs approach)  |
| CRDT (Conceptual Alternative)        | Conflict-free replicated data types as alternative to OT (Figma/Yjs approach)       |
| Cursor & Selection Tracking          | Real-time cursor positions and text selections visible to all editors                |
| Presence Indicators                  | "Who's viewing" -- user avatars, online/offline status, activity indicators          |
| Version History                      | Periodic snapshots, operation log, ability to view and restore previous versions     |
| Undo/Redo                            | Per-user undo/redo that correctly interacts with concurrent edits                    |
| Comments & Annotations              | Anchored comments on specific text ranges, threaded replies                          |
| Permissions & Sharing               | Owner, Editor, Viewer roles; link sharing with configurable access levels            |
| WebSocket Communication             | Persistent connections for real-time bidirectional communication                      |

### Out of Scope

| Feature                              | Reason                                                                              |
|--------------------------------------|-------------------------------------------------------------------------------------|
| Rich text formatting engine          | Rendering/layout engine is a client-side concern, not system design                  |
| File attachments / media embedding   | Separate storage pipeline; mention conceptually only                                 |
| Notification system                  | Covered in Project 03 (Notification System)                                          |
| User authentication / SSO            | Assume authentication exists; focus on collaboration                                 |
| Full-text search across documents    | Covered in Project 09 (Search Autocomplete)                                          |
| Mobile-specific optimizations        | Same architecture; different client implementation                                    |
| End-to-end encryption                | Complex topic; mention as a conceptual extension only                                |
| Spreadsheet / presentation modes     | Same OT/CRDT concepts apply; different data model                                    |
| AI-powered features (autocomplete)   | Separate ML pipeline; out of scope for system design                                 |

---

## 3. Assumptions

### Platform Scale

| Parameter                              | Value                                 | Derivation                                               |
|----------------------------------------|---------------------------------------|----------------------------------------------------------|
| Total documents                        | 100 million                           | Given                                                    |
| Daily Active Users (DAU)               | 10 million                            | Given                                                    |
| Concurrent editors per document (avg)  | 3                                     | Given; max ~50 for large team docs                       |
| Active documents at any moment         | 2 million                             | ~20% of DAU editing at peak                              |
| Operations per second per active doc   | 50 ops/sec                            | Given; 3 users typing ~15-20 ops/sec each                |
| Total operations per second (platform) | 100 million ops/sec                   | 2M active docs * 50 ops/sec                              |
| WebSocket connections (peak)           | 5 million                             | ~50% of DAU concurrently connected                       |
| Average document size                  | 50 KB                                 | ~10,000 words / 50,000 characters                        |
| Average operations per editing session | 500                                   | ~10 min session * ~50 ops/min per user                   |

### Data Volume

| Parameter                              | Value                                 | Derivation                                               |
|----------------------------------------|---------------------------------------|----------------------------------------------------------|
| Average operation size                 | 100 bytes                             | Type + position + content + metadata                     |
| Operations per day                     | ~5 billion                            | 10M DAU * 500 ops/session (simplified)                   |
| Operation log size per day             | ~500 GB                               | 5B ops * 100 bytes                                       |
| Snapshot size per document             | ~50 KB                                | Average document size                                    |
| Total document storage                 | ~5 TB                                 | 100M docs * 50 KB                                        |
| Operation log (30-day retention)       | ~15 TB                                | 500 GB/day * 30 days                                     |

### Latency Budget

```
Real-Time Edit Sync (end-to-end):       Target < 100ms (p99)
  (1) Keystroke to local apply:                   < 1 ms (optimistic, no network)
  (2) Send operation to server (WebSocket):      10-30 ms (network RTT / 2)
  (3) Server transform + persist:                 5-10 ms
  (4) Broadcast to other clients (WebSocket):    10-30 ms (network RTT / 2)
  (5) Remote client apply + render:               1-5 ms
  ------------------------------------------------
  Total (user A types, user B sees):            26-76 ms (within 100ms target)

Cursor Position Sync:                   Target < 150ms (p99)
  (1) Cursor move detected:                       < 1 ms
  (2) Throttle (batched every 50ms):             0-50 ms
  (3) Send to server:                            10-30 ms
  (4) Broadcast to other clients:                10-30 ms
  (5) Remote client render cursor:                1-5 ms
  ------------------------------------------------
  Total:                                        21-116 ms

Document Load:                          Target < 500ms (p99)
  (1) HTTP request to Document Service:          10-30 ms
  (2) Fetch latest snapshot from DB:             10-50 ms
  (3) Fetch pending operations since snapshot:    5-20 ms
  (4) Reconstruct current state:                  5-20 ms
  (5) Serialize + transmit to client:            20-100 ms
  (6) Client render:                             50-200 ms
  ------------------------------------------------
  Total:                                       100-420 ms
```

---

## 4. Functional Requirements

| #     | Requirement                          | Priority | Description                                                                                 |
|-------|--------------------------------------|----------|---------------------------------------------------------------------------------------------|
| FR-1  | Create Document                      | P0       | Users can create new blank or template-based documents                                      |
| FR-2  | Edit Document                        | P0       | Users can insert, delete, and format text within a document                                 |
| FR-3  | Real-Time Collaborative Editing      | P0       | Multiple users editing simultaneously; all edits merge without conflicts                    |
| FR-4  | Cursor & Selection Tracking          | P0       | Each user's cursor position and text selection visible to all other editors in real-time     |
| FR-5  | Presence Indicators                  | P0       | Show who is currently viewing/editing the document (avatars, colored cursors, count)         |
| FR-6  | Version History                      | P1       | Browse previous versions, view diffs between versions, restore a specific version            |
| FR-7  | Undo / Redo                          | P1       | Per-user undo/redo that correctly handles concurrent edits from other users                  |
| FR-8  | Comments & Annotations              | P1       | Add comments anchored to specific text ranges; threaded replies; resolve comments            |
| FR-9  | Permissions & Sharing               | P1       | Owner, Editor, Viewer roles; share via link or email; revoke access                          |
| FR-10 | Offline Editing (Conceptual)         | P2       | Buffer edits locally when disconnected; sync and merge when reconnected                      |
| FR-11 | Document Search (within document)    | P2       | Find and replace text within the current document                                            |

---

## 5. Non-Functional Requirements

| #     | Requirement                          | Target                                        | Rationale                                                           |
|-------|--------------------------------------|-----------------------------------------------|---------------------------------------------------------------------|
| NFR-1 | **Sync Latency**                     | p99 < 100ms for edit propagation              | Users must perceive edits as "instant" across all connected clients  |
| NFR-2 | **Convergence**                      | All clients converge to identical state        | Conflict-free merging is the entire point of the system              |
| NFR-3 | **Availability**                     | 99.99% uptime (< 52 min downtime/year)        | Collaboration is a core business workflow; downtime blocks teams     |
| NFR-4 | **Consistency**                      | Causal consistency for edits, eventual for presence | Edits must respect causal order; presence can lag a few seconds  |
| NFR-5 | **Durability**                       | Zero edit loss after server acknowledgment     | Users must never lose work                                           |
| NFR-6 | **Scalability**                      | 5M concurrent WebSocket connections            | Support 10M DAU with ~50% concurrency at peak                       |
| NFR-7 | **Offline Support (Conceptual)**     | Buffer + merge on reconnect                    | Network interruptions should not lose unsaved work                   |
| NFR-8 | **Throughput**                       | 100M operations/sec platform-wide              | 2M active docs * 50 ops/sec per doc                                  |
| NFR-9 | **Document Load Time**               | p99 < 500ms                                   | Documents must open quickly even with large edit histories            |

---

## 6. API Design

### 6.1 REST API -- Document Management

#### Create Document

```
POST /api/v1/documents
Authorization: Bearer <jwt_token>
Content-Type: application/json

Request:
{
  "title": "Q3 Product Roadmap",
  "content": "",
  "template_id": null,
  "workspace_id": "ws_7k2m9p4x",
  "permissions": {
    "default_access": "EDITOR",
    "link_sharing": "DISABLED"
  }
}

Response: 201 Created
{
  "document_id": "doc_3f8a2c1e7b9d",
  "title": "Q3 Product Roadmap",
  "owner_id": "usr_alice_001",
  "revision": 0,
  "created_at": "2026-04-26T10:00:00Z",
  "updated_at": "2026-04-26T10:00:00Z",
  "websocket_url": "wss://collab.example.com/ws/documents/doc_3f8a2c1e7b9d"
}
```

| Parameter        | Type   | Required | Description                                      |
|------------------|--------|----------|--------------------------------------------------|
| `title`          | String | Yes      | Document title (max 500 chars)                   |
| `content`        | String | No       | Initial content (empty for blank doc)            |
| `template_id`    | String | No       | Template to clone from                           |
| `workspace_id`   | String | No       | Workspace/folder the document belongs to         |
| `permissions`    | Object | No       | Default permissions configuration                |

#### Get Document

```
GET /api/v1/documents/{documentId}
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "document_id": "doc_3f8a2c1e7b9d",
  "title": "Q3 Product Roadmap",
  "content": "Hello Beautiful Earth",
  "revision": 7,
  "owner_id": "usr_alice_001",
  "active_editors": [
    {
      "user_id": "usr_alice_001",
      "display_name": "Alice",
      "avatar_url": "https://cdn.example.com/avatars/alice.png",
      "cursor_position": 42,
      "cursor_color": "#4285F4",
      "last_active_at": "2026-04-26T10:05:30Z"
    },
    {
      "user_id": "usr_bob_002",
      "display_name": "Bob",
      "avatar_url": "https://cdn.example.com/avatars/bob.png",
      "cursor_position": 18,
      "cursor_color": "#EA4335",
      "last_active_at": "2026-04-26T10:05:28Z"
    }
  ],
  "permissions": {
    "current_user_role": "EDITOR",
    "default_access": "EDITOR",
    "link_sharing": "VIEW_ONLY"
  },
  "created_at": "2026-04-26T10:00:00Z",
  "updated_at": "2026-04-26T10:05:30Z"
}
```

#### Get Version History

```
GET /api/v1/documents/{documentId}/history?page=1&size=20
Authorization: Bearer <jwt_token>

Response: 200 OK
{
  "document_id": "doc_3f8a2c1e7b9d",
  "versions": [
    {
      "version_id": "ver_9a2f3c8e1d4b",
      "revision": 7,
      "snapshot_content": "Hello Beautiful Earth",
      "edited_by": ["usr_alice_001", "usr_bob_002"],
      "operation_count": 12,
      "created_at": "2026-04-26T10:05:30Z",
      "label": null
    },
    {
      "version_id": "ver_7b4e1d9c3a6f",
      "revision": 5,
      "snapshot_content": "Hello World",
      "edited_by": ["usr_alice_001"],
      "operation_count": 5,
      "created_at": "2026-04-26T10:00:00Z",
      "label": "Initial Draft"
    }
  ],
  "pagination": {
    "page": 1,
    "size": 20,
    "total": 2,
    "has_next": false
  }
}
```

#### Update Permissions

```
PUT /api/v1/documents/{documentId}/permissions
Authorization: Bearer <jwt_token>
Content-Type: application/json

Request:
{
  "grants": [
    { "user_id": "usr_charlie_003", "role": "EDITOR" },
    { "user_id": "usr_diana_004", "role": "VIEWER" }
  ],
  "link_sharing": "EDITOR",
  "link_expiry": "2026-05-26T00:00:00Z"
}

Response: 200 OK
{
  "document_id": "doc_3f8a2c1e7b9d",
  "permissions": [
    { "user_id": "usr_alice_001", "role": "OWNER" },
    { "user_id": "usr_charlie_003", "role": "EDITOR" },
    { "user_id": "usr_diana_004", "role": "VIEWER" }
  ],
  "link_sharing": "EDITOR",
  "share_link": "https://docs.example.com/d/doc_3f8a2c1e7b9d?token=abc123"
}
```

### 6.2 WebSocket API -- Real-Time Collaboration

```
WebSocket Endpoint: wss://collab.example.com/ws/documents/{documentId}
                    ?token={jwt_token}&userId={userId}
```

#### Client-to-Server Messages

```
1. OPERATION -- Send an edit operation
   {
     "type": "OPERATION",
     "client_id": "client_a1b2c3",
     "operations": [
       {
         "op_type": "INSERT",
         "position": 6,
         "content": "Beautiful ",
         "attributes": { "bold": false, "italic": false }
       }
     ],
     "base_revision": 5,
     "local_op_id": "op_local_001",
     "timestamp": 1745654400000
   }

2. CURSOR -- Update cursor position
   {
     "type": "CURSOR",
     "user_id": "usr_alice_001",
     "position": 42,
     "selection": { "start": 42, "end": 67 },
     "timestamp": 1745654400050
   }

3. PRESENCE -- Heartbeat / activity signal
   {
     "type": "PRESENCE",
     "user_id": "usr_alice_001",
     "status": "ACTIVE",
     "timestamp": 1745654400000
   }

4. UNDO
   {
     "type": "UNDO",
     "client_id": "client_a1b2c3",
     "base_revision": 7
   }

5. REDO
   {
     "type": "REDO",
     "client_id": "client_a1b2c3",
     "base_revision": 7
   }
```

#### Server-to-Client Messages

```
1. OPERATION_ACK -- Server acknowledges a client's operation
   {
     "type": "OPERATION_ACK",
     "local_op_id": "op_local_001",
     "server_revision": 6,
     "timestamp": 1745654400035
   }

2. REMOTE_OPERATION -- Broadcast another user's (transformed) operation
   {
     "type": "REMOTE_OPERATION",
     "user_id": "usr_bob_002",
     "operations": [
       {
         "op_type": "DELETE",
         "position": 16,
         "length": 5
       },
       {
         "op_type": "INSERT",
         "position": 16,
         "content": "Earth"
       }
     ],
     "server_revision": 7,
     "timestamp": 1745654400040
   }

3. CURSOR_UPDATE -- Another user's cursor moved
   {
     "type": "CURSOR_UPDATE",
     "user_id": "usr_bob_002",
     "display_name": "Bob",
     "cursor_color": "#EA4335",
     "position": 18,
     "selection": null,
     "timestamp": 1745654400055
   }

4. PRESENCE_UPDATE -- User joined/left/went idle
   {
     "type": "PRESENCE_UPDATE",
     "user_id": "usr_charlie_003",
     "display_name": "Charlie",
     "status": "LEFT",
     "active_users_count": 2,
     "timestamp": 1745654400060
   }

5. DOCUMENT_STATE -- Full document state (on connect or resync)
   {
     "type": "DOCUMENT_STATE",
     "document_id": "doc_3f8a2c1e7b9d",
     "content": "Hello Beautiful Earth",
     "revision": 7,
     "active_users": [
       { "user_id": "usr_alice_001", "display_name": "Alice", "cursor_color": "#4285F4" },
       { "user_id": "usr_bob_002", "display_name": "Bob", "cursor_color": "#EA4335" }
     ]
   }

6. ERROR -- Operation rejected
   {
     "type": "ERROR",
     "code": "REVISION_MISMATCH",
     "message": "Base revision 3 is too old. Current revision is 7. Please resync.",
     "current_revision": 7
   }
```

#### WebSocket Connection Lifecycle

```
(1) Client opens WebSocket with JWT token and documentId
(2) Server validates token, checks document permissions
(3) Server sends DOCUMENT_STATE (full content + revision + active users)
(4) Client renders document and enters editing mode
(5) Client sends PRESENCE(status=ACTIVE)
(6) Server broadcasts PRESENCE_UPDATE("Alice joined") to other clients
(7) Client sends OPERATION messages as user edits
(8) Server responds with OPERATION_ACK for client's own ops
(9) Server sends REMOTE_OPERATION for other users' ops
(10) Client sends periodic PRESENCE heartbeat every 30 seconds
(11) If heartbeat missed for 2 cycles, server marks user as IDLE
(12) On tab close, client sends PRESENCE(status=LEFT)
(13) If ungraceful disconnect, server detects via WebSocket close event
(14) Server waits 5 seconds (reconnection grace period)
(15) If no reconnect, server broadcasts PRESENCE_UPDATE("Alice left")
```

---

## 7. Data Model

### 7.1 Document

```
Table: documents
+-------------------+----------------+----------------------------------------------+
| Column            | Type           | Description                                  |
+-------------------+----------------+----------------------------------------------+
| document_id       | VARCHAR(36) PK | UUID, e.g. "doc_3f8a2c1e7b9d"               |
| title             | VARCHAR(500)   | Document title                               |
| owner_id          | VARCHAR(36) FK | User who created the document                |
| workspace_id      | VARCHAR(36) FK | Parent workspace/folder                      |
| current_revision  | BIGINT         | Monotonically increasing revision counter     |
| content_snapshot  | TEXT           | Latest full document content (periodically   |
|                   |                | updated, not after every keystroke)           |
| snapshot_revision | BIGINT         | Revision at which snapshot was taken          |
| status            | ENUM           | ACTIVE, ARCHIVED, DELETED                    |
| created_at        | TIMESTAMP      | Creation time                                |
| updated_at        | TIMESTAMP      | Last modification time                       |
+-------------------+----------------+----------------------------------------------+
Index: PRIMARY (document_id)
Index: idx_owner (owner_id)
Index: idx_workspace (workspace_id, status)
```

### 7.2 Operation

```
Table: operations
+-------------------+----------------+----------------------------------------------+
| Column            | Type           | Description                                  |
+-------------------+----------------+----------------------------------------------+
| operation_id      | VARCHAR(36) PK | UUID                                         |
| document_id       | VARCHAR(36) FK | Document this operation belongs to            |
| user_id           | VARCHAR(36) FK | User who performed the operation              |
| revision          | BIGINT         | Server-assigned revision number               |
| op_type           | ENUM           | INSERT, DELETE, RETAIN, FORMAT                |
| position          | INTEGER        | Character position in document                |
| content           | TEXT           | Inserted text (null for DELETE/RETAIN)        |
| length            | INTEGER        | Length of affected text (for DELETE/RETAIN)    |
| attributes        | JSONB          | Formatting attributes (bold, italic, etc.)    |
| base_revision     | BIGINT         | Client's revision when op was created         |
| client_id         | VARCHAR(64)    | Client instance ID (for dedup)                |
| local_op_id       | VARCHAR(64)    | Client-side operation ID (for ACK matching)   |
| created_at        | TIMESTAMP      | When operation was applied on server          |
+-------------------+----------------+----------------------------------------------+
Index: PRIMARY (operation_id)
Index: idx_doc_rev (document_id, revision)  -- critical: fetch ops since revision N
Index: idx_doc_time (document_id, created_at)
```

### 7.3 Version (Snapshot)

```
Table: versions
+-------------------+----------------+----------------------------------------------+
| Column            | Type           | Description                                  |
+-------------------+----------------+----------------------------------------------+
| version_id        | VARCHAR(36) PK | UUID                                         |
| document_id       | VARCHAR(36) FK | Document                                     |
| revision          | BIGINT         | Revision at time of snapshot                  |
| content           | TEXT           | Full document content at this revision        |
| label             | VARCHAR(200)   | Optional user-assigned label ("Final Draft")  |
| created_by        | VARCHAR(36) FK | User who triggered or was active at snapshot  |
| contributing_users| JSONB          | List of user IDs who edited since last version|
| operation_count   | INTEGER        | Number of operations since previous snapshot  |
| created_at        | TIMESTAMP      | Snapshot creation time                        |
+-------------------+----------------+----------------------------------------------+
Index: PRIMARY (version_id)
Index: idx_doc_rev (document_id, revision DESC)
```

### 7.4 Cursor (Ephemeral -- Redis)

```
Redis Hash: cursor:{document_id}
+-------------------+----------------+----------------------------------------------+
| Field             | Type           | Description                                  |
+-------------------+----------------+----------------------------------------------+
| {user_id}         | JSON           | { "position": 42, "selection": {start, end}, |
|                   |                |   "color": "#4285F4", "updated_at": epoch }  |
+-------------------+----------------+----------------------------------------------+
TTL: 120 seconds (auto-expire if no updates)
```

### 7.5 Presence (Ephemeral -- Redis)

```
Redis Sorted Set: presence:{document_id}
  Score = last heartbeat timestamp (epoch ms)
  Member = user_id

Redis Hash: user_presence:{user_id}
+-------------------+----------------+----------------------------------------------+
| Field             | Type           | Description                                  |
+-------------------+----------------+----------------------------------------------+
| document_id       | String         | Which document the user has open             |
| status            | String         | ACTIVE, IDLE, LEFT                           |
| display_name      | String         | User's display name                          |
| avatar_url        | String         | User's avatar URL                            |
| cursor_color      | String         | Assigned cursor color (unique per document)  |
| connected_server  | String         | WebSocket server ID hosting this connection  |
| connected_at      | Long           | Connection timestamp                         |
| last_heartbeat    | Long           | Last heartbeat timestamp                     |
+-------------------+----------------+----------------------------------------------+
TTL: 120 seconds (auto-expire on disconnect)
```

### 7.6 Permission

```
Table: permissions
+-------------------+----------------+----------------------------------------------+
| Column            | Type           | Description                                  |
+-------------------+----------------+----------------------------------------------+
| permission_id     | VARCHAR(36) PK | UUID                                         |
| document_id       | VARCHAR(36) FK | Document                                     |
| user_id           | VARCHAR(36) FK | Grantee (null if link-based)                 |
| role              | ENUM           | OWNER, EDITOR, COMMENTER, VIEWER             |
| granted_by        | VARCHAR(36) FK | User who granted access                      |
| share_token       | VARCHAR(64)    | Token for link-based sharing (unique)        |
| expires_at        | TIMESTAMP      | Optional expiration for link sharing          |
| created_at        | TIMESTAMP      | When permission was granted                  |
+-------------------+----------------+----------------------------------------------+
Index: PRIMARY (permission_id)
Index: idx_doc_user (document_id, user_id) UNIQUE
Index: idx_share_token (share_token) UNIQUE
```

### 7.7 Comment

```
Table: comments
+-------------------+----------------+----------------------------------------------+
| Column            | Type           | Description                                  |
+-------------------+----------------+----------------------------------------------+
| comment_id        | VARCHAR(36) PK | UUID                                         |
| document_id       | VARCHAR(36) FK | Document                                     |
| user_id           | VARCHAR(36) FK | Author                                       |
| parent_comment_id | VARCHAR(36) FK | Null for top-level, FK for replies            |
| anchor_start      | INTEGER        | Start position in document (for anchoring)    |
| anchor_end        | INTEGER        | End position in document                      |
| anchor_revision   | BIGINT         | Revision at which anchor positions are valid  |
| content           | TEXT           | Comment text                                 |
| status            | ENUM           | OPEN, RESOLVED, DELETED                      |
| created_at        | TIMESTAMP      | Creation time                                |
| updated_at        | TIMESTAMP      | Last edit time                               |
+-------------------+----------------+----------------------------------------------+
Index: PRIMARY (comment_id)
Index: idx_doc_status (document_id, status)
Index: idx_parent (parent_comment_id)
```

### Entity Relationship Diagram

```
+------------------+       1:N       +------------------+
|    Document      |<----------------|    Operation     |
|                  |                 |                  |
| document_id (PK) |                 | operation_id (PK)|
| title            |                 | document_id (FK) |
| owner_id (FK)    |       1:N       | user_id (FK)     |
| current_revision |<----------------|    Version       |
| content_snapshot |                 |                  |
+--------+---------+                 | version_id (PK)  |
         |                           | document_id (FK) |
         |                           +------------------+
         |
         |  1:N       +------------------+
         +----------->|   Permission     |
         |            |                  |
         |            | permission_id(PK)|
         |            | document_id (FK) |
         |            | user_id (FK)     |
         |            | role             |
         |            +------------------+
         |
         |  1:N       +------------------+
         +----------->|    Comment       |
                      |                  |
                      | comment_id (PK)  |
                      | document_id (FK) |
                      | user_id (FK)     |
                      | anchor_start     |
                      | anchor_end       |
                      +------------------+

Ephemeral (Redis):
  cursor:{document_id}    -- Hash of user_id -> cursor JSON
  presence:{document_id}  -- Sorted Set of user_id by heartbeat time
```

---

## 8. High-Level Architecture

```
+-------------------------------------------------------------------+
|                         CLIENT LAYER                               |
|                                                                    |
|  +-------------------+  +-------------------+  +----------------+  |
|  |  Web Client       |  |  Desktop Client   |  |  Mobile Client |  |
|  |  (Browser)        |  |  (Electron)       |  |  (iOS/Android) |  |
|  |                   |  |                   |  |                |  |
|  | +---------------+ |  | +---------------+ |  | +------------+ |  |
|  | | Local OT      | |  | | Local OT      | |  | | Local OT   | |  |
|  | | Engine        | |  | | Engine        | |  | | Engine     | |  |
|  | +---------------+ |  | +---------------+ |  | +------------+ |  |
|  | | Operation     | |  | | Operation     | |  | | Operation  | |  |
|  | | Buffer        | |  | | Buffer        | |  | | Buffer     | |  |
|  | +---------------+ |  | +---------------+ |  | +------------+ |  |
|  +--------+----------+  +--------+----------+  +-------+--------+  |
|           |                      |                      |          |
+-----------+----------------------+----------------------+----------+
            |                      |                      |
            v                      v                      v
     WebSocket (wss://)     WebSocket (wss://)     WebSocket (wss://)
            |                      |                      |
+-----------+----------------------+----------------------+----------+
|                      LOAD BALANCER (L7)                            |
|           (Sticky sessions by documentId + userId)                 |
+-------------------------------------------------------------------+
            |
            v
+-----------+----------------------+----------------------+----------+
|                   WEBSOCKET GATEWAY LAYER                          |
|                                                                    |
|  +-------------------+  +-------------------+  +----------------+  |
|  | WS Gateway Node 1 |  | WS Gateway Node 2 |  | WS Gateway N   |  |
|  | (50K connections) |  | (50K connections) |  | (50K conns)    |  |
|  |                   |  |                   |  |                |  |
|  | - Auth validation |  | - Auth validation |  | - Auth valid.  |  |
|  | - Session mgmt    |  | - Session mgmt    |  | - Session mgmt |  |
|  | - Heartbeat       |  | - Heartbeat       |  | - Heartbeat    |  |
|  | - Message routing |  | - Message routing |  | - Msg routing  |  |
|  +--------+----------+  +--------+----------+  +-------+--------+  |
+-----------+----------------------+----------------------+----------+
            |                      |                      |
            v                      v                      v
+-----------+----------------------+----------------------+----------+
|                   COLLABORATION SERVICE LAYER                      |
|                                                                    |
|  +-------------------+  +-------------------+  +----------------+  |
|  | Collab Service 1  |  | Collab Service 2  |  | Collab Svc N   |  |
|  |                   |  |                   |  |                |  |
|  | +---------------+ |  | +---------------+ |  | +------------+ |  |
|  | | OT Transform  | |  | | OT Transform  | |  | | OT Xform   | |  |
|  | | Engine        | |  | | Engine        | |  | | Engine     | |  |
|  | +---------------+ |  | +---------------+ |  | +------------+ |  |
|  | | Op Validator  | |  | | Op Validator  | |  | | Op Valid.  | |  |
|  | +---------------+ |  | +---------------+ |  | +------------+ |  |
|  | | Revision Mgr  | |  | | Revision Mgr  | |  | | Rev Mgr    | |  |
|  | +---------------+ |  | +---------------+ |  | +------------+ |  |
|  +--------+----------+  +--------+----------+  +-------+--------+  |
+-----------+----------------------+----------------------+----------+
            |                      |                      |
            v                      v                      v
+-----------+----------------------+----------------------+----------+
|                     DATA & MESSAGING LAYER                         |
|                                                                    |
|  +-------------+  +----------+  +----------+  +--------------+     |
|  | PostgreSQL  |  |  Redis   |  |  Kafka   |  | Document     |     |
|  | (Documents, |  | (Cursor, |  | (Op Log, |  | Service      |     |
|  |  Versions,  |  | Presence,|  | Events,  |  | (CRUD, Snap- |     |
|  |  Perms,     |  | Sessions)|  | Broadcast|  |  shots, Hx)  |     |
|  |  Comments)  |  |          |  | Fan-out) |  |              |     |
|  +-------------+  +----------+  +----------+  +--------------+     |
|                                                                    |
|  +-------------------+  +-------------------+                      |
|  | Presence Service  |  | Version History   |                      |
|  | (Heartbeat check, |  | Service           |                      |
|  |  status broadcast)|  | (Snapshots, Diff) |                      |
|  +-------------------+  +-------------------+                      |
+-------------------------------------------------------------------+
```

### Request Flow -- Edit Operation

```
Alice types "Hello" at position 0 in document doc_123.

(1)  Alice's keystroke triggers local OT engine
(2)  Local engine applies INSERT(pos=0, text="Hello") optimistically
     - Alice sees "Hello" immediately (no network wait)
(3)  Client sends OPERATION message via WebSocket to WS Gateway Node 1
(4)  WS Gateway validates the message format and routes to Collaboration Service
(5)  Collaboration Service receives the operation with base_revision=5
(6)  Collaboration Service checks: server is at revision 6 (someone else edited)
(7)  Collaboration Service fetches operations [rev 6] from operation log
(8)  OT Transform Engine transforms Alice's operation against operation at rev 6
     - Alice's INSERT(pos=0) is unaffected (position 0 doesn't shift)
(9)  Transformed operation is assigned revision 7
(10) Collaboration Service persists operation to PostgreSQL (operations table)
(11) Collaboration Service publishes operation to Kafka topic: ops.doc_123
(12) Collaboration Service sends OPERATION_ACK(rev=7) back to Alice via WebSocket
(13) Alice's client receives ACK, moves operation from "pending" to "confirmed"
(14) Kafka consumer on WS Gateway Node 2 receives the operation
     - WS Gateway Node 2 hosts Bob's WebSocket connection for doc_123
(15) WS Gateway Node 2 sends REMOTE_OPERATION to Bob
(16) Bob's local OT engine transforms any pending local ops against Alice's op
(17) Bob's client applies Alice's op and renders the update
(18) Both clients are now at revision 7 with consistent state
```

### Request Flow -- Document Open

```
Bob opens document doc_123 for the first time.

(1)  Client sends HTTP GET /api/v1/documents/doc_123
(2)  API Gateway routes to Document Service
(3)  Document Service checks permission (Bob is EDITOR)
(4)  Document Service fetches latest snapshot from PostgreSQL
     - Snapshot is at revision 100 (taken 5 minutes ago)
(5)  Document Service fetches operations 101-107 from operations table
     - 7 operations have occurred since the snapshot
(6)  Document Service replays operations 101-107 on top of snapshot
(7)  Document Service returns reconstructed document at revision 107
(8)  Client renders the document content
(9)  Client opens WebSocket: wss://collab.example.com/ws/documents/doc_123
(10) WS Gateway assigns Bob to a gateway node (sticky by documentId hash)
(11) WS Gateway sends DOCUMENT_STATE(content, rev=107, active_users)
(12) Client verifies state matches what it got from HTTP
     - If mismatch (edits happened between HTTP and WS), client resyncs
(13) WS Gateway registers Bob's connection in Redis: presence:doc_123
(14) WS Gateway broadcasts PRESENCE_UPDATE("Bob joined, 3 viewers")
(15) Bob is now live -- any edits by Alice/Charlie are pushed via WebSocket
```

---

## 9. Component Deep Dive

### 9.1 WebSocket Gateway

The WebSocket Gateway is the entry point for all real-time communication. It manages persistent connections, authenticates users, routes messages, and handles ungraceful disconnections.

**Responsibilities:**

| Responsibility          | Details                                                                        |
|-------------------------|--------------------------------------------------------------------------------|
| Connection Management   | Accept, maintain, and close WebSocket connections; track connection metadata    |
| Authentication          | Validate JWT on connection; reject unauthorized connections                     |
| Session Tracking        | Register connection in Redis: userId + documentId -> serverId                  |
| Heartbeat               | Send ping every 30 seconds; close connection if 2 pongs missed                 |
| Message Routing         | Route OPERATION messages to Collaboration Service; route CURSOR to Presence    |
| Broadcast Delivery      | Receive events from Kafka and deliver to connected clients                      |
| Reconnection Handling   | 5-second grace period; resync document state on reconnect                       |

**Connection Management Flow:**

```
(1)  Client initiates WebSocket handshake with JWT + documentId
(2)  Gateway validates JWT (signature, expiration, claims)
(3)  Gateway checks permission: user has EDITOR or VIEWER role for document
(4)  Gateway assigns a cursor color (from a pool, unique within this document)
(5)  Gateway registers connection in Redis:
     - SET ws_conn:{userId}:{documentId} = {serverId, connectedAt} EX 120
     - ZADD presence:{documentId} {timestamp} {userId}
     - HSET cursor:{documentId} {userId} '{"position":0,"color":"#4285F4"}'
(6)  Gateway subscribes to Kafka topic: ops.{documentId}
(7)  Gateway sends DOCUMENT_STATE to client
(8)  Gateway broadcasts PRESENCE_UPDATE to other clients on this document
(9)  Connection is now active -- bidirectional message flow begins

On Disconnect:
(10) Gateway detects WebSocket close (clean close or TCP reset)
(11) Gateway starts 5-second reconnection timer
(12) If client reconnects within 5 seconds:
     - Reuse session, send missed operations since last ACK'd revision
(13) If timeout expires:
     - DEL ws_conn:{userId}:{documentId}
     - ZREM presence:{documentId} {userId}
     - HDEL cursor:{documentId} {userId}
     - Broadcast PRESENCE_UPDATE("Alice left")
```

**Scaling WebSocket Gateways:**

```
Each gateway node handles ~50,000 concurrent WebSocket connections.
For 5M concurrent connections: 5,000,000 / 50,000 = 100 gateway nodes.

                    +-----------------------------+
                    |       Load Balancer (L7)    |
                    |  Sticky: hash(documentId)   |
                    +----+--------+--------+------+
                         |        |        |
                    +----v--+ +---v---+ +--v----+
                    | GW-1  | | GW-2  | | GW-N  |
                    | 50K   | | 50K   | | 50K   |
                    | conns | | conns | | conns |
                    +---+---+ +---+---+ +---+---+
                        |         |         |
                        v         v         v
                    +-----------------------------+
                    |       Kafka (Fan-out)       |
                    |  Topic per documentId range  |
                    +-----------------------------+

Note: Sticky sessions by documentId mean all editors of the same
document are likely on the same gateway node, making local broadcast
fast. For documents with editors on different gateways, Kafka fan-out
ensures all gateways receive the operation.
```

### 9.2 Collaboration Service

The Collaboration Service is the brain of the system. It receives operations from clients, validates them, transforms them against concurrent operations using the OT engine, persists them, and triggers broadcast to all other editors.

**Responsibilities:**

| Responsibility                | Details                                                                     |
|-------------------------------|-----------------------------------------------------------------------------|
| Operation Reception           | Receive operations from WebSocket Gateway                                   |
| Validation                    | Check operation is well-formed, user has EDITOR permission                  |
| Transform                     | Transform operation against any concurrent ops using OT engine              |
| Revision Assignment           | Assign monotonically increasing server revision number                       |
| Persistence                   | Write operation to PostgreSQL and publish to Kafka                           |
| Acknowledgment                | Send ACK with server revision back to the authoring client                   |
| Broadcast Trigger             | Publish transformed operation to Kafka for fan-out to other clients          |
| Snapshot Trigger              | After every N operations, trigger a snapshot of the document                 |

**Operation Processing Flow (Detailed):**

```
Collaboration Service receives OPERATION from Alice.

(1)  Validate operation format:
     - op_type is valid (INSERT, DELETE, RETAIN, FORMAT)
     - position is non-negative and within document bounds
     - content is non-empty for INSERT
     - base_revision is a positive integer
(2)  Check permissions: Alice has EDITOR role for doc_123
(3)  Acquire document-level lock (distributed lock via Redis):
     - SET lock:doc_123 {serverId} NX EX 5
     - If lock already held, wait with backoff (max 3 retries)
(4)  Fetch current server revision for doc_123:
     - SELECT current_revision FROM documents WHERE document_id = 'doc_123'
     - Server is at revision 10
(5)  Alice's operation has base_revision = 8 (she's 2 revisions behind)
(6)  Fetch operations [9, 10] from operation log:
     - SELECT * FROM operations WHERE document_id = 'doc_123'
       AND revision IN (9, 10) ORDER BY revision ASC
(7)  Transform Alice's operation against ops 9 and 10 sequentially:
     - transformed_op = OT.transform(alice_op, op_9)
     - transformed_op = OT.transform(transformed_op, op_10)
(8)  Assign revision 11 to the transformed operation
(9)  Persist in a single transaction:
     - INSERT INTO operations (...) VALUES (transformed_op, rev=11)
     - UPDATE documents SET current_revision = 11 WHERE document_id = 'doc_123'
(10) Release document lock: DEL lock:doc_123
(11) Publish to Kafka: topic=ops.doc_123, key=doc_123, value=transformed_op
(12) Send OPERATION_ACK(rev=11) back to Alice via WebSocket
(13) Check if snapshot needed: revision 11 % 100 == 0? No -> skip
     - Snapshots taken every 100 operations
```

**Why Document-Level Locking?**

Operations on the same document must be serialized to maintain a total order. Without a lock, two concurrent operations could both read the same revision, both transform against the same base, and produce conflicting revision numbers. The lock ensures exactly one operation is processed at a time per document. For different documents, operations are fully parallel.

```
Without lock (RACE CONDITION):                With lock (CORRECT):

  Alice op (base=8)   Bob op (base=8)           Alice op (base=8)   Bob op (base=8)
       |                    |                         |                    |
  Read rev=10          Read rev=10               Acquire lock         Wait for lock...
       |                    |                         |                    |
  Transform(8->10)    Transform(8->10)           Read rev=10              |
       |                    |                         |                    |
  Assign rev=11       Assign rev=11 (CONFLICT!)  Transform(8->10)         |
                                                      |                    |
                                                 Assign rev=11             |
                                                      |                    |
                                                 Release lock              |
                                                                      Acquire lock
                                                                           |
                                                                      Read rev=11
                                                                           |
                                                                      Transform(8->11)
                                                                           |
                                                                      Assign rev=12
                                                                           |
                                                                      Release lock
```

### 9.3 Document Service

The Document Service handles non-real-time operations: CRUD, document loading, version management, and periodic snapshotting.

**Responsibilities:**

| Responsibility        | Details                                                                  |
|-----------------------|--------------------------------------------------------------------------|
| Create Document       | Initialize new document with empty content, revision 0                   |
| Load Document         | Fetch latest snapshot + replay pending operations                        |
| Snapshot Management   | Take periodic snapshots to limit operation replay on load                |
| Version Listing       | List historical versions with metadata                                    |
| Version Restore       | Restore document to a previous version (creates new operations)          |
| Delete / Archive      | Soft-delete with retention period                                         |

**Document Load Strategy:**

```
Why snapshots + operation replay?

Storing the full document after every keystroke is expensive and slow.
Instead, we store periodic snapshots and replay the operations since
the last snapshot. This is the "event sourcing" pattern.

Example: Document has 10,000 operations total

Without snapshots:
  Load time = replay 10,000 operations = ~500ms (too slow)

With snapshots every 100 operations:
  Latest snapshot at revision 9,900
  Operations since snapshot: 100
  Load time = fetch snapshot + replay 100 ops = ~20ms

Snapshot schedule:
  (1) Every 100 operations, OR
  (2) Every 5 minutes (whichever comes first), OR
  (3) When the last editor disconnects (ensures clean state on disk)
```

### 9.4 Operation Transform Engine

This is the **core algorithmic component** -- see Section 10 for the full deep dive.

**Summary:**

| Aspect              | Details                                                                    |
|---------------------|----------------------------------------------------------------------------|
| Purpose             | Transform concurrent operations so they converge to the same state         |
| Input               | An incoming operation + list of concurrent operations to transform against |
| Output              | A transformed operation that can be applied to the current server state    |
| Complexity           | O(n) per transform pair, O(n * m) for n ops against m concurrent ops      |
| Thread Safety       | Single-threaded per document (protected by document lock)                  |

### 9.5 CRDT Engine

An alternative to OT -- see Section 11 for the full deep dive.

**Summary:**

| Aspect              | Details                                                                    |
|---------------------|----------------------------------------------------------------------------|
| Purpose             | Data structure that automatically merges without central coordination      |
| Approach            | Each character has a unique ID; merge by ID ordering, not position         |
| Advantage           | No central server needed; works offline; mathematically proven convergence |
| Disadvantage        | Higher metadata overhead; tombstones; more complex implementation          |
| Used By             | Figma, Notion (Yjs library), xi-editor, Automerge                         |

### 9.6 Presence Service

Tracks which users are currently viewing or editing each document, and broadcasts presence changes.

**Responsibilities:**

| Responsibility          | Details                                                                  |
|-------------------------|--------------------------------------------------------------------------|
| Track Active Users      | Maintain set of active users per document in Redis                       |
| Heartbeat Monitoring    | Detect stale connections via missed heartbeats                           |
| Status Management       | Transition users between ACTIVE, IDLE, and LEFT states                   |
| Broadcast Updates       | Notify all document participants when presence changes                    |
| User Count              | Provide real-time count of active viewers/editors                         |

**Presence State Machine:**

```
                     connect
           +------------------------+
           |                        |
           v       heartbeat        |
    +------+------+ received +------+------+
    |             |<---------|             |
    |   ACTIVE    |          |   ACTIVE    |
    |             |--------->|             |
    +------+------+ 60s no   +------+------+
           |       heartbeat        ^
           |                        |
           | 60s no heartbeat       | heartbeat
           v                        | received
    +------+------+                 |
    |             |-----------------+
    |    IDLE     |
    |             |
    +------+------+
           |
           | 120s no heartbeat
           | OR explicit disconnect
           v
    +------+------+
    |             |
    |    LEFT     |-----> Remove from presence set
    |             |       Broadcast "user left"
    +-------------+
```

**Presence Data Flow:**

```
(1) Client sends PRESENCE heartbeat every 30 seconds
(2) WS Gateway forwards heartbeat to Presence Service
(3) Presence Service updates Redis:
    - ZADD presence:{docId} {timestamp} {userId}
    - HSET user_presence:{userId} last_heartbeat {timestamp}
(4) Background job runs every 10 seconds:
    - ZRANGEBYSCORE presence:{docId} -inf {now - 60s}
    - Any users with heartbeat older than 60s -> mark IDLE
    - Any users with heartbeat older than 120s -> mark LEFT
(5) On state change, Presence Service publishes to Kafka
(6) WS Gateways consume event and broadcast to connected clients
```

### 9.7 Version History Service

Manages document snapshots, allows users to browse history, view diffs, and restore previous versions.

**Responsibilities:**

| Responsibility          | Details                                                                  |
|-------------------------|--------------------------------------------------------------------------|
| Create Snapshots        | Periodically snapshot full document state                                 |
| List Versions           | Paginated list of versions with metadata                                  |
| View Version            | Reconstruct document at any historical revision                           |
| Diff Versions           | Compute diff between two versions                                         |
| Restore Version         | Create new operations that transform current state to target version      |
| Label Versions          | Allow users to name specific versions ("Final Draft", "v2")               |

**Snapshot Strategy:**

```
Trigger conditions (whichever occurs first):
  (1) Every 100 operations on a document
  (2) Every 5 minutes during active editing
  (3) When the last editor disconnects
  (4) Before a document is shared with new users

Snapshot creation:
  (1) Fetch latest snapshot (revision N)
  (2) Fetch operations N+1 through current_revision
  (3) Replay operations on snapshot to get current content
  (4) INSERT INTO versions (document_id, revision, content, ...)
  (5) UPDATE documents SET snapshot_revision = current_revision

Storage optimization:
  - Keep last 50 snapshots per document (covers ~5000 operations)
  - Older snapshots: keep 1 per day for 30 days
  - Beyond 30 days: keep 1 per week for 1 year
  - Operations between retained snapshots can be compacted/deleted
```

**Version Restore Flow:**

```
User wants to restore document from revision 500 (current is revision 1200).

(1)  User clicks "Restore this version" on version at revision 500
(2)  Version History Service fetches snapshot at revision 500
(3)  Service computes diff: current_content vs snapshot_500_content
(4)  Diff is converted into a series of operations (DELETE + INSERT)
(5)  These operations are submitted through the normal Collaboration Service
(6)  All other connected clients receive the restore operations
(7)  Document is now at revision 1201 with the content from revision 500
(8)  A new snapshot is taken immediately at revision 1201
(9)  Original history is preserved (revision 500 is still browsable)

Key insight: Restore is NOT a rollback. It creates NEW operations that
transform the current state to match the target version. History is
preserved, and the restore itself is undoable.
```

### 9.8 Permission Service

Manages access control for documents: who can view, edit, comment, or own a document.

**Permission Model:**

```
Roles (ordered by privilege):

  OWNER    - Full control: edit, share, delete, transfer ownership
  EDITOR   - Edit document, add comments, view history
  COMMENTER - Add comments only, cannot edit document content
  VIEWER   - Read-only access, can view but not modify

Access methods:
  (1) Direct grant: Owner grants role to specific user by email/userId
  (2) Link sharing: Generate a shareable link with a role level
      - Link can be VIEW_ONLY, COMMENT, or EDIT
      - Link can have an expiration date
  (3) Workspace-level: All members of a workspace inherit a default role
```

**Permission Check Flow:**

```
Bob tries to edit document doc_123.

(1) Bob's client sends OPERATION via WebSocket
(2) WS Gateway extracts userId from JWT
(3) Permission check (cached in Redis, TTL 60s):
    - GET perm:{doc_123}:{usr_bob_002}
    - Cache hit: "EDITOR" -> allowed
    - Cache miss: query PostgreSQL:
      SELECT role FROM permissions
      WHERE document_id = 'doc_123' AND user_id = 'usr_bob_002'
(4) If role is EDITOR or OWNER -> operation is forwarded to Collaboration Service
(5) If role is VIEWER or COMMENTER -> operation is rejected with ERROR
    { "type": "ERROR", "code": "PERMISSION_DENIED" }
(6) If no permission found, check link-based sharing:
    - Was Bob's session created via a share link?
    - If yes, use the link's role level
(7) Result is cached in Redis: SET perm:{doc_123}:{usr_bob_002} "EDITOR" EX 60
```

---

## 10. Operational Transform (OT) Deep Dive

This section is **the star of the interview**. OT is the algorithm that makes concurrent editing possible in Google Docs, and understanding it deeply is the primary signal interviewers look for.

### 10.1 What Is Operational Transform?

OT is a technique for transforming concurrent operations against each other so that, regardless of the order in which operations are received and applied, all clients converge to the same final document state.

**The Core Insight:**

When two users edit the same document at the same time, their operations are based on the same document state. But by the time the server receives the second operation, the first operation has already been applied, changing the document. The second operation's positions are now wrong. OT adjusts (transforms) the second operation so it produces the intended effect on the modified document.

```
Without OT (BROKEN):                        With OT (CORRECT):

Document: "ABC"                              Document: "ABC"

Alice: INSERT(pos=1, "X")  -> "AXBC"        Alice: INSERT(pos=1, "X")  -> "AXBC"
Bob:   DELETE(pos=2, len=1) -> delete 'C'    Bob:   DELETE(pos=2, len=1) -> delete 'C'

Server applies Alice first: "AXBC"           Server applies Alice first: "AXBC"
Server applies Bob's DELETE(pos=2):           Server transforms Bob's DELETE:
  Deletes char at pos 2 = 'B' (WRONG!)         INSERT shifted positions right by 1
  Result: "AXC" (Bob wanted to delete 'C')     Transformed: DELETE(pos=3, len=1)
                                                Deletes char at pos 3 = 'C' (CORRECT!)
                                                Result: "AXB"
```

### 10.2 Operations

OT works with three fundamental operations on text:

```
+----------+---------------------------+--------------------------------------------+
| Operation| Parameters                | Description                                |
+----------+---------------------------+--------------------------------------------+
| INSERT   | position, text            | Insert 'text' at 'position'                |
| DELETE   | position, length          | Delete 'length' characters starting at     |
|          |                           | 'position'                                 |
| RETAIN   | count                     | Skip over 'count' characters (no change)   |
|          |                           | Used in composite operations                |
+----------+---------------------------+--------------------------------------------+

Examples on document "Hello World" (length = 11):

  INSERT(pos=5, text=",") -> "Hello, World"     (comma after "Hello")
  DELETE(pos=5, len=1)    -> "HelloWorld"        (delete the space)
  RETAIN(5)               -> skip first 5 chars  (used in compound ops)

Compound operation (insert comma and space, delete trailing space):
  [RETAIN(5), INSERT(","), RETAIN(1), DELETE(1)]
  "Hello World" -> "Hello, World"

Note: Google Docs uses compound operations internally, but for interview
purposes, individual INSERT/DELETE operations are sufficient.
```

### 10.3 Transform Rules

The transform function takes two concurrent operations (A and B) that were both created against the same base document state, and produces two transformed operations (A' and B') such that:

```
apply(apply(doc, A), B') = apply(apply(doc, B), A')

This is called the "Transformation Property 1" (TP1).

Visually:
                    doc
                   /   \
                  A     B
                 /       \
              doc_A     doc_B
                 \       /
                  B'   A'
                   \  /
                  doc_AB = doc_BA   (convergence!)
```

**Rule 1: INSERT vs INSERT**

```
Both Alice and Bob insert text at different positions.

Case 1: Alice inserts BEFORE Bob's position
  Alice: INSERT(pos=2, "XX")
  Bob:   INSERT(pos=5, "YY")
  
  Transform Bob against Alice:
    Bob's position shifts right by len("XX") = 2
    Bob': INSERT(pos=7, "YY")

Case 2: Bob inserts BEFORE Alice's position
  Alice: INSERT(pos=5, "XX")
  Bob:   INSERT(pos=2, "YY")
  
  Transform Bob against Alice:
    Bob's position is before Alice's insert -> no shift
    Bob': INSERT(pos=2, "YY")

Case 3: Both insert at the SAME position (tie-breaking needed)
  Alice: INSERT(pos=3, "XX")
  Bob:   INSERT(pos=3, "YY")
  
  Tie-break: use userId ordering (Alice < Bob alphabetically)
    Alice's insert goes first (lower userId wins)
    Bob's position shifts right by len("XX") = 2
    Bob': INSERT(pos=5, "YY")
    Result: "...XXYY..." (Alice's text appears before Bob's)

Transform function (pseudocode):
  transform_insert_insert(server_op, client_op):
    if client_op.pos < server_op.pos:
      return client_op  // no change needed
    elif client_op.pos > server_op.pos:
      return INSERT(pos = client_op.pos + len(server_op.text), client_op.text)
    else:  // same position
      if client_op.userId < server_op.userId:
        return client_op  // client goes first
      else:
        return INSERT(pos = client_op.pos + len(server_op.text), client_op.text)
```

**Rule 2: INSERT vs DELETE**

```
One user inserts, the other deletes.

Case 1: Insert position is BEFORE delete range
  Server applied: INSERT(pos=2, "XX")    (adds 2 chars at position 2)
  Client sent:    DELETE(pos=5, len=3)
  
  Transform client's DELETE:
    Delete range shifts right by insert length
    Client': DELETE(pos=7, len=3)

Case 2: Insert position is AFTER delete range
  Server applied: INSERT(pos=8, "XX")
  Client sent:    DELETE(pos=2, len=3)    (deletes positions 2,3,4)
  
  Transform client's DELETE:
    Insert is after the delete range -> no shift
    Client': DELETE(pos=2, len=3)

Case 3: Insert position is INSIDE delete range
  Server applied: INSERT(pos=4, "XX")    (inserts inside range being deleted)
  Client sent:    DELETE(pos=2, len=5)    (deletes positions 2,3,4,5,6)
  
  Transform client's DELETE:
    Split into two deletes around the inserted text:
    Client': DELETE(pos=2, len=2) + DELETE(pos=4, len=3)
    (Delete chars before insert, skip inserted text, delete chars after)
    
  This preserves the newly inserted text while still deleting
  the characters the client intended to delete.
```

**Rule 3: DELETE vs DELETE**

```
Both users delete text in the same document.

Case 1: Non-overlapping ranges (delete1 before delete2)
  Server applied: DELETE(pos=0, len=3)   (deletes positions 0,1,2)
  Client sent:    DELETE(pos=5, len=2)   (deletes positions 5,6)
  
  Transform client's DELETE:
    Client's range shifts left by server's delete length
    Client': DELETE(pos=2, len=2)        (5 - 3 = 2)

Case 2: Non-overlapping ranges (delete2 before delete1)
  Server applied: DELETE(pos=5, len=2)
  Client sent:    DELETE(pos=0, len=3)
  
  Transform client's DELETE:
    Client's range is before server's -> no shift
    Client': DELETE(pos=0, len=3)

Case 3: Overlapping ranges
  Server applied: DELETE(pos=2, len=5)   (deletes positions 2,3,4,5,6)
  Client sent:    DELETE(pos=4, len=4)   (deletes positions 4,5,6,7)
  
  Overlap: positions 4,5,6 are deleted by BOTH
  Client should only delete the non-overlapping part: position 7
  After server's delete, position 7 becomes position 2:
    Client': DELETE(pos=2, len=1)

Case 4: Client's delete is entirely within server's delete
  Server applied: DELETE(pos=2, len=10)
  Client sent:    DELETE(pos=4, len=3)
  
  Server already deleted everything client wanted to delete
  Client': NO-OP (nothing to do)

Case 5: Identical deletes
  Server applied: DELETE(pos=2, len=5)
  Client sent:    DELETE(pos=2, len=5)
  
  Same characters already deleted by server
  Client': NO-OP
```

### 10.4 Server as Single Source of Truth (Centralized OT)

Google Docs uses **centralized OT**, where the server is the single source of truth for the document state and the ordering of operations.

```
+----------+                +----------+                +----------+
| Client A |                | Server   |                | Client B |
+----------+                +----------+                +----------+
     |                           |                           |
     |-- op_A (base_rev=5) ---->|                           |
     |                           |  1. Apply op_A            |
     |                           |  2. Assign rev=6          |
     |                           |  3. Persist               |
     |<-- ACK(rev=6) -----------|                           |
     |                           |-- op_A (rev=6) --------->|
     |                           |                           |
     |                           |<-- op_B (base_rev=5) ----|
     |                           |  4. op_B is based on rev 5|
     |                           |     but server is at rev 6|
     |                           |  5. Transform op_B        |
     |                           |     against op_A          |
     |                           |  6. Apply transformed op_B|
     |                           |  7. Assign rev=7          |
     |                           |                           |
     |<-- transformed_op_B -----|-- ACK(rev=7) ----------->|
     |                           |                           |
     |  Apply transformed_op_B  |                 Apply op_A |
     |  on local state           |           on local state  |
     |                           |                           |
  Client A state = Client B state = Server state (CONVERGENCE)
```

**Client-Side OT (Optimistic Apply):**

The client maintains three states:

```
+---------------------------------------------------------------------+
|                     CLIENT OT STATE MACHINE                          |
|                                                                      |
|  State 1: SYNCHRONIZED                                               |
|    - No pending operations                                           |
|    - Local state matches server state                                |
|    - On local edit: send op to server, move to AWAITING_ACK          |
|                                                                      |
|  State 2: AWAITING_ACK                                               |
|    - One operation sent, waiting for server ACK                      |
|    - Local state = server state + pending op                         |
|    - On local edit: buffer in queue, stay in AWAITING_ACK            |
|    - On ACK received: if queue empty -> SYNCHRONIZED                 |
|                        if queue non-empty -> send next, stay         |
|    - On remote op: transform pending op against remote op            |
|                                                                      |
|  State 3: AWAITING_ACK_WITH_BUFFER                                   |
|    - One operation sent + more operations buffered                   |
|    - Local state = server state + pending + buffer                   |
|    - On local edit: merge into buffer                                |
|    - On ACK received: send buffer as one composite op                |
|    - On remote op: transform pending AND buffer against remote op    |
+---------------------------------------------------------------------+

State transitions:

  SYNCHRONIZED --[local edit]--> AWAITING_ACK
  AWAITING_ACK --[ACK, no buffer]--> SYNCHRONIZED
  AWAITING_ACK --[local edit]--> AWAITING_ACK_WITH_BUFFER
  AWAITING_ACK_WITH_BUFFER --[ACK]--> AWAITING_ACK (send buffer)
  AWAITING_ACK --[remote op]--> AWAITING_ACK (transform pending)
  AWAITING_ACK_WITH_BUFFER --[remote op]--> AWAITING_ACK_WITH_BUFFER
                                            (transform pending + buffer)
```

### 10.5 Worked Example: Two Users Typing Simultaneously

This is the example you should walk through on the whiteboard in an interview.

```
Initial document: "ABCDE" (revision 5)
Alice is at position 2, Bob is at position 4.

Step 1: Both users type at the same time (neither has seen the other's edit yet)

  Alice: INSERT(pos=2, text="X")  base_revision=5
    Alice's local state: "ABXCDE"
    Alice sends operation to server

  Bob: INSERT(pos=4, text="Y")  base_revision=5
    Bob's local state: "ABCYDE"
    Bob sends operation to server

Step 2: Server receives Alice's operation first (network timing)

  Server state: "ABCDE" (rev=5)
  Alice's op: INSERT(pos=2, "X"), base_rev=5
  
  base_rev (5) == server_rev (5) -> no transform needed
  Apply directly: "ABCDE" -> "ABXCDE"
  Assign revision 6
  
  Server state: "ABXCDE" (rev=6)
  Send ACK(rev=6) to Alice
  Broadcast Alice's op to Bob

Step 3: Server receives Bob's operation

  Server state: "ABXCDE" (rev=6)
  Bob's op: INSERT(pos=4, "Y"), base_rev=5
  
  base_rev (5) < server_rev (6) -> TRANSFORM NEEDED
  Fetch operations since rev 5: [Alice's INSERT(pos=2, "X") at rev 6]
  
  Transform Bob's op against Alice's op:
    Alice inserted "X" at position 2 (before Bob's position 4)
    Bob's position shifts right by 1
    Transformed: INSERT(pos=5, "Y")
  
  Apply transformed op: "ABXCDE" -> "ABXCDYE"
  Assign revision 7
  
  Server state: "ABXCDYE" (rev=7)
  Send ACK(rev=7) to Bob
  Broadcast transformed op to Alice

Step 4: Alice receives Bob's transformed operation

  Alice's local state: "ABXCDE" (rev=6, after her own insert)
  Receives: INSERT(pos=5, "Y") from server (rev=7)
  
  No pending local ops -> apply directly
  "ABXCDE" -> "ABXCDYE"
  
  Alice's state: "ABXCDYE" (rev=7)

Step 5: Bob receives Alice's operation + ACK

  Bob's local state: "ABCYDE" (rev=5, with local unACK'd insert)
  Receives: Alice's INSERT(pos=2, "X") (rev=6)
  
  Bob has a pending local op: INSERT(pos=4, "Y")
  Must transform pending op against Alice's op (already done on server)
  Must also transform Alice's op against pending op for local apply:
    Transform Alice's INSERT(pos=2, "X") against Bob's INSERT(pos=4, "Y"):
    Alice's position (2) < Bob's position (4) -> no shift for Alice
    Alice': INSERT(pos=2, "X")
  
  Apply Alice's op to Bob's local state:
  "ABCYDE" -> "ABXCYDE"
  
  Then Bob receives ACK(rev=7) -> his local op is confirmed
  No need to re-apply (already applied locally)
  
  Bob's state: "ABXCDYE" (rev=7)

CONVERGENCE: Alice, Bob, and Server all have "ABXCDYE" at revision 7.
```

### 10.6 OT Complexity and Limitations

```
Time Complexity:
  - Single transform: O(1) for position arithmetic
  - Transform against N concurrent ops: O(N) -- sequential transforms
  - N concurrent clients, each sending M ops: O(N * M) worst case
  - In practice: N is small (3-5 editors), M is small (ops processed quickly)
  
Space Complexity:
  - Operation log: O(total_operations) -- append-only
  - Active document state: O(document_size)
  - Pending operations per client: O(ops_in_flight) -- typically 0-5

Limitations of OT:
  +--------------------------+-----------------------------------------------+
  | Limitation               | Details                                       |
  +--------------------------+-----------------------------------------------+
  | Central server required  | OT requires a single authority to order ops;  |
  |                          | true peer-to-peer OT is notoriously hard      |
  +--------------------------+-----------------------------------------------+
  | Transform complexity     | Every new operation type (e.g., "move",       |
  |                          | "table insert") requires new transform rules  |
  |                          | for every combination of operation pairs       |
  +--------------------------+-----------------------------------------------+
  | Correctness is fragile   | Getting transform rules wrong leads to        |
  |                          | divergence -- state split that's hard to debug |
  +--------------------------+-----------------------------------------------+
  | Offline is hard          | Without server, operations queue up. Long     |
  |                          | offline periods mean many transforms on       |
  |                          | reconnect, which can be slow                  |
  +--------------------------+-----------------------------------------------+
  | N-way transform          | With >2 concurrent editors, transforms must   |
  |                          | satisfy TP2 (transformation property 2),      |
  |                          | which is even harder to get right             |
  +--------------------------+-----------------------------------------------+

Fun fact: Google Docs has had convergence bugs in production.
The OT algorithm is simple in theory but devilishly hard to get right
for all edge cases with rich text (formatting, lists, tables, etc.).
```

---

## 11. CRDT Deep Dive

### 11.1 What Is a CRDT?

A **Conflict-free Replicated Data Type (CRDT)** is a data structure that can be replicated across multiple nodes, updated independently and concurrently on each node, and then merged automatically without conflicts. The merge is guaranteed to converge to the same state regardless of the order of operations.

**The Core Insight:**

Instead of transforming operations based on positions (like OT), CRDTs assign a **globally unique, immutable ID** to every element (character, paragraph, etc.). Operations reference elements by ID, not position. Since IDs are unique and immutable, there is no ambiguity about which element an operation refers to, and operations commute naturally.

```
OT approach:  "Delete character at position 5"
              (Position 5 changes meaning if someone inserts before it!)

CRDT approach: "Delete character with ID (siteA, seq=42)"
               (ID never changes regardless of other edits!)
```

### 11.2 CRDT Types

```
+------------------+--------------------+----------------------------------------------+
| CRDT Type        | Category           | Use Case                                     |
+------------------+--------------------+----------------------------------------------+
| G-Counter        | Counter            | Increment-only counter (view counts)         |
| PN-Counter       | Counter            | Increment and decrement (likes - dislikes)   |
| LWW-Register     | Register           | Last-writer-wins for single values (title)   |
| MV-Register      | Register           | Multi-value register (tracks conflicts)      |
| G-Set            | Set                | Grow-only set (add elements, never remove)   |
| OR-Set           | Set                | Observed-Remove set (add + remove elements)  |
| LWW-Element-Set  | Set                | Last-writer-wins per element                 |
| RGA              | Sequence (Text)    | Replicated Growable Array (text editing)     |
| LSEQ             | Sequence (Text)    | Logarithmic-space sequence CRDT              |
| Yata             | Sequence (Text)    | Used by Yjs library (Notion, etc.)           |
| TreeDoc          | Sequence (Text)    | Tree-based sequence CRDT                     |
+------------------+--------------------+----------------------------------------------+

For collaborative text editing, we use SEQUENCE CRDTs (RGA, LSEQ, or Yata).
```

### 11.3 RGA (Replicated Growable Array) for Text

RGA is the most intuitive sequence CRDT for text editing. Each character has a unique ID composed of (siteId, sequenceCounter), and the position of each character is determined by its relationship to other characters (inserted after which character).

**Character Structure:**

```
Each character in the document:
{
  id: { siteId: "A", counter: 1 },   // globally unique
  value: "H",                          // the actual character
  parent: { siteId: null, counter: 0 },// inserted after this ID (root for first char)
  tombstone: false                      // true = deleted but retained for merging
}

Document "Hello" created by site A:

  Position 0: { id: (A,1), value: "H", parent: ROOT }
  Position 1: { id: (A,2), value: "e", parent: (A,1) }
  Position 2: { id: (A,3), value: "l", parent: (A,2) }
  Position 3: { id: (A,4), value: "l", parent: (A,3) }
  Position 4: { id: (A,5), value: "o", parent: (A,4) }
```

**Concurrent Insert Example:**

```
Document: "AC" (two characters)
  (A,1) = "A" -> (A,2) = "C"

Site B inserts "B" between A and C:
  new char: { id: (B,1), value: "B", parent: (A,1) }
  Result at B: "ABC"

Concurrently, Site C inserts "X" between A and C:
  new char: { id: (C,1), value: "X", parent: (A,1) }
  Result at C: "AXC"

Now both sites receive each other's operations:

  Site B receives C's insert: { id: (C,1), value: "X", parent: (A,1) }
  Both (B,1) and (C,1) have the same parent (A,1) -- CONFLICT!
  
  Tie-break: compare IDs. "B" < "C" (lexicographic on siteId)
  So (B,1) goes before (C,1)
  
  Site B's document: A -> B -> X -> C = "ABXC"

  Site C receives B's insert: { id: (B,1), value: "B", parent: (A,1) }
  Same conflict, same tie-break rule
  
  Site C's document: A -> B -> X -> C = "ABXC"

CONVERGENCE: Both sites arrive at "ABXC" without any central server.
```

**Delete in CRDT (Tombstones):**

```
To delete a character in a CRDT, we DON'T remove it from the data structure.
Instead, we mark it as a TOMBSTONE (soft delete).

Why? Because other sites may have operations that reference this character
as a parent. If we physically remove it, those operations can't be applied.

Delete "B" from "ABXC":
  Mark (B,1) as tombstone = true
  
  Rendering: skip tombstoned characters
  Visible document: "AXC"
  
  Internal structure still has 4 elements:
  (A,1)="A" -> (B,1)="B"[TOMBSTONE] -> (C,1)="X" -> (A,2)="C"

Problem: Tombstones accumulate over time, wasting memory.
Solution: Periodic garbage collection (compact tombstones when all sites
  have acknowledged they've seen the delete operation).
```

### 11.4 CRDT Advantages

```
+----------------------------------+------------------------------------------------+
| Advantage                        | Details                                        |
+----------------------------------+------------------------------------------------+
| No central server needed         | Any node can accept writes and merge with any  |
|                                  | other node. True peer-to-peer possible.        |
+----------------------------------+------------------------------------------------+
| Works offline                    | Users can edit offline for hours. When they     |
|                                  | reconnect, CRDTs merge automatically. No      |
|                                  | complex conflict resolution on reconnect.      |
+----------------------------------+------------------------------------------------+
| Mathematically proven convergence| CRDTs satisfy Strong Eventual Consistency:     |
|                                  | if two nodes have received the same set of     |
|                                  | operations (in any order), their states are    |
|                                  | identical. This is a mathematical guarantee.   |
+----------------------------------+------------------------------------------------+
| Order-independent merge          | Operations can arrive in any order and the     |
|                                  | result is the same. No need to track or        |
|                                  | enforce operation ordering.                    |
+----------------------------------+------------------------------------------------+
| Simpler server logic             | Server just stores and relays operations.      |
|                                  | No transform computation needed server-side.   |
+----------------------------------+------------------------------------------------+
```

### 11.5 CRDT Disadvantages

```
+----------------------------------+------------------------------------------------+
| Disadvantage                     | Details                                        |
+----------------------------------+------------------------------------------------+
| Metadata overhead                | Every character needs a unique ID (siteId +    |
|                                  | counter) + parent pointer. A 10 KB document    |
|                                  | may need 50-100 KB of CRDT metadata.           |
+----------------------------------+------------------------------------------------+
| Tombstones                       | Deleted characters are retained (soft delete). |
|                                  | Long-lived documents accumulate thousands of   |
|                                  | tombstones, increasing memory usage.           |
+----------------------------------+------------------------------------------------+
| Implementation complexity        | Implementing a correct and efficient sequence  |
|                                  | CRDT from scratch is very challenging.          |
|                                  | Libraries like Yjs and Automerge exist.        |
+----------------------------------+------------------------------------------------+
| Undo is harder                   | Undoing an operation in a CRDT requires        |
|                                  | "inverse operations" that must also commute.   |
|                                  | Much harder than OT's undo.                    |
+----------------------------------+------------------------------------------------+
| Intent preservation              | CRDTs guarantee convergence but not always     |
|                                  | "user intent." Two concurrent inserts at the   |
|                                  | same position may interleave unexpectedly.     |
+----------------------------------+------------------------------------------------+
| Network bandwidth                | Sending CRDT operation payloads (with IDs) is  |
|                                  | larger than OT payloads (just position + text).|
+----------------------------------+------------------------------------------------+
```

### 11.6 OT vs CRDT Comparison

```
+------------------------+--------------------------------+--------------------------------+
| Dimension              | OT (Google Docs approach)      | CRDT (Figma/Notion approach)   |
+------------------------+--------------------------------+--------------------------------+
| Central server         | Required (single source of     | Not required (peer-to-peer     |
|                        | truth for ordering)            | possible)                      |
+------------------------+--------------------------------+--------------------------------+
| Offline support        | Difficult (ops queue up,       | Natural (merge on reconnect,   |
|                        | transform on reconnect)        | order-independent)             |
+------------------------+--------------------------------+--------------------------------+
| Correctness proof      | Fragile (transform rules must  | Mathematically proven (SEC     |
|                        | satisfy TP1 + TP2; bugs in     | guarantee by construction)     |
|                        | practice even at Google)       |                                |
+------------------------+--------------------------------+--------------------------------+
| Metadata overhead      | Low (operations store position | High (every element needs      |
|                        | + content only)                | unique ID + parent reference)  |
+------------------------+--------------------------------+--------------------------------+
| Server complexity      | High (server runs transform    | Low (server just relays; merge |
|                        | engine, holds document state)  | logic is in the data structure)|
+------------------------+--------------------------------+--------------------------------+
| Client complexity      | Medium (client tracks revisions| High (client maintains full    |
|                        | and pending ops)               | CRDT state + tombstones)       |
+------------------------+--------------------------------+--------------------------------+
| Undo/Redo              | Straightforward (invert the    | Complex (inverse ops must also |
|                        | operation, transform it)       | commute with concurrent ops)   |
+------------------------+--------------------------------+--------------------------------+
| New operation types    | O(n^2) work: every new op type | Easier: just define how the    |
|                        | needs transform rules against  | data type merges               |
|                        | every existing op type         |                                |
+------------------------+--------------------------------+--------------------------------+
| Latency                | Slightly lower (smaller        | Slightly higher (larger        |
|                        | payloads, simpler merge)       | payloads with IDs)             |
+------------------------+--------------------------------+--------------------------------+
| Who uses it?           | Google Docs, Microsoft Office  | Figma, Notion (Yjs), Apple    |
|                        | (early), Apache Wave           | Notes, Automerge, xi-editor   |
+------------------------+--------------------------------+--------------------------------+
| Best for               | Centralized systems with       | Decentralized systems, offline |
|                        | reliable server connection     | -first apps, peer-to-peer     |
+------------------------+--------------------------------+--------------------------------+

Interview recommendation:
  Start with OT (it's the classic answer and shows depth).
  Mention CRDT as an alternative.
  If the interviewer asks to compare, use this table.
  Say: "Google Docs uses OT because it was built in 2006 when CRDTs
  were less mature. If I were building from scratch today, I'd seriously
  consider a CRDT library like Yjs -- it's simpler to reason about
  correctness, works offline naturally, and the metadata overhead is
  manageable for text documents."
```

---

## 12. Cursor & Presence

### 12.1 Cursor Tracking

Each user's cursor position and text selection must be visible to all other editors in real-time. This is a key UX feature that makes collaboration feel "live."

**What to track per user:**

```
{
  "user_id": "usr_alice_001",
  "document_id": "doc_123",
  "position": 42,                  // caret position (character index)
  "selection": {                   // null if no selection
    "start": 42,
    "end": 67
  },
  "cursor_color": "#4285F4",       // unique per user per document
  "display_name": "Alice",
  "avatar_url": "https://cdn.example.com/avatars/alice.png"
}
```

**Cursor Color Assignment:**

```
Color pool (10 colors, recycled if more than 10 editors):
  #4285F4 (Google Blue)
  #EA4335 (Red)
  #34A853 (Green)
  #FBBC05 (Yellow)
  #8E24AA (Purple)
  #00ACC1 (Cyan)
  #FF7043 (Deep Orange)
  #5C6BC0 (Indigo)
  #26A69A (Teal)
  #EC407A (Pink)

Assignment: First user gets color[0], second gets color[1], etc.
When a user leaves, their color is returned to the pool.
```

### 12.2 Cursor Update Flow

```
Alice moves her cursor to position 42 in the document.

(1) Alice clicks at position 42 (or arrow keys to that position)
(2) Client detects cursor position change
(3) Client checks throttle: last cursor update was 60ms ago (> 50ms threshold)
(4) Client sends CURSOR message via WebSocket:
    { "type": "CURSOR", "position": 42, "selection": null }
(5) WS Gateway receives message, updates Redis:
    HSET cursor:doc_123 usr_alice_001 '{"pos":42,"sel":null,"ts":1745654400}'
(6) WS Gateway broadcasts CURSOR_UPDATE to all other connected clients:
    {
      "type": "CURSOR_UPDATE",
      "user_id": "usr_alice_001",
      "display_name": "Alice",
      "cursor_color": "#4285F4",
      "position": 42,
      "selection": null
    }
(7) Bob's and Charlie's clients receive the update
(8) Their renderers draw Alice's blue cursor at position 42
(9) A small name label "Alice" appears above the cursor

Throttling:
  - Cursor updates are throttled to MAX once per 50ms (20 updates/sec)
  - Without throttling, a user holding an arrow key generates events every ~30ms
  - 50ms throttle reduces traffic by ~40% with negligible visual difference
  - Selection changes (highlighting text) are throttled the same way
```

### 12.3 Cursor Position Transformation

When a remote operation modifies the document, all cursor positions must be adjusted. Otherwise, cursors would point to the wrong characters.

```
Document: "Hello World" (11 chars)
Alice's cursor is at position 8 (between 'o' and 'r' in "World")

Bob inserts "Beautiful " at position 6 (between "Hello " and "World"):
  New document: "Hello Beautiful World" (21 chars)

Alice's cursor must be transformed:
  Bob's insert was at position 6, which is BEFORE Alice's cursor at 8
  Alice's cursor shifts right by the length of the insert: 10 characters
  New cursor position: 8 + 10 = 18

Alice's cursor now points between 'o' and 'r' in "World" at position 18.
This is the same logical position -- the transform preserved intent.

Transform rules for cursor positions:
  (1) INSERT at pos P with length L:
      - If cursor > P: cursor += L
      - If cursor <= P: no change
  (2) DELETE at pos P with length L:
      - If cursor > P + L: cursor -= L
      - If cursor >= P and cursor <= P + L: cursor = P (collapsed into delete)
      - If cursor < P: no change
```

### 12.4 Presence Indicators

```
+------------------------------------------------------+
|  Document: Q3 Product Roadmap                        |
|  +--------------------------------------------------+
|  |  3 people viewing  [Alice] [Bob] [Charlie] [+2]  |
|  +--------------------------------------------------+
|  |                                                    |
|  |  The Q3 roadmap includes the following|            |
|  |                                       ^            |
|  |                              Alice's blue cursor   |
|  |                                                    |
|  |  initiatives for the product te|am:                |
|  |                                ^                   |
|  |                       Bob's red cursor              |
|  |                                                    |
|  |  1. [Improve onboarding flow]  <- Charlie's green  |
|  |      ^^^^^^^^^^^^^^^^^^^^^^^^     highlight         |
|  |      (Charlie is selecting this text)              |
|  +--------------------------------------------------+
|                                                       |
|  Presence bar shows:                                  |
|  - User avatars with colored borders matching cursors |
|  - Click avatar to jump to their cursor position      |
|  - Tooltip: "Alice - editing" / "Diana - viewing"     |
|  - "+2" overflow for more than 5 visible avatars      |
+------------------------------------------------------+
```

---

## 13. Version History

### 13.1 Snapshot Strategy

The version history system uses **periodic snapshots** combined with an **operation log** to allow reconstructing the document at any point in time.

```
Timeline of document edits:

Rev:  0    50   100  150  200  250  300  ...  1000
      |    |    |    |    |    |    |          |
      S0   .    S1   .    S2   .    S3    ...  S10
      ^         ^         ^         ^          ^
  Snapshot  Snapshot  Snapshot  Snapshot   Snapshot

Between snapshots: individual operations stored in operation log.

To reconstruct document at revision 175:
  (1) Find nearest snapshot BEFORE 175 -> S1 at revision 100
  (2) Fetch operations 101 through 175 from operation log
  (3) Replay 75 operations on top of S1
  (4) Result: document state at revision 175

Cost: O(1) snapshot fetch + O(75) operation replay = fast

Without snapshots, reconstructing revision 175 from scratch:
  Replay all 175 operations from revision 0 = much slower for large docs
```

### 13.2 Diff Computation

```
User wants to see what changed between version at rev 100 and rev 200.

(1) Reconstruct document at rev 100 (from snapshot S1)
(2) Reconstruct document at rev 200 (from snapshot S2)
(3) Run a text diff algorithm (Myers diff, O(ND) complexity)
(4) Present diff to user:

    The Q3 roadmap includes the following
  - initiatives for the engineering team:
  + initiatives for the product team:
    1. Improve onboarding flow
  + 2. Launch new dashboard
  + 3. Migrate to Java 21
    
    [Deleted section]
  - This section was removed by Bob on April 25.

(5) Optionally annotate: show WHO made each change using operation metadata
    - "Bob deleted 'engineering' and inserted 'product' at 10:15 AM"
    - "Alice added items 2 and 3 at 10:22 AM"
```

### 13.3 Operation Log Compaction

Over time, the operation log grows large. Compaction reduces storage while preserving the ability to reconstruct any version at snapshot boundaries.

```
Compaction strategy:

  Active period (0-7 days):
    - Keep ALL individual operations (full granularity)
    - Enables fine-grained undo, per-keystroke history

  Recent period (7-30 days):
    - Keep snapshots every 100 operations
    - Compact operations between snapshots into composite operations
    - E.g., 100 individual INSERTs of single characters -> 1 INSERT of a paragraph
    
  Archive period (30-365 days):
    - Keep daily snapshots only
    - Delete individual operations
    - Users can still see daily versions, but not per-keystroke history

  Purge period (>365 days):
    - Keep weekly snapshots for 1 additional year
    - Then monthly snapshots indefinitely (or per retention policy)

Storage savings example (for a document with 100,000 operations over 1 year):
  Without compaction: 100,000 ops * 100 bytes = 10 MB
  With compaction:    365 daily snapshots * 50 KB + 30 days of ops = 18.65 MB
  Hmm -- snapshots are larger than ops. But the ops enable reconstruction.
  
  Actual strategy: keep snapshots sparse, keep recent ops, compact old ops.
  Net savings: ~60-70% after 6 months.
```

---

## 14. Concurrency

### 14.1 Concurrent Edits

The primary concurrency challenge in this system is handling multiple users editing the same document at the same time.

```
Concurrency model: Single-writer per document (at the server level)

(1) Each document has a distributed lock (Redis SETNX with TTL)
(2) Only one operation is processed at a time per document
(3) Operations from different documents are fully parallel
(4) Lock granularity: per-document, not per-server or per-user

Why single-writer per document?
  OT requires a total ordering of operations. If two operations were
  processed concurrently for the same document, they might both read
  the same revision, both transform against the same base, and produce
  conflicting results. The lock ensures serial processing.

Throughput impact:
  Single-writer means max ~1000 ops/sec per document
  (limited by lock acquire + transform + persist + lock release = ~1ms)
  
  With 50 ops/sec per active document, this is 20x headroom.
  Even a "hot" document with 50 editors typing fast (200 ops/sec)
  is well within the limit.

For platform-level throughput (100M ops/sec across all documents):
  Each document is independent -> partition by documentId
  100M ops/sec / 1000 ops/sec per doc = 100K document partitions
  With 100 Collaboration Service nodes, each handles 1000 documents
```

### 14.2 Operation Ordering

```
Operations must be applied in a total order per document.
This is achieved by the server-assigned revision number.

Server revision: monotonically increasing integer per document.
  Revision 1, 2, 3, ..., N

Every operation gets a unique revision. No two operations share a revision.
This is guaranteed by the document-level lock.

Client-side ordering:
  Clients may receive operations out of order (network reordering).
  Client applies operations in revision order:
    If received rev=8 but haven't seen rev=7 yet, buffer rev=8.
    When rev=7 arrives, apply 7 then 8.
```

### 14.3 Causal Consistency

```
Causal consistency: if operation A causally depends on operation B
(i.e., A was created after seeing B's effect), then all clients
must apply B before A.

How we ensure this:
  (1) Each client operation includes base_revision
  (2) base_revision = the last server revision the client has applied
  (3) Server processes operations in total order (via lock)
  (4) Broadcasts include the server_revision
  (5) Clients apply operations in revision order

Example of causal dependency:
  Alice sees Bob's rev=6 edit, then types a reply at rev=6.
  Alice's operation has base_revision=6.
  Server knows Alice SAW rev=6 before creating her op.
  Alice's op will be assigned rev=7 or later.
  
  Any client receiving rev=7 has already received rev=6 (total order),
  so they see the same causal chain.

This is stronger than eventual consistency but weaker than linearizability.
It's the right consistency level for collaborative editing.
```

---

## 15. Scaling

### 15.1 Partition Strategy

```
Primary partitioning: by documentId

Every component partitions work by documentId:
  - WebSocket Gateways: sticky sessions by hash(documentId)
  - Collaboration Service: route by hash(documentId) % N
  - Kafka topics: partitioned by documentId as key
  - PostgreSQL: shard by documentId (if needed at extreme scale)
  - Redis: slot assignment by documentId key

Why documentId?
  All operations on a single document must be serialized.
  Partitioning by documentId ensures all ops for one doc
  go to the same Collaboration Service node -> document-level
  lock is local, not distributed.

+------------------------------------------------------------------+
|                    PARTITIONING BY DOCUMENT                        |
|                                                                    |
|  Documents A-D     Documents E-H     Documents I-L                |
|  +------------+    +------------+    +------------+               |
|  | Collab     |    | Collab     |    | Collab     |               |
|  | Service 1  |    | Service 2  |    | Service 3  |               |
|  |            |    |            |    |            |               |
|  | doc_A lock |    | doc_E lock |    | doc_I lock |               |
|  | doc_B lock |    | doc_F lock |    | doc_J lock |               |
|  | doc_C lock |    | doc_G lock |    | doc_K lock |               |
|  | doc_D lock |    | doc_H lock |    | doc_L lock |               |
|  +-----+------+    +-----+------+    +-----+------+               |
|        |                 |                 |                       |
|        v                 v                 v                       |
|  +------------+    +------------+    +------------+               |
|  | Kafka      |    | Kafka      |    | Kafka      |               |
|  | Partition 1|    | Partition 2|    | Partition 3|               |
|  +------------+    +------------+    +------------+               |
+------------------------------------------------------------------+
```

### 15.2 WebSocket Scaling

```
Challenge: WebSocket connections are stateful and long-lived.
  Each connection is pinned to a specific gateway node.
  If that node dies, all connections must reconnect.

Strategy: Sticky sessions + graceful failover

Load Balancer Configuration:
  - L7 (HTTP/WebSocket aware) load balancer
  - Sticky: hash(documentId + userId) -> specific gateway node
  - Health check: ping gateway every 5 seconds
  - Draining: on node restart, send "reconnect" message to all clients
    before shutting down; clients reconnect to a new node

Connection Limits:
  - Each gateway node: ~50,000 WebSocket connections
  - 5M concurrent connections / 50K per node = 100 gateway nodes
  - Each connection: ~10 KB memory (socket buffers + session state)
  - Per node memory for connections: 50K * 10 KB = 500 MB

Fan-out for cross-gateway broadcast:
  When Alice (on Gateway-1) edits doc_123, and Bob (on Gateway-2)
  is also editing doc_123, the operation must reach Bob.
  
  Solution: Kafka as the cross-gateway message bus.
  
  (1) Gateway-1 receives Alice's op, routes to Collaboration Service
  (2) Collaboration Service processes op, publishes to Kafka topic ops.doc_123
  (3) ALL gateway nodes subscribe to Kafka
  (4) Gateway-2 receives op from Kafka, checks if any local clients
      are connected to doc_123 -> yes, Bob is -> sends to Bob
  (5) Gateway nodes with no clients for doc_123 discard the message

Optimization: Instead of all gateways subscribing to all topics,
  use Kafka consumer groups so each gateway only receives ops for
  documents it has active connections for.
```

### 15.3 Hot Documents

```
A "hot document" is one with many concurrent editors (e.g., 500 people
editing a company-wide doc during an all-hands meeting).

Challenges:
  (1) Single document lock becomes a bottleneck at >1000 ops/sec
  (2) Broadcasting to 500 clients on every keystroke is expensive
  (3) Cursor updates from 500 users flood the network

Mitigations:

  Operation Batching:
    Instead of processing one op at a time, batch ops in 10ms windows.
    Process 5-10 ops per lock acquisition.
    Reduces lock overhead by 5-10x.

  Broadcast Throttling:
    Don't broadcast every individual operation.
    Batch operations over a 50ms window and broadcast as a composite.
    500 users * 50 ops/sec = 25,000 ops/sec -> 500 broadcasts/sec (50ms batch)

  Cursor Sampling:
    With 500 users, showing all cursors is visual noise.
    Show cursors for "nearby" users only (within viewport).
    Or show cursors for the 10 most recently active users.

  Read Replicas:
    Document loading (initial state fetch) can go to read replicas.
    Only the active editing path needs the primary.
```

### 15.4 Scaling Numbers Summary

```
+-------------------------+-------------------+--------------------------------------+
| Component               | Node Count        | Scaling Factor                       |
+-------------------------+-------------------+--------------------------------------+
| WebSocket Gateways      | 100 nodes         | 5M connections / 50K per node        |
| Collaboration Service   | 50 nodes          | 100M ops/sec / 2M ops/sec per node   |
| Document Service        | 20 nodes          | 10K doc loads/sec / 500 per node     |
| Presence Service        | 10 nodes          | Lightweight; mostly Redis reads      |
| Version History Service | 10 nodes          | Background snapshots, low QPS        |
| PostgreSQL (primary)    | 5 shards          | By documentId hash range             |
| PostgreSQL (replicas)   | 15 (3 per shard)  | Read scaling for doc loads           |
| Redis Cluster           | 10 nodes          | Presence, cursors, sessions, locks   |
| Kafka Cluster           | 15 brokers        | Operation log + event fan-out        |
+-------------------------+-------------------+--------------------------------------+
```

---

## 16. Database Choice

### 16.1 PostgreSQL -- Documents, Operations, Versions, Permissions, Comments

```
Why PostgreSQL?

  +---------------------------+----------------------------------------------+
  | Reason                    | Details                                      |
  +---------------------------+----------------------------------------------+
  | ACID transactions         | Operation persist + revision increment must   |
  |                           | be atomic -- a partial write corrupts state   |
  +---------------------------+----------------------------------------------+
  | Strong consistency        | Document state is the source of truth; we     |
  |                           | cannot tolerate stale reads for operations    |
  +---------------------------+----------------------------------------------+
  | Rich indexing             | Composite indexes on (document_id, revision)  |
  |                           | for fast operation log queries                |
  +---------------------------+----------------------------------------------+
  | JSONB support             | Operation attributes, formatting stored as    |
  |                           | JSONB for flexibility                         |
  +---------------------------+----------------------------------------------+
  | Proven at scale           | Shardable by documentId; read replicas for    |
  |                           | document loading                              |
  +---------------------------+----------------------------------------------+

Tables stored in PostgreSQL:
  - documents (100M rows, ~5 TB)
  - operations (billions of rows, ~15 TB with 30-day retention)
  - versions (snapshots, ~500M rows, ~25 TB)
  - permissions (~200M rows, ~20 GB)
  - comments (~500M rows, ~50 GB)

Sharding strategy:
  Shard by hash(document_id) % 5 -> 5 shards
  Each shard holds ~20M documents
  Operations and versions co-located with their document (same shard)
```

### 16.2 Redis -- Cursors, Presence, Sessions, Locks

```
Why Redis?

  +---------------------------+----------------------------------------------+
  | Reason                    | Details                                      |
  +---------------------------+----------------------------------------------+
  | Sub-millisecond latency   | Cursor and presence updates must be fast;    |
  |                           | Redis serves reads in <1ms                   |
  +---------------------------+----------------------------------------------+
  | TTL / auto-expiry         | Cursor and presence entries auto-expire when  |
  |                           | user disconnects (no cleanup needed)          |
  +---------------------------+----------------------------------------------+
  | Pub/Sub                   | Can broadcast presence changes to interested  |
  |                           | nodes (alternative to Kafka for ephemeral)   |
  +---------------------------+----------------------------------------------+
  | Distributed locks         | SETNX for document-level locks with TTL      |
  +---------------------------+----------------------------------------------+
  | Sorted sets               | Presence tracking with heartbeat timestamps   |
  +---------------------------+----------------------------------------------+

Redis data structures:
  - cursor:{documentId} (Hash) -- cursor positions per user
  - presence:{documentId} (Sorted Set) -- active users with heartbeat timestamps
  - user_presence:{userId} (Hash) -- user's current document and status
  - ws_conn:{userId}:{documentId} (String) -- WebSocket server assignment
  - lock:{documentId} (String) -- distributed lock for operation processing
  - perm:{documentId}:{userId} (String) -- permission cache (TTL 60s)

Memory estimate:
  5M active connections * ~500 bytes per connection state = ~2.5 GB
  2M active documents * ~2 KB per document cursor/presence = ~4 GB
  Total Redis memory: ~8-10 GB (fits comfortably in a 10-node cluster)
```

### 16.3 Kafka -- Operation Log, Event Fan-out

```
Why Kafka?

  +---------------------------+----------------------------------------------+
  | Reason                    | Details                                      |
  +---------------------------+----------------------------------------------+
  | Durable operation log     | Operations persisted to Kafka as the primary |
  |                           | event stream before PostgreSQL write          |
  +---------------------------+----------------------------------------------+
  | Fan-out to gateways       | Multiple WebSocket Gateways consume the same |
  |                           | operation stream for broadcast                |
  +---------------------------+----------------------------------------------+
  | Ordered delivery          | Kafka partitions guarantee ordering within a  |
  |                           | partition; partition by documentId = ordered  |
  |                           | operations per document                       |
  +---------------------------+----------------------------------------------+
  | Replay capability         | If a gateway restarts, it can replay recent  |
  |                           | operations from Kafka to catch up             |
  +---------------------------+----------------------------------------------+
  | Decoupling                | Collaboration Service publishes; multiple     |
  |                           | consumers (gateways, analytics, snapshotter)  |
  +---------------------------+----------------------------------------------+

Kafka topics:
  - document-operations:
      Partitions: 100 (by hash(documentId) % 100)
      Retention: 24 hours (short-term; PostgreSQL is the long-term store)
      Throughput: 100M msgs/sec (across all partitions)
  
  - document-presence:
      Partitions: 20
      Retention: 1 hour
      Throughput: ~1M msgs/sec (presence changes, less frequent)
  
  - document-events:
      Partitions: 20
      Retention: 7 days
      Events: document created, shared, restored, deleted

Kafka sizing:
  100M ops/sec * 100 bytes = 10 GB/sec throughput
  15 brokers with replication factor 3
  Each broker: ~2 GB/sec write throughput (NVMe SSD)
```

---

## 17. CAP Theorem

```
+------------------+------+------+------+----------------------------------------+
| Component        | C    | A    | P    | Strategy                               |
+------------------+------+------+------+----------------------------------------+
| Document State   | YES  | yes  | YES  | CP: Consistency is critical.           |
| (operations,     |      |      |      | All clients MUST converge to the same  |
| revisions)       |      |      |      | state. During a network partition,     |
|                  |      |      |      | reject operations rather than allow    |
|                  |      |      |      | divergent states.                      |
+------------------+------+------+------+----------------------------------------+
| Presence /       | no   | YES  | YES  | AP: Availability matters more.         |
| Cursors          |      |      |      | If presence data is slightly stale     |
|                  |      |      |      | (shows user as "online" for 30 extra   |
|                  |      |      |      | seconds), that's acceptable. Better    |
|                  |      |      |      | to show stale presence than no         |
|                  |      |      |      | presence at all.                       |
+------------------+------+------+------+----------------------------------------+
| Permissions      | YES  | yes  | YES  | CP: Permission checks must be          |
|                  |      |      |      | consistent. A revoked user must not    |
|                  |      |      |      | be able to edit. Short-lived cache     |
|                  |      |      |      | (60s TTL) is acceptable.               |
+------------------+------+------+------+----------------------------------------+
| Version History  | yes  | YES  | YES  | AP: Version browsing can tolerate      |
|                  |      |      |      | slightly stale snapshots. Eventually   |
|                  |      |      |      | consistent.                            |
+------------------+------+------+------+----------------------------------------+
| Comments         | yes  | YES  | YES  | AP: Comments can be eventually         |
|                  |      |      |      | consistent. A few seconds of delay     |
|                  |      |      |      | is acceptable.                         |
+------------------+------+------+------+----------------------------------------+

(YES = prioritized, yes = supported but secondary, no = sacrificed)

Key insight for interviews:
  "Document state is CP because convergence is the entire point of the system.
  If two clients diverge, the document is corrupted. We'd rather reject an
  operation during a partition than risk divergence. But presence is AP because
  showing 'Alice is online' when she's actually offline for 30 seconds is
  harmless."
```

---

## 18. Cloud Services

```
+----------------------------+------------------+--------------------------------+
| Component                  | AWS              | GCP                            |
+----------------------------+------------------+--------------------------------+
| WebSocket Gateway          | API Gateway      | Cloud Run (with WebSocket      |
|                            | (WebSocket API)  | support) or GKE                |
|                            | + ECS/EKS        |                                |
+----------------------------+------------------+--------------------------------+
| Collaboration Service      | ECS Fargate      | Cloud Run / GKE                |
|                            | or EKS           |                                |
+----------------------------+------------------+--------------------------------+
| Document Service           | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Presence Service           | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| Version History Service    | ECS Fargate      | Cloud Run / GKE                |
+----------------------------+------------------+--------------------------------+
| PostgreSQL                 | RDS PostgreSQL   | Cloud SQL for PostgreSQL       |
|                            | or Aurora         | or AlloyDB                     |
+----------------------------+------------------+--------------------------------+
| Redis                      | ElastiCache      | Memorystore for Redis          |
|                            | (Redis)          |                                |
+----------------------------+------------------+--------------------------------+
| Kafka                      | MSK (Managed     | Pub/Sub (or Confluent          |
|                            | Streaming for    | Cloud on GCP)                  |
|                            | Kafka)           |                                |
+----------------------------+------------------+--------------------------------+
| Load Balancer              | ALB (Application | Cloud Load Balancing (L7)      |
|                            | Load Balancer)   |                                |
+----------------------------+------------------+--------------------------------+
| Monitoring                 | CloudWatch       | Cloud Monitoring               |
+----------------------------+------------------+--------------------------------+
| Object Storage             | S3 (for document | Cloud Storage (for document    |
| (snapshots/attachments)    | snapshots)       | snapshots)                     |
+----------------------------+------------------+--------------------------------+
```

---

## 19. Tradeoffs Summary

```
+-----+------------------------------------+------------------------------------+
| #   | Decision                           | Tradeoff                           |
+-----+------------------------------------+------------------------------------+
| T1  | OT over CRDT (primary approach)    | OT: simpler payload, lower latency,|
|     |                                    | well-understood (Google Docs).     |
|     |                                    | But: requires central server, no   |
|     |                                    | offline support, fragile transforms|
|     |                                    | CRDT: offline works, proven correct|
|     |                                    | But: metadata overhead, tombstones |
+-----+------------------------------------+------------------------------------+
| T2  | Centralized OT (server as source   | Simplifies architecture (one total |
|     | of truth) over peer-to-peer OT     | order). But: server is a single    |
|     |                                    | point of failure for each document.|
|     |                                    | Mitigated by document partitioning.|
+-----+------------------------------------+------------------------------------+
| T3  | Document-level lock over           | Ensures correctness (serial ops    |
|     | lock-free approach                 | per doc). But: limits throughput   |
|     |                                    | to ~1000 ops/sec per document.     |
|     |                                    | Acceptable: max practical rate is  |
|     |                                    | ~200 ops/sec (50 concurrent users).|
+-----+------------------------------------+------------------------------------+
| T4  | Snapshots + operation replay over  | Fast document loads (replay from   |
|     | full state after every edit        | recent snapshot). But: more complex|
|     |                                    | logic. Alternative (full save per  |
|     |                                    | edit) would be too slow (50 ops/s  |
|     |                                    | * 50 KB = 2.5 MB/sec per doc).    |
+-----+------------------------------------+------------------------------------+
| T5  | WebSocket over HTTP polling /      | Real-time bidirectional, sub-100ms |
|     | Server-Sent Events                 | latency. But: stateful connections,|
|     |                                    | sticky sessions, harder to scale.  |
|     |                                    | SSE is simpler but one-directional.|
|     |                                    | HTTP polling is too slow (>1s).    |
+-----+------------------------------------+------------------------------------+
| T6  | Kafka for operation fan-out over   | Durable, ordered, replayable.      |
|     | Redis Pub/Sub                      | But: higher latency (~5-10ms vs    |
|     |                                    | ~1ms for Redis Pub/Sub).           |
|     |                                    | Kafka wins: durability > latency   |
|     |                                    | for operation log.                 |
+-----+------------------------------------+------------------------------------+
| T7  | Sticky sessions by documentId     | Co-locates editors on same gateway |
|     | over random load balancing         | = fast local broadcast. But:       |
|     |                                    | uneven load if one doc has many    |
|     |                                    | editors. Mitigated by monitoring   |
|     |                                    | and rebalancing.                   |
+-----+------------------------------------+------------------------------------+
| T8  | Optimistic local apply over        | User sees their edit instantly     |
|     | wait-for-server-ack               | (<1ms). But: must handle rollback  |
|     |                                    | if server rejects. Optimistic is   |
|     |                                    | essential for real-time feel.      |
+-----+------------------------------------+------------------------------------+
| T9  | Cursor throttling (50ms) over     | Reduces network traffic by 40%.    |
|     | sending every cursor movement      | But: cursors appear slightly less  |
|     |                                    | smooth for remote viewers. 50ms    |
|     |                                    | is imperceptible to humans.        |
+-----+------------------------------------+------------------------------------+
| T10 | PostgreSQL over DynamoDB for       | ACID transactions for operation    |
|     | operations                         | persistence. But: PostgreSQL       |
|     |                                    | requires sharding at scale.        |
|     |                                    | DynamoDB scales easier but lacks   |
|     |                                    | multi-row transactions.            |
+-----+------------------------------------+------------------------------------+
| T11 | Tombstones in CRDT over physical   | Preserves merge correctness.       |
|     | deletion                           | But: memory grows with deletes.    |
|     |                                    | Mitigated by periodic garbage      |
|     |                                    | collection when all sites ack.     |
+-----+------------------------------------+------------------------------------+
| T12 | CP for document state over AP      | Convergence is non-negotiable.     |
|     |                                    | But: operations fail during        |
|     |                                    | partitions. Mitigated: partitions  |
|     |                                    | are rare in a managed cloud env.   |
+-----+------------------------------------+------------------------------------+
```

---

## 20. Interview Talking Points

### Opening Statement (30 seconds)

> "I'll design a real-time collaborative editor like Google Docs. The system serves 100M documents with 10M daily active users. The core challenge is **conflict resolution** -- when multiple users edit the same document simultaneously, all edits must merge correctly so every client converges to the same state. I'll use **Operational Transform** with a centralized server as the primary approach, with WebSockets for real-time sync, and briefly discuss CRDTs as an alternative."

### Top 10 Points to Hit

```
+----+-----------------------------------+---------------------------------------------+
| #  | Talking Point                     | Key Phrase                                   |
+----+-----------------------------------+---------------------------------------------+
| 1  | OT transform example              | "Walk through INSERT vs INSERT transform    |
|    |                                   | on whiteboard -- this IS the interview"      |
+----+-----------------------------------+---------------------------------------------+
| 2  | Centralized OT architecture       | "Server is single source of truth. Assigns  |
|    |                                   | revision numbers. Transforms late-arriving  |
|    |                                   | ops against already-applied ones."           |
+----+-----------------------------------+---------------------------------------------+
| 3  | Client-side optimistic apply      | "Client applies edits locally immediately   |
|    |                                   | (no network wait). Sends to server. Server  |
|    |                                   | ACKs or transforms. Client reconciles."      |
+----+-----------------------------------+---------------------------------------------+
| 4  | WebSocket for real-time           | "Persistent bidirectional connection.        |
|    |                                   | Heartbeat every 30s. 5-second reconnect     |
|    |                                   | grace period. Sticky sessions by docId."     |
+----+-----------------------------------+---------------------------------------------+
| 5  | Snapshot + operation replay       | "Event sourcing pattern. Periodic snapshots |
|    |                                   | every 100 ops. Reconstruct any version by   |
|    |                                   | replaying ops from nearest snapshot."        |
+----+-----------------------------------+---------------------------------------------+
| 6  | CRDT as alternative               | "CRDTs assign unique IDs to every char.     |
|    |                                   | No central server needed. Works offline.    |
|    |                                   | But: metadata overhead and tombstones."      |
+----+-----------------------------------+---------------------------------------------+
| 7  | Partition by documentId           | "Everything partitions by documentId:        |
|    |                                   | Kafka, PostgreSQL, Redis, Collab Service.   |
|    |                                   | Operations on different docs are fully       |
|    |                                   | parallel."                                   |
+----+-----------------------------------+---------------------------------------------+
| 8  | Document-level lock               | "Single-writer per document via Redis SETNX.|
|    |                                   | Ensures total ordering. Throughput limit     |
|    |                                   | ~1000 ops/sec per doc, 20x above need."      |
+----+-----------------------------------+---------------------------------------------+
| 9  | Presence via Redis + ephemeral    | "Cursors and presence in Redis with TTL.    |
|    |                                   | Throttle cursor updates to 50ms intervals.  |
|    |                                   | AP consistency for presence (stale is OK)."  |
+----+-----------------------------------+---------------------------------------------+
| 10 | CP for doc state, AP for presence | "Document convergence is non-negotiable     |
|    |                                   | (CP). Presence can be stale (AP). Comments  |
|    |                                   | eventually consistent."                      |
+----+-----------------------------------+---------------------------------------------+
```

### Common Follow-up Questions

```
Q: "How do you handle offline editing?"
A: "With OT: buffer operations locally. On reconnect, send all buffered ops
   with the base_revision from when the client went offline. Server transforms
   them against all ops that happened while offline. Can be slow for long
   offline periods. With CRDT: offline edits merge naturally on reconnect
   because CRDTs are order-independent. CRDT is the better choice if offline
   is a hard requirement."

Q: "What if the server crashes mid-operation?"
A: "Operations are written to Kafka BEFORE the PostgreSQL transaction.
   On restart, the Collaboration Service replays unacknowledged operations
   from Kafka. The PostgreSQL write is idempotent (upsert with operation_id).
   The client retries unACK'd operations with the same local_op_id."

Q: "How do you handle undo/redo with OT?"
A: "Each client maintains a local undo stack of its OWN operations.
   On undo, the client generates the INVERSE operation (INSERT becomes DELETE,
   DELETE becomes INSERT) and sends it through the normal OT pipeline.
   The inverse is transformed against any concurrent operations that happened
   since the original, so undo correctly handles concurrent edits."

Q: "How do you handle a document with 1000 concurrent editors?"
A: "This is a hot-document problem. Mitigations:
   (1) Batch operations in 10ms windows to reduce lock contention.
   (2) Throttle cursor broadcasts to 50ms intervals.
   (3) Show only nearby or recently active cursors (not all 1000).
   (4) Consider splitting the document into sections with separate
       operation streams (Notion's block-based approach)."

Q: "Why not just use a database lock (SELECT FOR UPDATE)?"
A: "Database locks have higher latency (~5-10ms per acquire) compared to
   Redis SETNX (~0.5ms). At 50 ops/sec per document, a 10ms lock would
   consume 50% of our throughput budget. Redis lock is ~20x faster.
   Also, Redis lock has a TTL -- if the service crashes, the lock auto-
   releases. Database locks require explicit rollback or connection timeout."

Q: "What about rich text formatting (bold, italic, headings)?"
A: "Each operation can carry formatting attributes as metadata.
   For example: INSERT(pos=5, text='Hello', attrs={bold:true}).
   We also need a FORMAT operation type that changes attributes of
   existing text without inserting or deleting characters.
   The OT transform rules for FORMAT are additional complexity --
   FORMAT vs INSERT, FORMAT vs DELETE, FORMAT vs FORMAT. This is
   why Google Docs' OT codebase is reportedly 10,000+ lines."

Q: "How would you implement Google Docs' 'suggestion mode'?"
A: "Suggestions are a layer on top of the document. Each suggestion
   is stored as a pair: (proposed_operations, anchor_range).
   When a reviewer accepts a suggestion, the proposed operations
   are submitted through the normal OT pipeline. When rejected,
   the suggestion is simply deleted. Suggestions must also be
   transformed when the underlying document changes (their anchor
   positions shift, just like cursor positions)."
```

### Complexity Cheat Sheet

```
+-----------------------------------+-------------------+----------------------------+
| Operation                         | Time Complexity   | Notes                      |
+-----------------------------------+-------------------+----------------------------+
| Single OT transform               | O(1)              | Position arithmetic        |
| Transform against N concurrent ops| O(N)              | Sequential transforms      |
| Document load (snapshot + replay)  | O(K)              | K = ops since snapshot     |
| Broadcast to M clients             | O(M)              | Fan-out via Kafka          |
| Cursor transform                   | O(1)              | Position shift             |
| Snapshot creation                  | O(D)              | D = document size          |
| Version diff                       | O(N*D)            | Myers diff algorithm       |
| Permission check (cached)          | O(1)              | Redis lookup               |
| CRDT insert                        | O(log N)          | Tree-based position find   |
| CRDT merge                         | O(N + M)          | Merge two sequences        |
+-----------------------------------+-------------------+----------------------------+
```

### Architecture Diagram for Whiteboard (Simplified)

```
Draw this on the whiteboard in the first 3 minutes:

  +--------+     +--------+     +--------+
  |Client A|     |Client B|     |Client C|
  +---+----+     +---+----+     +---+----+
      |              |              |
      | WebSocket    | WebSocket    | WebSocket
      v              v              v
  +--------------------------------------+
  |        WebSocket Gateway             |
  |  (auth, session, heartbeat, route)   |
  +------------------+-------------------+
                     |
                     v
  +--------------------------------------+
  |      Collaboration Service           |
  |  +------------------------------+    |
  |  |    OT Transform Engine       |    |
  |  |  (transform concurrent ops)  |    |
  |  +------------------------------+    |
  |  | Revision Manager | Validator |    |
  +--+---------+--------+-----------+----+
               |                |
        +------+------+   +----+------+
        |             |   |           |
        v             v   v           v
  +-----------+  +---------+  +-------------+
  | PostgreSQL|  |  Redis  |  |    Kafka    |
  | (docs,ops |  | (cursor,|  | (op log,    |
  |  versions)|  | presence|  |  fan-out)   |
  +-----------+  |  locks) |  +-------------+
                 +---------+

Then walk through the numbered flow:
  (1) Client sends OPERATION via WebSocket
  (2) Gateway routes to Collaboration Service
  (3) Collaboration Service transforms via OT
  (4) Persists to PostgreSQL + publishes to Kafka
  (5) ACKs the client
  (6) Kafka fans out to other gateways
  (7) Gateways deliver to other clients
```

---

*This design covers the full scope of a real-time collaboration tool at interview depth. The star of this interview is Section 10 (OT Deep Dive) -- practice walking through the transform example on a whiteboard until it's second nature. Mention CRDTs as an alternative but default to OT for the primary design. The architecture (WebSocket + OT + Kafka fan-out) is clean and defensible.*
