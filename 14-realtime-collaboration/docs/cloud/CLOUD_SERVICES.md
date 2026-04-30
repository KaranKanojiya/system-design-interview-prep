# Real-Time Collaboration Tool (Google Docs) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway (WebSocket)** | API Gateway (WebSocket mode) + WAF | API Management (WebSocket) + Front Door | Cloud Endpoints + Apigee | WebSocket upgrade, auth, connection tracking, rate limiting |
| **Collaboration Service** | ECS/EKS (Fargate) | AKS | GKE | OT transform engine, operation ordering, conflict resolution |
| **Real-Time Subscriptions** | AppSync (GraphQL subscriptions) | SignalR Service | Firebase Realtime / Firestore | Alternative to raw WebSocket for presence + doc updates |
| **Relational DB (users, docs)** | RDS Aurora PostgreSQL | Azure SQL | Cloud SQL / AlloyDB | Document metadata, user profiles, permissions, sharing |
| **Operations Log** | DynamoDB (on-demand) | Cosmos DB | Firestore / Bigtable | Append-only operation log: INSERT/DELETE ops with baseVersion |
| **Cache (sessions, presence)** | ElastiCache Redis | Azure Cache for Redis | Memorystore (Redis) | Active cursors, user presence, session state, doc locks |
| **Document Snapshots** | S3 (Standard) | Blob Storage (Hot) | Cloud Storage (Standard) | Periodic full-document snapshots for version reconstruction |
| **Message Queue** | SQS + SNS | Service Bus + Event Grid | Pub/Sub | Operation fan-out, cross-service events, async processing |
| **WebSocket Connection Store** | DynamoDB (connectionId -> docId mapping) | Cosmos DB | Firestore | Track which connections are subscribed to which documents |
| **Presence Broadcast** | ElastiCache Redis Pub/Sub + API GW WebSocket | Azure SignalR | Firebase Presence | Cursor positions + colors broadcast to collaborators |
| **Version History** | DynamoDB (ops) + S3 (snapshots) | Cosmos DB + Blob Storage | Firestore + Cloud Storage | Reconstruct any version: snapshot + replay ops |
| **Search** | OpenSearch Service | Azure AI Search | Vertex AI Search | Full-text document search across all user documents |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | OT transform latency, WebSocket connection count, conflict rate |
| **DNS** | Route 53 (latency-based routing) | Traffic Manager | Cloud DNS | Multi-region, route to nearest WebSocket endpoint |

---

## Real-Time Collaboration Architecture on AWS (Numbered)

