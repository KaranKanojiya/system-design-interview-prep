# Low-Level Design: Notification System

> Interview-ready LLD for Senior Java Developer (7+ years).
> Covers core modules, class design, interfaces, concurrency, retry logic, and design patterns.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Handler Implementations (Strategy Pattern)](#6-handler-implementations-strategy-pattern)
7. [Service Layer](#7-service-layer)
8. [Template Engine](#8-template-engine)
9. [Priority Queue](#9-priority-queue)
10. [Concurrency Considerations](#10-concurrency-considerations)
11. [Retry Logic](#11-retry-logic)
12. [Validation and Error Handling](#12-validation-and-error-handling)
13. [Sample Workflows](#13-sample-workflows)
14. [Design Patterns](#14-design-patterns)
15. [Extensibility](#15-extensibility)

---

## 1. Core Modules Overview

| Module       | Responsibility                                                       | Key Classes                                                        |
|--------------|----------------------------------------------------------------------|--------------------------------------------------------------------|
| **model**    | Domain entities and enums                                            | Notification, NotificationRequest, Channel, Priority, NotificationStatus, UserPreference, NotificationTemplate, DeliveryAttempt |
| **service**  | Business logic orchestration                                         | NotificationService, PreferenceService, TemplateService, DeliveryTracker |
| **handler**  | Channel-specific delivery (Strategy implementations)                 | NotificationHandler (interface), PushNotificationHandler, EmailNotificationHandler, SmsNotificationHandler, InAppNotificationHandler |
| **queue**    | Priority-based notification buffering                                | NotificationQueue (interface), InMemoryPriorityQueue, QueueConsumer |
| **template** | Template rendering engine                                            | SimpleTemplateEngine                                               |
| **repository** | Data access abstraction (in-memory for demo)                       | NotificationRepository, PreferenceRepository, TemplateRepository + in-memory impls |
| **controller** | API entry point                                                    | NotificationController                                             |
| **config**   | Application wiring and constants                                     | AppConfig                                                          |
| **exception** | Custom domain exceptions                                            | NotificationException, TemplateNotFoundException, UserOptedOutException, RateLimitExceededException |

---

## 2. Package Structure

```
com.systemdesign.notification
├── model/
│   ├── Notification.java                  -- Core entity, Builder pattern
│   ├── NotificationRequest.java           -- Inbound DTO (single + batch)
│   ├── NotificationStatus.java            -- Enum: PENDING -> SENT -> DELIVERED / FAILED
│   ├── Channel.java                       -- Enum: PUSH, EMAIL, SMS, IN_APP
│   ├── Priority.java                      -- Enum: CRITICAL(0), HIGH(1), MEDIUM(2), LOW(3)
│   ├── UserPreference.java                -- Channel toggles, quiet hours, frequency caps
│   ├── NotificationTemplate.java          -- Subject/body templates with variable placeholders
│   └── DeliveryAttempt.java               -- Per-attempt record (notificationId, status, response)
│
├── handler/
│   ├── NotificationHandler.java           -- Interface: send(), supportedChannel(), isAvailable()
│   ├── PushNotificationHandler.java       -- FCM/APNs simulation, 90% success
│   ├── EmailNotificationHandler.java      -- SES/SendGrid simulation, 95% success
│   ├── SmsNotificationHandler.java        -- Twilio simulation, 85% success, rate-limited
│   └── InAppNotificationHandler.java      -- Always succeeds, in-app store write
│
├── service/
│   ├── NotificationService.java           -- Core orchestrator: send, processPending, retry, batch
│   ├── PreferenceService.java             -- Preference checks, quiet hours, frequency caps
│   ├── TemplateService.java               -- Template loading + rendering
│   └── DeliveryTracker.java               -- Attempt recording, status updates, stats
│
├── queue/
│   ├── NotificationQueue.java             -- Interface: enqueue, dequeue, size, isEmpty
│   ├── InMemoryPriorityQueue.java         -- PriorityBlockingQueue ordered by Priority ordinal
│   └── QueueConsumer.java                 -- Worker thread that drains queue and dispatches
│
├── repository/
│   ├── NotificationRepository.java        -- Interface: save, findById, findByUserId, updateStatus
│   ├── PreferenceRepository.java          -- Interface: findByUserId, save, isChannelEnabled
│   ├── TemplateRepository.java            -- Interface: findById, findByName, save
│   ├── InMemoryNotificationRepository.java
│   ├── InMemoryPreferenceRepository.java
│   └── InMemoryTemplateRepository.java
│
├── controller/
│   └── NotificationController.java        -- REST-style entry: sendNotification, sendBatch, getStatus
│
├── config/
│   └── AppConfig.java                     -- Constants, handler registry, executor pool config
│
├── exception/
│   ├── NotificationException.java         -- Base exception
│   ├── TemplateNotFoundException.java
│   ├── UserOptedOutException.java
│   └── RateLimitExceededException.java
│
└── template/
    └── SimpleTemplateEngine.java          -- {{variable}} replacement engine
```

---

## 3. Class Diagram

```
+------------------------------------------------------+
|                   NotificationController              |
|------------------------------------------------------|
| - notificationService: NotificationService            |
|------------------------------------------------------|
| + sendNotification(req: NotificationRequest): String  |
| + sendBatch(req: NotificationRequest): List<String>   |
| + getStatus(notificationId: String): Notification     |
| + getUserPreferences(userId: String): UserPreference  |
+------------------------------------------------------+
                          |
                          | delegates to
                          v
+------------------------------------------------------+
|                   NotificationService                 |
|------------------------------------------------------|
| - preferenceService: PreferenceService                |
| - templateService: TemplateService                    |
| - deliveryTracker: DeliveryTracker                    |
| - notificationQueue: NotificationQueue                |
| - notificationRepo: NotificationRepository            |
| - handlerRegistry: Map<Channel, NotificationHandler>  |
|------------------------------------------------------|
| + send(req: NotificationRequest): String              |
| + sendBatch(req: NotificationRequest): List<String>   |
| + processPendingNotifications(): void                 |
| + retryFailed(notification: Notification): void       |
+------------------------------------------------------+
           |              |              |
           v              v              v
+------------------+  +----------------+  +------------------+
| PreferenceService|  | TemplateService|  | DeliveryTracker  |
|------------------|  |----------------|  |------------------|
| - prefRepo       |  | - templateRepo |  | - notifRepo      |
| - notifRepo      |  | - engine       |  | - attempts: Map  |
|------------------|  |----------------|  |------------------|
| + isAllowed()    |  | + render()     |  | + record()       |
| + checkQuiet()   |  | + loadTemplate |  | + updateStatus() |
| + checkFreqCap() |  | + validate()   |  | + getStats()     |
+------------------+  +----------------+  +------------------+
                              |
                              v
                   +---------------------+
                   | SimpleTemplateEngine |
                   |---------------------|
                   | + render(template,  |
                   |   data): String     |
                   | + validate()        |
                   +---------------------+

+---------------------------------------------------+
|         <<interface>> NotificationHandler          |
|---------------------------------------------------|
| + send(notification: Notification): DeliveryAttempt|
| + supportedChannel(): Channel                      |
| + isAvailable(): boolean                           |
+---------------------------------------------------+
       ^           ^           ^           ^
       |           |           |           |
+----------+ +----------+ +--------+ +---------+
| Push     | | Email    | | Sms    | | InApp   |
| Handler  | | Handler  | | Handler| | Handler |
+----------+ +----------+ +--------+ +---------+
  90% ok      95% ok       85% ok    100% ok

+---------------------------------------------------+
|           <<interface>> NotificationQueue          |
|---------------------------------------------------|
| + enqueue(notification: Notification): void        |
| + dequeue(): Notification                          |
| + size(): int                                      |
| + isEmpty(): boolean                               |
+---------------------------------------------------+
                     ^
                     |
          +-------------------------+
          | InMemoryPriorityQueue   |
          |-------------------------|       +----------------+
          | - queue: PriorityBlock- | <---- | QueueConsumer  |
          |   ingQueue<Notification>|       |----------------|
          +-------------------------+       | - queue        |
                                            | - service      |
                                            | + start()      |
                                            | + stop()       |
                                            +----------------+

+---------------------------------------------------+
|       <<interface>> NotificationRepository        |
|---------------------------------------------------|
| + save(n: Notification): Notification              |
| + findById(id: String): Optional<Notification>     |
| + findByUserId(uid: String): List<Notification>    |
| + updateStatus(id, status): void                   |
| + findPendingRetries(): List<Notification>          |
+---------------------------------------------------+
                     ^
                     |
          +----------------------------+
          | InMemoryNotificationRepo   |
          |----------------------------|
          | - store: ConcurrentHashMap |
          +----------------------------+

+-------------------------------------------------+
|       <<interface>> PreferenceRepository        |
|-------------------------------------------------|
| + findByUserId(uid: String): Optional<UserPref> |
| + save(pref: UserPreference): void              |
| + isChannelEnabled(uid, channel): boolean       |
+-------------------------------------------------+
                     ^
                     |
          +----------------------------+
          | InMemoryPreferenceRepo     |
          |----------------------------|
          | - store: ConcurrentHashMap |
          +----------------------------+

+-------------------------------------------------+
|       <<interface>> TemplateRepository          |
|-------------------------------------------------|
| + findById(id: String): Optional<Template>      |
| + findByName(name: String): Optional<Template>  |
| + save(template: NotificationTemplate): void    |
+-------------------------------------------------+
                     ^
                     |
          +----------------------------+
          | InMemoryTemplateRepo       |
          |----------------------------|
          | - store: ConcurrentHashMap |
          +----------------------------+

+-------------------+     +---------------------+
| Notification      |     | NotificationRequest |
|-------------------|     |---------------------|
| - id: String      |     | - userId: String    |
| - userId: String  |     | - userIds: List     |
| - templateId      |     | - templateId: String|
| - channel: Channel|     | - channel: Channel  |
| - priority        |     | - priority: Priority|
| - status          |     | - data: Map<S,S>    |
| - subject: String |     | - scheduledAt: Long |
| - body: String    |     +---------------------+
| - data: Map<S,S>  |
| - scheduledAt     |     +---------------------+
| - sentAt          |     | UserPreference      |
| - deliveredAt     |     |---------------------|
| - retryCount: int |     | - userId            |
| - maxRetries: int |     | - channelEnabled    |
| - createdAt: long |     | - quietHoursStart   |
| + Builder         |     | - quietHoursEnd     |
+-------------------+     | - dailyFreqCap      |
                          +---------------------+
+-------------------+
| DeliveryAttempt   |     +---------------------+
|-------------------|     | NotificationTemplate|
| - notificationId  |     |---------------------|
| - attemptNumber   |     | - id: String        |
| - status          |     | - name: String      |
| - providerResponse|     | - channel: Channel  |
| - timestamp: long |     | - subjectTemplate   |
+-------------------+     | - bodyTemplate      |
                          | - requiredVariables |
                          +---------------------+

+---------------------------+
| <<enum>> Channel          |
|---------------------------|      +---------------------------+
| PUSH("Push Notification") |      | <<enum>> Priority         |
| EMAIL("Email")            |      |---------------------------|
| SMS("SMS")                |      | CRITICAL(0)               |
| IN_APP("In-App")          |      | HIGH(1)                   |
| - displayName: String     |      | MEDIUM(2)                 |
+---------------------------+      | LOW(3)                    |
                                   | - value: int              |
+---------------------------+      +---------------------------+
| <<enum>> NotificationStatus|
|---------------------------|
| PENDING, QUEUED, SENDING  |
| SENT, DELIVERED, FAILED   |
| BOUNCED, CANCELLED        |
+---------------------------+
```

---

## 4. Entity Design

### 4.1 Channel Enum

```java
public enum Channel {
    PUSH("Push Notification"),
    EMAIL("Email"),
    SMS("SMS"),
    IN_APP("In-App");

    private final String displayName;

    Channel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
```

**Interview note**: Each channel maps 1:1 to a `NotificationHandler` implementation. The enum acts as the routing key in the handler registry (`Map<Channel, NotificationHandler>`).

---

### 4.2 Priority Enum

```java
public enum Priority implements Comparable<Priority> {
    CRITICAL(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3);

    private final int value;

    Priority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
```

**Why int values?** The `PriorityBlockingQueue` uses a `Comparator<Notification>` that compares `priority.getValue()`. Lower value = higher priority = dequeued first. CRITICAL(0) always beats LOW(3).

---

### 4.3 NotificationStatus Enum

```java
public enum NotificationStatus {
    PENDING,      // Created, not yet queued
    QUEUED,       // In the priority queue
    SENDING,      // Picked up by worker, in-flight
    SENT,         // Handler confirmed dispatch
    DELIVERED,    // Delivery receipt confirmed
    FAILED,       // All retries exhausted
    BOUNCED,      // Permanently undeliverable (bad address)
    CANCELLED     // User/system cancelled before send
}
```

**State transitions**:
```
PENDING --> QUEUED --> SENDING --> SENT --> DELIVERED
                         |
                         +--> FAILED (after max retries)
                         +--> BOUNCED (permanent failure)
PENDING --> CANCELLED (user opts out, quiet hours)
```

---

### 4.4 Notification (Builder Pattern)

```java
public class Notification implements Comparable<Notification> {
    private final String id;
    private final String userId;
    private final String templateId;
    private final Channel channel;
    private final Priority priority;
    private NotificationStatus status;
    private String subject;
    private String body;
    private Map<String, String> data;
    private Long scheduledAt;    // epoch millis, null = immediate
    private Long sentAt;
    private Long deliveredAt;
    private int retryCount;
    private int maxRetries;
    private final long createdAt;

    private Notification(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.userId = builder.userId;
        this.templateId = builder.templateId;
        this.channel = builder.channel;
        this.priority = builder.priority != null ? builder.priority : Priority.MEDIUM;
        this.status = NotificationStatus.PENDING;
        this.subject = builder.subject;
        this.body = builder.body;
        this.data = builder.data != null ? builder.data : new HashMap<>();
        this.scheduledAt = builder.scheduledAt;
        this.retryCount = 0;
        this.maxRetries = builder.maxRetries;
        this.createdAt = System.currentTimeMillis();
    }

    // Comparable for PriorityBlockingQueue ordering
    @Override
    public int compareTo(Notification other) {
        return Integer.compare(this.priority.getValue(), other.priority.getValue());
    }

    // --- Builder ---
    public static class Builder {
        private String id;
        private String userId;       // required
        private String templateId;   // required
        private Channel channel;     // required
        private Priority priority;
        private String subject;
        private String body;
        private Map<String, String> data;
        private Long scheduledAt;
        private int maxRetries = 3;

        public Builder(String userId, String templateId, Channel channel) {
            this.userId = userId;
            this.templateId = templateId;
            this.channel = channel;
        }

        public Builder id(String id)                     { this.id = id; return this; }
        public Builder priority(Priority p)              { this.priority = p; return this; }
        public Builder subject(String s)                 { this.subject = s; return this; }
        public Builder body(String b)                    { this.body = b; return this; }
        public Builder data(Map<String, String> d)       { this.data = d; return this; }
        public Builder scheduledAt(Long t)               { this.scheduledAt = t; return this; }
        public Builder maxRetries(int r)                 { this.maxRetries = r; return this; }

        public Notification build() {
            Objects.requireNonNull(userId, "userId is required");
            Objects.requireNonNull(templateId, "templateId is required");
            Objects.requireNonNull(channel, "channel is required");
            return new Notification(this);
        }
    }

    // Standard getters, setters for mutable fields (status, sentAt, deliveredAt, retryCount)
}
```

**Interview talking points**:
- Builder pattern avoids telescoping constructor (12+ fields).
- `Notification` implements `Comparable<Notification>` so `PriorityBlockingQueue` can order by priority.
- Immutable required fields set in constructor; only status/timestamps/retryCount are mutable.

---

### 4.5 NotificationRequest (DTO)

```java
public class NotificationRequest {
    private String userId;             // for single send
    private List<String> userIds;      // for batch send
    private String templateId;
    private Channel channel;
    private Priority priority;
    private Map<String, String> data;  // template variables
    private Long scheduledAt;          // optional, null = immediate

    // Constructors
    public NotificationRequest(String userId, String templateId,
                               Channel channel, Priority priority,
                               Map<String, String> data) {
        this.userId = userId;
        this.templateId = templateId;
        this.channel = channel;
        this.priority = priority;
        this.data = data;
    }

    // Batch constructor
    public static NotificationRequest batch(List<String> userIds,
                                            String templateId,
                                            Channel channel,
                                            Priority priority,
                                            Map<String, String> data) {
        NotificationRequest req = new NotificationRequest();
        req.userIds = userIds;
        req.templateId = templateId;
        req.channel = channel;
        req.priority = priority;
        req.data = data;
        return req;
    }

    public boolean isBatch() {
        return userIds != null && !userIds.isEmpty();
    }

    // Getters and setters
}
```

---

### 4.6 UserPreference

```java
public class UserPreference {
    private String userId;
    private Map<Channel, Boolean> channelEnabled;  // true = opted in
    private int quietHoursStart;                   // hour 0-23 (e.g., 22 = 10 PM)
    private int quietHoursEnd;                     // hour 0-23 (e.g., 8 = 8 AM)
    private Map<Channel, Integer> dailyFrequencyCap; // max notifications per channel per day

    public UserPreference(String userId) {
        this.userId = userId;
        this.channelEnabled = new EnumMap<>(Channel.class);
        this.dailyFrequencyCap = new EnumMap<>(Channel.class);
        // Default: all channels enabled
        for (Channel ch : Channel.values()) {
            channelEnabled.put(ch, true);
        }
        this.quietHoursStart = -1; // disabled
        this.quietHoursEnd = -1;
    }

    public boolean isChannelEnabled(Channel channel) {
        return channelEnabled.getOrDefault(channel, true);
    }

    public boolean isQuietHoursActive() {
        if (quietHoursStart < 0 || quietHoursEnd < 0) return false;
        int currentHour = LocalTime.now().getHour();
        if (quietHoursStart <= quietHoursEnd) {
            // e.g., 9 AM to 5 PM
            return currentHour >= quietHoursStart && currentHour < quietHoursEnd;
        } else {
            // wraps midnight: e.g., 22 to 8
            return currentHour >= quietHoursStart || currentHour < quietHoursEnd;
        }
    }

    // Getters and setters
}
```

---

### 4.7 NotificationTemplate

```java
public class NotificationTemplate {
    private String id;
    private String name;                      // e.g., "order_confirmation"
    private Channel channel;
    private String subjectTemplate;           // "Order {{orderId}} confirmed"
    private String bodyTemplate;              // "Hello {{name}}, your order..."
    private List<String> requiredVariables;   // ["name", "orderId"]

    public NotificationTemplate(String id, String name, Channel channel,
                                String subjectTemplate, String bodyTemplate,
                                List<String> requiredVariables) {
        this.id = id;
        this.name = name;
        this.channel = channel;
        this.subjectTemplate = subjectTemplate;
        this.bodyTemplate = bodyTemplate;
        this.requiredVariables = requiredVariables;
    }

    // Getters
}
```

---

### 4.8 DeliveryAttempt

```java
public class DeliveryAttempt {
    private final String notificationId;
    private final int attemptNumber;
    private final NotificationStatus status;   // SENT, FAILED, BOUNCED
    private final String providerResponse;     // "MessageId: abc123" or error message
    private final long timestamp;

    public DeliveryAttempt(String notificationId, int attemptNumber,
                           NotificationStatus status, String providerResponse) {
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.providerResponse = providerResponse;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isSuccess() {
        return status == NotificationStatus.SENT || status == NotificationStatus.DELIVERED;
    }

    // Getters
}
```

---

## 5. Interface Contracts

### 5.1 NotificationHandler

```java
public interface NotificationHandler {

    /**
     * Send notification via this channel.
     * Returns a DeliveryAttempt recording success/failure and provider response.
     */
    DeliveryAttempt send(Notification notification);

    /**
     * Which channel does this handler serve?
     * Used by the handler registry for routing.
     */
    Channel supportedChannel();

    /**
     * Is this channel currently available?
     * Allows circuit-breaker behavior: if a provider is down, skip and retry later.
     */
    boolean isAvailable();
}
```

**Contract rules**:
- One implementation per `Channel` enum value.
- `send()` must never throw unchecked exceptions -- return a FAILED `DeliveryAttempt` instead.
- `isAvailable()` enables graceful degradation (circuit breaker pattern in production).

---

### 5.2 NotificationQueue

```java
public interface NotificationQueue {

    /**
     * Add notification to the queue. Higher priority notifications
     * are dequeued first (CRITICAL before LOW).
     */
    void enqueue(Notification notification);

    /**
     * Remove and return the highest-priority notification.
     * Blocks if queue is empty (blocking queue semantics).
     */
    Notification dequeue() throws InterruptedException;

    /** Current number of notifications waiting in the queue. */
    int size();

    /** True if no notifications are waiting. */
    boolean isEmpty();
}
```

---

### 5.3 NotificationRepository

```java
public interface NotificationRepository {

    Notification save(Notification notification);

    Optional<Notification> findById(String id);

    List<Notification> findByUserId(String userId);

    void updateStatus(String notificationId, NotificationStatus status);

    /** Find notifications that failed but still have retries remaining. */
    List<Notification> findPendingRetries();
}
```

---

### 5.4 PreferenceRepository

```java
public interface PreferenceRepository {

    Optional<UserPreference> findByUserId(String userId);

    void save(UserPreference preference);

    boolean isChannelEnabled(String userId, Channel channel);
}
```

---

### 5.5 TemplateRepository

```java
public interface TemplateRepository {

    Optional<NotificationTemplate> findById(String id);

    Optional<NotificationTemplate> findByName(String name);

    void save(NotificationTemplate template);
}
```

---

## 6. Handler Implementations (Strategy Pattern)

Each handler implements `NotificationHandler`. The `NotificationService` holds a `Map<Channel, NotificationHandler>` and routes by `notification.getChannel()`.

### 6.1 PushNotificationHandler

```java
public class PushNotificationHandler implements NotificationHandler {

    private static final double SUCCESS_RATE = 0.90;
    private final Random random = new Random();
    private volatile boolean available = true;

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("[PUSH] Sending to userId: %s subject: %s%n",
                notification.getUserId(), notification.getSubject());

        // Simulate FCM/APNs call latency
        simulateLatency(100, 500);

        boolean success = random.nextDouble() < SUCCESS_RATE;

        if (success) {
            String messageId = "fcm-" + UUID.randomUUID().toString().substring(0, 8);
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.SENT,
                "MessageId: " + messageId
            );
        } else {
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.FAILED,
                "FCM error: DeviceNotRegistered"
            );
        }
    }

    @Override
    public Channel supportedChannel() { return Channel.PUSH; }

    @Override
    public boolean isAvailable() { return available; }

    private void simulateLatency(int minMs, int maxMs) {
        try {
            Thread.sleep(minMs + random.nextInt(maxMs - minMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

### 6.2 EmailNotificationHandler

```java
public class EmailNotificationHandler implements NotificationHandler {

    private static final double SUCCESS_RATE = 0.95;
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private final Random random = new Random();
    private volatile boolean available = true;

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("[EMAIL] Sending to userId: %s subject: %s%n",
                notification.getUserId(), notification.getSubject());

        // Validate email format if present in data
        String email = notification.getData().get("email");
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.BOUNCED,
                "Invalid email format: " + email
            );
        }

        simulateLatency(200, 800);
        boolean success = random.nextDouble() < SUCCESS_RATE;

        if (success) {
            String messageId = "ses-" + UUID.randomUUID().toString().substring(0, 8);
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.SENT,
                "MessageId: " + messageId
            );
        } else {
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.FAILED,
                "SES error: Throttling"
            );
        }
    }

    @Override
    public Channel supportedChannel() { return Channel.EMAIL; }

    @Override
    public boolean isAvailable() { return available; }

    private void simulateLatency(int minMs, int maxMs) { /* same as above */ }
}
```

---

### 6.3 SmsNotificationHandler

```java
public class SmsNotificationHandler implements NotificationHandler {

    private static final double SUCCESS_RATE = 0.85;
    private static final int RATE_LIMIT_PER_SECOND = 10;
    private final AtomicInteger sentThisSecond = new AtomicInteger(0);
    private volatile long currentSecond = System.currentTimeMillis() / 1000;
    private final Random random = new Random();
    private volatile boolean available = true;

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("[SMS] Sending to userId: %s subject: %s%n",
                notification.getUserId(), notification.getSubject());

        // Rate limiting check
        long now = System.currentTimeMillis() / 1000;
        if (now != currentSecond) {
            currentSecond = now;
            sentThisSecond.set(0);
        }
        if (sentThisSecond.incrementAndGet() > RATE_LIMIT_PER_SECOND) {
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.FAILED,
                "Rate limit exceeded: " + RATE_LIMIT_PER_SECOND + " SMS/sec"
            );
        }

        simulateLatency(300, 1000);
        boolean success = random.nextDouble() < SUCCESS_RATE;

        if (success) {
            String sid = "SM" + UUID.randomUUID().toString().substring(0, 10);
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.SENT,
                "Twilio SID: " + sid
            );
        } else {
            return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.FAILED,
                "Twilio error: Unreachable"
            );
        }
    }

    @Override
    public Channel supportedChannel() { return Channel.SMS; }

    @Override
    public boolean isAvailable() { return available; }

    private void simulateLatency(int minMs, int maxMs) { /* same */ }
}
```

---

### 6.4 InAppNotificationHandler

```java
public class InAppNotificationHandler implements NotificationHandler {

    // In-app store: userId -> list of notification IDs
    private final Map<String, List<String>> inAppStore = new ConcurrentHashMap<>();

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("[IN_APP] Sending to userId: %s subject: %s%n",
                notification.getUserId(), notification.getSubject());

        // Always succeeds -- no external dependency
        inAppStore.computeIfAbsent(notification.getUserId(), k -> new CopyOnWriteArrayList<>())
                  .add(notification.getId());

        return new DeliveryAttempt(
            notification.getId(),
            notification.getRetryCount() + 1,
            NotificationStatus.DELIVERED,
            "Stored in-app for user " + notification.getUserId()
        );
    }

    @Override
    public Channel supportedChannel() { return Channel.IN_APP; }

    @Override
    public boolean isAvailable() { return true; }  // always available

    public List<String> getNotificationsForUser(String userId) {
        return inAppStore.getOrDefault(userId, Collections.emptyList());
    }
}
```

---

## 7. Service Layer

### 7.1 NotificationService (The Core Orchestrator)

```java
public class NotificationService {

