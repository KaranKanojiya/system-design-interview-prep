# Caching Strategy -- Real-time Collaboration Tool (Google Docs)

> Every cache layer in the system, from the document content cache to the
> operation log cache, presence cache, version history cache, and client-side
> optimistic cache. Interview-ready with Redis commands, TTL policies,
> invalidation strategies, and the full request flow through all cache layers.
>
> **Key insight:** Unlike most systems where caching is about read performance,
> in real-time collaboration **caching is about write performance** -- every
> keystroke triggers OT transform lookups against recent operations. The
> operation log cache (Redis Sorted Set) is THE critical cache layer.

---

## Cache Layer Overview

```
  User types a character -> Operation sent over WebSocket
       |
       v
  +---------------------------------------------------+
  | Operation Log Cache (Redis Sorted Set)             |  THE CRITICAL CACHE
  | Key: ops-cache:{docId}                             |
  | Recent operations for OT transform lookups         |
  | TTL: 5 minutes, refreshed on every write           |
  +---------------------------------------------------+
       | miss (rare -- only for stale docs)
       v
  +---------------------------------------------------+
  | PostgreSQL operations table                        |
  | SELECT * FROM operations                           |
  | WHERE document_id = ? AND version > ?              |
  +---------------------------------------------------+

  User opens a document -> GET document content
       |
       v
  +---------------------------------------------------+
  | Document Cache (Redis String)                      |
  | Key: doc-cache:{docId}                             |
  | Latest document content as JSON                    |
  | TTL: 60 seconds, invalidated on EVERY edit         |
  +---------------------------------------------------+
       | miss
       v
  +---------------------------------------------------+
  | PostgreSQL documents table                         |
  | Source of truth for document content               |
  +---------------------------------------------------+

  User sees other users' cursors -> Presence query
       |
       v
  +---------------------------------------------------+
  | Presence Cache (Redis Hash)                        |
  | Key: presence:{docId}                              |
  | Cursor positions, active users, typing indicators  |
  | TTL: 30 seconds, refreshed by heartbeat            |
  +---------------------------------------------------+
       | (no fallback -- presence IS the cache)

  User clicks "Version History" -> GET version
       |
       v
  +---------------------------------------------------+
  | Version Snapshot Cache (Redis String)              |
  | Key: snapshot:{docId}:{version}                    |
  | Cached document snapshots                          |
  | TTL: 1 hour                                        |
  +---------------------------------------------------+
       | miss
       v
  +---------------------------------------------------+
  | PostgreSQL document_versions table                 |
  | + S3 for large snapshots                           |
  +---------------------------------------------------+
```

---

## 1. Operation Log Cache -- THE Critical Cache

### Why This Is THE Cache

Every single edit requires looking up concurrent operations for OT transform.
Without this cache, every keystroke hits PostgreSQL. At 30 ops per user per
minute with 10 users, that is 300 PostgreSQL queries per minute per document.
With the cache, it is 0 PostgreSQL queries during active editing.

### Redis Data Model

```
  Key:    ops-cache:{documentId}
  Type:   Sorted Set (score = server version number)
  TTL:    5 minutes (refreshed on every write)

  Members: JSON-serialized operations
  Score:   server version number (monotonic, unique per document)
```

### Redis Commands

```
  -- Write: after transforming and applying an operation
  ZADD ops-cache:doc-42 15 '{"type":"INSERT","pos":7,"content":"X","userId":"alice","ver":15}'
  EXPIRE ops-cache:doc-42 300   -- refresh TTL to 5 minutes

  -- Read: fetch concurrent ops for OT transform
  -- "Give me all ops with version > 10" (client's last known version)
  ZRANGEBYSCORE ops-cache:doc-42 11 +inf

  -- Returns:
  -- 1) '{"type":"INSERT","pos":3,"content":"Y","userId":"bob","ver":11}'
  -- 2) '{"type":"DELETE","pos":7,"content":"Z","userId":"carol","ver":12}'
  -- 3) ... (all ops the client has not yet seen)

  -- Cleanup: remove very old ops (keep last 1000)
  ZREMRANGEBYRANK ops-cache:doc-42 0 -1001
```

### Numbered Call Chain -- OT Transform with Cache