```
User A and User B are editing document doc_001 simultaneously.

    1. WebSocket Connection Establishment:
       Client A -> API Gateway (WebSocket mode):
         $connect route:
           - Lambda authorizer validates JWT token
           - Store connection: DynamoDB { connectionId: "abc123", userId: "U001", docId: "doc_001" }
           - Load document state from ElastiCache Redis (or DynamoDB if cache miss)
           - Return current document content + version number (e.g., version=42)
           - Broadcast presence: "User A joined" to all connections on doc_001
       |
       Client B -> same flow, gets connectionId "def456", same doc version=42
    |
    v
    2. User A types "Hello" at position 10:
       Client A sends via WebSocket:
         {
           type: "OPERATION",
           op: { type: "INSERT", position: 10, text: "Hello" },
           baseVersion: 42,
           clientId: "U001"
         }
       |
       -> API Gateway routes to $default Lambda / ECS handler
    |
    v
    3. OT Transform on Server (Collaboration Service -- ECS Fargate):
       a. Receive operation from User A
       b. Check baseVersion: client says 42, server is at 42 -> no transform needed
       c. Apply operation to server document state:
            doc = doc[:10] + "Hello" + doc[10:]
       d. Increment server version: 42 -> 43
       e. Store operation in DynamoDB ops log:
            PK=doc_001, SK=v#43, op=INSERT(10,"Hello"), userId=U001, timestamp=...
       f. Update cached document in ElastiCache Redis
    |
    v
    4. Broadcast to all collaborators:
       Query DynamoDB: all connectionIds where docId = "doc_001"
       -> connectionIds: ["abc123" (User A), "def456" (User B)]
       |
       API Gateway WebSocket POST to each connection:
         To User A (abc123): { type: "ACK", version: 43, serverOp: INSERT(10,"Hello") }
         To User B (def456): { type: "REMOTE_OP", version: 43, op: INSERT(10,"Hello"), userId: "U001" }
    |
    v
    5. CONCURRENT EDIT -- User B typed "World" at position 10 (same time as User A):
       Client B sends:
         { op: INSERT(10, "World"), baseVersion: 42 }
       |
       Server receives, but server is now at version 43 (User A's edit applied)
       -> baseVersion 42 < serverVersion 43 -> TRANSFORM NEEDED
    |
    v
    6. OT Transform:
       User B's op:    INSERT(10, "World")    baseVersion=42
       Server ops since v42: [INSERT(10, "Hello")]  (User A's op at v43)
       |
       Transform rule: INSERT vs INSERT at same position
         User A inserted "Hello" (5 chars) at position 10
         User B wants to insert "World" at position 10
         After transform: User B's op becomes INSERT(15, "World")
         (position shifted right by length of User A's insert)
       |
       Apply transformed op:
         doc = doc[:15] + "World" + doc[15:]
       Server version: 43 -> 44
       Store in DynamoDB ops log: PK=doc_001, SK=v#44
    |
    v
    7. Broadcast transformed operation:
       To User A: { type: "REMOTE_OP", version: 44, op: INSERT(15,"World") }
       To User B: { type: "ACK", version: 44, serverOp: INSERT(15,"World") }
       |
       Both clients now have identical document state.
       Document: "...original...HelloWorld...rest..."
    |
    v
    8. Presence Updates (every 50ms via WebSocket):
       Client A sends:
         { type: "PRESENCE", cursor: { position: 15, selectionEnd: 15 }, color: "#FF5733" }
       |
       -> ElastiCache Redis: HSET presence:doc_001 U001 '{"pos":15,"color":"#FF5733","ts":1234}'
       -> Broadcast to all other connections on doc_001
       -> Stale presence (no update for 30s) -> mark user as idle/disconnected
    |
    v
    9. Periodic Snapshot (every 100 operations or 5 minutes):
       Lambda triggered by DynamoDB Streams or CloudWatch timer:
         - Read current document state from ElastiCache Redis
         - Write snapshot to S3: s3://snapshots/doc_001/v#100/snapshot.json
         - Record snapshot pointer in DynamoDB: PK=doc_001, SK=snapshot#100
       |
       Purpose: fast version reconstruction
         Without snapshots: replay ALL ops from v#1 (thousands of ops)
         With snapshots: load nearest snapshot + replay remaining ops
    |
    v
    10. Version History Reconstruction:
        User requests "show me version from 2 hours ago":
          a. Find nearest snapshot before target time (e.g., snapshot at v#500)
          b. Load snapshot from S3: s3://snapshots/doc_001/v#500/snapshot.json
          c. Replay ops from DynamoDB: v#501 through v#523 (target version)
          d. Return reconstructed document state
          Time: < 200ms (snapshot + ~23 ops replay vs thousands without snapshot)
```

---

## WebSocket Scaling: API Gateway + Lambda vs ECS + Sticky Sessions

