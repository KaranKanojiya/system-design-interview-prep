# Design Patterns in the Distributed Message Queue

> Interview-ready reference for a Senior Java developer.
> For each pattern: what it is, why it is here, anti-pattern contrast, numbered call chains,
> ASCII diagrams, alternatives, tradeoffs, and a 30-second interview soundbite.

---

## Table of Contents

| # | Pattern    | GoF Category | Key Class(es) | One-Liner |
|---|------------|--------------|----------------|-----------|
| 1 | Strategy   | Behavioral   | `PartitioningStrategy`, `DeliveryStrategy`, `StorageStrategy` | Swap partitioning, delivery, and storage algorithms at runtime |
| 2 | Builder    | Creational   | `Message.Builder` | Construct complex Message objects with optional fields cleanly |
| 3 | Factory    | Creational   | `AppConfig` | Centralized composition root with lazy initialization |
| 4 | Repository | Architectural | `TopicRepository` -> `InMemoryTopicRepository`, etc. | Decouple storage from business logic |
| 5 | Facade     | Structural   | `MessageQueueService` | Unified API for 6 subsystems |
| 6 | Observer   | Behavioral   | `ConsumerGroupCoordinator` rebalancing | React to consumer membership changes |
| 7 | State      | Behavioral   | `Offset`, `BrokerNode` controller flag | Track consumer progress and broker roles |
| 8 | Singleton  | Creational   | `AppConfig` lazy initialization | Single composition root for the entire system |

---

## 1. Strategy Pattern

### What

Define a family of algorithms, encapsulate each one behind a common interface, and make them
interchangeable at runtime. The client code (service layer) depends only on the interface,
never on concrete implementations.

### Where It Appears (3 Strategy Families)

```
FAMILY 1: Partitioning
  PartitioningStrategy
    |-- HashPartitioningStrategy        (key.hashCode() % count)
    |-- RoundRobinPartitioningStrategy  (counter++ % count)

FAMILY 2: Delivery Guarantee
  DeliveryStrategy
    |-- AtLeastOnceDeliveryStrategy     (retry loop, 5% failure sim)
    |-- ExactlyOnceDeliveryStrategy     (idempotent dedup set)

FAMILY 3: Storage / Compaction
  StorageStrategy
    |-- TimeBasedRetentionStrategy      (expire by timestamp)
    |-- LogCompactionStrategy           (keep latest per key)
```

### ASCII Diagram

```
                 +-------------------------+
                 |    <<interface>>         |
                 |  PartitioningStrategy    |
                 +-------------------------+
                 | + assignPartition(key,   |
                 |     partitionCount): int |
                 | + getStrategyName(): Str |
                 +-----------+-------------+
                             |
              +--------------+--------------+
              |                             |
+-------------+----------+   +-------------+-------------+
| HashPartitioning       |   | RoundRobinPartitioning    |
| Strategy               |   | Strategy                  |
|------------------------|   |---------------------------|
| (stateless)            |   | - counter: AtomicInteger  |
|------------------------|   |---------------------------|
| assignPartition():     |   | assignPartition():        |
|   abs(key.hashCode())  |   |   counter.getAndIncr()    |
|   % partitionCount     |   |   % partitionCount        |
+------------------------+   +---------------------------+

                 +-------------------------+
                 |    <<interface>>         |
                 |    DeliveryStrategy      |
                 +-------------------------+
                 | + deliver(msg, consumer) |
                 |     : boolean           |
                 | + getGuarantee(): Enum   |
                 +-----------+-------------+
                             |
              +--------------+--------------+
              |                             |
+-------------+----------+   +-------------+-------------+
| AtLeastOnceDelivery    |   | ExactlyOnceDelivery       |
| Strategy               |   | Strategy                  |
|------------------------|   |---------------------------|
| - MAX_RETRIES: 3       |   | - deliveredIds: Set<Str>  |
| - FAILURE_RATE: 0.05   |   |---------------------------|
|------------------------|   | deliver():                |
| deliver():             |   |   if id in set -> skip    |
|   for 1..3:            |   |   else add id, deliver    |
|     if ack -> return T |   +---------------------------+
|   return F             |
+------------------------+

                 +-------------------------+
                 |    <<interface>>         |
                 |    StorageStrategy       |
                 +-------------------------+
                 | + shouldRetain(msg, ms)  |
                 |     : boolean           |
                 | + compact(msgs): List   |
                 +-----------+-------------+
                             |
              +--------------+--------------+
              |                             |
+-------------+----------+   +-------------+-------------+
| TimeBasedRetention     |   | LogCompaction             |
| Strategy               |   | Strategy                  |
|------------------------|   |---------------------------|
| shouldRetain():        |   | shouldRetain(): true      |
|   age <= retentionMs   |   | compact():                |
| compact():             |   |   keep latest offset/key  |
|   filter by age        |   |   null-key always kept    |
+------------------------+   +---------------------------+
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: if-else dispatch in ProducerService
public int assignPartition(String key, int count, String algorithm) {
    if (algorithm.equals("hash")) {
        return Math.abs(key.hashCode()) % count;
    } else if (algorithm.equals("round-robin")) {
        return counter.getAndIncrement() % count;
    } else if (algorithm.equals("consistent-hash")) {
        return consistentHash(key, count);
    }
    // Every new algorithm = modify this method = OCP violation
    // Testing requires testing all branches together
    // No way to swap at runtime without string comparison
    throw new IllegalArgumentException("Unknown: " + algorithm);
}
```

### Clean Implementation

```java
// CLEAN: Strategy interface
public interface PartitioningStrategy {
    int assignPartition(String key, int partitionCount);
    String getStrategyName();
}

// Concrete: Hash-based
public class HashPartitioningStrategy implements PartitioningStrategy {
    @Override
    public int assignPartition(String key, int partitionCount) {
        if (key == null) return 0;
        return Math.abs(key.hashCode()) % partitionCount;
    }
}

// Service depends on interface only
public class ProducerService {
    private final PartitioningStrategy partitioningStrategy; // injected

    private int resolvePartition(ProducerRecord record, int partitionCount) {
        if (record.getPartition() != null) return record.getPartition();
        return partitioningStrategy.assignPartition(record.getKey(), partitionCount);
    }
}
```

### Numbered Call Chain (Produce with Hash Partitioning)

```
1. ProducerService.send(record, AckMode.LEADER)
2.   TopicRepository.findByName("orders") -> Topic{partitions=3}
3.   resolvePartition(record, partitionCount=3)
4.     record.getPartition() == null -> delegate to strategy
5.     HashPartitioningStrategy.assignPartition("order-123", 3)
6.       Math.abs("order-123".hashCode()) % 3 = 1
7.   PartitionManager.getPartition("orders", 1) -> CommitLog
8.   CommitLog.append(message) -> offset=5
9.   ReplicationEngine.replicate(msg, partition, AckMode.LEADER)
10.  return offset=5
```

### Numbered Call Chain (Consume with Exactly-Once Delivery)

```
1. ConsumerService.poll("payment-group", "payments", 0, 10)
2.   ConsumerGroupCoordinator.getCommittedOffset("payment-group", "payments", 0)
3.     -> returns 3 (last committed offset)
4.   PartitionManager.getPartition("payments", 0) -> CommitLog
5.   CommitLog.read(fromOffset=3, maxMessages=10) -> 5 messages
6.   Convert each Message to ConsumerRecord
7.   ExactlyOnceDeliveryStrategy.deliver(message, "consumer-1")
8.     deliveredIds.contains(messageId)?
9.       YES -> skip (idempotent, treat as success)
10.      NO  -> add to set, deliver
11.  return List<ConsumerRecord>
```

