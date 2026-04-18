# Caching Strategy — Chat/Messaging System

> Caching in a chat system is not just about speed — it's about enabling
> real-time message routing. The connection registry and presence cache are
> on the critical path of every single message delivery.

---

## What to Cache

### 1. Recent Messages per Conversation (Last 50)

```
  User opens conversation --> load last 50 messages

  WITHOUT cache:
    Cassandra read (2-5ms per query, but adds up with many conversation opens)

  WITH cache:
    Redis: GET recent-msgs:{conversationId} --> hit? Return immediately
    Miss? Query Cassandra, populate Redis, return

  Cache key:   recent-msgs:{conversationId}
  Cache value:  Serialized list of last 50 messages (JSON)
  TTL:          30 minutes (refreshed on access)
  Eviction:     LRU (conversations not accessed recently get evicted)
```

**Why cache this**: Users re-open the same conversation dozens of times per day.
The top 3-5 conversations account for 80%+ of reads. Cache hit rate is very high.

**Invalidation**: On new message, append to cached list and trim to 50. On message
delete, remove from cached list.

```java
public List<Message> getRecentMessages(String conversationId, int limit) {
    // Try cache first
    String cached = redis.get("recent-msgs:" + conversationId);
    if (cached != null) {
        return deserialize(cached);
    }

    // Cache miss — load from Cassandra
    List<Message> messages = messageRepository.findByConversation(conversationId, limit);

    // Populate cache
    redis.setex("recent-msgs:" + conversationId, 1800, serialize(messages));

    return messages;
}

public void onNewMessage(Message message) {
    String key = "recent-msgs:" + message.getConversationId();
    // Append and trim (atomic with Lua script in production)
    String cached = redis.get(key);
    if (cached != null) {
        List<Message> messages = deserialize(cached);
        messages.add(0, message);  // newest first
        if (messages.size() > 50) messages = messages.subList(0, 50);
        redis.setex(key, 1800, serialize(messages));
    }
}
```

---

### 2. Group Membership (Redis Set)

```
  Cache key:    group-members:{groupId}
  Cache type:   Redis SET
  Cache value:  {"user-1", "user-2", ..., "user-500"}
  TTL:          1 hour (refreshed from PostgreSQL periodically)

  Checked on EVERY group message for fan-out:
    SMEMBERS group-members:group-123 --> all member IDs
    --> Route message to each member
```

**Why cache this**: Group membership is checked on every single group message
send. A 500-member group that gets 100 messages/minute = 100 PostgreSQL queries/
minute without cache. With Redis SET, each lookup is O(N) where N = member count,
but in-memory and sub-millisecond.

```
  Fan-out flow:

  Message arrives for group-123
       |
       v
  SMEMBERS group-members:group-123    <-- Redis (sub-ms)
       |
       v
  ["user-1", "user-2", ..., "user-500"]
       |
       v
  For each member:
    Check presence --> route via WebSocket or offline queue
```

---

### 3. User Presence (Redis with TTL)

```
  Cache key:    presence:{userId}
  Cache value:  "ONLINE" | "AWAY"
  TTL:          60 seconds (2x heartbeat interval of 30s)

  Write-through: EVERY heartbeat updates the cache
    SETEX presence:user-42 60 ONLINE

  Read on EVERY message send:
    GET presence:user-42
    --> ONLINE? Deliver via WebSocket
    --> null (expired)? User is offline, queue message
```

**Why cache this**: Presence is checked on every message send to decide the
delivery strategy (online vs offline). This is the hottest data in the entire
system.

---

### 4. Connection Registry (Redis Hash)

```
  Cache key:    connection:{userId}
  Cache value:  "server-3"   (which connection server holds the WebSocket)
  TTL:          120 seconds  (refreshed on heartbeat)

  Checked on EVERY message delivery to an online user:
    GET connection:user-42 --> "server-3"
    --> Forward message to server-3's internal endpoint
    --> server-3 pushes via WebSocket to user-42
```

**Why cache this**: Without this, we'd have to broadcast every message to ALL
connection servers and let them figure out who has the user. The registry turns
O(S) broadcast (S = server count) into O(1) targeted delivery.

---

### 5. User Profiles (Local In-Process Cache)

