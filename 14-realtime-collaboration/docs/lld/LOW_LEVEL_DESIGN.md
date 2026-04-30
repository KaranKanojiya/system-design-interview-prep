# Low-Level Design: Real-Time Collaboration Tool (Google Docs)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Operational Transformation (OT), CRDTs, Conflict Resolution, Real-Time Sync, Cursor Presence, Version History
> This is the distributed editing interview question. It tests your understanding of OT vs. CRDT
> tradeoffs, concurrent conflict resolution, real-time cursor/presence broadcasting, version
> snapshots, event-sourced persistence, and permission-controlled shared editing -- all with
> concurrency-safe design.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Service Layer Design](#7-service-layer-design)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [SOLID Principles Applied](#9-solid-principles-applied)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: Document (Builder, docId, title, content via StringBuilder, version, ownerId), Operation (opId, docId, userId, type INSERT/DELETE/RETAIN, position, content, version, timestamp), OperationType (enum), DocumentVersion (versionId, docId, version number, content snapshot, operations since last snapshot, timestamp), CursorPosition (userId, docId, position, selectionStart, selectionEnd, color), UserPresence (userId, name, docId, isActive, lastActiveAt, cursorColor), Permission (docId, userId, role OWNER/EDITOR/VIEWER, grantedAt), PermissionRole (enum), Comment (commentId, docId, userId, content, position, resolvedAt). |
| **OT (Conflict Resolution)** | `ot/` | Pluggable conflict resolution: ConflictResolver interface with OTResolver (server-centric Operational Transform -- transforms concurrent ops against each other, Google Docs model) and CRDTResolver (simulated RGA/CRDT -- conflict-free replicated data type, Figma model). TransformResult value object holds transformedLocal + transformedRemote. |
| **Strategy (Sync)** | `strategy/sync/` | Pluggable sync: SyncStrategy interface with OTSyncStrategy (centralized server transforms all ops before applying -- consistent ordering) and CRDTSyncStrategy (each client applies locally, merges converge eventually -- no central coordinator). Strategy pattern -- swap sync model without touching services. |
| **Strategy (Persistence)** | `strategy/persistence/` | Pluggable persistence: PersistenceStrategy interface with SnapshotPersistence (periodic snapshots + operation log -- fast reads, moderate storage) and EventSourcedPersistence (store all ops, rebuild document from event replay -- full audit trail, higher read cost). |
| **Service** | `service/` | Business logic: CollaborationService (Facade -- receives ops, transforms, applies, broadcasts), DocumentService (CRUD, content management), OperationService (processes operations, applies OT/CRDT transforms), PresenceService (cursor tracking, active users per doc), VersionService (snapshots, history, rollback), PermissionService (access control, sharing), BroadcastService (WebSocket simulation -- broadcast ops to connected users). |
| **Repository** | `repository/` | Data access layer: DocumentRepository, OperationRepository, VersionRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like + WebSocket-simulated entry point: CollaborationController maps requests to CollaborationService. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | CollaborationStatsDisplay: active sessions, operation throughput, conflict rate, version history stats. |
| **Exception** | `exception/` | Domain exceptions: CollaborationException (base), ConflictException, DocumentNotFoundException, PermissionDeniedException. |

### Why Real-Time Collaboration Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you understand OT vs. CRDT tradeoffs?                       --> Core Algorithm
  2. Can you explain how concurrent edits are transformed?           --> Conflict Resolution
  3. Is the server the single source of truth (OT model)?           --> Consistency Model
  4. Do you handle cursor presence for multiple users?              --> Real-Time UX
  5. Is version history modeled with snapshots + op logs?           --> Version Control
  6. Are sync strategies pluggable (OT vs. CRDT)?                  --> Strategy Pattern
  7. Is CollaborationService a clean Facade over sub-services?     --> Facade Pattern
  8. Do you separate conflict resolution from persistence?          --> Separation of Concerns
  9. Can you add a new conflict algorithm without changing services? --> Open-Closed
  10. Is operation processing thread-safe for concurrent users?      --> Concurrency
```

---

## 2. Package Structure

```
com.systemdesign.collaboration
|
+-- model/
|   +-- Document.java             -- Builder, docId, title, content (StringBuilder), version, ownerId
|   +-- Operation.java            -- opId, docId, userId, type (INSERT/DELETE/RETAIN), position, content, version, timestamp
|   +-- OperationType.java        -- enum: INSERT, DELETE, RETAIN
|   +-- DocumentVersion.java      -- versionId, docId, version number, content snapshot, ops since last snapshot, timestamp
|   +-- CursorPosition.java       -- userId, docId, position (int), selectionStart, selectionEnd, color
|   +-- UserPresence.java         -- userId, name, docId, isActive, lastActiveAt, cursorColor
|   +-- Permission.java           -- docId, userId, role (OWNER/EDITOR/VIEWER), grantedAt
|   +-- PermissionRole.java       -- enum: OWNER, EDITOR, VIEWER
|   +-- Comment.java              -- commentId, docId, userId, content, position, resolvedAt
|
+-- ot/
|   +-- ConflictResolver.java     -- interface: resolve(Operation local, Operation remote) -> List<Operation>
|   +-- OTResolver.java           -- Operational Transform implementation (server-centric)
|   +-- CRDTResolver.java         -- CRDT-based implementation (simulated RGA)
|   +-- TransformResult.java      -- transformedLocal, transformedRemote
|
+-- strategy/
|   +-- sync/
|   |   +-- SyncStrategy.java          -- interface: applyOperation(Document, Operation) -> Document
|   |   +-- OTSyncStrategy.java        -- server-centric OT (Google Docs model)
|   |   +-- CRDTSyncStrategy.java      -- decentralized CRDT (Figma model)
|   |
|   +-- persistence/
|       +-- PersistenceStrategy.java    -- interface: save, snapshot, getHistory
|       +-- SnapshotPersistence.java    -- periodic snapshots + operation log
|       +-- EventSourcedPersistence.java-- store all ops, rebuild from events
|
+-- service/
|   +-- CollaborationService.java -- FACADE: receives ops, transforms, applies, broadcasts
|   +-- DocumentService.java      -- CRUD, content management
|   +-- OperationService.java     -- processes operations, applies OT/CRDT
|   +-- PresenceService.java      -- cursor tracking, active users per doc
|   +-- VersionService.java       -- snapshots, history, rollback
|   +-- PermissionService.java    -- access control, sharing
|   +-- BroadcastService.java     -- WebSocket simulation: broadcast ops to connected users
|
+-- repository/
|   +-- DocumentRepository.java, InMemoryDocumentRepository.java
|   +-- OperationRepository.java, InMemoryOperationRepository.java
|   +-- VersionRepository.java, InMemoryVersionRepository.java
|
+-- controller/
|   +-- CollaborationController.java
|
+-- config/
|   +-- AppConfig.java
|
+-- display/
|   +-- CollaborationStatsDisplay.java
|
+-- exception/
|   +-- CollaborationException.java
|   +-- ConflictException.java
|   +-- DocumentNotFoundException.java
|   +-- PermissionDeniedException.java
|
+-- RealtimeCollaborationApp.java  -- Main demo: wires everything, runs collaboration scenarios
```

---

## 3. Class Diagram

```
+=====================================================================+
|             THE CORE PROBLEM: CONCURRENT EDITS                       |
+=====================================================================+

  User A (NYC)           Server             User B (London)
      |                    |                      |
      |--- INSERT 'X'     |                      |
      |   at pos 5 -------+-----> transform       |
      |                    |         |             |
      |                    |         |   <--- DELETE pos 3 ---|
      |                    |         |             |
      |                    |   A' = transform(A, B)
      |                    |   B' = transform(B, A)
      |                    |         |             |
      |  <--- apply A' ---|         |--- apply B' ---->
      |                    |                      |
      |   BOTH CONVERGE TO THE SAME DOCUMENT STATE |
      +--------------------------------------------+

  Without OT/CRDT: last write wins = DATA LOSS.
  With OT/CRDT: all edits preserved, documents converge.


+=====================================================================+
|          ANTI-PATTERN: NAIVE LAST-WRITE-WINS (DO NOT DO THIS)        |
+=====================================================================+

  // --- BAD: NaiveCollaborationService.java ---
  //
  // This is how a junior developer might approach concurrent editing.
  // It "works" for a single user but DESTROYS data with multiple users.
  //
  //   class NaiveCollaborationService {
  //       private final Map<String, String> documents = new HashMap<>();  // <-- NOT thread-safe
  //
  //       public void applyEdit(String docId, String newContent) {
  //           // Problem 1: No conflict detection. Last caller wins.
  //           // Problem 2: No operation transform. Edits are absolute, not relative.
  //           // Problem 3: HashMap is not thread-safe. Race conditions.
  //           // Problem 4: No version tracking. Can't detect stale edits.
  //           // Problem 5: No operation log. Can't undo or audit.
  //           documents.put(docId, newContent);   // <-- OVERWRITES everything
  //       }
  //   }

  Timeline showing WHY last-write-wins fails:

  t=0   Document: "Hello World"
        User A sees: "Hello World"
        User B sees: "Hello World"

  t=1   User A starts typing "Hello Beautiful World"
        User B starts typing "Hello World!"

  t=2   User A submits: "Hello Beautiful World"
        Server: documents.put("doc1", "Hello Beautiful World")
        Document is now: "Hello Beautiful World"

  t=3   User B submits: "Hello World!"
        Server: documents.put("doc1", "Hello World!")       <-- A's edit GONE
        Document is now: "Hello World!"

  RESULT: User A's "Beautiful" insertion is SILENTLY LOST.
          No error, no warning, no trace. Just gone.

  +----------------------------------------------------------------+
  |  INTERVIEW RED FLAG: If you describe a replace-entire-document  |
  |  approach, the interviewer knows you don't understand the       |
  |  fundamental problem. Edits must be OPERATIONS (insert/delete   |
  |  at position), not full-document replacements.                  |
  +----------------------------------------------------------------+


+=====================================================================+
|          CLEAN SOLUTION: OPERATION-BASED OT ARCHITECTURE             |
+=====================================================================+

  +-------------------------------------------------------------------+
  |            <<interface>>  ConflictResolver                          |
  |-------------------------------------------------------------------|
  | + resolve(local: Operation, remote: Operation): List<Operation>    |
  | + getResolverName(): String                                        |
  +-------------------------------------------------------------------+
        ^                                    ^
        |                                    |
   implements                           implements
        |                                    |
  +-----+------------------+   +-------------+------------------+
  | OTResolver             |   | CRDTResolver                   |
  |------------------------|   |--------------------------------|
  | -pendingOps: Queue     |   | -charIds: Map<CharId, Char>    |
  | -serverVersion: int    |   | -ordering: List<CharId>        |
  |------------------------|   |--------------------------------|
  | resolve():             |   | resolve():                     |
  |  Transform ops against |   |  Each char has unique ID +     |
  |  each other using      |   |  position in ordered sequence. |
  |  INSERT/DELETE rules.  |   |  Insertions never conflict     |
  |  Server is arbiter.    |   |  because IDs are unique.       |
  |  O(n) per transform.   |   |  Merge = union of all chars    |
  |                        |   |  with deterministic ordering.  |
  |  Used by: Google Docs  |   |  Used by: Figma, Yjs          |
  +------------------------+   +--------------------------------+


  +-------------------------------------------------------------------+
  |            <<interface>>  SyncStrategy                              |
  |-------------------------------------------------------------------|
  | + applyOperation(doc: Document, op: Operation): Document           |
  | + handleConflict(doc: Document, local: Operation,                  |
  |                  remote: Operation): Document                      |
  | + getStrategyName(): String                                        |
  +-------------------------------------------------------------------+
        ^                                    ^
        |                                    |
   implements                           implements
        |                                    |
  +-----+------------------+   +-------------+------------------+
  | OTSyncStrategy         |   | CRDTSyncStrategy               |
  |------------------------|   |--------------------------------|
  | -resolver: OTResolver  |   | -resolver: CRDTResolver        |
  | -opLog: List<Operation>|   | -localState: Document          |
  |------------------------|   |--------------------------------|
  | applyOperation():      |   | applyOperation():              |
  |  1. Check version      |   |  1. Apply locally immediately  |
  |  2. Transform against  |   |  2. Broadcast to peers         |
  |     pending ops        |   |  3. On receive: merge states   |
  |  3. Apply transformed  |   |  4. Convergence guaranteed     |
  |  4. Increment version  |   |     by CRDT properties         |
  |  5. Broadcast result   |   |                                |
  |                        |   |  No central server needed.     |
  |  Requires server.      |   |  Higher memory (char IDs).     |
  +------------------------+   +--------------------------------+


  +-------------------------------------------------------------------+
  |            <<interface>>  PersistenceStrategy                       |
  |-------------------------------------------------------------------|
  | + save(docId: String, ops: List<Operation>): void                  |
  | + snapshot(docId: String, content: String, version: int): void     |
  | + getHistory(docId: String, fromVersion: int): List<Operation>     |
  | + rebuild(docId: String): Document                                 |
  | + getStrategyName(): String                                        |
  +-------------------------------------------------------------------+
        ^                                    ^
        |                                    |
   implements                           implements
        |                                    |
  +-----+------------------+   +-------------+------------------+
  | SnapshotPersistence    |   | EventSourcedPersistence        |
  |------------------------|   |--------------------------------|
  | -snapshots: Map        |   | -eventLog: Map<String,         |
  | -opLog: Map            |   |    List<Operation>>             |
  | -snapshotInterval: int |   |                                |
  |------------------------|   |--------------------------------|
  | save():                |   | save():                        |
  |  Append ops to log.    |   |  Append ALL ops to event log.  |
  |  Every N ops, take     |   |  Never snapshot. Full history. |
  |  snapshot of current   |   |                                |
  |  content.              |   | rebuild():                     |
  |                        |   |  Replay ALL ops from the       |
  | rebuild():             |   |  beginning to reconstruct      |
  |  Load latest snapshot, |   |  document.                     |
  |  replay ops since.     |   |                                |
  |  Fast: only replays    |   |  Slow for old docs, but        |
  |  recent ops.           |   |  perfect audit trail.          |
  +------------------------+   +--------------------------------+


+=====================================================================+
|              FULL CLASS DEPENDENCY DIAGRAM                            |
+=====================================================================+

  CollaborationController
       |
       v
  CollaborationService  -------- <<Facade>>
       |
       +----> DocumentService -------> DocumentRepository
       |           |
       |           +----------------> VersionService -------> VersionRepository
       |
       +----> OperationService ------> OperationRepository
       |           |
       |           +----> SyncStrategy (OT or CRDT)
       |           |           |
       |           |           +----> ConflictResolver (OTResolver or CRDTResolver)
       |           |
       |           +----> PersistenceStrategy (Snapshot or EventSourced)
       |
       +----> PresenceService
       |           |
       |           +----> BroadcastService (cursor updates)
       |
       +----> PermissionService
       |
       +----> BroadcastService ------> connected sessions (WebSocket simulation)


  AppConfig (wires everything)
       |
       +----> creates Repository instances (InMemory*)
       +----> creates ConflictResolver (OTResolver)
       +----> creates SyncStrategy (OTSyncStrategy, injected with resolver)
       +----> creates PersistenceStrategy (SnapshotPersistence)
       +----> creates Services (injected with repos + strategies)
       +----> creates CollaborationService (injected with all services)
       +----> creates Controller (injected with CollaborationService)
```

---

## 4. Entity Design

### 4.1 Document.java (Builder Pattern)

```java
/**
 * Core document entity. Uses StringBuilder for efficient character-level mutations.
 * Builder pattern for flexible construction in tests and service layer.
 *
 * WHY StringBuilder not String?
 *   - String is immutable: every insert/delete creates a new String object
 *   - StringBuilder allows O(n) insert at position, O(n) delete at position
 *   - For a collaboration tool, we do THOUSANDS of inserts/deletes per minute
 *   - String concatenation would be O(n^2) over time; StringBuilder is O(n) amortized
 */
public class Document {
    private final String docId;                // UUID, immutable after creation
    private String title;
    private final StringBuilder content;       // mutable character buffer
    private int version;                       // monotonically increasing, used by OT
    private final String ownerId;
    private final Instant createdAt;
    private Instant lastModifiedAt;

    // ---- Builder ----
    public static class Builder {
        private String docId;
        private String title;
        private String content = "";           // default empty document
        private int version = 0;
        private String ownerId;

        public Builder docId(String docId)       { this.docId = docId; return this; }
        public Builder title(String title)       { this.title = title; return this; }
        public Builder content(String content)   { this.content = content; return this; }
        public Builder version(int version)      { this.version = version; return this; }
        public Builder ownerId(String ownerId)   { this.ownerId = ownerId; return this; }

        public Document build() {
            Objects.requireNonNull(docId, "docId is required");
            Objects.requireNonNull(ownerId, "ownerId is required");
            return new Document(this);
        }
    }

    private Document(Builder builder) {
        this.docId = builder.docId;
        this.title = builder.title;
        this.content = new StringBuilder(builder.content);
        this.version = builder.version;
        this.ownerId = builder.ownerId;
        this.createdAt = Instant.now();
        this.lastModifiedAt = this.createdAt;
    }

    // --- Content mutation methods (called by OT/CRDT strategies) ---

    /**
     * Insert text at position. Used by OT INSERT operations.
     * @param position 0-based index in content
     * @param text     characters to insert
     * @throws IndexOutOfBoundsException if position > content.length()
     */
    public void insertAt(int position, String text) {
        if (position < 0 || position > content.length()) {
            throw new IndexOutOfBoundsException(
                "Insert position " + position + " out of bounds [0, " + content.length() + "]");
        }
        content.insert(position, text);
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Delete 'length' characters starting at position.
     * @param position 0-based start index
     * @param length   number of characters to delete
     */
    public void deleteAt(int position, int length) {
        if (position < 0 || position + length > content.length()) {
            throw new IndexOutOfBoundsException(
                "Delete range [" + position + ", " + (position + length) + ") out of bounds");
        }
        content.delete(position, position + length);
        this.lastModifiedAt = Instant.now();
    }

    public void incrementVersion() { this.version++; }

    public String getContent()       { return content.toString(); }
    public int getContentLength()    { return content.length(); }
    public String getDocId()         { return docId; }
    public String getTitle()         { return title; }
    public int getVersion()          { return version; }
    public String getOwnerId()       { return ownerId; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getLastModifiedAt() { return lastModifiedAt; }
}
```

### 4.2 Operation.java

```java
/**
 * Represents a single edit operation on a document.
 *
 * KEY DESIGN DECISION: Operations are the fundamental unit, NOT full documents.
 * This is what enables OT/CRDT. Each operation is:
 *   - Atomic: one insert OR one delete at one position
 *   - Positional: references a character index in the document
 *   - Versioned: tagged with the document version the user SAW when they typed
 *   - Transformable: can be adjusted when concurrent ops arrive
 *
 * INTERVIEW TIP: If you model edits as "replace entire document content",
 * you CANNOT do OT. Operations MUST be granular.
 */
public class Operation {
    private final String opId;            // UUID, globally unique
    private final String docId;
    private final String userId;          // who performed this operation
    private final OperationType type;     // INSERT, DELETE, or RETAIN
    private int position;                 // mutable -- OT transforms adjust this
    private String content;               // for INSERT: text to insert; for DELETE: text being deleted
    private final int version;            // document version user saw when editing
    private final Instant timestamp;

    public Operation(String opId, String docId, String userId,
                     OperationType type, int position, String content, int version) {
        this.opId = opId;
        this.docId = docId;
        this.userId = userId;
        this.type = type;
        this.position = position;
        this.content = content;
        this.version = version;
        this.timestamp = Instant.now();
    }

    // Position is mutable because OT transforms adjust it
    public void setPosition(int position) { this.position = position; }
    public void setContent(String content) { this.content = content; }

    // --- Getters ---
    public String getOpId()          { return opId; }
    public String getDocId()         { return docId; }
    public String getUserId()        { return userId; }
    public OperationType getType()   { return type; }
    public int getPosition()         { return position; }
    public String getContent()       { return content; }
    public int getVersion()          { return version; }
    public Instant getTimestamp()     { return timestamp; }

    /**
     * Create a deep copy for transformation (so original is not mutated).
     * OT MUST work on copies -- transforming the original would corrupt the op log.
     */
    public Operation copy() {
        return new Operation(opId, docId, userId, type, position, content, version);
    }
}
```

### 4.3 OperationType.java

```java
/**
 * The three fundamental OT operation types.
 *
 * INSERT: adds characters at a position
 * DELETE: removes characters at a position
 * RETAIN: skips characters (no-op, used in composite OT representations)
 *
 * Google Docs uses exactly these three. Every possible edit (typing, backspace,
 * cut, paste, replace) decomposes into sequences of INSERT + DELETE + RETAIN.
 */
public enum OperationType {
    INSERT,
    DELETE,
    RETAIN
}
```

### 4.4 DocumentVersion.java

```java
/**
 * A snapshot of a document at a specific version.
 *
 * WHY SNAPSHOTS?
 * Without snapshots, rebuilding a document requires replaying ALL operations from
 * version 0. For a document with 100,000 ops (a few hours of active editing),
 * that means 100K string mutations on every load. Snapshots let you:
 *   1. Load the most recent snapshot
 *   2. Replay only the ops SINCE that snapshot
 *
 * Google Docs snapshots approximately every 100 operations.
 */
public class DocumentVersion {
    private final String versionId;
    private final String docId;
    private final int versionNumber;
    private final String contentSnapshot;               // full content at this version
    private final List<Operation> operationsSinceLastSnapshot;  // ops to replay after snapshot
    private final Instant timestamp;

    public DocumentVersion(String versionId, String docId, int versionNumber,
                           String contentSnapshot, List<Operation> operations) {
        this.versionId = versionId;
        this.docId = docId;
        this.versionNumber = versionNumber;
        this.contentSnapshot = contentSnapshot;
        this.operationsSinceLastSnapshot = new ArrayList<>(operations);
        this.timestamp = Instant.now();
    }

    public String getVersionId()            { return versionId; }
    public String getDocId()                { return docId; }
    public int getVersionNumber()           { return versionNumber; }
    public String getContentSnapshot()      { return contentSnapshot; }
    public List<Operation> getOperationsSinceLastSnapshot() {
        return Collections.unmodifiableList(operationsSinceLastSnapshot);
    }
    public Instant getTimestamp()            { return timestamp; }
}
```

### 4.5 CursorPosition.java

```java
/**
 * Tracks where a specific user's cursor is in a specific document.
 * Broadcast to all other collaborators for real-time presence.
 *
 * selectionStart/selectionEnd: when user has text selected (highlight).
 * color: each collaborator gets a distinct cursor color (Google Docs style).
 *
 * INTERVIEW NOTE: Cursor positions must also be transformed by OT.
 * If User A inserts 5 characters before User B's cursor, B's cursor
 * position must shift right by 5. This is often forgotten in interviews.
 */
public class CursorPosition {
    private final String userId;
    private final String docId;
    private int position;                // caret position (0-based)
    private int selectionStart;          // -1 if no selection
    private int selectionEnd;            // -1 if no selection
    private final String color;          // hex color, e.g., "#FF5733"

    public CursorPosition(String userId, String docId, int position, String color) {
        this.userId = userId;
        this.docId = docId;
        this.position = position;
        this.selectionStart = -1;
        this.selectionEnd = -1;
        this.color = color;
    }

    /**
     * Adjust cursor position after an operation is applied.
     * Called by PresenceService after every OT transform.
     */
    public void adjustForOperation(Operation op) {
        if (!op.getDocId().equals(this.docId)) return;
        if (op.getUserId().equals(this.userId)) return;  // own cursor already correct

        switch (op.getType()) {
            case INSERT:
                if (op.getPosition() <= this.position) {
                    this.position += op.getContent().length();
                }
                break;
            case DELETE:
                if (op.getPosition() < this.position) {
                    int deleteEnd = op.getPosition() + op.getContent().length();
                    if (deleteEnd <= this.position) {
                        this.position -= op.getContent().length();
                    } else {
                        this.position = op.getPosition();  // cursor was inside deleted range
                    }
                }
                break;
            case RETAIN:
                break;  // no position change
        }
    }

    // --- Getters/Setters ---
    public String getUserId()       { return userId; }
    public String getDocId()        { return docId; }
    public int getPosition()        { return position; }
    public void setPosition(int p)  { this.position = p; }
    public int getSelectionStart()  { return selectionStart; }
    public int getSelectionEnd()    { return selectionEnd; }
    public void setSelection(int start, int end) {
        this.selectionStart = start;
        this.selectionEnd = end;
    }
    public String getColor()        { return color; }
}
```

### 4.6 UserPresence.java

```java
/**
 * Tracks whether a user is actively viewing/editing a document.
 * Powers the "3 users viewing this document" indicator in Google Docs.
 *
 * lastActiveAt: updated on every keystroke or cursor move.
 * If lastActiveAt > 5 minutes ago, PresenceService marks user inactive.
 */
public class UserPresence {
    private final String userId;
    private final String name;
    private String docId;                // which document they are in (null = not in any doc)
    private boolean isActive;
    private Instant lastActiveAt;
    private final String cursorColor;    // assigned once, persists across sessions

    public UserPresence(String userId, String name, String cursorColor) {
        this.userId = userId;
        this.name = name;
        this.cursorColor = cursorColor;
        this.isActive = false;
        this.lastActiveAt = Instant.now();
    }

    public void joinDocument(String docId) {
        this.docId = docId;
        this.isActive = true;
        this.lastActiveAt = Instant.now();
    }

    public void leaveDocument() {
        this.docId = null;
        this.isActive = false;
    }

    public void heartbeat() {
        this.lastActiveAt = Instant.now();
        this.isActive = true;
    }

    // --- Getters ---
    public String getUserId()        { return userId; }
    public String getName()          { return name; }
    public String getDocId()         { return docId; }
    public boolean isActive()        { return isActive; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public String getCursorColor()   { return cursorColor; }
}
```

### 4.7 Permission.java & PermissionRole.java

```java
/**
 * Access control for document sharing.
 * Maps directly to Google Docs sharing model: Owner / Editor / Viewer.
 */
public class Permission {
    private final String docId;
    private final String userId;
    private PermissionRole role;
    private final Instant grantedAt;

    public Permission(String docId, String userId, PermissionRole role) {
        this.docId = docId;
        this.userId = userId;
        this.role = role;
        this.grantedAt = Instant.now();
    }

    public boolean canEdit()  { return role == PermissionRole.OWNER || role == PermissionRole.EDITOR; }
    public boolean canView()  { return true; }  // all roles can view
    public boolean isOwner()  { return role == PermissionRole.OWNER; }

    public String getDocId()         { return docId; }
    public String getUserId()        { return userId; }
    public PermissionRole getRole()  { return role; }
    public void setRole(PermissionRole role) { this.role = role; }
    public Instant getGrantedAt()    { return grantedAt; }
}

public enum PermissionRole {
    OWNER,      // full control: edit, share, delete
    EDITOR,     // can edit content, cannot delete doc or change permissions
    VIEWER      // read-only, can add comments
}
```

### 4.8 Comment.java

```java
/**
 * A comment anchored to a position in the document.
 * Comments also need position adjustment when OT transforms shift text.
 */
public class Comment {
    private final String commentId;
    private final String docId;
    private final String userId;
    private final String content;
    private int position;                // character position the comment is anchored to
    private Instant resolvedAt;          // null if unresolved

    public Comment(String commentId, String docId, String userId,
                   String content, int position) {
        this.commentId = commentId;
        this.docId = docId;
        this.userId = userId;
        this.content = content;
        this.position = position;
    }

    public void resolve()                  { this.resolvedAt = Instant.now(); }
    public boolean isResolved()            { return resolvedAt != null; }
    public void adjustPosition(int delta)  { this.position += delta; }

    public String getCommentId()   { return commentId; }
    public String getDocId()       { return docId; }
    public String getUserId()      { return userId; }
    public String getContent()     { return content; }
    public int getPosition()       { return position; }
    public Instant getResolvedAt() { return resolvedAt; }
}
```

---

## 5. Interface Contracts

### 5.1 ConflictResolver (OT Engine Interface)

```java
/**
 * Core interface for conflict resolution algorithms.
 *
 * CONTRACT:
 *   Given two concurrent operations (local and remote) that were both based on
 *   the same document version, resolve() returns a list of operations that,
 *   when applied in order, produce the correct merged result.
 *
 * IMPLEMENTATIONS:
 *   - OTResolver:   server-authoritative Operational Transform
 *   - CRDTResolver: conflict-free replicated data type (no central authority)
 *
 * INTERVIEW KEY POINT:
 *   OT transforms operations. CRDT transforms data structures.
 *   OT needs a central server. CRDT does not.
 *   OT is simpler to implement. CRDT is simpler to reason about at scale.
 */
public interface ConflictResolver {

    /**
     * Resolve two concurrent operations.
     * @param local  operation from the local user
     * @param remote operation from a remote user
     * @return transformed operations that can be safely applied
     */
    List<Operation> resolve(Operation local, Operation remote);

    /**
     * Transform a single operation against a list of already-applied ops.
     * Used when a client is behind by multiple versions.
     */
    Operation transformAgainstHistory(Operation incoming, List<Operation> history);

    /** Human-readable name for logging/debugging. */
    String getResolverName();
}
```

### 5.2 SyncStrategy

```java
/**
 * Determines HOW operations are synchronized across clients.
 *
 * OTSyncStrategy:   Client sends op to server. Server transforms against
 *                   pending ops. Server broadcasts transformed op. All
 *                   clients apply the server-transformed version.
 *                   GUARANTEE: total ordering via server version numbers.
 *
 * CRDTSyncStrategy: Client applies op locally. Broadcasts to all peers.
 *                   Each peer merges into their local CRDT state.
 *                   GUARANTEE: eventual consistency via commutativity.
 */
public interface SyncStrategy {

    /**
     * Apply an operation to a document, handling any necessary transforms.
     * @param document the current document state
     * @param operation the incoming operation
     * @return the updated document
     */
    Document applyOperation(Document document, Operation operation);

    /**
     * Handle a conflict between two concurrent operations.
     */
    Document handleConflict(Document document, Operation local, Operation remote);

    /** Strategy name for logging. */
    String getStrategyName();
}
```

### 5.3 PersistenceStrategy

```java
/**
 * Determines HOW document state and operations are persisted.
 *
 * SnapshotPersistence:      Save ops continuously. Every N ops, snapshot the
 *                           full document. Rebuild = load snapshot + replay recent ops.
 *                           TRADEOFF: uses more storage, but fast rebuilds.
 *
 * EventSourcedPersistence:  Save every op, never snapshot. Rebuild = replay ALL ops.
 *                           TRADEOFF: minimal storage, but slow rebuilds for old docs.
 *                           Perfect audit trail -- can reconstruct any point in time.
 */
public interface PersistenceStrategy {

    /** Persist a batch of operations. */
    void save(String docId, List<Operation> operations);

    /** Take a snapshot of the current document content at this version. */
    void snapshot(String docId, String content, int version);

    /** Get all operations from a given version onward. */
    List<Operation> getHistory(String docId, int fromVersion);

    /** Rebuild the document from persisted state (snapshot + ops or full replay). */
    Document rebuild(String docId);

    String getStrategyName();
}
```

### 5.4 Repository Interfaces

```java
/**
 * Standard repository interfaces. InMemory implementations use ConcurrentHashMap.
 * In production, these would be backed by DynamoDB/Cassandra (ops) + S3 (snapshots).
 */
public interface DocumentRepository {
    void save(Document document);
    Optional<Document> findById(String docId);
    List<Document> findByOwnerId(String ownerId);
    void delete(String docId);
    boolean exists(String docId);
}

public interface OperationRepository {
    void save(Operation operation);
    void saveBatch(List<Operation> operations);
    List<Operation> findByDocId(String docId);
    List<Operation> findByDocIdAndVersionGreaterThan(String docId, int version);
    Optional<Operation> findById(String opId);
    long countByDocId(String docId);
}

public interface VersionRepository {
    void save(DocumentVersion version);
    Optional<DocumentVersion> findLatestByDocId(String docId);
    List<DocumentVersion> findAllByDocId(String docId);
    Optional<DocumentVersion> findByDocIdAndVersion(String docId, int versionNumber);
}
```

---

## 6. Strategy Implementations

### 6.1 OTResolver -- Operational Transform (The Heart of Google Docs)

```java
/**
 * Server-centric Operational Transform.
 *
 * THE CORE OT RULES (memorize these for interviews):
 *
 *   Given two concurrent ops A and B, both based on the same version:
 *
 *   CASE 1: INSERT vs. INSERT
 *     - If A.position < B.position:  B' = B.position + len(A.content)
 *     - If A.position > B.position:  A' = A.position + len(B.content)
 *     - If A.position == B.position: tie-break by userId (deterministic)
 *
 *   CASE 2: INSERT vs. DELETE
 *     - If INSERT.position <= DELETE.position: DELETE' = DELETE.position + len(INSERT.content)
 *     - If INSERT.position > DELETE.position + DELETE.length: INSERT' = INSERT.position - DELETE.length
 *     - If INSERT.position is inside DELETE range: INSERT at DELETE.position, shrink DELETE
 *
 *   CASE 3: DELETE vs. DELETE
 *     - If ranges don't overlap: adjust positions
 *     - If ranges overlap: merge into one DELETE covering the union
 *     - If ranges are identical: one becomes a no-op
 *
 *   CASE 4: RETAIN
 *     - RETAIN never conflicts. Skip.
 *
 * These rules guarantee:
 *   apply(A', state_after_B) == apply(B', state_after_A)
 *   i.e., convergence regardless of application order.
 */
public class OTResolver implements ConflictResolver {

    @Override
    public List<Operation> resolve(Operation local, Operation remote) {
        // Step 1: Create copies so we don't mutate originals
        //         (originals live in the operation log -- must not be changed)
        Operation transformedLocal = local.copy();
        Operation transformedRemote = remote.copy();

        // Step 2: Apply transform rules based on operation type combinations
        TransformResult result = transform(transformedLocal, transformedRemote);

        // Step 3: Return both transformed ops. The caller will:
        //         - Apply transformedRemote to the local client
        //         - Apply transformedLocal to the remote client
        return List.of(result.getTransformedLocal(), result.getTransformedRemote());
    }

    /**
     * Core transform function. This is what interviewers want to see.
     */
    private TransformResult transform(Operation local, Operation remote) {

        // --- INSERT vs INSERT ---
        if (local.getType() == OperationType.INSERT && remote.getType() == OperationType.INSERT) {
            return transformInsertInsert(local, remote);
        }

        // --- INSERT vs DELETE ---
        if (local.getType() == OperationType.INSERT && remote.getType() == OperationType.DELETE) {
            return transformInsertDelete(local, remote);
        }

        // --- DELETE vs INSERT ---
        if (local.getType() == OperationType.DELETE && remote.getType() == OperationType.INSERT) {
            TransformResult flipped = transformInsertDelete(remote, local);
            return new TransformResult(flipped.getTransformedRemote(), flipped.getTransformedLocal());
        }

        // --- DELETE vs DELETE ---
        if (local.getType() == OperationType.DELETE && remote.getType() == OperationType.DELETE) {
            return transformDeleteDelete(local, remote);
        }

        // --- RETAIN: no conflict possible ---
        return new TransformResult(local, remote);
    }

    /**
     * INSERT vs. INSERT: both users inserting at concurrent positions.
     *
     * Example:
     *   Document: "HELLO" (version 5)
     *   User A (version 5): INSERT 'X' at position 2  -> "HEXLLO"
     *   User B (version 5): INSERT 'Y' at position 4  -> "HELLY0"
     *
     *   A sees B's insert: B was at 4, but A already inserted at 2,
     *   so B needs to shift to 5. Result on A: "HEXLLY0"
     *
     *   B sees A's insert: A was at 2, before B's insert, so B's position
     *   was already past A. No shift needed for A. Result on B: "HEXLLY0"
     *
     *   CONVERGED: both clients have "HEXLLY0"
     */
    private TransformResult transformInsertInsert(Operation local, Operation remote) {
        if (local.getPosition() < remote.getPosition()) {
            // Local insert is before remote -- remote must shift right
            remote.setPosition(remote.getPosition() + local.getContent().length());
        } else if (local.getPosition() > remote.getPosition()) {
            // Remote insert is before local -- local must shift right
            local.setPosition(local.getPosition() + remote.getContent().length());
        } else {
            // Same position: tie-break by userId (alphabetical)
            // This ensures deterministic ordering on all clients
            if (local.getUserId().compareTo(remote.getUserId()) < 0) {
                remote.setPosition(remote.getPosition() + local.getContent().length());
            } else {
                local.setPosition(local.getPosition() + remote.getContent().length());
            }
        }
        return new TransformResult(local, remote);
    }

    /**
     * INSERT vs. DELETE: one user inserts, the other deletes concurrently.
     */
    private TransformResult transformInsertDelete(Operation insert, Operation delete) {
        int deleteEnd = delete.getPosition() + delete.getContent().length();

        if (insert.getPosition() <= delete.getPosition()) {
            // Insert is before delete range -- delete shifts right
            delete.setPosition(delete.getPosition() + insert.getContent().length());
        } else if (insert.getPosition() >= deleteEnd) {
            // Insert is after delete range -- insert shifts left
            insert.setPosition(insert.getPosition() - delete.getContent().length());
        } else {
            // Insert is INSIDE delete range -- insert at delete start, shrink delete
            insert.setPosition(delete.getPosition());
        }
        return new TransformResult(insert, delete);
    }

    /**
     * DELETE vs. DELETE: both users deleting concurrently.
     * Most complex case -- overlapping ranges.
     */
    private TransformResult transformDeleteDelete(Operation local, Operation remote) {
        int localEnd = local.getPosition() + local.getContent().length();
        int remoteEnd = remote.getPosition() + remote.getContent().length();

        if (localEnd <= remote.getPosition()) {
            // Local delete is entirely before remote -- remote shifts left
            remote.setPosition(remote.getPosition() - local.getContent().length());
        } else if (remoteEnd <= local.getPosition()) {
            // Remote delete is entirely before local -- local shifts left
            local.setPosition(local.getPosition() - remote.getContent().length());
        } else {
            // Overlapping deletes -- the overlap is already deleted by the other op
            // Reduce each delete to only cover the NON-overlapping portion
            int overlapStart = Math.max(local.getPosition(), remote.getPosition());
            int overlapEnd = Math.min(localEnd, remoteEnd);
            int overlapLength = overlapEnd - overlapStart;

            // Shrink local to exclude what remote already deleted
            if (overlapLength >= local.getContent().length()) {
                // Local is entirely within remote -- local becomes no-op
                local.setContent("");
                local.setPosition(remote.getPosition());
            } else {
                String newContent = local.getContent().substring(0,
                    local.getContent().length() - overlapLength);
                local.setContent(newContent);
                local.setPosition(Math.min(local.getPosition(), remote.getPosition()));
            }

            // Shrink remote to exclude what local already deleted
            if (overlapLength >= remote.getContent().length()) {
                remote.setContent("");
                remote.setPosition(local.getPosition());
            } else {
                String newContent = remote.getContent().substring(0,
                    remote.getContent().length() - overlapLength);
                remote.setContent(newContent);
                remote.setPosition(Math.min(local.getPosition(), remote.getPosition()));
            }
        }
        return new TransformResult(local, remote);
    }

    @Override
    public Operation transformAgainstHistory(Operation incoming, List<Operation> history) {
        Operation transformed = incoming.copy();
        for (Operation historyOp : history) {
            List<Operation> result = resolve(transformed, historyOp);
            transformed = result.get(0);  // we want the transformed incoming op
        }
        return transformed;
    }

    @Override
    public String getResolverName() { return "OTResolver (Operational Transform)"; }
}
```

### 6.2 CRDTResolver -- Conflict-Free Replicated Data Type

```java
/**
 * Simulated RGA (Replicated Growable Array) CRDT for text editing.
 *
 * CRDT vs. OT COMPARISON (interview gold):
 *
 * +--------------------+-----------------------------+-----------------------------+
 * | Aspect             | OT (Google Docs)            | CRDT (Figma, Yjs)           |
 * +--------------------+-----------------------------+-----------------------------+
 * | Central server     | REQUIRED (arbiter)          | NOT required                |
 * | Latency            | Round-trip to server        | Apply locally immediately   |
 * | Complexity          | Transform logic is hard     | Data structure is complex   |
 * | Memory             | O(ops) for op log           | O(chars) -- each char has ID|
 * | Convergence        | Guaranteed IF server orders | Guaranteed by math          |
 * | Offline editing    | Tricky (need rebasing)      | Natural (merge on reconnect)|
 * | Undo               | Complex (inverse transform) | Complex (tombstones)        |
 * | Industry adoption  | Google Docs, Etherpad       | Figma, Yjs, Automerge       |
 * +--------------------+-----------------------------+-----------------------------+
 *
 * HOW RGA WORKS:
 *   - Every character gets a unique ID: (userId, sequenceNumber)
 *   - Characters are stored in a linked list ordered by ID
 *   - INSERT: create new char ID, place in list based on position + parent
 *   - DELETE: mark char as tombstone (don't remove -- needed for convergence)
 *   - MERGE: union of all characters, deterministic ordering by ID
 *   - No conflicts possible because every char has a globally unique ID
 */
public class CRDTResolver implements ConflictResolver {

    /**
     * For CRDT, "resolve" means merging. Since CRDTs are conflict-free by design,
     * both operations can be applied as-is. The data structure guarantees convergence.
     *
     * In a full CRDT implementation, this would maintain the RGA structure.
     * Here we simulate the behavior using position-based operations.
     */
    @Override
    public List<Operation> resolve(Operation local, Operation remote) {
        // CRDTs don't need transformation -- both ops apply independently.
        // The underlying data structure (RGA) ensures convergence.
        // In our simulation, we use userId-based tie-breaking for same-position ops.

        if (local.getPosition() == remote.getPosition()
                && local.getType() == OperationType.INSERT
                && remote.getType() == OperationType.INSERT) {
            // Same position insert: CRDT uses unique char IDs for ordering.
            // We simulate with userId comparison.
            if (local.getUserId().compareTo(remote.getUserId()) > 0) {
                local.setPosition(local.getPosition() + remote.getContent().length());
            } else {
                remote.setPosition(remote.getPosition() + local.getContent().length());
            }
        }

        return List.of(local, remote);
    }

    @Override
    public Operation transformAgainstHistory(Operation incoming, List<Operation> history) {
        // CRDTs don't transform against history -- they merge state.
        // Each operation is independent. Return as-is.
        return incoming.copy();
    }

    @Override
    public String getResolverName() { return "CRDTResolver (Replicated Growable Array)"; }
}
```

### 6.3 TransformResult.java

```java
/**
 * Value object holding the result of transforming two concurrent operations.
 * Immutable -- once created, the transformed operations cannot be changed.
 */
public class TransformResult {
    private final Operation transformedLocal;
    private final Operation transformedRemote;

    public TransformResult(Operation transformedLocal, Operation transformedRemote) {
        this.transformedLocal = transformedLocal;
        this.transformedRemote = transformedRemote;
    }

    public Operation getTransformedLocal()  { return transformedLocal; }
    public Operation getTransformedRemote() { return transformedRemote; }
}
```

### 6.4 OTSyncStrategy

```java
/**
 * Server-centric OT sync (Google Docs model).
 *
 * FLOW:
 *   1. Client sends operation to server with the version they saw
 *   2. Server checks: is client version == current server version?
 *      YES -> apply directly, increment version, broadcast
 *      NO  -> client is behind. Transform incoming op against all ops
 *             that happened between client's version and server's version.
 *             Then apply, increment version, broadcast.
 *   3. All clients receive the server-transformed operation and apply it.
 *
 * GUARANTEE: The server assigns a total ordering to all operations.
 *            All clients apply operations in the same order.
 *            Therefore all clients converge to the same document state.
 */
public class OTSyncStrategy implements SyncStrategy {

    private final ConflictResolver resolver;
    private final OperationRepository operationRepository;

    public OTSyncStrategy(ConflictResolver resolver, OperationRepository operationRepository) {
        this.resolver = resolver;
        this.operationRepository = operationRepository;
    }

    @Override
    public Document applyOperation(Document document, Operation operation) {
        int clientVersion = operation.getVersion();
        int serverVersion = document.getVersion();

        Operation toApply;

        if (clientVersion == serverVersion) {
            // Client is up to date -- apply directly
            toApply = operation;
        } else if (clientVersion < serverVersion) {
            // Client is behind -- transform against missed ops
            //
            // Example:
            //   Client saw version 5. Server is at version 8.
            //   Ops 6, 7, 8 happened while client was typing.
            //   Transform client's op against ops 6, 7, 8 in order.
            //   The transformed op is now safe to apply at version 8.
            List<Operation> missedOps = operationRepository
                .findByDocIdAndVersionGreaterThan(document.getDocId(), clientVersion);

            toApply = resolver.transformAgainstHistory(operation, missedOps);
        } else {
            // clientVersion > serverVersion should never happen
            throw new ConflictException(
                "Client version " + clientVersion + " ahead of server version " + serverVersion);
        }

        // Apply the (possibly transformed) operation to the document
        applyToDocument(document, toApply);
        document.incrementVersion();

        // Persist the operation
        operationRepository.save(toApply);

        return document;
    }

    /**
     * Mutate the document's content based on the operation type.
     */
    private void applyToDocument(Document document, Operation op) {
        switch (op.getType()) {
            case INSERT:
                document.insertAt(op.getPosition(), op.getContent());
                break;
            case DELETE:
                document.deleteAt(op.getPosition(), op.getContent().length());
                break;
            case RETAIN:
                // No-op: cursor moved but content unchanged
                break;
        }
    }

    @Override
    public Document handleConflict(Document document, Operation local, Operation remote) {
        List<Operation> resolved = resolver.resolve(local, remote);
        for (Operation op : resolved) {
            applyToDocument(document, op);
        }
        document.incrementVersion();
        return document;
    }

    @Override
    public String getStrategyName() { return "OTSyncStrategy (Server-Centric OT)"; }
}
```

### 6.5 CRDTSyncStrategy

```java
/**
 * Decentralized CRDT sync (Figma model).
 *
 * KEY DIFFERENCE FROM OT:
 *   - No central server needed for ordering
 *   - Each client applies operations LOCALLY FIRST (instant response)
 *   - Operations are broadcast to peers
 *   - Peers apply received operations using CRDT merge rules
 *   - Mathematical properties (commutativity, idempotency) guarantee convergence
 *
 * TRADEOFFS:
 *   + Lower latency (no server round-trip)
 *   + Works offline (merge on reconnect)
 *   - Higher memory (every character needs a unique ID)
 *   - Tombstones accumulate (deleted chars are never truly removed)
 */
public class CRDTSyncStrategy implements SyncStrategy {

    private final ConflictResolver resolver;

    public CRDTSyncStrategy(ConflictResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Document applyOperation(Document document, Operation operation) {
        // CRDT: apply immediately. No version check needed.
        // The CRDT data structure handles concurrent ops through its merge semantics.
        switch (operation.getType()) {
            case INSERT:
                document.insertAt(operation.getPosition(), operation.getContent());
                break;
            case DELETE:
                document.deleteAt(operation.getPosition(), operation.getContent().length());
                break;
            case RETAIN:
                break;
        }
        document.incrementVersion();
        return document;
    }

    @Override
    public Document handleConflict(Document document, Operation local, Operation remote) {
        // CRDT: conflicts are resolved by the data structure itself.
        // We just apply both operations -- the CRDT properties guarantee convergence.
        List<Operation> resolved = resolver.resolve(local, remote);
        for (Operation op : resolved) {
            applyOperation(document, op);
        }
        return document;
    }

    @Override
    public String getStrategyName() { return "CRDTSyncStrategy (Decentralized CRDT)"; }
}
```

### 6.6 SnapshotPersistence

```java
/**
 * Periodic snapshot + operation log persistence.
 *
 * HOW IT WORKS:
 *   - Every operation is appended to the op log immediately
 *   - Every SNAPSHOT_INTERVAL operations, a full content snapshot is taken
 *   - To rebuild: load latest snapshot, replay ops since that snapshot
 *
 * WHY SNAPSHOTS MATTER (interview talking point):
 *
 *   Without snapshots:
 *     Rebuild doc with 50,000 ops -> replay all 50,000 -> ~5 seconds
 *
 *   With snapshots every 100 ops:
 *     Rebuild doc with 50,000 ops -> load snapshot at 49,900 -> replay 100 ops -> ~10ms
 *
 *   Google Docs uses snapshots. The exact interval varies by document activity.
 */
public class SnapshotPersistence implements PersistenceStrategy {

    private static final int SNAPSHOT_INTERVAL = 100;

    private final VersionRepository versionRepository;
    private final OperationRepository operationRepository;

    // Track operation count per document to know when to snapshot
    private final Map<String, Integer> opCountSinceSnapshot = new ConcurrentHashMap<>();

    public SnapshotPersistence(VersionRepository versionRepository,
                               OperationRepository operationRepository) {
        this.versionRepository = versionRepository;
        this.operationRepository = operationRepository;
    }

    @Override
    public void save(String docId, List<Operation> operations) {
        operationRepository.saveBatch(operations);

        int count = opCountSinceSnapshot.merge(docId, operations.size(), Integer::sum);

        if (count >= SNAPSHOT_INTERVAL) {
            // Trigger snapshot on next explicit snapshot() call from VersionService
            // (VersionService checks this threshold)
            opCountSinceSnapshot.put(docId, 0);
        }
    }

    @Override
    public void snapshot(String docId, String content, int version) {
        DocumentVersion snapshot = new DocumentVersion(
            UUID.randomUUID().toString(), docId, version, content, List.of()
        );
        versionRepository.save(snapshot);
        opCountSinceSnapshot.put(docId, 0);
    }

    @Override
    public List<Operation> getHistory(String docId, int fromVersion) {
        return operationRepository.findByDocIdAndVersionGreaterThan(docId, fromVersion);
    }

    @Override
    public Document rebuild(String docId) {
        // Step 1: Find the latest snapshot
        Optional<DocumentVersion> latestSnapshot = versionRepository.findLatestByDocId(docId);

        if (latestSnapshot.isPresent()) {
            DocumentVersion snap = latestSnapshot.get();
            // Step 2: Rebuild from snapshot + replay recent ops
            Document doc = new Document.Builder()
                .docId(docId)
                .content(snap.getContentSnapshot())
                .version(snap.getVersionNumber())
                .ownerId("system")      // owner info would come from DocumentRepository
                .build();

            // Step 3: Replay ops since snapshot
            List<Operation> recentOps = operationRepository
                .findByDocIdAndVersionGreaterThan(docId, snap.getVersionNumber());
            for (Operation op : recentOps) {
                applyOp(doc, op);
            }
            return doc;
        } else {
            // No snapshot exists -- replay ALL operations
            return rebuildFromScratch(docId);
        }
    }

    private Document rebuildFromScratch(String docId) {
        Document doc = new Document.Builder()
            .docId(docId).content("").version(0).ownerId("system").build();
        List<Operation> allOps = operationRepository.findByDocId(docId);
        for (Operation op : allOps) {
            applyOp(doc, op);
        }
        return doc;
    }

    private void applyOp(Document doc, Operation op) {
        switch (op.getType()) {
            case INSERT -> doc.insertAt(op.getPosition(), op.getContent());
            case DELETE -> doc.deleteAt(op.getPosition(), op.getContent().length());
            case RETAIN -> { /* no-op */ }
        }
        doc.incrementVersion();
    }

    @Override
    public String getStrategyName() { return "SnapshotPersistence (Periodic Snapshots)"; }
}
```

### 6.7 EventSourcedPersistence

```java
/**
 * Event-sourced persistence -- store ALL operations, rebuild by replaying events.
 *
 * TRADEOFFS vs. SNAPSHOT:
 *   + Perfect audit trail (every keystroke is preserved)
 *   + Can reconstruct document at ANY point in time
 *   + Natural fit for undo/redo (reverse the event stream)
 *   - Slower rebuilds for old documents (must replay entire history)
 *   - Op log grows without bound (need separate compaction strategy)
 *
 * WHEN TO USE:
 *   - Compliance-heavy environments (legal docs, financial docs)
 *   - When "who changed what when" is a hard requirement
 *   - When document history is a first-class feature (like Google Docs version history)
 */
public class EventSourcedPersistence implements PersistenceStrategy {

    private final OperationRepository operationRepository;

    public EventSourcedPersistence(OperationRepository operationRepository) {
        this.operationRepository = operationRepository;
    }

    @Override
    public void save(String docId, List<Operation> operations) {
        // Simply append all operations to the event log.
        // That's it. No snapshots, no compaction. Pure event sourcing.
        operationRepository.saveBatch(operations);
    }

    @Override
    public void snapshot(String docId, String content, int version) {
        // Event sourcing does NOT take snapshots by default.
        // This is a deliberate design choice: the event log IS the source of truth.
        // If performance becomes an issue, a CQRS read-side projection can be added.
        // (No-op here to satisfy the interface contract.)
    }

    @Override
    public List<Operation> getHistory(String docId, int fromVersion) {
        return operationRepository.findByDocIdAndVersionGreaterThan(docId, fromVersion);
    }

    @Override
    public Document rebuild(String docId) {
        // Replay ALL operations from the very beginning.
        // This is the COST of event sourcing -- O(total_ops) rebuild time.
        Document doc = new Document.Builder()
            .docId(docId).content("").version(0).ownerId("system").build();

        List<Operation> allOps = operationRepository.findByDocId(docId);
        for (Operation op : allOps) {
            switch (op.getType()) {
                case INSERT -> doc.insertAt(op.getPosition(), op.getContent());
                case DELETE -> doc.deleteAt(op.getPosition(), op.getContent().length());
                case RETAIN -> { /* no-op */ }
            }
            doc.incrementVersion();
        }
        return doc;
    }

    @Override
    public String getStrategyName() { return "EventSourcedPersistence (Full Event Log)"; }
}
```

---

## 7. Service Layer Design

### 7.1 CollaborationService (Facade)

```java
/**
 * FACADE: single entry point for all collaboration operations.
 *
 * CollaborationController calls ONLY this service.
 * This service orchestrates calls across DocumentService, OperationService,
 * PresenceService, VersionService, PermissionService, and BroadcastService.
 *
 * WHY A FACADE?
 *   The controller does not need to know that editing requires:
 *     1. Permission check (PermissionService)
 *     2. Operation transform (OperationService -> SyncStrategy -> ConflictResolver)
 *     3. Document update (DocumentService)
 *     4. Version snapshot check (VersionService -> PersistenceStrategy)
 *     5. Cursor adjustment (PresenceService)
 *     6. Broadcast to other users (BroadcastService)
 *
 *   The Facade hides this orchestration behind a single method: applyOperation().
 */
public class CollaborationService {

    private final DocumentService documentService;
    private final OperationService operationService;
    private final PresenceService presenceService;
    private final VersionService versionService;
    private final PermissionService permissionService;
    private final BroadcastService broadcastService;

    public CollaborationService(DocumentService documentService,
                                OperationService operationService,
                                PresenceService presenceService,
                                VersionService versionService,
                                PermissionService permissionService,
                                BroadcastService broadcastService) {
        this.documentService = documentService;
        this.operationService = operationService;
        this.presenceService = presenceService;
        this.versionService = versionService;
        this.permissionService = permissionService;
        this.broadcastService = broadcastService;
    }

    /**
     * Main entry point: a user submits an operation on a document.
     *
     * CALL CHAIN:
     *   Controller.submitOperation(op)
     *     -> CollaborationService.applyOperation(op)
     *       -> PermissionService.checkEditPermission(docId, userId)          [1]
     *       -> DocumentService.getDocument(docId)                            [2]
     *       -> OperationService.processOperation(document, op)               [3]
     *            -> SyncStrategy.applyOperation(document, op)                [3a]
     *                 -> ConflictResolver.transformAgainstHistory(op, missed) [3b]
     *       -> VersionService.recordOperation(docId, op)                     [4]
     *       -> PresenceService.adjustCursors(docId, op)                      [5]
     *       -> BroadcastService.broadcastOperation(docId, op, userId)        [6]
     */
    public Document applyOperation(Operation operation) {
        String docId = operation.getDocId();
        String userId = operation.getUserId();

        // [1] Permission check -- throws PermissionDeniedException if VIEWER
        permissionService.checkEditPermission(docId, userId);

        // [2] Load document -- throws DocumentNotFoundException if missing
        Document document = documentService.getDocument(docId);

        // [3] Process operation (transform if needed, apply to document)
        document = operationService.processOperation(document, operation);

        // [4] Record operation and check if snapshot is needed
        versionService.recordOperation(docId, operation, document);

        // [5] Adjust all other users' cursor positions
        presenceService.adjustCursors(docId, operation);

        // [6] Broadcast the applied operation to all connected users
        broadcastService.broadcastOperation(docId, operation, userId);

        // [7] Update presence heartbeat for this user
        presenceService.heartbeat(userId);

        return document;
    }

    // --- Document lifecycle ---

    public Document createDocument(String title, String ownerId) {
        Document doc = documentService.createDocument(title, ownerId);
        permissionService.grantPermission(doc.getDocId(), ownerId, PermissionRole.OWNER);
        return doc;
    }

    public void shareDocument(String docId, String ownerId, String targetUserId,
                              PermissionRole role) {
        permissionService.checkOwnerPermission(docId, ownerId);
        permissionService.grantPermission(docId, targetUserId, role);
    }

    // --- Presence ---

    public void userJoined(String userId, String name, String docId) {
        permissionService.checkViewPermission(docId, userId);
        presenceService.userJoined(userId, name, docId);
        broadcastService.broadcastPresenceUpdate(docId, userId, true);
    }

    public void userLeft(String userId, String docId) {
        presenceService.userLeft(userId, docId);
        broadcastService.broadcastPresenceUpdate(docId, userId, false);
    }

    public List<UserPresence> getActiveUsers(String docId) {
        return presenceService.getActiveUsers(docId);
    }

    // --- Version history ---

    public List<DocumentVersion> getVersionHistory(String docId, String userId) {
        permissionService.checkViewPermission(docId, userId);
        return versionService.getVersionHistory(docId);
    }

    public Document rollbackToVersion(String docId, String userId, int version) {
        permissionService.checkEditPermission(docId, userId);
        return versionService.rollbackToVersion(docId, version);
    }
}
```

### 7.2 DocumentService

```java
/**
 * CRUD operations for documents. Pure domain logic, no OT/CRDT concerns.
 */
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public Document createDocument(String title, String ownerId) {
        String docId = UUID.randomUUID().toString();
        Document doc = new Document.Builder()
            .docId(docId)
            .title(title)
            .content("")
            .version(0)
            .ownerId(ownerId)
            .build();
        documentRepository.save(doc);
        return doc;
    }

    public Document getDocument(String docId) {
        return documentRepository.findById(docId)
            .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + docId));
    }

    public void saveDocument(Document document) {
        documentRepository.save(document);
    }

    public void deleteDocument(String docId) {
        if (!documentRepository.exists(docId)) {
            throw new DocumentNotFoundException("Document not found: " + docId);
        }
        documentRepository.delete(docId);
    }

    public List<Document> getDocumentsByOwner(String ownerId) {
        return documentRepository.findByOwnerId(ownerId);
    }
}
```

### 7.3 OperationService

```java
/**
 * Processes incoming operations using the configured SyncStrategy.
 * This is the bridge between raw user input and the OT/CRDT engine.
 *
 * SEPARATION OF CONCERNS:
 *   - OperationService knows WHEN to transform (version mismatch)
 *   - SyncStrategy knows HOW to transform (OT rules vs. CRDT merge)
 *   - ConflictResolver knows the MATH of transformation
 *
 * Changing from OT to CRDT requires ZERO changes to OperationService.
 */
public class OperationService {

    private final SyncStrategy syncStrategy;
    private final OperationRepository operationRepository;

    public OperationService(SyncStrategy syncStrategy,
                            OperationRepository operationRepository) {
        this.syncStrategy = syncStrategy;
        this.operationRepository = operationRepository;
    }

    /**
     * Process an incoming operation.
     * SyncStrategy handles transformation internally.
     */
    public Document processOperation(Document document, Operation operation) {
        // Validate operation before processing
        validateOperation(document, operation);

        // Delegate to strategy (OT or CRDT)
        Document updated = syncStrategy.applyOperation(document, operation);

        return updated;
    }

    /**
     * Validate operation bounds and type.
     * Catches obviously invalid ops BEFORE they hit the OT engine.
     */
    private void validateOperation(Document document, Operation operation) {
        if (operation.getPosition() < 0) {
            throw new CollaborationException("Operation position cannot be negative");
        }
        if (operation.getType() == OperationType.INSERT
                && operation.getPosition() > document.getContentLength()) {
            throw new CollaborationException(
                "INSERT position " + operation.getPosition() +
                " exceeds document length " + document.getContentLength());
        }
        if (operation.getType() == OperationType.DELETE
                && operation.getPosition() + operation.getContent().length()
                   > document.getContentLength()) {
            throw new CollaborationException(
                "DELETE range exceeds document length");
        }
    }

    public List<Operation> getOperationHistory(String docId) {
        return operationRepository.findByDocId(docId);
    }

    public long getOperationCount(String docId) {
        return operationRepository.countByDocId(docId);
    }
}
```

### 7.4 PresenceService

```java
/**
 * Tracks which users are currently viewing/editing each document.
 * Manages cursor positions and broadcasts cursor updates.
 *
 * REAL-TIME PRESENCE FEATURES:
 *   - "Alice, Bob, and 3 others are viewing this document"
 *   - Colored cursors showing where each user is typing
 *   - Selection highlights (when a user selects text)
 *   - Idle detection (no activity for 5 minutes -> mark inactive)
 */
public class PresenceService {

    private static final long IDLE_TIMEOUT_SECONDS = 300; // 5 minutes

    // docId -> Map<userId, UserPresence>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, UserPresence>> presenceMap
        = new ConcurrentHashMap<>();

    // docId -> Map<userId, CursorPosition>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CursorPosition>> cursorMap
        = new ConcurrentHashMap<>();

    // Pre-assigned cursor colors for up to 8 concurrent editors
    private static final String[] CURSOR_COLORS = {
        "#4285F4", "#EA4335", "#34A853", "#FBBC04",
        "#FF6D01", "#46BDC6", "#7B1FA2", "#C2185B"
    };

    private int colorIndex = 0;

    public void userJoined(String userId, String name, String docId) {
        String color = CURSOR_COLORS[colorIndex++ % CURSOR_COLORS.length];

        UserPresence presence = new UserPresence(userId, name, color);
        presence.joinDocument(docId);

        presenceMap.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
                   .put(userId, presence);

        CursorPosition cursor = new CursorPosition(userId, docId, 0, color);
        cursorMap.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
                 .put(userId, cursor);
    }

    public void userLeft(String userId, String docId) {
        presenceMap.getOrDefault(docId, new ConcurrentHashMap<>()).remove(userId);
        cursorMap.getOrDefault(docId, new ConcurrentHashMap<>()).remove(userId);
    }

    /**
     * After an operation is applied, adjust ALL other users' cursor positions.
     * This is critical for a good UX -- without this, cursors jump to wrong positions.
     *
     * Example:
     *   User A's cursor is at position 10.
     *   User B inserts 5 characters at position 3.
     *   User A's cursor must shift to position 15.
     */
    public void adjustCursors(String docId, Operation operation) {
        ConcurrentHashMap<String, CursorPosition> cursors = cursorMap.get(docId);
        if (cursors == null) return;

        for (CursorPosition cursor : cursors.values()) {
            cursor.adjustForOperation(operation);
        }
    }

    public void heartbeat(String userId) {
        // Update lastActiveAt for the user across all their document presences
        presenceMap.values().forEach(docPresence -> {
            UserPresence presence = docPresence.get(userId);
            if (presence != null) {
                presence.heartbeat();
            }
        });
    }

    public List<UserPresence> getActiveUsers(String docId) {
        ConcurrentHashMap<String, UserPresence> docPresence = presenceMap.get(docId);
        if (docPresence == null) return List.of();

        Instant cutoff = Instant.now().minusSeconds(IDLE_TIMEOUT_SECONDS);
        return docPresence.values().stream()
            .filter(p -> p.isActive() && p.getLastActiveAt().isAfter(cutoff))
            .toList();
    }

    public Map<String, CursorPosition> getCursors(String docId) {
        return Collections.unmodifiableMap(
            cursorMap.getOrDefault(docId, new ConcurrentHashMap<>()));
    }
}
```

### 7.5 VersionService

```java
/**
 * Manages document version history: snapshots, operation recording, rollback.
 * Delegates actual persistence to the configured PersistenceStrategy.
 */
public class VersionService {

    private static final int SNAPSHOT_THRESHOLD = 100;

    private final PersistenceStrategy persistenceStrategy;
    private final VersionRepository versionRepository;

    // Track ops since last snapshot per document
    private final Map<String, Integer> opsSinceSnapshot = new ConcurrentHashMap<>();

    public VersionService(PersistenceStrategy persistenceStrategy,
                          VersionRepository versionRepository) {
        this.persistenceStrategy = persistenceStrategy;
        this.versionRepository = versionRepository;
    }

    /**
     * Record an operation and take a snapshot if threshold is reached.
     */
    public void recordOperation(String docId, Operation operation, Document currentState) {
        persistenceStrategy.save(docId, List.of(operation));

        int count = opsSinceSnapshot.merge(docId, 1, Integer::sum);

        if (count >= SNAPSHOT_THRESHOLD) {
            persistenceStrategy.snapshot(docId, currentState.getContent(),
                                         currentState.getVersion());
            opsSinceSnapshot.put(docId, 0);
        }
    }

    public List<DocumentVersion> getVersionHistory(String docId) {
        return versionRepository.findAllByDocId(docId);
    }

    /**
     * Rollback a document to a previous version.
     * Creates a NEW version (does not rewrite history).
     *
     * This is how Google Docs "restore this version" works:
     * it doesn't delete operations -- it creates new operations that
     * transform the current state back to the target state.
     */
    public Document rollbackToVersion(String docId, int targetVersion) {
        Optional<DocumentVersion> target = versionRepository
            .findByDocIdAndVersion(docId, targetVersion);

        if (target.isEmpty()) {
            throw new CollaborationException("Version " + targetVersion + " not found for " + docId);
        }

        return persistenceStrategy.rebuild(docId);
    }
}
```

### 7.6 PermissionService

```java
/**
 * Access control for documents. Enforces OWNER / EDITOR / VIEWER roles.
 *
 * PERMISSION MODEL (matches Google Docs):
 *   OWNER:  can edit, share, delete, transfer ownership
 *   EDITOR: can edit content, add comments
 *   VIEWER: can view, add comments (no edits)
 *
 * Every document operation checks permissions FIRST (fail-fast).
 */
public class PermissionService {

    // docId -> Map<userId, Permission>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Permission>> permissions
        = new ConcurrentHashMap<>();

    public void grantPermission(String docId, String userId, PermissionRole role) {
        Permission permission = new Permission(docId, userId, role);
        permissions.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
                   .put(userId, permission);
    }

    public void revokePermission(String docId, String userId) {
        ConcurrentHashMap<String, Permission> docPerms = permissions.get(docId);
        if (docPerms != null) {
            Permission perm = docPerms.get(userId);
            if (perm != null && perm.isOwner()) {
                throw new CollaborationException("Cannot revoke OWNER permission");
            }
            docPerms.remove(userId);
        }
    }

    public void checkEditPermission(String docId, String userId) {
        Permission perm = getPermission(docId, userId);
        if (!perm.canEdit()) {
            throw new PermissionDeniedException(
                "User " + userId + " has VIEWER role on document " + docId + ". Cannot edit.");
        }
    }

    public void checkViewPermission(String docId, String userId) {
        getPermission(docId, userId);  // throws if no permission at all
    }

    public void checkOwnerPermission(String docId, String userId) {
        Permission perm = getPermission(docId, userId);
        if (!perm.isOwner()) {
            throw new PermissionDeniedException(
                "User " + userId + " is not the OWNER of document " + docId);
        }
    }

    private Permission getPermission(String docId, String userId) {
        ConcurrentHashMap<String, Permission> docPerms = permissions.get(docId);
        if (docPerms == null || !docPerms.containsKey(userId)) {
            throw new PermissionDeniedException(
                "User " + userId + " has no access to document " + docId);
        }
        return docPerms.get(userId);
    }
}
```

### 7.7 BroadcastService

```java
/**
 * Simulated WebSocket broadcasting. In production, this would use actual
 * WebSocket connections (e.g., Netty, or a managed service like AWS API Gateway).
 *
 * BROADCAST MODEL:
 *   - When a user applies an operation, it must be sent to ALL other users
 *     currently viewing the same document.
 *   - Operations are broadcast AFTER OT transform (server sends the canonical version).
 *   - Presence updates (cursor moves, user join/leave) are also broadcast.
 *
 * WHY NOT POLLING?
 *   Polling: client asks "any new ops?" every 100ms = 10 requests/sec/user
 *   WebSocket: server PUSHES ops as they happen = 0 wasted requests
 *   For 50 concurrent users on one doc: polling = 500 req/sec, WebSocket = 0 idle req/sec
 */
public class BroadcastService {

    // docId -> Set<userId> (connected users)
    private final ConcurrentHashMap<String, Set<String>> connectedUsers
        = new ConcurrentHashMap<>();

    // Simulated message queue: docId -> List of pending broadcasts
    // In production: actual WebSocket frames or pub/sub messages
    private final ConcurrentHashMap<String, List<BroadcastMessage>> pendingBroadcasts
        = new ConcurrentHashMap<>();

    public void connect(String docId, String userId) {
        connectedUsers.computeIfAbsent(docId,
            k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    public void disconnect(String docId, String userId) {
        Set<String> users = connectedUsers.get(docId);
        if (users != null) {
            users.remove(userId);
        }
    }

    /**
     * Broadcast an operation to all connected users EXCEPT the sender.
     * The sender already applied the op locally.
     */
    public void broadcastOperation(String docId, Operation operation, String senderId) {
        Set<String> users = connectedUsers.getOrDefault(docId, Set.of());

        for (String userId : users) {
            if (!userId.equals(senderId)) {
                // In production: send WebSocket frame to this user's connection
                // Here: queue the message for simulated delivery
                BroadcastMessage msg = new BroadcastMessage(
                    "OPERATION", docId, userId, operation.toString());
                pendingBroadcasts.computeIfAbsent(docId, k -> new CopyOnWriteArrayList<>())
                    .add(msg);
            }
        }
    }

    public void broadcastPresenceUpdate(String docId, String userId, boolean joined) {
        String type = joined ? "USER_JOINED" : "USER_LEFT";
        Set<String> users = connectedUsers.getOrDefault(docId, Set.of());

        for (String targetUserId : users) {
            if (!targetUserId.equals(userId)) {
                BroadcastMessage msg = new BroadcastMessage(
                    type, docId, targetUserId, userId);
                pendingBroadcasts.computeIfAbsent(docId, k -> new CopyOnWriteArrayList<>())
                    .add(msg);
            }
        }
    }

    public int getConnectedUserCount(String docId) {
        return connectedUsers.getOrDefault(docId, Set.of()).size();
    }

    /**
     * Simple record for simulated broadcast messages.
     */
    public record BroadcastMessage(String type, String docId,
                                   String targetUserId, String payload) {}
}
```

---

## 8. Concurrency Considerations

```
+=====================================================================+
|          CONCURRENCY MODEL FOR REAL-TIME COLLABORATION               |
+=====================================================================+

PROBLEM:
  Multiple users editing the same document simultaneously.
  Operations arrive from network threads concurrently.
  Document state must remain consistent.

APPROACH: Per-document locking (NOT global lock)

  Why per-document?
  - Global lock: only one edit across ALL documents at a time (terrible)
  - Per-document lock: edits on different documents are fully parallel
  - Only edits on the SAME document are serialized

  +-----------------------------------------------------+
  |  Document: "Project Plan"                            |
  |  Lock: ReentrantLock (fair = true)                   |
  |                                                      |
  |  Thread-1 (Alice): lock() -> transform -> apply      |
  |  Thread-2 (Bob):   waiting...                        |
  |  Thread-1:         unlock()                          |
  |  Thread-2 (Bob):   lock() -> transform -> apply      |
  |  Thread-2:         unlock()                          |
  |                                                      |
  |  Edits are serialized PER DOCUMENT.                  |
  |  Other documents are unaffected.                     |
  +-----------------------------------------------------+

  +-----------------------------------------------------+
  |  Document: "Meeting Notes"   (different lock)        |
  |  Lock: ReentrantLock (fair = true)                   |
  |                                                      |
  |  Thread-3 (Carol): lock() -> transform -> apply      |
  |  Thread-3:         unlock()                          |
  |                                                      |
  |  Runs in PARALLEL with Project Plan edits.           |
  +-----------------------------------------------------+
```

### Thread-Safety by Component

```java
/**
 * CONCURRENCY MAP -- what protects what:
 *
 * +-------------------------+------------------------------------------+-------------------+
 * | Component               | Concurrency Mechanism                    | Why This Choice   |
 * +-------------------------+------------------------------------------+-------------------+
 * | DocumentRepository      | ConcurrentHashMap                        | Lock-free reads   |
 * | OperationRepository     | ConcurrentHashMap + CopyOnWriteArrayList | Append-mostly     |
 * | PresenceService.maps    | ConcurrentHashMap (nested)               | Per-doc isolation  |
 * | PermissionService.maps  | ConcurrentHashMap (nested)               | Per-doc isolation  |
 * | BroadcastService.conns  | ConcurrentHashMap + ConcurrentSkipList   | Concurrent add/rm |
 * | OperationService        | Per-document ReentrantLock               | Serialize OT      |
 * | Document.content        | Accessed only under per-doc lock         | No intrinsic sync |
 * +-------------------------+------------------------------------------+-------------------+
 *
 * KEY INSIGHT: The per-document lock in OperationService is the critical
 * synchronization point. All OT transforms for a document happen under
 * this lock, ensuring version numbers are assigned sequentially.
 *
 * ConcurrentHashMap handles the "which document" lookup.
 * ReentrantLock handles the "apply this edit safely" within a document.
 */

// Per-document locking in OperationService:
public class OperationService {

    private final ConcurrentHashMap<String, ReentrantLock> documentLocks
        = new ConcurrentHashMap<>();

    public Document processOperation(Document document, Operation operation) {
        ReentrantLock lock = documentLocks.computeIfAbsent(
            document.getDocId(), k -> new ReentrantLock(true));  // fair lock

        lock.lock();
        try {
            validateOperation(document, operation);
            return syncStrategy.applyOperation(document, operation);
        } finally {
            lock.unlock();
        }
    }
}
```

### Why NOT synchronized?

```
+----------------------------------------------------------------------+
| synchronized vs. ReentrantLock for this use case:                     |
|                                                                       |
| synchronized:                                                         |
|   - Locks on object monitor (Document instance)                       |
|   - Cannot be fair (threads may starve)                               |
|   - Cannot be tried with timeout (tryLock)                            |
|   - Cannot be interrupted while waiting                               |
|                                                                       |
| ReentrantLock(fair = true):                                           |
|   - Fair: longest-waiting thread gets lock next (no starvation)       |
|   - Supports tryLock(timeout) for deadlock avoidance                  |
|   - Supports lockInterruptibly() for cancellation                     |
|   - Explicit lock/unlock in try/finally (no accidental leaks)         |
|                                                                       |
| For real-time collaboration, FAIRNESS matters:                        |
|   If Alice and Bob type at similar speed, both should see their       |
|   edits applied without one consistently winning.                     |
+----------------------------------------------------------------------+
```

---

## 9. SOLID Principles Applied

```
+=====================================================================+
|                    SOLID IN THIS DESIGN                               |
+=====================================================================+

S - SINGLE RESPONSIBILITY
+----------------------------+------------------------------------------+
| Class                      | Single Responsibility                    |
+----------------------------+------------------------------------------+
| Document                   | Hold document state (content, version)   |
| Operation                  | Represent one atomic edit                |
| OTResolver                 | OT transform math only                  |
| CRDTResolver               | CRDT merge math only                    |
| OTSyncStrategy             | Orchestrate OT-based sync               |
| OperationService           | Validate and delegate to strategy        |
| PresenceService            | Track who is where                       |
| PermissionService          | Enforce access control                   |
| BroadcastService           | Push updates to clients                  |
| VersionService             | Manage snapshots and history             |
| DocumentService            | CRUD on documents                        |
| CollaborationService       | Orchestrate the full workflow (Facade)   |
+----------------------------+------------------------------------------+

O - OPEN/CLOSED
  Adding a new conflict resolution algorithm:
    1. Create NewResolver implements ConflictResolver        <-- NEW file
    2. Create NewSyncStrategy implements SyncStrategy        <-- NEW file
    3. Wire in AppConfig                                     <-- ONE line change
    4. ZERO changes to OperationService, CollaborationService, or any other service

  Adding a new persistence strategy:
    1. Create NewPersistence implements PersistenceStrategy   <-- NEW file
    2. Wire in AppConfig                                      <-- ONE line change
    3. ZERO changes to VersionService

L - LISKOV SUBSTITUTION
  OTSyncStrategy and CRDTSyncStrategy are interchangeable.
  Both implement SyncStrategy. CollaborationService works identically with either.
  If LSP were violated, switching from OT to CRDT would break the Facade.

  Test: Swap OTSyncStrategy for CRDTSyncStrategy in AppConfig.
        Run the same demo scenario. Same final document content. LSP holds.

I - INTERFACE SEGREGATION
  ConflictResolver: resolve(), transformAgainstHistory(), getResolverName()
    -- Only conflict math. No persistence, no broadcasting.

  SyncStrategy: applyOperation(), handleConflict(), getStrategyName()
    -- Only sync orchestration. No repository access (that's injected).

  PersistenceStrategy: save(), snapshot(), getHistory(), rebuild()
    -- Only persistence. No OT logic, no broadcasting.

  Each interface is small, focused, and used by exactly one consumer.

D - DEPENDENCY INVERSION
  OperationService depends on SyncStrategy (interface), not OTSyncStrategy (concrete).
  VersionService depends on PersistenceStrategy (interface), not SnapshotPersistence.
  SyncStrategy depends on ConflictResolver (interface), not OTResolver.

  Dependency graph (all arrows point toward abstractions):

    OperationService --------> SyncStrategy (interface)
                                    ^
                                    |
                               OTSyncStrategy (concrete)
                                    |
                                    v
                            ConflictResolver (interface)
                                    ^
                                    |
                               OTResolver (concrete)
```

---

## 10. Sample Workflows

### 10.1 Two Users Editing Simultaneously (OT Path)

```
SCENARIO: Alice and Bob both edit "Hello World" at the same time.
          Alice inserts "Beautiful " at position 6.
          Bob inserts "!" at position 11.
          Both are at document version 5.

  Alice's Client                 Server (OT)                    Bob's Client
       |                            |                                |
       | "Hello World" (v5)         | "Hello World" (v5)            | "Hello World" (v5)
       |                            |                                |
  t=1  | INSERT("Beautiful ", 6, v5)|                                |
       |--------------------------->|                                |
       |                            |                                | INSERT("!", 11, v5)
       |                            |<-------------------------------|
       |                            |                                |
       |                            | Received Alice's op first.     |
       |                            | version==5, serverVersion==5   |
       |                            | Apply directly:                |
       |                            |   "Hello Beautiful World" (v6) |
       |                            |                                |
       |                            | Received Bob's op.             |
       |                            | version==5, serverVersion==6   |
       |                            | Bob is BEHIND. Transform:      |
       |                            |   Bob's INSERT at 11           |
       |                            |   Alice inserted 10 chars at 6 |
       |                            |   6 < 11, so Bob shifts to 21  |
       |                            |   Bob' = INSERT("!", 21, v6)   |
       |                            | Apply:                         |
       |                            |   "Hello Beautiful World!" (v7)|
       |                            |                                |
       |<--- broadcast Bob'---------|                                |
       |     INSERT("!", 21)        |------- broadcast Alice' ------>|
       |                            |     INSERT("Beautiful ", 6)    |
       |                            |                                |
  Alice applies Bob':              |                         Bob applies Alice':
  "Hello Beautiful World"          |                         "Hello World!"
  + INSERT "!" at 21               |                         + INSERT "Beautiful " at 6
  = "Hello Beautiful World!"       |                         = "Hello Beautiful World!"
       |                            |                                |
       | CONVERGED: both have "Hello Beautiful World!" at v7         |
```

### 10.2 Conflicting Deletes (OT Path)

```
SCENARIO: Alice and Bob both delete overlapping ranges.
          Document: "ABCDEFGH" (v3)
          Alice deletes "CDE" (position 2, length 3)
          Bob deletes "DEF" (position 3, length 3)

  Alice's view: "ABCDEFGH" -> delete pos=2, len=3 -> "ABFGH"
  Bob's view:   "ABCDEFGH" -> delete pos=3, len=3 -> "ABCGH"

  Server receives Alice first:
    Apply Alice: "ABCDEFGH" -> "ABFGH" (v4)

  Server receives Bob (v3, but server is at v4):
    Transform Bob's delete against Alice's delete:
      Alice deleted [2,5), Bob wants to delete [3,6)
      Overlap: [3,5) -- already deleted by Alice
      Bob's remaining delete: [5,6) -> but shifted because Alice removed 3 chars before pos 5
      Bob' = DELETE("F", position=2, length=1)

    Apply Bob': "ABFGH" -> "ABGH" (v5)

  Broadcast:
    Alice gets Bob': DELETE("F", 2) -> "ABFGH" -> "ABGH"
    Bob gets Alice':  DELETE("CDE", 2) -> "ABCGH" -> "ABGH"  (Bob's D already gone -> adjust)

  CONVERGED: "ABGH" -- union of both deletes, no data loss, no duplication.
```

### 10.3 Version History Rollback

```
SCENARIO: Document has been edited to version 50.
          User wants to see what it looked like at version 20.

  CollaborationService.rollbackToVersion("doc1", "alice", 20)
    |
    +-> PermissionService.checkEditPermission("doc1", "alice")    -- OK
    |
    +-> VersionService.rollbackToVersion("doc1", 20)
        |
        +-> VersionRepository.findByDocIdAndVersion("doc1", 20)
        |   |
        |   +-> Found snapshot at version 15 (content: "...")
        |
        +-> PersistenceStrategy.rebuild("doc1")
            |
            +-> [SnapshotPersistence path]:
            |     Load snapshot at v15
            |     Replay ops 16, 17, 18, 19, 20
            |     Return document at v20
            |
            +-> [EventSourcedPersistence path]:
                  Replay ALL ops 1 through 20
                  Return document at v20

  NOTE: Rollback does NOT delete ops 21-50. It creates NEW ops that
  transform the current state back to the v20 state. History is preserved.
```

### 10.4 Cursor Presence Flow

```
SCENARIO: Alice, Bob, and Carol are all viewing "Hello World".
          Alice types at position 5.
          All cursors must be adjusted.

  BEFORE:
    Alice cursor: position 5 (color: #4285F4, blue)
    Bob cursor:   position 8 (color: #EA4335, red)
    Carol cursor: position 2 (color: #34A853, green)

  Alice inserts "XYZ" at position 5:
    Document: "Hello World" -> "HelloXYZ World"

  PresenceService.adjustCursors("doc1", INSERT("XYZ", 5)):
    Alice: skip (own cursor)
    Bob:   position 8, INSERT at 5 <= 8, shift right by 3 -> position 11
    Carol: position 2, INSERT at 5 > 2, no shift -> position 2

  AFTER:
    Alice cursor: position 8 (moved by typing, handled by client)
    Bob cursor:   position 11 (shifted right by server)
    Carol cursor: position 2 (unchanged)

  BroadcastService sends to Bob and Carol:
    { type: "OPERATION", op: INSERT("XYZ", 5) }
    { type: "CURSOR_UPDATE", userId: "alice", position: 8, color: "#4285F4" }
```

### 10.5 Permission Check Flow

```
SCENARIO: Bob (VIEWER) tries to edit Alice's document.

  CollaborationController.submitOperation(op)           -- op.userId = "bob"
    |
    +-> CollaborationService.applyOperation(op)
        |
        +-> PermissionService.checkEditPermission("doc1", "bob")
            |
            +-> permissions.get("doc1").get("bob") -> Permission(role=VIEWER)
            +-> perm.canEdit() -> false (VIEWER cannot edit)
            +-> throw PermissionDeniedException(
                  "User bob has VIEWER role on document doc1. Cannot edit.")

  RESULT: Operation is REJECTED. Document is unchanged.
          Client receives error and can display "You have view-only access."
```

---

## 11. Design Patterns Used

```
+=====================================================================+
|                    DESIGN PATTERNS SUMMARY                           |
+=====================================================================+

+-------------------+------------------------+------------------------------------------+
| Pattern           | Where                  | Why                                      |
+-------------------+------------------------+------------------------------------------+
| Strategy          | SyncStrategy           | Swap OT <-> CRDT without changing        |
|                   | PersistenceStrategy    | services. Swap Snapshot <-> EventSourced. |
|                   | ConflictResolver       | Swap OT <-> CRDT resolver.               |
+-------------------+------------------------+------------------------------------------+
| Facade            | CollaborationService   | Single entry point hides 6 sub-services. |
|                   |                        | Controller calls one method, not six.    |
+-------------------+------------------------+------------------------------------------+
| Builder           | Document               | Flexible construction with many optional |
|                   |                        | fields. Readable test setup.             |
+-------------------+------------------------+------------------------------------------+
| Observer          | BroadcastService       | Notify all connected clients when an     |
|                   |                        | operation is applied or user joins/leaves.|
+-------------------+------------------------+------------------------------------------+
| Repository        | *Repository interfaces | Separate domain logic from data access.  |
|                   |                        | Swap InMemory for DynamoDB in production. |
+-------------------+------------------------+------------------------------------------+
| Command           | Operation              | Each edit is a first-class object that   |
|                   |                        | can be stored, transformed, replayed,    |
|                   |                        | and undone. Classic Command pattern.     |
+-------------------+------------------------+------------------------------------------+
| Template Method   | PersistenceStrategy    | rebuild() defines the algorithm skeleton |
|                   |                        | (load base + replay ops). Subclasses     |
|                   |                        | decide what "base" means (snapshot vs.   |
|                   |                        | empty document).                         |
+-------------------+------------------------+------------------------------------------+
| Factory           | AppConfig              | Centralizes object creation and wiring.  |
|                   |                        | Pure constructor injection, no framework.|
+-------------------+------------------------+------------------------------------------+
```

### Pattern Interaction Diagram

```
  Controller
      |
      | calls
      v
  CollaborationService [Facade]
      |
      | delegates to
      v
  OperationService
      |
      | uses                   BroadcastService [Observer]
      v                              ^
  SyncStrategy [Strategy]            |
      |                              | notifies
      | uses                         |
      v                        CollaborationService
  ConflictResolver [Strategy]        |
      |                              | after applying
      | transforms                   |
      v                              v
  Operation [Command]          PresenceService
      |                              |
      | persisted via                | adjusts
      v                              v
  PersistenceStrategy [Strategy]  CursorPosition
      |
      | stores in
      v
  *Repository [Repository]
```

---

## 12. Extensibility Points

```
+=====================================================================+
|                    EXTENSIBILITY POINTS                               |
+=====================================================================+

Each extensibility point requires ZERO changes to existing services.
Only new files + one-line wiring change in AppConfig.

1. NEW CONFLICT RESOLUTION ALGORITHM
   +------------------------------------------------------------------+
   | Example: Add Jupiter OT (Google's proprietary OT variant)         |
   |                                                                    |
   | Step 1: Create JupiterResolver implements ConflictResolver         |
   | Step 2: Wire in AppConfig:                                         |
   |           ConflictResolver resolver = new JupiterResolver();       |
   | Step 3: Done. OperationService, CollaborationService unchanged.    |
   +------------------------------------------------------------------+

2. NEW SYNC MODEL
   +------------------------------------------------------------------+
   | Example: Add hybrid OT+CRDT (OT for text, CRDT for formatting)   |
   |                                                                    |
   | Step 1: Create HybridSyncStrategy implements SyncStrategy          |
   | Step 2: Wire in AppConfig                                          |
   | Step 3: Done.                                                      |
   +------------------------------------------------------------------+

3. NEW PERSISTENCE BACKEND
   +------------------------------------------------------------------+
   | Example: Add PostgreSQL-backed persistence                        |
   |                                                                    |
   | Step 1: Create PostgresPersistence implements PersistenceStrategy   |
   | Step 2: Create PostgresOperationRepository implements              |
   |         OperationRepository                                        |
   | Step 3: Wire in AppConfig                                          |
   | Step 4: Done. VersionService, OperationService unchanged.          |
   +------------------------------------------------------------------+

4. NEW PERMISSION MODEL
   +------------------------------------------------------------------+
   | Example: Add COMMENTER role (can comment but not edit)            |
   |                                                                    |
   | Step 1: Add COMMENTER to PermissionRole enum                      |
   | Step 2: Update Permission.canEdit() (still returns false)          |
   | Step 3: Add Permission.canComment() method                         |
   | Step 4: Done. No service changes needed.                           |
   +------------------------------------------------------------------+

5. NEW BROADCAST TRANSPORT
   +------------------------------------------------------------------+
   | Example: Replace simulated WebSocket with actual Netty WebSocket  |
   |                                                                    |
   | Step 1: Create NettyBroadcastService extending BroadcastService    |
   |         (or extract interface first)                               |
   | Step 2: Override broadcastOperation() to send real WebSocket frames|
   | Step 3: Wire in AppConfig                                          |
   | Step 4: Done. CollaborationService unchanged.                      |
   +------------------------------------------------------------------+

6. ADD REAL-TIME COMMENTS
   +------------------------------------------------------------------+
   | Example: Add threaded comments with @mentions                     |
   |                                                                    |
   | Step 1: Create CommentService (new service)                        |
   | Step 2: Add CommentRepository interface + InMemory impl            |
   | Step 3: Inject CommentService into CollaborationService            |
   | Step 4: Add comment methods to CollaborationService (Facade)       |
   | Step 5: Existing services untouched.                               |
   +------------------------------------------------------------------+

7. ADD OFFLINE EDITING + SYNC
   +------------------------------------------------------------------+
   | Example: Support editing while disconnected (like Google Docs)    |
   |                                                                    |
   | Step 1: Create OfflineBuffer (queues ops locally)                  |
   | Step 2: On reconnect: send all queued ops to server                |
   | Step 3: Server transforms each op against missed history           |
   | Step 4: OTSyncStrategy already handles this (clientVersion < server)|
   | Step 5: No core changes needed -- OT was designed for this.        |
   +------------------------------------------------------------------+
```

### AppConfig Wiring (How It All Connects)

```java
/**
 * Pure constructor injection. No framework, no annotations, no magic.
 * Every dependency is explicit and visible in this one file.
 *
 * INTERVIEW TIP: When the interviewer asks "how do you wire this up?",
 * point to this class. It shows you understand dependency injection
 * without needing Spring. Shows you understand the PRINCIPLE, not just the tool.
 */
public class AppConfig {

    // --- Repositories ---
    private final DocumentRepository documentRepository = new InMemoryDocumentRepository();
    private final OperationRepository operationRepository = new InMemoryOperationRepository();
    private final VersionRepository versionRepository = new InMemoryVersionRepository();

    // --- Conflict Resolution (swap OTResolver <-> CRDTResolver here) ---
    private final ConflictResolver conflictResolver = new OTResolver();

    // --- Sync Strategy (swap OT <-> CRDT here) ---
    private final SyncStrategy syncStrategy =
        new OTSyncStrategy(conflictResolver, operationRepository);

    // --- Persistence Strategy (swap Snapshot <-> EventSourced here) ---
    private final PersistenceStrategy persistenceStrategy =
        new SnapshotPersistence(versionRepository, operationRepository);

    // --- Services ---
    private final DocumentService documentService =
        new DocumentService(documentRepository);

    private final OperationService operationService =
        new OperationService(syncStrategy, operationRepository);

    private final PresenceService presenceService = new PresenceService();

    private final VersionService versionService =
        new VersionService(persistenceStrategy, versionRepository);

    private final PermissionService permissionService = new PermissionService();

    private final BroadcastService broadcastService = new BroadcastService();

    // --- Facade ---
    private final CollaborationService collaborationService =
        new CollaborationService(documentService, operationService, presenceService,
                                 versionService, permissionService, broadcastService);

    // --- Controller ---
    private final CollaborationController controller =
        new CollaborationController(collaborationService);

    // --- Getters ---
    public CollaborationController getController()         { return controller; }
    public CollaborationService getCollaborationService()  { return collaborationService; }

    /**
     * To switch from OT to CRDT, change TWO lines:
     *   conflictResolver = new CRDTResolver();
     *   syncStrategy = new CRDTSyncStrategy(conflictResolver);
     *
     * To switch from Snapshot to EventSourced, change ONE line:
     *   persistenceStrategy = new EventSourcedPersistence(operationRepository);
     *
     * Everything else stays the same. That is the power of Strategy + DI.
     */
}
```

### OT vs. CRDT Decision Matrix (Interview Cheat Sheet)

```
+=====================================================================+
|            WHEN TO USE OT vs. CRDT (INTERVIEW ANSWER)                |
+=====================================================================+

  Choose OT when:
    [x] You have a reliable central server
    [x] Strong consistency is required (all users see same state)
    [x] Document size is moderate (OT ops are lightweight)
    [x] You need precise character-level control
    [x] Example: Google Docs, Etherpad, Overleaf

  Choose CRDT when:
    [x] Peer-to-peer or decentralized architecture
    [x] Offline editing is a first-class requirement
    [x] Eventual consistency is acceptable
    [x] You can afford higher memory overhead (char IDs)
    [x] Example: Figma, Yjs, Automerge, Apple Notes

  Choose NEITHER (just use locks) when:
    [x] Low concurrency (< 3 simultaneous editors)
    [x] Edits are infrequent (e.g., wiki pages, config files)
    [x] Simplicity > correctness tradeoff is acceptable

+----------------------------------------------------------------------+
|  "Google Docs uses OT because they own the server infrastructure     |
|   and want strong consistency. Figma uses CRDT because designers     |
|   need offline support and the visual canvas model maps naturally    |
|   to CRDT merge semantics."                                          |
|                                                                       |
|   -- This single sentence will impress most interviewers.            |
+----------------------------------------------------------------------+
```
