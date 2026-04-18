# Design Patterns — Chat/Messaging System

> Quick reference for system design interviews. Each pattern includes why it fits,
> a code sketch, an ASCII diagram, and a one-liner you can drop in an interview.

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Observer | Behavioral | Presence, delivery, read receipts |
| 2 | Mediator | Behavioral | ChatService orchestration |
| 3 | Builder | Creational | Message construction |
| 4 | Strategy | Behavioral | Online vs offline routing |
| 5 | Command | Behavioral | Message lifecycle |
| 6 | Repository | Structural (enterprise) | Data access abstraction |
| 7 | Factory | Creational | AppConfig wiring |

---

## 1. Observer Pattern (Behavioral)

**The dominant pattern in this system.** Three independent subscription channels
share the same mechanics.

### Where It Applies

| Subject | Event | Observers |
|---------|-------|-----------|
| PresenceService | User goes online/offline | All contacts watching that user |
| MessageService | Message delivered / read | Original sender |
| GroupService | Member joins / leaves | All current group members |

### ASCII Diagram

```
  PresenceService (Subject)
  +---------------------------+
  | - observers: Map<userId,  |
  |       Set<Observer>>      |
  +---------------------------+
  | + subscribe(userId, obs)  |
  | + unsubscribe(userId,obs) |
  | + notifyPresenceChange()  |
  +---------------------------+
           |
           | notifyPresenceChange("user-42", ONLINE)
           |
     +-----+-----+-----+
     |           |           |
  Observer A  Observer B  Observer C
  (Contact)   (Contact)   (Contact)
  "user-42    "user-42    "user-42
   is online"  is online"  is online"
```

### Code Sketch

```java
public interface PresenceObserver {
    void onPresenceChanged(String userId, PresenceStatus status);
}

public class PresenceService {
    // userId -> set of observers watching that user
    private final Map<String, Set<PresenceObserver>> watchers = new ConcurrentHashMap<>();

    public void subscribe(String userId, PresenceObserver observer) {
        watchers.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(observer);
    }

    public void unsubscribe(String userId, PresenceObserver observer) {
        Set<PresenceObserver> set = watchers.get(userId);
        if (set != null) set.remove(observer);
    }

    public void updatePresence(String userId, PresenceStatus status) {
        // Persist to Redis with TTL
        redisClient.setex("presence:" + userId, HEARTBEAT_TTL, status.name());

        // Notify all watchers
        Set<PresenceObserver> set = watchers.getOrDefault(userId, Set.of());
        for (PresenceObserver obs : set) {
            obs.onPresenceChanged(userId, status);
        }
    }
}
```

### Interview One-Liner

> "Presence, delivery receipts, and group membership changes all use Observer —
> the subject maintains a subscriber list and pushes events, so we get O(1)
> registration and fan-out proportional to the number of watchers."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Loose coupling — subject doesn't know observer types | Memory overhead for large subscriber sets |
| Easy to add new event consumers | Ordering of notifications not guaranteed |
| Natural fit for push-based real-time systems | Observer leak if unsubscribe is missed (use WeakReferences or TTL) |
| Fan-out is explicit and auditable | In distributed mode, need pub/sub (Redis Pub/Sub or Kafka) instead of in-process |

---

## 2. Mediator Pattern (Behavioral)

### Why

A chat system has many services that could talk to each other (MessageService,
GroupService, PresenceService, Router, NotificationService). Without a mediator,
you get N*(N-1) dependencies. ChatService acts as the central coordinator.

### ASCII Diagram

```
                         +----------------+
                         |  ChatService   |
                         |   (Mediator)   |
                         +-------+--------+
                                 |
         +-----------+-----------+-----------+-----------+
         |           |           |           |           |
   +-----------+ +-----------+ +-----------+ +-------+ +--------+
   | Message   | | Group     | | Presence  | | Router| | Notif  |
   | Service   | | Service   | | Service   | |       | | Service|
   +-----------+ +-----------+ +-----------+ +-------+ +--------+

   Services NEVER call each other directly.
   All coordination flows through ChatService.
```

### Code Sketch

```java
public class ChatService {
    private final MessageService messageService;
    private final GroupService groupService;
    private final PresenceService presenceService;
    private final MessageRouter router;
    private final NotificationService notificationService;

    public void sendMessage(SendMessageRequest request) {
        // 1. Validate via GroupService (if group msg)
        if (request.isGroupMessage()) {
            groupService.validateMembership(request.getSenderId(), request.getConversationId());
        }

        // 2. Build and persist message
        Message message = messageService.createMessage(request);

        // 3. Check recipient presence and route
        List<String> recipientIds = resolveRecipients(request);
        for (String recipientId : recipientIds) {
            PresenceStatus status = presenceService.getPresence(recipientId);
            router.route(message, recipientId, status);
        }

        // 4. Push notifications for offline users
        notificationService.sendPushIfOffline(message, recipientIds);
    }
}
```