    private final PreferenceService preferenceService;
    private final TemplateService templateService;
    private final DeliveryTracker deliveryTracker;
    private final NotificationQueue notificationQueue;
    private final NotificationRepository notificationRepo;
    private final Map<Channel, NotificationHandler> handlerRegistry;

    public NotificationService(PreferenceService preferenceService,
                               TemplateService templateService,
                               DeliveryTracker deliveryTracker,
                               NotificationQueue notificationQueue,
                               NotificationRepository notificationRepo,
                               Map<Channel, NotificationHandler> handlerRegistry) {
        this.preferenceService = preferenceService;
        this.templateService = templateService;
        this.deliveryTracker = deliveryTracker;
        this.notificationQueue = notificationQueue;
        this.notificationRepo = notificationRepo;
        this.handlerRegistry = handlerRegistry;
    }

    // ---------------------------------------------------------------
    // send(): Main entry point for single notification
    // ---------------------------------------------------------------
    public String send(NotificationRequest request) {
        // 1. Validate request
        validateRequest(request);

        // 2. Check user preferences
        preferenceService.checkAllowed(request.getUserId(), request.getChannel());

        // 3. Load template and render
        String[] rendered = templateService.render(
            request.getTemplateId(), request.getData()
        );
        String subject = rendered[0];
        String body = rendered[1];

        // 4. Build notification entity
        int maxRetries = getMaxRetriesForChannel(request.getChannel());
        Notification notification = new Notification.Builder(
                request.getUserId(), request.getTemplateId(), request.getChannel())
            .priority(request.getPriority())
            .subject(subject)
            .body(body)
            .data(request.getData())
            .scheduledAt(request.getScheduledAt())
            .maxRetries(maxRetries)
            .build();

        // 5. Persist
        notificationRepo.save(notification);

        // 6. Enqueue
        notification.setStatus(NotificationStatus.QUEUED);
        notificationRepo.updateStatus(notification.getId(), NotificationStatus.QUEUED);
        notificationQueue.enqueue(notification);

        return notification.getId();
    }

