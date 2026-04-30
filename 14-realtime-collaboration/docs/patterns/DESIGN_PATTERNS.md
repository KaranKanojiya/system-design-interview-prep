# Design Patterns -- Real-time Collaboration Tool (Google Docs)

> Quick reference for system design interviews. Each pattern includes the ugly
> anti-pattern first, then the clean solution, numbered call chain, ASCII diagram,
> and a one-liner you can drop in an interview.
>
> **Domain:** Real-time collaborative document editing where multiple users type
> simultaneously. Three Strategy interfaces (SyncStrategy, PersistenceStrategy,
> ConflictResolver) make this a strategy-heavy project. Operational Transformation
> (OT) is THE core algorithm -- interviewers will ask you to walk through the
> transform rules.

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Strategy (x3) | Behavioral | SyncStrategy (OT, CRDT), PersistenceStrategy (Snapshot, EventSourced), ConflictResolver (OT, CRDT) |
| 2 | Builder | Creational | Document.Builder with title, owner, content, immutable result |
| 3 | Factory | Creational | AppConfig wires strategies, repos, services |
| 4 | Repository (x3) | Structural (enterprise) | DocumentRepository, OperationRepository, VersionRepository |
| 5 | Facade | Structural | CollaborationService orchestrates sync + broadcast + persist + presence |
| 6 | Observer | Behavioral | BroadcastService notifies connected users of operations/cursor updates |
| 7 | Command | Behavioral | Operation as command object (type + position + content, undo/replay) |
| 8 | Memento | Behavioral | DocumentVersion stores document snapshots for rollback |
| 9 | Mediator | Behavioral | CollaborationService mediates between OperationService, PresenceService, BroadcastService |

---

## 1. Strategy Pattern (Behavioral) -- THE KEY PATTERN

**Three independent Strategy interfaces** power the core of this system.
This is the pattern interviewers care about most -- it directly answers
"How do you handle concurrent edits?", "How do you persist document state?",
and "How do you resolve conflicts?"

### Strategy Interface A: SyncStrategy

Determines **how** concurrent operations from different users are synchronized
into a single consistent document.

```java
public interface SyncStrategy {
    /**
     * Transform an incoming operation against concurrent operations so it can
     * be applied to the current document state without conflict.
     *
     * @param incoming    the operation received from a client
     * @param concurrent  operations applied since the client's known version
     * @return            the transformed operation safe to apply
     */
    Operation transform(Operation incoming, List<Operation> concurrent);
}
```

Two concrete strategies:

| Strategy | Algorithm | Use Case |
|----------|-----------|----------|
| OTSyncStrategy | Operational Transformation -- server transforms each op against concurrent ops | Google Docs model: centralized server, total ordering |
| CRDTSyncStrategy | Conflict-free Replicated Data Type -- each character has a unique ID | Figma model: decentralized, works offline, eventual convergence |

### Strategy Interface B: PersistenceStrategy

Determines **how** the document state is persisted for durability and recovery.

```java
public interface PersistenceStrategy {
    /**
     * Save the current document state.
     *
     * @param document   the document to persist
     * @param operation  the operation that caused this save (may be null for snapshots)
     */
    void save(Document document, Operation operation);

    /**
     * Reconstruct the document state at a specific version.
     *
     * @param documentId  the document ID
     * @param version     the target version (0 = latest)
     * @return            the reconstructed document
     */
    Document load(String documentId, int version);
}
```

Two concrete strategies:

| Strategy | How It Works | Trade-off |
|----------|-------------|-----------|
| SnapshotPersistenceStrategy | Save full document content on every Nth edit | Fast reads, O(docSize) per save, no audit trail |
| EventSourcedPersistenceStrategy | Append every operation to a log, replay to reconstruct | Full audit trail, O(N) reconstruction, compact saves |

### Strategy Interface C: ConflictResolver

Determines **how** conflicting operations are reconciled when two users edit
the same region of text simultaneously.

```java
public interface ConflictResolver {
    /**
     * Resolve a conflict between two operations that affect overlapping
     * document regions.
     *
     * @param op1  the first operation (server-order)
     * @param op2  the second operation (client-submitted)
     * @return     a resolved pair of operations that can both be applied
     */
    OperationPair resolve(Operation op1, Operation op2);
}
```

Two concrete strategies:

| Strategy | Resolution Rule | When Used |
|----------|----------------|-----------|
| OTConflictResolver | Transform positions using OT rules (see section below) | With OTSyncStrategy -- server decides ordering |
| CRDTConflictResolver | Unique character IDs guarantee commutativity -- no conflict | With CRDTSyncStrategy -- no server needed |

### Ugly Anti-Pattern -- Hardcoded Last-Write-Wins

```java
// UGLY: No conflict resolution. Whoever saves last overwrites everyone else.
// Two users typing simultaneously? One loses all their work.

public class UglyCollaborationService {

    private final Map<String, String> documents = new HashMap<>();

    public void editDocument(String docId, String userId, String newContent) {
        // Last write wins -- NO transform, NO merge, NO conflict detection
        documents.put(docId, newContent);
        System.out.println(userId + " saved document " + docId);
        // If Alice and Bob are both editing, Alice's changes vanish
        // when Bob saves 50ms later
    }

    public String getDocument(String docId) {
        return documents.get(docId);
    }

    public void handleConcurrentEdit(String docId, String userId,
                                      String content, int version) {
        // "Conflict resolution": overwrite and pray
        String current = documents.get(docId);
        if (current != null) {
            // No OT, no CRDT, no merge -- just replace
            documents.put(docId, content);
            System.out.println("CONFLICT: " + userId + " overwrote document "
                + docId + " -- other users' edits LOST");
        }
    }

    // Cannot switch between OT and CRDT -- no strategy interface
    // Cannot switch between snapshot and event-sourced -- hardcoded HashMap
    // No operation history -- cannot undo, replay, or audit
    // No presence awareness -- no cursor tracking
}
```

**Problems:**
1. Last-write-wins destroys concurrent edits -- no transform, no merge
2. Sync algorithm hardcoded -- cannot switch OT/CRDT without rewriting
3. Persistence hardcoded as in-memory HashMap -- cannot swap to event-sourced
4. No conflict resolver -- overlapping edits silently overwrite
5. No operation log -- cannot undo, replay, or reconstruct history
6. No presence tracking -- users cannot see each other's cursors
7. Testing requires the full service -- no strategy injection

### Clean Solution -- Three Strategy Interfaces

