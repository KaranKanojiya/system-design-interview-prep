# CAP Theorem Analysis — Chat/Messaging System

> A chat system is one of the best interview examples for demonstrating **mixed
> CAP requirements** — different features need different tradeoffs.

---

## CAP Theorem Recap

```
            Consistency
               /\
              /  \
             /    \
            / pick \
           /  two   \
          /    of     \
         /    three    \
        /________________\
  Availability        Partition
                      Tolerance
```

In a distributed system, during a **network partition** you must choose:
- **CP** — Refuse to serve stale data. Block until partition heals.
- **AP** — Serve potentially stale data. Stay available.

**Partition tolerance is not optional** in any real distributed system. The real
choice is always between **C** and **A** during a partition.

---

## Mixed CAP Requirements (The Key Interview Insight)

A chat system does NOT have a single CAP choice. Different features have
different tolerance for staleness vs unavailability.

| Feature | CAP Choice | Rationale |
|---------|-----------|-----------|
| Presence (online/offline) | **AP** | Showing "online" for 30 extra seconds is harmless. Blocking presence entirely is worse. |
| Message ordering | **CP-like** | Messages MUST appear in order within a conversation. Delay delivery rather than deliver out of order. |
| Message delivery | **AP** | At-least-once. A duplicate is annoying; a lost message is unacceptable. |
| Group membership | **CP** | When a user leaves/is-removed, they must immediately stop receiving messages. Strong consistency. |
| Read receipts | **AP** | "Read" showing up 5 seconds late is fine. Blocking the conversation is not. |

### Why This Matters in Interviews

Most candidates say "I'd pick AP" or "I'd pick CP" for the entire system.
Discussing **per-feature CAP choices** shows mature distributed systems thinking.
The interviewer wants to hear that you understand the tradeoffs are granular.

---

## Deep Dive: Each Feature

### Presence: AP

```
  User A heartbeats every 30s
  +--------+                     +---------+
  | User A | ---heartbeat------> | Redis   |
  |        |    every 30s        | TTL=60s |
  +--------+                     +---------+
                                      |
                                      | TTL expires if no heartbeat
                                      | (partition or genuine disconnect)
                                      v
                                 Status: OFFLINE
```

- Redis key: `presence:{userId}` with TTL = 2x heartbeat interval
- During partition: user appears online for up to 60s after actual disconnect
- **This is acceptable** — WhatsApp shows "last seen" rather than real-time
- Alternative (CP): Block all presence queries during partition. Terrible UX.

### Message Ordering: CP-like

```
  Kafka Topic: messages
  +-----------+-----------+-----------+
  | Partition | Partition | Partition |
  |     0     |     1     |     2     |
  +-----------+-----------+-----------+
       |            |            |
   conv-A msgs  conv-B msgs  conv-C msgs
   (ordered)    (ordered)    (ordered)

  Partition key = conversation_id
  --> All messages for one conversation go to the same Kafka partition
  --> Kafka guarantees ordering within a partition
```

- Each message gets a `sequenceNumber` assigned by the server
- Client renders messages sorted by `sequenceNumber`, not by arrival time
- During partition: Kafka delays delivery rather than delivering out of order
- Client-side: if message #5 arrives before #4, buffer #5 until #4 arrives

### Message Delivery: AP (At-Least-Once)

```
  Sender             Server              Recipient
    |                   |                     |
    |--- send msg ----->|                     |
    |<-- server ACK ----|                     |
    |                   |--- deliver msg ---->|
    |                   |<-- delivery ACK ----|
    |<-- delivered -----|                     |
    |                   |                     |

  If delivery ACK is lost:
    Server retries delivery --> recipient gets duplicate
    Client deduplicates by messageId --> user sees it once
```

- Server retries until it gets an ACK from recipient (or recipient comes online)
- Duplicates are handled client-side via `messageId` deduplication
- **Lost message is catastrophic; duplicate is merely annoying (and filtered)**

### Group Membership: CP

```
  User leaves group
  +--------+        +------------+        +-----------+
  | User A | -----> | GroupService| -----> | PostgreSQL|
  | leaves |        | (write)    |        | (source   |
  +--------+        +------+-----+        |  of truth)|
                           |              +-----------+
                           | IMMEDIATE cache invalidation
                           v
                    +-------------+
                    | Redis cache |
                    | remove A    |
                    | from group  |
                    +-------------+
```

- When a user leaves/is-removed/blocks the group, they MUST stop receiving messages
- Cache invalidation is synchronous — do NOT return success until cache is updated
- During partition: fail the operation rather than risk delivering messages to
  someone who left. This is a legal/privacy requirement (think: harassment scenarios).

### Read Receipts: AP

- Eventual consistency is fine — "read" arriving 5s late is invisible to users
- Store in Cassandra, replicate asynchronously
- During partition: read receipt is queued and delivered when partition heals
- No user has ever complained about a read receipt being 5 seconds late

---

## Delivery Guarantees

| Guarantee | Behavior | Use In Chat |
|-----------|----------|-------------|
| **At-most-once** | Fire and forget. May lose messages. | NEVER for chat messages. Acceptable for typing indicators. |
| **At-least-once** | Retry until ACK. May duplicate. | Messages, read receipts, presence updates. |
| **Exactly-once** | No loss, no duplicates. Very expensive. | Not practical at scale. Simulated via at-least-once + idempotency. |

### How We Simulate Exactly-Once

