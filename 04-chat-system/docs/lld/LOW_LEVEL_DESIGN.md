# Low-Level Design: Chat/Messaging System (WhatsApp-like)

> Comprehensive LLD for a real-time chat system supporting 1:1 messaging, group chats,
> presence tracking, read receipts, message ordering, and offline delivery.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Service Layer](#6-service-layer)
7. [Connection Handler (Simulated WebSocket)](#7-connection-handler-simulated-websocket)
8. [Concurrency](#8-concurrency)
9. [Message Ordering](#9-message-ordering)
10. [Validation and Error Handling](#10-validation-and-error-handling)
11. [Sample Workflows](#11-sample-workflows)
12. [Design Patterns Used](#12-design-patterns-used)
13. [Extensibility](#13-extensibility)

---

## 1. Core Modules Overview

The system is decomposed into nine cohesive modules, each owning a single area of responsibility.

| Module         | Package         | Responsibility                                                                 |
|----------------|-----------------|--------------------------------------------------------------------------------|
| **Model**      | `model/`        | Domain entities: User, Message, Conversation, GroupChat, enums, value objects.  |
| **Service**    | `service/`      | Core business logic: sending messages, routing, presence, group management.    |
| **Handler**    | `handler/`      | Simulated WebSocket layer: connection lifecycle, real-time message push.       |
| **Router**     | `service/`      | MessageRouter: decides online vs. offline delivery per recipient.              |
| **Presence**   | `service/`      | PresenceService: heartbeat, timeout detection, status broadcasting.            |
| **Repository** | `repository/`   | Data access interfaces + in-memory implementations for all entities.           |
| **Controller** | `controller/`   | Simulated REST + WebSocket endpoints: entry point for all client operations.   |
| **Config**     | `config/`       | Application-wide configuration, bean wiring, tunable parameters.              |
| **Exception**  | `exception/`    | Custom exception hierarchy for domain-specific error signaling.               |

### Module Interaction Summary

```
Controller (entry point)
    |
    v
ChatService (orchestrator / mediator)
    |
    +---> MessageService ---> MessageRepository
    |         |
    |         +---> MessageRouter ---> ConnectionHandler (online delivery)
    |                    |
    |                    +---> OfflineQueue (offline delivery)
    |
    +---> GroupService ---> GroupRepository
    |
    +---> PresenceService ---> ConnectionRegistry
    |
    +---> UserService ---> UserRepository
    |
    +---> ConversationRepository
```

---

## 2. Package Structure

```
com.systemdesign.chat
├── model/
│   ├── User.java
│   ├── Message.java
│   ├── Conversation.java
│   ├── GroupChat.java
│   ├── ReadReceipt.java
│   ├── PresenceInfo.java
│   ├── MessageType.java          (enum)
│   ├── MessageStatus.java        (enum)
│   └── UserStatus.java           (enum)
│
├── service/
│   ├── ChatService.java          (orchestrator)
│   ├── MessageService.java       (core message logic)
│   ├── GroupService.java          (group lifecycle)
│   ├── PresenceService.java      (heartbeat + status)
│   ├── MessageRouter.java        (online/offline routing)
│   └── UserService.java          (user CRUD + status)
│
├── handler/
│   ├── ConnectionHandler.java    (simulated WebSocket connection)
│   └── MessageHandler.java       (inbound message processing)
│
├── repository/
│   ├── MessageRepository.java            (interface)
│   ├── ConversationRepository.java       (interface)
│   ├── UserRepository.java               (interface)
│   ├── GroupRepository.java              (interface)
│   ├── ConnectionRegistry.java           (interface)
│   ├── InMemoryMessageRepository.java
│   ├── InMemoryConversationRepository.java
│   ├── InMemoryUserRepository.java
│   ├── InMemoryGroupRepository.java
│   └── InMemoryConnectionRegistry.java
│
├── controller/
│   └── ChatController.java       (simulated REST + WebSocket endpoints)
│
├── config/
│   └── AppConfig.java
│
└── exception/
    ├── ChatException.java
    ├── UserNotFoundException.java
    ├── ConversationNotFoundException.java
    └── UnauthorizedException.java
```

---

## 3. Class Diagram

```
+---------------------------------------------------------------------+
|                            <<enumeration>>                          |
|                             MessageType                             |
|---------------------------------------------------------------------|
| TEXT | IMAGE | VIDEO | FILE | SYSTEM                                |
+---------------------------------------------------------------------+

+---------------------------------------------------------------------+
|                            <<enumeration>>                          |
|                            MessageStatus                            |
|---------------------------------------------------------------------|
| SENDING | SENT | DELIVERED | READ | FAILED                         |
+---------------------------------------------------------------------+

+---------------------------------------------------------------------+
|                            <<enumeration>>                          |
|                             UserStatus                              |
|---------------------------------------------------------------------|
| ONLINE | OFFLINE | AWAY                                            |
+---------------------------------------------------------------------+


+---------------------------------------------+      +------------------------------------------+
|                  User                       |      |              PresenceInfo                |
|---------------------------------------------|      |------------------------------------------|
| - userId: String                            |      | - userId: String                         |
| - username: String                          |      | - status: UserStatus                     |
| - avatarUrl: String                         |      | - lastHeartbeat: LocalDateTime           |
| - status: UserStatus                        |      | - connectedServer: String                |
| - lastSeen: LocalDateTime                   |      |------------------------------------------|
|---------------------------------------------|      | + isTimedOut(timeoutSec: long): boolean   |
| + updateStatus(status): void                |      +------------------------------------------+
| + updateLastSeen(): void                    |
+---------------------------------------------+


+-----------------------------------------------------------------------------+
|                                 Message                                     |
|-----------------------------------------------------------------------------|
| - messageId: String                                                         |
| - conversationId: String                                                    |
| - senderId: String                                                          |
| - content: String                                                           |
| - type: MessageType                                                         |
| - status: MessageStatus                                                     |
| - sequenceNumber: long                                                      |
| - createdAt: LocalDateTime                                                  |
| - deliveryStatus: Map<String, MessageStatus>     (per-recipient tracking)   |
|-----------------------------------------------------------------------------|
| + updateStatusForRecipient(userId, status): void                            |
| + isFullyDelivered(): boolean                                               |
| + isFullyRead(): boolean                                                    |
|-----------------------------------------------------------------------------|
|                         <<static inner class>>                              |
|                            Message.Builder                                  |
|-----------------------------------------------------------------------------|
| + messageId(String): Builder                                                |
| + conversationId(String): Builder                                           |
| + senderId(String): Builder                                                 |
| + content(String): Builder                                                  |
| + type(MessageType): Builder                                                |
| + status(MessageStatus): Builder                                            |
| + sequenceNumber(long): Builder                                             |
| + build(): Message                                                          |
+-----------------------------------------------------------------------------+


+---------------------------------------------+
|              Conversation                   |
|---------------------------------------------|
| - conversationId: String                    |
| - type: ConversationType (ONE_TO_ONE/GROUP) |
| - name: String                              |
| - memberIds: List<String>                   |
| - createdBy: String                         |
| - createdAt: LocalDateTime                  |
| - lastSequenceNumber: AtomicLong            |
|---------------------------------------------|
| + nextSequence(): long                      |
| + isMember(userId): boolean                 |
| + addMember(userId): void                   |
| + removeMember(userId): void                |
+---------------------------------------------+
          ^
          | extends
          |
+---------------------------------------------+
|               GroupChat                     |
|---------------------------------------------|
| - maxMembers: int = 256                     |
| - adminIds: Set<String>                     |
| - description: String                       |
|---------------------------------------------|
| + isAdmin(userId): boolean                  |
| + addAdmin(userId): void                    |
| + removeAdmin(userId): void                 |
| + isFull(): boolean                         |
+---------------------------------------------+


+---------------------------------------------+
|              ReadReceipt                    |
|---------------------------------------------|
| - messageId: String                         |
| - userId: String                            |
| - status: MessageStatus                     |
| - timestamp: LocalDateTime                  |
+---------------------------------------------+


+--------------------------------------------------------------+
|                   <<interface>>                               |
|                 MessageRepository                            |
|--------------------------------------------------------------|
| + save(message: Message): Message                            |
| + findById(messageId: String): Optional<Message>             |
| + findByConversationId(convId, limit, beforeSeq): List<Msg>  |
| + updateStatus(msgId, userId, status): void                  |
+--------------------------------------------------------------+
          ^
          | implements
+--------------------------------------------------------------+
|             InMemoryMessageRepository                        |
|--------------------------------------------------------------|
| - messages: ConcurrentHashMap<String, Message>               |
| - convIndex: ConcurrentHashMap<String, ConcurrentSkipListSet>|
+--------------------------------------------------------------+


+--------------------------------------------------------------+
|                   <<interface>>                               |
|              ConversationRepository                          |
|--------------------------------------------------------------|
| + save(conv: Conversation): Conversation                     |
| + findById(convId: String): Optional<Conversation>           |
| + findByUserId(userId: String): List<Conversation>           |
| + findOneToOne(userA, userB): Optional<Conversation>         |
+--------------------------------------------------------------+
          ^
          | implements
+--------------------------------------------------------------+
|          InMemoryConversationRepository                      |
|--------------------------------------------------------------|
| - conversations: ConcurrentHashMap<String, Conversation>     |
+--------------------------------------------------------------+


+--------------------------------------------------------------+
|                   <<interface>>                               |
|                  UserRepository                              |
|--------------------------------------------------------------|
| + save(user: User): User                                     |
| + findById(userId: String): Optional<User>                   |
| + updateStatus(userId: String, status: UserStatus): void     |
+--------------------------------------------------------------+
          ^
          | implements
+--------------------------------------------------------------+
|              InMemoryUserRepository                          |
|--------------------------------------------------------------|
| - users: ConcurrentHashMap<String, User>                     |
+--------------------------------------------------------------+


+--------------------------------------------------------------+
|                   <<interface>>                               |
|                 GroupRepository                               |
|--------------------------------------------------------------|
| + save(group: GroupChat): GroupChat                           |
| + findById(groupId: String): Optional<GroupChat>             |
| + findByUserId(userId: String): List<GroupChat>              |
+--------------------------------------------------------------+
          ^
          | implements
+--------------------------------------------------------------+
|             InMemoryGroupRepository                          |
|--------------------------------------------------------------|
| - groups: ConcurrentHashMap<String, GroupChat>                |
+--------------------------------------------------------------+


+--------------------------------------------------------------+
|                   <<interface>>                               |
|                ConnectionRegistry                            |
|--------------------------------------------------------------|
| + register(userId, serverId): void                           |
| + unregister(userId): void                                   |
| + getServer(userId): Optional<String>                        |
| + isOnline(userId): boolean                                  |
+--------------------------------------------------------------+
          ^
          | implements
+--------------------------------------------------------------+
|           InMemoryConnectionRegistry                         |
|--------------------------------------------------------------|
| - registry: ConcurrentHashMap<String, String>                |
+--------------------------------------------------------------+


+--------------------------------------------------------------+    +--------------------------------------+
|                  MessageRouter                               |    |         ConnectionHandler            |
|--------------------------------------------------------------|    |--------------------------------------|
| - connectionRegistry: ConnectionRegistry                     |    | - userId: String                     |
| - connectionHandlers: Map<String, ConnectionHandler>         |    | - serverId: String                   |
| - offlineQueues: Map<String, Queue<Message>>                 |    |--------------------------------------|
|--------------------------------------------------------------|    | + deliverMessage(msg): void          |
| + routeMessage(msg, recipientIds): void                      |    | + deliverPresenceUpdate(uid,st): void|
| + routeGroupMessage(msg, conv): void                         |    | + deliverReceipt(receipt): void      |
| + deliverOfflineMessages(userId): void                       |    +--------------------------------------+
+--------------------------------------------------------------+


+--------------------------------------------------------------+
|                    ChatService                               |
|              <<mediator / orchestrator>>                      |
|--------------------------------------------------------------|
| - messageService: MessageService                             |
| - groupService: GroupService                                 |
| - presenceService: PresenceService                           |
| - userService: UserService                                   |
| - conversationRepository: ConversationRepository             |
|--------------------------------------------------------------|
| + sendDirectMessage(senderId, recipientId, content, type)    |
| + sendGroupMessage(senderId, groupId, content, type)         |
| + createGroup(name, creatorId, memberIds)                    |
| + getConversations(userId): List<Conversation>               |
| + getHistory(convId, limit): List<Message>                   |
| + markAsRead(messageId, userId): void                        |
| + connect(userId): void                                      |
| + disconnect(userId): void                                   |
+--------------------------------------------------------------+


+--------------------------------------------------------------+
|                   ChatController                             |
|           <<simulated REST + WebSocket>>                      |
|--------------------------------------------------------------|
| - chatService: ChatService                                   |
|--------------------------------------------------------------|
| + POST   /chat/send                                          |
| + POST   /chat/group                                         |
| + GET    /chat/conversations/{userId}                        |
| + GET    /chat/history/{conversationId}                       |
| + POST   /chat/read                                          |
| + WS     /ws/connect/{userId}                                |
| + WS     /ws/disconnect/{userId}                             |
+--------------------------------------------------------------+


+--------------------------------------------------------------+
|                     AppConfig                                |
|--------------------------------------------------------------|
| + HEARTBEAT_TIMEOUT_SECONDS: int = 60                        |
| + MAX_GROUP_MEMBERS: int = 256                               |
| + MESSAGE_PAGE_SIZE: int = 50                                |
| + OFFLINE_QUEUE_CAPACITY: int = 10000                        |
| + SERVER_ID: String = "server-01"                            |
|--------------------------------------------------------------|
| + createChatService(): ChatService         <<factory>>       |
| + createMessageService(): MessageService                     |
| + createGroupService(): GroupService                         |
| + createPresenceService(): PresenceService                   |
+--------------------------------------------------------------+


+-----------------------------------+
|    <<exception hierarchy>>        |
|-----------------------------------|
| ChatException (base)              |
|   +-- UserNotFoundException       |
|   +-- ConversationNotFoundException|
|   +-- UnauthorizedException       |
+-----------------------------------+
```

### Relationship Summary

```
ChatController ──uses──> ChatService
ChatService ──uses──> MessageService, GroupService, PresenceService, UserService
MessageService ──uses──> MessageRepository, ConversationRepository, MessageRouter
MessageRouter ──uses──> ConnectionRegistry, ConnectionHandler, OfflineQueue
GroupService ──uses──> GroupRepository, ConversationRepository, MessageService
PresenceService ──uses──> ConnectionRegistry, UserRepository, MessageRouter
GroupChat ──extends──> Conversation
Message ──contains──> Message.Builder (static inner)
All Repositories: Interface <── InMemory implementation
```

---

## 4. Entity Design

### 4.1 MessageType (Enum)

```java
public enum MessageType {
    TEXT,        // Plain text message
    IMAGE,       // Image attachment (URL reference)
    VIDEO,       // Video attachment (URL reference)
    FILE,        // Generic file attachment
    SYSTEM       // System-generated message (join/leave/admin changes)
}
```

### 4.2 MessageStatus (Enum)

```java
public enum MessageStatus {
    SENDING,     // Client has initiated send, not yet confirmed by server
    SENT,        // Server has received and persisted the message
    DELIVERED,   // Message pushed to recipient's device
    READ,        // Recipient has opened/viewed the message
    FAILED;      // Delivery failed after retries

    /**
     * Status transitions are strictly ordered.
     * A status can only advance forward, never backward.
     */
    public boolean canTransitionTo(MessageStatus next) {
        return next.ordinal() > this.ordinal() || next == FAILED;
    }
}
```

### 4.3 UserStatus (Enum)

```java
public enum UserStatus {
    ONLINE,      // User has an active connection and recent heartbeat
    OFFLINE,     // User disconnected or heartbeat timed out
    AWAY         // User connected but idle for extended period
}
```

### 4.4 User

```java
public class User {
    private final String userId;
    private String username;
    private String avatarUrl;
    private UserStatus status;
    private LocalDateTime lastSeen;

    public User(String userId, String username) {
        this.userId = userId;
        this.username = username;
        this.status = UserStatus.OFFLINE;
        this.lastSeen = LocalDateTime.now();
    }

    // --- Getters ---
    public String getUserId()          { return userId; }
    public String getUsername()        { return username; }
    public String getAvatarUrl()      { return avatarUrl; }
    public UserStatus getStatus()     { return status; }
    public LocalDateTime getLastSeen(){ return lastSeen; }

    // --- Setters ---
    public void setUsername(String username)   { this.username = username; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    // --- Business Methods ---
    public void updateStatus(UserStatus status) {
        this.status = status;
        if (status == UserStatus.OFFLINE) {
            this.lastSeen = LocalDateTime.now();
        }
    }

    public void updateLastSeen() {
        this.lastSeen = LocalDateTime.now();
    }
}
```

### 4.5 Message (with Builder Pattern)

```java
public class Message {
    private final String messageId;
    private final String conversationId;
    private final String senderId;
    private final String content;
    private final MessageType type;
    private MessageStatus status;
    private final long sequenceNumber;
    private final LocalDateTime createdAt;
    private final Map<String, MessageStatus> deliveryStatus;  // recipientId -> status

    // Private constructor — only Builder can create
    private Message(Builder builder) {
        this.messageId      = builder.messageId;
        this.conversationId = builder.conversationId;
        this.senderId       = builder.senderId;
        this.content        = builder.content;
        this.type           = builder.type;
        this.status         = builder.status;
        this.sequenceNumber = builder.sequenceNumber;
        this.createdAt      = builder.createdAt != null
                              ? builder.createdAt : LocalDateTime.now();
        this.deliveryStatus = new ConcurrentHashMap<>(builder.deliveryStatus);
    }

    // --- Getters ---
    public String getMessageId()           { return messageId; }
    public String getConversationId()      { return conversationId; }
    public String getSenderId()            { return senderId; }
    public String getContent()             { return content; }
    public MessageType getType()           { return type; }
    public MessageStatus getStatus()       { return status; }
    public long getSequenceNumber()        { return sequenceNumber; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public Map<String, MessageStatus> getDeliveryStatus() {
        return Collections.unmodifiableMap(deliveryStatus);
    }

    // --- Business Methods ---
    public void updateStatusForRecipient(String userId, MessageStatus newStatus) {
        MessageStatus current = deliveryStatus.get(userId);
        if (current == null || current.canTransitionTo(newStatus)) {
            deliveryStatus.put(userId, newStatus);
            recalculateOverallStatus();
        }
    }

    /**
     * Overall status is the MINIMUM status across all recipients.
     * All DELIVERED -> overall DELIVERED. All READ -> overall READ.
     */
    private void recalculateOverallStatus() {
        if (deliveryStatus.isEmpty()) return;

        boolean allRead      = deliveryStatus.values().stream()
                                  .allMatch(s -> s == MessageStatus.READ);
        boolean allDelivered = deliveryStatus.values().stream()
                                  .allMatch(s -> s == MessageStatus.DELIVERED
                                              || s == MessageStatus.READ);
        if (allRead) {
            this.status = MessageStatus.READ;
        } else if (allDelivered) {
            this.status = MessageStatus.DELIVERED;
        }
    }

    public boolean isFullyDelivered() {
        return deliveryStatus.values().stream()
                .allMatch(s -> s == MessageStatus.DELIVERED || s == MessageStatus.READ);
    }

    public boolean isFullyRead() {
        return deliveryStatus.values().stream()
                .allMatch(s -> s == MessageStatus.READ);
    }

    // ========== Builder ==========
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String messageId;
        private String conversationId;
        private String senderId;
        private String content;
        private MessageType type = MessageType.TEXT;
        private MessageStatus status = MessageStatus.SENDING;
        private long sequenceNumber;
        private LocalDateTime createdAt;
        private Map<String, MessageStatus> deliveryStatus = new HashMap<>();

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder status(MessageStatus status) {
            this.status = status;
            return this;
        }

        public Builder sequenceNumber(long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder addRecipient(String recipientId) {
            this.deliveryStatus.put(recipientId, MessageStatus.SENDING);
            return this;
        }

        public Builder addRecipients(List<String> recipientIds) {
            recipientIds.forEach(id ->
                this.deliveryStatus.put(id, MessageStatus.SENDING));
            return this;
        }

        public Message build() {
            Objects.requireNonNull(messageId, "messageId is required");
            Objects.requireNonNull(conversationId, "conversationId is required");
            Objects.requireNonNull(senderId, "senderId is required");
            Objects.requireNonNull(content, "content is required");
            return new Message(this);
        }
    }
}
```

### 4.6 Conversation

```java
public class Conversation {

    public enum ConversationType { ONE_TO_ONE, GROUP }

    private final String conversationId;
    private final ConversationType type;
    private String name;
    private final List<String> memberIds;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final AtomicLong lastSequenceNumber;
    private final ReadWriteLock memberLock = new ReentrantReadWriteLock();

    public Conversation(String conversationId, ConversationType type,
                        String name, String createdBy) {
        this.conversationId    = conversationId;
        this.type              = type;
        this.name              = name;
        this.createdBy         = createdBy;
        this.createdAt         = LocalDateTime.now();
        this.memberIds         = new ArrayList<>();
        this.lastSequenceNumber = new AtomicLong(0);
    }

    // --- Thread-safe sequence generation ---
    public long nextSequence() {
        return lastSequenceNumber.incrementAndGet();
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber.get();
    }

    // --- Member management with ReadWriteLock ---
    public boolean isMember(String userId) {
        memberLock.readLock().lock();
        try {
            return memberIds.contains(userId);
        } finally {
            memberLock.readLock().unlock();
        }
    }

    public void addMember(String userId) {
        memberLock.writeLock().lock();
        try {
            if (!memberIds.contains(userId)) {
                memberIds.add(userId);
            }
        } finally {
            memberLock.writeLock().unlock();
        }
    }

    public void removeMember(String userId) {
        memberLock.writeLock().lock();
        try {
            memberIds.remove(userId);
        } finally {
            memberLock.writeLock().unlock();
        }
    }

    public List<String> getMemberIds() {
        memberLock.readLock().lock();
        try {
            return new ArrayList<>(memberIds);  // defensive copy
        } finally {
            memberLock.readLock().unlock();
        }
    }

    // --- Getters ---
    public String getConversationId()     { return conversationId; }
    public ConversationType getType()     { return type; }
    public String getName()               { return name; }
    public String getCreatedBy()          { return createdBy; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
}
```

### 4.7 GroupChat (extends Conversation)

```java
public class GroupChat extends Conversation {

    private static final int DEFAULT_MAX_MEMBERS = 256;

    private final int maxMembers;
    private final Set<String> adminIds;
    private String description;

    public GroupChat(String groupId, String name, String creatorId,
                     String description) {
        super(groupId, ConversationType.GROUP, name, creatorId);
        this.maxMembers  = DEFAULT_MAX_MEMBERS;
        this.adminIds    = ConcurrentHashMap.newKeySet();
        this.description = description;

        // Creator is automatically a member and admin
        this.addMember(creatorId);
        this.adminIds.add(creatorId);
    }

    public boolean isAdmin(String userId)   { return adminIds.contains(userId); }

    public void addAdmin(String userId) {
        if (!isMember(userId)) {
            throw new ChatException("User " + userId + " must be a member first");
        }
        adminIds.add(userId);
    }

    public void removeAdmin(String userId) {
        if (adminIds.size() <= 1) {
            throw new ChatException("Cannot remove last admin");
        }
        adminIds.remove(userId);
    }

    public boolean isFull() {
        return getMemberIds().size() >= maxMembers;
    }

    @Override
    public void addMember(String userId) {
        if (isFull()) {
            throw new ChatException("Group is full (max " + maxMembers + ")");
        }
        super.addMember(userId);
    }

    @Override
    public void removeMember(String userId) {
        super.removeMember(userId);
        adminIds.remove(userId);  // also remove from admins if present
    }

    // --- Getters ---
    public int getMaxMembers()        { return maxMembers; }
    public Set<String> getAdminIds()  { return Collections.unmodifiableSet(adminIds); }
    public String getDescription()    { return description; }
    public void setDescription(String d) { this.description = d; }
}
```

### 4.8 ReadReceipt

```java
public class ReadReceipt {
    private final String messageId;
    private final String userId;
    private final MessageStatus status;
    private final LocalDateTime timestamp;

    public ReadReceipt(String messageId, String userId, MessageStatus status) {
        this.messageId = messageId;
        this.userId    = userId;
        this.status    = status;
        this.timestamp = LocalDateTime.now();
    }

    public String getMessageId()        { return messageId; }
    public String getUserId()           { return userId; }
    public MessageStatus getStatus()    { return status; }
    public LocalDateTime getTimestamp()  { return timestamp; }
}
```

### 4.9 PresenceInfo

```java
public class PresenceInfo {
    private final String userId;
    private volatile UserStatus status;
    private volatile LocalDateTime lastHeartbeat;
    private volatile String connectedServer;

    public PresenceInfo(String userId, String connectedServer) {
        this.userId          = userId;
        this.status          = UserStatus.ONLINE;
        this.lastHeartbeat   = LocalDateTime.now();
        this.connectedServer = connectedServer;
    }

    public void refreshHeartbeat() {
        this.lastHeartbeat = LocalDateTime.now();
        this.status        = UserStatus.ONLINE;
    }

    public boolean isTimedOut(long timeoutSeconds) {
        return Duration.between(lastHeartbeat, LocalDateTime.now())
                       .getSeconds() > timeoutSeconds;
    }

    // --- Getters ---
    public String getUserId()              { return userId; }
    public UserStatus getStatus()          { return status; }
    public LocalDateTime getLastHeartbeat(){ return lastHeartbeat; }
    public String getConnectedServer()     { return connectedServer; }

    public void setStatus(UserStatus status)              { this.status = status; }
    public void setConnectedServer(String connectedServer){ this.connectedServer = connectedServer; }
}
```

---

## 5. Interface Contracts

### 5.1 MessageRepository

```java
public interface MessageRepository {

    /**
     * Persist a message. Returns the saved message (with generated ID if needed).
     */
    Message save(Message message);

    /**
     * Retrieve a single message by ID.
     */
    Optional<Message> findById(String messageId);

    /**
     * Paginated message history for a conversation.
     * Returns up to `limit` messages with sequenceNumber < beforeSequence,
     * ordered by sequenceNumber descending (newest first).
     *
     * @param conversationId  the conversation to query
     * @param limit           max number of messages to return
     * @param beforeSequence  upper bound on sequence (exclusive); 
     *                        pass Long.MAX_VALUE for latest
     * @return messages sorted by sequenceNumber descending
     */
    List<Message> findByConversationId(String conversationId,
                                       int limit, long beforeSequence);

    /**
     * Update delivery status for a specific recipient on a specific message.
     */
    void updateStatus(String messageId, String userId, MessageStatus status);
}
```

### 5.2 ConversationRepository

```java
public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(String conversationId);

    /**
     * Find all conversations a user is a member of.
     */
    List<Conversation> findByUserId(String userId);

    /**
     * Find existing 1:1 conversation between two users, if any.
     * Used to avoid creating duplicate conversations.
     */
    Optional<Conversation> findOneToOne(String userA, String userB);
}
```

### 5.3 UserRepository

```java
public interface UserRepository {

    User save(User user);

    Optional<User> findById(String userId);

    /**
     * Update user's online/offline/away status.
     */
    void updateStatus(String userId, UserStatus status);
}
```

### 5.4 GroupRepository

```java
public interface GroupRepository {

    GroupChat save(GroupChat group);

    Optional<GroupChat> findById(String groupId);

    /**
     * Find all groups a user belongs to.
     */
    List<GroupChat> findByUserId(String userId);
}
```

### 5.5 ConnectionRegistry

```java
/**
 * Maps which connection/WebSocket server each user is connected to.
 * In a distributed system, this would be backed by Redis.
 * Here, it uses an in-memory ConcurrentHashMap.
 */
public interface ConnectionRegistry {

    /**
     * Register a user as connected to a specific server.
     */
    void register(String userId, String serverId);

    /**
     * Remove a user's connection mapping.
     */
    void unregister(String userId);

    /**
     * Get the server a user is connected to, if any.
     */
    Optional<String> getServer(String userId);

    /**
     * Check if a user has an active connection.
     */
    boolean isOnline(String userId);
}
```

### 5.6 In-Memory Implementations

#### InMemoryMessageRepository

```java
public class InMemoryMessageRepository implements MessageRepository {

    // Primary store: messageId -> Message
    private final ConcurrentHashMap<String, Message> messages
        = new ConcurrentHashMap<>();

    // Index: conversationId -> sorted set of messageIds by sequence
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Long, String>>
        conversationIndex = new ConcurrentHashMap<>();

    @Override
    public Message save(Message message) {
        messages.put(message.getMessageId(), message);
        conversationIndex
            .computeIfAbsent(message.getConversationId(),
                             k -> new ConcurrentSkipListMap<>())
            .put(message.getSequenceNumber(), message.getMessageId());
        return message;
    }

    @Override
    public Optional<Message> findById(String messageId) {
        return Optional.ofNullable(messages.get(messageId));
    }

    @Override
    public List<Message> findByConversationId(String conversationId,
                                               int limit, long beforeSequence) {
        ConcurrentSkipListMap<Long, String> index =
            conversationIndex.get(conversationId);
        if (index == null) return Collections.emptyList();

        // headMap(beforeSequence, exclusive) gives all keys < beforeSequence
        // descendingMap() for newest-first ordering
        return index.headMap(beforeSequence, false)
                    .descendingMap()
                    .values()
                    .stream()
                    .limit(limit)
                    .map(messages::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
    }

    @Override
    public void updateStatus(String messageId, String userId,
                              MessageStatus status) {
        Message msg = messages.get(messageId);
        if (msg != null) {
            msg.updateStatusForRecipient(userId, status);
        }
    }
}
```

#### InMemoryConnectionRegistry

```java
public class InMemoryConnectionRegistry implements ConnectionRegistry {

    private final ConcurrentHashMap<String, String> registry
        = new ConcurrentHashMap<>();  // userId -> serverId

    @Override
    public void register(String userId, String serverId) {
        registry.put(userId, serverId);
    }

    @Override
    public void unregister(String userId) {
        registry.remove(userId);
    }

    @Override
    public Optional<String> getServer(String userId) {
        return Optional.ofNullable(registry.get(userId));
    }

    @Override
    public boolean isOnline(String userId) {
        return registry.containsKey(userId);
    }
}
```

---

## 6. Service Layer

### 6.1 MessageService (Core)

```java
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRouter messageRouter;

    public MessageService(MessageRepository messageRepository,
                          ConversationRepository conversationRepository,
                          MessageRouter messageRouter) {
        this.messageRepository      = messageRepository;
        this.conversationRepository = conversationRepository;
        this.messageRouter          = messageRouter;
    }

    /**
     * Core send flow:
     * 1. Validate sender is member of conversation
     * 2. Assign monotonic sequence number
     * 3. Build Message using Builder
     * 4. Persist to repository
     * 5. Route to all recipients
     * 6. Return message with status=SENT
     */
    public Message sendMessage(String senderId, String conversationId,
                               String content, MessageType type) {

        // Step 1: Validate
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        if (!conversation.isMember(senderId)) {
            throw new UnauthorizedException(
                "User " + senderId + " is not a member of conversation "
                + conversationId);
        }

        // Step 2: Assign sequence number (atomic, gap-free within conversation)
        long sequence = conversation.nextSequence();

        // Step 3: Identify recipients (all members except sender)
        List<String> recipientIds = conversation.getMemberIds().stream()
            .filter(id -> !id.equals(senderId))
            .collect(Collectors.toList());

        // Step 4: Build message
        Message message = Message.builder()
            .messageId(UUID.randomUUID().toString())
            .conversationId(conversationId)
            .senderId(senderId)
            .content(content)
            .type(type)
            .status(MessageStatus.SENT)
            .sequenceNumber(sequence)
            .addRecipients(recipientIds)
            .build();

        // Step 5: Persist
        messageRepository.save(message);

        // Step 6: Route to recipients
        messageRouter.routeMessage(message, recipientIds);

        return message;
    }

    /**
     * Retrieve paginated message history.
     * Returns messages before the given sequence number, newest first.
     */
    public List<Message> getHistory(String conversationId, int limit,
                                     long beforeSequence) {
        return messageRepository.findByConversationId(
            conversationId, limit, beforeSequence);
    }

    /**
     * Mark a message as DELIVERED for a specific user.
     */
    public void markAsDelivered(String messageId, String userId) {
        messageRepository.updateStatus(messageId, userId, MessageStatus.DELIVERED);
    }

    /**
     * Mark a message as READ for a specific user.
     * Also creates a ReadReceipt and notifies the sender.
     */
    public ReadReceipt markAsRead(String messageId, String userId) {
        messageRepository.updateStatus(messageId, userId, MessageStatus.READ);

        ReadReceipt receipt = new ReadReceipt(messageId, userId, MessageStatus.READ);

        // Notify sender about the read receipt
        Message msg = messageRepository.findById(messageId).orElse(null);
        if (msg != null) {
            messageRouter.routeReadReceipt(receipt, msg.getSenderId());
        }

        return receipt;
    }
}
```

### 6.2 MessageRouter

```java
/**
 * Routes messages to recipients based on their connection status.
 * Online users get real-time delivery; offline users get queued.
 *
 * Uses the Strategy pattern: online delivery vs. offline queueing.
 */
public class MessageRouter {

    private final ConnectionRegistry connectionRegistry;
    private final Map<String, ConnectionHandler> connectionHandlers;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Message>>
        offlineQueues = new ConcurrentHashMap<>();

    public MessageRouter(ConnectionRegistry connectionRegistry,
                         Map<String, ConnectionHandler> connectionHandlers) {
        this.connectionRegistry = connectionRegistry;
        this.connectionHandlers = connectionHandlers;
    }

    /**
     * Route a message to a list of recipients.
     * For each recipient:
     *   - If ONLINE  -> deliver immediately via ConnectionHandler -> mark DELIVERED
     *   - If OFFLINE -> enqueue for later delivery
     */
    public void routeMessage(Message message, List<String> recipientIds) {
        for (String recipientId : recipientIds) {
            if (connectionRegistry.isOnline(recipientId)) {
                deliverToOnlineUser(message, recipientId);
            } else {
                enqueueForOfflineUser(message, recipientId);
            }
        }
    }

    /**
     * Fan-out for group messages: get all members except sender, route to each.
     */
    public void routeGroupMessage(Message message, Conversation group) {
        List<String> recipientIds = group.getMemberIds().stream()
            .filter(id -> !id.equals(message.getSenderId()))
            .collect(Collectors.toList());
        routeMessage(message, recipientIds);
    }

    /**
     * When a user comes online, deliver all queued messages.
     * Called during the connect flow.
     */
    public void deliverOfflineMessages(String userId) {
        ConcurrentLinkedQueue<Message> queue = offlineQueues.get(userId);
        if (queue == null || queue.isEmpty()) return;

        ConnectionHandler handler = connectionHandlers.get(userId);
        if (handler == null) return;

        Message msg;
        while ((msg = queue.poll()) != null) {
            handler.deliverMessage(msg);
            msg.updateStatusForRecipient(userId, MessageStatus.DELIVERED);
        }
    }

    /**
     * Route a read receipt notification to the original sender.
     */
    public void routeReadReceipt(ReadReceipt receipt, String senderId) {
        if (connectionRegistry.isOnline(senderId)) {
            ConnectionHandler handler = connectionHandlers.get(senderId);
            if (handler != null) {
                handler.deliverReceipt(receipt);
            }
        }
        // If sender is offline, read receipt will be visible when they
        // load message history (status is persisted in Message.deliveryStatus)
    }

    // --- Private helpers ---

    private void deliverToOnlineUser(Message message, String recipientId) {
        ConnectionHandler handler = connectionHandlers.get(recipientId);
        if (handler != null) {
            handler.deliverMessage(message);
            message.updateStatusForRecipient(recipientId, MessageStatus.DELIVERED);
        }
    }

    private void enqueueForOfflineUser(Message message, String recipientId) {
        offlineQueues
            .computeIfAbsent(recipientId, k -> new ConcurrentLinkedQueue<>())
            .add(message);
        // In a real system, also trigger push notification here
        System.out.println("[PUSH -> " + recipientId
            + "] You have a new message from " + message.getSenderId());
    }
}
```

### 6.3 PresenceService

```java
/**
 * Manages user presence (online/offline/away) via heartbeat mechanism.
 * Uses ConcurrentHashMap for thread-safe presence tracking.
 *
 * Implements the Observer pattern: notifies connected users about
 * presence changes of their contacts.
 */
public class PresenceService {

    private final ConcurrentHashMap<String, PresenceInfo> presenceMap
        = new ConcurrentHashMap<>();
    private final ConnectionRegistry connectionRegistry;
    private final UserRepository userRepository;
    private final Map<String, ConnectionHandler> connectionHandlers;
    private final ConversationRepository conversationRepository;
    private final long heartbeatTimeoutSeconds;

    public PresenceService(ConnectionRegistry connectionRegistry,
                           UserRepository userRepository,
                           Map<String, ConnectionHandler> connectionHandlers,
                           ConversationRepository conversationRepository,
                           long heartbeatTimeoutSeconds) {
        this.connectionRegistry     = connectionRegistry;
        this.userRepository         = userRepository;
        this.connectionHandlers     = connectionHandlers;
        this.conversationRepository = conversationRepository;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    /**
     * Called periodically by the client to signal it is still alive.
     */
    public void heartbeat(String userId) {
        PresenceInfo info = presenceMap.get(userId);
        if (info != null) {
            info.refreshHeartbeat();
        }
    }

    /**
     * Called when a user establishes a WebSocket connection.
     */
    public void userConnected(String userId, String serverId) {
        PresenceInfo info = new PresenceInfo(userId, serverId);
        presenceMap.put(userId, info);
        connectionRegistry.register(userId, serverId);
        userRepository.updateStatus(userId, UserStatus.ONLINE);
        broadcastPresenceChange(userId, UserStatus.ONLINE);
    }

    /**
     * Called when a user's WebSocket disconnects (graceful or detected).
     */
    public void disconnect(String userId) {
        presenceMap.remove(userId);
        connectionRegistry.unregister(userId);
        userRepository.updateStatus(userId, UserStatus.OFFLINE);
        broadcastPresenceChange(userId, UserStatus.OFFLINE);
    }

    /**
     * Get current presence information for a user.
     */
    public PresenceInfo getPresence(String userId) {
        return presenceMap.get(userId);
    }

    /**
     * Scheduled task: scan all tracked users, mark those whose
     * heartbeat has not been refreshed within the timeout as OFFLINE.
     *
     * In production, this runs on a ScheduledExecutorService every 30s.
     */
    public void checkTimeouts() {
        List<String> timedOut = new ArrayList<>();

        presenceMap.forEach((userId, info) -> {
            if (info.isTimedOut(heartbeatTimeoutSeconds)) {
                timedOut.add(userId);
            }
        });

        for (String userId : timedOut) {
            System.out.println("[PRESENCE] Heartbeat timeout for user: " + userId);
            disconnect(userId);
        }
    }

    /**
     * Observer pattern: notify all users who share a conversation with
     * the status-changing user about the presence change.
     */
    private void broadcastPresenceChange(String userId, UserStatus newStatus) {
        // Find all users who share a conversation with this user
        List<Conversation> conversations =
            conversationRepository.findByUserId(userId);

        Set<String> notifiedUsers = new HashSet<>();

        for (Conversation conv : conversations) {
            for (String memberId : conv.getMemberIds()) {
                if (!memberId.equals(userId) && !notifiedUsers.contains(memberId)) {
                    ConnectionHandler handler = connectionHandlers.get(memberId);
                    if (handler != null && connectionRegistry.isOnline(memberId)) {
                        handler.deliverPresenceUpdate(userId, newStatus);
                        notifiedUsers.add(memberId);
                    }
                }
            }
        }
    }
}
```

### 6.4 GroupService

```java
/**
 * Manages group lifecycle: creation, member management, admin operations.
 * Sends SYSTEM messages on join/leave/admin events.
 */
public class GroupService {

    private final GroupRepository groupRepository;
    private final ConversationRepository conversationRepository;
    private final MessageService messageService;
    private final UserRepository userRepository;

    public GroupService(GroupRepository groupRepository,
                        ConversationRepository conversationRepository,
                        MessageService messageService,
                        UserRepository userRepository) {
        this.groupRepository        = groupRepository;
        this.conversationRepository = conversationRepository;
        this.messageService         = messageService;
        this.userRepository         = userRepository;
    }

    /**
     * Create a new group chat.
     * 1. Create GroupChat entity
     * 2. Add all initial members
     * 3. Save as both GroupChat and Conversation
     * 4. Send SYSTEM message announcing creation
     */
    public GroupChat createGroup(String name, String creatorId,
                                 List<String> memberIds, String description) {
        // Validate creator exists
        userRepository.findById(creatorId)
            .orElseThrow(() -> new UserNotFoundException(creatorId));

        String groupId = "grp-" + UUID.randomUUID().toString().substring(0, 8);
        GroupChat group = new GroupChat(groupId, name, creatorId, description);

        // Add initial members (creator already added in constructor)
        for (String memberId : memberIds) {
            if (!memberId.equals(creatorId)) {
                userRepository.findById(memberId)
                    .orElseThrow(() -> new UserNotFoundException(memberId));
                group.addMember(memberId);
            }
        }

        groupRepository.save(group);
        conversationRepository.save(group);

        // System message
        messageService.sendMessage(creatorId, groupId,
            "Group '" + name + "' created", MessageType.SYSTEM);

        return group;
    }

    /**
     * Add a member to a group (admin-only operation).
     */
    public void addMember(String groupId, String userId, String addedBy) {
        GroupChat group = getGroupOrThrow(groupId);
        validateAdmin(group, addedBy);
        userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        group.addMember(userId);
        groupRepository.save(group);
        conversationRepository.save(group);

        String adderName = getUserName(addedBy);
        String addedName = getUserName(userId);
        messageService.sendMessage(addedBy, groupId,
            adderName + " added " + addedName, MessageType.SYSTEM);
    }

    /**
     * Remove a member from a group (admin-only operation).
     */
    public void removeMember(String groupId, String userId, String removedBy) {
        GroupChat group = getGroupOrThrow(groupId);
        validateAdmin(group, removedBy);

        if (userId.equals(removedBy)) {
            throw new ChatException("Use leaveGroup() to remove yourself");
        }

        group.removeMember(userId);
        groupRepository.save(group);
        conversationRepository.save(group);

        String removerName = getUserName(removedBy);
        String removedName = getUserName(userId);
        messageService.sendMessage(removedBy, groupId,
            removerName + " removed " + removedName, MessageType.SYSTEM);
    }

    /**
     * A member leaves the group voluntarily.
     */
    public void leaveGroup(String groupId, String userId) {
        GroupChat group = getGroupOrThrow(groupId);

        if (!group.isMember(userId)) {
            throw new ChatException("User " + userId + " is not a member");
        }

        group.removeMember(userId);
        groupRepository.save(group);
        conversationRepository.save(group);

        String userName = getUserName(userId);
        messageService.sendMessage(userId, groupId,
            userName + " left the group", MessageType.SYSTEM);
    }

    // --- Private helpers ---

    private GroupChat getGroupOrThrow(String groupId) {
        return groupRepository.findById(groupId)
            .orElseThrow(() -> new ConversationNotFoundException(groupId));
    }

    private void validateAdmin(GroupChat group, String userId) {
        if (!group.isAdmin(userId)) {
            throw new UnauthorizedException(
                "User " + userId + " is not an admin of group "
                + group.getConversationId());
        }
    }

    private String getUserName(String userId) {
        return userRepository.findById(userId)
            .map(User::getUsername)
            .orElse(userId);
    }
}
```

### 6.5 UserService

```java
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createUser(String username) {
        String userId = "usr-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User(userId, username);
        return userRepository.save(user);
    }

    public User getUser(String userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
    }

    public void updateStatus(String userId, UserStatus status) {
        userRepository.updateStatus(userId, status);
    }
}
```

### 6.6 ChatService (Orchestrator / Mediator)

```java
/**
 * Mediator pattern: orchestrates interactions between MessageService,
 * GroupService, PresenceService, and MessageRouter.
 *
 * This is the single entry point for all chat operations, keeping
 * the controller thin and the sub-services decoupled from each other.
 */
public class ChatService {

    private final MessageService messageService;
    private final GroupService groupService;
    private final PresenceService presenceService;
    private final UserService userService;
    private final ConversationRepository conversationRepository;
    private final MessageRouter messageRouter;

    public ChatService(MessageService messageService,
                       GroupService groupService,
                       PresenceService presenceService,
                       UserService userService,
                       ConversationRepository conversationRepository,
                       MessageRouter messageRouter) {
        this.messageService         = messageService;
        this.groupService           = groupService;
        this.presenceService        = presenceService;
        this.userService            = userService;
        this.conversationRepository = conversationRepository;
        this.messageRouter          = messageRouter;
    }

    /**
     * Send a direct (1:1) message.
     * Finds or creates the 1:1 conversation, then delegates to MessageService.
     */
    public Message sendDirectMessage(String senderId, String recipientId,
                                      String content, MessageType type) {
        // Validate both users exist
        userService.getUser(senderId);
        userService.getUser(recipientId);

        // Find existing 1:1 conversation or create a new one
        Conversation conversation = conversationRepository
            .findOneToOne(senderId, recipientId)
            .orElseGet(() -> {
                Conversation conv = new Conversation(
                    "conv-" + UUID.randomUUID().toString().substring(0, 8),
                    Conversation.ConversationType.ONE_TO_ONE,
                    null,   // 1:1 conversations typically have no name
                    senderId
                );
                conv.addMember(senderId);
                conv.addMember(recipientId);
                return conversationRepository.save(conv);
            });

        return messageService.sendMessage(
            senderId, conversation.getConversationId(), content, type);
    }

    /**
     * Send a message in a group conversation.
     * Validates membership, then delegates to MessageService.
     */
    public Message sendGroupMessage(String senderId, String groupId,
                                     String content, MessageType type) {
        userService.getUser(senderId);

        Conversation group = conversationRepository.findById(groupId)
            .orElseThrow(() -> new ConversationNotFoundException(groupId));

        if (!group.isMember(senderId)) {
            throw new UnauthorizedException(
                "User " + senderId + " is not a member of group " + groupId);
        }

        return messageService.sendMessage(senderId, groupId, content, type);
    }

    /**
     * Create a new group.
     */
    public GroupChat createGroup(String name, String creatorId,
                                 List<String> memberIds, String description) {
        return groupService.createGroup(name, creatorId, memberIds, description);
    }

    /**
     * Get all conversations for a user.
     */
    public List<Conversation> getConversations(String userId) {
        userService.getUser(userId);  // validate
        return conversationRepository.findByUserId(userId);
    }

    /**
     * Get paginated message history.
     */
    public List<Message> getHistory(String conversationId, int limit) {
        return messageService.getHistory(conversationId, limit, Long.MAX_VALUE);
    }

    /**
     * Mark a message as read by a user.
     */
    public ReadReceipt markAsRead(String messageId, String userId) {
        return messageService.markAsRead(messageId, userId);
    }

    /**
     * User connects (WebSocket established).
     */
    public void connect(String userId, String serverId) {
        userService.getUser(userId);  // validate
        presenceService.userConnected(userId, serverId);
        messageRouter.deliverOfflineMessages(userId);
    }

    /**
     * User disconnects (WebSocket closed).
     */
    public void disconnect(String userId) {
        presenceService.disconnect(userId);
    }
}
```

---

## 7. Connection Handler (Simulated WebSocket)

### 7.1 ConnectionHandler

```java
/**
 * Simulates a WebSocket connection to a single client.
 * In production, this would wrap a real WebSocket session.
 *
 * Each connected user gets one ConnectionHandler instance.
 * The handler is stored in a Map<String, ConnectionHandler> keyed by userId.
 */
public class ConnectionHandler {

    private final String userId;
    private final String serverId;
    private final UserService userService;

    public ConnectionHandler(String userId, String serverId,
                             UserService userService) {
        this.userId      = userId;
        this.serverId    = serverId;
        this.userService = userService;
    }

    /**
     * Simulate pushing a message to the connected client.
     */
    public void deliverMessage(Message message) {
        String senderName = resolveSenderName(message.getSenderId());
        String typeIndicator = "";
        switch (message.getType()) {
            case IMAGE:  typeIndicator = "[Image] ";  break;
            case VIDEO:  typeIndicator = "[Video] ";  break;
            case FILE:   typeIndicator = "[File] ";   break;
            case SYSTEM: typeIndicator = "[System] "; break;
            default:     typeIndicator = "";           break;
        }
        System.out.println("[WS -> " + userId + "] " + senderName + ": "
            + typeIndicator + message.getContent()
            + " (seq=" + message.getSequenceNumber() + ")");
    }

    /**
     * Simulate pushing a presence update to the connected client.
     */
    public void deliverPresenceUpdate(String targetUserId, UserStatus status) {
        String targetName = resolveSenderName(targetUserId);
        System.out.println("[PRESENCE -> " + userId + "] "
            + targetName + " is now " + status);
    }

    /**
     * Simulate pushing a read receipt to the connected client.
     */
    public void deliverReceipt(ReadReceipt receipt) {
        System.out.println("[RECEIPT -> " + userId + "] Message "
            + receipt.getMessageId() + " was "
            + receipt.getStatus() + " by " + receipt.getUserId());
    }

    public String getUserId()  { return userId; }
    public String getServerId(){ return serverId; }

    private String resolveSenderName(String senderId) {
        try {
            return userService.getUser(senderId).getUsername();
        } catch (Exception e) {
            return senderId;
        }
    }
}
```

### 7.2 MessageHandler

```java
/**
 * Processes inbound messages from clients (simulated WebSocket frames).
 * Validates, parses, and delegates to ChatService.
 */
public class MessageHandler {

    private final ChatService chatService;

    public MessageHandler(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Handle an inbound text message from a connected client.
     * In production, this would parse a WebSocket frame/JSON payload.
     */
    public Message handleSendMessage(String senderId, String conversationId,
                                      String content, MessageType type) {
        if (content == null || content.trim().isEmpty()) {
            throw new ChatException("Message content cannot be empty");
        }
        if (type == MessageType.SYSTEM) {
            throw new ChatException("Clients cannot send SYSTEM messages");
        }
        return chatService.sendGroupMessage(senderId, conversationId,
                                             content, type);
    }

    /**
     * Handle a direct message request.
     */
    public Message handleDirectMessage(String senderId, String recipientId,
                                        String content, MessageType type) {
        if (content == null || content.trim().isEmpty()) {
            throw new ChatException("Message content cannot be empty");
        }
        return chatService.sendDirectMessage(senderId, recipientId, content, type);
    }

    /**
     * Handle read receipt from client.
     */
    public void handleReadReceipt(String messageId, String userId) {
        chatService.markAsRead(messageId, userId);
    }
}
```

---

## 8. Concurrency

The system is designed to be thread-safe throughout. Here is a summary of all concurrency mechanisms used.

| Component                    | Mechanism                          | Purpose                                                        |
|------------------------------|------------------------------------|----------------------------------------------------------------|
| `InMemoryMessageRepository`  | `ConcurrentHashMap<String, Msg>`   | Thread-safe message storage without full synchronization.      |
| `InMemoryMessageRepository`  | `ConcurrentSkipListMap<Long, Str>` | Lock-free sorted index for sequence-based range queries.       |
| `InMemoryConnectionRegistry` | `ConcurrentHashMap<String, Str>`   | Thread-safe user-to-server mapping.                            |
| `PresenceService`            | `ConcurrentHashMap<String, PI>`    | Thread-safe presence tracking; volatile fields in PresenceInfo.|
| `Conversation.lastSeqNumber` | `AtomicLong`                       | Lock-free monotonic sequence generation per conversation.      |
| `Conversation.memberIds`     | `ReentrantReadWriteLock`           | Multiple concurrent readers, exclusive writer for member list. |
| `GroupChat.adminIds`         | `ConcurrentHashMap.newKeySet()`    | Thread-safe set backed by ConcurrentHashMap.                   |
| `MessageRouter.offlineQueues`| `ConcurrentLinkedQueue<Message>`   | Lock-free FIFO queue for offline message buffering.            |
| `Message.deliveryStatus`     | `ConcurrentHashMap<String, MS>`    | Per-recipient status updates from multiple delivery threads.   |
| `PresenceInfo` fields        | `volatile`                         | Visibility guarantee for heartbeat and status fields.          |

### Concurrency Flow Example: Two Users Send Messages Simultaneously

```
Thread-1 (Alice sends msg)              Thread-2 (Bob sends msg)
      |                                       |
      v                                       v
conv.nextSequence() -> seq=5            conv.nextSequence() -> seq=6
      |  (AtomicLong.incrementAndGet)         |  (AtomicLong.incrementAndGet)
      v                                       v
messageRepo.save(msg_5)                 messageRepo.save(msg_6)
      |  (ConcurrentHashMap.put)              |  (ConcurrentHashMap.put)
      v                                       v
router.routeMessage(msg_5)              router.routeMessage(msg_6)
      |                                       |
      v                                       v
handler.deliverMessage(msg_5)           handler.deliverMessage(msg_6)
```

Both threads proceed independently with no blocking. The `AtomicLong` guarantees unique, monotonically increasing sequence numbers. The `ConcurrentHashMap` guarantees safe concurrent inserts.

---

## 9. Message Ordering

### Problem

In a distributed system, messages from different senders may arrive out of order due to network latency, load balancer routing, or multi-server deployment.

### Solution: Per-Conversation Sequence Numbers

```
Conversation
  |
  +-- lastSequenceNumber: AtomicLong
  |
  +-- nextSequence(): long
        |
        returns lastSequenceNumber.incrementAndGet()
```

**Invariants:**

1. Every message within a conversation gets a unique, monotonically increasing sequence number.
2. Sequence numbers are assigned server-side (not client-side) to prevent spoofing or gaps.
3. The `AtomicLong` ensures no two messages in the same conversation can get the same sequence number, even under concurrent sends.

### How Clients Use Sequence Numbers

```
Client receives:  seq=5, seq=8, seq=6, seq=7  (out of order)
Client reorders:  seq=5, seq=6, seq=7, seq=8  (display order)
Client detects:   gap between seq=5 and seq=8 -> requests seq=6, seq=7
```

### Pagination with Sequence Numbers

```
getHistory(conversationId, limit=20, beforeSequence=100)
  -> Returns messages with seq in [80..99], newest first

getHistory(conversationId, limit=20, beforeSequence=80)
  -> Returns messages with seq in [60..79], newest first

(Cursor-based pagination: client passes the lowest sequence number
 it has seen as `beforeSequence` for the next page.)
```

### Why Not Timestamps?

| Approach          | Problem                                                    |
|-------------------|------------------------------------------------------------|
| Timestamps        | Clock skew between servers; sub-millisecond collisions.    |
| UUIDs             | No ordering; random, so no efficient range queries.        |
| **Sequence (chosen)** | **Monotonic, gap-free, total order within conversation.**  |

---

## 10. Validation and Error Handling

### 10.1 Exception Hierarchy

```java
/**
 * Base exception for all chat-domain errors.
 */
public class ChatException extends RuntimeException {
    public ChatException(String message) {
        super(message);
    }

    public ChatException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Thrown when a referenced user does not exist.
 */
public class UserNotFoundException extends ChatException {
    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}

/**
 * Thrown when a referenced conversation/group does not exist.
 */
public class ConversationNotFoundException extends ChatException {
    public ConversationNotFoundException(String conversationId) {
        super("Conversation not found: " + conversationId);
    }
}

/**
 * Thrown when a user attempts an operation they are not authorized for.
 * Examples: non-member sending to a conversation, non-admin adding members.
 */
public class UnauthorizedException extends ChatException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
```

### 10.2 Validation Rules

| Operation              | Validations                                                       |
|------------------------|-------------------------------------------------------------------|
| Send message           | User exists, user is member of conversation, content not empty    |
| Send group message     | User exists, user is member of group                              |
| Create group           | Creator exists, all member IDs valid, name not empty              |
| Add member to group    | Requester is admin, target user exists, group not full            |
| Remove member          | Requester is admin, cannot remove self (use leave)                |
| Leave group            | User is a member of the group                                     |
| Mark as read           | Message exists, user is a recipient of the message               |
| Send SYSTEM message    | Only server can send; rejected if sent by a client               |

### 10.3 Controller-Level Error Handling

```java
// In ChatController — wraps all calls with try/catch
public void handleRequest(Runnable action) {
    try {
        action.run();
    } catch (UserNotFoundException e) {
        System.out.println("[ERROR 404] " + e.getMessage());
    } catch (ConversationNotFoundException e) {
        System.out.println("[ERROR 404] " + e.getMessage());
    } catch (UnauthorizedException e) {
        System.out.println("[ERROR 403] " + e.getMessage());
    } catch (ChatException e) {
        System.out.println("[ERROR 400] " + e.getMessage());
    } catch (Exception e) {
        System.out.println("[ERROR 500] Internal error: " + e.getMessage());
    }
}
```

---

## 11. Sample Workflows

### 11.1 1:1 Message (Both Users Online)

```
Alice (sender)         Server (ChatService)         Bob (recipient)
    |                        |                            |
    | 1. sendDirectMessage   |                            |
    |  (alice, bob,          |                            |
    |   "Hello!", TEXT)      |                            |
    |----------------------->|                            |
    |                        | 2. Validate alice exists   |
    |                        | 3. Validate bob exists     |
    |                        | 4. Find/create 1:1 conv    |
    |                        |    between alice & bob      |
    |                        |                            |
    |                        | 5. Delegate to             |
    |                        |    MessageService          |
    |                        |    .sendMessage()          |
    |                        |                            |
    |                        | 6. Validate alice is       |
    |                        |    member of conversation  |
    |                        |                            |
    |                        | 7. conv.nextSequence()     |
    |                        |    -> seq = 42             |
    |                        |                            |
    |                        | 8. Build Message via       |
    |                        |    Builder pattern         |
    |                        |    (id, convId, senderId,  |
    |                        |     content, type, seq)    |
    |                        |                            |
    |                        | 9. messageRepo.save(msg)   |
    |                        |                            |
    |                        | 10. MessageRouter          |
    |                        |     .routeMessage(msg,[bob])|
    |                        |                            |
    |                        | 11. ConnectionRegistry     |
    |                        |     .isOnline(bob) -> true |
    |                        |                            |
    |                        | 12. ConnectionHandler[bob] |
    |                        |     .deliverMessage(msg)   |
    |                        |-------------------------->|
    |                        |                            |
    |                        |     [WS -> bob] Alice:     |
    |                        |     Hello! (seq=42)        |
    |                        |                            |
    |                        | 13. msg.updateStatus       |
    |                        |     (bob, DELIVERED)       |
    |                        |                            |
    |  14. Return Message    |                            |
    |  (status=SENT)         |                            |
    |<-----------------------|                            |
```

### 11.2 1:1 Message (Recipient Offline, Comes Online Later)

```
Alice (sender)         Server                        Bob (offline)
    |                        |                            |
    | 1. sendDirectMessage   |                            |
    |  (alice, bob,          |                            |
    |   "Are you there?")   |                            |
    |----------------------->|                            |
    |                        | 2. Validate & find conv    |
    |                        | 3. Build & save message    |
    |                        |    (seq=43)                |
    |                        |                            |
    |                        | 4. MessageRouter           |
    |                        |    .routeMessage(msg,[bob])|
    |                        |                            |
    |                        | 5. ConnectionRegistry      |
    |                        |    .isOnline(bob) -> FALSE |
    |                        |                            |
    |                        | 6. Enqueue msg to          |
    |                        |    offlineQueues[bob]      |
    |                        |                            |
    |                        | 7. Trigger push notif:     |
    |                        |    [PUSH -> bob] New msg   |
    |                        |    from Alice              |
    |                        |                            |
    |  8. Return Message     |                            |
    |  (status=SENT)         |                            |
    |<-----------------------|                            |
    |                        |                            |
    |                        |   ... time passes ...      |
    |                        |                            |
    |                        |                    Bob connects
    |                        |<---------------------------|
    |                        |                            |
    |                        | 9. ChatService.connect     |
    |                        |    (bob, server-01)        |
    |                        |                            |
    |                        | 10. PresenceService        |
    |                        |     .userConnected(bob)    |
    |                        |                            |
    |                        | 11. MessageRouter          |
    |                        |     .deliverOfflineMessages|
    |                        |     (bob)                  |
    |                        |                            |
    |                        | 12. Dequeue msg (seq=43)   |
    |                        |     from offlineQueues[bob]|
    |                        |                            |
    |                        | 13. ConnectionHandler[bob] |
    |                        |     .deliverMessage(msg)   |
    |                        |-------------------------->|
    |                        |                            |
    |                        |     [WS -> bob] Alice:     |
    |                        |     Are you there? (seq=43)|
    |                        |                            |
    |                        | 14. msg.updateStatus       |
    |                        |     (bob, DELIVERED)       |
```

### 11.3 Group Message Fan-Out

```
Alice (sender)          Server                    Bob    Charlie    Diana
    |                      |                       |       |         |
    | 1. sendGroupMessage  |                       |       |         |
    |  (alice, grp-001,    |                       |       |         |
    |   "Team meeting!")   |                       |       |         |
    |--------------------->|                       |       |         |
    |                      | 2. Validate alice     |       |         |
    |                      |    is member of       |       |         |
    |                      |    grp-001            |       |         |
    |                      |                       |       |         |
    |                      | 3. conv.nextSequence  |       |         |
    |                      |    -> seq = 108       |       |         |
    |                      |                       |       |         |
    |                      | 4. Build msg with     |       |         |
    |                      |    recipients:        |       |         |
    |                      |    [bob,charlie,diana]|       |         |
    |                      |                       |       |         |
    |                      | 5. messageRepo.save   |       |         |
    |                      |                       |       |         |
    |                      | 6. routeMessage       |       |         |
    |                      |    fan-out to each:   |       |         |
    |                      |                       |       |         |
    |                      | 7a. bob online?  YES  |       |         |
    |                      |--deliver------------>|       |         |
    |                      |  [WS->bob] Alice:     |       |         |
    |                      |  Team meeting!(seq108)|       |         |
    |                      |                       |       |         |
    |                      | 7b. charlie online? YES      |         |
    |                      |--deliver--------------------->|         |
    |                      |  [WS->charlie] Alice:         |         |
    |                      |  Team meeting! (seq=108)      |         |
    |                      |                               |         |
    |                      | 7c. diana online? NO                    |
    |                      |--enqueue to offlineQueues[diana]        |
    |                      |  [PUSH->diana] New msg from Alice      |
    |                      |                                         |
    | 8. Return Message    |                                         |
    |  (status=SENT)       |                                         |
    |<---------------------|                                         |
```

### 11.4 Read Receipt Flow (Sent -> Delivered -> Read)

```
Alice (sender)         Server                        Bob (recipient)
    |                      |                              |
    | 1. Message sent      |                              |
    |  status=SENT         |                              |
    |                      | 2. Deliver to Bob            |
    |                      |----------------------------->|
    |                      |                              |
    |                      | 3. msg.updateStatus          |
    |                      |    (bob, DELIVERED)          |
    |                      |                              |
    |                      |    Overall status now:       |
    |                      |    DELIVERED                 |
    |                      |                              |
    |                      |                   4. Bob opens chat
    |                      |                              |
    |                      |          5. markAsRead       |
    |                      |             (msgId, bob)     |
    |                      |<-----------------------------|
    |                      |                              |
    |                      | 6. messageRepo.updateStatus  |
    |                      |    (msgId, bob, READ)        |
    |                      |                              |
    |                      | 7. Create ReadReceipt        |
    |                      |    (msgId, bob, READ, now)   |
    |                      |                              |
    |                      | 8. MessageRouter             |
    |                      |    .routeReadReceipt         |
    |                      |    (receipt, alice)          |
    |                      |                              |
    |                      | 9. ConnectionRegistry        |
    |                      |    .isOnline(alice) -> true  |
    |                      |                              |
    |  10. Deliver receipt |                              |
    |  [RECEIPT -> alice]  |                              |
    |  msg was READ by bob |                              |
    |<---------------------|                              |
    |                      |                              |
    | 11. UI updates:      |                              |
    |  single check ->     |                              |
    |  double check ->     |                              |
    |  blue double check   |                              |
```

### 11.5 User Goes Offline (Heartbeat Timeout)

```
Bob (connected)        Server                     Alice (contact)
    |                      |                           |
    | 1. Last heartbeat    |                           |
    |  at T=12:00:00       |                           |
    |--------------------->|                           |
    |                      | 2. PresenceInfo[bob]      |
    |                      |    .refreshHeartbeat()    |
    |                      |                           |
    |   (Bob's network     |                           |
    |    drops / app       |                           |
    |    crashes)          |                           |
    |                      |                           |
    |                      | 3. ScheduledExecutor runs |
    |                      |    checkTimeouts()        |
    |                      |    at T=12:01:05          |
    |                      |                           |
    |                      | 4. presenceMap.forEach:   |
    |                      |    bob.isTimedOut(60)?     |
    |                      |    65s > 60s -> YES       |
    |                      |                           |
    |                      | 5. [PRESENCE] Heartbeat   |
    |                      |    timeout for user: bob  |
    |                      |                           |
    |                      | 6. disconnect(bob):       |
    |                      |    - presenceMap.remove   |
    |                      |    - registry.unregister  |
    |                      |    - userRepo.updateStatus|
    |                      |      (bob, OFFLINE)       |
    |                      |                           |
    |                      | 7. broadcastPresence      |
    |                      |    Change(bob, OFFLINE)   |
    |                      |                           |
    |                      | 8. Find Alice (shares     |
    |                      |    conversation with Bob) |
    |                      |                           |
    |                      | 9. ConnectionHandler      |
    |                      |    [alice].deliverPresence|
    |                      |    Update(bob, OFFLINE)   |
    |                      |-------------------------->|
    |                      |                           |
    |                      |  [PRESENCE -> alice]      |
    |                      |  Bob is now OFFLINE       |
```

### 11.6 Media Message Sharing

```
Alice (sender)         Server                        Bob (recipient)
    |                      |                              |
    | 1. Client uploads    |                              |
    |    image to media    |                              |
    |    storage service   |                              |
    |    (S3/CDN)          |                              |
    |                      |                              |
    | 2. Receives media URL|                              |
    |    https://cdn/img123|                              |
    |                      |                              |
    | 3. sendDirectMessage |                              |
    |  (alice, bob,        |                              |
    |   "https://cdn/img123",                             |
    |   MessageType.IMAGE) |                              |
    |--------------------->|                              |
    |                      | 4. Validate alice, bob       |
    |                      | 5. Find/create conversation  |
    |                      |                              |
    |                      | 6. Build Message:            |
    |                      |   type = IMAGE               |
    |                      |   content = media URL        |
    |                      |   seq = 44                   |
    |                      |                              |
    |                      | 7. messageRepo.save(msg)     |
    |                      |                              |
    |                      | 8. Route to Bob              |
    |                      |----------------------------->|
    |                      |                              |
    |                      |  [WS -> bob] Alice:          |
    |                      |  [Image] https://cdn/img123  |
    |                      |  (seq=44)                    |
    |                      |                              |
    |                      |               9. Bob's client|
    |                      |    downloads image from CDN  |
    |                      |    and renders thumbnail     |
    |                      |                              |
    | 10. Return Message   |                              |
    |  (status=SENT,       |                              |
    |   type=IMAGE)        |                              |
    |<---------------------|                              |
```

---

## 12. Design Patterns Used

### 12.1 Observer Pattern (Presence Updates, Delivery Notifications)

```
Subject: PresenceService
Observers: Connected users (via ConnectionHandler)

When a user's status changes:
  PresenceService.broadcastPresenceChange(userId, newStatus)
      |
      +---> Find all users sharing conversations with userId
      +---> For each online contact:
              ConnectionHandler.deliverPresenceUpdate(userId, status)

Similarly for read receipts:
  MessageRouter.routeReadReceipt(receipt, senderId)
      |
      +---> If sender online:
              ConnectionHandler.deliverReceipt(receipt)
```

**Why Observer?** Decouples presence/receipt events from the specific consumers. Adding new notification channels (email, SMS) requires no changes to the PresenceService itself.

### 12.2 Mediator Pattern (ChatService)

```
                    ChatController
                         |
                         v
                   +------------+
                   | ChatService|  <-- MEDIATOR
                   +------------+
                  /   |    |    \
                 /    |    |     \
                v     v    v      v
        Message  Group  Presence  User
        Service  Service Service  Service
```

**Why Mediator?** Sub-services (MessageService, GroupService, PresenceService) do not know about each other. ChatService coordinates multi-service flows (e.g., `connect` involves PresenceService + MessageRouter). This keeps each service focused on a single responsibility.

### 12.3 Builder Pattern (Message)

```
Message msg = Message.builder()
    .messageId(UUID.randomUUID().toString())
    .conversationId("conv-001")
    .senderId("alice")
    .content("Hello!")
    .type(MessageType.TEXT)
    .status(MessageStatus.SENT)
    .sequenceNumber(42)
    .addRecipient("bob")
    .build();
```

**Why Builder?** Message has 8+ fields, several optional. A telescoping constructor would be unreadable. The Builder provides a fluent API with compile-time validation via `build()`.

### 12.4 Factory Pattern (AppConfig)

```java
public class AppConfig {

    public static final int HEARTBEAT_TIMEOUT_SECONDS = 60;
    public static final int MAX_GROUP_MEMBERS = 256;
    public static final int MESSAGE_PAGE_SIZE = 50;
    public static final int OFFLINE_QUEUE_CAPACITY = 10000;
    public static final String SERVER_ID = "server-01";

    // Factory methods wire the entire object graph
    public static ChatService createChatService() {
        // Repositories
        MessageRepository messageRepo          = new InMemoryMessageRepository();
        ConversationRepository conversationRepo = new InMemoryConversationRepository();
        UserRepository userRepo                 = new InMemoryUserRepository();
        GroupRepository groupRepo               = new InMemoryGroupRepository();
        ConnectionRegistry registry             = new InMemoryConnectionRegistry();

        // Connection handlers (populated as users connect)
        Map<String, ConnectionHandler> handlers = new ConcurrentHashMap<>();

        // Services
        UserService userService       = new UserService(userRepo);
        MessageRouter router          = new MessageRouter(registry, handlers);
        MessageService messageService = new MessageService(
            messageRepo, conversationRepo, router);

        PresenceService presenceService = new PresenceService(
            registry, userRepo, handlers, conversationRepo,
            HEARTBEAT_TIMEOUT_SECONDS);

        GroupService groupService = new GroupService(
            groupRepo, conversationRepo, messageService, userRepo);

        return new ChatService(
            messageService, groupService, presenceService,
            userService, conversationRepo, router);
    }
}
```

**Why Factory?** Encapsulates the complex wiring of repositories, services, and handlers. Tests can override with mock implementations. The main method calls `AppConfig.createChatService()` and gets a fully wired system.

### 12.5 Repository Pattern (Data Access)

```
<<interface>>                    <<implementation>>
MessageRepository        <---   InMemoryMessageRepository
ConversationRepository   <---   InMemoryConversationRepository
UserRepository           <---   InMemoryUserRepository
GroupRepository          <---   InMemoryGroupRepository
ConnectionRegistry       <---   InMemoryConnectionRegistry
```

**Why Repository?** Abstracts data access behind interfaces. Swap `InMemory*` for `Cassandra*` or `Redis*` implementations without changing any service code. Each repository owns the storage semantics for one aggregate root.

### 12.6 Strategy Pattern (Message Routing)

```
MessageRouter.routeMessage(message, recipientIds)
    |
    for each recipient:
    |
    +-- Strategy A: Online Delivery
    |     ConnectionRegistry.isOnline(recipientId) == true
    |     -> ConnectionHandler.deliverMessage(msg)
    |     -> msg.updateStatus(recipientId, DELIVERED)
    |
    +-- Strategy B: Offline Queueing
          ConnectionRegistry.isOnline(recipientId) == false
          -> offlineQueues.enqueue(recipientId, msg)
          -> trigger push notification
```

**Why Strategy?** The routing decision (online vs. offline) is made at runtime per recipient. New delivery strategies (e.g., "forward to another server in the cluster") can be added without modifying the core routing logic.

### 12.7 Command Pattern (Message as Command)

```
Message object is a self-contained command:
  - Who sent it (senderId)
  - Where it goes (conversationId, recipients in deliveryStatus)
  - What it contains (content, type)
  - Ordering info (sequenceNumber)
  - Status tracking (deliveryStatus per recipient)

The Message flows through the system as a command object:
  Client -> MessageHandler -> ChatService -> MessageService
         -> MessageRepository (persist)
         -> MessageRouter (route/execute)
         -> ConnectionHandler (deliver)
```

**Why Command?** Messages are first-class objects that carry all information needed for their own processing. They can be queued (offline), retried (failed delivery), logged, and replayed.

---

## 13. Extensibility

### 13.1 Typing Indicators

```
Add to ConnectionHandler:
  + deliverTypingIndicator(conversationId, userId, boolean isTyping)

Add to ChatService:
  + sendTypingIndicator(userId, conversationId, boolean isTyping)
    -> Find conversation members
    -> For each online member (except sender):
         connectionHandler.deliverTypingIndicator(convId, userId, isTyping)

No persistence needed — typing indicators are ephemeral, fire-and-forget.
Throttle on the client side: send at most 1 indicator per 3 seconds.
```

### 13.2 Message Editing / Deletion

```
Add to Message:
  + editedAt: LocalDateTime (null if never edited)
  + deletedAt: LocalDateTime (null if not deleted)
  + originalContent: String (for audit trail)
  + isEdited(): boolean
  + isDeleted(): boolean

Add to MessageService:
  + editMessage(messageId, senderId, newContent):
      1. Validate sender owns the message
      2. Validate within edit window (e.g., 15 minutes)
      3. Store originalContent, update content, set editedAt
      4. Route MESSAGE_EDITED event to all recipients

  + deleteMessage(messageId, senderId):
      1. Validate sender owns the message
      2. Set deletedAt, replace content with "This message was deleted"
      3. Route MESSAGE_DELETED event to all recipients

Add MessageType: MESSAGE_EDITED, MESSAGE_DELETED
```

### 13.3 Reactions

```
New entity:
  Reaction { messageId, userId, emoji, timestamp }

Add to Message:
  + reactions: Map<String, Set<Reaction>>  // emoji -> set of reactions

New repository:
  ReactionRepository { save, findByMessageId, delete }

Add to MessageService:
  + addReaction(messageId, userId, emoji):
      1. Validate user is in the conversation
      2. Save reaction
      3. Route REACTION event to conversation members

  + removeReaction(messageId, userId, emoji):
      1. Remove reaction
      2. Route REACTION_REMOVED event
```

### 13.4 End-to-End Encryption

```
Add to User:
  + publicKey: String (asymmetric encryption public key)

New service:
  EncryptionService:
    + generateKeyPair(): KeyPair
    + encryptForRecipient(content, recipientPublicKey): String
    + decryptWithPrivateKey(encrypted, privateKey): String

Modify MessageService.sendMessage():
  - For 1:1: encrypt content with recipient's public key
  - For groups: use a shared group key (distributed via
    pairwise encryption with each member's public key)

Messages stored encrypted; server cannot read content.
Key exchange protocol:
  1. Alice generates session key K
  2. Alice encrypts K with Bob's public key -> K_bob
  3. Alice sends K_bob to Bob
  4. Both use K for symmetric encryption of messages
```

### 13.5 Voice Messages

```
Extend MessageType enum:
  + VOICE

Voice message flow:
  1. Client records audio
  2. Client uploads to media storage (S3/CDN)
  3. Client sends Message with type=VOICE, content=mediaUrl
  4. Additional metadata in a new field:
       + mediaMetadata: Map<String, String>
         { "duration": "0:32", "waveform": "[0.2,0.5,0.8,...]" }

No server-side changes to routing or delivery.
ConnectionHandler renders: [WS -> bob] Alice: [Voice 0:32] https://cdn/voice123
```

### 13.6 Read Receipt Toggle (Privacy Setting)

```
Add to User:
  + sendReadReceipts: boolean = true  (default on)

Modify MessageService.markAsRead():
  Before:
    Always send read receipt to sender.
  After:
    1. Check user.isSendReadReceipts()
    2. If false: still update local deliveryStatus to READ
       (so the reader's own UI shows "read") but DO NOT
       route the ReadReceipt to the sender
    3. If true: route normally

Privacy contract: if you disable read receipts, you also
cannot see others' read receipts (enforced client-side).
```

---

## Summary

This LLD covers a complete chat messaging system with the following key design decisions:

- **AtomicLong-based sequence numbers** for total message ordering within conversations
- **ConcurrentHashMap + ConcurrentSkipListMap** for lock-free, thread-safe in-memory storage
- **ReadWriteLock** on group member lists to allow concurrent reads with exclusive writes
- **Builder pattern** on Message for clean construction of complex objects
- **Mediator (ChatService)** to keep sub-services decoupled and orchestrate cross-cutting flows
- **Strategy-based routing** (online delivery vs. offline queueing) for flexible message fan-out
- **Observer-based presence** broadcasting to notify contacts of status changes
- **Repository interfaces** for clean data access abstraction, swappable for any persistence backend
- **Per-recipient delivery tracking** (Map<String, MessageStatus>) for granular read receipts

The design is extensible (typing indicators, reactions, E2E encryption, voice messages) without modifying existing core classes, following the Open-Closed Principle.