```
1.  Alice sends INSERT("X", pos=5, clientVersion=10) over WebSocket
2.  CollaborationService calls OperationService.getConcurrentOps("doc-42", 10)
3.  OperationService checks Redis: ZRANGEBYSCORE ops-cache:doc-42 11 +inf
4.  CACHE HIT: Redis returns [bobOp(ver=11), carolOp(ver=12)]
5.  OperationService calls SyncStrategy.transform(aliceOp, [bobOp, carolOp])
6.  OT transforms Alice's position: 5 -> 6 (Bob inserted at pos=3) -> 6 (Carol's delete was after)
7.  OperationService applies transformed op to document
8.  OperationService writes new op to Redis: ZADD ops-cache:doc-42 13 '{...}'
9.  OperationService writes to Kafka (durable log) and PostgreSQL (async)
10. BroadcastService pushes transformed op to all connected users
```

### Cache Miss Flow

```
1.  Alice opens a document she has not edited in 2 hours
2.  Alice's client has clientVersion=500, server is at version=750
3.  OperationService checks Redis: ZRANGEBYSCORE ops-cache:doc-42 501 +inf
4.  CACHE MISS: Redis key expired (TTL was 5 minutes, doc idle for 2 hours)
5.  OperationService falls back to PostgreSQL:
    SELECT * FROM operations WHERE document_id = 'doc-42' AND version > 500
    ORDER BY version ASC
6.  PostgreSQL returns 250 operations
7.  OperationService backfills Redis:
    ZADD ops-cache:doc-42 501 '{...}' 502 '{...}' ... 750 '{...}'
    EXPIRE ops-cache:doc-42 300
8.  OT transforms Alice's op against all 250 concurrent ops
9.  (In practice: send Alice the full document at version 750 instead --
     cheaper than transforming 250 ops)
```

### Cache Sizing

| Metric | Value | Calculation |
|--------|-------|-------------|
| Ops per active document per minute | 150-1500 | 5-50 users * 30 ops/min |
| Op JSON size | ~100 bytes | type + pos + content + userId + version |
| Cache size per doc (5-min window) | 75-750 KB | 750-7500 ops * 100 bytes |
| Active documents per server | 1,000-5,000 | Depends on shard size |
| Total Redis memory for ops cache | 75 MB - 3.75 GB | 1000-5000 docs * 75-750 KB |

---

## 2. Document Cache -- Latest Version in Redis

### Why Cache the Document?

When a user first opens a document, we need to send them the full current
content. Without caching, every document open hits PostgreSQL. With caching,
we serve from Redis (sub-millisecond).

**Critical:** Unlike most caches, this one is **invalidated on EVERY edit**.
Documents change on every keystroke, so the cache TTL is a safety net, not
the primary invalidation mechanism.

### Redis Data Model

```
  Key:    doc-cache:{documentId}
  Type:   String (JSON)
  TTL:    60 seconds (safety net -- primary invalidation is explicit)

  Value: {
    "id": "doc-42",
    "title": "Meeting Notes",
    "content": "Hello World...",
    "version": 750,
    "updatedAt": "2024-01-15T10:30:00Z"
  }
```

### Redis Commands

```
  -- Write: after applying an operation (invalidate + rewrite)
  SET doc-cache:doc-42 '{"id":"doc-42","content":"Hello WorldX...","version":751}'
  EXPIRE doc-cache:doc-42 60

  -- Read: when a user opens a document
  GET doc-cache:doc-42

  -- Invalidate: on every edit (before writing new value)
  DEL doc-cache:doc-42
  -- Then SET with new content (or let it be lazily populated)
```

### Invalidation Strategy: Write-Through

```
  Every edit flow:
  1. OT transform the incoming operation
  2. Apply to in-memory document
  3. Write to Kafka (durable log)
  4. Write to Redis doc-cache (update cache)    <-- write-through
  5. Broadcast to connected users

  Why write-through (not write-behind)?
  - The next user who opens the doc should see the latest content
  - Write-through ensures Redis is always current
  - 60s TTL is a safety net for edge cases (server crash before cache update)
```

### ASCII Diagram -- Document Cache Flow

```
  User opens document "doc-42"
       |
       v
  Check Redis: GET doc-cache:doc-42
       |
       +-- HIT --> Return cached document (sub-ms)
       |
       +-- MISS --> Query PostgreSQL: SELECT * FROM documents WHERE id = 'doc-42'
                    |
                    +-- Populate cache: SET doc-cache:doc-42 '...' EX 60
                    |
                    +-- Return document to user
```

### When NOT to Use This Cache