```
  Cache location:  Local (in-process ConcurrentHashMap or Caffeine cache)
  Cache key:       user-profile:{userId}
  Cache value:     {displayName, avatarUrl, ...}
  TTL:             5 minutes
  Max size:         10,000 entries (LRU eviction)

  Used for: Rendering message sender info (display name, avatar)
  in the recipient's chat window
```

**Why local, not Redis**: User profiles change rarely. A 5-minute stale profile
is invisible to users. Local cache avoids a Redis round-trip for every message
render. Each connection server caches profiles for its connected users.

```java
private final Cache<String, UserProfile> profileCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .build();

public UserProfile getProfile(String userId) {
    return profileCache.get(userId, id -> userRepository.findById(id).orElseThrow());
}
```

---

## What NOT to Cache

| Data | Why Not |
|------|---------|
| **Message delivery status** | Must be real-time accurate. If a message is shown as "delivered" but it wasn't, the sender assumes the recipient read it. Cache staleness is unacceptable. Query Cassandra directly. |
| **Conversation list** | Changes on every new message (reordering by most recent). Caching a list that changes every few seconds provides near-zero hit rate and adds invalidation complexity. |
| **Typing indicators** | Ephemeral, fire-and-forget. Delivered via WebSocket directly. No storage or caching needed. |
| **Media content** | Handled by CDN, not application-level cache. S3 + CloudFront/CDN handles edge caching. |

---

## Cache Strategies: Cache-Aside vs Write-Through

### Cache-Aside (Lazy Loading) -- For Messages

```
  Read path:
  +--------+     cache      +---------+
  | Service | ---GET------> | Redis   |
  |         | <--hit?-------|         |
  +--------+     |          +---------+
       |         |
       | miss    |
       v         |
  +-----------+  |
  | Cassandra |  |
  |           |--+-- populate cache on miss
  +-----------+
```

- **Used for**: Recent messages, user profiles
- **When**: Data is read more than written; cache misses are acceptable (cold start)
- **Tradeoff**: First read after eviction is slow (cache miss + DB query + cache write)

### Write-Through -- For Presence and Connection Registry

```
  Write path:
  +--------+     write      +---------+     write      +--------+
  | Service | ---SETEX----> | Redis   | (primary store)| No DB  |
  |         |               | (TTL)   |                | needed |
  +--------+               +---------+                +--------+

  Redis IS the primary store for presence.
  No separate database write. TTL = automatic cleanup.
```

- **Used for**: Presence, connection registry
- **When**: Every write MUST update the cache because reads happen on every message
- **Tradeoff**: Write latency includes cache write (but Redis is sub-ms)

### Comparison

| Aspect | Cache-Aside | Write-Through |
|--------|------------|---------------|
| Read miss penalty | Yes (DB query) | No (always in cache) |
| Write latency | Lower (no cache write on write path) | Slightly higher |
| Staleness | Possible (until TTL expires) | Never stale |
| Complexity | Lower | Higher (must ensure write succeeds) |
| Best for | Messages, profiles | Presence, connection registry |

---

## Eviction Strategies

### LRU for Messages

```
  Caffeine/Redis eviction: Least Recently Used

  Conversations accessed in last 30 min:
  [conv-A: 2min ago] [conv-B: 5min ago] [conv-C: 28min ago] [conv-D: 31min ago]
                                                                  ^
                                                             EVICTED (LRU)

  Why LRU: Users have 2-3 active conversations at any time.
  The rest are dormant and don't need to occupy cache space.
```

### TTL for Presence

```
  Presence key: presence:user-42
  TTL: 60 seconds

  Timeline:
  0s   -- heartbeat --> SETEX presence:user-42 60 ONLINE
  30s  -- heartbeat --> SETEX presence:user-42 60 ONLINE  (TTL reset)
  60s  -- heartbeat --> SETEX presence:user-42 60 ONLINE  (TTL reset)
  90s  -- NO heartbeat (user disconnected)
  120s -- TTL expires --> key deleted --> user is OFFLINE

  TTL = natural eviction. No explicit delete needed for crashed clients.
```

---

## Cache Consistency: Critical Scenarios

### Scenario: User Leaves/Blocks Group