### Runtime Swapping via AppConfig

```
1. AppConfig.setPartitioningStrategy(new RoundRobinPartitioningStrategy())
2.   this.partitioningStrategy = new RoundRobinPartitioningStrategy()
3.   this.producerService = null          // clear dependent
4.   this.messageQueueService = null      // clear transitive dependent
5.   this.messageQueueController = null   // clear transitive dependent
6.   this.messageQueueStatsDisplay = null // clear transitive dependent
7. Next call to getProducerService() lazily rebuilds with new strategy
```

### Alternatives Considered

| Alternative         | Verdict                                                    |
|--------------------|------------------------------------------------------------|
| Enum-based dispatch | Works for 2 options; grows unwieldy at 5+; violates SRP   |
| if-else chain       | Violates OCP; every new algorithm modifies existing code   |
| Template Method     | Ties to inheritance; strategies here are stateless/simple  |
| Command Pattern     | Overkill; Command is for undo/redo and request queuing     |

### Tradeoffs

| Pro | Con |
|-----|-----|
| OCP: add algorithms without modifying services | More classes (one per algorithm) |
| Each strategy independently unit-testable | Slight indirection for readers |
| Runtime swappable (A/B test different partitioning) | Caller must know which strategy to inject |
| Three families share the same pattern = consistency | N/A |

### Interview Soundbite (30 seconds)

> "We use Strategy three times: for partitioning, delivery guarantees, and storage compaction.
> For example, switching from hash to round-robin partitioning requires zero code changes
> in ProducerService -- we just inject a different PartitioningStrategy implementation via
> AppConfig. This also lets us A/B test different algorithms or swap strategies at runtime
> behind a feature flag. Each strategy is independently unit-testable."

### When to Mention in Interview

- When asked "How would you support multiple partitioning schemes?"
- When discussing delivery guarantees (at-least-once vs. exactly-once)
- When explaining log compaction vs. time-based retention
- When the interviewer asks about extensibility and the Open-Closed Principle

---

## 2. Builder Pattern

### What

Separate the construction of a complex object from its representation, allowing the same
construction process to create different configurations. In practice: fluent, step-by-step
object building with required and optional parameters.

### ASCII Diagram

```
+-----------------------------------+
|        Message (immutable*)       |
+-----------------------------------+
| - id: String           (UUID)     |
| - key: String          (nullable) |
| - value: String        (required) |
| - headers: Map<S,S>              |       +----------------------------+
| - topic: String        (required) |<------| Message.Builder            |
| - partition: int       (-1 init) |       +----------------------------+
| - offset: long         (-1 init) |       | + Builder(topic, value)    |
| - timestamp: Instant             |       | + id(String): Builder      |
| - producerId: String             |       | + key(String): Builder     |
+-----------------------------------+       | + header(k, v): Builder   |
| + getId(): String                 |       | + headers(Map): Builder   |
| + getKey(): String                |       | + partition(int): Builder |
| + getValue(): String              |       | + offset(long): Builder   |
| + setOffset(long): void           |       | + timestamp(Inst): Builder|
| + setPartition(int): void         |       | + producerId(S): Builder  |
+-----------------------------------+       | + build(): Message        |
                                            +----------------------------+
  * partition and offset are mutable (set by broker after routing)
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: Telescoping constructor
public Message(String topic, String value) { ... }
public Message(String topic, String value, String key) { ... }
public Message(String topic, String value, String key, int partition) { ... }
public Message(String topic, String value, String key, int partition,
               long offset, Instant timestamp, String producerId,
               Map<String, String> headers) { ... }
// 8 parameters -> which is the key? which is the producerId?
// Easy to swap arguments of the same type
// Every new optional field = new constructor overload
```

### Clean Implementation

```java
// CLEAN: Builder with required fields in constructor, optional via methods
Message msg = new Message.Builder("orders", "{\"item\":\"laptop\"}")
    .key("order-123")                           // optional: partition key
    .header("trace-id", "abc-xyz")              // optional: metadata
    .header("source", "web-app")                // optional: chainable
    .producerId("producer-1")                   // optional: client ID
    .build();

// Minimal construction (only required fields):
Message minimal = new Message.Builder("orders", "payload").build();
// id=UUID, key=null, partition=-1, offset=-1, timestamp=now
```

### Numbered Call Chain (Message Construction in ProducerService)

```
1. ProducerService.send(record, ackMode)
2.   topic = TopicRepository.findByName(record.getTopic())
3.   targetPartition = resolvePartition(record, partitionCount)
4.   Message msg = new Message.Builder(topicName, record.getValue())
5.     .key(record.getKey())              // from ProducerRecord
6.     .partition(targetPartition)         // from partitioning strategy
7.     .headers(record.getHeaders())       // from ProducerRecord
8.     .build()                            // id=UUID, timestamp=now, offset=-1
9.   CommitLog.append(msg)                 // broker assigns offset
10.  msg.setOffset(assignedOffset)         // broker stamps offset
```

### Alternatives Considered

| Alternative           | Verdict                                                |
|----------------------|--------------------------------------------------------|
| Telescoping ctors    | Unreadable at 5+ params; easy to swap same-type args   |
| JavaBeans setters    | Mutable throughout lifecycle; no validation at build    |
| Static factory       | Works for 2-3 params; does not scale to 9 fields      |
| Lombok @Builder      | Reduces boilerplate but hides intent; no control over validation |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Clear separation of required vs. optional fields | Extra inner class (Builder) |
| Impossible to forget required fields (topic, value) | Slightly more verbose than constructor |
| Fluent API reads like documentation | Builder state is mutable during construction |
| Easy to add new optional fields without breaking callers | N/A |

### Interview Soundbite (30 seconds)

> "Message has 9 fields, but only topic and value are required. A telescoping constructor
> would be unreadable. The Builder pattern lets callers set only what they need --
> `new Message.Builder('orders', payload).key('order-123').build()`. Required fields
> are enforced at compile time via the Builder constructor. The built Message is
> effectively immutable except for partition and offset, which the broker sets after routing."

### When to Mention in Interview

- When discussing domain model design with many optional fields
- When the interviewer asks about Message construction and immutability
- When explaining how the producer API works

---

## 3. Factory Pattern (Composition Root)

### What

Centralize all object creation in a single class so that the rest of the codebase never
calls `new ConcreteClass()`. This is the "composition root" -- the only place where
concrete implementations are selected and wired together.

### ASCII Diagram

