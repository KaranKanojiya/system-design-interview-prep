# Technologies -- Real-time Collaboration Tool (Google Docs)

> Production technology stack for a real-time collaborative editing platform.
> For each tech: why it fits, key operations, data model, complexity analysis,
> and how our Java implementation maps to the production version.
>
> **Domain-specific:** Real-time collaboration has unique tech requirements --
> OT/CRDT libraries for conflict resolution, WebSockets for bidirectional
> communication, operation logs for event sourcing, and presence tracking
> for cursor awareness. This doc covers all of them.

---

## Technology Map

```
  +-------------------+     +-------------------+     +------------------------------+
  |   Client (Browser)|---->|   WebSocket       |---->|   CollaborationService       |
  |   Local Doc Copy  |     |   Gateway         |     |   (Facade / Mediator)        |
  +-------------------+     +-------------------+     +------------------------------+
        |                         |                       |          |          |
        |  Cursor updates         |           +-----------+----+     |     +----+------+
        |  Operation ACKs         |           |                |     |     |           |
        v                         v           v                v     v     v           v
  +----------+              +----------+ +----------+   +--------+ +------+ +---------+
  | IndexedDB|              | Redis    | | Kafka    |   |Postgre | |Redis | |S3       |
  | (offline |              | Presence | | Operation|   |SQL     | |Op    | |Version  |
  |  queue)  |              | Cursors  | | Log      |   |Document| |Cache | |Snapshots|
  +----------+              | TTL 30s  | | Ordered  |   |Store   | |Recent| |Periodic |
                            +----------+ +----------+   +--------+ +------+ +---------+
                                              |
                                              v
                                        +----------+
                                        | PostgreSQL|
                                        | Operation |
                                        | Archive   |
                                        +----------+
```

---

## 1. OT Libraries -- Operational Transformation

### Google's OT (Jupiter Protocol)

The original OT algorithm used by Google Wave and later adapted for Google Docs.

| Property | Detail |
|----------|--------|
| Name | Jupiter protocol (Google internal name) |
| Model | Client-server with server as authority |
| Transform | Position-based: INSERT shifts right, DELETE shifts left |
| Ordering | Server assigns total order via monotonic version numbers |
| Convergence | Guaranteed if transform function satisfies TP1 (transformation property 1) |
| Scale | Per-document server -- each document has one authoritative process |
| Production users | Google Docs, Google Slides, Google Sheets |

**TP1 (Transformation Property 1):**

```
For any two concurrent operations a and b:
  apply(apply(doc, a), transform(b, a)) == apply(apply(doc, b), transform(a, b))

In plain English: no matter which operation you apply first, after transforming
and applying the other, you get the same result.
```

### ShareDB

Open-source OT framework for Node.js. Production-grade, widely used.

| Property | Detail |
|----------|--------|
| Language | JavaScript / Node.js |
| Model | Client-server with server as authority |
| Backend | Pluggable: MongoDB, PostgreSQL, Redis |
| Types | JSON OT (json0), rich text OT (rich-text), plain text OT (text) |
| WebSocket | Built-in WebSocket support via `sharedb-client` |
| Used by | Quill (rich text editor), Derby.js framework |
| GitHub | github.com/share/sharedb |

**Key API:**

```javascript
// Server
var ShareDB = require('sharedb');
var backend = new ShareDB();
var connection = backend.connect();
var doc = connection.get('documents', 'doc-42');

// Client
var socket = new WebSocket('ws://server/docs');
var connection = new sharedb.Connection(socket);
var doc = connection.get('documents', 'doc-42');

doc.subscribe(function(err) {
    // doc.data is the current document
    doc.submitOp([{p: ['content', 5], si: 'X'}]);  // insert "X" at position 5
});
```

### Yjs (OT-Compatible Mode)

Yjs is primarily a CRDT library but can operate in an OT-compatible mode
with a central server for ordering.

| Property | Detail |
|----------|--------|
| Language | JavaScript / TypeScript |
| Model | CRDT-based, but supports server-mediated ordering |
| Transport | y-websocket, y-webrtc (peer-to-peer) |
| Editor bindings | ProseMirror, Quill, Monaco, CodeMirror, TipTap |
| Offline | Built-in -- CRDT enables offline editing |
| Size | ~30KB minified |
| GitHub | github.com/yjs/yjs |

---

## 2. CRDT Libraries -- Conflict-free Replicated Data Types