```
  DO NOT serve the document cache for users who are already connected
  and actively editing. They have a local copy that is kept in sync
  via WebSocket operations.

  The document cache is ONLY for:
  1. First load when a user opens a document
  2. Reconnection after a disconnect (to get the latest state quickly)
  3. API calls from external services (e.g., search indexing, export)
```

---

## 3. Presence Cache -- Redis with Short TTL

### Why Presence is Cache-Only

Presence data (cursors, active users, typing indicators) is ephemeral.
There is no "source of truth" in PostgreSQL. Redis IS the source of truth
for presence. If Redis loses the data, users simply re-send their presence
on the next heartbeat.

### Redis Data Model

```
  Key:    presence:{documentId}
  Type:   Hash (field = userId, value = JSON cursor info)
  TTL:    30 seconds (refreshed by heartbeat every 10 seconds)

  Fields:
    alice -> '{"cursor":42,"color":"#FF6B6B","name":"Alice","lastSeen":1700000000}'
    bob   -> '{"cursor":17,"color":"#4ECDC4","name":"Bob","lastSeen":1700000001}'

  Key:    typing:{documentId}
  Type:   Set (members = userIds)
  TTL:    5 seconds (auto-expires when user stops typing)
```

### Redis Commands

```
  -- User moves cursor
  HSET presence:doc-42 alice '{"cursor":42,"color":"#FF6B6B","lastSeen":1700000050}'
  EXPIRE presence:doc-42 30    -- refresh TTL

  -- Heartbeat (every 10 seconds, same command)
  HSET presence:doc-42 alice '{"cursor":42,"color":"#FF6B6B","lastSeen":1700000060}'
  EXPIRE presence:doc-42 30

  -- Get all active users and cursors for a document
  HGETALL presence:doc-42
  --> 1) "alice"
  --> 2) '{"cursor":42,"color":"#FF6B6B","lastSeen":1700000050}'
  --> 3) "bob"
  --> 4) '{"cursor":17,"color":"#4ECDC4","lastSeen":1700000051}'

  -- User leaves (explicit)
  HDEL presence:doc-42 alice

  -- User leaves (implicit -- no heartbeat for 30 seconds)
  -- Redis TTL auto-expires the entire hash

  -- Typing indicator
  SADD typing:doc-42 alice
  EXPIRE typing:doc-42 5        -- auto-clear after 5 seconds

  SMEMBERS typing:doc-42
  --> 1) "alice"
```

### Numbered Call Chain -- Presence Update

```
1.  Alice moves her cursor to position 42
2.  Client sends cursor update over WebSocket: {type:"cursor", pos:42}
3.  CollaborationService calls PresenceService.updateCursor("doc-42", "alice", 42)
4.  PresenceService writes to Redis: HSET presence:doc-42 alice '{"cursor":42,...}'
5.  PresenceService refreshes TTL: EXPIRE presence:doc-42 30
6.  CollaborationService calls BroadcastService.broadcastCursor("doc-42", "alice", 42)
7.  BroadcastService pushes cursor update to Bob and Carol over WebSocket
8.  Bob and Carol's clients render Alice's cursor at position 42
```

### Presence Staleness -- Why AP Is OK

```
  Scenario: Redis replication lag of 2 seconds

  t=0   Alice moves cursor to position 42
  t=0   Redis primary updated: cursor=42
  t=1   Bob reads from Redis replica: cursor=40 (stale by 2 positions)
  t=2   Redis replica catches up: cursor=42
  t=2   Bob reads from Redis replica: cursor=42 (now current)

  Impact: Bob sees Alice's cursor 2 positions behind for 2 seconds.
  User experience: imperceptible. Nobody notices a cursor that is
  2 characters off for 2 seconds.

  Compare to document staleness:
  t=0   Alice types "Hello"
  t=1   Bob sees "Hell" (missing the "o")
  t=2   Bob sees "Hello" (caught up)

  Impact: Bob's document is WRONG for 2 seconds. If Bob types based
  on "Hell" instead of "Hello", his edit is now semantically wrong.
  THIS is why document state needs CP, not AP.
```

### User Disconnect Detection