```
                         AppConfig
                    (Composition Root)
                           |
          +----------------+----------------+
          |                |                |
     Layer 1:         Layer 2:         Layer 3:
     Repositories     Engines          Strategies
          |                |                |
  +-------+-------+  +----+----+    +------+------+
  | TopicRepo     |  | Partition|   | Partitioning|
  | BrokerRepo    |  | Manager  |   | Delivery    |
  | ConsumerGrpR  |  | CGCoord  |   | Storage     |
  | OffsetRepo    |  | MsgRouter|   +------+------+
  +-------+-------+  | ReplEng  |          |
          |           +----+----+          |
          |                |               |
          +-------+--------+-------+-------+
                  |
             Layer 4:
             Services
                  |
  +-------+-------+-------+-------+-------+-------+
  | Topic | Producer| Consumer| Broker| Retention| Metrics|
  +---+---+---+-----+---+-----+--+----+----+-----+---+---+
      |       |          |       |          |         |
      +-------+----------+-------+----------+---------+
                         |
                    Layer 5:
               MessageQueueService
                    (FACADE)
                         |
              +----------+----------+
              |                     |
         Layer 6:              Layer 6:
    MessageQueueController   StatsDisplay
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: Services create their own dependencies
public class ProducerService {
    private final PartitionManager pm = new PartitionManager();                    // hardcoded
    private final PartitioningStrategy strategy = new HashPartitioningStrategy(); // hardcoded
    private final ReplicationEngine engine = new ReplicationEngine(3);            // hardcoded
    private final TopicRepository repo = new InMemoryTopicRepository();           // hardcoded

    // Problems:
    // 1. Cannot swap implementations (e.g., InMemory -> Postgres)
    // 2. Cannot share instances across services (each has its own PM)
    // 3. Cannot test with mocks
    // 4. Cannot change strategy at runtime
}
```

### Clean Implementation

```java
// CLEAN: AppConfig creates everything; services receive dependencies via constructor
public class AppConfig {
    private PartitionManager partitionManager;
    private PartitioningStrategy partitioningStrategy;
    private ProducerService producerService;

    // Lazy initialization with caching
    public PartitionManager getPartitionManager() {
        if (partitionManager == null) {
            partitionManager = new PartitionManager();
        }
        return partitionManager;
    }

    public ProducerService getProducerService() {
        if (producerService == null) {
            producerService = new ProducerService(
                getPartitionManager(),          // shared instance
                getPartitioningStrategy(),      // swappable
                getReplicationEngine(),         // shared instance
                getTopicRepository()            // shared instance
            );
        }
        return producerService;
    }

    // Strategy setter clears dependents so graph rebuilds lazily
    public void setPartitioningStrategy(PartitioningStrategy strategy) {
        this.partitioningStrategy = strategy;
        this.producerService = null;           // clear dependent
        this.messageQueueService = null;       // clear transitive
        this.messageQueueController = null;    // clear transitive
    }
}
```

### Numbered Call Chain (AppConfig Wiring)

```
1. main() creates new AppConfig()
2. config.getController()
3.   -> getMessageQueueService()
4.     -> getTopicService()
5.       -> getTopicRepository() -> new InMemoryTopicRepository()  [cached]
6.       -> getPartitionManager() -> new PartitionManager()        [cached]
7.       -> new TopicService(topicRepo, partitionManager)          [cached]
8.     -> getProducerService()
9.       -> getPartitionManager()                                  [reused from step 6]
10.      -> getPartitioningStrategy() -> new HashPartitioningStrategy() [cached]
11.      -> getReplicationEngine() -> new ReplicationEngine(3)     [cached]
12.      -> getTopicRepository()                                   [reused from step 5]
13.      -> new ProducerService(pm, strategy, engine, repo)        [cached]
14.    -> getConsumerService()
15.      -> getPartitionManager()                                  [reused]
16.      -> getConsumerGroupCoordinator() -> new CGCoordinator()   [cached]
17.      -> getDeliveryStrategy() -> new AtLeastOnceDeliveryStrategy() [cached]
18.      -> new ConsumerService(pm, coordinator, deliveryStrategy) [cached]
19.    -> getBrokerService(), getRetentionService(), getMetricsService()
20.    -> new MessageQueueService(topic, producer, consumer, broker, retention, metrics)
21.  -> getMetricsService()                                        [reused]
22.  -> new MessageQueueController(mqService, metricsService)
```

### Strategy Setter Dependency Cascade

```
config.setPartitioningStrategy(new RoundRobinPartitioningStrategy())
    |
    +-- partitioningStrategy = new RoundRobin...
    +-- producerService = null       (depends on partitioning strategy)
    +-- messageQueueService = null   (depends on producerService)
    +-- messageQueueController = null (depends on mqService)
    +-- messageQueueStatsDisplay = null (depends on mqService)
    |
    Next call to getProducerService():
    +-- creates new ProducerService with RoundRobin strategy
    +-- all transitive dependents rebuilt lazily
```

### Alternatives Considered

| Alternative              | Verdict                                               |
|--------------------------|-------------------------------------------------------|
| Spring IoC Container     | Production-ready but adds framework dependency        |
| Guice                    | Lighter than Spring but still external dependency     |
| Service Locator          | Anti-pattern; hides dependencies; hard to test        |
| Direct `new` in services | No swappability; no shared instances; untestable      |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Single place to understand all wiring | AppConfig grows with the system |
| Runtime strategy swapping via setters | Setter-triggered cascading clears are implicit |
| Lazy init = pay only for what you use | First access pays initialization cost |
| No framework dependency (pure Java) | No auto-wiring; manual wiring required |

### Interview Soundbite (30 seconds)

> "AppConfig is our composition root -- the only class that calls `new ConcreteClass()`.
> Every service receives its dependencies via constructor injection. Strategies are
> swappable at runtime via setters that cascade-clear dependent objects, so the graph
> rebuilds lazily with the new strategy. This gives us Spring-like DI without Spring --
> easy to test, easy to reason about, and zero framework dependency."

### When to Mention in Interview

- When asked "How do you wire dependencies without Spring?"
- When discussing testability and dependency injection
- When explaining how strategies are swapped at runtime

---

## 4. Repository Pattern

### What

Encapsulate data access behind an interface so the domain layer does not know
whether data lives in memory, a database, ZooKeeper, or Kafka's internal topics.

### ASCII Diagram

```
  +----------------------------------------------+
  |               Service Layer                   |
  |  TopicService    BrokerService    ConsumerSvc |
  +------+------------------+--------------------+
         |                  |
         v                  v
  +------+-------+  +------+--------+
  | <<interface>> |  | <<interface>>  |
  | TopicRepo     |  | BrokerRepo     |
  +---------+-----+  +---------+------+
            |                  |
   +--------+--------+  +-----+-----------+
   | InMemoryTopicRepo|  | InMemoryBrokerRepo|
   |                  |  |                   |
   | ConcurrentHashMap|  | ConcurrentHashMap  |
   | <String, Topic>  |  | <String, BrokerNode>|
   +------------------+  +--------------------+
```

### Four Repository Interfaces

```
TopicRepository
  |-- save(Topic)
  |-- findByName(String): Optional<Topic>
  |-- findAll(): List<Topic>
  |-- deleteByName(String)
  |-- existsByName(String): boolean

BrokerRepository
  |-- save(BrokerNode)
  |-- findById(String): Optional<BrokerNode>
  |-- findController(): Optional<BrokerNode>    <-- broker-specific
  |-- findAll(): List<BrokerNode>
  |-- findAlive(Duration): List<BrokerNode>     <-- broker-specific
  |-- deleteById(String)

ConsumerGroupRepository
  |-- save(ConsumerGroup)
  |-- findById(String): Optional<ConsumerGroup>
  |-- findAll(): List<ConsumerGroup>
  |-- deleteById(String)

OffsetRepository
  |-- save(Offset)
  |-- findByGroupTopicPartition(g, t, p): Optional<Offset>
  |-- findByGroup(groupId): List<Offset>
  |-- findAll(): List<Offset>
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: Direct ConcurrentHashMap usage in service
public class TopicService {
    private final ConcurrentHashMap<String, Topic> topics = new ConcurrentHashMap<>();

    public Topic createTopic(String name, int partitions, int replication) {
        if (topics.containsKey(name)) throw new IllegalStateException("exists");
        Topic topic = new Topic(name, partitions, replication);
        topics.put(name, topic);
        return topic;
    }
    // Problems:
    // 1. Cannot swap to Postgres/ZooKeeper without rewriting TopicService
    // 2. Storage details leak into business logic
    // 3. Cannot test TopicService with a mock store
    // 4. ConcurrentHashMap API bleeds into domain code
}
```