```
TWO APPROACHES TO WEBSOCKET AT SCALE:

=== OPTION A: API Gateway WebSocket + Lambda ===

    Client <-> API Gateway (WebSocket) <-> Lambda functions
    |
    Connection management: API Gateway handles it (managed)
    Connection state: DynamoDB (connectionId -> docId mapping)
    Broadcast: Lambda queries DynamoDB for connections, POSTs to each via API GW Management API
    |
    PROS:
      - Zero server management (fully serverless)
      - Auto-scales to millions of connections
      - Pay per message ($1.00 per million messages)
      - No sticky sessions needed
    |
    CONS:
      - Lambda cold start: 100-500ms (bad for real-time collaboration)
      - API GW Management API for broadcast: 1 HTTP call per recipient
        10 collaborators = 10 API calls per operation (slow, expensive)
      - No in-memory document state: must read/write DynamoDB + Redis per operation
      - Max WebSocket payload: 128 KB (fine for text ops, not for large pastes)
      - Cost at scale: $1/million messages * 100M messages/day = $100/day per feature
    |
    BEST FOR: Low-traffic collaboration (< 10K concurrent documents)

=== OPTION B: ECS/EKS + Sticky Sessions (RECOMMENDED) ===

    Client <-> ALB (sticky sessions) <-> ECS Fargate containers
    |
    Connection management: Application handles WebSocket upgrade
    Connection state: In-memory (HashMap<connectionId, WebSocket>)
    Document state: In-memory per container (hot documents)
    Broadcast: Direct WebSocket push (in-memory, < 1ms)
    |
    PROS:
      - In-memory document state: OT transforms in < 1ms (no DB round-trip)
      - Direct WebSocket broadcast: push to all connections in-process (< 1ms)
      - No cold start: persistent containers, always warm
      - In-memory presence: cursor updates broadcast instantly
      - Batch DB writes: accumulate ops, flush to DynamoDB every 100ms
    |
    CONS:
      - Sticky sessions required: ALB routes all connections for a document to same container
      - Container failure: must re-establish connections, reload document from DB
      - Scaling: must shard documents across containers
      - Ops overhead: manage ECS tasks, health checks, deployment
    |
    BEST FOR: Real-time collaboration (THIS DESIGN)

    STICKY SESSION STRATEGY:
      ALB cookie: AWSALB cookie based on documentId hash
      All users editing doc_001 -> same ECS container
      Container holds doc_001 state in memory:
        - Current document text
        - Current version number
        - All active WebSocket connections
        - Presence state (cursors, colors)
        - Pending operations queue
      |
      If container has 4 vCPU / 8 GB RAM:
        Each document: ~50 KB average (text + metadata + connections)
        Capacity: ~100K documents per container
        At 1000 operations/second per container -> more than enough for collaboration

    FAILOVER (Numbered):
      1. Container X dies (holding doc_001 state)
      2. ALB detects health check failure (5 seconds)
      3. Clients receive WebSocket disconnect event
      4. Clients reconnect -> ALB routes to Container Y (new sticky target)
      5. Container Y loads doc_001 from DynamoDB (latest ops) + Redis (cached state)
      6. Container Y rebuilds in-memory state: load snapshot + replay recent ops
      7. Clients receive current document state + version number
      8. Collaboration resumes (total disruption: 5-10 seconds)
```

---

## Multi-Region Collaboration (Latency Challenges + CRDT Advantage)

```
PROBLEM: Users in New York and Tokyo editing the same document.
  Round-trip latency: NY <-> Tokyo = ~200ms
  OT requires server as source of truth -> one region hosts the "master"
  User in non-master region experiences 200ms delay per keystroke.

=== OT MULTI-REGION (Current Design -- Centralized Server) ===

                     +------------------+
                     |  Route 53 (DNS)  |
                     |  Latency-based   |
                     +--------+---------+
                              |
              +---------------+---------------+
              |                               |
    +---------v----------+         +----------v---------+
    |    us-east-1       |         |   ap-northeast-1   |
    |    (PRIMARY)       |         |   (REPLICA)        |
    |                    |         |                     |
    |  ALB + ECS         | <-----> |  ALB + ECS          |
    |  (OT Server)       |  sync   |  (Presence only)    |
    |  DynamoDB (ops)    |         |  DynamoDB (Global)  |
    |  Redis (state)     |         |  Redis (presence)   |
    +--------------------+         +---------------------+

    FLOW (Numbered):

    1. User in NY edits document:
       WebSocket -> us-east-1 ECS (local, ~10ms)
       OT transform applied locally
       Broadcast to NY collaborators: ~10ms
       Replicate op to ap-northeast-1: ~200ms
       Tokyo user sees the edit after ~210ms total

    2. User in Tokyo edits document:
       WebSocket -> ap-northeast-1 ALB
       ALB forwards to us-east-1 ECS (cross-region, ~200ms)
       OT transform applied on us-east-1 master
       Broadcast to Tokyo user: ~200ms return
       Total latency for Tokyo user: ~400ms round-trip (NOTICEABLE)

    3. Presence (cursors) handled regionally:
       Tokyo cursor updates -> ap-northeast-1 Redis -> local broadcast (10ms)
       Replicate to us-east-1 asynchronously (eventual consistency OK for cursors)

    PROBLEM: 400ms round-trip for non-local users is noticeable.
    Google Docs solves this with LOCAL PREDICTION:
      Client applies op optimistically (instant local feedback)
      Server confirms or rejects (400ms later)
      If rejected (conflict): client rolls back + applies server version
      This makes it FEEL instant, but occasional flicker on conflicts.

=== CRDT MULTI-REGION (Alternative -- Decentralized, No Master) ===

    WHY CRDT SHINES FOR MULTI-REGION:
      CRDT operations are commutative and idempotent.
      No server needed as source of truth.
      Each region applies operations independently.
      Operations replicate asynchronously and ALWAYS converge.
      No transform needed -- the data structure resolves conflicts by design.

              +------------------+
              |  Route 53 (DNS)  |
              +--------+---------+
                       |
           +-----------+-----------+
           |                       |
    +------v-------+       +-------v------+
    |  us-east-1   |       | ap-northeast |
    |  ECS (CRDT)  | <---> | ECS (CRDT)   |
    |  DynamoDB    |  async| DynamoDB     |
    |  (Global     |  sync | (Global      |
    |   Tables)    |       |  Tables)     |
    +--------------+       +--------------+

    FLOW (Numbered):

    1. User in NY types at position 10:
       -> Apply to local CRDT immediately (0ms perceived latency)
       -> Broadcast to NY collaborators via local WebSocket (10ms)
       -> Replicate operation to Tokyo region (200ms, async)

    2. User in Tokyo types at position 10 (concurrent):
       -> Apply to local CRDT immediately (0ms perceived latency)
       -> Broadcast to Tokyo collaborators (10ms)
       -> Replicate to NY region (200ms, async)

    3. Both regions receive each other's operations:
       -> CRDT merge: deterministic resolution (e.g., lower userId wins tie)
       -> Both regions converge to IDENTICAL state
       -> No transform, no rollback, no flicker

    CRDT TRADE-OFFS:
      + Zero perceived latency in any region (apply locally first)
      + No single point of failure (no master)
      + Natural multi-region (designed for distribution)
      - Higher storage overhead (CRDT metadata per character: ~5x text size)
      - More complex data structure (Yjs, Automerge libraries)
      - Harder to reason about for simple use cases
      - Undo/redo is more complex with CRDTs

    WHEN TO USE WHICH:
      OT:   Google Docs (centralized, strong consistency, simpler mental model)
      CRDT: Figma (distributed, multi-region, offline-first)
```