```
  How we detect a user left without explicit disconnect:

  1. Client sends heartbeat every 10 seconds:
     HSET presence:doc-42 alice '{"cursor":42,"lastSeen":NOW}'
     EXPIRE presence:doc-42 30

  2. If Alice's browser crashes or network drops:
     - No more heartbeats
     - After 30 seconds, Redis TTL expires the presence hash
     - HGETALL presence:doc-42 no longer returns Alice
     - BroadcastService periodically polls presence, detects Alice gone
     - Broadcasts "Alice left" to remaining users

  3. Explicit disconnect (tab closed):
     - Client sends "leave" message over WebSocket
     - Server: HDEL presence:doc-42 alice
     - Immediate removal, no 30-second wait
```

---

## 4. Version History Cache -- Snapshots Cached, Ops Reconstructed on Demand

### Why Cache Snapshots?

When a user clicks "Version History," they want to browse snapshots quickly.
The most recent snapshots are cached in Redis. Older snapshots are fetched
from PostgreSQL (or S3 for very large documents).

### Redis Data Model

```
  Key:    snapshot:{documentId}:{version}
  Type:   String (JSON)
  TTL:    1 hour

  Value: {
    "documentId": "doc-42",
    "version": 200,
    "content": "Hello World... (full document content)",
    "createdAt": "2024-01-15T10:00:00Z",
    "createdBy": "alice"
  }
```

### Redis Commands

```
  -- Cache a snapshot after creating it (every 100 ops)
  SET snapshot:doc-42:200 '{"documentId":"doc-42","version":200,...}'
  EXPIRE snapshot:doc-42:200 3600   -- 1 hour TTL

  -- Fetch a cached snapshot
  GET snapshot:doc-42:200

  -- Cache miss -> fetch from PostgreSQL
  SELECT * FROM document_versions
  WHERE document_id = 'doc-42' AND version = 200;
```

### Numbered Call Chain -- User Browses Version History

```
1.  User clicks "Version History" in the UI
2.  Client sends GET /api/documents/doc-42/versions
3.  Server queries PostgreSQL for snapshot list:
    SELECT version, created_at, created_by FROM document_versions
    WHERE document_id = 'doc-42' ORDER BY version DESC LIMIT 20
4.  Returns: [v200, v100, v0] (snapshots every 100 ops)
5.  User clicks on version 200
6.  Server checks Redis: GET snapshot:doc-42:200
7.  CACHE HIT: return cached snapshot content
8.  User sees the document as it was at version 200
9.  User clicks on version 150 (NOT a snapshot -- between v100 and v200)
10. Server finds nearest snapshot: v100 (from cache or PostgreSQL)
11. Server fetches ops 101-150 from PostgreSQL (or Redis ops-cache if recent)
12. Server replays 50 ops on top of v100 snapshot
13. Server returns reconstructed document at version 150
14. Server optionally caches the reconstructed result:
    SET snapshot:doc-42:150 '...' EX 3600
```

### Version Reconstruction Cost

```
  With snapshots every 100 ops:
  +----+----+----+----+----+----+----+----+
  |  0 | 100| 200| 300| 400| 500| 600| 700|   <-- cached snapshots
  +----+----+----+----+----+----+----+----+
            ^         ^
            |         |
       User wants    User wants
       version 150   version 350

  Version 150: find v100 snapshot, replay 50 ops. Cost: O(50).
  Version 350: find v300 snapshot, replay 50 ops. Cost: O(50).
  Version 100: exact snapshot. Cost: O(1).
  Version 699: find v600 snapshot, replay 99 ops. Cost: O(99). WORST CASE.

  Without snapshots:
  Version 150: replay 150 ops from empty doc. Cost: O(150).
  Version 350: replay 350 ops. Cost: O(350).
  Version 699: replay 699 ops. Cost: O(699). Getting slow.
  Version 50000: replay 50000 ops. Cost: O(50000). VERY slow.
```

---

## 5. Client-Side Cache -- Local Document Copy and Optimistic Updates

### Why Client-Side Cache?

The client maintains a local copy of the document and applies edits
optimistically (before server confirmation). This gives instant feedback --
the user sees their character appear immediately, not after a 50-100ms
round trip to the server.

### Client Cache Architecture

