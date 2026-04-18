# Technology Choices — Chat/Messaging System

> For each technology: why it was chosen, how it's configured, what the
> alternatives are, and what to say in an interview.

---

## Table of Contents

| Technology | Role |
|-----------|------|
| WebSocket | Real-time bidirectional communication |
| Kafka | Message ordering and async processing |
| Cassandra | Message storage (write-heavy, time-series) |
| Redis | Presence, connection registry, cache, offline queue |
| S3 / CDN | Media storage and delivery |
| FCM / APNs | Push notifications for offline users |
| PostgreSQL | User and group metadata |
| Load Balancer | Connection distribution |
| Observability | Metrics, monitoring, alerting |

---

## 1. WebSocket

### Why Not HTTP Polling?

```
  HTTP Polling (bad):
  Client ----GET /messages----> Server      (every 1s)
  Client <---200 [] -----------Server      (empty 99% of the time)
  Client ----GET /messages----> Server      (wasted request)
  Client <---200 [msg] --------Server      (finally a message)

  WebSocket (good):
  Client ----HTTP Upgrade-----> Server      (once)
  Client <===== persistent TCP connection =====> Server
         <--- msg pushed instantly when available ---
```

| Aspect | HTTP Polling | Long Polling | SSE | WebSocket |
|--------|-------------|-------------|-----|-----------|
| Direction | Client-to-server | Client-to-server | Server-to-client only | Full-duplex |
| Connection | New per request | Held open until event | Persistent, one-way | Persistent, two-way |
| Overhead | High (headers every req) | Medium | Low | Lowest |
| Latency | Up to poll interval | Near real-time | Real-time (server push) | Real-time (both ways) |
| Chat fit | Poor | Acceptable fallback | Read receipts only | Ideal |
| Browser support | Universal | Universal | Modern browsers | Modern browsers |
| Through proxies | Easy | Moderate | Moderate | Can be tricky (needs L4 LB) |

### Connection Lifecycle

```
  1. Client sends HTTP Upgrade request
     GET /ws HTTP/1.1
     Upgrade: websocket
     Connection: Upgrade
     Sec-WebSocket-Key: dGhlIHNhbXBsZQ==

  2. Server responds 101 Switching Protocols
     HTTP/1.1 101 Switching Protocols
     Upgrade: websocket
     Connection: Upgrade

  3. Persistent TCP connection established
     Client <=====> Server (full-duplex frames)

  4. Client sends heartbeat (ping) every 30s
     Server responds with pong
     If no pong for 2 intervals --> client reconnects

  5. On disconnect:
     - Server removes from connection registry
     - Server updates presence to OFFLINE (with grace period)
     - Client attempts reconnect with exponential backoff
```

### Fallback Strategy

```
  Client attempts (in order):
  1. WebSocket ---- if fails (corporate proxy blocks) --->
  2. SSE (Server-Sent Events) ---- if fails --->
  3. Long Polling ---- always works

  Detection: try WebSocket, set 5s timeout.
  If onopen doesn't fire, fall back.
```

### Interview Talking Point

> "WebSocket gives us full-duplex persistent connections for real-time delivery.
> We use ping/pong for liveness detection. Fallback is long polling for clients
> behind restrictive proxies. The connection server is stateful (holds WebSocket
> connections), which is why we need a connection registry in Redis — so any
> server can route a message to the right connection server."

---

## 2. Kafka

### Why Kafka for Chat?

1. **Message ordering** via partition key = `conversation_id`
2. **Decoupling** — producers (connection servers) and consumers (delivery workers) scale independently
3. **Durability** — messages survive server crashes (replicated to 3 brokers)
4. **Replay** — if a consumer crashes, it resumes from its last committed offset

### Partition Strategy

```
  Topic: chat-messages (N partitions)

  hash(conversation_id) % N --> partition assignment

  Partition 0: [conv-A msg1] [conv-A msg2] [conv-A msg3] ...  (ordered)
  Partition 1: [conv-B msg1] [conv-B msg2] ...                (ordered)
  Partition 2: [conv-C msg1] [conv-C msg2] ...                (ordered)

  Guarantee: All messages for one conversation land in the SAME partition
  --> Kafka preserves insertion order within a partition
  --> Messages for conversation A are always delivered in order
```

