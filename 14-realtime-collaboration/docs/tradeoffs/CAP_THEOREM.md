# CAP Theorem -- Real-time Collaboration Tool (Google Docs)

> Interview-ready analysis of consistency, availability, and partition tolerance
> tradeoffs for a real-time collaborative editing platform. Covers the unique
> CAP challenges of collaboration: document convergence, cursor presence,
> OT vs CRDT consistency models, causal ordering, and how Google Docs and
> Figma approach CAP differently.
>
> **Key insight:** Unlike most systems that pick one CAP side globally,
> real-time collaboration uses **CP for document state** (convergence is
> non-negotiable) and **AP for presence/cursors** (stale cursor positions
> are tolerable).

---

## CAP Recap

| Letter | Property | Meaning |
|--------|----------|---------|
| **C** | Consistency | Every read receives the most recent write |
| **A** | Availability | Every request receives a response (no timeouts) |
| **P** | Partition Tolerance | System continues operating despite network partitions |

In a distributed system, network partitions **will** happen. You must choose:

```
           C
          / \
         /   \
        /     \
      CP       CA  <-- not possible in distributed systems
      /         \
     /           \
    P ----------- A
          AP
```

**You can have CP or AP, never CA in a real distributed system.**

---

## The Split Strategy: CP for Documents, AP for Presence

### Why Split?

Real-time collaboration has two fundamentally different data types:

| Data Type | Consistency Need | Staleness Tolerance | CAP Choice |
|-----------|-----------------|--------------------|-----------| 
| Document content | **Non-negotiable** -- all users MUST see the same document | Zero -- divergence means data loss | **CP** |
| Cursor positions | Nice-to-have -- users can tolerate a stale cursor | 1-2 seconds stale is fine | **AP** |
| Active user list | Nice-to-have -- "who's online" can be slightly off | 5-10 seconds stale is fine | **AP** |
| Operation history | Important -- but can be eventually consistent | Seconds stale, as long as eventually complete | **AP** |

### The Core Argument

```
Scenario: Network partition between data centers

  Document content:
    WRONG: Alice sees "Hello World" while Bob sees "Hello Wrld"
           --> Users think their edits are saved, but documents DIVERGED
           --> When partition heals, which version wins? Data is LOST.
    RIGHT: Block edits during partition. Users see "reconnecting..."
           --> Annoying, but no data loss. Resume when partition heals.

  Cursor position:
    WRONG: Block cursor updates during partition. Document is frozen.
           --> Users cannot even see where others are editing? Overkill.
    RIGHT: Show stale cursor positions. Bob's cursor is 2 seconds behind.
           --> Users barely notice. Resume real-time updates when healed.
```

### ASCII Diagram -- Split CAP Strategy

```
  +---------------------------------------------------------------------+
  |                    REAL-TIME COLLABORATION                          |
  |                                                                     |
  |   +---------------------------+   +-----------------------------+   |
  |   |    DOCUMENT STATE (CP)    |   |    PRESENCE STATE (AP)      |   |
  |   |                           |   |                             |   |
  |   |  - Document content       |   |  - Cursor positions         |   |
  |   |  - Operation log          |   |  - Active user list         |   |
  |   |  - Version numbers        |   |  - Typing indicators        |   |
  |   |  - Access permissions     |   |  - Last-seen timestamps     |   |
  |   |                           |   |                             |   |
  |   |  Strong consistency       |   |  Eventual consistency       |   |
  |   |  Total ordering           |   |  Best-effort delivery       |   |
  |   |  Server is source of      |   |  Redis with short TTL       |   |
  |   |  truth                    |   |  Tolerate staleness         |   |
  |   +---------------------------+   +-----------------------------+   |
  +---------------------------------------------------------------------+
```

---

## OT: Centralized (CP) -- The Google Docs Model

### How OT Achieves CP

Operational Transformation uses a **centralized server** as the single source
of truth. All operations go through the server, which assigns a total order
and transforms operations before broadcasting.

```
  Alice                 SERVER (source of truth)              Bob
    |                        |                                  |
    |-- INSERT("X", pos=2) ->|                                  |
    |                        |-- assign version 11              |
    |                        |-- transform against pending ops  |
    |                        |-- broadcast to all               |
    |                        |--------------------------------->|
    |<-----------------------|                                  |
    |  ACK (version 11)      |                                  |
    |                        |                                  |
    |                        |<-- INSERT("Y", pos=4) ----------|
    |                        |-- assign version 12              |
    |                        |-- transform against version 11   |
    |                        |-- broadcast to all               |
    |<-----------------------|                                  |
    |  Bob's op (transformed)|                                  |
    |                        |--------------------------------->|
    |                        |  ACK (version 12)                |
```