```
  +-------------------------------------------------------+
  |                  CLIENT (Browser)                      |
  |                                                        |
  |  +------------------+     +------------------------+   |
  |  | Local Document   |     | Pending Operations     |   |
  |  | Copy             |     | Queue                  |   |
  |  |                  |     |                        |   |
  |  | "Hello WorldX"   |     | [INSERT("X", pos=11,  |   |
  |  | version: 750     |     |   clientVer=750)]      |   |
  |  |                  |     |                        |   |
  |  | Applied locally  |     | Sent to server,       |   |
  |  | immediately      |     | awaiting ACK           |   |
  |  +------------------+     +------------------------+   |
  |                                                        |
  |  +------------------+     +------------------------+   |
  |  | Incoming Ops     |     | IndexedDB              |   |
  |  | Buffer           |     | (Offline Queue)        |   |
  |  |                  |     |                        |   |
  |  | [ops from server |     | Ops created while      |   |
  |  |  waiting to be   |     | offline, sent on       |   |
  |  |  applied]        |     | reconnect              |   |
  |  +------------------+     +------------------------+   |
  +-------------------------------------------------------+
```

### Optimistic Update Flow

```
  Alice types "X" at position 11:

  1. Client applies INSERT("X", pos=11) to local document IMMEDIATELY
     Local doc: "Hello World" -> "Hello WorldX"
     User sees "X" appear instantly (0ms latency)

  2. Client adds op to pending queue and sends to server

  3. Server transforms, assigns version=751, broadcasts

  4. Client receives ACK (version=751)
     Remove op from pending queue

  5. If server transformed the position (e.g., pos=11 -> pos=12):
     Client rebases its local document against the server's transform
```

### Conflict Between Optimistic Update and Server Transform

```
  Alice's local doc: "Hello World" (version 750)
  Alice types "X" at pos=11 -> local: "Hello WorldX" (optimistic)

  Meanwhile, server receives Bob's INSERT("Y", pos=5) -> version 751
  Server sends Bob's transformed op to Alice

  Alice receives Bob's op BEFORE her own ACK:
  1. Alice must apply Bob's op to her local doc
  2. But Alice already applied her own op optimistically!
  3. Solution: transform Bob's op against Alice's pending op
     Bob: INSERT("Y", pos=5) -- Alice's pending: INSERT("X", pos=11)
     Bob's pos=5 < Alice's pos=11, so no transform needed
     Apply Bob's op: "Hello WorldX" -> "HelloY WorldX"
  4. When Alice's ACK arrives: remove from pending queue (already applied)
```

### Offline Queue (IndexedDB)

```
  Scenario: Alice loses internet while editing

  1. Alice types 50 characters while offline
  2. Each keystroke creates an Operation, applied to local doc immediately
  3. Operations queued in IndexedDB (persistent browser storage):
     IndexedDB: "offline-ops" store
     [{type:INSERT, pos:11, content:"X", clientVer:750},
      {type:INSERT, pos:12, content:"Y", clientVer:750},
      ...
      {type:INSERT, pos:60, content:"Z", clientVer:750}]

  4. Alice reconnects
  5. Client fetches server's current version: 780 (30 ops happened while offline)
  6. Client sends queued ops to server one by one
  7. Server transforms each against the 30 ops that happened during offline
  8. Server broadcasts transformed ops to other users
  9. Client clears IndexedDB offline queue
  10. Client receives all 30 ops it missed, applies to local doc

  Key insight: the 50 offline ops all have clientVersion=750 because Alice
  never received server updates while offline. The server must transform
  each against ALL ops from 750 to current (up to 80 ops for the last one).
```

### Client Cache Summary

| Cache Layer | Storage | Lifetime | Purpose |
|-------------|---------|----------|---------|
| Local document copy | In-memory (JS) | While tab is open | Instant rendering, optimistic updates |
| Pending operations queue | In-memory (JS) | Until ACK received | Track unconfirmed ops for rebasing |
| Incoming ops buffer | In-memory (JS) | Until applied | Buffer out-of-order server ops |
| Offline queue | IndexedDB | Until reconnect + sync | Persist ops across page reloads while offline |
| Document metadata | localStorage | 24 hours | Title, last cursor position, user prefs |

---

## 6. What NOT to Cache -- Security-Critical Data

### Permission Checks -- ALWAYS Hit the Database

```
  WRONG (cached permissions):
  +--------+     +-------+     +-----------+
  | Client |---->| Redis |---->| Check     |
  |        |     | perms |     | cached    |
  |        |     | cache |     | permission|
  +--------+     +-------+     +-----------+

  Scenario:
  1. Alice shares doc-42 with Bob (READ permission)
  2. Permission cached in Redis: "bob:doc-42:READ"
  3. Alice REVOKES Bob's access
  4. PostgreSQL updated, but Redis cache still has "bob:doc-42:READ"
  5. Bob can still read the document for up to TTL seconds!

  If the TTL is 5 minutes and the document contains sensitive data
  (salary info, medical records, legal documents), Bob has 5 minutes
  of unauthorized access after revocation.
```