### Consumer Groups

```
  Consumer Group: "delivery-workers"

  +-----------+     +-----------+     +-----------+
  | Worker 1  |     | Worker 2  |     | Worker 3  |
  | (P0, P1)  |     | (P2, P3)  |     | (P4, P5)  |
  +-----------+     +-----------+     +-----------+

  Each partition is consumed by exactly one worker in the group
  --> Parallel processing without duplicate delivery
  --> Add workers to scale (up to N = partition count)
```

### Comparison with Alternatives

| Feature | Kafka | RabbitMQ | Redis Streams |
|---------|-------|----------|---------------|
| Ordering | Per-partition (strong) | Per-queue | Per-stream |
| Throughput | Very high (100K+ msg/s) | High (50K msg/s) | High (100K+ msg/s) |
| Durability | Disk + replication | Disk + mirrored queues | AOF + replication |
| Consumer groups | Native | Competing consumers | Native (XREADGROUP) |
| Message replay | Yes (offset-based) | No (consumed = gone) | Yes (ID-based) |
| Routing | Partition key only | Exchange routing (flexible) | Key-based |
| Operational complexity | High (ZooKeeper/KRaft) | Medium | Low |
| Best for | Ordered event streaming | Task queues, routing | Simple streaming, prototyping |

**Why Kafka over RabbitMQ**: We need strict per-conversation ordering and message
replay. RabbitMQ's exchange routing is overkill and it doesn't support replay.

**Why Kafka over Redis Streams**: Redis Streams are simpler but less durable at
scale. For a chat system handling billions of messages, Kafka's disk-based
storage and mature replication are safer.

---

## 3. Cassandra

### Why Cassandra for Messages?

- **Write-heavy workload** — chat systems write far more than they read
- **Time-series data** — messages are append-only, ordered by time
- **Wide rows** — one conversation's messages = one partition (efficient range queries)
- **Linear scalability** — add nodes to handle more conversations

### Data Model

```
  CREATE TABLE messages (
      conversation_id TEXT,
      sequence_number BIGINT,
      message_id      TEXT,
      sender_id       TEXT,
      content         TEXT,
      message_type    TEXT,
      media_url       TEXT,
      reply_to        TEXT,
      status          TEXT,
      created_at      TIMESTAMP,
      PRIMARY KEY (conversation_id, sequence_number)
  ) WITH CLUSTERING ORDER BY (sequence_number DESC);
```

### Partition Layout (Wide Row)

```
  Partition: conversation_id = "conv-ABC"

  +----------+------------+----------+---------+--------+
  | seq: 100 | seq: 99    | seq: 98  | seq: 97 | ...    |
  | "Hello"  | "Hi there" | "image"  | "Hey"   |        |
  | user-1   | user-2     | user-1   | user-2  |        |
  +----------+------------+----------+---------+--------+
                                                    |
                                          Oldest messages
  Clustering ORDER BY sequence_number DESC
  --> Most recent messages are read first (pagination from top)
```

### Query Patterns

| Query | How |
|-------|-----|
| Load recent messages | `SELECT * FROM messages WHERE conversation_id = ? LIMIT 50` |
| Load next page | `SELECT * FROM messages WHERE conversation_id = ? AND sequence_number < ? LIMIT 50` |
| Get single message | `SELECT * FROM messages WHERE conversation_id = ? AND sequence_number = ?` |

### TTL for Storage Management

```java
// Free users: messages expire after 90 days
INSERT INTO messages (...) VALUES (...) USING TTL 7776000;

// Premium users: no TTL (messages kept forever)
INSERT INTO messages (...) VALUES (...);
```

### Interview Talking Point

> "Cassandra is ideal for message storage because chat is write-heavy and
> append-only. We partition by conversation_id so one conversation's messages
> are co-located on the same node. Clustering by sequence_number DESC gives us
> efficient reverse-chronological pagination. TTL handles automatic expiration."

---

## 4. Redis

Redis serves **four distinct roles** in this system. This is a common interview
question: "You mention Redis — what exactly are you storing in it?"

### Role 1: Presence Store