    // ---------------------------------------------------------------
    // sendBatch(): Fan-out to multiple users
    // ---------------------------------------------------------------
    public List<String> sendBatch(NotificationRequest request) {
        if (!request.isBatch()) {
            return List.of(send(request));
        }

        List<String> notificationIds = new ArrayList<>();
        for (String userId : request.getUserIds()) {
            try {
                NotificationRequest single = new NotificationRequest(
                    userId, request.getTemplateId(),
                    request.getChannel(), request.getPriority(),
                    request.getData()
                );
                notificationIds.add(send(single));
            } catch (NotificationException e) {
                // Log and continue -- don't let one user's failure stop the batch
                System.err.printf("Batch: skipped userId=%s reason=%s%n",
                    userId, e.getMessage());
            }
        }
        return notificationIds;
    }

    // ---------------------------------------------------------------
    // processPendingNotifications(): Called by QueueConsumer
    // ---------------------------------------------------------------
    public void processPendingNotifications() {
        while (!notificationQueue.isEmpty()) {
            try {
                Notification notification = notificationQueue.dequeue();

                // Update status to SENDING
                notification.setStatus(NotificationStatus.SENDING);
                notificationRepo.updateStatus(notification.getId(),
                    NotificationStatus.SENDING);

                // Route to correct handler
                NotificationHandler handler = handlerRegistry.get(
                    notification.getChannel());

                if (handler == null || !handler.isAvailable()) {
                    // Re-enqueue for later
                    notification.setStatus(NotificationStatus.QUEUED);
                    notificationQueue.enqueue(notification);
                    continue;
                }

                // Send and track
                DeliveryAttempt attempt = handler.send(notification);
                deliveryTracker.record(attempt);

                if (attempt.isSuccess()) {
                    notification.setStatus(attempt.getStatus());
                    notification.setSentAt(System.currentTimeMillis());
                    notificationRepo.updateStatus(notification.getId(),
                        attempt.getStatus());
                } else {
                    retryFailed(notification);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ---------------------------------------------------------------
    // retryFailed(): Exponential backoff retry
    // ---------------------------------------------------------------
    public void retryFailed(Notification notification) {
        notification.setRetryCount(notification.getRetryCount() + 1);

        if (notification.getRetryCount() >= notification.getMaxRetries()) {
            // Max retries exhausted -- move to DLQ (conceptual)
            notification.setStatus(NotificationStatus.FAILED);
            notificationRepo.updateStatus(notification.getId(),
                NotificationStatus.FAILED);
            System.err.printf("DLQ: notification %s failed after %d retries%n",
                notification.getId(), notification.getRetryCount());
            return;
        }

        // Exponential backoff: delay = 1000ms * 2^retryCount
        long delay = 1000L * (1L << notification.getRetryCount());
        System.out.printf("Retry: notification %s attempt %d/%d in %dms%n",
            notification.getId(), notification.getRetryCount() + 1,
            notification.getMaxRetries(), delay);

        // In production: schedule with ScheduledExecutorService
        // For demo: re-enqueue immediately
        notification.setStatus(NotificationStatus.QUEUED);
        notificationRepo.updateStatus(notification.getId(),
            NotificationStatus.QUEUED);
        notificationQueue.enqueue(notification);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
    private void validateRequest(NotificationRequest request) {
        if (request.getUserId() == null && !request.isBatch()) {
            throw new NotificationException("userId is required");
        }
        if (request.getTemplateId() == null) {
            throw new NotificationException("templateId is required");
        }
        if (request.getChannel() == null) {
            throw new NotificationException("channel is required");
        }
    }

    private int getMaxRetriesForChannel(Channel channel) {
        switch (channel) {
            case PUSH:  return 5;
            case EMAIL: return 5;
            case SMS:   return 3;   // SMS is expensive, fewer retries
            case IN_APP: return 1;  // always succeeds, 1 attempt enough
            default:    return 3;
        }
    }
}
```

---

### 7.2 PreferenceService

```java
public class PreferenceService {

    private final PreferenceRepository prefRepo;
    private final NotificationRepository notifRepo;

    public PreferenceService(PreferenceRepository prefRepo,
                             NotificationRepository notifRepo) {
        this.prefRepo = prefRepo;
        this.notifRepo = notifRepo;
    }

    /**
     * Check if a notification is allowed for this user + channel.
     * Throws UserOptedOutException or RateLimitExceededException.
     */
    public void checkAllowed(String userId, Channel channel) {
        Optional<UserPreference> prefOpt = prefRepo.findByUserId(userId);
        if (prefOpt.isEmpty()) {
            return; // No preferences set -- allow all
        }

        UserPreference pref = prefOpt.get();

        // Check channel opt-out
        if (!pref.isChannelEnabled(channel)) {
            throw new UserOptedOutException(
                "User " + userId + " opted out of " + channel.getDisplayName());
        }

        // Check quiet hours (except CRITICAL -- those always go through)
        if (pref.isQuietHoursActive()) {
            throw new UserOptedOutException(
                "User " + userId + " is in quiet hours");
        }

        // Check daily frequency cap
        checkFrequencyCap(userId, channel, pref);
    }

    private void checkFrequencyCap(String userId, Channel channel,
                                   UserPreference pref) {
        Integer cap = pref.getDailyFrequencyCap().get(channel);
        if (cap == null) return; // no cap set

        // Count today's notifications for this user + channel
        long todayCount = notifRepo.findByUserId(userId).stream()
            .filter(n -> n.getChannel() == channel)
            .filter(n -> isToday(n.getCreatedAt()))
            .count();

        if (todayCount >= cap) {
            throw new RateLimitExceededException(
                "User " + userId + " exceeded daily cap of "
                + cap + " for " + channel.getDisplayName());
        }
    }

    private boolean isToday(long epochMillis) {
        LocalDate notifDate = Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault()).toLocalDate();
        return notifDate.equals(LocalDate.now());
    }
}
```

---

### 7.3 TemplateService

```java
public class TemplateService {

    private final TemplateRepository templateRepo;
    private final SimpleTemplateEngine engine;

    public TemplateService(TemplateRepository templateRepo,
                           SimpleTemplateEngine engine) {
        this.templateRepo = templateRepo;
        this.engine = engine;
    }

    /**
     * Load template by ID and render subject + body with provided data.
     * Returns [subject, body].
     */
    public String[] render(String templateId, Map<String, String> data) {
        NotificationTemplate template = templateRepo.findById(templateId)
            .orElseThrow(() -> new TemplateNotFoundException(
                "Template not found: " + templateId));

        // Validate all required variables are present
        engine.validateVariables(template.getRequiredVariables(), data);

        String subject = engine.render(template.getSubjectTemplate(), data);
        String body = engine.render(template.getBodyTemplate(), data);

        return new String[]{subject, body};
    }

    public NotificationTemplate loadTemplate(String templateId) {
        return templateRepo.findById(templateId)
            .orElseThrow(() -> new TemplateNotFoundException(
                "Template not found: " + templateId));
    }
}
```

---

### 7.4 DeliveryTracker

```java
public class DeliveryTracker {

    private final NotificationRepository notifRepo;
    private final Map<String, List<DeliveryAttempt>> attemptLog =
        new ConcurrentHashMap<>();

    // Stats
    private final AtomicInteger totalSent = new AtomicInteger(0);
    private final AtomicInteger totalFailed = new AtomicInteger(0);
    private final AtomicInteger totalDelivered = new AtomicInteger(0);

    public DeliveryTracker(NotificationRepository notifRepo) {
        this.notifRepo = notifRepo;
    }

    public void record(DeliveryAttempt attempt) {
        attemptLog.computeIfAbsent(attempt.getNotificationId(),
            k -> new CopyOnWriteArrayList<>()).add(attempt);

        // Update stats
        if (attempt.isSuccess()) {
            totalSent.incrementAndGet();
            if (attempt.getStatus() == NotificationStatus.DELIVERED) {
                totalDelivered.incrementAndGet();
            }
        } else {
            totalFailed.incrementAndGet();
        }
    }

    public void updateStatus(String notificationId, NotificationStatus status) {
        notifRepo.updateStatus(notificationId, status);
    }

    public List<DeliveryAttempt> getAttempts(String notificationId) {
        return attemptLog.getOrDefault(notificationId, Collections.emptyList());
    }

    public String getStats() {
        return String.format("Sent: %d | Delivered: %d | Failed: %d",
            totalSent.get(), totalDelivered.get(), totalFailed.get());
    }
}
```

---

## 8. Template Engine

### SimpleTemplateEngine

```java
public class SimpleTemplateEngine {

    private static final Pattern VARIABLE_PATTERN =
        Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * Replace all {{variable}} placeholders with values from data map.
     *
     * Input:  "Hello {{name}}, your order {{orderId}} is {{status}}"
     * Data:   {name=John, orderId=ORD-123, status=shipped}
     * Output: "Hello John, your order ORD-123 is shipped"
     */
    public String render(String template, Map<String, String> data) {
        if (template == null) return "";

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String variable = matcher.group(1);
            String value = data.getOrDefault(variable, "{{" + variable + "}}");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Validate that all required variables are present in the data map.
     * Throws NotificationException if any are missing.
     */
    public void validateVariables(List<String> requiredVariables,
                                  Map<String, String> data) {
        if (requiredVariables == null) return;

        List<String> missing = requiredVariables.stream()
            .filter(v -> !data.containsKey(v))
            .collect(Collectors.toList());

        if (!missing.isEmpty()) {
            throw new NotificationException(
                "Missing required template variables: " + missing);
        }
    }
}
```

**Interview note**: `Matcher.quoteReplacement()` is critical here -- it escapes `$` and `\` in replacement strings to avoid regex issues. This is a common interview gotcha.

---

## 9. Priority Queue

### InMemoryPriorityQueue

```java
public class InMemoryPriorityQueue implements NotificationQueue {

    private final PriorityBlockingQueue<Notification> queue;

    public InMemoryPriorityQueue() {
        // Notification implements Comparable, ordering by Priority.value
        this.queue = new PriorityBlockingQueue<>(100);
    }

    @Override
    public void enqueue(Notification notification) {
        queue.offer(notification);
    }

    @Override
    public Notification dequeue() throws InterruptedException {
        return queue.take();  // blocks if empty
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
```

### QueueConsumer

```java
public class QueueConsumer implements Runnable {

    private final NotificationQueue queue;
    private final NotificationService service;
    private volatile boolean running = true;

    public QueueConsumer(NotificationQueue queue, NotificationService service) {
        this.queue = queue;
        this.service = service;
    }

    @Override
    public void run() {
        while (running) {
            service.processPendingNotifications();
            try {
                Thread.sleep(100); // poll interval
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void start() {
        new Thread(this, "queue-consumer").start();
    }

    public void stop() {
        running = false;
    }
}
```

**Production mapping**:

| In-Memory                 | Production                                      |
|---------------------------|-------------------------------------------------|
| PriorityBlockingQueue     | Separate Kafka topics per priority              |
| Single QueueConsumer      | Consumer group with more consumers for CRITICAL |
| Thread.sleep polling      | Kafka consumer poll loop                        |
| In-memory ordering        | Topic-level partitioning + consumer concurrency |

---

## 10. Concurrency Considerations

| Concern              | Solution                              | Why                                           |
|----------------------|---------------------------------------|-----------------------------------------------|
| Repository storage   | `ConcurrentHashMap`                   | Thread-safe reads/writes without full locking  |
| Queue ordering       | `PriorityBlockingQueue`               | Thread-safe, blocks on empty (no busy-wait)    |
| Worker pool          | `ExecutorService` (fixed thread pool) | Simulates multiple workers processing queue    |
| Delivery stats       | `AtomicInteger`                       | Lock-free increment for counters               |
| Handler availability | `volatile boolean`                    | Visibility across threads without synchronization |
| Rate limit counter   | `AtomicInteger`                       | Thread-safe SMS rate limiting                  |
| In-app store         | `CopyOnWriteArrayList`               | Read-heavy, write-rare per-user list           |

### ExecutorService Usage (in AppConfig)

```java
public class AppConfig {

    public static final int WORKER_POOL_SIZE = 4;
    public static final int BASE_RETRY_DELAY_MS = 1000;
    public static final int MAX_RETRIES_PUSH = 5;
    public static final int MAX_RETRIES_EMAIL = 5;
    public static final int MAX_RETRIES_SMS = 3;
    public static final int MAX_RETRIES_INAPP = 1;

    private final ExecutorService workerPool;
    private final ScheduledExecutorService scheduler;

    public AppConfig() {
        this.workerPool = Executors.newFixedThreadPool(WORKER_POOL_SIZE,
            r -> {
                Thread t = new Thread(r);
                t.setName("notif-worker-" + t.getId());
                t.setDaemon(true);
                return t;
            });
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public Map<Channel, NotificationHandler> buildHandlerRegistry() {
        Map<Channel, NotificationHandler> registry = new EnumMap<>(Channel.class);
        registry.put(Channel.PUSH, new PushNotificationHandler());
        registry.put(Channel.EMAIL, new EmailNotificationHandler());
        registry.put(Channel.SMS, new SmsNotificationHandler());
        registry.put(Channel.IN_APP, new InAppNotificationHandler());
        return Collections.unmodifiableMap(registry);
    }

    public void shutdown() {
        workerPool.shutdown();
        scheduler.shutdown();
    }

    // Getters
}
```

---

## 11. Retry Logic

### Exponential Backoff Formula

```
delay = BASE_DELAY_MS * 2^(retryCount)

Retry 1:  1000 * 2^1 =  2,000ms  (2 seconds)
Retry 2:  1000 * 2^2 =  4,000ms  (4 seconds)
Retry 3:  1000 * 2^3 =  8,000ms  (8 seconds)
Retry 4:  1000 * 2^4 = 16,000ms  (16 seconds)
Retry 5:  1000 * 2^5 = 32,000ms  (32 seconds)
```

### Channel-Specific Retry Strategies

| Channel  | Max Retries | Retry On                        | No Retry On             |
|----------|-------------|---------------------------------|-------------------------|
| PUSH     | 5           | Timeout, ServerError            | DeviceNotRegistered (BOUNCED) |
| EMAIL    | 5           | Throttling, Timeout             | InvalidAddress (BOUNCED)      |
| SMS      | 3           | Unreachable, Timeout            | InvalidNumber (BOUNCED)       |
| IN_APP   | 1           | N/A (always succeeds)           | N/A                           |

### Retry Flow

```
send() fails
    |
    v
retryCount < maxRetries?
    |           |
   YES          NO
    |           |
    v           v
Calculate      Mark FAILED
backoff delay  Move to DLQ
    |
    v
Re-enqueue with QUEUED status
    |
    v
Worker picks up again
    |
    v
send() -- success or retry again
```

### Dead Letter Queue (DLQ)

In production, notifications that exhaust all retries are moved to a DLQ (e.g., a separate Kafka topic or database table). The DLQ enables:
- Manual investigation of persistent failures.
- Bulk replay after a provider outage is resolved.
- Alerting when DLQ depth exceeds a threshold.

```java
// Conceptual DLQ handling in retryFailed()
if (notification.getRetryCount() >= notification.getMaxRetries()) {
    notification.setStatus(NotificationStatus.FAILED);
    notificationRepo.updateStatus(notification.getId(), NotificationStatus.FAILED);
    // In production: deadLetterQueue.enqueue(notification);
    // In production: alertingService.alert("Notification exhausted retries", notification);
    System.err.printf("DLQ: notification %s failed after %d retries%n",
        notification.getId(), notification.getRetryCount());
}
```

---

## 12. Validation and Error Handling

### Custom Exception Hierarchy

```
NotificationException (base, unchecked)
    |
    +-- TemplateNotFoundException
    |       "Template not found: ORDER_CONFIRM_V2"
    |
    +-- UserOptedOutException
    |       "User user-123 opted out of SMS"
    |       "User user-123 is in quiet hours"
    |
    +-- RateLimitExceededException
            "User user-123 exceeded daily cap of 5 for Email"
            "Rate limit exceeded: 10 SMS/sec"
```

```java
public class NotificationException extends RuntimeException {
    public NotificationException(String message) { super(message); }
    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class TemplateNotFoundException extends NotificationException {
    public TemplateNotFoundException(String message) { super(message); }
}

public class UserOptedOutException extends NotificationException {
    public UserOptedOutException(String message) { super(message); }
}

public class RateLimitExceededException extends NotificationException {
    public RateLimitExceededException(String message) { super(message); }
}
```

### Validation Checklist

| Check                            | Where                     | Exception                      |
|----------------------------------|---------------------------|--------------------------------|
| Template exists                  | TemplateService.render()  | TemplateNotFoundException      |
| Required template vars present   | SimpleTemplateEngine      | NotificationException          |
| Channel is valid (not null)      | NotificationService.send()| NotificationException          |
| User hasn't opted out of channel | PreferenceService         | UserOptedOutException          |
| Not in quiet hours               | PreferenceService         | UserOptedOutException          |
| Daily frequency cap not exceeded | PreferenceService         | RateLimitExceededException     |
| Email format valid               | EmailNotificationHandler  | Returns BOUNCED attempt        |
| SMS rate limit                   | SmsNotificationHandler    | Returns FAILED attempt         |
| Handler available                | NotificationService       | Re-enqueue for later           |

---

## 13. Sample Workflows

### 13.1 Single Notification -- Happy Path

```
Client              Controller          NotifService         PrefService
  |                     |                    |                    |
  |--- sendNotif(req)-->|                    |                    |
  |                     |--- send(req) ----->|                    |
  |                     |                    |-- checkAllowed --->|
  |                     |                    |<-- OK ------------|
  |                     |                    |
  |                     |                TemplateService    SimpleTemplateEngine
  |                     |                    |                    |
  |                     |                    |-- render() ------->|
  |                     |                    |<-- [subj, body] --|
  |                     |                    |
  |                     |                    |--- build Notification (Builder)
  |                     |                    |--- save to repo
  |                     |                    |--- enqueue(notification)
  |                     |<-- notifId --------|
  |<-- 200 + notifId ---|
  |
  |                    (asynchronous)
  |
  |              QueueConsumer          NotifService         EmailHandler
  |                  |                      |                    |
  |                  |-- processPending --->|                    |
  |                  |                      |-- dequeue -------->|
  |                  |                      |-- handler.send --->|
  |                  |                      |                    |-- [EMAIL] Sending...
  |                  |                      |<-- DeliveryAttempt |  (SENT)
  |                  |                      |
  |                  |                  DeliveryTracker
  |                  |                      |
  |                  |                      |-- record(attempt)
  |                  |                      |-- updateStatus(SENT)
```

### 13.2 Failed Delivery with Retry

```
QueueConsumer           NotifService           PushHandler         DeliveryTracker
    |                       |                      |                    |
    |-- processPending ---->|                      |                    |
    |                       |-- dequeue            |                    |
    |                       |-- handler.send ----->|                    |
    |                       |                      |-- [PUSH] Sending.. |
    |                       |                      |   (random fail)    |
    |                       |<-- DeliveryAttempt --|  (FAILED)          |
    |                       |                                           |
    |                       |-- record(attempt) ----------------------->|
    |                       |                                           |
    |                       |-- retryFailed()                           |
    |                       |   retryCount: 0 -> 1                     |
    |                       |   1 < maxRetries(5)? YES                 |
    |                       |   backoff = 1000 * 2^1 = 2000ms          |
    |                       |   re-enqueue(notification)               |
    |                       |                                          |
    |  ... 2 seconds later ...                                         |
    |                       |                                          |
    |-- processPending ---->|                                          |
    |                       |-- dequeue                                |
    |                       |-- handler.send ----->|                   |
    |                       |                      |-- [PUSH] Sending..|
    |                       |<-- DeliveryAttempt --|  (SENT)           |
    |                       |-- record(attempt) ---------------------->|
    |                       |-- updateStatus(SENT)                     |
```

### 13.3 User Opted Out of Channel

```
Client              Controller          NotifService         PrefService
  |                     |                    |                    |
  |--- sendNotif(req)-->|                    |                    |
  |   (channel=SMS)     |--- send(req) ----->|                    |
  |                     |                    |-- checkAllowed --->|
  |                     |                    |                    |
  |                     |                    |   prefRepo.findByUserId()
  |                     |                    |   channelEnabled[SMS] = false
  |                     |                    |                    |
  |                     |                    |<-- THROW ----------|
  |                     |                    | UserOptedOutException:
  |                     |                    | "User user-42 opted out of SMS"
  |                     |                    |
  |                     |<-- exception ------|
  |<-- 400 + error msg -|
```

### 13.4 Batch Notification Fan-Out

```
Client              Controller          NotifService
  |                     |                    |
  |--- sendBatch(req)-->|                    |
  |  userIds=[A,B,C]    |--- sendBatch() --->|
  |                     |                    |
  |                     |                    |-- send(userId=A) --> OK, id-1
  |                     |                    |-- send(userId=B) --> UserOptedOut (skip)
  |                     |                    |-- send(userId=C) --> OK, id-3
  |                     |                    |
  |                     |<-- [id-1, id-3] ---|
  |<-- 200 + ids -------|
  |
  |                    Queue now has 2 notifications:
  |                    id-1 (user A) and id-3 (user C)
  |                    user B was skipped -- logged, not failed
```

### 13.5 Quiet Hours Enforcement

```
Client              Controller          NotifService         PrefService
  |                     |                    |                    |
  |--- sendNotif(req)-->|                    |                    |
  | (priority=MEDIUM,   |--- send(req) ----->|                    |
  |  channel=PUSH)      |                    |-- checkAllowed --->|
  |                     |                    |                    |
  |                     |                    |   quietHoursStart = 22
  |                     |                    |   quietHoursEnd = 8
  |                     |                    |   currentHour = 23  (11 PM)
  |                     |                    |   isQuietHoursActive() = true
  |                     |                    |                    |
  |                     |                    |<-- THROW ----------|
  |                     |                    | UserOptedOutException:
  |                     |                    | "User user-42 is in quiet hours"
  |                     |                    |
  |<-- 400 + error msg -|
  |
  |  NOTE: CRITICAL priority notifications bypass quiet hours
  |  in production (add priority param to checkAllowed).
```

---

## 14. Design Patterns

| # | Pattern             | Where                                        | Why                                                           | Interview One-Liner |
|---|---------------------|----------------------------------------------|---------------------------------------------------------------|---------------------|
| 1 | **Strategy**        | `NotificationHandler` + channel impls        | Swap delivery logic per channel without touching the service  | "Define a family of algorithms, encapsulate each one, make them interchangeable." |
| 2 | **Observer**        | `DeliveryTracker` observing send results     | Decouple delivery tracking from the send flow; stats updated reactively | "When one object changes state, all dependents are notified automatically." |
| 3 | **Builder**         | `Notification.Builder`                       | 12+ fields, optional vs required params, immutable construction | "Separate construction of a complex object from its representation." |
| 4 | **Template Method** | Common send flow in `NotificationService.processPendingNotifications()` | All channels follow: dequeue -> status update -> send -> track. Channel-specific logic is in the handler. | "Define skeleton of algorithm in base, defer steps to subclasses (here, handler)." |
| 5 | **Factory**         | `AppConfig.buildHandlerRegistry()`           | Centralize handler creation and wiring; callers depend on interface not impl | "Let a factory method decide which class to instantiate." |
| 6 | **Repository**      | All `*Repository` interfaces + in-memory impls | Abstract data access; swap from ConcurrentHashMap to JPA/Redis without changing services | "Mediate between domain and data mapping layers." |
| 7 | **Producer-Consumer** | `NotificationQueue` + `QueueConsumer`       | Decouple notification creation (fast, synchronous) from delivery (slow, async) | "Producers enqueue work; consumers drain and process independently." |

### Strategy Pattern Deep Dive

```
                  +-----------------------------+
                  |   NotificationHandler       |
                  |   (Strategy Interface)      |
                  +-----------------------------+
                  | + send(Notification)         |
                  | + supportedChannel()         |
                  | + isAvailable()              |
                  +-----------------------------+
                       ^    ^    ^    ^
                       |    |    |    |
             +---------+    |    |    +----------+
             |              |    |               |
      +------+----+  +-----+---+  +------+---+  +-------+---+
      | Push      |  | Email   |  | Sms      |  | InApp     |
      | Handler   |  | Handler |  | Handler  |  | Handler   |
      +-----------+  +---------+  +----------+  +-----------+
      | FCM/APNs  |  | SES     |  | Twilio   |  | In-memory |
      | 90% rate  |  | 95% rate|  | 85% rate |  | 100% rate |
      +-----------+  +---------+  +----------+  +-----------+

NotificationService holds:
    Map<Channel, NotificationHandler> handlerRegistry

Routing:
    NotificationHandler handler = handlerRegistry.get(notification.getChannel());
    DeliveryAttempt result = handler.send(notification);  // polymorphic dispatch
```

### Builder Pattern Deep Dive

```java
// Without Builder -- telescoping constructor nightmare:
new Notification(id, userId, templateId, channel, priority, status,
    subject, body, data, scheduledAt, sentAt, deliveredAt,
    retryCount, maxRetries, createdAt);  // 15 params -- unreadable

// With Builder -- clear, self-documenting:
Notification notification = new Notification.Builder("user-1", "tpl-1", Channel.EMAIL)
    .priority(Priority.HIGH)
    .subject("Order Confirmed")
    .body("Your order ORD-123 has been confirmed.")
    .data(Map.of("orderId", "ORD-123"))
    .maxRetries(5)
    .build();
```

### Producer-Consumer Deep Dive

```
Producer Side (synchronous, fast):
    NotificationService.send()
        --> validates, renders template, builds entity
        --> enqueue(notification)  // returns immediately
        --> return notificationId to caller

Consumer Side (asynchronous, slow):
    QueueConsumer.run()  (separate thread)
        --> dequeue()  // blocks until notification available
        --> processPendingNotifications()
        --> handler.send()  // slow network call
        --> track delivery result

Why?
    - Caller gets instant response (notification ID)
    - Slow provider calls don't block the API
    - Priority ordering ensures CRITICAL goes first
    - Can scale consumers independently of producers
```

---

## 15. Extensibility

### 15.1 Adding a New Channel (e.g., Slack)

**Steps** (no existing code changes needed):

1. Add `SLACK("Slack")` to `Channel` enum.
2. Create `SlackNotificationHandler implements NotificationHandler`.
3. Register in `AppConfig.buildHandlerRegistry()`:
   ```java
   registry.put(Channel.SLACK, new SlackNotificationHandler());
   ```
4. Done. The `NotificationService` routes automatically via `handlerRegistry.get(channel)`.

**This is the power of the Strategy pattern** -- open for extension, closed for modification.

---

### 15.2 Adding a New Template Engine

Replace `SimpleTemplateEngine` with a richer engine (e.g., Thymeleaf, Mustache):

```java
public interface TemplateEngine {
    String render(String template, Map<String, String> data);
    void validateVariables(List<String> required, Map<String, String> data);
}

// SimpleTemplateEngine implements TemplateEngine  (existing)
// ThymeleafTemplateEngine implements TemplateEngine  (new)
```

Wire the desired implementation in `AppConfig`. `TemplateService` depends on the interface, not the impl.

---

### 15.3 Persistent Queue (Kafka)

Replace `InMemoryPriorityQueue` with `KafkaNotificationQueue implements NotificationQueue`:

```java
public class KafkaNotificationQueue implements NotificationQueue {
    // Separate Kafka topics per priority:
    //   notifications.critical  (8 partitions, 8 consumers)
    //   notifications.high      (4 partitions, 4 consumers)
    //   notifications.medium    (2 partitions, 2 consumers)
    //   notifications.low       (1 partition,  1 consumer)

    @Override
    public void enqueue(Notification notification) {
        String topic = "notifications." + notification.getPriority().name().toLowerCase();
        kafkaProducer.send(new ProducerRecord<>(topic, notification.getId(),
            serialize(notification)));
    }

    @Override
    public Notification dequeue() {
        // Kafka consumer poll -- priority handled by consumer count per topic
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
        // ...
    }
}
```

---

### 15.4 Webhook Delivery Tracking

Add a `WebhookDeliveryListener` that fires when delivery status changes:

```java
public interface DeliveryListener {
    void onStatusChange(String notificationId, NotificationStatus oldStatus,
                        NotificationStatus newStatus);
}

public class WebhookDeliveryListener implements DeliveryListener {
    @Override
    public void onStatusChange(String notificationId,
                               NotificationStatus oldStatus,
                               NotificationStatus newStatus) {
        // POST to customer's webhook URL with status update
        httpClient.post(webhookUrl, Map.of(
            "notificationId", notificationId,
            "oldStatus", oldStatus.name(),
            "newStatus", newStatus.name(),
            "timestamp", Instant.now().toString()
        ));
    }
}
```

Register listeners in `DeliveryTracker` -- classic Observer pattern.

---

### 15.5 A/B Testing for Templates

```java
public class ABTestTemplateService extends TemplateService {

    private final Map<String, List<String>> abTests;  // testName -> [templateA, templateB]

    public String[] render(String templateId, Map<String, String> data) {
        // Check if this template is part of an A/B test
        String resolvedId = resolveABVariant(templateId, data.get("userId"));
        return super.render(resolvedId, data);
    }

    private String resolveABVariant(String templateId, String userId) {
        List<String> variants = abTests.get(templateId);
        if (variants == null) return templateId;

        // Deterministic bucketing by userId hash
        int bucket = Math.abs(userId.hashCode()) % variants.size();
        return variants.get(bucket);
    }
}
```

---

## Quick Reference: Interview Talking Points

| Topic                  | Key Point                                                                                  |
|------------------------|--------------------------------------------------------------------------------------------|
| Why Strategy?          | New channels (Slack, WhatsApp) without modifying service code. Open/Closed Principle.       |
| Why Builder?           | Notification has 12+ fields, mix of required/optional. Prevents telescoping constructors.   |
| Why Priority Queue?    | CRITICAL notifications (password reset) must not wait behind LOW (marketing emails).        |
| Why async queue?       | Decouple fast API response from slow provider calls. Scale consumers independently.         |
| Retry strategy?        | Exponential backoff (1s, 2s, 4s, 8s...). Channel-specific max retries. DLQ for exhausted.  |
| Quiet hours?           | User preference with start/end hour. Wraps around midnight. CRITICAL bypasses.              |
| Rate limiting?         | Per-user daily cap (preference) + per-channel provider limit (SMS handler).                 |
| Idempotency?           | Notification ID generated before enqueue. Dedup on ID at handler level.                     |
| Scaling?               | Kafka topics per priority, more consumers for CRITICAL. Horizontal scaling of workers.      |
| Monitoring?            | DeliveryTracker with AtomicInteger counters. In prod: Prometheus metrics + Grafana.         |

---