### Automerge

The most research-backed CRDT library. Created by Martin Kleppmann (Cambridge).

| Property | Detail |
|----------|--------|
| Language | Rust core + JavaScript/TypeScript bindings |
| CRDT type | Document CRDT -- JSON-like data structure |
| Text CRDT | RGA-based (Replicated Growable Array) |
| Merge | Automatic, deterministic, commutative |
| History | Full change history built into the data structure |
| Size | ~500KB (includes Rust WASM core) |
| Used by | Ink & Switch research projects, various open-source tools |
| GitHub | github.com/automerge/automerge |

**Key API:**

```javascript
import * as Automerge from 'automerge';

// Create a document
let doc = Automerge.init();
doc = Automerge.change(doc, 'Add title', doc => {
    doc.title = "Meeting Notes";
    doc.content = new Automerge.Text();
    doc.content.insertAt(0, 'H', 'e', 'l', 'l', 'o');
});

// Concurrent edit on another peer
let doc2 = Automerge.clone(doc);
doc2 = Automerge.change(doc2, 'Bob edits', doc => {
    doc.content.insertAt(5, ' ', 'W', 'o', 'r', 'l', 'd');
});

// Merge -- guaranteed to converge
let merged = Automerge.merge(doc, doc2);
// merged.content.toString() === "Hello World"
```

### Yjs (CRDT Mode)

Yjs is the most popular CRDT library for production collaborative editing.

| Property | Detail |
|----------|--------|
| Language | JavaScript / TypeScript |
| CRDT type | Y.Doc containing Y.Text, Y.Map, Y.Array, Y.XmlFragment |
| Text CRDT | YATA (Yet Another Transformation Approach) |
| Transport | y-websocket, y-webrtc, y-indexeddb (persistence) |
| Editor support | Quill, ProseMirror, Monaco, CodeMirror, TipTap, Slate |
| Performance | ~30KB, optimized for text editing |
| Used by | Liveblocks, Hocuspocus, many SaaS products |
| GitHub | github.com/yjs/yjs |

**Key API:**

```javascript
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';

const ydoc = new Y.Doc();
const ytext = ydoc.getText('content');

// Connect to server for sync
const wsProvider = new WebsocketProvider(
    'ws://server', 'doc-42', ydoc);

// Insert text -- automatically synced to all peers
ytext.insert(0, 'Hello');

// Observe changes from other peers
ytext.observe(event => {
    console.log('Text changed:', ytext.toString());
});
```

### Diamond Types

High-performance CRDT library written in Rust by Joseph Gentle (ex-Google Wave).

| Property | Detail |
|----------|--------|
| Language | Rust + WASM bindings |
| CRDT type | Text CRDT (fugue-based) |
| Performance | 10-100x faster than Automerge for text operations |
| Memory | Extremely compact -- no tombstone overhead |
| Author | Joseph Gentle (worked on Google Wave OT) |
| Status | Research-grade, not yet production-stable |
| GitHub | github.com/josephg/diamond-types |

---

## 3. Text CRDT Data Structures

### RGA (Replicated Growable Array)

The foundational text CRDT. Every character has a unique ID and a reference
to the character it was inserted after.

```
  Document: "HELLO"

  RGA linked list:
  ROOT -> (A,1,"H") -> (A,2,"E") -> (A,3,"L") -> (A,4,"L") -> (A,5,"O")

  Insert "X" between "E" and "L":
  - New node: (B,1,"X") with reference to (A,2,"E")
  - ROOT -> (A,1,"H") -> (A,2,"E") -> (B,1,"X") -> (A,3,"L") -> ...

  Delete "L" at position 3:
  - Mark (A,3,"L") as tombstone (do not remove, just hide)
  - ROOT -> (A,1,"H") -> (A,2,"E") -> (B,1,"X") -> [A,3,TOMBSTONE] -> (A,4,"L") -> (A,5,"O")
  - Visible text: "HEXLO"
```

| Property | RGA |
|----------|-----|
| Insert | O(1) -- link new node after reference |
| Delete | O(1) -- mark as tombstone |
| Lookup by position | O(N) -- traverse from root, skip tombstones |
| Merge | Compare IDs at same position, deterministic ordering |
| Tombstone cleanup | Garbage collection when all peers have seen the delete |

### LSEQ (Linear Sequence)

Allocates positions from a dense space. Each character gets a position
between its neighbors. No tombstones needed.