### OT Consistency Properties

| Property | How OT Achieves It |
|----------|--------------------|
| Total ordering | Server assigns monotonic version numbers |
| Convergence | Every client applies the same ops in the same server-ordered sequence |
| Intent preservation | OT transforms positions so each op achieves its original intent |
| Causality | Version numbers encode causal ordering -- op N causally depends on ops 1..N-1 |

### OT as CP -- The Trade-offs

| Advantage | Disadvantage |
|-----------|-------------|
| Strong consistency -- all users see same document | Server is single point of failure |
| Simple mental model -- server decides ordering | Higher latency -- every op goes through server |
| Proven at scale (Google Docs, 15+ years) | Offline editing is complex (queue ops, reconcile on reconnect) |
| Transform algorithm is well-understood | Server must process every operation in order (bottleneck) |

### Numbered Call Chain -- OT Consistency During Partition

```
1.  Alice and Bob are editing "doc-42" on Server A
2.  Network partition: Server A cannot reach Server B (replica)
3.  Server A is the primary -- it continues accepting edits (CP: choose consistency)
4.  Alice sends INSERT("X", pos=5) -- Server A accepts, assigns version 100
5.  Bob sends DELETE(pos=3, len=1) -- Server A accepts, assigns version 101
6.  Server A transforms and broadcasts to both Alice and Bob
7.  Meanwhile, Server B is unreachable -- no writes accepted there
8.  If a client was connected to Server B: it sees "reconnecting..."
9.  Partition heals: Server B replays ops 100, 101 from Server A's log
10. All replicas converge to the same document state
```

---

## CRDT: Decentralized (AP) -- The Figma Model

### How CRDT Achieves AP

Conflict-free Replicated Data Types assign every character a **globally unique
ID** that determines its position. Operations commute -- the order of application
does not matter. No server required for convergence.

```
  Alice                                                         Bob
    |                                                             |
    |  Insert "X" between char-ID-5 and char-ID-6                |
    |  Assign new char-ID: (alice, seq=42)                        |
    |                                                             |
    |-- broadcast to peers ---+-------- broadcast to peers ------>|
    |                         |                                   |
    |  Insert "Y" between char-ID-5 and char-ID-6                |
    |<-------- broadcast ---  |                                   |
    |                         +-- both arrive, order by char-ID   |
    |                                                             |
    |  Result: both Alice and Bob see "...XY..." or "...YX..."   |
    |  depending on char-ID ordering -- but BOTH see the SAME    |
    |  result regardless of delivery order!                       |
```

### CRDT Consistency Properties

| Property | How CRDT Achieves It |
|----------|---------------------|
| Commutativity | Operations commute -- applying A then B = applying B then A |
| Convergence | Same set of operations always produces the same result, regardless of order |
| Availability | No server needed -- peers can edit offline and sync later |
| Partition tolerance | Each peer has a full copy, merges on reconnect |

### CRDT as AP -- The Trade-offs

| Advantage | Disadvantage |
|-----------|-------------|
| Works offline -- edit without server | Tombstones accumulate (deleted chars not truly removed) |
| No single point of failure | Metadata overhead -- every character needs a unique ID |
| Peer-to-peer possible -- lower latency | Harder to implement undo (commutative undo is complex) |
| Eventual convergence guaranteed by math | Document size grows with edits (needs periodic compaction) |

### CRDT Data Structure -- Text as a Sequence of Unique IDs

```
  Document: "HELLO"

  OT representation (positions):
  +---+---+---+---+---+
  | H | E | L | L | O |
  | 0 | 1 | 2 | 3 | 4 |   <-- positions shift when others insert/delete
  +---+---+---+---+---+

  CRDT representation (unique IDs):
  +----------+----------+----------+----------+----------+
  | H        | E        | L        | L        | O        |
  | (A,1)    | (A,2)    | (A,3)    | (A,4)    | (A,5)    |
  +----------+----------+----------+----------+----------+
                    ^
       Unique ID: (userId, sequenceNumber)
       Never changes. Never conflicts. Position derived from ID ordering.
```

### Numbered Call Chain -- CRDT Offline Editing + Sync