```java
// CLEAN: SyncStrategy, PersistenceStrategy, and ConflictResolver
// are all injected. Each can be swapped, tested, and evolved independently.

public class CleanCollaborationService {

    private final SyncStrategy          syncStrategy;
    private final PersistenceStrategy   persistenceStrategy;
    private final ConflictResolver      conflictResolver;
    private final OperationService      operationService;
    private final BroadcastService      broadcastService;
    private final PresenceService       presenceService;

    public CleanCollaborationService(SyncStrategy syncStrategy,
                                     PersistenceStrategy persistenceStrategy,
                                     ConflictResolver conflictResolver,
                                     OperationService operationService,
                                     BroadcastService broadcastService,
                                     PresenceService presenceService) {
        this.syncStrategy        = syncStrategy;
        this.persistenceStrategy = persistenceStrategy;
        this.conflictResolver    = conflictResolver;
        this.operationService    = operationService;
        this.broadcastService    = broadcastService;
        this.presenceService     = presenceService;
    }

    public void applyOperation(String docId, Operation incoming) {
        // 1. Get concurrent ops since client's last known version
        List<Operation> concurrent = operationService.getConcurrentOps(
            docId, incoming.getClientVersion());

        // 2. Transform incoming op against concurrent ops (Strategy A)
        Operation transformed = syncStrategy.transform(incoming, concurrent);

        // 3. Apply to document, persist (Strategy B)
        Document doc = operationService.apply(docId, transformed);
        persistenceStrategy.save(doc, transformed);

        // 4. Broadcast to all connected users (Observer)
        broadcastService.broadcast(docId, transformed);
    }

    public void updateCursor(String docId, String userId, int position) {
        presenceService.updateCursor(docId, userId, position);
        broadcastService.broadcastCursor(docId, userId, position);
    }
}
```

### ASCII Diagram -- Three Strategy Axes

```
  SyncStrategy                PersistenceStrategy            ConflictResolver
 (how ops are synced)       (how doc is persisted)         (how conflicts resolve)
        |                          |                               |
  +-----+-----+            +------+------+                +-------+-------+
  |           |            |             |                |               |
  OT         CRDT       Snapshot    EventSourced     OTResolver     CRDTResolver
 (server    (peer-to-   (full doc   (append ops,     (transform     (unique IDs,
  transforms  peer,      on every    replay to        positions      commutative,
  each op)    unique     Nth edit)   reconstruct)     using OT       no conflict)
              char IDs)                                rules)
```

### Numbered Call Chain -- User Types a Character (OT Strategy)

```
1.  Alice types "H" at position 5 in document "doc-42"
2.  Client creates Operation(INSERT, pos=5, content="H", clientVersion=10)
3.  Client sends operation over WebSocket to CollaborationService
4.  CollaborationService calls OperationService.getConcurrentOps("doc-42", 10)
5.  OperationService queries OperationRepository for ops with version > 10
6.  Returns: [Bob's INSERT at pos=3 (version 11)]
7.  CollaborationService calls SyncStrategy.transform(aliceOp, [bobOp])
8.  OTSyncStrategy: Bob inserted at pos=3 (before pos=5), so Alice's pos shifts to 6
9.  Transformed operation: INSERT at position 6, content="H"
10. CollaborationService calls OperationService.apply("doc-42", transformedOp)
11. OperationService updates in-memory document, assigns version 12
12. CollaborationService calls PersistenceStrategy.save(doc, transformedOp)
13. EventSourcedPersistenceStrategy appends operation to the log
14. CollaborationService calls BroadcastService.broadcast("doc-42", transformedOp)
15. BroadcastService sends transformed op to Bob and all other connected users
16. Bob's client receives the op and applies it locally (no transform needed -- already transformed)
```

### Numbered Call Chain -- Cursor Movement (Presence)

```
1. Alice moves cursor to position 42 in document "doc-42"
2. Client sends cursor update over WebSocket
3. CollaborationService calls PresenceService.updateCursor("doc-42", "alice", 42)
4. PresenceService stores cursor in Redis: HSET presence:doc-42 alice 42
5. PresenceService sets TTL: EXPIRE presence:doc-42 30 (heartbeat keeps alive)
6. CollaborationService calls BroadcastService.broadcastCursor("doc-42", "alice", 42)
7. BroadcastService sends cursor position to Bob and all connected users
8. Bob's client renders Alice's cursor at position 42 with her color
```

### Interview One-Liner

> "We inject three strategies -- SyncStrategy picks OT vs. CRDT for conflict-free
> merging, PersistenceStrategy picks snapshot vs. event-sourced for durability, and
> ConflictResolver handles the transform rules. The Facade (CollaborationService)
> orchestrates all three plus presence and broadcast."

**Cross-reference:**
- OT transform rules: see Section 1b (below)
- Facade orchestration: see Pattern 5
- Observer broadcast: see Pattern 6
- Command operations: see Pattern 7

---

## 1b. OT Transform Rules -- THE INTERVIEW DEEP DIVE

This is the section interviewers test most. You must be able to walk through
all 4 transform cases with concrete examples.

### The Core Idea

When two users edit concurrently, the server must **transform** one operation
against the other so that both can be applied and the document converges to
the same state regardless of application order.

```
  Alice's state: "ABCDE"     Bob's state: "ABCDE"
       |                           |
  Alice: INSERT("X", pos=2)   Bob: INSERT("Y", pos=4)
       |                           |
       v                           v
  Alice sees: "ABXCDE"        Bob sees: "ABCDYE"
       |                           |
       +--- Server transforms -----+
       |                           |
  Apply Bob' to Alice's doc    Apply Alice' to Bob's doc
       |                           |
       v                           v
  "ABXCDYE"                   "ABXCDYE"   <-- CONVERGED!
```

### Transform Function Signature

```java
/**
 * Transform op2 against op1, assuming op1 has already been applied.
 * Returns op2' (op2-prime) that achieves the same intent as op2 but
 * in the context where op1 has already happened.
 */
public Operation transform(Operation op1, Operation op2) {
    // 4 cases based on (op1.type, op2.type)
}
```

### Case 1: INSERT / INSERT

Both users insert text. The second insert may need its position shifted.

```
Initial document: "HELLO"

Alice: INSERT("X", pos=2)  -->  "HEXLLO"
Bob:   INSERT("Y", pos=4)  -->  "HELLY0"   (Bob intended "HELLYO")

Transform Bob against Alice (Alice already applied):
  - Alice inserted at pos=2, Bob inserts at pos=4
  - Since Bob's pos (4) >= Alice's pos (2), shift Bob's pos by Alice's insert length
  - Bob' = INSERT("Y", pos=4+1=5)

Apply Bob' to Alice's doc: "HEXLLO" --> "HEXLLYO"
Apply Alice' to Bob's doc: "HELLYO" --> "HEXLLYO"   CONVERGED!
```

**Java implementation:**