```
  Position allocation strategy:
  - Between positions 0.0 and 1.0, allocate 0.5
  - Between 0.5 and 1.0, allocate 0.75
  - Between 0.5 and 0.75, allocate 0.625
  - ...

  Document: "HI"
  H at position 0.25
  I at position 0.75

  Insert "E" between H and I:
  E at position 0.50  (midpoint of 0.25 and 0.75)

  Result: H(0.25) E(0.50) I(0.75)  -->  "HEI"
```

| Property | LSEQ |
|----------|------|
| Insert | O(log N) -- binary search for position |
| Delete | O(log N) -- remove from sorted sequence |
| No tombstones | Deleted chars are truly removed |
| Interleaving | Possible if allocation space exhausted -- rare but problematic |
| Memory | Lower than RGA (no tombstones) |

### Logoot

Similar to LSEQ but uses integer-vector positions for deterministic allocation.

```
  Position: [siteId, clock, offset]
  
  "AB":
  A at position [1, 0, 0]
  B at position [1, 0, 1]
  
  Insert "X" between A and B:
  X at position [2, 0, 0]  (site 2's first character)
  
  Ordering: [1,0,0] < [2,0,0] < [1,0,1]  (lexicographic)
  Result: A X B --> "AXB"
```

### Text CRDT Comparison

| CRDT | Tombstones | Insert | Delete | Memory | Used By |
|------|-----------|--------|--------|--------|---------|
| RGA | Yes | O(1) | O(1) | High (tombstone accumulation) | Automerge |
| YATA | Yes (optimized) | O(1) | O(1) | Medium (block compaction) | Yjs |
| LSEQ | No | O(log N) | O(log N) | Low | Academic |
| Logoot | No | O(log N) | O(log N) | Low | Academic |
| Fugue | No (novel approach) | O(1) amortized | O(1) amortized | Low | Diamond Types |

---

## 4. WebSocket -- Bidirectional Real-time Communication

### Why WebSocket?

HTTP is request-response: client asks, server answers. For real-time
collaboration, the server must push edits to clients the moment they happen.
WebSocket provides full-duplex, persistent connections.

```
  HTTP (request-response):
  Client: "Any new edits?"  -->  Server: "No."
  Client: "Any new edits?"  -->  Server: "No."
  Client: "Any new edits?"  -->  Server: "Yes, here is one."
  Client: "Any new edits?"  -->  Server: "No."
  ... (polling wastes bandwidth and adds latency)

  WebSocket (full-duplex):
  Client <---------- persistent connection ----------> Server
  Server pushes edit to Client the INSTANT it happens
  Client pushes edit to Server the INSTANT user types
  No polling. No wasted requests. Sub-50ms latency.
```

### Java WebSocket API (JSR 356)

```java
@ServerEndpoint("/ws/documents/{documentId}")
public class DocumentWebSocket {

    @OnOpen
    public void onOpen(Session session,
                       @PathParam("documentId") String documentId) {
        // Register this session as an observer for the document
        broadcastService.subscribe(documentId,
            new WebSocketObserver(session));
        presenceService.userJoined(documentId,
            getUserId(session));
    }

    @OnMessage
    public void onMessage(String message, Session session,
                          @PathParam("documentId") String documentId) {
        // Deserialize operation from JSON
        Operation op = JsonUtil.deserialize(message, Operation.class);

        // Delegate to the Facade
        collaborationService.applyOperation(documentId, op);
    }

    @OnClose
    public void onClose(Session session,
                        @PathParam("documentId") String documentId) {
        broadcastService.unsubscribe(documentId,
            getObserver(session));
        presenceService.userLeft(documentId,
            getUserId(session));
    }

    @OnError
    public void onError(Session session, Throwable error) {
        logger.error("WebSocket error for session "
            + session.getId(), error);
    }
}
```

### WebSocket Message Protocol

```
  Client -> Server (operations):
  {
    "type": "operation",
    "documentId": "doc-42",
    "operation": {
      "type": "INSERT",
      "position": 5,
      "content": "Hello",
      "clientVersion": 10
    }
  }

  Server -> Client (transformed operation):
  {
    "type": "operation",
    "documentId": "doc-42",
    "operation": {
      "type": "INSERT",
      "position": 7,
      "content": "Hello",
      "serverVersion": 15
    }
  }

  Client -> Server (cursor update):
  {
    "type": "cursor",
    "documentId": "doc-42",
    "userId": "alice",
    "position": 42
  }

  Server -> Client (presence broadcast):
  {
    "type": "presence",
    "documentId": "doc-42",
    "users": [
      {"userId": "alice", "cursor": 42, "color": "#FF6B6B"},
      {"userId": "bob", "cursor": 17, "color": "#4ECDC4"}
    ]
  }
```

