# Chat System (WhatsApp-like) — Interview Prep

## Problem Summary

Design a real-time **1:1 and group messaging system** supporting presence (online/offline/last seen), read receipts, offline message delivery, media sharing, and typing indicators. Target: **500M DAU, 40B messages/day**.

---

## 1-Minute Interview Revision

- **500M DAU**, 40B messages/day, **460K msg/sec** peak
- **WebSocket** for real-time bidirectional communication
- **Connection Gateway -> Message Service -> Kafka -> Message Router -> Recipient**
- **Message ordering:** Kafka partition by `conversation_id` + sequence numbers
- **Presence:** Redis with TTL-based heartbeat (30s heartbeat, 60s timeout)
- **Offline:** Queue messages in Cassandra, deliver on reconnect + push notification via FCM/APNs
- **Read receipts:** checkmark sent, double-checkmark delivered, blue double-checkmark read
- **Fan-out:** On write for groups <256 members
- **DB:** Cassandra (messages), Redis (presence/connections), PostgreSQL (users/groups), S3 (media)
- **CAP:** Mixed -- AP for presence/delivery, CP-like for message ordering
- **Dedup:** Client-generated `message_id` (UUID) for idempotent processing

---

## Architecture Summary

```
  Mobile/Web ──► DNS (Geo) ──► Connection Gateway (WebSocket, stateful)
                                       │
                    ┌──────────────────┼──────────────────┐
                    ▼                  ▼                  ▼
              Redis: Connection    Redis: Presence    Redis: Typing
              Registry             TTL Heartbeat      Indicators
              (user→server)        (30s/60s)
                    │
                    ▼
              Message Service (stateless)
                    │
                    ▼
              ┌──────────┐
              │  Kafka    │  partition key = conversation_id
              └────┬─────┘
                   │
         ┌─────────┼─────────┐
         ▼         ▼         ▼
    Message     Message    Cassandra
    Router      Router     (persist)
         │         │
         ▼         ▼
    Connection  Connection      If offline:
    Server A    Server B   ──►  SNS → FCM/APNs
         │         │
         ▼         ▼
    Recipient   Recipient
```

---

## Key Components (One-Liner Each)

| Component | Purpose |
|-----------|---------|
| Connection Gateway | Manages WebSocket connections, authenticates clients, routes to message service |
| Connection Registry | Redis map of `userId -> serverId` so messages reach the right server |
| Message Service | Validates, assigns sequence number, persists to Cassandra, publishes to Kafka |
| Message Router | Consumes from Kafka, looks up connection registry, forwards to correct server |
| Presence Service | Tracks online/offline via Redis TTL heartbeat, publishes changes via pub/sub |
| Group Service | Manages group membership, handles fan-out to all members on write |
| Media Service | Uploads to S3, generates pre-signed URLs, sends URL as message payload |
| Push Service | Sends FCM/APNs notifications for offline users |
| Sync Service | Delivers queued messages when offline user reconnects |
| Read Receipt Handler | Processes delivered/read acks, updates message status, notifies sender |

---

## Key Tradeoffs

| Decision | Option A | Option B | Our Choice | Why |
|----------|----------|----------|-------------|-----|
| Real-time transport | WebSocket | Long polling | WebSocket | True bidirectional, lower latency, less overhead |
| Offline delivery | Push on reconnect | Client pulls | Push on reconnect + pull | Push for immediacy, pull for gap-fill |
| Group fan-out | Fan-out on write | Fan-out on read | On write (<256 members) | Groups are small, write-time fan-out is simpler |
| Message storage | Cassandra | DynamoDB | Cassandra | Better write throughput, flexible clustering key for time-range queries |
| Presence tracking | Centralized Redis | Per-server local | Centralized Redis | Consistent view, any server can check any user |
| Message ordering | Timestamps only | Sequence numbers | Sequence numbers | Timestamps have clock skew; sequence numbers are monotonic per conversation |

---

## Design Patterns

| Pattern | Where Used |
|---------|-----------|
| **Observer** | Presence changes notify all subscribers (contacts watching online status) |
| **Mediator** | Message Service mediates between sender and recipients without direct coupling |
| **Builder** | Constructing complex Message objects with optional fields (media, reply, forward) |
| **Strategy** | Delivery strategy varies by recipient state (online -> WebSocket, offline -> push) |
| **Command** | Each message/receipt/typing event is a command object processed asynchronously |
| **Repository** | Abstracts Cassandra/Redis/PostgreSQL access behind clean interfaces |
| **Factory** | Creates appropriate channel handler (1:1 vs group, text vs media) |

---

## CAP Summary

| Component | CAP Choice | Reasoning |
|-----------|-----------|-----------|
| Presence (Redis) | AP | Stale "online" status is acceptable; availability matters more |
| Message delivery | AP | At-least-once delivery + client dedup; never lose a message |
| Message ordering | CP-like | Kafka partitions guarantee order within a conversation |
| User/Group metadata | CP | PostgreSQL with replicas; correctness over availability |
| Connection registry | AP | Stale entry just means a failed delivery attempt + retry |