### Interview One-Liner

> "ChatService is a Mediator — it coordinates MessageService, GroupService,
> PresenceService, and Router so those services stay decoupled from each other
> and only depend on the mediator."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Reduces N*(N-1) dependencies to N | Mediator can become a god object |
| Single place to understand message flow | Single point of failure (mitigate with stateless instances) |
| Easy to add new services without modifying existing ones | Risk of business logic leaking into mediator |

---

## 3. Builder Pattern (Creational)

### Why

A `Message` has many fields, many optional (media, replyTo, forwardedFrom,
mentions, reactions, expiresAt). Telescoping constructors would be unreadable.

### Code Sketch

```java
public class Message {
    private final String messageId;
    private final String senderId;
    private final String conversationId;
    private final String content;
    private final MessageType type;
    private final long timestamp;
    // Optional fields
    private final String mediaUrl;
    private final String replyToMessageId;
    private final String forwardedFrom;
    private final List<String> mentions;
    private final long expiresAt;

    private Message(Builder builder) {
        this.messageId = builder.messageId;
        this.senderId = builder.senderId;
        this.conversationId = builder.conversationId;
        this.content = builder.content;
        this.type = builder.type;
        this.timestamp = builder.timestamp;
        this.mediaUrl = builder.mediaUrl;
        this.replyToMessageId = builder.replyToMessageId;
        this.forwardedFrom = builder.forwardedFrom;
        this.mentions = builder.mentions;
        this.expiresAt = builder.expiresAt;
    }

    public static class Builder {
        // Required
        private final String messageId;
        private final String senderId;
        private final String conversationId;
        private final String content;
        // Defaults
        private MessageType type = MessageType.TEXT;
        private long timestamp = System.currentTimeMillis();
        // Optional
        private String mediaUrl;
        private String replyToMessageId;
        private String forwardedFrom;
        private List<String> mentions = List.of();
        private long expiresAt = 0;

        public Builder(String messageId, String senderId,
                       String conversationId, String content) {
            this.messageId = messageId;
            this.senderId = senderId;
            this.conversationId = conversationId;
            this.content = content;
        }

        public Builder type(MessageType type) { this.type = type; return this; }
        public Builder mediaUrl(String url) { this.mediaUrl = url; return this; }
        public Builder replyTo(String msgId) { this.replyToMessageId = msgId; return this; }
        public Builder forwardedFrom(String fwd) { this.forwardedFrom = fwd; return this; }
        public Builder mentions(List<String> m) { this.mentions = m; return this; }
        public Builder expiresAt(long ts) { this.expiresAt = ts; return this; }

        public Message build() { return new Message(this); }
    }
}

// Usage
Message msg = new Message.Builder(uuid, senderId, convId, "Hello!")
    .type(MessageType.TEXT)
    .replyTo(previousMsgId)
    .mentions(List.of("user-7", "user-12"))
    .build();
```

### Interview One-Liner

> "Message uses Builder because it has 10+ fields with many optional —
> it gives us readable construction, immutability, and validation in build()."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Readable, self-documenting construction | More boilerplate (Lombok @Builder mitigates) |
| Immutable objects — safe for concurrent access | Slight overhead from extra object allocation |
| Validation in build() catches bad state early | N/A |

---

## 4. Strategy Pattern (Behavioral)

### Why

When a message is sent, the delivery mechanism depends on the recipient's current
presence. Online users get real-time WebSocket delivery. Offline users get queued.
The routing logic shouldn't have if/else chains — it should delegate to a strategy.

### ASCII Diagram

```
  MessageRouter
  +-----------------------------+
  | - strategies: Map<Status,   |
  |     DeliveryStrategy>       |
  +-----------------------------+
  | + route(msg, recipientId,   |
  |         status)             |
  +-----------------------------+
           |
           | strategy = strategies.get(status)
           | strategy.deliver(msg, recipientId)
           |
     +-----+-----------+
     |                  |
  +--------+      +-----------+
  | Online |      | Offline   |
  |Strategy|      | Strategy  |
  +--------+      +-----------+
  | Send   |      | Push to   |
  | via WS |      | Redis     |
  | conn   |      | queue +   |
  +--------+      | send push |
                  | notif     |
                  +-----------+
```

### Code Sketch