### WebSocket vs Alternatives

| Technology | Direction | Latency | Overhead | Use Case |
|-----------|-----------|---------|----------|----------|
| WebSocket | Full-duplex | ~1ms (after connect) | 2-byte frame header | Real-time collab (our choice) |
| Server-Sent Events (SSE) | Server -> Client only | ~1ms | HTTP headers per event | Read-only streams (news feeds) |
| HTTP Long Polling | Simulated push | ~100ms | Full HTTP headers per poll | Legacy browsers |
| HTTP/2 Push | Server -> Client | ~5ms | HTTP/2 frames | Static assets (not real-time) |
| WebRTC | Peer-to-peer | <1ms (local network) | ICE/STUN overhead | Video calls, P2P CRDT |

### Socket.IO (Node.js)

```javascript
// Server
const io = require('socket.io')(server);

io.on('connection', (socket) => {
    socket.on('join-document', (docId) => {
        socket.join(docId);  // Join room for this document
    });

    socket.on('operation', (data) => {
        // Transform and broadcast to room (except sender)
        const transformed = otEngine.transform(data.op, concurrentOps);
        socket.to(data.docId).emit('operation', transformed);
    });

    socket.on('cursor', (data) => {
        socket.to(data.docId).emit('cursor', data);
    });
});
```

### SignalR (.NET)

```csharp
public class DocumentHub : Hub
{
    public async Task JoinDocument(string docId)
    {
        await Groups.AddToGroupAsync(Context.ConnectionId, docId);
    }

    public async Task SendOperation(string docId, Operation op)
    {
        var transformed = _otEngine.Transform(op, concurrentOps);
        await Clients.OthersInGroup(docId)
            .SendAsync("ReceiveOperation", transformed);
    }
}
```

---

## 5. Databases -- Polyglot Persistence

### PostgreSQL -- Document Store (Source of Truth)

```
  Table: documents
  +-------------+----------+--------------------------------------------------+
  | Column      | Type     | Purpose                                          |
  +-------------+----------+--------------------------------------------------+
  | id          | VARCHAR  | Primary key (UUID)                               |
  | title       | VARCHAR  | Document title                                   |
  | owner_id    | VARCHAR  | Foreign key to users table                       |
  | content     | TEXT     | Current document content (denormalized)          |
  | version     | INTEGER  | Current version number (monotonic)               |
  | created_at  | TIMESTAMP| Creation timestamp                               |
  | updated_at  | TIMESTAMP| Last modification timestamp                      |
  +-------------+----------+--------------------------------------------------+

  Table: operations
  +-------------+----------+--------------------------------------------------+
  | Column      | Type     | Purpose                                          |
  +-------------+----------+--------------------------------------------------+
  | id          | VARCHAR  | Primary key (UUID)                               |
  | document_id | VARCHAR  | Foreign key to documents                         |
  | user_id     | VARCHAR  | Who performed the operation                      |
  | type        | VARCHAR  | INSERT, DELETE, RETAIN                           |
  | position    | INTEGER  | Position in document                             |
  | content     | TEXT     | Inserted/deleted text                            |
  | version     | INTEGER  | Server-assigned version (unique per document)    |
  | created_at  | TIMESTAMP| When the operation was applied                   |
  +-------------+----------+--------------------------------------------------+
  Index: (document_id, version) -- for fetching concurrent ops

  Table: document_versions (snapshots)
  +-------------+----------+--------------------------------------------------+
  | Column      | Type     | Purpose                                          |
  +-------------+----------+--------------------------------------------------+
  | id          | VARCHAR  | Primary key (UUID)                               |
  | document_id | VARCHAR  | Foreign key to documents                         |
  | version     | INTEGER  | Snapshot version number                          |
  | content     | TEXT     | Full document content at this version             |
  | created_by  | VARCHAR  | User who triggered the snapshot                  |
  | created_at  | TIMESTAMP| When the snapshot was taken                      |
  +-------------+----------+--------------------------------------------------+
  Index: (document_id, version) -- for nearest-snapshot lookup
```