```
  At-least-once delivery  +  Client-side dedup by messageId  =  "Effectively once"

  Client maintains a Set<String> of seen messageIds
  On receive:
    if (seenIds.contains(msg.messageId)) {
        // Discard duplicate
        return;
    }
    seenIds.add(msg.messageId);
    displayMessage(msg);
```

Server-side idempotency for persistence:

```java
// Cassandra INSERT is idempotent by nature (same primary key = upsert)
// messageId is part of the primary key
// Re-inserting the same message is a no-op
INSERT INTO messages (conversation_id, sequence_number, message_id, ...)
VALUES (?, ?, ?, ...)
```

---

## Idempotency

| Operation | Idempotency Key | Mechanism |
|-----------|----------------|-----------|
| Send message | `messageId` (client-generated UUID) | Cassandra upsert by primary key |
| Deliver message | `messageId` | Client-side dedup set |
| Read receipt | `messageId + readerId` | Cassandra upsert |
| Presence update | `userId + timestamp` | Redis SET overwrites previous value |

**Key insight**: The client generates the `messageId` (UUID) before sending.
This means retries at any layer (network, Kafka, server) are safe because
the messageId is the same.

---

## Split-Brain Scenario

### Problem

Two connection servers think the same user is connected.

```
  User A connects to Server 1
  Network blip -- Server 1 doesn't detect disconnect
  User A reconnects to Server 2

  Connection Registry (Redis):
    user-A -> server-1    (stale)
    user-A -> server-2    (current)

  Which server gets the message?
```

### Solution

```
  1. Connection registry uses Redis with overwrite semantics
     SET connection:{userId} server-2
     (overwrites server-1 entry)

  2. Server-1 still thinks user is connected
     --> Server-1 tries to send via its local WebSocket
     --> WebSocket is dead (user disconnected)
     --> Server-1 detects failure, removes its stale state

  3. Meanwhile, message is ALSO sent to server-2 (current entry)
     --> User receives the message

  4. At-least-once means user might get it from both attempts
     --> Client dedup by messageId handles this
```

### Prevention

- Heartbeat with timeout: server evicts connection if no heartbeat for 2 intervals
- Connection versioning: each connection gets an incrementing version number;
  server ignores messages for old versions
- Redis pub/sub: when a new connection registers, old server is notified to
  clean up its stale WebSocket

---

## Practical Interview Answer

> "Our chat system uses mixed CAP. Presence and read receipts are AP — eventual
> consistency is fine because a 30-second stale presence or a 5-second late read
> receipt doesn't impact user experience. Message ordering is CP-like — we use
> Kafka partition ordering by conversation_id plus server-assigned sequence
> numbers, and we'd rather delay delivery than deliver out of order. Message
> delivery is AP with at-least-once semantics — we retry until ACK'd and dedup
> on the client by messageId. Group membership is CP — when a user leaves,
> they must immediately stop receiving messages, so we fail the operation
> during a partition rather than risk delivering messages to someone who left."

---

## Follow-Up Q&A

### Q1: "What if Kafka goes down?"

**A**: Kafka is designed for partition tolerance with replication factor 3 and
ISR (in-sync replicas). If a broker dies, the partition leader fails over to
another broker. If the entire Kafka cluster is unavailable (rare), we buffer
messages in the connection server's local memory (bounded queue) and retry.
This is a temporary AP choice — we accept potential message loss if the buffer
overflows, but the buffer should hold minutes of traffic.

### Q2: "How do you handle clock skew for message ordering?"

**A**: We do NOT rely on wall-clock timestamps for ordering. The server assigns
a monotonically increasing `sequenceNumber` per conversation. This is generated
using an atomic counter (Redis INCR on `seq:{conversationId}`) or Kafka's offset
within the partition. Clocks are only used for display ("2:34 PM"), never for
ordering.

### Q3: "Why not exactly-once delivery?"

**A**: True exactly-once requires distributed transactions across the delivery
pipeline — Kafka consumer, Cassandra write, WebSocket send. This adds latency
and complexity. Instead, we use at-least-once with idempotent operations. The
messageId (client-generated UUID) makes every operation idempotent: Cassandra
upserts by messageId, clients dedup by messageId. The result is "effectively
once" at a fraction of the cost.

### Q4: "What happens when a user has been offline for 2 weeks?"

**A**: Offline messages are queued in Redis Lists (bounded to last N messages
per user). When the user comes online:
1. Drain the Redis queue (most recent messages)
2. For older messages, the client fetches from Cassandra using cursor-based
   pagination (conversation_id partition, sequence_number clustering key DESC)
3. TTL on Cassandra messages (e.g., 90 days for free users) prevents unbounded
   storage growth

### Q5: "How do you handle message ordering in group chats with 1000 members?"

**A**: All messages for a group conversation go to the same Kafka partition
(partition key = conversation_id). Kafka guarantees ordering within a partition.
The server assigns a `sequenceNumber` atomically. Fan-out to 1000 members
happens AFTER ordering — each member receives the messages in the same order.
The fan-out is parallel (1000 WebSocket sends or queue insertions), but the
source ordering is serial through a single Kafka partition.

### Q6: "Isn't a single Kafka partition a bottleneck for a hot group?"

**A**: Yes, a single partition limits throughput to ~10K messages/sec for one
conversation. For a chat group, this is more than enough — even 1000-member
groups rarely exceed 100 messages/sec. If we needed higher throughput (broadcast
channel), we'd shard the fan-out: one Kafka partition for ordering, then fan
out to multiple worker partitions for delivery. Ordering is preserved because
the fan-out workers process sequentially by sequenceNumber.
