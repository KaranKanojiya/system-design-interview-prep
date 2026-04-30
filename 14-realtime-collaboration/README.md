# Real-Time Collaboration Tool (Google Docs)

## Problem Summary

Design a **real-time collaboration tool** (like Google Docs) that supports 100K+ concurrent documents with multiple users editing simultaneously. The core challenges are **Operational Transformation (OT)** -- transforming concurrent operations so they converge to the same document state regardless of arrival order. The server is the source of truth; when two users type at the same position, OT shifts the later operation's position by the length of the earlier insert. Operations are **INSERT(pos, text)** and **DELETE(pos, length)**, each carrying a **baseVersion** that the server uses to determine which prior operations need to be transformed against. **Presence** (cursor positions + selection colors) is broadcast via WebSocket every 50ms locally, throttled to 500ms for network broadcast; stale presence is cleaned up after 30 seconds of inactivity. **Version history** is reconstructed from periodic S3 snapshots (every 100 operations) plus operation log replay from DynamoDB -- load the nearest snapshot, replay remaining ops, reconstruct any point in time in < 200ms. **WebSocket connections** use ECS Fargate with ALB sticky sessions so all users on the same document route to the same container, which holds the document state **in-memory** for sub-millisecond OT transforms and instant broadcast. The alternative to OT is **CRDTs (Conflict-free Replicated Data Types)** which auto-merge without a central server (Figma uses this), but at the cost of higher storage overhead (~5x per character for CRDT metadata). The system is **CP for document operations** (convergence is critical -- all users must see identical text) and **AP for presence** (a stale cursor position for 500ms is invisible to users).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **OT: transform concurrent operations so they converge. Server is source of truth. Google Docs uses this.** When two users edit simultaneously, the server receives both operations with their baseVersion. If User A's INSERT(10, "Hello") is applied first (v42->v43), and User B's INSERT(10, "World") arrives with baseVersion=42, the server transforms B's op: position 10 shifts to 15 (10 + length of "Hello"). Both users converge to "...HelloWorld..." regardless of network timing. OT is centralized -- one server decides the canonical order. Simpler mental model than CRDTs, battle-tested at Google scale.
- **CRDT: conflict-free data types that auto-merge. No server needed. Figma uses this.** Each character gets a unique ID (e.g., fractional index or Lamport timestamp). Insertions between two characters get an ID between them. Deletes are tombstoned, not removed. Any two replicas receiving the same set of operations in ANY order converge to the same state. No transform step, no central server. Trade-off: 5x storage overhead for CRDT metadata per character, more complex undo/redo, but zero-latency local edits and natural multi-region support.
- **OT transform rules: INSERT vs INSERT -> shift position. INSERT vs DELETE -> adjust boundary. DELETE vs DELETE -> merge ranges.** INSERT(10,"Hello") vs INSERT(10,"World"): second insert shifts to position 15. INSERT(5,"X") vs DELETE(3, 4): if insert is inside deleted range, insert at delete start; if insert is after delete, shift left by delete length. DELETE(5,3) vs DELETE(5,3): identical deletes cancel out (idempotent). These three rules handle 90% of collaboration conflicts.
- **Operations: INSERT(pos, text), DELETE(pos, length). Each op has baseVersion for OT transform.** Client sends operation + baseVersion (the version the client last saw). Server compares baseVersion to current serverVersion. If they match, apply directly. If baseVersion < serverVersion, transform the incoming op against all operations between baseVersion and serverVersion. This is the OT core loop. Each applied operation increments serverVersion by 1.
- **Presence: cursor positions + colors broadcast via WebSocket every 50ms. Stale presence cleaned up.** Each user gets a unique color (assigned on join). Client sends cursor position every 50ms to local state, throttled to 500ms for network broadcast. Server stores presence in Redis: HSET presence:docId userId '{"pos":42,"color":"#FF5733","ts":...}'. Presence older than 30 seconds is marked idle. On disconnect, presence is removed. This is AP -- eventual consistency is perfectly fine for cursor positions.
- **Version history: periodic snapshots + operation log. Reconstruct any version by replay.** Every 100 operations (or 5 minutes), a snapshot of the full document is written to S3. The operation log in DynamoDB is append-only (PK=docId, SK=version). To reconstruct version N: find nearest snapshot before N (e.g., snapshot at v500), load from S3, replay ops v501 through vN from DynamoDB. Without snapshots, reconstructing v5000 means replaying 5000 ops. With snapshots, max replay is 99 ops. Snapshots are cheap (~50 KB each).
- **CAP: CP for document (convergence critical), AP for presence (stale cursor OK).** Document operations MUST be CP: if two users see different text, the tool is broken. OT guarantees convergence -- the server is the single source of truth, and all operations are totally ordered by version number. Presence is AP: showing a cursor at position 42 when it's actually at position 45 is invisible to users. During a network partition, presence can be stale but documents must not diverge.