### Clean Implementation

```java
// CLEAN: Interface abstracts storage
public interface TopicRepository {
    void save(Topic topic);
    Optional<Topic> findByName(String name);
    boolean existsByName(String name);
}

// In-memory implementation
public class InMemoryTopicRepository implements TopicRepository {
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();

    @Override
    public void save(Topic topic) { topics.put(topic.getName(), topic); }

    @Override
    public Optional<Topic> findByName(String name) {
        return Optional.ofNullable(topics.get(name));
    }
}

// Service depends on interface only
public class TopicService {
    private final TopicRepository topicRepo;

    public TopicService(TopicRepository topicRepo, PartitionManager pm) {
        this.topicRepo = topicRepo;
    }

    public Topic createTopic(String name, int partitions, int replication) {
        if (topicRepo.existsByName(name)) throw new IllegalStateException("exists");
        Topic topic = new Topic(name, partitions, replication);
        topicRepo.save(topic);
        return topic;
    }
}
```

### Numbered Call Chain (Topic Creation)

```
1. TopicService.createTopic("orders", 3, 2)
2.   topicRepo.existsByName("orders")
3.     -> InMemoryTopicRepository: topics.containsKey("orders") -> false
4.   new Topic("orders", 3, 2)
5.   topicRepo.save(topic)
6.     -> InMemoryTopicRepository: topics.put("orders", topic)
7.   PartitionManager.createPartition("orders", 0) -> CommitLog
8.   PartitionManager.createPartition("orders", 1) -> CommitLog
9.   PartitionManager.createPartition("orders", 2) -> CommitLog
10.  return Topic{name=orders, partitions=3, replication=2}
```

### Alternatives Considered

| Alternative       | Verdict                                                |
|-------------------|--------------------------------------------------------|
| DAO Pattern       | Similar but typically tied to SQL; Repository is more domain-aligned |
| Active Record     | Entity manages its own persistence; violates SRP       |
| Direct Map access | No abstraction; cannot swap storage; untestable        |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Swap InMemory for Postgres/ZK with zero service changes | Interface + impl = 2 files per entity |
| Services are testable with mock repositories | Slight indirection |
| Storage details completely hidden from domain logic | Must define interface upfront |
| `Optional` return types enforce null-safety | N/A |

### Interview Soundbite (30 seconds)

> "We have four repository interfaces -- Topic, Broker, ConsumerGroup, and Offset --
> each with an in-memory implementation backed by ConcurrentHashMap. TopicService
> depends on TopicRepository interface, so we can swap to a Postgres or ZooKeeper
> implementation without changing any business logic. In a real Kafka deployment,
> offsets would be stored in the __consumer_offsets internal topic, but the
> OffsetRepository interface makes that swap transparent."

### When to Mention in Interview

- When asked about data access patterns
- When discussing how to swap from in-memory to persistent storage
- When explaining testability and mock injection

---

## 5. Facade Pattern

### What

Provide a unified interface to a set of interfaces in a subsystem. The Facade defines
a higher-level interface that makes the subsystem easier to use. Clients interact with
the Facade instead of wiring individual services directly.

### ASCII Diagram

```
         Client (Controller / App)
                  |
                  v
  +---------------------------------------+
  |       MessageQueueService             |
  |            (FACADE)                   |
  +---------------------------------------+
  | + createTopic(name, part, repl)       |
  | + produce(record, ackMode): offset    |
  | + consume(group, topic, part, max)    |
  | + commit(group, topic, part, offset)  |
  | + subscribe(group, consumer, topic)   |
  | + runRetention(topic, retentionMs)    |
  | + getSystemOverview(): String         |
  +---+--------+--------+--------+---+---+
      |        |        |        |   |   |
      v        v        v        v   v   v
  +------+ +------+ +------+ +---+ +-+ +-+
  |Topic | |Prod. | |Cons. | |Bro| |Re| |Me|
  |Svc   | |Svc   | |Svc   | |Svc| |tn| |tr|
  +------+ +------+ +------+ +---+ +-+ +-+
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: Controller wires 6 services directly
public class MessageQueueController {
    private final TopicService topicService;
    private final ProducerService producerService;
    private final ConsumerService consumerService;
    private final BrokerService brokerService;
    private final RetentionService retentionService;
    private final MetricsService metricsService;

    public long produce(ProducerRecord record, AckMode ackMode) {
        long offset = producerService.send(record, ackMode);
        Message metricsMsg = new Message.Builder(record.getTopic(), record.getValue())
            .key(record.getKey()).build();
        metricsService.recordProduce(record.getTopic(), metricsMsg);
        return offset;
    }
    // Problems:
    // 1. Controller knows about metrics tracking (not its job)
    // 2. 6 constructor parameters = high coupling
    // 3. Orchestration logic duplicated if another client (CLI, SDK) needs it
}
```

### Clean Implementation

```java
// CLEAN: Facade hides orchestration
public class MessageQueueService {
    private final TopicService topicService;
    private final ProducerService producerService;
    private final ConsumerService consumerService;
    private final BrokerService brokerService;
    private final RetentionService retentionService;
    private final MetricsService metricsService;

    public long produce(ProducerRecord record, AckMode ackMode) {
        long offset = producerService.send(record, ackMode);
        // Facade handles cross-cutting concerns (metrics)
        Message metricsMsg = new Message.Builder(record.getTopic(), record.getValue())
            .key(record.getKey()).build();
        metricsService.recordProduce(record.getTopic(), metricsMsg);
        return offset;
    }
}

// Controller is thin -- delegates to Facade
public class MessageQueueController {
    private final MessageQueueService mqService;  // just 1 dependency

    public long produce(ProducerRecord record, AckMode ackMode) {
        return mqService.produce(record, ackMode);
    }
}
```

### Numbered Call Chain (Produce via Facade)

```
1. MessageQueueController.produce(record, AckMode.LEADER)
2.   MessageQueueService.produce(record, AckMode.LEADER)     -- FACADE
3.     ProducerService.send(record, AckMode.LEADER)
4.       TopicRepository.findByName("orders")
5.       PartitioningStrategy.assignPartition("order-123", 3) -> partition 1
6.       PartitionManager.getPartition("orders", 1) -> CommitLog
7.       Message.Builder("orders", payload).key("order-123").build()
8.       CommitLog.append(message) -> offset=5
9.       ReplicationEngine.replicate(msg, partition, AckMode.LEADER)
10.    MetricsService.recordProduce("orders", metricsMsg)
11.      QueueMetrics.recordIn(message) -> messagesIn++, lag recalculated
12.  return offset=5
```

### Numbered Call Chain (Consume via Facade)