---

## Tech Stack Summary

| Layer | Technology | Why |
|-------|-----------|-----|
| Connection | Netty / Spring WebSocket | High-performance NIO for 50K+ connections per server |
| Message Queue | Apache Kafka | Partition-based ordering, high throughput, replay capability |
| Message DB | Apache Cassandra | Write-optimized, time-series friendly, linear scalability |
| Presence/Cache | Redis Cluster | Sub-ms lookups, TTL for heartbeat, pub/sub for notifications |
| User/Group DB | PostgreSQL | ACID for user profiles, group membership, contacts |
| Media Storage | S3 + CloudFront CDN | Durable object storage with global edge delivery |
| Push | FCM (Android), APNs (iOS) | Platform-native push for offline delivery |
| Monitoring | Prometheus + Grafana | Connection count, message latency p99, delivery success rate |

---

## Common Interview Follow-Up Questions

1. **How to maintain message ordering?**
   Kafka partition by `conversation_id` ensures all messages for a conversation go to the same partition (FIFO). Each message gets a monotonic sequence number. Clients reorder by sequence number on display.

2. **How to handle 500M concurrent WebSocket connections?**
   ~10,000 connection servers (50K connections each). Redis connection registry maps users to servers. DNS geo-routing distributes clients across regions.

3. **What happens when a connection server goes down?**
   Redis entries expire via TTL. Clients detect disconnect, reconnect to another server (exponential backoff). New server registers in Redis. Sync service delivers missed messages.

4. **How does fan-out work for a 256-member group?**
   Fan-out on write: message service looks up all group members, writes one Kafka message per recipient. Router delivers to each member's connection server. Offline members get push + queued delivery.

5. **How to implement read receipts efficiently?**
   Recipient sends `DELIVERED` ack on receive, `READ` ack on view. These are lightweight Kafka messages routed back to sender's connection server. Batch receipts for groups (don't send 256 individual acks).

6. **How to handle last seen / online status at scale?**
   Heartbeat every 30s updates Redis key with TTL=60s. If heartbeat stops, key expires = offline. Only publish presence changes to users who have the contact's chat open (not all contacts).

7. **How to support media (image/video) sharing?**
   Client uploads to S3 via pre-signed URL, gets back a media URL. Sends a message with `type=IMAGE` and the URL. Recipient downloads from CDN. Thumbnails generated async by Lambda.

8. **Push vs pull for message sync?**
   Hybrid: push via WebSocket when online, push notification when offline. On reconnect, client sends last known sequence number, server pushes all messages after that sequence (pull to fill gaps).

9. **How to handle duplicate message delivery?**
   Client generates a UUID `message_id` before sending. Server uses this as idempotency key. If same `message_id` arrives twice, server ignores the duplicate. Client-side dedup on display.

10. **What if Kafka goes down?**
    Kafka is deployed as a multi-broker cluster with replication factor 3. If a broker dies, partitions failover to replicas. If entire Kafka is down, fall back to synchronous delivery + write-ahead log.

11. **How to implement typing indicator?**
    Send `TYPING` event via WebSocket, routed to recipient's connection server. Use Redis pub/sub (not Kafka -- too heavyweight). Short TTL (3s) so indicator auto-clears if no follow-up.

12. **How to paginate message history efficiently?**
    Cassandra partition key = `conversation_id`, clustering key = `(timestamp, sequence_num) DESC`. Query: `SELECT * FROM messages WHERE conversation_id = ? AND timestamp < ? LIMIT 20`. Cursor-based pagination.

13. **How to handle message deletion?**
    Soft delete: set `deleted=true` flag, send `DELETE` event to all participants. Clients remove from UI. Hard delete after retention period. "Delete for everyone" sends delete event within a time window.

14. **How to scale the connection gateway layer?**
    Horizontal scaling: add more connection server pods behind ALB. Connection registry (Redis) decouples routing from server identity. Graceful drain: stop accepting new connections, wait for existing ones to disconnect or migrate.

---

## How to Run

```bash
cd 04-chat-system && ../gradlew run
```

---

## What to Improve Later

- [ ] End-to-end encryption (Signal Protocol — Diffie-Hellman key exchange, double ratchet)
- [ ] Multi-device sync (each device has its own encryption keys, message fan-out per device)
- [ ] Message search (Elasticsearch index on decrypted messages — conflicts with E2E encryption)
- [ ] Voice/video calling (WebRTC signaling via WebSocket, TURN/STUN servers)
- [ ] Status/Stories feature (fan-out on read, TTL-based expiry)
- [ ] Rate limiting per user to prevent spam
- [ ] Message reactions and threaded replies
- [ ] Geo-distributed deployment with cross-region message routing