---

## Class Hierarchy

```
Document (domain entity)                Operation (value object)
  |-- documentId, title                   |-- operationId (UUID)
  |-- ownerId                             |-- documentId
  |-- content (current text)              |-- type: INSERT | DELETE
  |-- version (monotonic counter)         |-- position (0-based index)
  |-- collaborators: List<UserId>         |-- text (for INSERT)
  |-- status: ACTIVE | ARCHIVED           |-- length (for DELETE)
  |-- createdAt, updatedAt                |-- baseVersion (client's last seen version)
  |-- permissions: Map<UserId, Role>      |-- userId (who made the edit)
                                          |-- timestamp
                                          |-- No setters (immutable)

Cursor (value object)                   DocumentSnapshot (value object)
  |-- userId                              |-- documentId
  |-- documentId                          |-- version (snapshot taken at this version)
  |-- position (caret index)              |-- content (full document text)
  |-- selectionStart, selectionEnd        |-- s3Url (storage location)
  |-- color (unique per user)             |-- timestamp
  |-- lastUpdated (for stale detection)   |-- No setters (immutable)

TransformStrategy (interface)           ConflictResolutionStrategy (interface)
  |-- OTTransformStrategy                  |-- ServerAuthorityStrategy
  |     (centralized, server transforms)   |     (server version wins, Google Docs)
  |-- CRDTMergeStrategy                    |-- LastWriterWinsStrategy
  |     (decentralized, auto-merge)        |     (timestamp-based, simple)
  |-- TransformStrategyFactory             |-- CRDTMergeResolutionStrategy
  |     (picks OT or CRDT by config)       |     (deterministic merge, Figma)

PersistenceStrategy (interface)         BroadcastStrategy (interface)
  |-- AppendLogPersistenceStrategy         |-- DirectWebSocketBroadcast
  |     (DynamoDB append-only ops log)     |     (in-process, same container)
  |-- SnapshotPersistenceStrategy          |-- RedisPubSubBroadcast
  |     (periodic S3 snapshots)            |     (cross-container fan-out)
  |-- HybridPersistenceStrategy            |-- BroadcastStrategyFactory
  |     (ops log + snapshots together)     |     (picks based on topology)

CollaborationService                    PresenceService
  |-- onConnect(userId, docId)            |-- updateCursor(userId, docId, position)
  |     -> load doc, assign color, join   |-- getCursors(docId) -> Map<UserId, Cursor>
  |-- onOperation(userId, operation)      |-- onDisconnect(userId, docId)
  |     -> transform, apply, broadcast    |-- cleanStalePresence(docId)
  |-- onDisconnect(userId, docId)         |-- assignColor(userId) -> String
  |     -> remove presence, notify

DocumentService                         VersionHistoryService
  |-- createDocument(userId, title)       |-- getVersionAt(docId, timestamp)
  |-- getDocument(docId) -> Document      |     -> find snapshot + replay ops
  |-- shareDocument(docId, userId, role)  |-- takeSnapshot(docId)
  |-- listDocuments(userId)               |     -> write full doc to S3
  |-- deleteDocument(docId)               |-- listVersions(docId) -> List<Version>
                                          |-- diffVersions(docId, v1, v2) -> Diff

AppConfig (wiring)
  |-- creates services, strategies
  |-- wires WebSocket -> collaboration -> persistence
  |-- configures ECS, ALB, DynamoDB, Redis, S3
  |-- sticky session routing by documentId
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Document` | Core domain entity. Holds current text content, version counter, collaborator list, and permissions. Version increments with every applied operation. |
| `Operation` | Immutable value object representing a single edit: INSERT(pos, text) or DELETE(pos, length). Carries baseVersion for OT transform. Stored in DynamoDB ops log. |
| `Cursor` | Value object for user presence. Position, selection range, unique color. Broadcast via WebSocket, stored in Redis. Stale after 30 seconds. |
| `DocumentSnapshot` | Periodic full-document capture stored in S3. Created every 100 operations. Enables fast version reconstruction (snapshot + replay) instead of replaying entire ops log. |
| `TransformStrategy` | Strategy pattern: OTTransformStrategy (centralized server transforms concurrent ops) vs CRDTMergeStrategy (decentralized auto-merge). Factory selects by configuration. |
| `ConflictResolutionStrategy` | Strategy pattern: ServerAuthorityStrategy (OT -- server orders operations), LastWriterWins (simple but lossy), CRDTMergeResolution (deterministic merge by character ID). |
| `PersistenceStrategy` | Strategy pattern: AppendLog (every op to DynamoDB), Snapshot (periodic S3), Hybrid (both -- production choice for fast writes + fast reconstruction). |
| `BroadcastStrategy` | Strategy pattern: DirectWebSocket (same container, < 1ms) vs RedisPubSub (cross-container, < 10ms). Direct is used when sticky sessions route all doc users to same container. |
| `CollaborationService` | Core orchestrator: receives operations via WebSocket, runs OT transform, applies to in-memory document state, persists to DynamoDB, broadcasts to all collaborators. |
| `PresenceService` | Manages cursor positions and user colors. Updates stored in Redis. Broadcasts every 500ms. Cleans up stale presence (no update for 30s). |
| `DocumentService` | CRUD for documents: create, share, list, delete. Manages permissions (owner, editor, viewer). Uses RDS Aurora for relational queries. |
| `VersionHistoryService` | Reconstructs any historical version: find nearest snapshot in S3, replay ops from DynamoDB. Takes periodic snapshots. Lists version timeline. |
| `AppConfig` | Wires everything together. ECS tasks, ALB sticky sessions, DynamoDB tables, Redis clusters, S3 buckets, WebSocket handlers. Single entry point for demo. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Conflict resolution | OT (centralized server transforms) | CRDT (decentralized auto-merge) | **OT** -- simpler mental model, battle-tested at Google scale, lower storage overhead. CRDT better for offline-first or multi-region without master. |
| WebSocket hosting | API Gateway WebSocket + Lambda (serverless) | ECS + ALB sticky sessions (persistent containers) | **ECS + sticky sessions** -- in-memory document state for sub-ms OT transforms. Lambda cold start (100-500ms) is too slow for real-time collaboration. |
| Operation persistence | Write every op synchronously to DB | Batch writes (buffer in memory, flush every 100ms) | **Batch writes** -- 10x fewer DynamoDB writes. Acceptable risk: lose last 100ms of ops on container crash (re-type a few characters). |
| Version reconstruction | Replay all ops from start | Periodic snapshots + replay remaining ops | **Snapshots every 100 ops** -- max replay is 99 ops (< 50ms) vs thousands of ops (seconds). Snapshots are cheap (50 KB to S3). |
| Presence broadcast | Every cursor move (50ms) | Throttled (every 500ms) | **Throttled 500ms** -- 10x fewer WebSocket messages. Users cannot perceive 500ms cursor lag on remote collaborators. |
| Document state | Stateless (load from DB per operation) | Stateful (in-memory per container) | **Stateful** -- OT transforms must be fast (< 1ms). DB round-trip per op adds 5-20ms latency, unacceptable for real-time typing. |
| Multi-region | Single region (simpler) | Multi-region with master election | **Single region** with client-side prediction. Multi-region OT requires master region; cross-region latency (~200ms) handled by optimistic local apply. |
| Undo/redo | Client-only (local undo stack) | Server-aware (inverse operations stored) | **Server-aware** -- store inverse of each operation. Undo = apply inverse op through normal OT pipeline. Ensures undo is consistent across collaborators. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | TransformStrategy (OT vs CRDT) | Swap conflict resolution algorithm without changing collaboration pipeline |
| **Strategy** | PersistenceStrategy (AppendLog vs Snapshot vs Hybrid) | Swap persistence approach without changing collaboration service |
| **Strategy** | BroadcastStrategy (DirectWebSocket vs RedisPubSub) | Swap broadcast mechanism based on deployment topology |
| **Strategy** | ConflictResolutionStrategy (ServerAuthority vs CRDT merge) | Swap resolution policy for different consistency requirements |
| **Observer** | WebSocket connection -> CollaborationService -> BroadcastStrategy | Decouple operation reception from processing from delivery |
| **Command** | Operation as immutable command object (INSERT/DELETE) | Operations are serializable, transformable, replayable, invertible |
| **Memento** | DocumentSnapshot (captures full state at a point in time) | Enable version history without storing every intermediate state |
| **Factory** | TransformStrategyFactory (picks OT or CRDT by config) | Encapsulate algorithm selection, single creation point |
| **Repository** | DocumentRepository, OperationRepository, SnapshotRepository | Abstract storage; swap DynamoDB/Redis/S3 implementations |
| **Template Method** | Base collaboration flow: receive -> validate -> transform -> apply -> persist -> broadcast | Fixed sequence; subclasses override specific steps (e.g., OT vs CRDT transform step) |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :14-realtime-collaboration:run
```

---

## Demo Output Preview

```
========================================
  REAL-TIME COLLABORATION TOOL (GOOGLE DOCS) DEMO