**Key Queries:**

```sql
-- Fetch concurrent operations for OT transform
SELECT * FROM operations
WHERE document_id = 'doc-42' AND version > 10
ORDER BY version ASC;

-- Find nearest snapshot before target version
SELECT * FROM document_versions
WHERE document_id = 'doc-42' AND version <= 250
ORDER BY version DESC
LIMIT 1;

-- Insert new operation (with version gap detection)
INSERT INTO operations (id, document_id, user_id, type, position, content, version)
SELECT $1, $2, $3, $4, $5, $6, COALESCE(MAX(version), 0) + 1
FROM operations WHERE document_id = $2;
```

### Redis -- Presence and Cursor Cache

```
  Key pattern: presence:{documentId}
  Type: Hash
  TTL: 30 seconds (refreshed by heartbeat)

  HSET presence:doc-42 alice '{"cursor":42,"color":"#FF6B6B","lastSeen":1700000000}'
  HSET presence:doc-42 bob   '{"cursor":17,"color":"#4ECDC4","lastSeen":1700000001}'

  HGETALL presence:doc-42
  --> {
        "alice": '{"cursor":42,"color":"#FF6B6B","lastSeen":1700000000}',
        "bob":   '{"cursor":17,"color":"#4ECDC4","lastSeen":1700000001}'
      }

  EXPIRE presence:doc-42 30   -- auto-cleanup if no heartbeat

  Key pattern: ops-cache:{documentId}
  Type: Sorted Set (score = version number)
  TTL: 5 minutes

  ZADD ops-cache:doc-42 11 '{"type":"INSERT","pos":3,"content":"Y"}'
  ZADD ops-cache:doc-42 12 '{"type":"DELETE","pos":7,"len":1}'

  -- Fetch ops since version 10 (for OT transform)
  ZRANGEBYSCORE ops-cache:doc-42 11 +inf
```

**Redis Data Model Summary:**

| Key Pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `presence:{docId}` | Hash | 30s | Cursor positions, active users |
| `ops-cache:{docId}` | Sorted Set | 5min | Recent operations for fast OT lookups |
| `doc-cache:{docId}` | String (JSON) | 60s | Latest document content |
| `typing:{docId}` | Set | 5s | Users currently typing |
| `session:{sessionId}` | Hash | 24h | WebSocket session metadata |

### Kafka -- Operation Log (Event Sourcing)

```
  Topic: document-operations
  Partitioned by: documentId (all ops for one doc go to one partition)
  Retention: 30 days (then archived to S3)

  Key: "doc-42"
  Value: {
    "operationId": "op-uuid",
    "documentId": "doc-42",
    "userId": "alice",
    "type": "INSERT",
    "position": 5,
    "content": "Hello",
    "serverVersion": 15,
    "timestamp": 1700000000
  }

  Why Kafka:
  - Ordered within partition (one partition per document = total order)
  - Durable (replicated across brokers)
  - Replayable (consumers can seek to any offset)
  - High throughput (millions of ops/sec across all documents)
  - Consumer groups for different downstream: archival, analytics, search indexing
```

**Kafka Consumer Groups:**

```
  Topic: document-operations
       |
       +---> Consumer Group: "archival"
       |     PostgreSQL archival (batch insert every 5 seconds)
       |
       +---> Consumer Group: "search-index"
       |     Elasticsearch document indexing
       |
       +---> Consumer Group: "analytics"
       |     Edit frequency, user activity metrics
       |
       +---> Consumer Group: "notification"
             Notify mentioned users, comment replies
```

---

## 6. Our Java Implementation vs Production

### What We Implement (Simplified)

| Component | Our Implementation | Production Equivalent |
|-----------|-------------------|----------------------|
| SyncStrategy | OTSyncStrategy with 4 transform cases | Google Jupiter protocol / ShareDB |
| PersistenceStrategy | InMemoryEventLog + snapshot every 100 ops | Kafka + PostgreSQL + S3 |
| ConflictResolver | OTConflictResolver with position transforms | Same algorithm, but hardened |
| WebSocket | Simulated (method calls between services) | Java WebSocket API (JSR 356) |
| Document store | InMemoryDocumentRepository (HashMap) | PostgreSQL with JSONB |
| Operation store | InMemoryOperationRepository (ArrayList) | Kafka topic + PostgreSQL archive |
| Version store | InMemoryVersionRepository (HashMap) | PostgreSQL + S3 for large snapshots |
| Presence | InMemoryPresenceService (ConcurrentHashMap) | Redis Hash with 30s TTL |
| Broadcast | InMemoryBroadcastService (observer list) | WebSocket + Kafka fan-out |