```
1. MessageQueueController.consume("order-group", "orders", 0, 10)
2.   MessageQueueService.consume("order-group", "orders", 0, 10)  -- FACADE
3.     ConsumerService.poll("order-group", "orders", 0, 10)
4.       ConsumerGroupCoordinator.getCommittedOffset() -> 3
5.       PartitionManager.getPartition("orders", 0) -> CommitLog
6.       CommitLog.read(3, 10) -> 5 messages
7.       Convert each Message to ConsumerRecord
8.     For each ConsumerRecord:
9.       MetricsService.recordConsume("orders", metricsMsg)
10.      QueueMetrics.recordOut(message) -> messagesOut++, lag recalculated
11.  return 5 ConsumerRecords
```

### Alternatives Considered

| Alternative       | Verdict                                              |
|-------------------|------------------------------------------------------|
| Mediator          | Mediator decouples peer-to-peer; Facade simplifies access to subsystem |
| Service aggregator | Same concept; Facade is the GoF name                |
| Direct service access | High coupling; orchestration duplicated across clients |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Controller has 1 dependency instead of 6 | Facade can become a "god class" if not careful |
| Orchestration logic (produce + metrics) in one place | Extra layer of indirection |
| Multiple clients (controller, CLI, SDK) reuse Facade | Facade must expose all needed operations |
| Cross-cutting concerns (metrics) handled centrally | N/A |

### Interview Soundbite (30 seconds)

> "MessageQueueService is a Facade over six services -- Topic, Producer, Consumer,
> Broker, Retention, and Metrics. The Controller has just one dependency instead
> of six. The Facade also handles cross-cutting concerns: after producing, it
> records metrics; after consuming, it records metrics for each record. If we
> add a CLI or SDK client, they reuse the same Facade with zero duplication."

### When to Mention in Interview

- When asked "How do you keep the controller thin?"
- When discussing API design and separation of concerns
- When explaining how multiple clients (REST, CLI, SDK) share business logic

---

## 6. Observer Pattern (Implicit)

### What

Define a one-to-many dependency so that when one object changes state, all dependents
are notified and updated automatically. In this codebase, it is implemented implicitly
through consumer group membership changes triggering rebalancing.

### ASCII Diagram

```
  Consumer joins/leaves group
         |
         v
  ConsumerService.subscribe() / .unsubscribe()
         |
         +---> ConsumerGroupCoordinator.joinGroup() / .leaveGroup()
         |         |
         |         v
         |     group.addMember() / group.removeMember()
         |
         +---> ConsumerGroupCoordinator.rebalance(groupId, partitionCount)
                   |
                   v
              For each consumer in sorted order:
                assign partition range
                   |
                   v
              group.setAssignment(consumerId, partitions)
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: No rebalancing; manual partition assignment
public class ConsumerService {
    public void subscribe(String groupId, String consumerId, String topic) {
        // Hardcode partition assignment
        if (consumerId.equals("consumer-A")) assignPartitions(List.of(0, 1));
        if (consumerId.equals("consumer-B")) assignPartitions(List.of(2, 3));
        // Problems:
        // 1. Adding consumer-C requires code change
        // 2. Removing consumer-B leaves partitions 2,3 unassigned
        // 3. No automatic redistribution
    }
}
```

### Clean Implementation

```java
// CLEAN: Membership change triggers rebalance (Observer-like)
public void subscribe(String groupId, String consumerId, String topic, int partitionCount) {
    // 1. Create group if needed
    if (coordinator.getGroup(groupId).isEmpty()) {
        coordinator.createGroup(groupId);
    }
    // 2. Subscribe group to topic
    coordinator.getGroup(groupId).ifPresent(g -> g.subscribe(topic));
    // 3. Join group (state change)
    ConsumerInstance consumer = new ConsumerInstance(consumerId, groupId, "localhost");
    coordinator.joinGroup(groupId, consumer);
    // 4. Rebalance triggered (notification/reaction)
    coordinator.rebalance(groupId, partitionCount);
}

public void unsubscribe(String groupId, String consumerId) {
    // 1. Leave group (state change)
    coordinator.leaveGroup(groupId, consumerId);
    // 2. Rebalance triggered (notification/reaction)
    coordinator.getGroup(groupId).ifPresent(group -> {
        if (group.getMemberCount() > 0) {
            coordinator.rebalance(groupId, partitionCount);
        }
    });
}
```

### Numbered Call Chain (Consumer Join Triggers Rebalance)

```
State: 2 consumers, 4 partitions
  consumer-A -> [0, 1]
  consumer-B -> [2, 3]

1. ConsumerService.subscribe("analytics", "consumer-C", "events", 4)
2.   coordinator.joinGroup("analytics", consumer-C)
3.     group.addMember(consumer-C)
4.   coordinator.rebalance("analytics", 4)
5.     consumerIds = ["consumer-A", "consumer-B", "consumer-C"] (sorted)
6.     partitionsPerConsumer = 4 / 3 = 1
7.     remainder = 4 % 3 = 1
8.     consumer-A -> [0, 1]  (gets remainder = 1 extra)
9.     consumer-B -> [2]
10.    consumer-C -> [3]

Result: partitions redistributed automatically
```

### Alternatives Considered

| Alternative            | Verdict                                            |
|------------------------|----------------------------------------------------|
| Explicit Observer/Listener | More formal but overkill for single event type |
| Event bus (Guava)      | Adds framework dependency for a simple use case    |
| Polling-based rebalance | Wastes CPU; delayed reaction to membership changes |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Automatic redistribution on join/leave | Rebalance blocks caller thread (sync) |
| Deterministic assignment (sorted IDs) | Range assignment may cause uneven load |
| Simple implementation (no event bus) | Not a formal Observer; relies on calling convention |

### Interview Soundbite (30 seconds)

> "When a consumer joins or leaves a group, the ConsumerService automatically triggers
> a rebalance via the ConsumerGroupCoordinator. This is an implicit Observer pattern --
> the membership change is the event, and rebalancing is the reaction. Range assignment
> sorts consumer IDs and distributes partitions evenly, with remainder partitions going
> to the first consumers. In Kafka, this is called a 'cooperative rebalance' and uses
> the GroupCoordinator protocol."

### When to Mention in Interview

- When asked about consumer group rebalancing
- When discussing how the system reacts to consumer failures
- When explaining Kafka's group coordinator protocol

---

## 7. State Pattern (Implicit)

### What

Allow an object to alter its behavior when its internal state changes. In this codebase,
state transitions are tracked through mutable fields rather than formal State objects,
but the concept is the same: behavior depends on current state.

### ASCII Diagram -- Offset Lifecycle

```
  New Offset                Consuming                    Committed
  (committedOffset = -1)    (reading messages)           (offset = N)
       |                         |                            |
       v                         v                            v
  +----------+              +----------+               +----------+
  | INITIAL  | --consume--> | READING  | --commit----> | COMMITTED|
  | offset=-1|              | messages |               | offset=N |
  +----------+              +----------+               +----------+
       ^                                                     |
       |                                                     |
       +------------- restart (resume from committed) -------+
```

### ASCII Diagram -- BrokerNode Controller State

```
  +-------------+          +---------------+
  | FOLLOWER    | --elect->| CONTROLLER    |
  | isController|          | isController  |
  | = false     |          | = true        |
  +------+------+          +-------+-------+
         ^                         |
         |                         |
         +--- failure/re-election -+
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: Scattered state checks
public void processMessage(String groupId, String topic, int partition) {
    // Check if offset was ever committed
    Long offset = offsets.get(key);
    if (offset == null) {
        offset = 0L;  // start from beginning
    }
    // Read from offset...
    // Problems:
    // 1. No clear state transitions documented
    // 2. -1 vs null vs 0 semantics scattered across codebase
    // 3. No lifecycle visibility
}
```