```
  RIGHT (always check DB):
  +--------+     +------------+
  | Client |---->| PostgreSQL |
  |        |     | permissions|
  |        |     | table      |
  +--------+     +------------+

  Every document access:
  SELECT permission FROM document_permissions
  WHERE document_id = 'doc-42' AND user_id = 'bob';

  Revocation is immediate. No cache staleness window.
  Cost: ~1ms per query (indexed). Acceptable for security-critical checks.
```

### What Else NOT to Cache

| Data | Why Not Cache | Alternative |
|------|--------------|-------------|
| Permissions (read/write/admin) | Revocation must be immediate | Always query PostgreSQL |
| Authentication tokens | Revoked tokens must fail immediately | Verify against auth service |
| Document encryption keys | Cached key could be used after revocation | Fetch from KMS per session |
| Rate limit counters | Must be accurate to prevent abuse | Redis atomic counters (not cached reads) |
| Billing/usage meters | Under-counting loses revenue | Direct writes to billing DB |

### Permission Check in the Edit Flow

```
  1. Alice sends an edit operation over WebSocket
  2. BEFORE OT transform, check permission:
     SELECT permission FROM document_permissions
     WHERE document_id = 'doc-42' AND user_id = 'alice';
  3. Permission = WRITE? Proceed with OT transform.
     Permission = READ? Reject with "read-only" error.
     No permission? Reject with "access denied" and close WebSocket.
  4. NEVER cache this check. The document owner could revoke
     Alice's access between two consecutive keystrokes.
```

---

## 7. Cache Invalidation Strategies by Layer

### Summary Table

| Cache Layer | Key Pattern | TTL | Invalidation Trigger | Strategy |
|-------------|------------|-----|---------------------|----------|
| Operation log | `ops-cache:{docId}` | 5 min | Never explicitly invalidated (append-only) | TTL expiry + LRU eviction |
| Document content | `doc-cache:{docId}` | 60s | Every edit (write-through) | Write-through + TTL safety net |
| Presence/cursors | `presence:{docId}` | 30s | Heartbeat refresh | TTL expiry (no explicit invalidation) |
| Typing indicators | `typing:{docId}` | 5s | User stops typing | TTL auto-expire |
| Version snapshots | `snapshot:{docId}:{ver}` | 1h | Never (snapshots are immutable) | TTL eviction only |
| Client local doc | Browser memory | Tab lifetime | Server ops via WebSocket | Real-time sync |
| Client offline queue | IndexedDB | Until sync | Successful reconnect + ACK | Explicit clear after sync |

### Invalidation Decision Tree

```
  Is the data security-critical?
       |
       +-- YES --> Do NOT cache. Always query source of truth.
       |           (permissions, auth tokens, encryption keys)
       |
       +-- NO --> Is the data immutable?
                     |
                     +-- YES --> Cache with long TTL. No invalidation needed.
                     |           (version snapshots, completed ops)
                     |
                     +-- NO --> Is staleness tolerable?
                                   |
                                   +-- YES --> Cache with short TTL. AP model.
                                   |           (cursors: 30s, typing: 5s)
                                   |
                                   +-- NO --> Write-through cache. CP model.
                                              (document content: invalidate on every edit)
```

---

## 8. Cache Warming -- Preparing for Active Editing

### When a User Opens a Document

```
  1. User opens document "doc-42"
  2. Server loads document from doc-cache (Redis) or PostgreSQL
  3. Server checks: is ops-cache:doc-42 populated?
     |
     +-- YES --> Ready for OT transforms immediately
     |
     +-- NO --> Cache warming:
                a. Fetch last 100 operations from PostgreSQL
                b. ZADD ops-cache:doc-42 ... (backfill)
                c. EXPIRE ops-cache:doc-42 300
                d. Now ready for OT transforms
  4. Server loads presence: HGETALL presence:doc-42
  5. Server subscribes user to BroadcastService (Observer)
  6. Server sends to client:
     - Full document content (version 750)
     - Active users and cursor positions
     - Last 10 operations (for client-side OT catchup)
```

### Numbered Call Chain -- Document Open with Cache Warming