```
1.  Alice goes offline while editing "doc-42"
2.  Alice types "Hello" -- creates 5 CRDT character nodes with IDs (alice, 1..5)
3.  Bob (online) types "World" -- creates 5 nodes with IDs (bob, 1..5)
4.  Alice reconnects after 10 minutes
5.  Alice sends her 5 character nodes to Bob (and server)
6.  Bob sends his 5 character nodes to Alice
7.  Both merge: order characters by their unique IDs
8.  Merge is deterministic -- both Alice and Bob see "HelloWorld" (or "WorldHello"
    depending on ID ordering rules)
9.  No conflict resolution needed -- CRDT commutativity guarantees convergence
10. Tombstones for any deleted characters are retained for future merges
```

---

## Causal Consistency -- Operations Must Be Applied in Causal Order

### What Is Causal Consistency?

If operation B was created **after** seeing the result of operation A, then
B causally depends on A. Every client must apply A before B.

```
  Alice types "Hello" (op A, version 1)
  Bob sees "Hello" and types " World" at position 5 (op B, version 2)
  Carol receives op B before op A (network reordering)

  WITHOUT causal ordering:
    Carol applies op B first: insert " World" at pos 5 of empty doc?!
    CRASH or CORRUPTION

  WITH causal ordering:
    Carol buffers op B (version 2), waits for op A (version 1)
    Applies op A first ("Hello"), then op B (" World")
    Result: "Hello World"   CORRECT
```

### How OT Enforces Causality

```
  Each operation carries a clientVersion:
    op.clientVersion = the server version the client has seen

  Server rule:
    if op.clientVersion < server.currentVersion:
        transform op against all ops between clientVersion and currentVersion
    Operations are applied in server-assigned version order
    Clients buffer incoming ops and apply in version order

  Version vector (simplified):
    Client maintains: "I have seen server version N"
    Server maintains: "Current version is M"
    If client sends op with version N < M: transform against ops N+1..M
```

### How CRDT Handles Causality

```
  Each operation carries a vector clock:
    {alice: 5, bob: 3}  means "I have seen Alice's first 5 ops and Bob's first 3"

  Delivery rule:
    Deliver op from Alice with clock {alice: 5, bob: 3} only if:
      - I have seen Alice's ops 1..4 (so this is her next one)
      - I have seen Bob's ops 1..3 (Alice had seen them when she created this op)

  If either condition fails: buffer the op until dependencies arrive
```

### Causal Ordering Comparison

| Aspect | OT | CRDT |
|--------|----|----- |
| Mechanism | Server-assigned version numbers | Vector clocks per peer |
| Ordering | Total order (server decides) | Partial order (causal, concurrent ops can be in any order) |
| Buffering | Client buffers if version gap | Client buffers if vector clock gap |
| Offline | Queue ops, reconcile on reconnect | Vector clock tracks what has been seen |

---

## Conflict Resolution: OT Transforms vs CRDT Commutativity

### OT Conflict Resolution

```
  Alice: INSERT("X", pos=2)      Bob: INSERT("Y", pos=2)
                                   (same position!)

  OT resolution:
  1. Server receives Alice's op first (total ordering)
  2. Server applies Alice's op: "ABCDE" -> "ABXCDE"
  3. Server transforms Bob's op: pos=2, but Alice inserted at pos=2
     Tie-break: Alice's userId < Bob's userId, so Alice goes first
     Bob' = INSERT("Y", pos=3)
  4. Server applies Bob': "ABXCDE" -> "ABXYCDE"
  5. Broadcast: Alice gets Bob'(pos=3), Bob gets Alice(pos=2) + rebased
  6. Result: "ABXYCDE" on both clients
```

### CRDT Conflict Resolution

```
  Alice: INSERT("X", between charID-2 and charID-3)
         New char ID: (alice, seq=10)

  Bob: INSERT("Y", between charID-2 and charID-3)
       New char ID: (bob, seq=7)

  CRDT resolution:
  1. No server needed. Both operations include unique char IDs.
  2. Alice receives Bob's op, Bob receives Alice's op
  3. Both insert between charID-2 and charID-3
  4. Tie-break: compare char IDs -> (alice,10) vs (bob,7)
     Deterministic ordering: e.g., alphabetical on userId -> alice < bob
     So X goes before Y
  5. Both Alice and Bob arrive at: "...XY..."
  6. No transform needed -- the char IDs determine the order
```

### Resolution Comparison Table