### Clean Implementation

```java
// CLEAN: Offset tracks its own state with clear transitions
public class Offset {
    private long committedOffset;       // -1 = never committed
    private Instant lastCommitTime;

    public Offset(String groupId, String topicName, int partitionId) {
        this.committedOffset = -1;      // INITIAL state
        this.lastCommitTime = Instant.now();
    }

    public void commit(long newOffset) {
        this.committedOffset = newOffset;    // transition to COMMITTED
        this.lastCommitTime = Instant.now(); // record when
    }
}

// BrokerNode tracks controller state
public class BrokerNode {
    private boolean isController;

    public void setController(boolean controller) {
        this.isController = controller;  // FOLLOWER <-> CONTROLLER transition
    }

    public boolean isAlive(Duration timeout) {
        return Duration.between(lastHeartbeat, Instant.now()).compareTo(timeout) < 0;
    }
}
```

### Numbered Call Chain (Offset State Transitions)

```
1. ConsumerService.subscribe("group-1", "consumer-A", "orders", 3)
2.   ConsumerGroupCoordinator creates offset tracking
3.   Offset("group-1", "orders", 0) -> committedOffset = -1   [INITIAL]

4. ConsumerService.poll("group-1", "orders", 0, 10)
5.   coordinator.getCommittedOffset("group-1", "orders", 0)
6.     -> returns 0 (default, since no offset committed)       [READING]

7. ConsumerService.commit("group-1", "orders", 0, offset=5)
8.   coordinator.commitOffset("group-1", "orders", 0, 5)
9.     committedOffsets.put("group-1-orders-0", 5)             [COMMITTED]

10. ConsumerService.poll("group-1", "orders", 0, 10)
11.   coordinator.getCommittedOffset("group-1", "orders", 0)
12.    -> returns 5 (resume from committed)                    [READING from 5]
```

### Alternatives Considered

| Alternative          | Verdict                                          |
|---------------------|--------------------------------------------------|
| Formal State objects | Overkill; only 2-3 states per entity             |
| Enum-based state     | Adds complexity without benefit for simple fields |
| Boolean flags        | What we use; simple and sufficient                |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Simple mutable fields (no extra classes) | State transitions are implicit |
| Clear default values (-1 for "never committed") | No compile-time state validation |
| Matches Kafka's offset semantics exactly | Reader must understand conventions |

### Interview Soundbite (30 seconds)

> "Offset tracks consumer progress through implicit state transitions. A new offset
> starts at -1 (never committed), transitions to a committed value when the consumer
> calls commit(), and resumes from the committed value on restart. BrokerNode uses
> an isController flag that transitions during leader election. These are implicit
> State patterns -- simple enough that formal State objects would be overkill."

### When to Mention in Interview

- When asked about offset management and consumer restarts
- When discussing broker controller election
- When explaining how consumers resume after crashes

---

## 8. Singleton Pattern (Lazy Initialization)

### What

Ensure a class has only one instance and provide a global point of access to it.
In this codebase, AppConfig acts as a lazy-initialized Singleton-like composition
root -- while not enforced via `private constructor + static instance`, it is
designed to be instantiated once and shared.

### ASCII Diagram

```
  main()
    |
    v
  AppConfig config = new AppConfig()      <-- single instance
    |
    +-- getPartitionManager()
    |     -> if null, create new
    |     -> cache and return                 <-- lazy init, cached
    |
    +-- getTopicRepository()
    |     -> if null, create new
    |     -> cache and return                 <-- lazy init, cached
    |
    +-- getProducerService()
          -> if null, create new
          -> uses cached PM + cached Repo
          -> cache and return                 <-- lazy init, cached
```

### Anti-Pattern (What NOT to Do)

```java
// ANTI-PATTERN: Multiple AppConfig instances
public class Demo1 {
    public static void main() {
        AppConfig config1 = new AppConfig();
        AppConfig config2 = new AppConfig();
        // config1.getPartitionManager() != config2.getPartitionManager()
        // Two separate PartitionManagers = data inconsistency
        // Messages produced via config1 are invisible to config2 consumers
    }
}
```

### Clean Implementation

```java
// CLEAN: Single AppConfig, lazy initialization
public class DistributedMessageQueueApp {
    public static void main(String[] args) {
        AppConfig config = new AppConfig();  // single composition root

        // All demos share the same config -> same PartitionManager -> same data
        demo1_ProduceAndConsume(config);
        demo2_Partitioning(config);
        demo3_ConsumerGroupRebalancing(config);
        // ...
    }
}

// Inside AppConfig: lazy caching
public PartitionManager getPartitionManager() {
    if (partitionManager == null) {
        partitionManager = new PartitionManager();  // created once
    }
    return partitionManager;  // same instance on every call
}
```

### Numbered Call Chain (Lazy Init + Caching)

```
1. config.getController()
2.   messageQueueController == null -> need to create
3.   config.getMessageQueueService()
4.     messageQueueService == null -> need to create
5.     config.getTopicService()
6.       topicService == null -> need to create
7.       config.getTopicRepository()
8.         topicRepository == null -> new InMemoryTopicRepository()  [CACHED]
9.       config.getPartitionManager()
10.        partitionManager == null -> new PartitionManager()        [CACHED]
11.      new TopicService(topicRepo, pm)                            [CACHED]
12.    config.getProducerService()
13.      config.getPartitionManager() -> REUSED from step 10        [HIT]
14.      config.getPartitioningStrategy()
15.        new HashPartitioningStrategy()                            [CACHED]
16.      new ProducerService(pm, strategy, engine, repo)            [CACHED]
17.    ... (consumer, broker, retention, metrics services)
18.    new MessageQueueService(...)                                  [CACHED]
19.  new MessageQueueController(mqService, metricsService)          [CACHED]
```

### Alternatives Considered

| Alternative                    | Verdict                                      |
|-------------------------------|----------------------------------------------|
| `private` ctor + `static getInstance()` | Classic Singleton; too rigid; hard to test |
| Enum Singleton                | Thread-safe but does not support DI           |
| Spring `@Component` scope     | Production approach; adds Spring dependency   |
| Eager initialization          | Simple but pays full cost upfront             |

### Tradeoffs

| Pro | Con |
|-----|-----|
| Lazy init = pay only for used components | Not thread-safe (single-threaded assumed) |
| Single instance = consistent data view | Not enforced; discipline required |
| No framework dependency | No compile-time guarantee of single instance |
| Easy to test (just create new AppConfig) | Strategy setters cascade-clear can surprise |

### Interview Soundbite (30 seconds)

> "AppConfig uses lazy initialization -- each getter creates its object on first
> access and caches it for subsequent calls. This ensures all services share the
> same PartitionManager, the same repositories, and the same strategies. It behaves
> as a Singleton composition root without the rigidity of a static getInstance()
> pattern, making it easy to test by simply creating a new AppConfig per test."

### When to Mention in Interview

- When asked about application bootstrap and initialization
- When discussing lazy vs. eager initialization tradeoffs
- When explaining how to avoid redundant object creation

---

## Cross-Cutting Pattern Interactions

### How Patterns Work Together

