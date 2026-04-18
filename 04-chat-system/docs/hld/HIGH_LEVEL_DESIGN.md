# High-Level Design: Chat/Messaging System (WhatsApp-like)

> **Interview Level**: HARD | **Time Budget**: 35-45 minutes
> **Target Role**: Senior Java Developer (7+ years)

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Traffic Estimates](#7-traffic-estimates)
8. [Data Model](#8-data-model)
9. [High-Level Architecture](#9-high-level-architecture)
10. [Component Deep Dive](#10-component-deep-dive)
11. [Message Delivery Flow -- THE CORE](#11-message-delivery-flow----the-core)
12. [Message Ordering](#12-message-ordering)
13. [1:1 vs Group Chat -- Fan-out Strategy](#13-11-vs-group-chat----fan-out-strategy)
14. [Scaling Strategy](#14-scaling-strategy)
15. [Database Choice](#15-database-choice)
16. [Caching Strategy](#16-caching-strategy)
17. [Offline Handling](#17-offline-handling)
18. [Read Receipts and Message Status](#18-read-receipts-and-message-status)
19. [CAP Theorem Analysis](#19-cap-theorem-analysis)
20. [Cloud Services Mapping](#20-cloud-services-mapping)
21. [Tradeoffs Summary](#21-tradeoffs-summary)
22. [Interview Talking Points](#22-interview-talking-points)

---

## 1. Problem Statement

**Build a real-time messaging system** that supports 1:1 chat, group chat, online/offline presence indicators, read receipts, and media sharing -- at the scale of WhatsApp.

### Why This Is a HARD Problem

| Challenge | Why It's Difficult |
|---|---|
| **Real-time delivery** | Sub-200ms delivery for 500M concurrent users requires persistent WebSocket connections and intelligent routing |
| **Message ordering** | Messages must appear in-order per conversation even across distributed servers and network partitions |
| **Offline support** | Users go offline constantly -- every message must be durably stored and reliably delivered when they reconnect |
| **Scale of connections** | 500M concurrent WebSocket connections = stateful servers, connection registry, and session affinity at massive scale |
| **Fan-out for groups** | A single group message to 256 members requires fan-out to potentially 256 different connection servers |
| **Consistency vs availability** | Messages must never be lost (durability) but the system must remain available (no single point of failure) |

---

## 2. Scope

### In Scope

- 1:1 real-time messaging
- Group chat (up to 256 members per group)
- Online/offline presence indicator
- Last seen timestamp
- Read receipts: single tick, double tick, blue tick
- Message history (scrollable, paginated)
- Media sharing (images, videos, documents)
- Offline message delivery (queue + deliver on reconnect)
- Push notifications for offline users

### Out of Scope

- Voice/video calling (WebRTC -- separate system)
- Stories/status feature
- End-to-end encryption implementation (mention at architecture level only)
- Payments / money transfer
- Message reactions / replies threading
- User registration / authentication (assume exists)

---

## 3. Assumptions

| Parameter | Value | Rationale |
|---|---|---|
| Daily Active Users (DAU) | 500M | WhatsApp-scale |
| Messages per day | 40B | ~80 messages/user/day |
| Average message size | 100 bytes | Text messages with metadata |
| Peak messages per minute | 50M (~833K/sec) | 1.8x average (events, New Year, etc.) |
| Avg groups per user | 10 | Family, work, friends |
| Media messages | 10% of total (4B/day) | Images, videos, documents |
| Average media size | 200 KB | Compressed images/thumbnails |
| Max group size | 256 members | WhatsApp limit |
| Connection per user | 1 active device | Simplification for initial design |
| Message retention | Indefinite (on device), 30 days server-side | Storage optimization |

---

## 4. Functional Requirements

| # | Requirement | Priority |
|---|---|---|
| FR-1 | Send and receive 1:1 messages in real-time | P0 |
| FR-2 | Group messaging -- create group, add/remove members, send messages | P0 |
| FR-3 | Online/offline presence indicator + last seen timestamp | P0 |
| FR-4 | Message status: sent (single tick), delivered (double tick), read (blue tick) | P0 |
| FR-5 | Message history -- scrollable, paginated, reverse chronological | P1 |
| FR-6 | Media sharing (images, videos, documents) via object storage | P1 |
| FR-7 | Offline message delivery -- queue messages, deliver when user reconnects | P0 |
| FR-8 | Push notifications for offline users via FCM/APNs | P1 |
| FR-9 | Typing indicator | P2 |
| FR-10 | Message search within conversation | P2 |

---

## 5. Non-Functional Requirements

| # | Requirement | Target |
|---|---|---|
| NFR-1 | **Latency** | < 200ms message delivery for online users (same region) |
| NFR-2 | **Message ordering** | Guaranteed per-conversation ordering |
| NFR-3 | **Delivery guarantee** | At-least-once delivery (clients dedup) |
| NFR-4 | **Availability** | 99.99% uptime (< 52 min downtime/year) |
| NFR-5 | **Scalability** | Support 500M concurrent WebSocket connections |
| NFR-6 | **Durability** | Zero message loss after server acknowledges |
| NFR-7 | **Consistency** | Eventual consistency for presence, strong ordering for messages |
| NFR-8 | **Throughput** | 800K messages/sec at peak |

---

## 6. API Design

### 6.1 WebSocket Protocol (Primary Channel)

All real-time communication uses persistent WebSocket connections.

```
WebSocket Endpoint: wss://chat.example.com/ws?userId={userId}&token={token}
```

#### WebSocket Events (Client to Server)

```
1. connect(userId, token)
   - Establishes WebSocket connection
   - Server registers connection in Redis: userId -> serverId

2. sendMessage(chatId, content, type)
   - Sends a message to a conversation
   - Server returns ACK with messageId and sequence number

3. typing(chatId)
   - Sends typing indicator to other participants
   - Ephemeral -- not persisted

4. ack(messageId, status)
   - Client acknowledges message delivery (DELIVERED) or read (READ)
```

#### WebSocket Events (Server to Client)

```
1. newMessage(message)          - New incoming message
2. messageStatus(messageId, status) - Status update (delivered/read)
3. presenceUpdate(userId, status)   - Contact online/offline
4. typingIndicator(chatId, userId)  - Someone is typing
```

#### Message Payload JSON

```json
{
  "messageId": "msg_7f3a8b2c-e91d-4f5a-b8c3-1d2e3f4a5b6c",
  "conversationId": "conv_a1b2c3d4",
  "senderId": "user_alice_001",
  "content": "Hey, are you free for lunch?",
  "type": "TEXT",
  "timestamp": 1713456789000,
  "sequenceNumber": 14523,
  "metadata": {
    "replyTo": null,
    "mediaUrl": null,
    "thumbnailUrl": null,
    "mimeType": null,
    "fileSize": null
  }
}
```

#### Media Message Payload

```json
{
  "messageId": "msg_8e4b9c3d-f02e-5g6b-c9d4-2e3f4g5h6i7j",
  "conversationId": "conv_a1b2c3d4",
  "senderId": "user_alice_001",
  "content": "Check out this photo!",
  "type": "IMAGE",
  "timestamp": 1713456800000,
  "sequenceNumber": 14524,
  "metadata": {
    "mediaUrl": "https://cdn.example.com/media/img_abc123.jpg",
    "thumbnailUrl": "https://cdn.example.com/thumb/img_abc123_thumb.jpg",
    "mimeType": "image/jpeg",
    "fileSize": 245760,
    "dimensions": { "width": 1920, "height": 1080 }
  }
}
```

### 6.2 REST API (Fallback + Management)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/messages/send` | Send message (REST fallback if WebSocket unavailable) |
| `GET` | `/api/messages/{chatId}?before={timestamp}&limit=50` | Fetch message history (paginated, cursor-based) |
| `POST` | `/api/groups` | Create a new group |
| `PUT` | `/api/groups/{groupId}/members` | Add/remove group members |
| `GET` | `/api/groups/{groupId}/members` | List group members |
| `GET` | `/api/users/{userId}/presence` | Get user presence status |
| `POST` | `/api/media/upload-url` | Get pre-signed URL for media upload |

#### REST: Send Message

```
POST /api/messages/send
Authorization: Bearer <token>

{
  "conversationId": "conv_a1b2c3d4",
  "content": "Hello from REST fallback",
  "type": "TEXT"
}

Response 201:
{
  "messageId": "msg_7f3a8b2c-...",
  "sequenceNumber": 14525,
  "status": "SENT",
  "timestamp": 1713456900000
}
```

#### REST: Fetch Message History

```
GET /api/messages/conv_a1b2c3d4?before=1713456789000&limit=50
Authorization: Bearer <token>

Response 200:
{
  "messages": [ ... ],
  "hasMore": true,
  "nextCursor": "1713400000000"
}
```

---

## 7. Traffic Estimates

### 7.1 Message Throughput

```
Messages per day:        40,000,000,000 (40B)
Messages per second:     40B / 86,400 = ~460,000 msg/sec (average)
Peak messages per second: ~800,000 msg/sec (1.8x average)
Peak messages per minute: ~50,000,000
```

### 7.2 Connection Scale

```
Concurrent connections:          500,000,000 (500M)
Connections per server:          ~50,000 (practical limit for WebSocket servers)
Connection servers needed:       500M / 50K = 10,000 servers
```

### 7.3 Storage Estimates

```
Text Messages:
  Daily:   40B x 100 bytes       = 4 TB/day
  Monthly: 4 TB x 30             = 120 TB/month
  Yearly:  120 TB x 12           = 1.44 PB/year

Media:
  Daily:   4B x 200 KB           = 800 TB/day
  Monthly: 800 TB x 30           = 24 PB/month

Total Storage (30-day retention for server-side text):
  Text:  120 TB
  Media: 24 PB (CDN + S3, longer retention)
```

### 7.4 Bandwidth Estimates

```
Text bandwidth:  460K msg/sec x 100 bytes  = 46 MB/sec = 368 Mbps
Media bandwidth: 46K media/sec x 200 KB    = 9.2 GB/sec = 73.6 Gbps
Total egress:    ~74 Gbps (dominated by media, served via CDN)
```

### 7.5 Summary Table

| Metric | Value |
|---|---|
| Avg messages/sec | 460K |
| Peak messages/sec | 800K |
| Concurrent WebSocket connections | 500M |
| Connection servers | 10,000 |
| Daily text storage | 4 TB |
| Daily media storage | 800 TB |
| Text bandwidth | 46 MB/sec |
| Media bandwidth | 9.2 GB/sec |

---

## 8. Data Model

### 8.1 Schema Design

#### `user` Table (PostgreSQL)

```sql
CREATE TABLE user (
    user_id        UUID PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    phone          VARCHAR(20) UNIQUE NOT NULL,
    avatar_url     VARCHAR(512),
    last_seen      TIMESTAMP,
    status_message VARCHAR(256),
    created_at     TIMESTAMP DEFAULT NOW()
);
```

#### `conversation` Table (PostgreSQL)

```sql
CREATE TABLE conversation (
    conversation_id  UUID PRIMARY KEY,
    type             ENUM('DIRECT', 'GROUP') NOT NULL,
    name             VARCHAR(128),          -- NULL for DIRECT
    avatar_url       VARCHAR(512),          -- NULL for DIRECT
    created_by       UUID REFERENCES user(user_id),
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);
```

#### `conversation_member` Table (PostgreSQL)

```sql
CREATE TABLE conversation_member (
    conversation_id      UUID REFERENCES conversation(conversation_id),
    user_id              UUID REFERENCES user(user_id),
    joined_at            TIMESTAMP DEFAULT NOW(),
    role                 ENUM('ADMIN', 'MEMBER') DEFAULT 'MEMBER',
    last_read_message_id UUID,               -- Tracks read position
    muted_until          TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id)
);

-- Index for "find all conversations for a user"
CREATE INDEX idx_member_user ON conversation_member(user_id);
```

#### `message` Table (Cassandra)

```sql
CREATE TABLE message (
    conversation_id  UUID,
    sequence_number  BIGINT,              -- Monotonically increasing per conversation
    message_id       UUID,
    sender_id        UUID,
    content          TEXT,
    type             TEXT,                 -- TEXT, IMAGE, VIDEO, FILE
    media_url        TEXT,
    thumbnail_url    TEXT,
    created_at       TIMESTAMP,
    PRIMARY KEY ((conversation_id), sequence_number)
) WITH CLUSTERING ORDER BY (sequence_number DESC);

-- Partition key: conversation_id  --> all messages for a conversation on same node
-- Clustering key: sequence_number --> messages sorted within partition
```

#### `message_status` Table (Cassandra)

```sql
CREATE TABLE message_status (
    message_id   UUID,
    user_id      UUID,
    status       TEXT,                    -- SENT, DELIVERED, READ
    updated_at   TIMESTAMP,
    PRIMARY KEY ((message_id), user_id)
);
```

### 8.2 Partition Key Strategy

```
+---------------------+--------------------+----------------------------+
| Table               | Partition Key      | Why                        |
+---------------------+--------------------+----------------------------+
| message             | conversation_id    | All msgs for a chat on     |
|                     |                    | same node, ordered by seq  |
+---------------------+--------------------+----------------------------+
| message_status      | message_id         | Quick lookup of who has    |
|                     |                    | received/read a message    |
+---------------------+--------------------+----------------------------+
| conversation_member | conversation_id    | Fetch all members of a     |
|                     |                    | group in single read       |
+---------------------+--------------------+----------------------------+
```

### 8.3 Entity Relationship Overview

```
  user 1──────M conversation_member M──────1 conversation
                        |
                        | (last_read_message_id)
                        v
                    message 1──────M message_status
                  (conversation_id,       (message_id,
                   sequence_number)        user_id)
```

---

## 9. High-Level Architecture

### 9.1 System Architecture Diagram

```
                            +--------------+
                            |   Clients    |
                            | (Mobile/Web) |
                            +------+-------+
                                   |
                          WebSocket + HTTPS
                                   |
                            +------v-------+
                            | API Gateway  |
                            |  / Load      |
                            |  Balancer    |
                            +------+-------+
                                   |
                 +-----------------+------------------+
                 |                                    |
         +-------v--------+                  +--------v-------+
         |  Connection    |                  |   REST API     |
         |  Gateway       |                  |   Servers      |
         |  (WebSocket)   |                  |  (Stateless)   |
         |  10,000 nodes  |                  +--------+-------+
         +---+----+---+---+                           |
             |    |   |                               |
    +--------+    |   +--------+             +--------+
    |             |            |             |
+---v---+   +----v----+  +----v----+   +----v----+
|Message|   |Presence |  | Group   |   |  User   |
|Service|   |Service  |  | Service |   | Service |
+---+---+   +----+----+  +----+----+   +----+----+
    |             |            |             |
    |        +----v----+  +----v----+   +----v----+
    |        |  Redis  |  |PostgreSQL|  |PostgreSQL|
    |        | Cluster |  | (Groups) |  | (Users) |
    |        |(Presence|  +---------+   +---------+
    |        | + Conn  |
    |        | Registry|
    |        +---------+
    |
+---v-----------+
|     Kafka     |
| (Message Bus) |
+---+-----------+
    |
+---v--------------+        +-----------------+
| Message Fanout / |        | Push Notification|
| Router Service   +------->| Service         |
+---+--------------+        | (FCM / APNs)    |
    |                       +-----------------+
    |
+---v-----------+        +------------------+
|   Cassandra   |        |  Media Service   |
| (Messages DB) |        |  (S3 + CDN)     |
+---------------+        +------------------+
```

### 9.2 Component Summary

```
+---------------------------+-------------------------------------------+
| Component                 | Responsibility                            |
+---------------------------+-------------------------------------------+
| Connection Gateway        | Maintain WebSocket connections,           |
|                           | route messages to/from clients            |
+---------------------------+-------------------------------------------+
| Message Service           | Validate, persist, sequence messages      |
+---------------------------+-------------------------------------------+
| Message Fanout/Router     | Route messages to recipients via          |
|                           | correct connection server                 |
+---------------------------+-------------------------------------------+
| Presence Service          | Track online/offline, heartbeat, TTL      |
+---------------------------+-------------------------------------------+
| Group Service             | Group CRUD, membership management         |
+---------------------------+-------------------------------------------+
| User Service              | User profiles, contacts                   |
+---------------------------+-------------------------------------------+
| Media Service             | Pre-signed URL generation, S3 upload      |
+---------------------------+-------------------------------------------+
| Push Notification Service | FCM/APNs for offline users                |
+---------------------------+-------------------------------------------+
| Message Sync Service      | Sync missed messages on reconnect         |
+---------------------------+-------------------------------------------+
| Kafka                     | Message queue, decouples write from       |
|                           | fan-out, guarantees ordering              |
+---------------------------+-------------------------------------------+
| Redis Cluster             | Presence, connection registry,            |
|                           | group member cache, recent messages       |
+---------------------------+-------------------------------------------+
| Cassandra Cluster         | Message storage (write-heavy, time-series)|
+---------------------------+-------------------------------------------+
| PostgreSQL                | User and group metadata (relational)      |
+---------------------------+-------------------------------------------+
| S3 + CDN                  | Media object storage + delivery           |
+---------------------------+-------------------------------------------+
```

---

## 10. Component Deep Dive

### 10.1 Connection Gateway

```
Purpose: Maintain persistent WebSocket connections between clients and the server.

Architecture:
  - 10,000 servers, each handling ~50K concurrent connections
  - STATEFUL: each connection is pinned to a specific server
  - Connection registry in Redis: maps userId -> connectionServerId

Flow:
  1. Client connects via WebSocket
  2. Gateway authenticates token (JWT validation)
  3. Registers mapping in Redis:
       SET conn:{userId} -> {serverId, connectionId, timestamp}
       with TTL = 120 seconds (refreshed by heartbeat)
  4. All messages for this user are routed TO this specific server
  5. On disconnect: remove from Redis, mark user offline

Scaling Concern:
  - Adding/removing servers requires connection migration
  - Use consistent hashing for load balancer affinity
  - Graceful shutdown: notify clients to reconnect to a different server

Java Implementation Note:
  - Use Netty or Spring WebFlux for non-blocking WebSocket handling
  - Netty can handle 50K+ connections per server with proper tuning
  - -Xmx tuning: ~2KB per connection overhead = ~100MB for 50K connections
```

### 10.2 Message Service

```
Purpose: Receive messages, assign ordering, persist, and publish for delivery.

Flow:
  1. Receive message from Connection Gateway
  2. Validate: sender is member of conversation, content size limits
  3. Generate message_id (UUID v7 -- time-sortable)
  4. Assign sequence_number:
       - Use Redis INCR on key "seq:{conversationId}" for atomic increment
       - Guarantees monotonic ordering per conversation
  5. Persist to Cassandra (async, but before ACK for durability)
  6. Publish to Kafka topic "messages" with key = conversationId
  7. Return ACK (single tick) to sender via WebSocket

Stateless: Yes -- can scale horizontally behind load balancer.

Failure Handling:
  - If Cassandra write fails: retry with idempotency key (message_id)
  - If Kafka publish fails: write to local WAL, retry async
  - Client retries with same message_id if no ACK received
```

### 10.3 Message Fanout / Router Service

```
Purpose: Consume from Kafka and route messages to the correct connection servers.

Flow:
  1. Consume from Kafka topic "messages" (consumer group)
  2. Read message, determine conversation type:

  For 1:1 (DIRECT):
    a. Look up recipient userId from conversation_member (cached in Redis)
    b. Check Redis: conn:{recipientId} -> connectionServerId
    c. If online: forward message to that connection server via internal RPC
    d. If offline: push to offline queue + trigger push notification

  For GROUP:
    a. Fetch member list from Redis cache (or Group Service)
    b. For each member (except sender):
       - Check presence in Redis
       - If online: batch messages by connection server, send via RPC
       - If offline: queue + push notification
    c. Fan-out is parallelized, batched per connection server
       (e.g., if 50 members are on server-7, send one batch)

Scaling:
  - Number of Kafka consumers = number of Kafka partitions
  - Partition key = conversationId ensures ordering
  - Multiple consumer instances for throughput
```

### 10.4 Presence Service

```
Purpose: Track which users are online/offline and their last seen time.

Mechanism -- Heartbeat-Based:
  1. Client sends heartbeat every 30 seconds via WebSocket
  2. Server updates Redis key with TTL:
       SET presence:{userId} -> {status: "online", serverId: "srv-42"}
       EXPIRE presence:{userId} 60    // 2x heartbeat interval
  3. If no heartbeat for 60 seconds -> key expires -> user is offline
  4. On explicit disconnect: delete key immediately + set last_seen

Presence Subscription:
  - When user A opens chat with user B, subscribe to B's presence
  - Use Redis Pub/Sub channel: presence:{userId}
  - On status change: publish to all subscribers
  - Unsubscribe when user leaves chat screen

Optimizations:
  - Batch presence checks: when loading contact list, MGET all presence keys
  - Throttle presence updates: max 1 update per 10 seconds per user
  - Stale data is acceptable: "online" showing for 60 seconds after disconnect is OK

Last Seen:
  - Updated on disconnect or heartbeat timeout
  - Stored in PostgreSQL user table (persistent) and Redis (fast reads)
```

### 10.5 Group Service

```
Purpose: Manage group lifecycle and membership.

Operations:
  - Create group (name, avatar, initial members)
  - Add member (by admin)
  - Remove member (by admin or self-leave)
  - Update group info (name, avatar)
  - List members

Storage:
  - PostgreSQL: source of truth for group metadata + membership
  - Redis cache: group:{groupId}:members -> SET of userIds (hot data)
  - Cache invalidation: on membership change, update Redis + publish event

Group Message Fan-out Optimization:
  - Pre-compute member list in Redis for fast fan-out
  - For large groups (>100 members): paginate fan-out via message queue
  - Cache is warmed on first access, TTL = 10 minutes
```

### 10.6 Media Service

```
Purpose: Handle media upload and delivery without passing binaries through chat servers.

Flow:
  1. Client requests pre-signed upload URL:
       POST /api/media/upload-url
       { "fileName": "photo.jpg", "mimeType": "image/jpeg", "fileSize": 245760 }

  2. Server generates pre-signed S3 URL (valid for 15 minutes)
       Response: { "uploadUrl": "https://s3.../presigned", "mediaId": "media_xyz" }

  3. Client uploads directly to S3 (bypasses chat servers entirely)

  4. S3 triggers Lambda/webhook -> generate thumbnail, validate file

  5. Client sends chat message with mediaUrl pointing to CDN:
       { "type": "IMAGE", "mediaUrl": "https://cdn.../media_xyz.jpg",
         "thumbnailUrl": "https://cdn.../media_xyz_thumb.jpg" }

Why Direct Upload:
  - Chat servers never handle large binary payloads
  - S3 handles storage durability (11 nines)
  - CDN (CloudFront) handles delivery at edge
  - Reduces chat server bandwidth by 99%+ for media
```

### 10.7 Push Notification Service

```
Purpose: Notify offline users of new messages.

Flow:
  1. Message Router determines user is offline (no Redis presence key)
  2. Publishes to Kafka topic "push-notifications"
  3. Push Notification Service consumes event
  4. Looks up user's device tokens (FCM for Android, APNs for iOS)
  5. Sends push notification with:
       - Sender name
       - Message preview (first 100 chars)
       - Conversation ID (for deep linking)
  6. Rate limiting: max 1 push per conversation per 30 seconds
       (collapse multiple messages into "3 new messages from Alice")

Batching:
  - If user has 50 unread messages across 10 chats, send summary push
  - Group pushes: "Alice sent a message in 'Team Lunch'"
```

### 10.8 Message Sync Service

```
Purpose: Synchronize missed messages when a user comes back online.

Flow:
  1. User reconnects via WebSocket
  2. Client sends last known sequence_number per conversation
  3. Sync Service queries Cassandra:
       SELECT * FROM message
       WHERE conversation_id = ? AND sequence_number > ?
       LIMIT 50
  4. Returns messages in order, paginated
  5. Client requests more pages as user scrolls up
  6. Each synced message triggers delivery ACK -> double tick

Optimization:
  - Maintain per-user offline queue in Redis (LIST)
  - On reconnect: drain the queue first (faster than querying Cassandra)
  - Queue is TTL'd: if user is offline >30 days, fall back to Cassandra
```

---

## 11. Message Delivery Flow -- THE CORE

### 11.1 One-to-One Message: Alice Sends to Bob

```
Alice's Phone           Connection         Message        Kafka      Message       Connection        Bob's Phone
                        Server A           Service                   Router        Server B

     |                      |                  |             |           |              |                |
     |--- sendMessage() --->|                  |             |           |              |                |
     |   (WebSocket)        |                  |             |           |              |                |
     |                      |--- validate +    |             |           |              |                |
     |                      |    forward ------>|             |           |              |                |
     |                      |                  |             |           |              |                |
     |                      |                  |-- 1. Assign |           |              |                |
     |                      |                  |   seq_id    |           |              |                |
     |                      |                  |             |           |              |                |
     |                      |                  |-- 2. Write  |           |              |                |
     |                      |                  |   Cassandra |           |              |                |
     |                      |                  |             |           |              |                |
     |                      |                  |-- 3. Publish|           |              |                |
     |                      |                  |   --------->|           |              |                |
     |                      |                  |             |           |              |                |
     |                      |   ACK (tick 1)   |             |           |              |                |
     |<-- single tick  ----|<--- SENT ---------|             |           |              |                |
     |       (checkmark)    |                  |             |           |              |                |
     |                      |                  |             |-- 4. ---->|              |                |
     |                      |                  |             |  consume  |              |                |
     |                      |                  |             |           |              |                |
     |                      |                  |             |           |-- 5. Check:  |                |
     |                      |                  |             |           |  Is Bob       |                |
     |                      |                  |             |           |  online?      |                |
     |                      |                  |             |           |  Redis lookup |                |
     |                      |                  |             |           |              |                |
```

#### Step 6a: Bob IS ONLINE

```
     |                      |                  |             |           |-- forward -->|                |
     |                      |                  |             |           |   (internal  |-- newMessage ->|
     |                      |                  |             |           |    RPC)      |   (WebSocket)  |
     |                      |                  |             |           |              |                |
     |                      |                  |             |           |              |<-- delivery ---|
     |                      |                  |             |           |              |    ACK         |
     |                      |                  |             |           |              |                |
     |<-- double tick ------|<------- DELIVERED status update ----------|              |                |
     |     (2 checkmarks)   |                  |             |           |              |                |
```

#### Step 6b: Bob IS OFFLINE

```
     |                      |                  |             |           |-- Bob is     |                |
     |                      |                  |             |           |   offline    |                |
     |                      |                  |             |           |              |                |
     |                      |                  |             |           |-- Queue msg  |                |
     |                      |                  |             |           |   in Redis   |                |
     |                      |                  |             |           |              |                |
     |                      |                  |             |           |-- Trigger -->| Push Notif     |
     |                      |                  |             |           |   push notif | Service        |
     |                      |                  |             |           |              |----> FCM/APNs  |
     |                      |                  |             |           |              |                |
     |                      |    ... later, Bob comes online ...        |              |                |
     |                      |                  |             |           |              |                |
     |                      |                  |             |           |              |<-- connect ----|
     |                      |                  |             |           |              |   (WebSocket)  |
     |                      |                  |             |           |              |                |
     |                      |                  |             |           |              |-- drain queue->|
     |                      |                  |             |           |              |   sync msgs    |
     |                      |                  |             |           |              |                |
     |<-- double tick ------|<------- DELIVERED status update ------------------------|                |
```

#### Step 7: Bob Reads the Message

```
     |                      |                  |             |           |              |<-- Bob opens  |
     |                      |                  |             |           |              |   conversation |
     |                      |                  |             |           |              |                |
     |                      |                  |             |           |              |<-- readReceipt-|
     |                      |                  |             |           |              |   (WebSocket)  |
     |                      |                  |             |           |<-- forward --|                |
     |                      |                  |             |           |              |                |
     |<-- blue ticks -------|<------- READ status update ---|-----------|              |                |
     |                      |                  |             |           |              |                |
```

### Numbered Summary: 1:1 Message Flow

```
1. Alice's client sends message via WebSocket to Connection Server A
2. Connection Server A forwards to Message Service
3. Message Service:
   a. Assigns sequence_number (Redis INCR)
   b. Persists to Cassandra
   c. Publishes to Kafka (partition key = conversationId)
   d. Returns ACK to Alice -> single tick (checkmark)
4. Kafka delivers to Message Router (consumer)
5. Router looks up Bob's connection server in Redis
6a. IF ONLINE:  Route to Connection Server B -> WebSocket -> Bob
                 Bob's client ACKs -> double tick (2 checkmarks)
6b. IF OFFLINE: Queue in Redis + trigger push notification
                 When Bob reconnects -> sync -> double tick
7. Bob opens conversation -> read receipt -> blue ticks
```

### 11.2 Group Message: Alice Sends to Group (100 Members)

```
1. Alice's client sends message via WebSocket to Connection Server A
2. Connection Server A forwards to Message Service
3. Message Service:
   a. Assigns sequence_number (same as 1:1)
   b. Persists to Cassandra
   c. Publishes to Kafka
   d. Returns ACK to Alice -> single tick

4. Kafka delivers to Message Router
5. Router fetches group member list from Redis cache
   (100 members, excluding Alice = 99 recipients)

6. Router groups recipients by connection server:
   +-------------------+------------------+
   | Connection Server | Members          |
   +-------------------+------------------+
   | Server-12         | [Bob, Carol, ..] |  <- batch of 15
   | Server-47         | [Dave, Eve, ..]  |  <- batch of 22
   | Server-83         | [Frank, ..]      |  <- batch of 8
   | (offline)         | [Grace, ..]      |  <- 12 offline users
   +-------------------+------------------+

7. For each batch: send single RPC to connection server with all recipients
   - Connection server pushes to each member's WebSocket
   - Reduces cross-server calls from 99 to ~15-20 batched RPCs

8. For offline members: queue + push notification (batched)

9. Delivery/read receipts: tracked per-member in message_status table
```

---

## 12. Message Ordering

### Why Ordering Is Critical

```
Without ordering, this can happen:

  Alice sends:  "Should we cancel the meeting?"
  Alice sends:  "Just kidding, see you there!"

  Bob receives: "Just kidding, see you there!"
  Bob receives: "Should we cancel the meeting?"  <-- Confusing!
```

### Ordering Strategy

```
Scope: Per-conversation ordering (NOT global ordering)
  - Messages within a single chat must be in order
  - Messages across different chats do NOT need global ordering
  - This simplification is critical for scalability

Mechanism:
  1. KAFKA PARTITION KEY = conversation_id
     - All messages for a conversation go to the SAME Kafka partition
     - Kafka guarantees ordering within a partition
     - Different conversations can be on different partitions (parallelism)

  2. SEQUENCE NUMBER per conversation
     - Redis INCR on key "seq:{conversationId}"
     - Monotonically increasing, gap-free
     - Assigned by Message Service before Kafka publish

  3. CLIENT-SIDE REORDERING (defense in depth)
     - Client maintains local sequence tracker per conversation
     - If message arrives with seq_number > expected + 1:
       a. Buffer the message
       b. Request missing messages from server
       c. Display in correct order once gaps are filled
     - If message arrives with seq_number <= last_seen: deduplicate

Tradeoff:
  - Per-conversation ordering is achievable with Kafka partitions
  - Global ordering would require a single partition (bottleneck) or
    vector clocks (complexity) -- not needed for chat
```

### Ordering Guarantee Chain

```
  Sender -> Message Service -> Kafka (partition=conversationId) -> Router -> Recipient
            (assign seq_num)  (ordered within partition)
  
  End-to-end: if Alice sends M1 then M2 in the same conversation,
  Bob is guaranteed to receive M1 before M2.
```

---

## 13. 1:1 vs Group Chat -- Fan-out Strategy

### Strategy Comparison

```
+-------------------+--------------------+--------------------+--------------------+
| Strategy          | Fan-out on WRITE   | Fan-out on READ    | Hybrid             |
+-------------------+--------------------+--------------------+--------------------+
| How it works      | Push message to    | Store once, each   | Write for small    |
|                   | every recipient    | recipient pulls    | groups, read for   |
|                   | immediately        | when they open app | mega-groups        |
+-------------------+--------------------+--------------------+--------------------+
| Latency           | Low (push-based)   | Higher (pull-based)| Low for most cases |
+-------------------+--------------------+--------------------+--------------------+
| Write cost        | O(N) per message   | O(1) per message   | Varies             |
+-------------------+--------------------+--------------------+--------------------+
| Read cost         | O(1) per read      | O(N) per read      | Varies             |
+-------------------+--------------------+--------------------+--------------------+
| Best for          | 1:1 + small groups | Huge groups (1000+)| Chat apps          |
+-------------------+--------------------+--------------------+--------------------+
```

### Our Design Choice

```
1:1 Chat (DIRECT):
  - Fan-out on WRITE
  - Trivial: one sender, one receiver
  - Route directly to recipient's connection server

Small Group (2-100 members):
  - Fan-out on WRITE
  - When message arrives, immediately push to all online members
  - Parallel fan-out, batched per connection server
  - Acceptable cost: up to 100 deliveries per message

Large Group (100-256 members):
  - Fan-out on WRITE, but BATCHED via message queue
  - Prevents thundering herd: don't blast 256 RPCs simultaneously
  - Use internal queue to stagger delivery in batches of 50
  - Still push-based (real-time), just rate-limited

Why NOT fan-out on read:
  - Chat is latency-sensitive: users expect instant delivery
  - Pull-based adds noticeable delay (seconds, not milliseconds)
  - With max group size of 256, write fan-out cost is bounded
  - Would only consider fan-out on read for broadcast channels (10K+ members)
```

---

## 14. Scaling Strategy

### 14.1 Scaling Each Component

```
+---------------------+----------+--------------------------------------------+
| Component           | Stateful?| Scaling Strategy                           |
+---------------------+----------+--------------------------------------------+
| Connection Gateway  | YES      | Add servers. Redis connection registry     |
|                     |          | maps userId -> serverId. Consistent        |
|                     |          | hashing for LB. Graceful drain on remove.  |
+---------------------+----------+--------------------------------------------+
| Message Service     | NO       | Horizontal scaling behind load balancer.   |
|                     |          | Any instance can handle any message.       |
+---------------------+----------+--------------------------------------------+
| Message Router      | NO       | Scale with Kafka partitions. 1 consumer    |
|                     |          | per partition. Add partitions + consumers. |
+---------------------+----------+--------------------------------------------+
| Presence Service    | NO       | Stateless; all state in Redis. Scale       |
|                     |          | horizontally.                              |
+---------------------+----------+--------------------------------------------+
| Group Service       | NO       | Stateless + Redis cache. Scale behind LB.  |
+---------------------+----------+--------------------------------------------+
| Kafka               | YES      | Partition by conversation_id (or hash).    |
|                     |          | Add brokers + rebalance partitions.        |
|                     |          | Target: 10K-50K partitions.                |
+---------------------+----------+--------------------------------------------+
| Cassandra           | YES      | Partition by conversation_id. Add nodes,   |
|                     |          | auto-rebalance. Write-optimized.           |
|                     |          | Replication factor = 3.                    |
+---------------------+----------+--------------------------------------------+
| Redis               | YES      | Redis Cluster with sharding. Separate      |
|                     |          | clusters for: presence, connection         |
|                     |          | registry, group cache, offline queues.     |
+---------------------+----------+--------------------------------------------+
```

### 14.2 Connection Server Scaling Detail

```
Problem: 500M connections across 10,000 servers. How to:
  - Route messages to the right server?
  - Handle server failures?
  - Add/remove servers?

Solution: Connection Registry in Redis
  
  On connect:
    HSET connection_registry {userId} {serverId}:{connectionId}:{timestamp}
  
  On disconnect:
    HDEL connection_registry {userId}
  
  On message route:
    serverId = HGET connection_registry {recipientId}
    if serverId != null:
      RPC to serverId with message
    else:
      user is offline -> queue
  
  Server failure:
    - Health checker detects server-42 is down
    - Scan Redis for all users on server-42 (indexed separately)
    - Clients auto-reconnect to a new server (different one via LB)
    - New connection overwrites Redis entry
    - Messages queued during reconnect gap are drained on reconnect
```

---

## 15. Database Choice

### 15.1 Comparison Matrix

```
+-------------------+------------------+-------------------+------------------+
| Use Case          | Chosen DB        | Alternative       | Why Chosen       |
+-------------------+------------------+-------------------+------------------+
| Message storage   | Cassandra        | DynamoDB,         | Write-heavy      |
|                   |                  | ScyllaDB          | workload (460K   |
|                   |                  |                   | writes/sec).     |
|                   |                  |                   | Time-series      |
|                   |                  |                   | access pattern.  |
|                   |                  |                   | Partition by     |
|                   |                  |                   | conversation_id. |
|                   |                  |                   | Linear scale.    |
|                   |                  |                   | No joins needed. |
+-------------------+------------------+-------------------+------------------+
| Presence +        | Redis            | Memcached         | In-memory for    |
| Connection        | (Cluster)        |                   | speed. TTL for   |
| Registry          |                  |                   | heartbeat expiry.|
|                   |                  |                   | Pub/Sub for      |
|                   |                  |                   | presence events. |
|                   |                  |                   | Data structures  |
|                   |                  |                   | (SET, LIST, HASH)|
+-------------------+------------------+-------------------+------------------+
| User + Group      | PostgreSQL       | MySQL,            | Relational data  |
| metadata          |                  | CockroachDB       | with joins       |
|                   |                  |                   | (user->groups).  |
|                   |                  |                   | Small dataset    |
|                   |                  |                   | (~500M users).   |
|                   |                  |                   | ACID for member  |
|                   |                  |                   | operations.      |
+-------------------+------------------+-------------------+------------------+
| Media files       | S3 (Object      | Google Cloud      | 11 nines          |
|                   | Storage)        | Storage, Azure    | durability.      |
|                   | + CloudFront    | Blob              | CDN for global   |
|                   | (CDN)           |                   | delivery.        |
|                   |                  |                   | Pre-signed URL   |
|                   |                  |                   | for direct       |
|                   |                  |                   | upload.          |
+-------------------+------------------+-------------------+------------------+
```

### 15.2 Why Cassandra for Messages (Detailed)

```
Access Patterns:
  1. Write a message to a conversation           -> frequent (460K/sec)
  2. Read recent messages in a conversation       -> frequent (pagination)
  3. Read messages after a sequence number        -> on reconnect (sync)
  4. Delete/expire old messages                   -> TTL-based

Why Cassandra fits:
  - Write-optimized (LSM-tree): handles 460K writes/sec
  - Partition key = conversation_id: all messages co-located
  - Clustering key = sequence_number DESC: natural sort order
  - TTL: automatic expiration of old messages (30-day retention)
  - Linear scalability: add nodes to handle more data/traffic
  - Tunable consistency: QUORUM writes for durability, ONE reads for speed

Why NOT:
  - MySQL/PostgreSQL: Can't handle 460K writes/sec at this scale
  - MongoDB: Less efficient for time-series scan patterns
  - DynamoDB: Viable alternative, but vendor lock-in + cost at this scale
```

---

## 16. Caching Strategy

### Cache Layers

```
+------------------------------+-----------+--------+---------------------------+
| What's Cached                | Store     | TTL    | Invalidation              |
+------------------------------+-----------+--------+---------------------------+
| Recent messages per          | Redis     | 30 min | New message -> append to  |
| conversation (last 50)       | LIST      |        | list, trim to 50          |
+------------------------------+-----------+--------+---------------------------+
| Group membership             | Redis     | 10 min | Membership change ->      |
| (group_id -> member list)    | SET       |        | delete key, repopulate    |
+------------------------------+-----------+--------+---------------------------+
| User presence                | Redis     | 60 sec | Heartbeat refreshes TTL.  |
| (user_id -> online/offline)  | STRING    | (TTL)  | Expiry = offline.         |
+------------------------------+-----------+--------+---------------------------+
| Connection server mapping    | Redis     | 120 sec| Refresh on heartbeat.     |
| (user_id -> server_id)       | HASH      |        | Delete on disconnect.     |
+------------------------------+-----------+--------+---------------------------+
| User profile                 | Redis     | 5 min  | Profile update -> delete  |
| (user_id -> name, avatar)    | STRING    |        | key.                      |
+------------------------------+-----------+--------+---------------------------+
| Conversation metadata        | Redis     | 10 min | On update -> invalidate.  |
| (conv_id -> type, name)      | STRING    |        |                           |
+------------------------------+-----------+--------+---------------------------+
```

### Cache Access Pattern

```
Client opens a conversation:
  1. Check Redis for recent messages (cache HIT -> return immediately)
  2. Cache MISS -> query Cassandra, populate cache, return
  3. Check Redis for group members (if group chat)
  4. Check Redis for presence of participants

New message arrives:
  1. Persist to Cassandra
  2. LPUSH to Redis list "recent:{conversationId}"
  3. LTRIM to keep only last 50
  4. Publish to Kafka for delivery
```

---

## 17. Offline Handling

### Offline Message Queue

```
When user goes offline:
  1. Redis presence key expires (TTL 60s after last heartbeat)
  2. Connection registry entry is removed

When a message arrives for offline user:
  1. Router checks Redis: conn:{userId} -> NULL (offline)
  2. Push message to offline queue:
       RPUSH offline:{userId} {serialized_message}
       EXPIRE offline:{userId} 2592000   // 30 days TTL
  3. Trigger push notification (FCM/APNs)

When user comes back online:
  1. Client reconnects via WebSocket
  2. Connection Gateway registers in Redis
  3. Message Sync Service is triggered:
       a. Check offline queue: LRANGE offline:{userId} 0 -1
       b. Send all queued messages to client (batched, paginated)
       c. Clear queue: DEL offline:{userId}
       d. For each delivered message: update status -> DELIVERED (double tick)
  4. If offline queue is empty or expired:
       Fall back to Cassandra:
       - Client sends last_seen sequence_number per conversation
       - Query: SELECT * FROM message WHERE conversation_id = ?
                AND sequence_number > ? LIMIT 50
       - Paginate until client is caught up

Edge Cases:
  - User offline for >30 days: offline queue expired, use Cassandra
  - User has 10,000 unread messages: paginate delivery, don't blast all at once
  - User switches devices: sync via Cassandra (offline queue is per-device)
```

---

## 18. Read Receipts and Message Status

### Status State Machine

```
                 Server receives        Recipient device         Recipient opens
                 + persists             receives message         conversation
  PENDING ---------> SENT -----------------> DELIVERED -----------------> READ
                  (single tick)           (double tick)             (blue tick)
                       |                       |                       |
                   checkmark              2 checkmarks            2 blue checkmarks
```

### Implementation

```
Single Tick (SENT):
  - Triggered when: Message Service persists to Cassandra + returns ACK
  - Who triggers: Message Service -> Connection Gateway -> Sender
  - Latency: immediate (same request path)

Double Tick (DELIVERED):
  - Triggered when: Recipient's client receives message via WebSocket
  - Who triggers: Recipient client -> Connection Gateway -> status update
  - Flow:
      1. Recipient client sends: ack(messageId, "DELIVERED")
      2. Connection Gateway forwards to Message Service
      3. Message Service updates message_status table
      4. Message Service notifies sender via WebSocket (if online)
  - For offline delivery: triggered when synced messages are received

Blue Tick (READ):
  - Triggered when: Recipient opens the conversation containing the message
  - Who triggers: Recipient client -> Connection Gateway -> status update
  - Flow:
      1. Recipient opens conversation
      2. Client sends: readReceipt(conversationId, lastReadMessageId)
      3. Updates conversation_member.last_read_message_id
      4. All messages up to lastReadMessageId are marked READ
      5. Sender is notified via WebSocket

Group Read Receipts:
  - More complex: N members, each with their own status
  - message_status table: one row per (message_id, user_id)
  - Display logic:
      * Single tick: server persisted
      * Double tick: ALL members have received (or: at least one -- design choice)
      * Blue tick: ALL members have read (or: show individual read status)
  - WhatsApp approach: double tick = all delivered, blue = all read
    But tapping on message shows per-member status

Optimization:
  - Batch read receipts: don't send one per message
  - Send: "I've read up to sequence_number X in conversation Y"
  - Server marks all messages up to X as READ for this user
```

---

## 19. CAP Theorem Analysis

### CAP Choices by Component

```
+---------------------+--------+--------------------------------------------+
| Component           | Choice | Justification                              |
+---------------------+--------+--------------------------------------------+
| Presence Service    | AP     | Stale "online" status for a few seconds    |
|                     |        | is acceptable. Showing someone as "online" |
|                     |        | when they just disconnected causes no harm.|
|                     |        | Availability > strict consistency here.    |
+---------------------+--------+--------------------------------------------+
| Message Ordering    | CP-ish | Use Kafka partition ordering to guarantee   |
|                     |        | message sequence within a conversation.    |
|                     |        | If Kafka partition leader fails, brief     |
|                     |        | unavailability is acceptable to preserve   |
|                     |        | ordering (leader election takes seconds).  |
+---------------------+--------+--------------------------------------------+
| Message Delivery    | AP     | At-least-once delivery. Duplicates are     |
|                     |        | possible (client deduplicates by           |
|                     |        | message_id). A message may be delivered    |
|                     |        | twice, but never lost. Availability        |
|                     |        | trumps exactly-once guarantee.             |
+---------------------+--------+--------------------------------------------+
| Message Storage     | AP     | Cassandra with QUORUM writes: data is      |
| (Cassandra)         |        | durable on majority of replicas. Eventual  |
|                     |        | consistency for reads is fine (messages     |
|                     |        | are immutable once written).               |
+---------------------+--------+--------------------------------------------+
| User/Group Metadata | CP     | PostgreSQL with strong consistency.         |
| (PostgreSQL)        |        | Group membership changes must be            |
|                     |        | consistent (don't send to removed member). |
+---------------------+--------+--------------------------------------------+
```

### Summary

```
  Presence:  Availability + Partition Tolerance  (AP)  -- stale is OK
  Messages:  Availability + Partition Tolerance  (AP)  -- at-least-once, dedup
  Ordering:  Consistency + Partition Tolerance   (CP)  -- Kafka partition leader
  Metadata:  Consistency + Partition Tolerance   (CP)  -- PostgreSQL
```

---

## 20. Cloud Services Mapping

```
+-------------------------+-------------------+-------------------+-------------------+
| Component               | AWS               | GCP               | Azure             |
+-------------------------+-------------------+-------------------+-------------------+
| Connection Gateway      | EC2 + NLB         | GCE + Network LB  | VM + Azure LB     |
| (WebSocket servers)     | (TCP passthrough)  |                   |                   |
+-------------------------+-------------------+-------------------+-------------------+
| Message Queue           | Amazon MSK        | Cloud Pub/Sub     | Azure Event Hubs  |
| (Kafka)                 | (Managed Kafka)   | or Confluent      | (Kafka-compatible)|
+-------------------------+-------------------+-------------------+-------------------+
| Message Storage         | Amazon Keyspaces  | Cloud Bigtable    | Cosmos DB         |
| (Cassandra)             | or self-managed   | (similar model)   | (Cassandra API)   |
+-------------------------+-------------------+-------------------+-------------------+
| Presence + Cache        | ElastiCache       | Memorystore       | Azure Cache       |
| (Redis)                 | (Redis)           | (Redis)           | for Redis         |
+-------------------------+-------------------+-------------------+-------------------+
| User/Group Metadata     | Amazon RDS        | Cloud SQL         | Azure Database    |
| (PostgreSQL)            | (PostgreSQL)      | (PostgreSQL)      | for PostgreSQL    |
+-------------------------+-------------------+-------------------+-------------------+
| Media Storage           | S3                | Cloud Storage     | Blob Storage      |
+-------------------------+-------------------+-------------------+-------------------+
| CDN                     | CloudFront        | Cloud CDN         | Azure CDN         |
+-------------------------+-------------------+-------------------+-------------------+
| Push Notifications      | Amazon SNS +      | Firebase Cloud    | Azure             |
|                         | Pinpoint          | Messaging (FCM)   | Notification Hubs |
+-------------------------+-------------------+-------------------+-------------------+
| Service Orchestration   | ECS / EKS         | GKE               | AKS               |
| (Kubernetes)            |                   |                   |                   |
+-------------------------+-------------------+-------------------+-------------------+
| Monitoring              | CloudWatch +      | Cloud Monitoring  | Azure Monitor     |
|                         | X-Ray             | + Cloud Trace     | + App Insights    |
+-------------------------+-------------------+-------------------+-------------------+
```

---

## 21. Tradeoffs Summary

### Key Design Decisions

```
+---+---------------------------+------------------+------------------+-----------------------+
| # | Decision                  | Chosen           | Alternative      | Why                   |
+---+---------------------------+------------------+------------------+-----------------------+
| 1 | Real-time protocol        | WebSocket        | Long polling,    | Bidirectional, low    |
|   |                           |                  | SSE, HTTP/2      | latency, persistent.  |
|   |                           |                  |                  | 500M connections      |
|   |                           |                  |                  | need efficient proto. |
+---+---------------------------+------------------+------------------+-----------------------+
| 2 | Fan-out strategy          | Fan-out on WRITE | Fan-out on READ  | Chat requires instant |
|   |                           |                  |                  | delivery. Max group   |
|   |                           |                  |                  | 256 bounds the cost.  |
+---+---------------------------+------------------+------------------+-----------------------+
| 3 | Message storage           | Cassandra        | DynamoDB,        | Write-heavy, time-    |
|   |                           |                  | MongoDB,         | series, partition by  |
|   |                           |                  | PostgreSQL       | conversation. No      |
|   |                           |                  |                  | vendor lock-in.       |
+---+---------------------------+------------------+------------------+-----------------------+
| 4 | Message ordering          | Kafka partition  | Database         | Kafka guarantees      |
|   |                           | per conversation | sequence +       | partition ordering    |
|   |                           |                  | client reorder   | with high throughput. |
+---+---------------------------+------------------+------------------+-----------------------+
| 5 | Offline delivery          | Push (queue +    | Pull (client     | Users expect instant  |
|   |                           | sync on          | polls            | delivery on reconnect.|
|   |                           | reconnect)       | periodically)    | Push is more real-    |
|   |                           |                  |                  | time.                 |
+---+---------------------------+------------------+------------------+-----------------------+
| 6 | Presence tracking         | Heartbeat +      | Connection-based | Heartbeat handles     |
|   |                           | Redis TTL        | (online while    | network drops, half-  |
|   |                           |                  | connected)       | open connections.     |
|   |                           |                  |                  | TTL = auto-cleanup.   |
+---+---------------------------+------------------+------------------+-----------------------+
| 7 | Media handling            | Direct upload    | Upload through   | Chat servers avoid    |
|   |                           | to S3 (pre-      | chat servers     | handling large binary  |
|   |                           | signed URL)      |                  | payloads. S3 scales.  |
+---+---------------------------+------------------+------------------+-----------------------+
| 8 | Read receipt granularity  | Batch per        | Individual per   | Reduces status update |
|   |                           | conversation     | message          | traffic by 10-50x.   |
|   |                           | (last_read_id)   |                  |                       |
+---+---------------------------+------------------+------------------+-----------------------+
| 9 | Connection state          | Redis registry   | Sticky sessions  | Redis allows any      |
|   | management                | (userId->server) | (LB affinity)    | router to find any    |
|   |                           |                  |                  | user's server.        |
+---+---------------------------+------------------+------------------+-----------------------+
|10 | Delivery guarantee        | At-least-once    | Exactly-once     | At-least-once is      |
|   |                           | + client dedup   |                  | simpler and more      |
|   |                           |                  |                  | available. Client     |
|   |                           |                  |                  | dedups by messageId.  |
+---+---------------------------+------------------+------------------+-----------------------+
```

---

## 22. Interview Talking Points

### What to Proactively Mention

```
1. START with the hardest part: "The core challenge is maintaining
   500M persistent WebSocket connections and routing messages in
   real-time. Let me address this first."

2. DRAW the architecture immediately: Connection Gateway + Message
   Service + Kafka + Router. This is the skeleton everything hangs on.

3. WALK THROUGH the message flow: "Let me trace a message from
   Alice to Bob end-to-end." This shows depth.

4. CALL OUT ordering: "Message ordering is per-conversation, not
   global. We partition Kafka by conversationId to guarantee this."

5. ADDRESS the offline case unprompted: "What happens when Bob is
   offline? We queue in Redis, trigger push, and sync on reconnect."

6. DISCUSS fan-out for groups: "For a 256-member group, we batch
   fan-out by connection server to avoid thundering herd."

7. MENTION what you'd add with more time: "With more time, I'd
   discuss E2E encryption (Signal Protocol), multi-device sync,
   and message search indexing (Elasticsearch)."
```

### Time Allocation Guide (40-Minute Interview)

```
+-------+----+-----------------------------------------------+
| Phase | Min| Focus                                         |
+-------+----+-----------------------------------------------+
| 1     | 3  | Clarify requirements, state assumptions       |
|       |    | (500M DAU, 40B messages/day)                  |
+-------+----+-----------------------------------------------+
| 2     | 5  | High-level architecture: draw Connection      |
|       |    | Gateway, Message Service, Kafka, Router,      |
|       |    | Cassandra, Redis, S3                          |
+-------+----+-----------------------------------------------+
| 3     | 10 | CORE: Message delivery flow (1:1 and group).  |
|       |    | Walk through numbered steps. Cover online     |
|       |    | and offline cases. This is the heart of the   |
|       |    | interview -- spend the most time here.        |
+-------+----+-----------------------------------------------+
| 4     | 5  | Data model: message table in Cassandra,       |
|       |    | partition key, clustering key. Presence in    |
|       |    | Redis with TTL.                               |
+-------+----+-----------------------------------------------+
| 5     | 5  | Message ordering: Kafka partition key =       |
|       |    | conversationId. Sequence numbers. Client-     |
|       |    | side reordering.                              |
+-------+----+-----------------------------------------------+
| 6     | 5  | Scaling: 10K connection servers, stateless    |
|       |    | message service, Cassandra partitioning,      |
|       |    | Redis cluster.                                |
+-------+----+-----------------------------------------------+
| 7     | 5  | Read receipts, presence, offline handling.    |
|       |    | Group fan-out strategy.                       |
+-------+----+-----------------------------------------------+
| 8     | 2  | Tradeoffs: AP vs CP per component. At-least-  |
|       |    | once vs exactly-once. Fan-out on write vs     |
|       |    | read.                                         |
+-------+----+-----------------------------------------------+
```

### Common Follow-Up Questions and Answers

```
Q: "How do you handle a hot group with 256 very active members?"
A: "We batch fan-out by connection server. If 50 members are on the
   same server, we send one RPC with all 50 recipients. We also
   rate-limit: if a group gets >100 msgs/sec, we start batching
   notifications (collapse into 'N new messages')."

Q: "What if a connection server goes down?"
A: "Clients reconnect automatically to a new server via LB. The new
   connection overwrites the Redis registry entry. Messages queued
   during the ~2-second reconnect gap are drained from the offline
   queue. Effectively, the user experiences a brief disconnect."

Q: "How do you guarantee exactly-once delivery?"
A: "We don't -- exactly-once is extremely expensive in distributed
   systems. We guarantee at-least-once delivery with client-side
   deduplication using messageId (UUID). The client maintains a
   set of recently received messageIds and drops duplicates."

Q: "How do you handle message search?"
A: "Not in MVP, but I'd add Elasticsearch. When a message is
   persisted to Cassandra, also index it in ES (async via Kafka
   consumer). Search by keyword within a conversation. Partition
   ES index by conversationId for co-location."

Q: "How would you add end-to-end encryption?"
A: "Use the Signal Protocol (Double Ratchet). Each device has a
   public/private key pair. Messages are encrypted on the sender's
   device and decrypted on the recipient's device. The server never
   sees plaintext. Group chats use Sender Keys. This doesn't change
   the architecture -- it just adds an encryption layer at the client."

Q: "How do you handle multi-device (WhatsApp Web)?"
A: "Each device maintains its own WebSocket connection. The connection
   registry maps userId -> [device1_server, device2_server]. Message
   Router fans out to ALL devices. Sync state across devices via
   a device-specific sequence cursor in Cassandra."
```

### Key Differentiators (What Makes You Stand Out)

```
1. Know that WebSocket connections are STATEFUL -- this is the hardest
   part of the system. Connection registry in Redis is the solution.

2. Explain Kafka partition key = conversationId for ordering. Most
   candidates miss this.

3. Distinguish between online delivery (WebSocket push) and offline
   delivery (queue + sync). Show both flows.

4. Batch group fan-out by connection server. Don't do N separate RPCs.

5. Read receipts are batched per conversation (last_read_message_id),
   not per individual message. This reduces traffic dramatically.

6. Media bypasses chat servers entirely (pre-signed S3 URL). The
   message only contains a URL reference.
```

---

*This document is optimized for a 35-45 minute senior-level system design interview. Focus on the message delivery flow (Section 11) -- it is the core of the interview and demonstrates the deepest understanding.*