```java
// Case 1: INSERT / INSERT
if (op1.getType() == INSERT && op2.getType() == INSERT) {
    if (op2.getPosition() >= op1.getPosition()) {
        // op2 is at or after op1 -- shift right by op1's insert length
        return new Operation(INSERT,
            op2.getPosition() + op1.getContent().length(),
            op2.getContent(), op2.getUserId(), op2.getDocumentId());
    } else {
        // op2 is before op1 -- no shift needed
        return op2;  // unchanged
    }
}
```

**Edge case -- same position:**

```
Alice: INSERT("X", pos=3)
Bob:   INSERT("Y", pos=3)

Tie-breaking rule: user with lower userId goes first.
If Alice < Bob: Bob' = INSERT("Y", pos=4)  -- Alice's insert goes first
If Bob < Alice: Bob stays at pos=3, Alice shifts to pos=4
```

### Case 2: INSERT / DELETE

One user inserts, the other deletes. The delete position may shift.

```
Initial document: "HELLO"

Alice: INSERT("X", pos=2)  -->  "HEXLLO"
Bob:   DELETE(pos=3, len=1) -->  "HELO"   (Bob deleted the first "L")

Transform Bob against Alice (Alice already applied):
  - Alice inserted at pos=2, Bob deletes at pos=3
  - Since Bob's pos (3) >= Alice's pos (2), shift Bob's pos by insert length
  - Bob' = DELETE(pos=3+1=4, len=1)

Apply Bob' to Alice's doc: "HEXLLO" --> "HEXLO"
```

**Java implementation:**

```java
// Case 2: INSERT / DELETE (op1=INSERT, op2=DELETE)
if (op1.getType() == INSERT && op2.getType() == DELETE) {
    if (op2.getPosition() >= op1.getPosition()) {
        // Delete is after insert -- shift right
        return new Operation(DELETE,
            op2.getPosition() + op1.getContent().length(),
            op2.getContent(), op2.getUserId(), op2.getDocumentId());
    } else {
        // Delete is before insert -- no shift
        return op2;
    }
}
```

### Case 3: DELETE / INSERT

One user deletes, the other inserts. The insert position may shift.

```
Initial document: "HELLO"

Alice: DELETE(pos=1, len=1) -->  "HLLO"   (Alice deleted "E")
Bob:   INSERT("X", pos=3)  -->  "HELXLO"

Transform Bob against Alice (Alice already applied):
  - Alice deleted at pos=1 (len=1), Bob inserts at pos=3
  - Since Bob's pos (3) > Alice's pos (1), shift Bob's pos LEFT by delete length
  - Bob' = INSERT("X", pos=3-1=2)

Apply Bob' to Alice's doc: "HLLO" --> "HLXLO"
```

**Java implementation:**

```java
// Case 3: DELETE / INSERT (op1=DELETE, op2=INSERT)
if (op1.getType() == DELETE && op2.getType() == INSERT) {
    if (op2.getPosition() > op1.getPosition()) {
        // Insert is after delete -- shift left by delete length
        int deleteLen = op1.getContent() != null ? op1.getContent().length() : 1;
        return new Operation(INSERT,
            op2.getPosition() - deleteLen,
            op2.getContent(), op2.getUserId(), op2.getDocumentId());
    } else {
        // Insert is at or before delete -- no shift
        return op2;
    }
}
```

### Case 4: DELETE / DELETE

Both users delete text. Positions shift, and overlapping deletes may cancel.

```
Initial document: "HELLO"

Alice: DELETE(pos=1, len=1) -->  "HLLO"   (deleted "E")
Bob:   DELETE(pos=3, len=1) -->  "HELO"   (deleted second "L")

Transform Bob against Alice (Alice already applied):
  - Alice deleted at pos=1 (before Bob's pos=3), shift Bob left
  - Bob' = DELETE(pos=3-1=2, len=1)

Apply Bob' to Alice's doc: "HLLO" --> "HLO"
```

**Overlapping delete (same character):**

```
Alice: DELETE(pos=2, len=1)  -- deletes "L" at pos 2
Bob:   DELETE(pos=2, len=1)  -- also deletes "L" at pos 2

Both deleted the same character!
Bob' = NO-OP (nothing left to delete)
```

**Java implementation:**

```java
// Case 4: DELETE / DELETE
if (op1.getType() == DELETE && op2.getType() == DELETE) {
    int op1Len = op1.getContent() != null ? op1.getContent().length() : 1;

    if (op2.getPosition() > op1.getPosition()) {
        // op2 is after op1 -- shift left
        return new Operation(DELETE,
            op2.getPosition() - op1Len,
            op2.getContent(), op2.getUserId(), op2.getDocumentId());
    } else if (op2.getPosition() < op1.getPosition()) {
        // op2 is before op1 -- no shift
        return op2;
    } else {
        // Same position -- both deleted the same thing -> NO-OP
        return Operation.noOp(op2.getUserId(), op2.getDocumentId());
    }
}
```

### Complete Transform Matrix

```
+------------------+----------------------------+----------------------------+
|  op1 \ op2       |        INSERT              |        DELETE              |
+------------------+----------------------------+----------------------------+
|                  | if op2.pos >= op1.pos:     | if op2.pos >= op1.pos:     |
|  INSERT          |   op2.pos += op1.len       |   op2.pos += op1.len       |
|                  | else:                      | else:                      |
|                  |   no change                |   no change                |
+------------------+----------------------------+----------------------------+
|                  | if op2.pos > op1.pos:      | if op2.pos > op1.pos:      |
|  DELETE           |   op2.pos -= op1.len       |   op2.pos -= op1.len       |
|                  | else:                      | elif same pos: NO-OP       |
|                  |   no change                | else: no change            |
+------------------+----------------------------+----------------------------+
```

### OT Interview One-Liner

> "OT transforms the second operation's position against the first: inserts
> shift positions right, deletes shift positions left, and same-position
> deletes become no-ops. The server applies ops in total order and broadcasts
> the transformed version so every client converges."

---

## 2. Builder Pattern (Creational) -- Document.Builder

### Why Builder?

A `Document` has many fields (id, title, owner, content, version, created/updated
timestamps, collaborators). Telescoping constructors are unreadable. Builder gives
named parameters with validation and an immutable result.

### Ugly Anti-Pattern -- Telescoping Constructor

```java
// UGLY: Which String is the title? Which is the owner? Which is the content?
// What if you want to skip content and set collaborators?

public class UglyDocument {
    public UglyDocument(String id, String title, String ownerId,
                        String content, int version, Instant createdAt,
                        Instant updatedAt, List<String> collaborators) {
        // 8 parameters -- impossible to read at the call site
        this.id = id;
        this.title = title;
        this.ownerId = ownerId;
        this.content = content;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.collaborators = collaborators;
    }
}

// Call site -- which String is which?
Document doc = new UglyDocument("doc-1", "Meeting Notes", "user-42",
    "", 0, Instant.now(), Instant.now(), List.of("user-43", "user-44"));
```