| Aspect | OT (Transform) | CRDT (Commutativity) |
|--------|----------------|---------------------|
| Who resolves | Server (centralized) | Each peer (decentralized) |
| How | Transform positions against prior ops | Unique IDs determine order, ops commute |
| Tie-breaking | Server's total order + userId | Character ID comparison |
| Same-position insert | Shift second op's position | Both chars inserted, ordered by ID |
| Same-position delete | Second becomes no-op | Both delete same char (idempotent) |
| Complexity | O(N) transforms per op (N = concurrent ops) | O(1) per op, but O(N) metadata |

---

## Google Docs vs Figma -- Real-World Comparison

### Google Docs (OT + CP)

```
  Architecture:
  +--------+     +--------+     +--------+
  | Alice  |---->| Google |<----| Bob    |
  | Client |<----| Server |---->| Client |
  +--------+     +--------+     +--------+
                     |
               Source of Truth
               Total Ordering
               OT Transforms

  - Server assigns version numbers (total order)
  - Server transforms operations (OT)
  - Clients send ops, receive transformed ops
  - Offline: queue ops, reconcile on reconnect
  - Strong consistency: all users see same document at all times
  - Single point of failure (mitigated by server redundancy)
```

| Property | Google Docs Implementation |
|----------|--------------------------|
| Algorithm | OT (Jupiter protocol variant) |
| CAP | CP -- consistency over availability |
| Server role | Central authority, transforms and orders ops |
| Offline | Limited -- queues ops, but reconnect can be slow |
| Scale | Per-document -- each doc has one authoritative server |
| History | Full operation log, reconstructable |
| Latency | ~50-100ms round trip to server |

### Figma (CRDT + AP)

```
  Architecture:
  +--------+          +--------+
  | Alice  |<-------->| Bob    |
  | Client |    |     | Client |
  +--------+    |     +--------+
       |        |         |
       v        v         v
  +--------+--------+--------+
  |       Figma Server       |
  |   (relay + persistence)  |
  |   (NOT source of truth)  |
  +-------------------------+

  - Each client has a full copy of the document
  - Changes identified by unique IDs, not positions
  - Server relays and persists, but does NOT transform
  - Clients merge independently -- math guarantees convergence
  - Works offline -- sync when reconnected
```

| Property | Figma Implementation |
|----------|---------------------|
| Algorithm | CRDT (custom, based on RGA concepts) |
| CAP | AP -- availability over strict consistency |
| Server role | Relay and persistence, NOT authority |
| Offline | Full support -- edit offline, merge on reconnect |
| Scale | Per-client -- each client is independent |
| History | Implicit in CRDT state (tombstones) |
| Latency | ~0ms local (immediate), async sync |

### Head-to-Head Comparison

| Dimension | Google Docs (OT+CP) | Figma (CRDT+AP) |
|-----------|--------------------|-----------------| 
| Consistency model | Strong (server-ordered) | Eventual (converges after sync) |
| Offline editing | Limited (queues ops) | Full (local CRDT copy) |
| Server dependency | High (server transforms) | Low (server relays only) |
| Latency feel | 50-100ms (round trip) | Instant (local first) |
| Implementation complexity | Moderate (transform logic) | High (CRDT data structures) |
| Memory overhead | Low (positions are integers) | Higher (unique IDs per char) |
| Undo complexity | Simple (inverse op) | Complex (commutative undo) |
| Battle-tested | 15+ years at Google scale | 5+ years at Figma scale |
| Best for | Text documents (sequential) | Design tools (spatial + text) |

---

## Consistency Spectrum by Feature

| Feature | Consistency Model | Staleness Tolerance | Store | Why |
|---------|------------------|--------------------| ------|-----|
| Document content | CP (strong) | Zero | PostgreSQL | Divergence = data loss |
| Operation log | CP (strong) | Zero | Kafka + PostgreSQL | Must be complete and ordered |
| Version snapshots | CP (strong) | Zero | PostgreSQL + S3 | Must match actual document state |
| Access permissions | CP (strong) | Zero | PostgreSQL | Security-critical, always check latest |
| Cursor positions | AP (eventual) | 1-2 seconds | Redis (TTL 30s) | Stale cursor is cosmetic, not harmful |
| Active user list | AP (eventual) | 5-10 seconds | Redis (TTL 60s) | "Who's online" can lag |
| Typing indicators | AP (eventual) | 2-3 seconds | Redis (TTL 5s) | "Alice is typing" can be brief stale |
| Comment threads | AP (eventual) | 1-5 seconds | PostgreSQL + cache | Comments can be seconds behind |
| Notification badges | AP (eventual) | Minutes | PostgreSQL + push | "3 new edits" can lag |