---

## Cost Estimation (10K Concurrent Documents, 100K DAU)

### Assumptions

```
Daily Active Users:               100,000 (100K DAU)
Concurrent documents being edited: 10,000
Average collaborators per document: 3
Average operations per user/min:    30 (typing + cursor moves)
Total operations per minute:        100K * 30 = 3M ops/min
WebSocket messages per day:         3M * 60 * 8 hours active = 1.44B messages/day
Average document size:              50 KB
Snapshots per document per day:     50 (every 100 ops or 5 min)
Total snapshots per day:            10K docs * 50 = 500K snapshots
Presence updates:                   Every 50ms per active user = 20/sec * 100K * 8hr = 57.6B/day
  (batched to every 500ms for broadcast -> 5.76B presence messages/day)
```

### Monthly Cost Breakdown (AWS -- ECS + Sticky Sessions)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **API Gateway (WebSocket)** | Not used (ECS handles WebSocket directly) | $0 |
| **ALB (Application Load Balancer)** | 2 ALBs (multi-AZ), sticky sessions, WebSocket support | ~$500 |
| **ECS Fargate (Collaboration Service)** | 50 tasks, 4 vCPU / 8 GB each. Each handles ~200 concurrent docs. In-memory OT + presence. | ~$22,000 |
| **DynamoDB (operations log)** | Write: 50K WCU (ops ingestion). Read: 10K RCU (reconnect, history). Storage: ~500 GB/month growing | ~$8,500 |
| **DynamoDB (connection store)** | Write: 5K WCU (connect/disconnect). Read: 20K RCU (broadcast lookups) | ~$2,000 |
| **ElastiCache Redis (document cache + presence)** | 10 shards, r6g.xlarge, 1 replica each. Hot doc state + cursor positions. | ~$8,500 |
| **S3 (document snapshots)** | 500K snapshots/day * 50 KB = 25 GB/day. Monthly: ~750 GB. Plus history retention. | ~$25 |
| **RDS Aurora PostgreSQL (users, docs, permissions)** | db.r6g.2xlarge, Multi-AZ, 500 GB storage | ~$2,500 |
| **SQS (async events)** | Cross-service events, notification triggers. ~50M messages/month | ~$20 |
| **CloudWatch + X-Ray** | Metrics, logs, traces for collaboration latency monitoring | ~$500 |
| **Route 53** | DNS, health checks | ~$50 |
| **Total** | | **~$44,600/month** |