### Clean Solution -- Document.Builder

```java
public class Document {
    private final String id;
    private final String title;
    private final String ownerId;
    private final String content;
    private final int version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<String> collaboratorIds;

    private Document(Builder builder) {
        this.id              = builder.id;
        this.title           = builder.title;
        this.ownerId         = builder.ownerId;
        this.content         = builder.content;
        this.version         = builder.version;
        this.createdAt       = builder.createdAt;
        this.updatedAt       = builder.updatedAt;
        this.collaboratorIds = List.copyOf(builder.collaboratorIds);
    }

    // Getters omitted for brevity

    public static class Builder {
        private String id;
        private String title;
        private String ownerId;
        private String content = "";
        private int version = 0;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        private List<String> collaboratorIds = new ArrayList<>();

        public Builder id(String id)               { this.id = id; return this; }
        public Builder title(String title)         { this.title = title; return this; }
        public Builder ownerId(String ownerId)     { this.ownerId = ownerId; return this; }
        public Builder content(String content)     { this.content = content; return this; }
        public Builder version(int version)        { this.version = version; return this; }
        public Builder createdAt(Instant t)        { this.createdAt = t; return this; }
        public Builder updatedAt(Instant t)        { this.updatedAt = t; return this; }
        public Builder collaboratorIds(List<String> ids) {
            this.collaboratorIds = ids; return this;
        }

        public Document build() {
            Objects.requireNonNull(id, "Document ID is required");
            Objects.requireNonNull(title, "Title is required");
            Objects.requireNonNull(ownerId, "Owner ID is required");
            return new Document(this);
        }
    }
}

// Clean call site -- every field is named
Document doc = new Document.Builder()
    .id("doc-1")
    .title("Meeting Notes")
    .ownerId("user-42")
    .content("")
    .collaboratorIds(List.of("user-43", "user-44"))
    .build();
```

### Interview One-Liner

> "Document.Builder gives named parameters, validates required fields at build
> time, and returns an immutable Document. No more guessing which String is
> the title vs. the owner."

**Cross-reference:** Factory (Pattern 3) uses Builder internally.

---

## 3. Factory Pattern (Creational) -- AppConfig

### Why Factory?

`AppConfig` is the single place where we decide which strategy implementations
to use. Switching from OT to CRDT, or from snapshot to event-sourced persistence,
is a one-line change here.

### Ugly Anti-Pattern -- Scattered `new` Calls

```java
// UGLY: Every class that needs a service creates its own dependencies.
// Switching from OT to CRDT requires finding and changing 15 files.

public class UglyMain {
    public static void main(String[] args) {
        // Hardcoded OT everywhere -- switch to CRDT? Change every line.
        OTSyncStrategy sync = new OTSyncStrategy();
        OTConflictResolver resolver = new OTConflictResolver();
        SnapshotPersistenceStrategy persistence = new SnapshotPersistenceStrategy();

        // Each service creates its own dependencies too
        OperationService opService = new OperationService(
            new InMemoryOperationRepository(), sync);
        BroadcastService broadcast = new BroadcastService();
        PresenceService presence = new PresenceService();

        // Duplicate creation in tests, in controllers, in background jobs...
    }
}
```

### Clean Solution -- AppConfig Factory

```java
public class AppConfig {

    private final SyncStrategy syncStrategy;
    private final PersistenceStrategy persistenceStrategy;
    private final ConflictResolver conflictResolver;

    private final DocumentRepository documentRepository;
    private final OperationRepository operationRepository;
    private final VersionRepository versionRepository;

    private final OperationService operationService;
    private final BroadcastService broadcastService;
    private final PresenceService presenceService;
    private final CollaborationService collaborationService;

    public AppConfig() {
        // --- Strategies: change ONE line to switch algorithms ---
        this.syncStrategy        = new OTSyncStrategy();
        this.conflictResolver    = new OTConflictResolver();
        this.persistenceStrategy = new EventSourcedPersistenceStrategy();

        // --- Repositories ---
        this.documentRepository  = new InMemoryDocumentRepository();
        this.operationRepository = new InMemoryOperationRepository();
        this.versionRepository   = new InMemoryVersionRepository();

        // --- Services ---
        this.operationService = new OperationService(
            operationRepository, documentRepository, syncStrategy);
        this.broadcastService = new BroadcastService();
        this.presenceService  = new PresenceService();

        // --- Facade ---
        this.collaborationService = new CollaborationService(
            syncStrategy, persistenceStrategy, conflictResolver,
            operationService, broadcastService, presenceService);
    }

    // Getters for all services
    public CollaborationService getCollaborationService() {
        return collaborationService;
    }
    // ... other getters
}
```

### Numbered Call Chain -- Application Startup

```
1. main() creates AppConfig
2. AppConfig instantiates OTSyncStrategy (Strategy A)
3. AppConfig instantiates OTConflictResolver (Strategy C)
4. AppConfig instantiates EventSourcedPersistenceStrategy (Strategy B)
5. AppConfig instantiates 3 in-memory repositories
6. AppConfig instantiates OperationService with repos + sync strategy
7. AppConfig instantiates BroadcastService and PresenceService
8. AppConfig instantiates CollaborationService (Facade) with all dependencies
9. main() calls config.getCollaborationService() to start handling requests
```

### Interview One-Liner

> "AppConfig is the single wiring point. Switching from OT to CRDT is one line:
> change `new OTSyncStrategy()` to `new CRDTSyncStrategy()`. Everything downstream
> is coded to the interface."

**Cross-reference:** All strategies (Pattern 1), all repositories (Pattern 4).

---

## 4. Repository Pattern (Structural) -- Three Repositories

### Why Repository?

Three distinct data types need storage: documents, operations, and version
snapshots. Each repository abstracts the persistence mechanism so we can
swap in-memory (testing/demo) for PostgreSQL/Redis (production).

### The Three Repositories

```java
public interface DocumentRepository {
    void save(Document document);
    Optional<Document> findById(String documentId);
    List<Document> findByCollaborator(String userId);
    void delete(String documentId);
}

public interface OperationRepository {
    void save(Operation operation);
    List<Operation> findByDocumentId(String documentId);
    List<Operation> findByDocumentIdAndVersionGreaterThan(
        String documentId, int version);
    Optional<Operation> findLatest(String documentId);
}

public interface VersionRepository {
    void save(DocumentVersion version);
    Optional<DocumentVersion> findByDocumentIdAndVersion(
        String documentId, int version);
    List<DocumentVersion> findByDocumentId(String documentId);
    Optional<DocumentVersion> findLatest(String documentId);
}
```

### Repository Comparison

