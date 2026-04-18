# Chat System — Interview Walkthrough

> **This is a HARD problem.** Pacing matters. The core challenge is the stateful WebSocket layer + message ordering + offline delivery. Budget 35-40 minutes. If short on time, summarize Phases 7-8 in one sentence each.

---

## 30-Second Elevator Pitch

> "I'd design this as a **WebSocket-based real-time messaging system** with a clear separation between the **stateful connection layer** (which server holds which user's WebSocket) and the **stateless message processing layer** (validate, persist, route). Messages flow through **Kafka partitioned by conversation_id** for ordering guarantees. **Cassandra** stores messages (write-heavy, time-series), **Redis** tracks presence via TTL heartbeats and maps users to connection servers. Offline users get messages queued and delivered on reconnect plus a push notification."

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

| Question | Expected Answer | Why It Matters |
|----------|----------------|----------------|
| 1:1 only or groups too? | Both | Group fan-out is a key design decision |
| Max group size? | 256 members | Fan-out on write is viable (not like Twitter's millions) |
| Presence (online/offline)? | Yes | Need heartbeat system, adds Redis dependency |
| Read receipts? | Yes | Adds receipt routing back to sender |
| Media (images/video)? | Yes | Need S3/CDN, separate upload flow |
| E2E encryption? | Mention, don't deep dive | Signal Protocol — acknowledge but don't design it |
| Message history/search? | Basic history | Cassandra pagination, search is a follow-up |
| Scale? | 500M DAU | This drives the entire connection server architecture |

### Functional Requirements
- Send/receive messages in real-time (1:1 and group)
- Online/offline presence with last seen
- Read receipts (sent, delivered, read)
- Offline message delivery
- Media sharing (images, video, documents)

### Non-Functional Requirements
- **Low latency:** <100ms for online-to-online delivery
- **Reliability:** No message loss (at-least-once delivery)
- **Ordering:** Messages appear in correct order within a conversation
- **Scale:** 500M DAU, 460K messages/sec

---

## Phase 2: Traffic Estimation (2-3 min)

### Back-of-Envelope

| Metric | Calculation | Value |
|--------|------------|-------|
| DAU | Given | 500M |
| Messages per user per day | ~80 | 80 |
| Messages per day | 500M x 80 | **40B messages/day** |
| Messages per second | 40B / 86,400 | **~460K msg/sec** |
| Peak (3x average) | 460K x 3 | **~1.4M msg/sec** |
| Concurrent connections | 40% of DAU | **200M connections** |
| Connections per server | ~50K (Java/Netty) | |
| **Connection servers** | 200M / 50K | **~4,000 (8-10K with headroom)** |

### Storage

| Data | Size | Daily | Monthly |
|------|------|-------|---------|
| Text message | ~100 bytes | 40B x 100B = **4TB/day** | 120TB |
| Metadata per message | ~200 bytes | 40B x 200B = **8TB/day** | 240TB |
| Media (10% of messages) | ~200KB avg | 4B x 200KB = **800TB/day** | 24PB |

---

## Phase 3: API Design (2-3 min)

### WebSocket Events (Real-time)

```
// Client → Server
SEND_MESSAGE    { conversation_id, content, type, client_msg_id }
TYPING          { conversation_id }
READ_RECEIPT    { conversation_id, last_read_seq }
HEARTBEAT       { }

// Server → Client
NEW_MESSAGE     { message_id, conversation_id, sender_id, content, seq_num, timestamp }
DELIVERY_ACK    { message_id, status: SENT|DELIVERED|READ }
PRESENCE_UPDATE { user_id, status: ONLINE|OFFLINE, last_seen }
TYPING_UPDATE   { conversation_id, user_id }
```

### REST APIs (Non-real-time)

```
GET  /api/conversations                          → list conversations
GET  /api/conversations/{id}/messages?before=seq  → paginated history
POST /api/conversations                          → create group
POST /api/media/upload                           → get pre-signed S3 URL
GET  /api/users/{id}/presence                    → check presence
```

### Message Payload Example

```json
{
  "client_msg_id": "uuid-generated-by-client",
  "conversation_id": "conv_123",
  "sender_id": "user_456",
  "content": "Hello!",
  "type": "TEXT",
  "timestamp": 1713456789000,
  "seq_num": 42
}
```

---

## Phase 4: High-Level Architecture (5-7 min) -- CORE

> **This is the most important phase. Draw this diagram and explain the stateful vs stateless split.**

```
                    ┌──────────────────────────────────────────┐
                    │           Mobile / Web Clients           │
                    └──────────────────┬───────────────────────┘
                                       │ WebSocket
                    ┌──────────────────▼───────────────────────┐
                    │     Connection Gateway (STATEFUL)         │
                    │  ┌─────────┐ ┌─────────┐ ┌─────────┐    │
                    │  │Server 1 │ │Server 2 │ │Server N │    │
                    │  │ 50K WS  │ │ 50K WS  │ │ 50K WS  │    │
                    │  └────┬────┘ └────┬────┘ └────┬────┘    │
                    └───────┼──────────┼──────────┼───────────┘
                            │          │          │
                    ┌───────▼──────────▼──────────▼───────────┐
                    │         Redis Cluster                     │
                    │  Connection Registry: user → server       │
                    │  Presence: user → {online, last_seen}     │
                    └──────────────────┬───────────────────────┘
                                       │
                    ┌──────────────────▼───────────────────────┐
                    │     Message Service (STATELESS)           │
                    │  Validate → Assign seq_num → Persist      │
                    └──────────────────┬───────────────────────┘
                                       │
                    ┌──────────────────▼───────────────────────┐
                    │              Kafka                        │
                    │  Partition key = conversation_id          │
                    │  Topics: messages, receipts, presence     │
                    └──────────────────┬───────────────────────┘
                                       │
                    ┌──────────────────▼───────────────────────┐
                    │          Message Router                   │
                    │  Consume → Lookup connection registry     │
                    │        → Forward to correct server        │
                    │  If offline → Push notification + queue   │
                    └──────────────────────────────────────────┘
                                       │
              ┌────────────────────────┼────────────────────┐
              ▼                        ▼                    ▼
        Cassandra               PostgreSQL              S3 + CDN
     (messages, PK:          (users, groups,          (media files,
      conversation_id)        contacts)               pre-signed URLs)
```

### Key Insight to State in Interview

> "The **connection gateway is stateful** — it must know which WebSocket belongs to which user. The **message service is stateless** — any instance can process any message. This split is the core architectural decision. The **Redis connection registry** bridges the two: it maps `userId -> serverId` so the message router knows where to deliver."

---

## Phase 5: Message Delivery Flow (5-7 min) -- MOST ASKED

### 1:1 Message: Step by Step

```
Alice sends "Hello" to Bob
```

| Step | Action | Component |
|------|--------|-----------|
| 1 | Alice's client generates `client_msg_id` (UUID) and sends via WebSocket | Client |
| 2 | Connection Server receives message, forwards to Message Service | Connection Gateway |
| 3 | Message Service validates (auth, rate limit, content) | Message Service |
| 4 | Assigns monotonic `seq_num` for this conversation | Message Service |
| 5 | Persists message to Cassandra | Cassandra |
| 6 | Sends `SENT` ack back to Alice (checkmark) | Connection Gateway |
| 7 | Publishes message to Kafka (partition = conversation_id) | Kafka |
| 8 | Message Router consumes, looks up Bob in Redis connection registry | Message Router |
| 9a | **Bob online:** Forward to Bob's connection server, deliver via WebSocket | Connection Gateway |
| 9b | **Bob offline:** Store in offline queue, send push notification via FCM/APNs | Push Service |
| 10 | Bob's client sends `DELIVERED` ack (double checkmark routed back to Alice) | Client |

### Group Message: Fan-Out

```
Alice sends "Hello team" to Group (50 members)
```

1. Steps 1-7 same as 1:1
2. Message Router looks up all 50 group members
3. For each member: look up connection registry, forward to their server
4. Offline members: queue + push notification
5. **Optimization:** Batch members by connection server (if 10 members on server-5, send once)

### Offline Delivery

1. Bob comes online, WebSocket connection established
2. Bob's client sends last known `seq_num` per conversation
3. Sync Service queries Cassandra: `WHERE conversation_id = ? AND seq_num > last_known`
4. Delivers all missed messages in order
5. Clears offline queue entries

---

## Phase 6: Message Ordering (3-4 min)

### Why Timestamps Are Not Enough

- Clock skew between devices (mobile clocks are unreliable)
- Two messages sent at "same" millisecond
- Network delays: message sent first may arrive second

### Solution: Kafka Partition + Sequence Numbers

```
Kafka partition key = conversation_id
  → All messages for conv_123 go to same partition
  → Kafka guarantees FIFO within a partition

Message Service assigns seq_num:
  → Atomic increment per conversation (Redis INCR or Cassandra LWT)
  → seq_num is the source of truth for ordering

Client-side:
  → Display messages sorted by seq_num
  → If message arrives out of order, buffer and reorder
```

### Interview Talking Points

- **Single-partition ordering** is Kafka's guarantee -- no need for global ordering
- **Sequence numbers vs Lamport clocks:** Sequence numbers are simpler and sufficient for chat (single authority assigns them). Lamport clocks are for distributed systems without a central sequencer.
- **Conflict:** Two users send at the same instant? Both get different `seq_num` from the atomic counter. First-write-wins on the counter.

---

## Phase 7: Presence System (2-3 min)

### Heartbeat Design

```
Client ──(every 30s)──► HEARTBEAT ──► Connection Server ──► Redis SET user:123 TTL=60s

If heartbeat stops for 60s → Redis key expires → user is OFFLINE
```

| Parameter | Value | Reasoning |
|-----------|-------|-----------|
| Heartbeat interval | 30 seconds | Balance between freshness and overhead |
| TTL | 60 seconds | 2x heartbeat — tolerates one missed heartbeat |
| Status values | ONLINE, OFFLINE, last_seen timestamp | |

### Presence Fan-Out (Scalability Challenge)

**Naive:** When Alice goes online, notify all 500 contacts. At scale: 500M users x 500 contacts = **250B notifications/day** just for presence.

**Optimized approach:**
1. Only notify users who have Alice's chat **currently open**
2. For contact list, **pull presence on demand** when user opens the app
3. Subscribe to presence changes only for **visible conversations**
4. Use Redis pub/sub: subscribe to `presence:user_123` channel only when needed

---

## Phase 8: Database and Storage (3 min)

### Cassandra — Messages

```sql
CREATE TABLE messages (
    conversation_id UUID,
    seq_num         BIGINT,
    message_id      UUID,
    sender_id       UUID,
    content         TEXT,
    type            TEXT,      -- TEXT, IMAGE, VIDEO, DOCUMENT
    media_url       TEXT,
    created_at      TIMESTAMP,
    deleted         BOOLEAN,
    PRIMARY KEY (conversation_id, seq_num)
) WITH CLUSTERING ORDER BY (seq_num DESC);
```

**Why Cassandra:**
- Write-heavy (460K writes/sec)
- Partition by `conversation_id` = all messages for a chat on same node
- Clustering by `seq_num DESC` = latest messages first (pagination)
- Linear horizontal scalability

### Redis — Presence and Connection Registry

```
# Connection registry
SET conn:user_123 "ws-server-7" EX 90

# Presence
SET presence:user_123 "{status:ONLINE, last_seen:1713456789}" EX 60

# Online user set (for contact list queries)
SADD online_users user_123
```

### PostgreSQL — Users, Groups, Contacts

- User profiles, group metadata, group membership
- Small dataset, complex queries (find mutual contacts, group admin permissions)
- ACID guarantees for membership changes

### S3 — Media

- Pre-signed upload URL (client uploads directly, bypasses our servers)
- CloudFront CDN for download
- Thumbnails generated async (Lambda trigger on S3 upload)

---

## Phase 9: Tradeoffs and CAP (2-3 min)

### Delivery Guarantees

| Guarantee | Approach |
|-----------|----------|
| **At-least-once** delivery | Kafka consumer commits offset after delivery. If crash, re-delivers. |
| **Client-side dedup** | `client_msg_id` (UUID) ensures duplicate messages are ignored on display |
| **No message loss** | Persist to Cassandra before sending `SENT` ack. Even if Kafka dies, message is safe. |

### CAP Choices

| Component | Choice | Impact |
|-----------|--------|--------|
| Presence | AP | User might show "online" for 60s after disconnect — acceptable |
| Message delivery | AP | At-least-once with dedup — never lose a message |
| Message ordering | CP-like | Kafka single-partition FIFO + sequence numbers |
| Group membership | CP | PostgreSQL — must be consistent for fan-out correctness |

### Key Tradeoff Discussion Points

- **Consistency vs latency:** We ack `SENT` after Cassandra write but before Kafka publish. If Kafka is slow, message is still safe but delivery is delayed.
- **Memory vs connections:** More RAM per server = more connections = fewer servers = lower cost. This is why Erlang (2M connections/server) beats Java (50K connections/server).
- **Fan-out on write vs read:** For groups <256, fan-out on write. For broadcast channels with millions of subscribers, fan-out on read.

---

## Red Flags in Interview

| Red Flag | Why It's Wrong |
|----------|---------------|
| Using HTTP polling instead of WebSocket | Massive overhead, high latency |
| Ignoring the stateful nature of WebSocket servers | Core architectural challenge |
| Single database for everything | Can't handle 460K writes/sec in PostgreSQL |
| Global message ordering | Unnecessary and impossible at scale — per-conversation ordering suffices |
| Sending presence updates to all contacts eagerly | 250B notifications/day — doesn't scale |
| Skipping offline delivery | Most users are offline most of the time |

## Green Flags in Interview

| Green Flag | Why It Impresses |
|------------|-----------------|
| Separating stateful (connection) from stateless (message processing) layers | Shows deep understanding |
| Redis connection registry for WebSocket routing | Production-grade approach |
| Kafka partition by conversation_id for ordering | Correct use of Kafka guarantees |
| Client-generated message_id for dedup | Shows understanding of at-least-once |
| Heartbeat + TTL for presence | Simple, scalable, battle-tested |
| Pre-signed URLs for media upload | Offloads bandwidth from application servers |

---

## Time Allocation Summary

| Phase | Time | Priority |
|-------|------|----------|
| 1. Requirements | 2-3 min | Must do |
| 2. Estimation | 2-3 min | Must do |
| 3. API Design | 2-3 min | Must do |
| 4. Architecture | 5-7 min | **CORE — spend time here** |
| 5. Message Flow | 5-7 min | **MOST ASKED — nail this** |
| 6. Ordering | 3-4 min | Must do |
| 7. Presence | 2-3 min | Skip if short on time |
| 8. DB & Storage | 3 min | Skip if short on time |
| 9. Tradeoffs | 2-3 min | Must do |
| **Total** | **26-36 min** | |

> **If you only have 25 minutes:** Do Phases 1-6 and 9. Mention presence and DB choices in one sentence each: "Presence via Redis TTL heartbeat, messages in Cassandra partitioned by conversation_id."
