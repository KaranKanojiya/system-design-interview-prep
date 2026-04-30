# Interview Walkthrough -- Real-Time Collaboration Tool (Google Docs)

> **Total time: ~35 minutes. The OT/CRDT Deep Dive is 60% of this interview.**
> This problem tests operational transformation (OT), conflict-free replicated data types (CRDTs), WebSocket architecture, presence broadcasting, version history reconstruction, and scaling real-time state synchronization across millions of concurrent documents. The hard part is explaining how concurrent edits from multiple users transform into a single converged document state -- with concrete transform rules and examples.

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "How many concurrent documents are being edited at peak? 1K or 1M? This determines whether we can hold document state in-memory per container or need a distributed state layer."
- "What kind of content? Plain text only, or rich text (bold, italic, headings, tables)? Rich text makes OT significantly more complex -- operations on attributed spans instead of raw characters."
- "Do we need offline editing? If yes, CRDTs are strongly favored over OT because they merge without a central server. If online-only, OT is simpler and battle-tested."
- "What's the maximum collaborator count per document? 5 users vs 500 users changes the broadcast fan-out strategy."
- "Do we need version history? 'Show me what this doc looked like yesterday' requires an operation log + snapshot strategy."
- "What's the consistency requirement? Is it acceptable for two users to briefly see different text, or must they always converge within a bounded time?"

### Clarified Scope

```
In scope:   Real-time collaborative text editing (multiple cursors, live updates),
            operational transformation (conflict resolution), presence system
            (cursor positions + colors), version history (reconstruct any past
            version), WebSocket communication, document CRUD + sharing/permissions
Out of scope: Rich text formatting (mention only), offline editing (mention CRDT
              advantage), comments/suggestions, image/table embedding, spell check,
              access control lists (basic permissions only)
```

### What This Signals

You understand this is a **conflict resolution + real-time sync problem** where the hard part is ensuring all users converge to the same document state despite concurrent edits arriving in different orders. You're probing for scale (in-memory vs distributed), content complexity (plain vs rich text), and connectivity model (online-only vs offline) because these fundamentally change the algorithm choice.

**Common follow-up:** "Why does offline matter so much for the algorithm choice?"

**Answer:** "OT requires a central server to order operations -- when User A and User B edit concurrently, the server decides which op is applied first and transforms the other. If User B is offline, their operations queue locally but can't be transformed until the server sees them. On reconnect, the server must transform potentially hundreds of queued operations against everything that happened while the user was offline -- this is computationally expensive and error-prone. CRDTs don't have this problem: each operation is self-contained with enough metadata (character IDs, logical timestamps) to merge deterministically in any order. Figma chose CRDTs specifically for this -- designers on spotty WiFi can keep editing and merge cleanly on reconnect."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll build this around a **WebSocket-based collaboration service** that holds document state in-memory. Clients connect via WebSocket through an ALB with sticky sessions -- all users editing the same document are routed to the same ECS container. This container holds the document text, version counter, and all active WebSocket connections in memory. When a user types, the client sends an **INSERT or DELETE operation** with a **baseVersion**. The server runs **OT transform** against any operations that arrived between the client's baseVersion and the current serverVersion, applies the transformed operation, and **broadcasts** it to all other connected users via WebSocket -- all in under 1 millisecond because everything is in-memory. **Presence** (cursor positions and colors) flows through Redis Pub/Sub for cross-container scenarios, but is mostly in-process for sticky-sessioned documents. The **operation log** is an append-only table in DynamoDB (PK=docId, SK=version), and **periodic snapshots** go to S3 every 100 operations for fast version history reconstruction. The system is **CP for document operations** (convergence is non-negotiable) and **AP for presence** (stale cursors are acceptable)."

### Draw This Diagram