========================================

--- Document Creation Demo ---
User U001 (Alice) creates a new document: "System Design Notes"
  Document{id='doc_001', title='System Design Notes', owner='U001', version=0}
  Sharing with U002 (Bob) as EDITOR
  Sharing with U003 (Charlie) as VIEWER

--- WebSocket Connection Demo ---
User U001 (Alice) connects to doc_001...
  WebSocket established: connectionId='conn_001'
  Loaded document state: version=0, content="" (empty)
  Assigned presence color: #FF5733 (red)
  Active collaborators: [Alice]

User U002 (Bob) connects to doc_001...
  WebSocket established: connectionId='conn_002'
  Loaded document state: version=0, content="" (empty)
  Assigned presence color: #33FF57 (green)
  Broadcast: "Bob joined" -> [Alice]
  Active collaborators: [Alice, Bob]

--- Operational Transformation Demo ---
Alice types "Hello " at position 0:
  Client A sends: { op: INSERT(0, "Hello "), baseVersion: 0 }
  Server: baseVersion=0 == serverVersion=0 -> no transform needed
  Applied: doc = "Hello "
  Server version: 0 -> 1
  DynamoDB: PK=doc_001, SK=v#1, op=INSERT(0,"Hello "), userId=U001
  Broadcast to Alice: { type: ACK, version: 1 }
  Broadcast to Bob:   { type: REMOTE_OP, version: 1, op: INSERT(0,"Hello ") }