```
  Key:    presence:{userId}
  Value:  "ONLINE"
  TTL:    60 seconds (2x heartbeat interval)

  On heartbeat: SETEX presence:user-42 60 ONLINE
  On explicit disconnect: DEL presence:user-42
  On TTL expiry (missed heartbeats): auto-deleted = OFFLINE
```

### Role 2: Connection Registry

```
  Key:    connection:{userId}
  Value:  "server-3"        (which connection server holds this user's WebSocket)
  TTL:    120 seconds       (refreshed on heartbeat)

  Message routing:
  1. HGET connection:user-42 --> "server-3"
  2. Forward message to server-3
  3. server-3 delivers via WebSocket to user-42
```

### Role 3: Group Member Cache

```
  Key:    group-members:{groupId}
  Type:   SET
  Value:  {"user-1", "user-2", "user-3", ..., "user-500"}

  On group message send:
  1. SMEMBERS group-members:group-123
  2. For each member, check presence and route
  3. Fan-out to all members

  Cache invalidation:
  - SADD group-members:{groupId} {userId}    (on join)
  - SREM group-members:{groupId} {userId}    (on leave — IMMEDIATE)
  - EXPIRE group-members:{groupId} 3600      (refresh hourly from PostgreSQL)
```

### Role 4: Offline Message Queue

```
  Key:    offline-queue:{userId}
  Type:   LIST (acts as a queue)

  When user is offline:
    RPUSH offline-queue:user-42 "{serialized message JSON}"
    LTRIM offline-queue:user-42 0 999    (cap at 1000 messages)

  When user comes online:
    messages = LRANGE offline-queue:user-42 0 -1
    DEL offline-queue:user-42
    --> Deliver all queued messages via WebSocket
```

### Summary Table

| Role | Key Pattern | Data Type | TTL | Access Frequency |
|------|------------|-----------|-----|-----------------|
| Presence | `presence:{userId}` | String | 60s | Every message send |
| Connection registry | `connection:{userId}` | String | 120s | Every message route |
| Group members | `group-members:{groupId}` | Set | 3600s | Every group message |
| Offline queue | `offline-queue:{userId}` | List | None (drained on connect) | On offline delivery |

---

## 5. S3 / CDN

### Media Upload Flow

```
  Client                   Server                  S3
    |                         |                      |
    |-- request upload URL -->|                      |
    |<-- pre-signed PUT URL --|-- generate URL ----->|
    |                         |                      |
    |-- PUT media directly -------------------------------->|
    |<-- 200 OK -------------------------------------------|
    |                         |                      |
    |-- send message with --->|                      |
    |   mediaUrl = s3://...   |                      |
```

### Media Download Flow

```
  Recipient                Server                  S3         CDN
    |                         |                      |          |
    |-- request media URL --->|                      |          |
    |<-- CDN URL -------------|                      |          |
    |                         |                      |          |
    |-- GET media ----------------------------------------->|
    |<-- media (cached) ----------------------------------------|
    |                         |                      |          |
    If cache miss:            |                      |          |
    |                         |                      |<---------|
    |                         |                      |-- fetch->|
    |<-- media (from S3 via CDN) --------------------------------|
```

- **Pre-signed URLs**: Server never proxies media — client uploads/downloads directly
- **CDN**: Hot media (recently shared images) served from edge cache
- **S3 lifecycle**: Move media older than 30 days to S3 Infrequent Access tier

---

## 6. FCM / APNs (Push Notifications)

```
  Message send flow (recipient offline):

  1. PresenceService.getPresence(recipientId) --> OFFLINE
  2. OfflineDeliveryStrategy:
     a. Queue message in Redis offline queue
     b. Send push notification:

  Server --> FCM (Android) / APNs (iOS)
         --> Device notification tray
         --> "User X: Hello!" (truncated preview)
```

- Same architecture as the Notification System project
- Rate limiting: max 1 push per conversation per 30 seconds (batch)
- Silent push for background sync (no alert, just triggers data fetch)
- Push payload is minimal: `{conversationId, senderId, preview}` — full message
  fetched when app opens

---

## 7. PostgreSQL

### What It Stores

