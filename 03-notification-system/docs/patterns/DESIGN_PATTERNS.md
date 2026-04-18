# Design Patterns in the Notification System

> Senior Java interview prep -- every pattern used, why it was chosen, and how to talk about it.

---

## Table of Contents

| # | Pattern | Primary Purpose |
|---|---------|----------------|
| 1 | [Strategy](#1-strategy-pattern) | Channel-agnostic dispatch |
| 2 | [Observer](#2-observer-pattern) | Delivery status tracking |
| 3 | [Builder](#3-builder-pattern) | Complex notification construction |
| 4 | [Template Method](#4-template-method-pattern) | Common processing pipeline |
| 5 | [Factory](#5-factory-pattern) | Handler registration and lookup |
| 6 | [Producer-Consumer](#6-producer-consumer-pattern) | Async queue processing |
| 7 | [Repository](#7-repository-pattern) | Data access abstraction |

---

## 1. Strategy Pattern

**THE core pattern of this system.** One interface, four completely different delivery mechanisms.

### ASCII Diagram

```
                    +---------------------------+
                    | NotificationHandler       |
                    |  (Strategy Interface)     |
                    +---------------------------+
                    | + send(Notification): void|
                    | + supports(Channel): bool |
                    +-------------+-------------+
                                  |
          +-----------+-----------+-----------+-----------+
          |           |           |           |           |
  +-------v---+ +----v------+ +-v--------+ +v----------+
  | PushHandler| |EmailHandler| |SMSHandler| |InAppHandler|
  +-----------+ +-----------+ +----------+ +-----------+
  | FCM / APNs| | SES /      | | Twilio / | | WebSocket |
  | token mgmt| | SendGrid   | | SNS      | | + DB store|
  +-----------+ +-----------+ +----------+ +-----------+
```

### Code Snippet

```java
public interface NotificationHandler {
    void send(Notification notification);
    boolean supports(Channel channel);
}

// --- One of four implementations ---
public class PushNotificationHandler implements NotificationHandler {

    @Override
    public void send(Notification notification) {
        String token = deviceTokenCache.get(notification.getUserId());
        fcmClient.send(PushPayload.from(notification, token));
    }

    @Override
    public boolean supports(Channel channel) {
        return channel == Channel.PUSH;
    }
}
```

### Why This Pattern

| Concern | Answer |
|---------|--------|
| **Open/Closed** | Adding Slack or WhatsApp = one new class. Zero changes to existing handlers or the service layer. |
| **Single Responsibility** | Each handler owns exactly one delivery mechanism. |
| **Testability** | Mock `NotificationHandler` in service tests; test each handler in isolation with a fake external client. |

### Interview Talking Point

> "We use the Strategy pattern so the NotificationService delegates to the correct handler at
> runtime based on the channel. Adding a new channel -- say WhatsApp -- is a single new class
> that implements `NotificationHandler`. Nothing else changes. This is a textbook Open/Closed
> Principle application."

### Tradeoffs

- (+) Perfect extensibility for new channels.
- (+) Each handler can evolve independently (different retry logic, different SDKs).
- (-) More classes to manage. For a system with only two channels, this is over-engineering.
- (-) Cross-cutting concerns (retry, circuit breaker) must be applied per handler or via decorator.

---

## 2. Observer Pattern

Delivery tracking: handlers notify the `DeliveryTracker` whenever a notification changes state.

### ASCII Diagram

```
  +---------------------+         notify(event)        +------------------+
  | NotificationService |  ---------------------------> | DeliveryTracker  |
  | (Subject)           |                               | (Observer)       |
  +---------------------+                               +------------------+
          |                                                     |
          |  send() completes                                   |  update status
          |  or fails                                           |  log metrics
          v                                                     v
  SENT / DELIVERED / FAILED                            NotificationRepository
                                                       MetricsCollector

  --- In Production (Kafka-based) ---

  Handler --> Kafka "delivery-events" topic --> DeliveryTracker consumer
              (SENT, DELIVERED, FAILED,         (updates DB, fires
               BOUNCED, CLICKED)                 webhooks, alerts)
```

### Code Snippet

```java
public class DeliveryTracker {

    public void onStatusChange(String notificationId, DeliveryStatus status) {
        notificationRepository.updateStatus(notificationId, status);
        metricsCollector.recordDelivery(status);

        if (status == DeliveryStatus.FAILED) {
            retryScheduler.scheduleRetry(notificationId);
        }
    }
}

// Inside a handler after send:
deliveryTracker.onStatusChange(notification.getId(), DeliveryStatus.SENT);
```

### Interview Talking Point

> "The Observer pattern decouples delivery execution from status tracking. In our in-memory
> version, the handler calls the tracker directly. In a production system, the handler publishes
> a Kafka event (SENT, DELIVERED, FAILED), and the DeliveryTracker is a separate consumer group.
> This means delivery and tracking scale independently."

### Tradeoffs

- (+) Loose coupling between send path and tracking/alerting path.
- (+) Easy to add more observers (analytics, billing, audit log) without touching send logic.
- (-) In the synchronous version, a slow observer blocks the send path.
- (-) In the async (Kafka) version, status updates are eventually consistent.

---

## 3. Builder Pattern

The `Notification` entity has 15+ fields. Many are optional (`scheduledAt`, `deliveredAt`, `sentAt`, `metadata`). Builder makes construction readable and the result immutable.

### ASCII Diagram

```
  Notification.builder()
      .id(uuid)                    // required
      .userId("u-123")            // required
      .channel(Channel.EMAIL)     // required
      .title("Order Shipped")     // required
      .body("Your order...")      // required
      .priority(Priority.HIGH)    // optional
      .templateId("tmpl-ship")   // optional
      .scheduledAt(future)        // optional -- null if immediate
      .metadata(Map.of(...))      // optional
      .build();                   // --> immutable Notification

  After build():
  - All fields are final
  - No setters exposed
  - Thread-safe by construction
```

### Code Snippet

```java
public class Notification {
    private final String id;
    private final String userId;
    private final Channel channel;
    private final String title;
    private final String body;
    private final Priority priority;
    private final Instant scheduledAt;   // optional
    private final Instant sentAt;        // optional
    private final Instant deliveredAt;   // optional
    // ... more fields

    private Notification(Builder builder) {
        this.id = builder.id;
        this.userId = builder.userId;
        // ... assign all
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String userId;
        private Instant scheduledAt; // null = send immediately
        // ...

        public Builder id(String id) { this.id = id; return this; }
        public Builder scheduledAt(Instant at) { this.scheduledAt = at; return this; }
        // ...

        public Notification build() {
            Objects.requireNonNull(id, "id required");
            Objects.requireNonNull(userId, "userId required");
            return new Notification(this);
        }
    }
}
```

### Interview Talking Point

> "Notification has 15+ fields, many of which are optional -- scheduledAt, deliveredAt, metadata.
> The Builder pattern gives us readable construction and guarantees immutability after build().
> This is especially important in a concurrent system where notifications pass through queues
> and multiple threads."

### Tradeoffs

- (+) Readable, self-documenting construction.
- (+) Immutability = thread safety without synchronization.
- (+) Can enforce required vs optional fields at build time.
- (-) More boilerplate (Lombok `@Builder` eliminates this in production).
- (-) If you need to modify a notification, you must create a new instance (copy builder).

---

## 4. Template Method Pattern

Every channel follows the same high-level processing pipeline. The channel-specific part is only the actual `send()` step.

### ASCII Diagram

```
  Common Pipeline (Template Method)
  ==================================

  validate()          <-- shared: null checks, schema validation
       |
  checkPreferences()  <-- shared: user opted out? frequency cap hit?
       |
  renderTemplate()    <-- shared: resolve {{variables}} from template
       |
  enqueue()           <-- shared: put on channel-specific queue
       |
  send()              <-- ABSTRACT: each channel implements this differently
       |
  trackDelivery()     <-- shared: update status, fire metrics

  +-----------------+     +-----------------+     +-----------------+
  | PushHandler     |     | EmailHandler    |     | SMSHandler      |
  | send():         |     | send():         |     | send():         |
  |  resolve token  |     |  build MIME     |     |  format E.164   |
  |  call FCM API   |     |  call SES API   |     |  call Twilio    |
  +-----------------+     +-----------------+     +-----------------+
```

### Code Snippet

```java
public abstract class AbstractNotificationHandler implements NotificationHandler {

    // Template method -- defines the skeleton
    public final void process(Notification notification) {
        validate(notification);
        if (!checkPreferences(notification.getUserId(), getChannel())) {
            return; // user opted out
        }
        String renderedBody = renderTemplate(notification);
        enqueue(notification, renderedBody);
    }

    // Concrete steps (shared)
    private void validate(Notification n) { /* null checks, etc. */ }
    private boolean checkPreferences(String userId, Channel ch) { /* check repo */ }
    private String renderTemplate(Notification n) { /* Mustache render */ }

    // Abstract step (channel-specific)
    protected abstract void send(Notification notification, String renderedBody);
    protected abstract Channel getChannel();
}
```

### Interview Talking Point

> "The Template Method pattern defines the invariant processing pipeline -- validate, check
> preferences, render, enqueue, send, track. Each channel only overrides the `send()` step.
> This prevents bugs where, say, the SMS handler forgets to check user preferences."

### Tradeoffs

- (+) Enforces a consistent pipeline. No channel can skip preference checking.
- (+) Common logic lives in one place (DRY).
- (-) Inheritance-based -- less flexible than composition. Hard to change the pipeline order for one channel.
- (-) If one channel needs a genuinely different flow (e.g., in-app skips enqueue), you need escape hatches.

---

## 5. Factory Pattern

`AppConfig` acts as a factory registry, creating all handlers and wiring them into a `Map<Channel, NotificationHandler>`.

### ASCII Diagram

```
  AppConfig (Factory)
  ====================
        |
        |  creates and registers
        v
  Map<Channel, NotificationHandler>
  +-----------------------------------+
  | PUSH   -> PushNotificationHandler |
  | EMAIL  -> EmailNotificationHandler|
  | SMS    -> SMSNotificationHandler  |
  | IN_APP -> InAppNotificationHandler|
  +-----------------------------------+
        |
        |  injected into
        v
  NotificationService.send(notification)
      Channel ch = notification.getChannel();
      handlers.get(ch).send(notification);   // O(1) lookup
```

### Code Snippet

```java
public class AppConfig {

    public Map<Channel, NotificationHandler> createHandlerRegistry() {
        Map<Channel, NotificationHandler> handlers = new EnumMap<>(Channel.class);
        handlers.put(Channel.PUSH,   new PushNotificationHandler(fcmClient, tokenCache));
        handlers.put(Channel.EMAIL,  new EmailNotificationHandler(sesClient, templateEngine));
        handlers.put(Channel.SMS,    new SMSNotificationHandler(twilioClient));
        handlers.put(Channel.IN_APP, new InAppNotificationHandler(wsManager, repository));
        return Collections.unmodifiableMap(handlers);
    }
}

// In NotificationService:
public void send(Notification notification) {
    NotificationHandler handler = handlerRegistry.get(notification.getChannel());
    if (handler == null) throw new UnsupportedChannelException(notification.getChannel());
    handler.send(notification);
}
```

### Interview Talking Point

> "We use a factory registry -- a Map of Channel to Handler -- so dispatching is O(1) and
> adding a new channel means registering one more entry. In Spring, this would be auto-wired
> via `@Qualifier` or a `List<NotificationHandler>` that self-registers. The factory keeps
> the service layer completely unaware of concrete handler types."

### Tradeoffs

- (+) O(1) dispatch. Clean separation of wiring from business logic.
- (+) Easy to swap implementations (e.g., mock handler in tests).
- (-) Static registry -- if handlers need runtime creation (e.g., per-tenant config), need a more dynamic factory.
- (-) EnumMap ties you to a fixed set of channels at compile time (fine for most systems).

---

## 6. Producer-Consumer Pattern

Decouples the "accept notification request" path from the "actually deliver it" path.

### ASCII Diagram

```
  PRODUCER SIDE                          CONSUMER SIDE
  ==============                         ==============

  API Request                            Worker Pool
       |                                      |
       v                                      v
  NotificationService                    processQueue()
       |                                      |
       | enqueue(notification)                | poll()
       v                                      v
  +------------------------------------------+
  |        BlockingQueue<Notification>        |
  |  (in-memory for demo; Kafka in prod)     |
  +------------------------------------------+

  --- Production Architecture ---

  API --> Kafka Topic: "notifications.high"    --> Consumer Group: push-workers (3 instances)
      --> Kafka Topic: "notifications.medium"  --> Consumer Group: email-workers (5 instances)
      --> Kafka Topic: "notifications.low"     --> Consumer Group: sms-workers (2 instances)

  Benefits:
  - API responds in <50ms (just enqueue)
  - Consumers scale independently per channel
  - Backpressure handled by Kafka consumer lag
  - Failed messages go to DLQ for retry
```

### Code Snippet

```java
// Producer
public class NotificationService {
    private final BlockingQueue<Notification> queue;

    public void send(Notification notification) {
        validate(notification);
        checkPreferences(notification);
        queue.put(notification);  // non-blocking enqueue
        // API returns 202 Accepted immediately
    }
}

// Consumer
public class NotificationWorker implements Runnable {
    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            Notification n = queue.take();  // blocks until available
            NotificationHandler handler = registry.get(n.getChannel());
            try {
                handler.send(n);
                tracker.onStatusChange(n.getId(), DELIVERED);
            } catch (Exception e) {
                tracker.onStatusChange(n.getId(), FAILED);
                retryOrDLQ(n);
            }
        }
    }
}
```

### Interview Talking Point

> "The producer-consumer pattern decouples accepting the notification from delivering it. The
> API just validates and enqueues -- it responds in under 50ms. Separate worker threads (or
> Kafka consumer groups in production) handle actual delivery. This gives us backpressure
> handling, independent scaling per channel, and graceful degradation -- if email is slow,
> push notifications are unaffected."

### Tradeoffs

- (+) Fast API response. Delivery latency is absorbed by the queue.
- (+) Independent scaling: add more SMS workers without touching push workers.
- (+) Natural retry: failed messages go back on queue or to DLQ.
- (-) Added complexity: queue monitoring, consumer lag alerts, DLQ management.
- (-) At-least-once semantics: consumer crash after send but before ack = duplicate delivery.
- (-) Harder to debug: request and delivery happen in different threads/services.

---

## 7. Repository Pattern

Three repositories abstract data access for notifications, preferences, and templates.

### ASCII Diagram

```
  Service Layer
       |
       +---> NotificationRepository   --> Cassandra (write-heavy, TTL)
       |       save(), findByUser(),
       |       updateStatus()
       |
       +---> PreferenceRepository     --> PostgreSQL (relational, consistent)
       |       getPreferences(),
       |       updateOptOut()
       |
       +---> TemplateRepository       --> PostgreSQL + Redis cache
                getTemplate(),
                findByChannel()

  Each repository is an interface.
  Swap Cassandra for DynamoDB? Change one implementation class.
```

### Code Snippet

```java
public interface NotificationRepository {
    void save(Notification notification);
    Optional<Notification> findById(String id);
    List<Notification> findByUserId(String userId);
    void updateStatus(String id, DeliveryStatus status);
}

// In-memory implementation (for demo / tests)
public class InMemoryNotificationRepository implements NotificationRepository {
    private final ConcurrentHashMap<String, Notification> store = new ConcurrentHashMap<>();

    @Override
    public void save(Notification notification) {
        store.put(notification.getId(), notification);
    }
    // ...
}
```

### Interview Talking Point

> "The repository pattern gives us a clean interface between business logic and data storage.
> The service layer never knows if it is talking to Cassandra, PostgreSQL, or an in-memory
> map. This made testing trivial -- all service tests use in-memory repositories -- and it
> means we can swap storage engines per data type: Cassandra for high-volume notification
> logs, PostgreSQL for user preferences where consistency matters."

### Tradeoffs

- (+) Testability: in-memory implementations for unit tests.
- (+) Storage-agnostic: swap databases without touching service logic.
- (+) Clear data ownership boundaries.
- (-) Extra abstraction layer. For simple CRUD, it can feel like boilerplate.
- (-) Complex queries (joins, aggregations) can be awkward to express through a generic interface.

---

## Pattern Interaction Map

How all seven patterns work together in a single notification flow:

```
  1. API receives request
  2. Builder constructs immutable Notification object          [Builder]
  3. NotificationService validates and checks preferences      [Template Method]
  4. Service enqueues notification                             [Producer-Consumer]
  5. Worker dequeues and looks up handler via registry         [Factory]
  6. Correct handler executes channel-specific send()          [Strategy]
  7. Handler notifies DeliveryTracker of result                [Observer]
  8. Tracker persists status via repository                    [Repository]
```

---

## Quick Reference: When to Mention Each Pattern

| Interview Question | Lead With |
|-------------------|-----------|
| "How do you handle multiple notification channels?" | Strategy + Factory |
| "How do you add a new channel?" | Strategy (Open/Closed Principle) |
| "How do you track delivery status?" | Observer (+ Kafka in production) |
| "Why is the Notification object designed this way?" | Builder (immutability, optional fields) |
| "Walk me through the notification flow." | Template Method (the pipeline) |
| "How do you handle high throughput?" | Producer-Consumer (queue + workers) |
| "How do you test this system?" | Repository (in-memory impls) + Strategy (mock handlers) |