```
                    Client Request: produce("orders", "order-123", payload)
                           |
                           v
    +------ [8. SINGLETON] AppConfig creates and caches all components
    |                      |
    |                      v
    |          [5. FACADE] MessageQueueService.produce(record, ackMode)
    |                      |
    |          +-----------+-----------+
    |          |                       |
    |          v                       v
    |  [1. STRATEGY]           [4. REPOSITORY]
    |  PartitioningStrategy    TopicRepository
    |  .assignPartition()      .findByName("orders")
    |  key.hashCode() % 3      -> Topic{partitions=3}
    |          |
    |          v
    |  [2. BUILDER]
    |  Message.Builder("orders", payload)
    |  .key("order-123")
    |  .partition(1)
    |  .build()
    |          |
    |          v
    |  CommitLog.append(message)
    |  -> [7. STATE] offset transitions from -1 to assigned value
    |          |
    |          v
    |  ReplicationEngine.replicate(msg, partition, AckMode.LEADER)
    |          |
    |          v
    |  MetricsService.recordProduce()
    |  -> QueueMetrics.recordIn() -> messagesIn++, lag updated
    |
    +------ All objects cached by [8. SINGLETON] AppConfig
```

### Pattern Responsibility Matrix

```
+-------------+--------------------------------------------------+
| Pattern     | Responsibility in the System                     |
+-------------+--------------------------------------------------+
| Strategy    | HOW messages are partitioned, delivered, stored   |
| Builder     | HOW messages are constructed                      |
| Factory     | WHERE objects are created (AppConfig only)        |
| Repository  | WHERE data is stored (interface abstraction)      |
| Facade      | WHO clients talk to (single entry point)          |
| Observer    | WHEN rebalancing happens (on membership change)   |
| State       | WHAT state an offset/broker is in                 |
| Singleton   | HOW MANY instances exist (one AppConfig)           |
+-------------+--------------------------------------------------+
```

---

## Quick-Reference: Pattern Decision Guide

Use this table when deciding which pattern to mention for a given interview question:

| Interview Question | Primary Pattern | Supporting Patterns |
|-------------------|-----------------|---------------------|
| "How would you support multiple partitioning schemes?" | Strategy | Factory (swap via AppConfig) |
| "How do you construct messages with 9 fields?" | Builder | N/A |
| "How do you wire dependencies without Spring?" | Factory | Singleton (lazy init) |
| "How do you swap from in-memory to Postgres?" | Repository | Factory (AppConfig returns impl) |
| "How do you keep the controller thin?" | Facade | Repository, Strategy |
| "What happens when a consumer joins/leaves?" | Observer | State (offset lifecycle) |
| "How does offset tracking work?" | State | Repository (OffsetRepository) |
| "How do you avoid creating duplicate objects?" | Singleton | Factory (lazy caching) |
| "How would you add exactly-once delivery?" | Strategy | State (dedup set) |
| "How does log compaction work?" | Strategy | State (offset-based latest-per-key) |
| "How does controller election work?" | State | Observer (react to broker failure) |
| "How do you handle cross-cutting concerns?" | Facade | Strategy (metrics per strategy) |

---

## Kafka Mapping: Patterns to Production Concepts

| This Codebase | Kafka Equivalent | Pattern Used |
|--------------|------------------|--------------|
| `CommitLog` | Kafka Log Segment | N/A (core data structure) |
| `PartitioningStrategy` | `Partitioner` interface | Strategy |
| `DeliveryStrategy` | `acks` + idempotent producer + txn | Strategy |
| `StorageStrategy` | `cleanup.policy` (delete/compact) | Strategy |
| `Message.Builder` | `ProducerRecord` constructor | Builder |
| `AppConfig` | Spring Boot auto-configuration | Factory + Singleton |
| `TopicRepository` | ZooKeeper / KRaft metadata store | Repository |
| `OffsetRepository` | `__consumer_offsets` topic | Repository |
| `MessageQueueService` | `KafkaProducer` + `KafkaConsumer` API | Facade |
| `ConsumerGroupCoordinator` | `GroupCoordinator` protocol | Observer |
| `Offset` lifecycle | Consumer offset commit protocol | State |
| `BrokerNode` controller flag | KRaft controller election | State |

---

## Deep Dive: Strategy Pattern Across Three Families

This section provides a side-by-side comparison of all three Strategy families,
showing how the same pattern is applied consistently across different domains.

### Interface Comparison

```
+---------------------------+---------------------------+---------------------------+
| PartitioningStrategy      | DeliveryStrategy          | StorageStrategy           |
+---------------------------+---------------------------+---------------------------+
| assignPartition(          | deliver(                  | shouldRetain(             |
|   key: String,            |   message: Message,       |   message: Message,       |
|   partitionCount: int     |   consumerId: String      |   retentionMs: long       |
| ): int                    | ): boolean                | ): boolean                |
|                           |                           |                           |
| getStrategyName(): String | getGuarantee(): Enum      | compact(                  |
|                           | getStrategyName(): String |   msgs: List<Message>     |
|                           |                           | ): List<Message>          |
|                           |                           | getStrategyName(): String |
+---------------------------+---------------------------+---------------------------+
```

### Implementation Count

```
PartitioningStrategy (2 impls)
  |-- HashPartitioningStrategy        -- deterministic, per-key ordering
  |-- RoundRobinPartitioningStrategy  -- even distribution, no ordering

DeliveryStrategy (2 impls)
  |-- AtLeastOnceDeliveryStrategy     -- retry with ack, possible duplicates
  |-- ExactlyOnceDeliveryStrategy     -- idempotent dedup, no duplicates

StorageStrategy (2 impls)
  |-- TimeBasedRetentionStrategy      -- expire by age
  |-- LogCompactionStrategy           -- keep latest per key
```

### Where Each Strategy Is Injected

```
PartitioningStrategy
  -> injected into ProducerService via AppConfig
  -> called during ProducerService.send() -> resolvePartition()
  -> determines WHICH partition a message lands in

DeliveryStrategy
  -> injected into ConsumerService via AppConfig
  -> used for delivery guarantee semantics
  -> determines HOW reliably a message is delivered

StorageStrategy
  -> injected into RetentionService via AppConfig
  -> called during RetentionService.runCleanup() and .runCompaction()
  -> determines WHEN/HOW old messages are cleaned up
```

### Adding a New Strategy (Step-by-Step)

**Example: Add ConsistentHashPartitioningStrategy**

```
Step 1: Create the class
  File: strategy/partitioning/ConsistentHashPartitioningStrategy.java

  public class ConsistentHashPartitioningStrategy implements PartitioningStrategy {
      private final TreeMap<Integer, Integer> ring = new TreeMap<>();

      public ConsistentHashPartitioningStrategy(int partitionCount, int virtualNodes) {
          for (int p = 0; p < partitionCount; p++) {
              for (int v = 0; v < virtualNodes; v++) {
                  int hash = (p + "-" + v).hashCode();
                  ring.put(hash, p);
              }
          }
      }

      @Override
      public int assignPartition(String key, int partitionCount) {
          if (key == null) return 0;
          int hash = key.hashCode();
          Map.Entry<Integer, Integer> entry = ring.ceilingEntry(hash);
          return (entry != null) ? entry.getValue() : ring.firstEntry().getValue();
      }

      @Override
      public String getStrategyName() { return "ConsistentHashPartitioning"; }
  }

Step 2: Register in AppConfig
  config.setPartitioningStrategy(
      new ConsistentHashPartitioningStrategy(partitionCount, 100));

Step 3: Verify
  - ProducerService unchanged
  - MessageRouter unchanged
  - TopicService unchanged
  - All existing tests pass
  - New strategy independently testable
```

**Example: Add AtMostOnceDeliveryStrategy**