---

## Partition Handling -- Step by Step

### OT System During Partition

```
  Scenario: Network partition between Alice's region and Bob's region

  +--------+          PARTITION          +--------+
  | Alice  |      X X X X X X X X       | Bob    |
  | Client |---->| Server A |    | Server B |<----| Client |
  +--------+     +----------+    +----------+     +--------+

  Timeline:
  t=0   Partition starts. Server A and Server B cannot communicate.
  t=1   Alice sends INSERT("X") to Server A. Server A accepts (CP: it is primary).
  t=2   Bob sends INSERT("Y") to Server B. Server B REJECTS (CP: it is not primary).
  t=3   Bob sees "reconnecting..." in his editor.
  t=4   Bob's client queues his operation locally.
  t=10  Partition heals. Server B syncs with Server A.
  t=11  Bob's queued op is sent to Server A (now reachable).
  t=12  Server A transforms Bob's op against Alice's op.
  t=13  Both users converge to the same document.

  Key: Bob could NOT edit during the partition (CP trade-off).
       Alice COULD edit because she was on the primary server.
```

### CRDT System During Partition

```
  Scenario: Same partition, but using CRDT

  +--------+          PARTITION          +--------+
  | Alice  |      X X X X X X X X       | Bob    |
  | Client |                             | Client |
  +--------+                             +--------+
  (local CRDT)                           (local CRDT)

  Timeline:
  t=0   Partition starts. Alice and Bob cannot communicate.
  t=1   Alice inserts "X" (charID: alice-42). Applied locally immediately.
  t=2   Bob inserts "Y" (charID: bob-17). Applied locally immediately.
  t=3   Both users continue editing their local copies. No "reconnecting..."
  t=10  Partition heals. Alice and Bob exchange their ops.
  t=11  Alice receives Bob's "Y" (charID: bob-17). Merges into her CRDT.
  t=12  Bob receives Alice's "X" (charID: alice-42). Merges into his CRDT.
  t=13  Both CRDTs converge to the same state (commutativity guarantees this).

  Key: Both users could edit during the partition (AP trade-off).
       Convergence happened after the partition healed (eventual consistency).
```

---

## Interview Decision Framework

### When to Pick OT (CP)

```
Pick OT when:
  [x] Document content must be strongly consistent at all times
  [x] You have a reliable, low-latency server infrastructure
  [x] Offline editing is NOT a core requirement
  [x] Undo/redo must be simple and predictable
  [x] You need total ordering for audit/compliance
  [x] Team is familiar with transform algorithms

Example: Google Docs, Microsoft Word Online, Notion
```

### When to Pick CRDT (AP)

```
Pick CRDT when:
  [x] Offline editing is a CORE requirement
  [x] You want peer-to-peer without server dependency
  [x] You can tolerate eventual consistency (brief divergence)
  [x] Memory overhead of unique character IDs is acceptable
  [x] You need partition tolerance over strict consistency
  [x] Document type is spatial (design tools, whiteboards)

Example: Figma, Linear, Apple Notes, Ink & Switch tools
```

### Interview Quick-Reference

| Question | Answer |
|----------|--------|
| "Is your system CP or AP?" | "Split: CP for document state (convergence is non-negotiable), AP for presence/cursors (stale cursor is fine)." |
| "Why not AP for documents?" | "Document divergence means data loss. If Alice sees 'Hello' and Bob sees 'Help', which is correct? CP ensures one source of truth." |
| "Why not CP for cursors?" | "Blocking cursor updates during a partition is overkill. A cursor that is 2 seconds stale is invisible to users." |
| "OT or CRDT?" | "OT for text-heavy, server-backed apps (Google Docs). CRDT for offline-first, spatial apps (Figma). Our implementation uses OT with a central server." |
| "What about causal consistency?" | "OT uses server-assigned version numbers. CRDT uses vector clocks. Both ensure operations are applied in causal order." |
| "How does Google handle partitions?" | "CP: the primary server continues, replicas block. Clients on unreachable replicas see 'reconnecting' and queue ops locally." |

---

## Interview One-Liner (Full CAP Answer)

> "We split CAP by data type: CP for document state because convergence is
> non-negotiable -- all users MUST see the same document. AP for presence
> and cursors because a stale cursor position is cosmetic, not harmful.
> OT gives us CP via a central server that assigns total order and transforms
> operations. CRDT would give us AP with offline support, but we chose OT
> for simplicity and strong consistency, like Google Docs."