```
              +-----------------------------------+
              |       Clients (Browser Editors)   |
              | Alice (cursor red), Bob (green)   |
              +----------------+------------------+
                               |
              1. WebSocket connection (persistent, bidirectional)
              2. Operations: INSERT(pos, text), DELETE(pos, length)
              3. Presence: cursor position + color every 500ms
                               |
                               v
              +-----------------------------------+
              |     ALB (Sticky Sessions)         |
              |  Cookie hash: documentId -> ECS   |
              |  All users on doc_001 -> Task A   |
              +--------+--------------+-----------+
                       |              |
            doc_001    |              |    doc_002
            users      |              |    users
                       v              v
              +----------------+  +----------------+
              |  ECS Task A    |  |  ECS Task B    |
              |  (IN-MEMORY)   |  |  (IN-MEMORY)   |
              |                |  |                 |
              | doc_001 state: |  | doc_002 state:  |
              |  text: "Hello" |  |  text: "Draft"  |
              |  version: 42   |  |  version: 17    |
              |  connections:  |  |  connections:    |
              |   [Alice, Bob] |  |   [Charlie]     |
              |  cursors:      |  |  cursors:        |
              |   Alice@pos5   |  |   Charlie@pos3   |
              |   Bob@pos11    |  |                  |
              +-------+--------+  +--------+--------+
                      |                     |
         4. Persist operations (batch every 100ms)
         5. Read/write document state on connect/failover
                      |                     |
           +----------v---------------------v----------+
           |                                           |
    +------v-------+  +--------v--------+  +-----------v--+
    | DynamoDB     |  | ElastiCache     |  | S3           |
    | (ops log)    |  | Redis           |  | (snapshots)  |
    |              |  |                 |  |              |
    | PK=doc_001   |  | doc state cache |  | doc_001/     |
    | SK=v#1..v#42 |  | presence pub/sub|  |  v#100.json  |
    | append-only  |  | session store   |  |  v#200.json  |
    +--------------+  +-----------------+  +--------------+
           |
    +------v-------+
    | RDS Aurora   |
    | PostgreSQL   |
    |              |
    | users, docs, |
    | permissions, |
    | sharing      |
    +--------------+

  OPERATION FLOW (when Alice types "X" at position 5):

    6. Client A sends via WebSocket:
       { op: INSERT(5, "X"), baseVersion: 42 }

    7. ECS Task A (in-memory, < 1ms):
       a. Compare baseVersion (42) to serverVersion (42)
       b. No transform needed (versions match)
       c. Apply: text = text[:5] + "X" + text[5:]
       d. Increment version: 42 -> 43
       e. Queue for DynamoDB batch write

    8. Broadcast via WebSocket (in-process, < 1ms):
       To Alice: { type: ACK, version: 43 }
       To Bob:   { type: REMOTE_OP, version: 43, op: INSERT(5,"X"), userId: "Alice" }

    9. Bob's client applies remote operation:
       Insert "X" at position 5 in local document
       Adjust Bob's cursor if it was at or after position 5

    10. DynamoDB batch write (every 100ms):
        PutItem: PK=doc_001, SK=v#43, op=INSERT(5,"X"), userId=U001, ts=...
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| ALB + Sticky Sessions | Route all WebSocket connections for a document to the same ECS container. Cookie based on documentId hash. | N/A (routing layer) |
| ECS Fargate (Collaboration Service) | Holds document state in-memory: text, version, connections, cursors. Runs OT transforms in < 1ms. Broadcasts via WebSocket. | CP for document (in-memory state is the source of truth during operation) |
| DynamoDB (Operations Log) | Append-only log of all operations. PK=docId, SK=version. Used for reconnect catch-up, version history, failover recovery. | CP (strongly consistent reads for version ordering) |
| ElastiCache Redis | Cached document state for fast reconnection, presence Pub/Sub for cross-container cursor broadcast, session store. | AP (cache miss falls through to DynamoDB) |
| S3 (Snapshots) | Periodic full-document snapshots every 100 ops. Enables fast version reconstruction: load snapshot + replay remaining ops. | AP (snapshot can be slightly behind, ops log is authoritative) |
| RDS Aurora (Metadata) | User profiles, document metadata, permissions, sharing. Relational queries: "list my documents", "who has access". | CP (permissions must be consistent -- viewer must not be able to edit) |

### What This Signals

You lead with the **in-memory architecture** (sticky sessions, sub-ms transforms, direct WebSocket broadcast) rather than a stateless design that round-trips to the database per operation. This shows you understand that real-time collaboration is fundamentally a **stateful problem** -- the server must hold the document in memory to transform operations fast enough for typing to feel instant. You clearly separate the fast path (in-memory OT + WebSocket) from the durable path (DynamoDB ops log + S3 snapshots).

**Common follow-up:** "What happens if the ECS container dies? You lose the in-memory state."

**Answer:** "Yes, and that's acceptable. The in-memory state is a performance optimization, not the source of truth. The DynamoDB ops log has every operation ever applied. When the container dies: (1) ALB detects health check failure in 5 seconds, (2) clients get WebSocket disconnect, (3) clients reconnect to a new container via ALB, (4) the new container loads the latest snapshot from S3 and replays ops since that snapshot from DynamoDB -- this takes under 500ms for a document with 100 ops since last snapshot. (5) Collaboration resumes. Total disruption: 5-10 seconds. Users re-type a few characters at most. The batch write buffer (100ms) means we might lose the last 100ms of operations, but that's 3-4 keystrokes that the user simply re-types."

---

## Phase 3: OT/CRDT Deep Dive (8-10 min)

**This is THE star section for real-time collaboration interviews. Spend the most time here.**

### Part A: OT Transform Rules with Examples

> "Operational Transformation has three core transform rules. Every concurrent editing conflict falls into one of these three categories. Let me walk through each with a concrete example."

```
OT TRANSFORM RULES (Numbered):

  Starting document: "ABCDEFGH" (8 characters, positions 0-7)
  Server version: 10
  Two users edit concurrently. Both have baseVersion=10.

  === RULE 1: INSERT vs INSERT ===

      User A: INSERT(3, "XY")   -- insert "XY" at position 3
      User B: INSERT(3, "Z")    -- insert "Z" at position 3 (same position!)

      Without OT (BROKEN):
        Apply A first: "ABCXYDEFGH"
        Apply B at pos 3: "ABCZXYDEFGH"  -- Z lands INSIDE A's insert!
        OR apply B first: "ABCZDEFGH"
        Apply A at pos 3: "ABCXYZDEFGH"  -- different result depending on order!

      With OT:
        A arrives first at server -> apply directly:
          doc = "ABCXYDEFGH"  (version 10 -> 11)

        B arrives with baseVersion=10, server at 11:
          Transform B against A:
          Both INSERT at position 3. A was applied first.
          Rule: shift B's position RIGHT by length of A's insert.
          B becomes: INSERT(3 + 2, "Z") = INSERT(5, "Z")

          Apply transformed B:
          doc = "ABCXYZDEFGH"  (version 11 -> 12)

      Both users converge to: "ABCXYZDEFGH"
      A's text "XY" is at positions 3-4, B's "Z" is at position 5.

      GENERAL RULE:
        If opA = INSERT(posA, textA) applied first
        and opB = INSERT(posB, textB) arrives later:
          If posB >= posA:  opB.position += len(textA)    -- shift right
          If posB < posA:   no change to opB              -- B is before A
          If posB == posA:  tie-break by userId (lower ID goes first)

  === RULE 2: INSERT vs DELETE ===

      User A: INSERT(3, "XY")   -- insert "XY" at position 3
      User B: DELETE(2, 4)      -- delete 4 chars starting at position 2 (removes "CDEF")

      A arrives first at server -> apply:
        doc = "ABCXYDEFGH"  (version 10 -> 11)

      B arrives with baseVersion=10, server at 11:
        Transform B (DELETE) against A (INSERT):
        A inserted 2 chars at position 3. B wants to delete positions 2-5.
        The inserted text is INSIDE B's delete range.

        Rule: B's delete range must EXPAND to include A's insertion
              OR split around the insertion.

        Approach (expand):
          Original delete: DELETE(2, 4)  -- removes positions 2,3,4,5
          A inserted 2 chars at pos 3 -> delete range expands:
          DELETE(2, 4 + 2) = DELETE(2, 6)  -- removes "CXYDEF"

        Apply transformed B:
          doc = "ABGH"  (version 11 -> 12)

        Alternative (preserve inserted text):
          Split delete around insertion:
          DELETE(2, 1) + DELETE(2+1+2, 3) = DELETE(2,1) + DELETE(5,3)
          doc = "ABXYGH"  -- A's insert preserved, surrounding text deleted

        Design choice: Google Docs preserves the insert (split approach).
        Rationale: if User A just typed text, deleting it immediately
        without their intent is surprising. Preserve the insert.

      GENERAL RULE:
        If opA = INSERT(posA, textA) applied first
        and opB = DELETE(posB, lenB) arrives later:
          If posB >= posA:  opB.position += len(textA)  -- shift right
          If posB + lenB <= posA:  no change            -- delete is entirely before insert
          If delete range spans insert point:  split delete around insert

  === RULE 3: DELETE vs DELETE ===

      User A: DELETE(2, 4)   -- delete positions 2-5 ("CDEF")
      User B: DELETE(4, 3)   -- delete positions 4-6 ("EFG")

      A arrives first at server -> apply:
        doc = "ABGH"  (version 10 -> 11)
        (removed "CDEF", positions 2-5)

      B arrives with baseVersion=10, server at 11:
        Transform B (DELETE) against A (DELETE):
        A deleted positions 2-5. B wants to delete positions 4-6.
        Overlap: positions 4-5 already deleted by A.

        Rule: reduce B's delete to only cover NOT-ALREADY-DELETED positions.
        B originally: DELETE(4, 3)  -- positions 4,5,6
        Positions 4,5 already deleted by A.
        Remaining for B: position 6 only (which is now at position 2 in the new doc).
        Transformed B: DELETE(2, 1)  -- delete 1 char at position 2 ("G")

        Apply transformed B:
          doc = "ABH"  (version 11 -> 12)

      GENERAL RULE:
        If opA = DELETE(posA, lenA) applied first
        and opB = DELETE(posB, lenB) arrives later:
          Calculate overlap between ranges [posA, posA+lenA) and [posB, posB+lenB)
          Remove overlap from B (already deleted)
          Shift B's position left by the portion of A's delete that comes before B
          If entirely overlapping: B becomes no-op (nothing left to delete)

  === SUMMARY TABLE ===
      +-------------------+-------------------------------------------+
      | Conflict Type     | Transform Rule                            |
      +-------------------+-------------------------------------------+
      | INSERT vs INSERT  | Shift later insert right by earlier's     |
      |                   | length. Tie-break by userId.              |
      +-------------------+-------------------------------------------+
      | INSERT vs DELETE  | If delete after insert: shift right.      |
      |                   | If delete spans insert: split around it.  |
      |                   | If delete before insert: no change.       |
      +-------------------+-------------------------------------------+
      | DELETE vs DELETE  | Remove overlap (already deleted).          |
      |                   | Shift remaining left. If fully            |
      |                   | overlapping: no-op.                       |
      +-------------------+-------------------------------------------+