```
Step 1: Create the class
  File: strategy/delivery/AtMostOnceDeliveryStrategy.java

  public class AtMostOnceDeliveryStrategy implements DeliveryStrategy {
      @Override
      public boolean deliver(Message message, String consumerId) {
          // Fire-and-forget: commit offset BEFORE processing
          // If consumer crashes during processing, message is lost
          System.out.println("[DELIVERY] AT_MOST_ONCE -- delivered " + message.getId()
              + " to " + consumerId + " (no retry, no dup)");
          return true;
      }

      @Override
      public DeliveryGuarantee getGuarantee() {
          return DeliveryGuarantee.AT_MOST_ONCE;
      }

      @Override
      public String getStrategyName() { return "AtMostOnceDelivery"; }
  }

Step 2: Register
  config.setDeliveryStrategy(new AtMostOnceDeliveryStrategy());

Step 3: Verify
  - ConsumerService unchanged
  - Zero existing code modified
```

### Strategy Selection Decision Tree

```
  What are you deciding?
       |
       +-- Which PARTITION to write to?
       |     |
       |     +-- Need per-key ordering?
       |     |     YES -> HashPartitioningStrategy
       |     |     NO  -> RoundRobinPartitioningStrategy
       |     |
       |     +-- Need consistent hashing (partition expansion)?
       |           YES -> ConsistentHashPartitioningStrategy (extensibility point)
       |
       +-- What DELIVERY GUARANTEE do you need?
       |     |
       |     +-- Can tolerate message loss?
       |     |     YES -> AtMostOnceDeliveryStrategy (extensibility point)
       |     |
       |     +-- Can tolerate duplicates?
       |     |     YES -> AtLeastOnceDeliveryStrategy (default)
       |     |
       |     +-- Need exactly-once?
       |           YES -> ExactlyOnceDeliveryStrategy
       |
       +-- How should old messages be CLEANED UP?
             |
             +-- Delete by age?
             |     YES -> TimeBasedRetentionStrategy
             |
             +-- Keep latest per key (changelog)?
                   YES -> LogCompactionStrategy
```

---

## Deep Dive: Facade Orchestration Sequences

### Sequence 1: Full Produce-Consume-Commit Cycle

```
Client         Controller      Facade           ProducerSvc      ConsumerSvc      Metrics
  |                |              |                  |                |              |
  |-- produce() ->|              |                  |                |              |
  |                |-- produce ->|                  |                |              |
  |                |              |-- send() ------->|                |              |
  |                |              |                  |-- findTopic -->|              |
  |                |              |                  |<-- Topic ------|              |
  |                |              |                  |-- partition -->|              |
  |                |              |                  |-- append() --->|              |
  |                |              |                  |<-- offset=5 ---|              |
  |                |              |                  |-- replicate -->|              |
  |                |              |<-- offset=5 -----|                |              |
  |                |              |-- recordProduce() ------------->|              |
  |                |              |                  |                |<-- metrics --|
  |                |<-- offset=5 -|                  |                |              |
  |<-- offset=5 --|              |                  |                |              |
  |                |              |                  |                |              |
  |-- consume() ->|              |                  |                |              |
  |                |-- consume ->|                  |                |              |
  |                |              |-- poll() ----------------------->|              |
  |                |              |                  |                |-- getOff -->|
  |                |              |                  |                |<-- off=0 ---|
  |                |              |                  |                |-- read() -->|
  |                |              |                  |                |<-- msgs ----|
  |                |              |<-- records ------|----------------|              |
  |                |              |-- recordConsume() ------------->|              |
  |                |<-- records --|                  |                |              |
  |<-- records ---|              |                  |                |              |
  |                |              |                  |                |              |
  |-- commit() -->|              |                  |                |              |
  |                |-- commit -->|                  |                |              |
  |                |              |-- commit() ------|--------------->|              |
  |                |              |                  |                |-- commitOff->|
  |                |<-- ok ------|                  |                |              |
  |<-- ok --------|              |                  |                |              |
```

### Sequence 2: Topic Creation with Partition Setup

```
Client         Controller      Facade           TopicSvc         PartitionMgr
  |                |              |                  |                |
  |-- createTopic->|              |                  |                |
  |                |-- create --->|                  |                |
  |                |              |-- createTopic -->|                |
  |                |              |                  |-- validate --->|
  |                |              |                  |-- existsByName?|
  |                |              |                  |<-- false ------|
  |                |              |                  |-- save(topic)->|
  |                |              |                  |                |
  |                |              |                  |-- createPart(0)|
  |                |              |                  |               -+-> CommitLog(0)
  |                |              |                  |-- createPart(1)|
  |                |              |                  |               -+-> CommitLog(1)
  |                |              |                  |-- createPart(2)|
  |                |              |                  |               -+-> CommitLog(2)
  |                |              |                  |                |
  |                |              |<-- Topic --------|                |
  |                |<-- Topic ----|                  |                |
  |<-- Topic ------|              |                  |                |
```

---

## Pattern Anti-Pattern Summary Table

| Pattern    | Anti-Pattern                                  | Clean Solution                              | OCP Violation? |
|------------|-----------------------------------------------|---------------------------------------------|----------------|
| Strategy   | if-else chain for algorithm selection          | Interface + implementations, injected       | Yes -> No      |
| Builder    | Telescoping constructors (8+ params)          | Fluent builder with required/optional split  | N/A            |
| Factory    | Services create own deps with `new`           | Composition root creates all; DI via ctor   | Yes -> No      |
| Repository | Direct `ConcurrentHashMap` in service         | Interface abstracts storage; impl swappable | Yes -> No      |
| Facade     | Controller wires 6 services directly          | Single MQService Facade; 2 deps in controller| N/A           |
| Observer   | Hardcoded partition assignment per consumer   | Automatic rebalance on join/leave           | Yes -> No      |
| State      | Scattered null/magic-value checks             | Explicit state fields with clear transitions | N/A           |
| Singleton  | Multiple AppConfig instances = data split     | Single instance shared across all demos     | N/A            |

---

## Interview Preparation: 60-Second Walkthrough

Use this script when asked "Walk me through your system design":

> "The Distributed Message Queue is modeled after Kafka. At its core is the **CommitLog** --
> an append-only log per partition. Producers write messages that get a monotonic offset;
> consumers read from any offset forward.
>
> **Partitioning** is handled by the Strategy pattern: hash partitioning guarantees per-key
> ordering, round-robin maximizes throughput. The strategy is injected via AppConfig and
> swappable at runtime.
>
> **Consumer groups** coordinate multiple consumers via range-based rebalancing. When a
> consumer joins or leaves, the Observer pattern triggers automatic partition redistribution.
> Offset tracking uses a State pattern -- offsets transition from initial (-1) to committed.
>
> **Delivery guarantees** are another Strategy family: at-least-once retries with ack,
> exactly-once uses an idempotent dedup set. Storage cleanup has two strategies:
> time-based retention (delete by age) and log compaction (keep latest per key).
>
> **Replication** simulates ISR with three ack modes: acks=0 (fire-and-forget), acks=1
> (leader only), acks=all (all ISR replicas). The broker cluster uses heartbeat-based
> liveness with deterministic controller election.
>
> The architecture follows SOLID: **MessageQueueService** is a Facade over 6 services,
> repositories abstract storage behind interfaces, and **AppConfig** is the composition root
> where all concrete classes are instantiated. 8 GoF patterns, 51 Java files, fully extensible."