Bob types "World" at position 0 (concurrent, hasn't seen Alice's edit):
  Client B sends: { op: INSERT(0, "World"), baseVersion: 0 }
  Server: baseVersion=0 < serverVersion=1 -> TRANSFORM NEEDED
  Transform against v1 [INSERT(0,"Hello ")]:
    INSERT(0,"World") vs INSERT(0,"Hello "): same position, Alice first (lower userId)
    Transformed: INSERT(6, "World")  (shifted right by len("Hello ")=6)
  Applied: doc = "Hello World"
  Server version: 1 -> 2
  DynamoDB: PK=doc_001, SK=v#2, op=INSERT(6,"World"), userId=U002
  Broadcast to Bob:   { type: ACK, version: 2, serverOp: INSERT(6,"World") }
  Broadcast to Alice: { type: REMOTE_OP, version: 2, op: INSERT(6,"World") }

  Both clients now see: "Hello World"  (CONVERGED)

--- Presence Demo ---
Alice moves cursor to position 5:
  Presence update: { userId: U001, pos: 5, color: #FF5733 }
  Redis: HSET presence:doc_001 U001 '{"pos":5,"color":"#FF5733","ts":1234567890}'
  Broadcast to Bob: { type: PRESENCE, userId: U001, cursor: { pos: 5, color: "#FF5733" } }
  Bob's editor shows: red cursor at position 5 (between "Hello" and " World")

Bob moves cursor to position 11:
  Presence update: { userId: U002, pos: 11, color: #33FF57 }
  Broadcast to Alice: { type: PRESENCE, userId: U002, cursor: { pos: 11, color: "#33FF57" } }
  Alice's editor shows: green cursor at end of "Hello World"

--- DELETE Operation Demo ---
Alice deletes "Hello " (position 0, length 6):
  Client A sends: { op: DELETE(0, 6), baseVersion: 2 }
  Server: baseVersion=2 == serverVersion=2 -> no transform
  Applied: doc = "World"
  Server version: 2 -> 3
  Broadcast to Bob: { type: REMOTE_OP, version: 3, op: DELETE(0, 6) }
  Both clients now see: "World"

--- Concurrent DELETE Demo ---
Bob types "Beautiful " at position 0 (baseVersion=3):
Alice deletes "World" at position 0 (baseVersion=3, concurrent):
  Bob's op arrives first: INSERT(0, "Beautiful ")
  Applied: doc = "Beautiful World"  version: 3 -> 4

  Alice's op arrives: DELETE(0, 5) with baseVersion=3
  Server at version 4 -> transform against [INSERT(0,"Beautiful ")]
  Transform: DELETE(0,5) vs INSERT(0,"Beautiful "):
    Insert at pos 0 shifts delete range right by insert length (10)
    Transformed: DELETE(10, 5)
  Applied: doc = "Beautiful "  version: 4 -> 5
  Both clients see: "Beautiful "  (CONVERGED)

--- Version History Demo ---
Taking snapshot at version 5...
  Snapshot: { docId: doc_001, version: 5, content: "Beautiful " }
  Stored: s3://snapshots/doc_001/v#5/snapshot.json (52 bytes)

... (100 more operations happen) ...

Reconstructing document at version 50:
  Nearest snapshot: v#5 (from S3)
  Replay ops v#6 through v#50 from DynamoDB (45 operations)
  Reconstruction time: 12ms
  Result: "Beautiful system design notes with detailed..."

--- Disconnect + Reconnect Demo ---
Bob disconnects (network drop)...
  Presence removed: Redis HDEL presence:doc_001 U002
  Broadcast to Alice: { type: PRESENCE_LEAVE, userId: U002 }
  Alice's editor: Bob's green cursor disappears

Bob reconnects after 3 seconds...
  WebSocket re-established: connectionId='conn_003'
  Server sends: current document state + version=105
  Bob's client: had local version=102
  Server sends: ops v#103, v#104, v#105 (catch-up)
  Bob applies missed operations -> document state synchronized
  Presence restored: green cursor reappears for Alice

========================================
  DEMO COMPLETE -- PROJECT 14 FINISHED!
========================================
```

---

## Quick Reference

```
OT (Operational Transformation): Centralized server transforms concurrent ops to converge. Google Docs. Server = source of truth.
CRDT (Conflict-free Replicated Data Types): Decentralized auto-merge. Figma. No server needed. Higher storage overhead (~5x).
Operations:         INSERT(pos, text), DELETE(pos, length). Each carries baseVersion for transform.
Transform rules:    INSERT vs INSERT -> shift position. INSERT vs DELETE -> adjust boundary. DELETE vs DELETE -> merge/cancel.
WebSocket:          ECS Fargate + ALB sticky sessions. All users on same doc -> same container. In-memory state for < 1ms transforms.
Presence:           Cursor position + color per user. Redis Pub/Sub. Broadcast every 500ms. Stale cleanup at 30s.
Version history:    S3 snapshots every 100 ops + DynamoDB ops log. Reconstruct any version: nearest snapshot + replay remaining ops.
Snapshots:          Full document written to S3 every 100 ops or 5 minutes. Max replay = 99 ops. Without snapshots: replay thousands.
Batch persistence:  Buffer ops in memory, flush to DynamoDB every 100ms. 10x fewer writes. Risk: lose ~100ms of ops on crash.
Sticky sessions:    ALB cookie hashes documentId -> same ECS container. In-memory doc state, direct WebSocket broadcast.
Failover:           Container dies -> ALB routes to new container -> load from Redis/DynamoDB -> resume in 5-10 seconds.
CAP choice:         CP for document operations (convergence critical). AP for presence (stale cursor is fine).
Multi-region:       OT needs master region (centralized). Client-side prediction hides cross-region latency. CRDT naturally multi-region.
```

---

## What to Improve Later

- [ ] Full Document entity with version tracking and permission validation
- [ ] OTTransformStrategy with complete transform rules (INSERT/INSERT, INSERT/DELETE, DELETE/DELETE)
- [ ] CRDTMergeStrategy with Yjs-style sequence CRDT implementation
- [ ] PresenceService with Redis Pub/Sub, color assignment, stale cleanup
- [ ] VersionHistoryService with S3 snapshots and DynamoDB ops replay
- [ ] WebSocket handler with connection lifecycle (connect, message, disconnect)
- [ ] Undo/redo via inverse operations through OT pipeline
- [ ] Offline editing queue with reconnect sync (buffer local ops, replay on reconnect)
- [ ] Rich text support (bold, italic, headings -- operations on attributed spans)
- [ ] Permission enforcement (viewer cannot send operations, only receive)
- [ ] Rate limiting per user (prevent operation flooding)