| Repository | Entity | Key Operations | Production Store |
|-----------|--------|---------------|-----------------|
| DocumentRepository | Document | save, findById, findByCollaborator | PostgreSQL |
| OperationRepository | Operation | save, findByVersion, findLatest | Kafka + PostgreSQL |
| VersionRepository | DocumentVersion | save, findByVersion, findLatest | PostgreSQL + S3 |

### Ugly Anti-Pattern -- Raw SQL Everywhere

```java
// UGLY: SQL strings scattered across service classes. Changing from
// PostgreSQL to DynamoDB means rewriting every service.

public class UglyOperationService {
    private final DataSource ds;

    public List<Operation> getConcurrentOps(String docId, int version) {
        String sql = "SELECT * FROM operations "
            + "WHERE document_id = ? AND version > ? ORDER BY version";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ps.setInt(2, version);
            // ... ResultSet mapping, error handling, connection cleanup
            // Duplicated in 5 other methods in this class alone
        }
    }
}
```

### Clean Solution -- Repository Interface

```java
// CLEAN: Service depends on the interface. In-memory for tests,
// JDBC for production, DynamoDB for serverless -- swap freely.

public class CleanOperationService {
    private final OperationRepository operationRepo;

    public CleanOperationService(OperationRepository operationRepo) {
        this.operationRepo = operationRepo;
    }

    public List<Operation> getConcurrentOps(String docId, int version) {
        return operationRepo.findByDocumentIdAndVersionGreaterThan(
            docId, version);
    }
}
```

### Interview One-Liner

> "Three repositories (Document, Operation, Version) abstract persistence.
> In-memory for tests, PostgreSQL + Kafka for production. Services never
> see SQL or know the storage engine."

**Cross-reference:** Factory (Pattern 3) wires repositories. Memento (Pattern 8)
uses VersionRepository.

---

## 5. Facade Pattern (Structural) -- CollaborationService

### Why Facade?

A single user edit involves: sync (OT transform), persistence (save op/snapshot),
broadcast (WebSocket push), and presence (cursor update). CollaborationService
hides this complexity behind a single `applyOperation()` call.

### ASCII Diagram -- What the Facade Hides

```
  Client (WebSocket)
       |
       v
  +------------------------------------+
  |     CollaborationService (FACADE)  |
  |                                    |
  |  applyOperation(docId, op)         |
  |  updateCursor(docId, userId, pos)  |
  |  joinDocument(docId, userId)       |
  |  leaveDocument(docId, userId)      |
  +------------------------------------+
       |            |            |            |
       v            v            v            v
  +---------+  +----------+  +----------+  +----------+
  |Operation|  |Broadcast |  |Persistence|  |Presence  |
  |Service  |  |Service   |  |Strategy   |  |Service   |
  |(sync +  |  |(WebSocket|  |(snapshot  |  |(cursors, |
  | apply)  |  | push)    |  | or event  |  | who's    |
  |         |  |          |  | sourced)  |  | online)  |
  +---------+  +----------+  +----------+  +----------+
```

### Ugly Anti-Pattern -- Client Orchestrates Everything

```java
// UGLY: Client code (controller) has to coordinate 4 services manually.
// Every endpoint that handles an edit repeats this 15-line dance.

public class UglyDocumentController {
    public void handleEdit(String docId, Operation op) {
        List<Operation> concurrent = operationService.getConcurrentOps(
            docId, op.getClientVersion());
        Operation transformed = otEngine.transform(op, concurrent);
        Document doc = documentService.apply(docId, transformed);
        snapshotService.maybeSave(doc);
        operationLogService.append(transformed);
        webSocketService.broadcast(docId, transformed);
        presenceService.heartbeat(docId, op.getUserId());
        // Cursor update? Repeat similar code...
        // Join document? Repeat again...
    }
}
```

### Clean Solution -- Facade Orchestrates

```java
// CLEAN: Controller calls one method. Facade handles the 4-service dance.

public class CleanDocumentController {
    private final CollaborationService facade;

    public void handleEdit(String docId, Operation op) {
        facade.applyOperation(docId, op);  // ONE call
    }

    public void handleCursorMove(String docId, String userId, int pos) {
        facade.updateCursor(docId, userId, pos);  // ONE call
    }
}
```

### Numbered Call Chain -- Full Edit Flow Through Facade

```
1.  WebSocket receives edit message from Alice
2.  Controller deserializes into Operation, calls CollaborationService.applyOperation()
3.  Facade calls OperationService.getConcurrentOps(docId, clientVersion)
4.  OperationService queries OperationRepository (ops since client's version)
5.  Facade calls SyncStrategy.transform(incoming, concurrentOps)
6.  OTSyncStrategy transforms position (see OT rules in section 1b)
7.  Facade calls OperationService.apply(docId, transformedOp)
8.  OperationService updates document content, increments version
9.  Facade calls PersistenceStrategy.save(doc, transformedOp)
10. EventSourcedPersistenceStrategy appends op to operation log
11. (Every 100 ops) SnapshotPersistenceStrategy also saves full document
12. Facade calls BroadcastService.broadcast(docId, transformedOp)
13. BroadcastService pushes transformed op to all connected users except sender
14. Each client applies the transformed op to their local document copy
```

### Interview One-Liner

> "CollaborationService is a Facade that orchestrates sync, persist, broadcast,
> and presence behind a single `applyOperation()` call. The controller never
> touches OT transforms or WebSockets directly."

**Cross-reference:** Mediator (Pattern 9) -- CollaborationService also acts as
a Mediator between the services it orchestrates.

---

## 6. Observer Pattern (Behavioral) -- BroadcastService

### Why Observer?

When a user edits a document, all other connected users must see the change
in real time. BroadcastService maintains a registry of connected sessions
per document and pushes operations/cursor updates to all observers.

### Ugly Anti-Pattern -- Hardcoded Notification

```java
// UGLY: OperationService directly knows about WebSockets, email
// notifications, analytics logging. Adding a new listener means
// editing OperationService.

public class UglyOperationService {
    private final WebSocketHandler webSocket;
    private final EmailService email;
    private final AnalyticsService analytics;

    public void apply(String docId, Operation op) {
        // Apply the operation...
        document.applyOp(op);

        // Hardcoded notifications -- cannot add/remove without editing
        webSocket.send(docId, op);
        email.notifyMentioned(docId, op);
        analytics.logEdit(docId, op);
        // Need to add Slack notification? Edit THIS class.
    }
}
```

### Clean Solution -- Observer Registry