| Table | Purpose | Row Count |
|-------|---------|-----------|
| users | User profiles (name, avatar, phone) | Millions |
| groups | Group metadata (name, created_by, avatar) | Millions |
| group_members | Group membership (group_id, user_id, role) | Tens of millions |
| contacts | User contacts / friend list | Hundreds of millions |
| blocked_users | Block relationships | Millions |

### Why PostgreSQL?

- **Small dataset** relative to messages — fits on a single node with read replicas
- **Relational queries**: "Get all groups where user X is a member and role = ADMIN"
- **ACID transactions**: Adding a user to a group + updating member count atomically
- **Not messages**: Messages are write-heavy time-series data. PostgreSQL would be
  a bottleneck. That's why messages go to Cassandra.

---

## 8. Load Balancer

### The WebSocket Challenge

WebSocket connections are **long-lived** and **stateful**. Traditional HTTP
load balancing (round-robin per request) doesn't work well.

```
  Option A: Sticky Sessions (NOT recommended)
  +----+    +-----+    +-----------+
  | LB | ---sticky--> | Server 1  |  (user always routed here)
  +----+              +-----------+
  Problem: Server 1 dies = all its users disconnected + no record of where they were

  Option B: Connection Registry (recommended)
  +----+    +-----+    +-----------+
  | LB | ---any------> | Server N  |  (any server, registered in Redis)
  +----+              +-----------+
  Redis: connection:user-42 = server-N
  Any server can look up where a user is connected and forward the message
```

### LB Configuration

- **Layer 4 (TCP)** load balancing for WebSocket — must preserve the TCP connection
  through the HTTP upgrade
- **Layer 7** for REST API endpoints (health checks, media upload URLs)
- Connection draining: when removing a server, drain existing WebSocket connections
  gracefully (send reconnect signal to clients)

---

## 9. Observability

### Key Metrics

| Metric | What It Measures | Alert Threshold |
|--------|-----------------|-----------------|
| `messages_per_second` | System throughput | < 50% of baseline |
| `delivery_latency_p99` | Time from send to deliver (p99) | > 500ms |
| `active_connections` | WebSocket connections per server | > 80% of server capacity |
| `offline_queue_depth` | Messages waiting per user | > 1000 (user offline too long) |
| `group_fanout_time_p99` | Time to fan out to all group members | > 200ms for 500-member group |
| `kafka_consumer_lag` | Unprocessed messages in Kafka | > 10000 |
| `cassandra_write_latency_p99` | Message persistence latency | > 50ms |
| `redis_memory_usage` | Redis memory utilization | > 80% |
| `websocket_error_rate` | Failed WebSocket connections/min | > 5% of attempts |
| `push_notification_failure_rate` | FCM/APNs failures | > 2% |

### Distributed Tracing

```
  Trace a single message through the system:

  [Client Send] --> [Connection Server] --> [Kafka Produce]
       |                   |                      |
    trace-id            trace-id               trace-id
       |                   |                      |
       v                   v                      v
  [Kafka Consume] --> [Delivery Worker] --> [WebSocket Send]
       |                   |                      |
    trace-id            trace-id               trace-id

  Total latency = sum of all spans
  Bottleneck identification: which span is slowest?
```

### Health Check Endpoints

```
  GET /health/ready    --> 200 if server can accept new connections
  GET /health/live     --> 200 if server process is running
  GET /health/ws-count --> {"connections": 45231}
  GET /health/kafka    --> {"consumer_lag": 142}
```

---

## Technology Decision Matrix

| Requirement | Technology | Why This Over Alternatives |
|------------|-----------|---------------------------|
| Real-time delivery | WebSocket | Full-duplex, lowest latency |
| Message ordering | Kafka | Partition-level ordering guarantee |
| Message storage | Cassandra | Write-heavy, time-series, wide rows |
| Presence + routing | Redis | Sub-millisecond lookups, TTL, pub/sub |
| Media storage | S3 + CDN | Scalable blob storage, edge caching |
| Push notifications | FCM/APNs | Only way to reach mobile devices when app is closed |
| User/group metadata | PostgreSQL | Relational queries, ACID, small dataset |
| Traffic distribution | L4 Load Balancer | TCP pass-through for WebSocket upgrade |