```java
public interface DeliveryStrategy {
    void deliver(Message message, String recipientId);
}

public class OnlineDeliveryStrategy implements DeliveryStrategy {
    private final ConnectionRegistry connectionRegistry;
    private final WebSocketManager wsManager;

    @Override
    public void deliver(Message message, String recipientId) {
        String serverId = connectionRegistry.getServer(recipientId);
        wsManager.sendToUser(serverId, recipientId, message);
    }
}

public class OfflineDeliveryStrategy implements DeliveryStrategy {
    private final OfflineMessageQueue offlineQueue;
    private final PushNotificationService pushService;

    @Override
    public void deliver(Message message, String recipientId) {
        offlineQueue.enqueue(recipientId, message);
        pushService.sendPush(recipientId, message);
    }
}

public class MessageRouter {
    private final Map<PresenceStatus, DeliveryStrategy> strategies;

    public MessageRouter(DeliveryStrategy online, DeliveryStrategy offline) {
        this.strategies = Map.of(
            PresenceStatus.ONLINE, online,
            PresenceStatus.OFFLINE, offline
        );
    }

    public void route(Message message, String recipientId, PresenceStatus status) {
        strategies.getOrDefault(status, strategies.get(PresenceStatus.OFFLINE))
                  .deliver(message, recipientId);
    }
}
```

### Interview One-Liner

> "Message routing uses Strategy — online users get WebSocket delivery, offline
> users get queued and push-notified. The router picks the strategy based on
> presence status, so adding a new delivery mode is just a new class."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Open/closed — add new strategies without modifying router | Extra classes for simple branching |
| Each strategy is independently testable | Client (router) must know which strategy to select |
| Clean separation of online vs offline concerns | Map lookup overhead (negligible) |

---

## 5. Command Pattern (Behavioral)

### Why

A `Message` isn't just data — it's a unit of work that travels through a pipeline:
created, validated, persisted, routed, delivered, acknowledged. Each stage
processes the "command" differently. This also enables undo (delete/unsend),
replay (redeliver from queue), and logging.

### ASCII Diagram

```
  Message (Command Object)
  +-------------------+
  | messageId         |
  | senderId          |       Pipeline stages:
  | conversationId    |
  | content           |       CREATE --> VALIDATE --> PERSIST --> ROUTE --> DELIVER --> ACK
  | status: CREATED   |          |          |           |          |          |         |
  +-------------------+       Builder   ChatService  MessageRepo  Router   WebSocket  Client
                                                                           / Queue    sends
                                                                                      ACK
```

### Code Sketch

```java
public enum MessageStatus {
    CREATED, VALIDATED, PERSISTED, ROUTED, DELIVERED, READ
}

// The message acts as the command — it carries all context needed for execution
// Each processor in the pipeline advances the status

public class MessagePipeline {

    public void process(Message message) {
        validate(message);      // CREATED -> VALIDATED
        persist(message);       // VALIDATED -> PERSISTED
        route(message);         // PERSISTED -> ROUTED
        // DELIVERED and READ happen asynchronously via client ACKs
    }

    private void validate(Message message) {
        // Check sender exists, conversation exists, content length, etc.
        message.setStatus(MessageStatus.VALIDATED);
    }

    private void persist(Message message) {
        messageRepository.save(message);
        message.setStatus(MessageStatus.PERSISTED);
    }

    private void route(Message message) {
        router.route(message, recipientId, presenceStatus);
        message.setStatus(MessageStatus.ROUTED);
    }
}
```

### Interview One-Liner

> "A Message acts as a Command object — it encapsulates all the data needed to
> process it and travels through a pipeline where each stage advances its status.
> This gives us replay (redeliver from offline queue) and audit logging for free."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Pipeline is explicit and auditable | Slight complexity vs simple method calls |
| Supports undo (unsend), replay, retry | Status tracking adds state to manage |
| Each stage is independently testable | N/A |

---

## 6. Repository Pattern (Structural / Enterprise)

### Why

Three data stores (Cassandra, PostgreSQL, Redis) with different access patterns.
Repositories abstract the storage engine so services never know which DB they hit.

### Repositories

| Repository | Backing Store | Key Operations |
|-----------|---------------|----------------|
| MessageRepository | Cassandra | save, findByConversation(paginated), findById |
| UserRepository | PostgreSQL | findById, findByUsername, save, updateProfile |
| GroupRepository | PostgreSQL + Redis cache | create, addMember, removeMember, getMembers |

### Code Sketch