```java
public interface DocumentObserver {
    void onOperation(String documentId, Operation operation);
    void onCursorUpdate(String documentId, String userId, int position);
    void onUserJoined(String documentId, String userId);
    void onUserLeft(String documentId, String userId);
}

public class BroadcastService {
    // documentId -> set of observers (WebSocket sessions)
    private final Map<String, Set<DocumentObserver>> observers =
        new ConcurrentHashMap<>();

    public void subscribe(String documentId, DocumentObserver observer) {
        observers.computeIfAbsent(documentId, k -> ConcurrentHashMap.newKeySet())
                 .add(observer);
    }

    public void unsubscribe(String documentId, DocumentObserver observer) {
        Set<DocumentObserver> docObservers = observers.get(documentId);
        if (docObservers != null) {
            docObservers.remove(observer);
        }
    }

    public void broadcast(String documentId, Operation operation) {
        Set<DocumentObserver> docObservers = observers.get(documentId);
        if (docObservers != null) {
            for (DocumentObserver observer : docObservers) {
                observer.onOperation(documentId, operation);
            }
        }
    }

    public void broadcastCursor(String documentId, String userId, int pos) {
        Set<DocumentObserver> docObservers = observers.get(documentId);
        if (docObservers != null) {
            for (DocumentObserver observer : docObservers) {
                observer.onCursorUpdate(documentId, userId, pos);
            }
        }
    }
}
```

### ASCII Diagram -- Observer Fan-out

```
  Alice edits document "doc-42"
       |
       v
  CollaborationService.applyOperation()
       |
       v
  BroadcastService.broadcast("doc-42", transformedOp)
       |
       +---> WebSocketObserver(Bob)    --> Bob's browser
       |
       +---> WebSocketObserver(Carol)  --> Carol's browser
       |
       +---> WebSocketObserver(Dave)   --> Dave's browser
       |
       +---> AnalyticsObserver         --> log edit event
       |
       +---> AuditObserver             --> compliance log
```

### Numbered Call Chain -- Broadcast After Edit

```
1. CollaborationService calls BroadcastService.broadcast("doc-42", op)
2. BroadcastService looks up observers for "doc-42"
3. Finds 3 WebSocketObservers (Bob, Carol, Dave) + 1 AnalyticsObserver
4. Calls onOperation() on each observer
5. WebSocketObserver(Bob) serializes op to JSON, sends over Bob's WebSocket
6. WebSocketObserver(Carol) does the same for Carol
7. WebSocketObserver(Dave) does the same for Dave
8. AnalyticsObserver logs the edit event (userId, docId, timestamp, opType)
9. Alice is NOT notified (she already applied the op locally)
```

### Interview One-Liner

> "BroadcastService maintains a registry of observers per document. When an
> operation is applied, it fans out to all connected WebSocket sessions. Adding
> analytics or audit logging is just registering a new observer."

**Cross-reference:** Facade (Pattern 5) calls BroadcastService. Presence
(Pattern 9) also broadcasts cursor updates.

---

## 7. Command Pattern (Behavioral) -- Operation as Command Object

### Why Command?

Every edit (insert, delete, retain) is captured as an Operation object with
all the information needed to apply, transform, undo, and replay it. Operations
are first-class objects that can be logged, queued, and transmitted.

### Operation as a Command

```java
public class Operation {
    private final OperationType type;      // INSERT, DELETE, RETAIN
    private final int position;            // where in the document
    private final String content;          // what text (for INSERT/DELETE)
    private final String userId;           // who performed the operation
    private final String documentId;       // which document
    private final int clientVersion;       // client's known version when issued
    private final int serverVersion;       // assigned by server after transform
    private final Instant timestamp;       // when it was created

    // Can be applied
    public Document apply(Document doc) {
        switch (type) {
            case INSERT:
                return doc.insertAt(position, content);
            case DELETE:
                return doc.deleteAt(position, content.length());
            case RETAIN:
                return doc;  // no change
        }
    }

    // Can be undone (inverse operation)
    public Operation inverse() {
        switch (type) {
            case INSERT:
                return new Operation(DELETE, position, content,
                    userId, documentId);
            case DELETE:
                return new Operation(INSERT, position, content,
                    userId, documentId);
            case RETAIN:
                return this;
        }
    }

    // Can be serialized and sent over WebSocket
    // Can be stored in operation log for replay
    // Can be transformed against other operations (OT)
}
```

### Ugly Anti-Pattern -- Edit as Method Call with Side Effects

```java
// UGLY: Edits are method calls that directly mutate state. Cannot undo,
// cannot replay, cannot log, cannot transform.

public class UglyDocument {
    private StringBuilder content;

    public void insert(int pos, String text) {
        content.insert(pos, text);
        // Edit is gone. Cannot undo. Cannot tell other users what happened.
        // Cannot transform against concurrent edits.
    }

    public void delete(int pos, int len) {
        content.delete(pos, pos + len);
        // Deleted text is lost forever. Cannot undo.
    }
}
```

### Clean Solution -- Operation as First-Class Command

```java
// CLEAN: Every edit is an Operation object. Can be applied, undone,
// transformed, serialized, logged, and replayed.

public class CleanDocument {
    private String content;
    private int version;
    private final List<Operation> operationHistory = new ArrayList<>();

    public void apply(Operation op) {
        // Apply the operation
        switch (op.getType()) {
            case INSERT:
                content = content.substring(0, op.getPosition())
                    + op.getContent()
                    + content.substring(op.getPosition());
                break;
            case DELETE:
                content = content.substring(0, op.getPosition())
                    + content.substring(op.getPosition()
                        + op.getContent().length());
                break;
        }
        version++;
        operationHistory.add(op);
    }

    public void undo() {
        if (!operationHistory.isEmpty()) {
            Operation last = operationHistory.remove(
                operationHistory.size() - 1);
            apply(last.inverse());  // Apply the inverse command
        }
    }
}
```

### Command Properties Table

| Property | How Operation Supports It |
|----------|--------------------------|
| Encapsulate action | type + position + content fully describe the edit |
| Undo | inverse() returns the opposite operation |
| Replay | Apply operations in order to reconstruct any version |
| Log | Append to operation log (event sourcing) |
| Transform | OT transforms position against concurrent ops |
| Serialize | Send over WebSocket as JSON |
| Queue | Buffer operations when offline, send on reconnect |

### Interview One-Liner

> "Every edit is an Operation command object with type, position, and content.
> It can be applied, undone (via inverse), transformed (OT), serialized
> (WebSocket), and appended to the operation log (event sourcing)."

**Cross-reference:** OT transform rules (Section 1b) operate on Command objects.
Event sourcing (Pattern 8) replays Command objects.

---

## 8. Memento Pattern (Behavioral) -- DocumentVersion (Snapshots)

### Why Memento?

DocumentVersion captures a complete snapshot of a document at a specific version.
This allows rollback to any previous state without replaying the entire operation
log from the beginning.

### Ugly Anti-Pattern -- No Snapshots, Replay Everything