```

### Part B: OT Server Loop (The Core Algorithm)

> "The OT server loop is the heart of the system. Every incoming operation goes through this exact sequence. Let me walk through the pseudocode."

```
OT SERVER LOOP (Numbered):

      For each incoming operation from a client:

      1. RECEIVE:
         op = { type: INSERT, position: 5, text: "Hello", baseVersion: 40 }
         clientId = "U002"
         currentServerVersion = 43

      2. VALIDATE:
         - Is clientId connected to this document? (auth check)
         - Is baseVersion <= currentServerVersion? (sanity check)
         - Is baseVersion >= earliest retained version? (not too old)
         - Is operation well-formed? (position within bounds, text non-empty)

      3. FETCH CONCURRENT OPS:
         If baseVersion < currentServerVersion:
           concurrentOps = opsLog.getRange(docId, baseVersion + 1, currentServerVersion)
           // Returns ops at versions 41, 42, 43 (3 ops to transform against)
         Else:
           concurrentOps = []  // No transform needed

      4. TRANSFORM (the core):
         transformedOp = op
         For each serverOp in concurrentOps (in version order):
           transformedOp = transform(transformedOp, serverOp)
           // After each transform, transformedOp is adjusted for that server op

         Example walkthrough:
           Incoming: INSERT(5, "Hello"), baseVersion=40
           Server ops since v40:
             v41: INSERT(3, "AB")  -- someone inserted "AB" at pos 3
             v42: DELETE(10, 2)    -- someone deleted 2 chars at pos 10
             v43: INSERT(0, "Z")   -- someone inserted "Z" at pos 0

           Transform step 1: INSERT(5,"Hello") vs INSERT(3,"AB")
             pos 5 >= pos 3 -> shift right by 2
             Result: INSERT(7, "Hello")

           Transform step 2: INSERT(7,"Hello") vs DELETE(10,2)
             pos 7 < pos 10 -> no change (insert is before delete)
             Result: INSERT(7, "Hello")

           Transform step 3: INSERT(7,"Hello") vs INSERT(0,"Z")
             pos 7 >= pos 0 -> shift right by 1
             Result: INSERT(8, "Hello")

           Final transformed op: INSERT(8, "Hello")

      5. APPLY:
         document.text = text[:8] + "Hello" + text[8:]
         document.version = 44  (currentServerVersion + 1)

      6. PERSIST (batched):
         Add to write buffer: { docId, version: 44, op: INSERT(8,"Hello"), userId: U002 }
         Buffer flushes to DynamoDB every 100ms

      7. BROADCAST:
         For each connection on this document:
           If connection == sender (U002):
             send ACK: { type: ACK, version: 44, serverOp: INSERT(8,"Hello") }
             // Client uses this to reconcile its optimistic local state
           Else:
             send REMOTE_OP: { type: REMOTE_OP, version: 44, op: INSERT(8,"Hello"), userId: U002 }
             // Other clients apply this operation to their local document