```
  BEFORE (user is a member):
  SMEMBERS group-members:group-123 --> {..., "user-42", ...}
  Messages fan out to user-42 ✓

  User-42 leaves group:
  1. PostgreSQL: DELETE FROM group_members WHERE group_id='123' AND user_id='42'
  2. Redis: SREM group-members:group-123 user-42        <-- SYNCHRONOUS
  3. Return success to user-42

  AFTER:
  SMEMBERS group-members:group-123 --> {...}  (user-42 removed)
  Messages do NOT fan out to user-42 ✓

  CRITICAL: Step 2 MUST happen before returning success.
  If cache invalidation is async, there's a window where user-42
  still receives messages after leaving. This is a privacy violation.
```

### Scenario: User Blocks Another User

```
  User-A blocks User-B:
  1. PostgreSQL: INSERT INTO blocked_users (blocker, blocked) VALUES ('A', 'B')
  2. Redis/Local cache: Invalidate any cached data that would allow B to message A
  3. WebSocket: If B has A's conversation open, presence updates stop

  Must be synchronous — B must not be able to send messages to A after block.
```

### Scenario: Stale Presence (Acceptable)

```
  User-42 crashes (no clean disconnect):
  - Redis: presence:user-42 = ONLINE (TTL has 45s remaining)
  - For 45 seconds, user-42 appears online but is not

  This is ACCEPTABLE because:
  - Messages sent during this window go to WebSocket delivery
  - WebSocket delivery fails (connection is dead)
  - Fallback: message is queued in offline queue
  - User receives message when they reconnect
  - No message is lost
```

---

## Cache Architecture Overview

```
  +-------------------+
  | Connection Server  |
  |                   |
  | Local Caches:     |
  | - User profiles   |
  |   (Caffeine, 5m)  |
  +--------+----------+
           |
           | Redis calls for everything else
           v
  +-------------------+
  |   Redis Cluster    |
  |                   |
  | Presence (TTL)    |  <-- write-through, checked every message
  | Connection Reg    |  <-- write-through, checked every message
  | Group Members     |  <-- cache-aside, checked every group message
  | Offline Queues    |  <-- write-through, drained on connect
  | Recent Messages   |  <-- cache-aside, loaded on conversation open
  +--------+----------+
           |
           | Cache miss (messages, group members)
           v
  +-------------------+    +-------------------+
  | Cassandra          |    | PostgreSQL         |
  | (messages)         |    | (users, groups)    |
  +-------------------+    +-------------------+
```

---

## Interview Talking Points

### "Walk me through caching in your chat system."

> "We have two tiers. Local in-process cache (Caffeine) for user profiles — they
> change rarely, so 5-minute TTL is fine and avoids Redis round-trips. Redis handles
> everything on the critical message path: presence (write-through with TTL for
> automatic offline detection), connection registry (which server holds each user's
> WebSocket), group membership (Redis SET for O(1) membership checks and SMEMBERS
> for fan-out), offline message queues (Redis List drained on reconnect), and
> recent messages (cache-aside, populated on conversation open)."

### "How do you handle cache invalidation?"

> "Depends on the data. Presence uses TTL — no explicit invalidation needed.
> Group membership invalidation is synchronous and mandatory — when a user leaves
> a group, we SREM from the Redis SET before returning success. This is a privacy
> requirement. Message cache uses append-on-write — new messages are appended to
> the cached list rather than invalidating the whole cache. User profiles use
> TTL-based expiry — 5 minutes of staleness is invisible."

### "What's your cache hit rate?"

> "Presence and connection registry are essentially 100% hit rate because they're
> write-through — every write goes to Redis first. Group membership is 95%+ because
> groups are relatively stable. Recent messages hit rate depends on how many active
> conversations a user has — typically 80-90% because users cycle between 3-5
> conversations. Profile cache is 95%+ because the working set (contacts) is
> stable."

### "What if Redis goes down?"

> "Redis is deployed as a cluster with replicas. If the primary fails, a replica
> promotes automatically (< 1 second). During the failover window: presence checks
> fail, so we default to OFFLINE (safe — messages get queued rather than lost).
> Connection registry is unavailable, so we fall back to broadcasting to all
> connection servers (expensive but correct). Group membership falls back to
> PostgreSQL queries (slower but correct). The system degrades gracefully rather
> than failing."