### What We Skip (Interview Awareness)

| Component | Why Skipped | What to Say in Interview |
|-----------|------------|------------------------|
| Real WebSocket | Adds network complexity without teaching OT | "In production, JSR 356 or Socket.IO over wss://" |
| Kafka | Requires infrastructure setup | "Kafka topic per document for ordered, durable operation log" |
| Redis presence | Requires Redis instance | "Redis Hash with 30s TTL for cursor positions" |
| Authentication | Orthogonal to collaboration | "JWT tokens validated at WebSocket handshake" |
| Rate limiting | Covered in project 02 | "Token bucket per user per document" |
| CRDT implementation | OT is sufficient for demo | "We implemented OT; CRDT would use Yjs or Automerge" |

---

## 7. OT vs CRDT -- Detailed Comparison

### Algorithm Comparison

| Dimension | OT (Operational Transformation) | CRDT (Conflict-free Replicated Data Type) |
|-----------|--------------------------------|------------------------------------------|
| Core idea | Transform positions of concurrent ops | Assign unique IDs to every character |
| Server role | Central authority (transforms + orders) | Relay only (or no server at all) |
| Ordering | Total order (server-assigned version) | Partial order (causal via vector clocks) |
| Convergence | Guaranteed if transform satisfies TP1 | Guaranteed by mathematical properties |
| Offline | Queue ops, reconcile on reconnect | Full offline support (local CRDT copy) |
| Undo | Simple (inverse operation) | Complex (commutative undo) |

### Performance Comparison

```
  Operation: single character insert in a 10,000-character document
  with 5 concurrent users

  OT:
  - Client: create op O(1)
  - Network: send to server O(1)
  - Server: fetch concurrent ops O(K) where K = ops since client's version
  - Server: transform against each O(K) -- typically K < 10
  - Server: broadcast O(N) where N = connected users
  - Total server work per op: O(K) -- very fast for small K
  - Memory: O(docSize) for document + O(opsLog) for history

  CRDT:
  - Client: create char node with unique ID O(1)
  - Network: broadcast to peers O(N)
  - Each peer: merge O(1) per received op
  - No server transform needed
  - Total work per op: O(1) at each peer
  - Memory: O(totalCharsEverInserted) -- tombstones accumulate!
```

### Performance Table

| Metric | OT | CRDT |
|--------|----|----- |
| Insert latency (local) | ~0ms (optimistic) | ~0ms (local-first) |
| Insert latency (confirmed) | ~50-100ms (round trip to server) | ~0ms (local, sync async) |
| Transform cost per op | O(K) where K = concurrent ops | O(1) merge |
| Memory per character | ~0 bytes (position is implicit) | 8-20 bytes (unique ID + metadata) |
| Memory for 10K-char doc | ~10KB (content only) | ~100-200KB (content + IDs + tombstones) |
| Undo cost | O(1) -- apply inverse op | O(N) -- may need to recompute |
| Offline reconciliation | O(K*M) -- K queued ops, M server ops | O(M) -- merge M remote ops |

### Complexity Comparison

| Aspect | OT Complexity | CRDT Complexity |
|--------|--------------|-----------------|
| Core algorithm | Moderate (4 transform cases) | High (unique ID generation, tree/list structure) |
| Server implementation | Moderate (transform + order) | Low (relay only) |
| Client implementation | Low (send ops, apply server response) | High (full CRDT state machine) |
| Testing | Moderate (verify convergence for all pairs) | High (verify commutativity + associativity) |
| Debugging | Easier (total order, server logs) | Harder (no total order, distributed state) |
| Library maturity | High (Google uses it for 15+ years) | Growing (Yjs, Automerge actively developed) |

### When to Choose

```
  Choose OT when:                          Choose CRDT when:
  +----------------------------------+     +----------------------------------+
  | - Text-heavy documents           |     | - Offline editing is critical    |
  | - Always-online assumption       |     | - Peer-to-peer desired           |
  | - Simple undo/redo needed        |     | - Spatial data (design tools)    |
  | - Team knows OT (Google model)   |     | - No server dependency           |
  | - Memory efficiency matters      |     | - Partition tolerance > all       |
  | - Total ordering required        |     | - Team knows CRDTs               |
  +----------------------------------+     +----------------------------------+
  
  Examples: Google Docs, Notion,           Examples: Figma, Linear,
            Microsoft Word Online                    Apple Notes, Obsidian
```