```java
// UGLY: To get the document at version 500, replay all 500 operations
// from the beginning. Takes seconds for large documents with long histories.

public class UglyVersionService {
    public Document getDocumentAtVersion(String docId, int version) {
        Document doc = new Document("");  // empty document
        List<Operation> allOps = operationRepo.findAll(docId);
        for (int i = 0; i < version && i < allOps.size(); i++) {
            doc.apply(allOps.get(i));
        }
        // Version 50,000? Replay 50,000 operations. Every. Time.
        return doc;
    }
}
```

### Clean Solution -- Memento Snapshots

```java
public class DocumentVersion {
    private final String documentId;
    private final int version;
    private final String content;         // full document snapshot (memento)
    private final Instant timestamp;
    private final String createdByUserId;

    // This IS the memento -- a frozen snapshot of document state
    // No operations needed to reconstruct -- just read the content

    public Document restore() {
        return new Document.Builder()
            .id(documentId)
            .content(content)
            .version(version)
            .build();
    }
}

public class VersionService {
    private final VersionRepository versionRepo;
    private final OperationRepository operationRepo;

    /**
     * Get document at any version:
     * 1. Find nearest snapshot BEFORE target version
     * 2. Replay only the ops between snapshot and target
     *
     * Snapshot every 100 ops means at most 99 ops to replay.
     */
    public Document getDocumentAtVersion(String docId, int targetVersion) {
        // 1. Find nearest snapshot
        DocumentVersion snapshot = versionRepo
            .findNearestBefore(docId, targetVersion)
            .orElse(DocumentVersion.empty(docId));

        // 2. Replay only the gap
        List<Operation> gap = operationRepo
            .findByDocumentIdAndVersionBetween(
                docId, snapshot.getVersion(), targetVersion);

        Document doc = snapshot.restore();
        for (Operation op : gap) {
            doc.apply(op);
        }
        return doc;
    }

    /**
     * Periodically save snapshots (every 100 operations).
     */
    public void maybeSaveSnapshot(Document doc) {
        if (doc.getVersion() % 100 == 0) {
            DocumentVersion snapshot = new DocumentVersion(
                doc.getId(), doc.getVersion(), doc.getContent(),
                Instant.now(), doc.getLastEditUserId());
            versionRepo.save(snapshot);
        }
    }
}
```

### ASCII Diagram -- Snapshot + Op Replay

```
  Operation log:
  [op1][op2][op3]...[op99][op100][op101]...[op199][op200][op201]...[op250]
                           |                        |                 |
                        SNAPSHOT                 SNAPSHOT          target
                        v=100                    v=200            v=250
                        content="..."            content="..."

  To get version 250:
  1. Find nearest snapshot: v=200
  2. Replay ops 201-250 (only 50 ops, not 250)
  3. Return reconstructed document

  Without snapshots:
  1. Start from empty document
  2. Replay ops 1-250 (all 250 ops)
  3. 5x slower for v=250, 500x slower for v=50000
```

### Snapshot Frequency Trade-offs

| Snapshot Interval | Storage Cost | Reconstruction Cost | Best For |
|-------------------|-------------|-------------------|----------|
| Every 10 ops | High (many snapshots) | At most 9 ops to replay | Frequently accessed history |
| Every 100 ops | Medium | At most 99 ops to replay | Default -- good balance |
| Every 1000 ops | Low | At most 999 ops to replay | Write-heavy, rarely accessed |
| Never | Zero | Replay from beginning | Simple systems, short docs |

### Interview One-Liner

> "DocumentVersion is a Memento -- a frozen snapshot of the document at a point
> in time. We save a snapshot every 100 ops. To reach any version, find the
> nearest snapshot and replay only the gap. O(100) replay instead of O(N)."

**Cross-reference:** Event sourcing (PersistenceStrategy, Pattern 1) appends ops.
VersionRepository (Pattern 4) stores mementos.

---

## 9. Mediator Pattern (Behavioral) -- CollaborationService as Mediator

### Why Mediator?

OperationService, PresenceService, and BroadcastService should not know about
each other. CollaborationService mediates their interactions -- when an operation
is applied, it tells BroadcastService to notify users and PresenceService to
update heartbeats. None of the sub-services hold references to each other.

### Ugly Anti-Pattern -- Services Call Each Other Directly

```java
// UGLY: Every service knows about every other service.
// Circular dependencies, spaghetti call graph.

public class UglyOperationService {
    private final BroadcastService broadcast;     // knows about broadcast
    private final PresenceService presence;       // knows about presence
    private final VersionService versionService;  // knows about versions

    public void apply(String docId, Operation op) {
        // Apply op...
        broadcast.broadcast(docId, op);          // direct call
        presence.heartbeat(docId, op.getUserId()); // direct call
        versionService.maybeSaveSnapshot(doc);     // direct call
        // OperationService is now coupled to 3 other services
        // Adding AuditService? Edit OperationService AGAIN.
    }
}

public class UglyPresenceService {
    private final BroadcastService broadcast;  // also knows about broadcast

    public void updateCursor(String docId, String userId, int pos) {
        // Update cursor...
        broadcast.broadcastCursor(docId, userId, pos);  // direct call
        // PresenceService coupled to BroadcastService
    }
}
```

### Clean Solution -- Mediator Coordinates

```java
// CLEAN: Sub-services do ONE thing. CollaborationService (Mediator)
// coordinates the workflow between them.

public class OperationService {
    // Does NOT know about BroadcastService or PresenceService
    private final OperationRepository opRepo;
    private final DocumentRepository docRepo;
    private final SyncStrategy syncStrategy;

    public Operation transformAndApply(String docId, Operation incoming) {
        List<Operation> concurrent = opRepo
            .findByDocumentIdAndVersionGreaterThan(docId,
                incoming.getClientVersion());
        Operation transformed = syncStrategy.transform(incoming, concurrent);
        // Apply to document, save op
        Document doc = docRepo.findById(docId).orElseThrow();
        doc.apply(transformed);
        docRepo.save(doc);
        opRepo.save(transformed);
        return transformed;
    }
}

public class PresenceService {
    // Does NOT know about BroadcastService
    private final Map<String, Map<String, CursorPosition>> cursors =
        new ConcurrentHashMap<>();

    public void updateCursor(String docId, String userId, int position) {
        cursors.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
               .put(userId, new CursorPosition(position, Instant.now()));
    }

    public Map<String, CursorPosition> getActiveCursors(String docId) {
        return cursors.getOrDefault(docId, Map.of());
    }
}

// MEDIATOR: coordinates all interactions
public class CollaborationService {
    private final OperationService operationService;   // does transform + apply
    private final BroadcastService broadcastService;   // does fan-out
    private final PresenceService presenceService;     // does cursor tracking
    private final PersistenceStrategy persistenceStrategy; // does save

    public void applyOperation(String docId, Operation incoming) {
        // 1. Transform and apply (OperationService)
        Operation transformed = operationService
            .transformAndApply(docId, incoming);

        // 2. Persist (PersistenceStrategy)
        Document doc = operationService.getDocument(docId);
        persistenceStrategy.save(doc, transformed);

        // 3. Broadcast (BroadcastService)
        broadcastService.broadcast(docId, transformed);

        // 4. Heartbeat (PresenceService)
        presenceService.heartbeat(docId, incoming.getUserId());

        // Sub-services never call each other. Mediator decides the order.
    }
}
```