### Cost at Different Scales

| Scale | DAU | Concurrent Docs | Monthly Cost | Cost/DAU |
|-------|-----|-----------------|-------------|---------|
| Startup | 1K | 100 | ~$3,000 | $3.00 |
| Growth | 10K | 1,000 | ~$12,000 | $1.20 |
| Scale | 100K | 10,000 | ~$45,000 | $0.45 |
| Google Docs-scale | 10M | 1,000,000 | ~$2,500,000 | $0.25 |

### Cost Optimization Strategies

1. **Batch DynamoDB Writes** -- Don't write every operation individually. Buffer 100ms of ops in memory, batch-write. Reduces DynamoDB WCU by 10x.
2. **Snapshot-Based Reconstruction** -- Snapshots every 100 ops mean you never replay more than 99 ops. Without snapshots, reconstruction scans entire ops log (thousands of ops).
3. **Presence Throttling** -- Cursor updates every 50ms locally, but broadcast to other users every 500ms. Reduces WebSocket messages by 10x with imperceptible quality loss.
4. **Cold Document Eviction** -- Documents not edited for 5 minutes: evict from ECS memory, close WebSocket idle connections. Re-load from Redis/DynamoDB on next edit.
5. **Operations Log TTL** -- After creating a snapshot, ops before the snapshot are needed only for version history. Move to S3 after 7 days, delete after 90 days.
6. **Spot Instances for Background Tasks** -- Snapshot generation, version history reconstruction, analytics -- all batch-safe and retryable.

---

## Interview Tip

> "For a real-time collaboration tool like Google Docs on AWS, I'd use **ECS Fargate with sticky sessions** behind an ALB for WebSocket connections -- all users editing the same document route to the same container, which holds the document state **in-memory** for sub-millisecond OT transforms. Operations are **INSERT(pos, text)** and **DELETE(pos, length)**, each carrying a **baseVersion** for OT transformation. When concurrent edits arrive, the server transforms them using OT rules: INSERT vs INSERT shifts position by the earlier insert's length; INSERT vs DELETE adjusts for the deleted range. Transformed operations are broadcast to all collaborators via **WebSocket** (< 10ms). **Presence** (cursor positions + colors) is broadcast every 500ms via **Redis Pub/Sub**. The operation log lives in **DynamoDB** (append-only, PK=docId, SK=version). **S3** stores periodic snapshots every 100 operations for fast version reconstruction -- load nearest snapshot + replay remaining ops instead of replaying thousands. The system is **CP for document operations** (convergence is critical -- all users must see the same text) and **AP for presence** (a stale cursor position is acceptable). For multi-region, OT requires a centralized server (one region is master), but **client-side prediction** makes edits feel instant even at 200ms cross-region latency."

This shows you understand **OT transforms, WebSocket architecture, in-memory state with sticky sessions, operation logging, snapshot-based version history, and the CAP trade-off between document consistency and presence availability** -- the six pillars of real-time collaboration design.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| WebSocket connections | ALB + ECS Fargate | Sticky sessions by docId hash | In-memory doc state, sub-ms OT transforms, direct broadcast |
| OT transform engine | ECS Fargate (in-memory) | 4 vCPU / 8 GB per task, 200 docs/task | Transform must be fast (< 1ms), in-memory is non-negotiable |
| Operation log | DynamoDB (on-demand) | PK=docId, SK=version, append-only | High write throughput, ordered by version, infinite scale |
| Document cache | ElastiCache Redis | Hot document state + presence | Sub-ms reads for reconnect, presence Pub/Sub |
| Document snapshots | S3 Standard | Every 100 ops or 5 minutes | Cheap storage, fast reconstruction, version history |
| User/doc metadata | RDS Aurora PostgreSQL | Multi-AZ, read replicas | Relational data: users, permissions, sharing, folders |
| Presence broadcast | Redis Pub/Sub + WebSocket | Channel per document | Real-time cursor positions, 500ms broadcast interval |
| Connection tracking | DynamoDB | connectionId -> docId mapping | Survives container restart, enables cross-container broadcast |
| Async events | SQS + SNS | Notification triggers, analytics | Decouple collaboration from notifications, search indexing |
| Monitoring | CloudWatch + X-Ray | OT latency, connection count | Alert on transform latency > 50ms, connection drops |