```java
public interface MessageRepository {
    void save(Message message);
    List<Message> findByConversation(String conversationId, int limit, String cursor);
    Optional<Message> findById(String messageId);
    void updateStatus(String messageId, MessageStatus status);
}

public class CassandraMessageRepository implements MessageRepository {
    // Partition key: conversation_id
    // Clustering key: sequence_number DESC
    // One partition = one conversation's message history

    @Override
    public List<Message> findByConversation(String conversationId, int limit, String cursor) {
        // Cassandra query with partition key + LIMIT + paging state
        return cassandraTemplate.select(
            QueryBuilder.selectFrom("messages")
                .whereColumn("conversation_id").isEqualTo(literal(conversationId))
                .limit(limit)
                .build()
        );
    }
}

public interface UserRepository {
    Optional<User> findById(String userId);
    Optional<User> findByUsername(String username);
    void save(User user);
}

public interface GroupRepository {
    Group create(Group group);
    void addMember(String groupId, String userId);
    void removeMember(String groupId, String userId);
    Set<String> getMembers(String groupId);
}
```

### Interview One-Liner

> "Three repositories abstract Cassandra (messages), PostgreSQL (users/groups),
> and Redis (caching). Services depend on interfaces, so we can swap storage
> engines or add caching layers without touching business logic."

### Tradeoffs

| Pro | Con |
|-----|-----|
| Swappable storage — easy to test with in-memory impl | Extra abstraction layer |
| Encapsulates query complexity (Cassandra pagination) | Can leak storage semantics if not careful |
| Single place to add caching decorators | N/A |

---

## 7. Factory Pattern (Creational)

### Why

`AppConfig` wires together all services, repositories, strategies, and observers.
It's a simple factory that centralizes object creation so `main()` stays clean
and dependencies are explicit.

### Code Sketch

```java
public class AppConfig {

    public static ChatService createChatService() {
        // --- Infrastructure ---
        CassandraSession cassandra = CassandraSessionFactory.create();
        RedisClient redis = RedisClientFactory.create();
        DataSource postgres = PostgresDataSourceFactory.create();

        // --- Repositories ---
        MessageRepository messageRepo = new CassandraMessageRepository(cassandra);
        UserRepository userRepo = new PostgresUserRepository(postgres);
        GroupRepository groupRepo = new CachedGroupRepository(
            new PostgresGroupRepository(postgres), redis
        );

        // --- Services ---
        PresenceService presenceService = new PresenceService(redis);
        MessageService messageService = new MessageService(messageRepo);
        GroupService groupService = new GroupService(groupRepo);

        // --- Strategies ---
        ConnectionRegistry connRegistry = new RedisConnectionRegistry(redis);
        WebSocketManager wsManager = new WebSocketManager();
        DeliveryStrategy onlineStrategy = new OnlineDeliveryStrategy(connRegistry, wsManager);
        DeliveryStrategy offlineStrategy = new OfflineDeliveryStrategy(
            new RedisOfflineQueue(redis), new FcmPushService()
        );
        MessageRouter router = new MessageRouter(onlineStrategy, offlineStrategy);

        // --- Mediator ---
        return new ChatService(messageService, groupService, presenceService, router);
    }
}
```

### Interview One-Liner

> "AppConfig is a Factory that wires everything together — repositories, services,
> strategies, observers. It's the composition root, so main() just calls
> `AppConfig.createChatService()` and all dependencies are explicit."

### Tradeoffs

| Pro | Con |
|-----|-----|
| All wiring in one place — easy to find | Manual wiring (no DI framework magic) |
| Dependencies are explicit and traceable | Gets long as system grows |
| No framework dependency — pure Java | Must update factory when adding new services |

---

## Pattern Interaction Map

```
  AppConfig (Factory)
      |
      | creates
      v
  ChatService (Mediator)
      |
      +---> MessageService ---> MessageRepository (Repository)
      |         |
      |         +---> Message.Builder (Builder)
      |         +---> Message as Command (Command) --> pipeline stages
      |
      +---> GroupService ---> GroupRepository (Repository)
      |         |
      |         +---> Observer (member join/leave notifications)
      |
      +---> PresenceService
      |         |
      |         +---> Observer (online/offline notifications)
      |
      +---> MessageRouter (Strategy)
                |
                +---> OnlineDeliveryStrategy
                +---> OfflineDeliveryStrategy
```

---

## Quick-Fire Interview Answers

| Question | Answer |
|----------|--------|
| "What's the main pattern?" | Observer — presence, receipts, and group events all use publish-subscribe |
| "How do you avoid spaghetti coupling?" | Mediator — ChatService coordinates, services don't call each other |
| "Why Builder for Message?" | 10+ fields, many optional, need immutability for thread safety |
| "How do you handle online vs offline?" | Strategy — router delegates to OnlineDeliveryStrategy or OfflineDeliveryStrategy |
| "How does a message flow through the system?" | Command — Message is a command object processed by a pipeline: create, validate, persist, route, deliver, ack |
| "How do you abstract storage?" | Repository — three repos hide Cassandra, PostgreSQL, and Redis behind interfaces |
| "How do you wire it all up?" | Factory — AppConfig.createChatService() builds the entire object graph |