### ASCII Diagram -- Mediator vs Direct Coupling

```
  UGLY: Direct coupling (spaghetti)     CLEAN: Mediator (star topology)

  OperationService <---> BroadcastService     OperationService
       |     \               ^                     |
       |      \             /                      v
       v       +---> PresenceService        CollaborationService (MEDIATOR)
  VersionService <--- BroadcastService       /         |         \
                                            v          v          v
                                    BroadcastService  PresenceService  PersistenceStrategy
```

### Mediator vs Facade

| Aspect | Facade | Mediator |
|--------|--------|----------|
| Purpose | Simplify interface for callers | Decouple sub-services from each other |
| Direction | Outside -> Inside | Inside <-> Inside |
| Who benefits | The controller/client | The sub-services |
| CollaborationService is... | A Facade to the controller | A Mediator between sub-services |

CollaborationService is BOTH. It simplifies the API for controllers (Facade)
AND decouples the sub-services from each other (Mediator).

### Interview One-Liner

> "CollaborationService is a Mediator -- OperationService, BroadcastService,
> and PresenceService never reference each other. The Mediator coordinates the
> transform-persist-broadcast-presence workflow. It is also a Facade, giving
> controllers a single `applyOperation()` entry point."

**Cross-reference:** Facade (Pattern 5) -- same class, different lens. Observer
(Pattern 6) -- BroadcastService is the Observer the Mediator delegates to.

---

## Pattern Interaction Map

How all 9 patterns work together in a single edit flow:

```
  +------------------------------------------------------------------+
  |                     FULL EDIT FLOW                                |
  +------------------------------------------------------------------+
  |                                                                    |
  |  1. Client creates Operation (COMMAND)                            |
  |     - type=INSERT, pos=5, content="H", clientVersion=10          |
  |                                                                    |
  |  2. WebSocket delivers to CollaborationService (FACADE/MEDIATOR)  |
  |                                                                    |
  |  3. Facade calls OperationService                                 |
  |     a. OperationRepo (REPOSITORY) fetches concurrent ops          |
  |     b. SyncStrategy (STRATEGY A) transforms the operation         |
  |     c. ConflictResolver (STRATEGY C) handles overlap if needed    |
  |     d. OperationService applies transformed op to Document        |
  |        (Document was built with BUILDER)                          |
  |                                                                    |
  |  4. Facade calls PersistenceStrategy (STRATEGY B)                 |
  |     a. EventSourced: append op to log                             |
  |     b. Snapshot: if version % 100 == 0, save MEMENTO              |
  |        (DocumentVersion stored in VersionRepo - REPOSITORY)       |
  |                                                                    |
  |  5. Facade calls BroadcastService (OBSERVER)                      |
  |     a. Fan out transformed op to all connected users              |
  |     b. Each WebSocket session is an Observer                       |
  |                                                                    |
  |  6. Facade calls PresenceService (via MEDIATOR coordination)      |
  |     a. Update user heartbeat                                      |
  |     b. BroadcastService notifies cursor positions                 |
  |                                                                    |
  +------------------------------------------------------------------+
```

### Numbered Call Chain -- Complete End-to-End

```
1.  Alice types "X" at position 5 in her local document
2.  Client creates Operation(INSERT, 5, "X", "alice", "doc-42", clientVer=10)   [COMMAND]
3.  Client applies op locally (optimistic update) and sends over WebSocket
4.  Server: CollaborationService.applyOperation("doc-42", op)                   [FACADE]
5.  CollaborationService calls OperationService.transformAndApply()              [MEDIATOR]
6.  OperationService calls OperationRepository.findByVersion("doc-42", >10)     [REPOSITORY]
7.  Returns [BobOp(INSERT, 3, "Y", ver=11)]
8.  OperationService calls SyncStrategy.transform(aliceOp, [bobOp])             [STRATEGY A]
9.  OTSyncStrategy: Bob inserted at 3 (before 5), shift Alice to pos=6
10. If ops overlap, ConflictResolver.resolve(bobOp, aliceOp)                    [STRATEGY C]
11. OperationService applies transformed op to Document                         [COMMAND.apply()]
12. Document was constructed via Document.Builder                               [BUILDER]
13. CollaborationService calls PersistenceStrategy.save(doc, transformedOp)     [STRATEGY B]
14. EventSourcedPersistenceStrategy appends op to log
15. Version 1200 -- divisible by 100? No. Skip snapshot.
16. CollaborationService calls BroadcastService.broadcast("doc-42", op)         [OBSERVER]
17. BroadcastService iterates observers: Bob, Carol, Dave
18. Each observer's onOperation() sends JSON over their WebSocket
19. CollaborationService calls PresenceService.heartbeat("doc-42", "alice")     [MEDIATOR]
20. PresenceService updates Alice's last-seen timestamp
```

---

## Interview Quick-Reference Card

| Question | Pattern | One-Liner Answer |
|----------|---------|-------------------|
| "How do you handle concurrent edits?" | Strategy (OT/CRDT) | "SyncStrategy transforms each incoming op against concurrent ops. OT shifts positions; CRDT uses unique character IDs." |
| "How do you persist the document?" | Strategy (Persistence) | "PersistenceStrategy: event-sourced appends every op to a log; snapshot saves full content every Nth edit." |
| "How do you notify other users?" | Observer | "BroadcastService maintains a per-document observer set. Operations fan out to all WebSocket sessions." |
| "How do you undo?" | Command | "Operation has an inverse() method. INSERT becomes DELETE and vice versa. Apply the inverse." |
| "How do you rollback to a previous version?" | Memento | "DocumentVersion snapshots every 100 ops. Find nearest snapshot, replay the gap." |
| "How do you decouple your services?" | Mediator | "CollaborationService mediates between OperationService, BroadcastService, and PresenceService. They never reference each other." |
| "Walk me through the OT transform rules." | OT (Section 1b) | "Four cases: INS/INS shifts right, INS/DEL shifts right, DEL/INS shifts left, DEL/DEL same-position becomes no-op." |
| "OT vs CRDT?" | Strategy | "OT: centralized server, total order, simpler. CRDT: decentralized, works offline, eventual convergence. Google Docs uses OT; Figma uses CRDT." |
| "How do you build a Document?" | Builder | "Document.Builder with named setters, validation, and immutable result." |
| "Where is the wiring?" | Factory | "AppConfig -- one-line swap from OT to CRDT, from snapshot to event-sourced." |