```

### Part C: Client-Side Prediction (Why It Feels Instant)

> "The reason Google Docs feels instant despite a server round-trip is client-side prediction. The client applies the operation locally BEFORE sending it to the server. If the server confirms, great -- the prediction was correct. If the server sends back a different transformed version, the client adjusts."

```
CLIENT-SIDE PREDICTION (Numbered):

      1. User types "X" at position 5:
         LOCAL STATE (immediate, 0ms):
           Apply INSERT(5, "X") to local document
           Show "X" on screen immediately (user sees instant feedback)
           Add to pending queue: { op: INSERT(5,"X"), baseVersion: 42 }
           Send to server via WebSocket

      2. While waiting for server response (~10-50ms):
         User keeps typing. Each keystroke:
           Applied locally (instant)
           Added to pending queue
           Sent to server

      3. Server ACK arrives:
         { type: ACK, version: 43, serverOp: INSERT(5,"X") }
         |
         Server op matches local prediction -> no adjustment needed
         Remove from pending queue
         Update local baseVersion to 43

      4. CONFLICT CASE -- server transforms the op:
         Local prediction: INSERT(5, "X")
         Server ACK: { version: 43, serverOp: INSERT(8, "X") }
         |
         Server moved the position from 5 to 8 (other ops shifted it).
         Client must REBASE:
           a. Undo local prediction (remove "X" from position 5)
           b. Apply server version (insert "X" at position 8)
           c. Re-transform all pending operations against the server ops
           d. Re-apply pending operations
         |
         This rebase is invisible to the user (happens in < 1ms).
         The cursor might jump slightly if the position changed significantly.

      5. REMOTE OPERATION arrives (another user's edit):
         { type: REMOTE_OP, version: 43, op: INSERT(10,"Hello"), userId: U001 }
         |
         Apply to local document (after any pending local ops)
         Transform remote op against pending local ops:
           If pending local op is INSERT(5,"X") and remote is INSERT(10,"Hello"):
             Remote pos 10 >= local pos 5 -> shift to INSERT(11,"Hello")
         Show remote user's edit + cursor animation

  WHY THIS WORKS:
    Without prediction: every keystroke has 10-50ms delay (network round-trip)
    With prediction:    every keystroke is instant (0ms local apply)
    Conflicts are rare: two users editing the exact same position simultaneously
    When conflicts do occur: rebase is < 1ms and usually invisible
    Google Docs, VS Code Live Share, and Figma all use this technique
```

**Common follow-up:** "What's the difference between OT and CRDT at a high level? When would you choose one over the other?"

**Answer:** "OT is an algorithm -- it transforms operations against each other using rules. It requires a central server to establish operation order. It's simpler to implement for basic text editing and has lower storage overhead. Google Docs uses OT. CRDT is a data structure -- each character has a unique ID (like a fractional index between its neighbors). Operations are commutative by construction -- you can apply them in any order and get the same result. No central server needed. The trade-off: CRDT metadata is ~5x the size of the text itself (each character carries an ID, logical clock, and tombstone flag). Choose OT when you have a reliable server and don't need offline editing. Choose CRDT when you need offline support, multi-region without a master, or peer-to-peer collaboration. Figma chose CRDT because designers work on unreliable networks and need local-first editing."

---

## Phase 4: Presence & Version History (5-7 min)

### Part A: Presence System

> "Presence shows where each collaborator's cursor is in real-time -- the colored cursors and selections you see in Google Docs. It's a separate system from document operations because it has very different consistency and latency requirements."

```
PRESENCE SYSTEM (Numbered):

      1. USER JOINS DOCUMENT:
         Alice connects to doc_001:
           Assign unique color: #FF5733 (red)
           Colors are deterministic by userId hash (same user always gets same color)
           Maximum colors: 12 distinct colors (beyond that, reuse with pattern variation)
           Broadcast to all: { type: PRESENCE_JOIN, userId: "Alice", color: "#FF5733" }
           Show Alice's avatar in collaborator bar

      2. CURSOR MOVEMENT (continuous):
         Alice moves cursor to position 42:
           Local state updated immediately (every 50ms)
           Network broadcast THROTTLED to every 500ms:
             { type: PRESENCE, userId: "Alice", cursor: { pos: 42, selEnd: 42 }, color: "#FF5733" }
           |
           Why throttle? 50ms = 20 updates/second per user.
           10 collaborators = 200 messages/second just for cursors.
           500ms = 2 updates/second per user = 20 messages/second. 10x reduction.
           Users cannot perceive 500ms lag on REMOTE cursors.
           Local cursor remains instant (no throttling on local display).

      3. SELECTION (highlight):
         Alice selects text from position 10 to position 25:
           { cursor: { pos: 10, selEnd: 25 }, color: "#FF5733" }
           Other users see: red highlight over positions 10-25
           When Alice types: selection replaced, highlight disappears

      4. STORAGE (Redis):
         HSET presence:doc_001 Alice '{"pos":42,"selEnd":42,"color":"#FF5733","ts":1714060800}'
         HSET presence:doc_001 Bob   '{"pos":15,"selEnd":15,"color":"#33FF57","ts":1714060801}'
         |
         Redis chosen over DynamoDB:
           Sub-ms reads (presence queried on every reconnect)
           Built-in TTL for stale cleanup
           Pub/Sub for cross-container broadcast

      5. STALE DETECTION:
         Background job every 10 seconds:
           Scan presence:doc_001 hash
           If any entry has ts older than 30 seconds:
             Mark user as "idle" (gray out cursor)
           If ts older than 60 seconds:
             Remove presence entry (user disconnected without clean close)
             Broadcast: { type: PRESENCE_LEAVE, userId: "staleUser" }

      6. CURSOR ADJUSTMENT ON REMOTE OPERATIONS:
         Bob's cursor is at position 20.
         Alice inserts "XYZ" at position 10 (before Bob's cursor).
         |
         Bob's client receives: REMOTE_OP INSERT(10, "XYZ")
         Bob's cursor must shift: 20 + 3 = 23
         Without adjustment: Bob's cursor appears to jump backward (bad UX)
         Rule: shift remote cursors using same transform logic as operations

      7. CROSS-CONTAINER PRESENCE (rare with sticky sessions):
         If doc_001 users span containers (failover scenario):
           Container A publishes: Redis PUBLISH presence:doc_001 '{"userId":"Alice","pos":42}'
           Container B subscribes: Redis SUBSCRIBE presence:doc_001
           Container B receives Alice's cursor, broadcasts to its local WebSocket connections
```

### Part B: Version History

> "Version history lets users see what the document looked like at any point in time -- 'show me yesterday's version'. The naive approach is replaying all operations from the beginning, which gets slow as the document grows. Snapshots solve this."

```
VERSION HISTORY ARCHITECTURE (Numbered):

      1. OPERATION LOG (DynamoDB -- continuous):
         Every applied operation is stored:
           PK = doc_001
           SK = v#00001    op=INSERT(0,"Hello "), userId=U001, ts=2026-04-26T10:00:00
           SK = v#00002    op=INSERT(6,"World"), userId=U002, ts=2026-04-26T10:00:01
           SK = v#00003    op=DELETE(5,1), userId=U001, ts=2026-04-26T10:00:05
           ...
           SK = v#05000    op=INSERT(2847,"conclusion"), userId=U001, ts=2026-04-26T18:30:00

         Properties:
           Append-only (never update or delete during active editing)
           Ordered by version (SK sort key)
           Queryable by time range (GSI on timestamp)

      2. PERIODIC SNAPSHOTS (S3 -- every 100 ops or 5 minutes):
         At version 100:
           snapshot = { docId: "doc_001", version: 100, content: "full text at v100...", ts: "..." }
           Store: s3://snapshots/doc_001/v#00100/snapshot.json (50 KB avg)
         At version 200:
           s3://snapshots/doc_001/v#00200/snapshot.json
         At version 300:
           s3://snapshots/doc_001/v#00300/snapshot.json
         ...

         DynamoDB pointer:
           PK=doc_001, SK=snapshot#00100, s3Url=s3://snapshots/...
           PK=doc_001, SK=snapshot#00200, s3Url=s3://snapshots/...

      3. RECONSTRUCTION (when user requests "show me version 247"):
         |
         a. Find nearest snapshot BEFORE version 247:
            Query DynamoDB: PK=doc_001, SK begins_with "snapshot#", SK <= "snapshot#00247"
            Result: snapshot#00200 (at version 200)
         |
         b. Load snapshot from S3:
            GET s3://snapshots/doc_001/v#00200/snapshot.json
            content = "the full document text at version 200..."
         |
         c. Replay ops from version 201 to 247:
            Query DynamoDB: PK=doc_001, SK between v#00201 and v#00247
            Result: 47 operations
         |
         d. Apply each operation in order:
            For each op in [v201, v202, ..., v247]:
              content = applyOp(content, op)
         |
         e. Return reconstructed document:
            Version 247 content, reconstructed in < 50ms
            (S3 fetch: ~30ms, 47 ops replay: ~5ms, DynamoDB query: ~10ms)

      4. WITHOUT SNAPSHOTS (why snapshots matter):
         To reconstruct version 4500:
           Load empty document (version 0)
           Replay ALL 4500 operations from DynamoDB
           DynamoDB query: paginate through 4500 items (~500ms)
           Replay: 4500 string operations (~200ms)
           Total: ~700ms (noticeable delay)

         WITH SNAPSHOTS:
           Load snapshot at v#4400 from S3 (~30ms)
           Replay 100 ops from DynamoDB (~15ms)
           Total: ~45ms (instant)

      5. VERSION TIMELINE UI:
         Show timeline slider: [v1] -------- [v2500] -------- [v5000]
         User drags slider to any point:
           Nearest snapshot loaded + ops replayed
           Document content displayed (read-only)
           Show who made each change (userId on each op)

      6. DIFF BETWEEN VERSIONS:
         User: "What changed between yesterday and today?"
         Reconstruct version at yesterday's end (say v#3000)
         Reconstruct current version (say v#5000)
         Compute text diff (standard diff algorithm)
         Display: red (deleted), green (inserted), gray (unchanged)
```

**Common follow-up:** "How do you handle undo/redo in a collaborative setting?"

**Answer:** "Undo in collaborative editing is NOT 'restore the previous document state' -- that would undo OTHER people's changes too. Instead, undo means 'reverse MY last operation'. We store the inverse of each operation: the inverse of INSERT(5, 'Hello') is DELETE(5, 5). When a user presses Ctrl+Z, we take their last operation's inverse and send it through the normal OT pipeline as a NEW operation. The server transforms it against any operations that happened since the original, ensuring it only reverses the user's text without disturbing others' work. This is why operations are modeled as commands -- they're invertible."

---

## Phase 5: Scaling & Edge Cases (5-8 min)

### Part A: Scaling to Millions of Concurrent Documents

```
SCALING STRATEGY (Numbered):

      1. DOCUMENT SHARDING (ECS containers):
         Each ECS container holds ~200 documents in memory.
         1 million concurrent documents = ~5,000 ECS containers.
         |
         ALB sticky session routes by documentId hash:
           documentId % N -> container index
         |
         Container sizing: 4 vCPU / 8 GB RAM
           Each document: ~50 KB (text + metadata + connections)
           200 docs * 50 KB = 10 MB memory for documents
           Remaining 7.99 GB: WebSocket buffers, OT engine, runtime
         |
         Auto-scaling:
           Scale metric: active documents per container
           Target: 200 docs/container (leave 50% headroom)
           Scale up: new documents route to new containers
           Scale down: drain documents (close idle WebSockets, evict from memory)

      2. HOT DOCUMENT HANDLING (1000+ collaborators):
         A viral public document (e.g., shared meeting notes for 1000 people):
           Single container cannot handle 1000 WebSocket connections + OT transforms
         |
         Solution: READ-WRITE SPLIT
           1 write container: receives all operations, runs OT, broadcasts ACKs
           N read containers: subscribe to operation stream, broadcast to read-only viewers
         |
         Flow:
           Writers (editors) -> sticky session -> write container (OT transforms)
           Viewers (read-only) -> any read container -> receive operation stream
           Write container publishes ops to Redis Pub/Sub
           Read containers subscribe, push ops to viewer WebSockets
         |
         This handles 10+ writers + 1000+ viewers per document.

      3. OPERATION THROUGHPUT:
         Typical: 30 ops/user/minute * 3 users/doc = 90 ops/doc/minute
         Peak (meeting notes): 50 ops/user/minute * 20 users = 1000 ops/doc/minute
         |
         Per container (200 docs):
           200 * 90 = 18,000 ops/minute average = 300 ops/second
           OT transform: < 1ms per op -> 300ms of 1 CPU second (30% utilization)
           Plenty of headroom for peak bursts.

      4. DYNAMODB OPERATIONS LOG AT SCALE:
         1M concurrent docs * 90 ops/minute = 90M ops/minute = 1.5M writes/second
         |
         DynamoDB on-demand: auto-scales to this level
         Partition key = docId -> operations for each doc in same partition
         DynamoDB partition: 1000 WCU per partition
         90 ops/minute per doc = 1.5 WCU per doc -> WELL within limits
         |
         Batch writes: buffer 100ms -> ~10 ops per batch per doc
         BatchWriteItem: 25 items per call
         Effective: each container makes ~20 BatchWriteItem calls/second
         Manageable.
```

### Part B: Offline Editing & Reconnection

```
OFFLINE + RECONNECT SCENARIO (Numbered):

      1. USER GOES OFFLINE:
         Bob is editing doc_001 (version=42, connected to ECS Task A).
         Bob's WiFi drops.
         |
         WebSocket disconnects.
         Server: broadcast { PRESENCE_LEAVE, userId: Bob } to other users.
         Server: Bob's connection removed from active set.

      2. BOB CONTINUES EDITING LOCALLY (offline):
         Bob's client has local document state (version=42).
         Bob types 15 characters (15 INSERT operations).
         Operations queued locally:
           { INSERT(10,"a"), baseVersion: 42 }
           { INSERT(11,"b"), baseVersion: 42 }  // all against v42
           ...
           { INSERT(24,"o"), baseVersion: 42 }
         |
         Local document shows Bob's edits immediately (client-side prediction).
         15 operations buffered, waiting for reconnection.

      3. MEANWHILE, ALICE EDITS (online):
         Alice makes 8 operations (server goes from v42 to v50):
           v43: INSERT(0, "Title: ") -- Alice
           v44: DELETE(20, 3) -- Alice
           ...
           v50: INSERT(30, "!") -- Alice

      4. BOB RECONNECTS:
         WebSocket re-established to ECS Task A (same container if still alive,
         or new container via ALB if Task A died).
         |
         Bob sends: { type: RECONNECT, docId: "doc_001", lastVersion: 42 }

      5. SERVER CATCH-UP:
         Server sees: Bob last saw version 42, current version is 50.
         Send Bob all ops from v43 to v50:
           { type: CATCH_UP, ops: [v43, v44, ..., v50] }
         |
         Bob's client receives catch-up ops.

      6. CLIENT-SIDE REBASE:
         Bob's client must merge his 15 local ops with Alice's 8 server ops.
         |
         a. Undo all 15 local predictions (revert to v42 state)
         b. Apply server ops v43-v50 (now at v50 state, matching server)
         c. Transform each of Bob's 15 ops against all 8 server ops:
              For each local_op in Bob's pending ops:
                For each server_op in [v43..v50]:
                  local_op = transform(local_op, server_op)
         d. Send transformed ops to server (they'll be applied as v51-v65)
         e. Apply transformed ops locally

      7. CONVERGENCE:
         Server applies Bob's 15 transformed ops (v51-v65).
         Broadcasts to Alice.
         Alice and Bob see identical document state.
         |
         Total offline duration tolerance:
           OT: works for minutes of offline editing (10-100 ops)
           Beyond that: transform chain grows long, risk of user-confusing rebase
           CRDT: works for hours/days of offline (designed for this)

      8. EDGE CASE: CONTAINER DIED DURING BOB'S OFFLINE:
         Bob reconnects, ALB routes to new container (Task B).
         Task B has no in-memory state for doc_001.
         |
         Task B: load latest snapshot from S3 + replay ops from DynamoDB
         Task B: now has doc at version 50
         Continue with step 5 above (catch-up + rebase).
```

### Part C: Conflict Scenarios at Scale

```
EDGE CASES (Numbered):

      1. SAME WORD, SAME TIME:
         Alice and Bob both try to replace "color" with different words.
         Alice: DELETE(10, 5) + INSERT(10, "colour")   -- British spelling
         Bob:   DELETE(10, 5) + INSERT(10, "hue")      -- different word
         |
         OT resolution:
           Alice's ops arrive first -> applied: doc has "colour" at pos 10
           Bob's DELETE(10,5) vs Alice's DELETE(10,5): overlapping delete -> no-op
           Bob's INSERT(10,"hue") vs Alice's DELETE(10,5) + INSERT(10,"colour"):
             Transformed: INSERT(10 + len("colour"), "hue") = INSERT(17, "hue")
           Result: "...colourhue..."
         |
         Not ideal but CONSISTENT. Both users see the same thing.
         In practice: users see the conflict, one manually cleans up.
         This is acceptable -- real collaboration is 99.9% non-conflicting.

      2. CURSOR AT DOCUMENT END:
         Document: "Hello" (length 5)
         Alice: INSERT(5, " World") at the end
         Bob: INSERT(5, "!") at the end
         |
         Tie-break by userId: lower userId goes first.
         If Alice (U001) < Bob (U002): Alice's text first.
         Result: "Hello World!"
         Deterministic -- same result regardless of arrival order.

      3. RAPID DELETION + INSERTION (undo-redo race):
         Alice deletes a paragraph (DELETE(100, 500)).
         Bob (who hasn't seen the delete) types inside that paragraph.
         |
         Bob's INSERT lands inside a deleted range.
         OT: preserve Bob's insert (place it at the delete boundary).
         Bob sees his text survive Alice's delete. Alice sees Bob's text appear.
         Both can then decide what to do.

      4. MAXIMUM CONCURRENT OPERATIONS:
         20 users all paste at position 0 simultaneously.
         Server receives 20 INSERTs, all with same baseVersion.
         Each must be transformed against all previous:
           Op 2: transform against op 1 (1 transform)
           Op 3: transform against ops 1, 2 (2 transforms)
           Op 20: transform against ops 1-19 (19 transforms)
         Total transforms: 20 * 19 / 2 = 190 transforms
         At < 1ms each: ~190ms total
         Acceptable, but this is the worst case.
```

**Common follow-up:** "What about a document with 10,000 concurrent editors?"

**Answer:** "10,000 editors on a single document is an extreme edge case -- even Google's all-hands meeting notes don't have that many simultaneous typists. The bottleneck is broadcast: each operation must be sent to 10,000 WebSocket connections. Solution: hierarchical broadcast. A single write server handles OT transforms for active writers (likely < 100 people are actually typing). Operations are published to a Redis Pub/Sub channel. Multiple read-relay servers subscribe and each handles 1,000 viewer connections. Writers connect to the write server; viewers connect to relay servers. This separates the OT bottleneck (CPU-bound, handles 100 writers easily) from the broadcast bottleneck (I/O-bound, scales horizontally with relay servers)."

---

## Phase 6: Tradeoffs (3-5 min)

### OT vs CRDT

| Aspect | OT (Google Docs) | CRDT (Figma, Automerge) |
|--------|------------------|------------------------|
| Central server required | Yes (server orders operations) | No (merge anywhere) |
| Storage overhead | Low (just the text + ops log) | High (~5x for char metadata) |
| Offline editing | Difficult (long transform chains) | Natural (designed for it) |
| Multi-region | Needs master region | True multi-master |
| Complexity | Algorithm complexity (transform rules) | Data structure complexity (unique char IDs) |
| Undo/redo | Straightforward (inverse operations) | Complex (CRDT undo is hard) |
| Industry use | Google Docs, VS Code Live Share | Figma, Apple Notes, Linear |
| Best for | Server-centric, online-first | Offline-first, peer-to-peer |

**Say:** "I chose OT for this design because the requirements are online-first collaboration with a reliable server. OT is simpler to reason about -- three transform rules cover 90% of conflicts. Google Docs has battle-tested OT at billions-of-documents scale. If the interviewer shifts requirements to offline-first or peer-to-peer, I'd switch to CRDTs. Figma chose CRDTs because designers work on unreliable networks and need local-first editing with eventual sync. The key insight: OT is an algorithm (transforms operations), CRDT is a data structure (self-merging). Algorithm vs data structure -- different trade-offs."

### Centralized vs Decentralized Architecture

| Aspect | Centralized (This Design) | Decentralized (P2P) |
|--------|--------------------------|---------------------|
| Latency | Server round-trip (10-50ms) + prediction | Direct peer (varies, 5-200ms) |
| Consistency | Strong (server is source of truth) | Eventual (CRDTs converge) |
| Failure mode | Server down = no edits | Any peer can keep editing |
| Scaling | Vertical per doc (one server holds state) | Horizontal (peers share load) |
| Simplicity | Simpler (one authority) | Complex (peer discovery, NAT traversal) |
| Best for | Enterprise docs (Google Docs) | Creative tools (Figma), local-first apps |

**Say:** "Centralized is the right default for enterprise collaboration. Users expect the server to be the source of truth. It simplifies conflict resolution, permissions, version history, and auditing. Decentralized shines when the network is unreliable or you want zero-latency local edits without server dependency. Figma started centralized and migrated to CRDT-based architecture as they scaled internationally -- cross-continent latency made centralized OT feel sluggish for design work where every pixel matters."

### AP vs CP: Where Each Applies

| Component | CAP Choice | Why |
|-----------|-----------|-----|
| Document operations (text edits) | **CP** | Two users seeing different text = broken product. OT guarantees convergence. Server is single source of truth. |
| Presence (cursor positions) | **AP** | Cursor at position 42 vs 45 is invisible. Showing a stale cursor is better than showing no cursor during a partition. |
| Document metadata (title, sharing) | **AP** | Title change taking 2 seconds to propagate is fine. Availability > consistency. |
| Permissions | **CP** | A viewer must not be able to edit. Permission changes must be immediately enforced. |
| Version history | **AP** | A snapshot being 30 seconds behind is fine. Ops log is the authoritative record. |
| Operation log | **CP** | Losing an operation = permanent document corruption. DynamoDB with strong consistency reads. |

**Say:** "The interesting insight is that this system is CP where convergence matters and AP where human perception has a tolerance window. Document text is CP because users looking at different words is a showstopper. Cursor positions are AP because humans can't tell if a remote cursor is 500ms stale. This dual-CAP approach -- CP for data integrity, AP for user experience -- is the pattern Google Docs actually uses."

---

## Red Flags (What NOT to Do)

- No conflict resolution strategy -- "just apply operations in order" breaks when users edit simultaneously
- Polling instead of WebSocket -- "client polls every second for changes" adds unacceptable latency and wastes bandwidth
- Stateless server -- "load document from DB for every operation" adds 5-20ms per keystroke, unacceptable for real-time typing
- No version tracking -- "overwrite the document in the database" loses history and makes OT impossible (no baseVersion to transform against)
- Single-threaded broadcast -- "iterate through 10K connections and send one by one" blocks the OT transform loop
- No snapshots -- "replay all operations from the beginning" makes version history O(n) in total operations instead of O(1) with snapshots
- Treating presence like document ops -- "use OT for cursor positions" overcomplicates presence (AP is fine, no transform needed)
- No client-side prediction -- "wait for server ACK before showing the character" makes typing feel laggy (50ms perceived delay per keystroke)

## Green Flags (What Interviewers Want to Hear)

- Lead with OT vs CRDT trade-off: "OT for server-centric, CRDT for offline-first. I'm choosing OT because..."
- Concrete transform rules: "INSERT vs INSERT shifts position by length. INSERT vs DELETE splits around the insertion."
- In-memory state: "Sticky sessions route all users on a doc to the same container. OT transforms in < 1ms, no DB round-trip."
- Client-side prediction: "Apply locally, send to server, reconcile on ACK. Typing feels instant."
- Operations as commands: "INSERT(pos, text) and DELETE(pos, length) with baseVersion. Immutable, transformable, invertible."
- Dual persistence: "Ops log in DynamoDB (every operation) + snapshots in S3 (every 100 ops). Reconstruct any version in < 50ms."
- Presence is AP: "Cursors are eventually consistent. 500ms broadcast throttle. CP only for document text."
- Reconnection strategy: "Catch-up ops since last version, client rebases local pending ops, convergence guaranteed."

---

## 30-Second Elevator Pitch

> "For a Google Docs-style collaboration tool, I'd use **ECS Fargate with ALB sticky sessions** -- all users on the same document route to the same container, which holds the document state **in-memory** for sub-millisecond OT transforms. Operations are **INSERT(pos, text)** and **DELETE(pos, length)**, each with a **baseVersion**. When concurrent edits arrive, the server transforms them: **INSERT vs INSERT shifts position**, **INSERT vs DELETE adjusts boundaries**, **DELETE vs DELETE merges ranges**. Transformed ops are broadcast via **WebSocket** to all collaborators (< 10ms). **Client-side prediction** makes typing feel instant -- apply locally, send to server, reconcile on ACK. **Presence** (cursors + colors) broadcasts via **Redis Pub/Sub** every 500ms (AP -- stale cursors are fine). The operation log is append-only in **DynamoDB**, with **S3 snapshots** every 100 ops for fast version reconstruction. System is **CP for document text** (convergence is non-negotiable) and **AP for presence** (eventual consistency is perfect for cursors). Alternative: **CRDTs** for offline-first or multi-region without master (Figma's choice), but OT is simpler and battle-tested at Google scale."

**Time: Under 30 seconds. Covers: OT transforms, WebSocket + sticky sessions, client prediction, presence, persistence strategy, version history, CAP trade-off, CRDT alternative.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements            2-3 min   (scale, plain vs rich text, offline, consistency)
Phase 2:  High-Level Architecture          5-7 min   (WebSocket, ECS sticky sessions, in-memory OT, persistence)
Phase 3:  OT/CRDT Deep Dive               8-10 min  (transform rules with examples, server loop, client prediction)
Phase 4:  Presence & Version History       5-7 min   (cursor colors, throttled broadcast, snapshots + replay)
Phase 5:  Scaling & Edge Cases             5-8 min   (millions of docs, offline/reconnect, conflict scenarios)
Phase 6:  Tradeoffs Discussion             3-5 min   (OT vs CRDT, centralized vs decentralized, CP vs AP)
-----------------------------------------------------------------------------------
Total:                                     ~35 min
```

If short on time, shorten Phase 5 (scaling/edge cases) and Phase 6 (tradeoffs). Never skip Phase 3 (OT/CRDT deep dive) -- that IS the interview for this problem and what differentiates a senior answer from a generic one. Phase 4 (presence + version history) is the second priority -- it shows you understand the full product, not just the algorithm.