---

## 8. Infrastructure Architecture (Production)

### Numbered Call Chain -- Production Edit Flow

```
1.  Alice types "X" in her browser at position 5
2.  Client applies op locally (optimistic update -- instant feedback)
3.  Client sends op over WSS to WebSocket Gateway (nginx/HAProxy)
4.  Gateway routes to the correct Collaboration Server (by document shard)
5.  Collaboration Server fetches concurrent ops from Redis ops-cache
6.  Cache miss? Fall back to PostgreSQL operations table
7.  OT engine transforms Alice's op against concurrent ops
8.  Collaboration Server writes transformed op to Kafka (document-operations topic)
9.  Kafka consumer writes op to PostgreSQL (archival)
10. Collaboration Server updates Redis doc-cache with new content
11. Collaboration Server broadcasts transformed op via WebSocket to all connected users
12. Every 100 ops: snapshot service saves full document to PostgreSQL + S3
13. Presence: Alice's cursor position updated in Redis (HSET, TTL 30s)
14. Presence broadcast: all users receive Alice's new cursor position
```

### Scaling Architecture

```
  +-------------------+
  |   Load Balancer   |
  |  (sticky by docId)|
  +-------------------+
     |       |       |
     v       v       v
  +-----+ +-----+ +-----+
  |Collab| |Collab| |Collab|    Collaboration Servers
  |Srv 1 | |Srv 2 | |Srv 3 |    (one server per document shard)
  +-----+ +-----+ +-----+
     |       |       |
     v       v       v
  +-----------------------------+
  |          Kafka              |   Operation Log
  |  (partitioned by docId)    |   (total order per doc)
  +-----------------------------+
     |       |       |
     v       v       v
  +-----+ +-----+ +-----+
  |PG   | |Redis | |S3   |     Storage Layer
  |Docs  | |Cache | |Snaps|
  +-----+ +-----+ +-----+
```

### Production Sizing Estimates

| Metric | Estimate | Basis |
|--------|---------|-------|
| Avg ops per user per minute | ~30 (typing speed) | 300 chars/min, 1 op per 10 chars (batching) |
| Concurrent users per doc | 5-50 | Google Docs typical |
| Ops per doc per minute | 150-1500 | 5-50 users * 30 ops |
| WebSocket connections per server | 10,000-50,000 | Java NIO / epoll |
| Documents per server shard | 1,000-5,000 | Depends on activity level |
| Operation log size per doc/day | ~1 MB | 1500 ops/min * 60 min * 8 hrs * 100 bytes/op |
| Snapshot size per doc | 10 KB - 10 MB | Depends on document content |
| Redis memory per active doc | ~5 KB | Presence hash + ops cache |

---

## Interview Quick-Reference

| Question | Technology | One-Liner |
|----------|-----------|-----------|
| "How do clients communicate?" | WebSocket | "Full-duplex WebSocket (JSR 356 in Java). Server pushes transformed ops instantly. No polling." |
| "How do you persist operations?" | Kafka + PostgreSQL | "Kafka topic partitioned by docId for ordered, durable event log. PostgreSQL for archival and queries." |
| "How do you handle presence?" | Redis | "Redis Hash with 30s TTL per document. HSET for cursor updates, EXPIRE for auto-cleanup." |
| "What OT library would you use?" | ShareDB / Jupiter | "ShareDB for Node.js, or implement Jupiter protocol in Java. Our demo implements simplified OT." |
| "What CRDT library?" | Yjs / Automerge | "Yjs for performance and editor bindings. Automerge for research-grade correctness." |
| "OT vs CRDT?" | See section 7 | "OT: centralized, simple undo, low memory. CRDT: offline, decentralized, higher memory. Google Docs uses OT; Figma uses CRDT." |
| "How do you scale?" | Shard by docId | "Sticky load balancing by documentId. Each document shard handled by one server. Kafka partition per doc." |
| "What text CRDT data structure?" | RGA / YATA / Fugue | "RGA is foundational. Yjs uses YATA (optimized RGA). Diamond Types uses Fugue (no tombstones)." |