```
1.  Alice clicks "Open" on document "doc-42"
2.  Client opens WebSocket connection to /ws/documents/doc-42
3.  Server: check doc-cache:doc-42 (GET doc-cache:doc-42)
4.  CACHE HIT: parse JSON, get document at version 750
5.  Server: check ops-cache:doc-42 (EXISTS ops-cache:doc-42)
6.  CACHE MISS: document has been idle for 2 hours
7.  Server warms ops cache:
    SELECT * FROM operations WHERE document_id='doc-42'
    ORDER BY version DESC LIMIT 100
8.  Server backfills Redis:
    ZADD ops-cache:doc-42 651 '...' 652 '...' ... 750 '...'
    EXPIRE ops-cache:doc-42 300
9.  Server loads presence: HGETALL presence:doc-42
10. Returns: {} (no other users online -- doc was idle)
11. Server registers Alice's presence:
    HSET presence:doc-42 alice '{"cursor":0,"color":"#FF6B6B"}'
    EXPIRE presence:doc-42 30
12. Server subscribes Alice's WebSocket session as Observer
13. Server sends initial payload to Alice:
    {document: {..., version: 750}, users: [], recentOps: [...last 10...]}
14. Alice's client renders the document and starts heartbeat timer
```

---

## 9. Redis Memory Budget

### Per-Document Memory Estimate

| Cache Layer | Key | Est. Size | Docs | Total |
|-------------|-----|-----------|------|-------|
| Ops cache (5 min, ~750 ops) | `ops-cache:{docId}` | 75 KB | 5,000 active | 375 MB |
| Document cache | `doc-cache:{docId}` | 50 KB | 5,000 active | 250 MB |
| Presence | `presence:{docId}` | 1 KB | 5,000 active | 5 MB |
| Typing | `typing:{docId}` | 0.1 KB | 1,000 active | 0.1 MB |
| Snapshots (recent) | `snapshot:{docId}:{ver}` | 50 KB * 5 versions | 5,000 | 1.25 GB |
| Session metadata | `session:{sessionId}` | 0.5 KB | 50,000 users | 25 MB |
| **Total** | | | | **~1.9 GB** |

### Redis Cluster Configuration

```
  For 5,000 active documents, 50,000 connected users:

  Redis cluster: 3 nodes (primary) + 3 nodes (replica)
  Memory per node: 2 GB (6 GB total, ~1.9 GB used = 32% utilization)
  eviction-policy: allkeys-lru (evict least recently used if memory full)
  
  Key distribution:
  - Ops cache keys hashed by docId -> even distribution across slots
  - Presence keys hashed by docId -> co-located with ops cache
  - Session keys hashed by sessionId -> distributed evenly
```

---

## Interview Quick-Reference

| Question | Answer |
|----------|--------|
| "What is your most important cache?" | "Operation log cache (Redis Sorted Set). Every keystroke needs OT transform lookups against recent ops. Without it, every keystroke hits PostgreSQL." |
| "How do you cache document content?" | "Write-through to Redis on every edit. 60s TTL safety net. Used only for first load and reconnection, not for active editing." |
| "How do you handle presence?" | "Redis Hash with 30s TTL. Heartbeat every 10s refreshes TTL. No PostgreSQL backup -- presence is ephemeral." |
| "How do you cache version history?" | "Immutable snapshots cached in Redis with 1h TTL. Reconstruct non-snapshot versions by replaying ops from nearest snapshot." |
| "What about client-side caching?" | "Local document copy with optimistic updates. Pending ops queue for rebasing. IndexedDB for offline persistence." |
| "What do you NOT cache?" | "Permission checks. Revocation must be immediate. A 5-minute cache window on permissions could mean 5 minutes of unauthorized access to sensitive documents." |
| "How do you warm the cache?" | "When a user opens an idle document: backfill last 100 ops from PostgreSQL into Redis Sorted Set. Takes ~5ms." |
| "How much Redis memory?" | "~2 GB for 5,000 active documents and 50,000 connected users. Ops cache is the biggest consumer." |

---

## Interview One-Liner (Full Caching Answer)

> "Our caching has four layers: (1) operation log cache in Redis Sorted Set
> for sub-millisecond OT transform lookups on every keystroke, (2) document
> content cache with write-through invalidation for first-load, (3) presence
> cache in Redis Hash with 30-second TTL for cursor positions, and (4)
> client-side local copy with optimistic updates for instant feedback.
> We do NOT cache permission checks -- revocation must be immediate."
