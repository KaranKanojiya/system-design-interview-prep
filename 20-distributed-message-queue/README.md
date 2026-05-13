# Distributed Message Queue (Kafka / RabbitMQ / Pulsar)

## Problem Summary

Design a **Distributed Message Queue** (like Apache Kafka, RabbitMQ, or Apache Pulsar) that provides durable, ordered, high-throughput message delivery between producers and consumers. The core abstraction is the **append-only commit log** -- a per-partition sequential data structure where producers append messages at the tail (O(1) write) and consumers read from any offset forward (O(1) read by offset). Each topic is split into **partitions** for parallelism; the partitioning strategy determines which partition a message lands in: **(1) explicit partition** (producer specifies directly), **(2) key-based hashing** (`Math.abs(key.hashCode()) % partitionCount` -- same key always maps to the same partition, guaranteeing per-key ordering), or **(3) round-robin** (even distribution when no key is provided, maximizing throughput at the cost of ordering). **Consumer groups** enable load-balanced consumption -- each partition is assigned to exactly one consumer within a group via **range rebalancing** (sort consumers lexicographically, divide partitions evenly, first `remainder` consumers get one extra partition; triggered on member join/leave via Observer pattern). **Offset management** tracks consumer progress per (groupId, topic, partition) tuple: `committedOffset` = "I have processed up to here", `lag = latestOffset - committedOffset` = "how far behind am I". Three **ack modes** control the durability-latency tradeoff: `acks=0` (fire-and-forget, fastest, messages may be lost), `acks=1` (leader acknowledges, balanced), `acks=all` (all ISR replicas acknowledge, safest, highest latency). **Delivery guarantees** are: at-most-once (commit before process), at-least-once (process then commit -- default, requires idempotent consumers), and exactly-once (at-least-once + consumer-side deduplication via message ID set, or Kafka's transactional API with producer ID + sequence number). **Log compaction** (`cleanup.policy=compact`) keeps only the latest value per key -- used for changelog topics, CDC streams, and materialized views. **Time-based retention** (`retention.ms`) deletes messages older than the configured window (default 7 days). **ISR replication** ensures durability: the leader writes to its local log, followers in the In-Sync Replica set pull and acknowledge; `min.insync.replicas` prevents writes to under-replicated partitions. The **broker cluster** uses controller election (lowest broker ID wins, ZooKeeper-based or KRaft) to manage partition leader assignment and topic lifecycle. The system is **AP for consumption** (consumers tolerate stale offsets and eventually catch up) and **CP for production with acks=all** (writes are rejected if ISR falls below `min.insync.replicas`, preventing data loss).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Commit Log (Core Abstraction): Append-only sequential log per partition. O(1) append at tail, O(1) read by offset. Offsets are monotonically increasing longs assigned at write time. The log is NOT deleted on consumption (unlike traditional queues) -- enabling replay and multi-consumer. Kafka's key insight: a distributed log IS the message queue.** Each partition is backed by a `CommitLog` with an `AtomicLong currentOffset`. `append(message)` assigns `currentOffset.getAndIncrement()` and adds to the list. `read(fromOffset, maxMessages)` returns a bounded subList. In production: Kafka stores segments as files on disk (segment = offset-indexed log file + time index + offset index). Sequential disk I/O is faster than random memory access at scale (6.6GB/s sequential vs 100MB/s random on NVMe). Zero-copy (`sendfile()` syscall) transfers data directly from page cache to network socket, skipping user-space copy. This is why Kafka achieves multi-GB/s throughput per broker.

- **Partitioning & Ordering: Key-based hash partitioning (key.hashCode() % partitionCount) guarantees per-key ordering. Same user's events always land in the same partition, processed by the same consumer in order. No key = round-robin (max throughput, no ordering). Partition count is set at topic creation and is hard to change.** `HashPartitioningStrategy` computes `Math.abs(key.hashCode()) % partitionCount` -- deterministic, so the same key always maps to the same partition. `RoundRobinPartitioningStrategy` uses an `AtomicInteger` counter mod partitionCount for even distribution. The `MessageRouter` cascades: explicit partition > key hash > round-robin. In production: Kafka uses Murmur2 hash (better distribution than Java's hashCode). Partition count determines max parallelism -- you cannot have more active consumers in a group than partitions. Uber uses hash partitioning on trip-id to ensure all events for a trip (request, match, pickup, dropoff) are processed in order by a single consumer.

- **Consumer Group Rebalancing: Range assignment -- sort consumer IDs, divide partitions evenly, first (partitionCount % consumerCount) consumers get one extra. Triggered on member join/leave. Each partition assigned to exactly ONE consumer in the group.** `ConsumerGroupCoordinator.rebalance()` sorts consumer IDs for deterministic assignment, computes `partitionsPerConsumer = partitionCount / consumerCount` and `remainder = partitionCount % consumerCount`. First `remainder` consumers get `partitionsPerConsumer + 1`. In Kafka: also Sticky and CooperativeSticky assignors to minimize partition movement. Cooperative rebalancing avoids the "stop-the-world" rebalance where all consumers pause. If consumers > partitions, extra consumers sit idle. Scale consumers up to partition count for max parallelism.

- **Offset Management & Consumer Lag: Offset = consumer progress per (group, topic, partition). Committed offset = "processed up to here." Lag = latestOffset - committedOffset. High lag = consumer falling behind = scale up consumers. Kafka stores offsets in __consumer_offsets internal topic.** `ConsumerGroupCoordinator` stores offsets in `ConcurrentHashMap<String, Long>` keyed by `"groupId-topic-partition"`. `commitOffset()` persists the new offset. `getLag()` computes `latestOffset - committed`. In production: Kafka's `__consumer_offsets` is a compacted internal topic (latest offset per key = latest committed offset per group-partition). Consumer lag is the primary metric for auto-scaling consumers. LinkedIn monitors lag across 7T+ messages/day. Lag > 0 for extended periods triggers alerts; lag growing = consumer throughput < producer throughput.

- **Ack Modes & Delivery Guarantees: acks=0 (fire-and-forget, no ack), acks=1 (leader ack only), acks=all (all ISR ack -- safest). Delivery: at-most-once (commit before process), at-least-once (process then commit -- default), exactly-once (idempotent producer + transactional consumer).** `ReplicationEngine.replicate()` switches on AckMode: NONE returns immediately, LEADER logs leader ack, ALL iterates all ISR replicas and warns if ISR < replicationFactor. `AtLeastOnceDeliveryStrategy` retries up to 3 times with 5% simulated failure rate. `ExactlyOnceDeliveryStrategy` uses a `ConcurrentHashMap.newKeySet()` to dedup by message ID. In production: Kafka EOS (exactly-once semantics) uses producer ID + sequence number on the broker side (idempotent producer) + transactions spanning produce + commit (transactional consumer). acks=all + min.insync.replicas=2 guarantees no data loss if at least 2 replicas survive.

- **Log Compaction vs Time-Based Retention: Compaction (cleanup.policy=compact) keeps LATEST value per key -- for changelogs, CDC, materialized views. Time-based (retention.ms) deletes messages older than window (default 7 days). Can combine: compact + delete.** `LogCompactionStrategy.compact()` groups messages by key in a `LinkedHashMap`, keeps highest-offset entry per key; null-key messages always survive. `TimeBasedRetentionStrategy.shouldRetain()` compares `Instant.now() - message.getTimestamp()` against `retentionMs`. In production: Kafka's log cleaner runs in background threads, operating on "dirty" segments (segments with keys that have newer values elsewhere). Tombstone = message with null value = key deletion marker. Compacted topics can grow unbounded (one entry per unique key), so size-based retention (`retention.bytes`) is a secondary safety net. KTable in Kafka Streams uses compacted topics as its backing store.

---

## Class Hierarchy

```
Message (core entity, Builder pattern)                ProducerRecord (producer-side)
  |-- id (UUID)                                         |-- topic (String, required)
  |-- key (String, nullable -- null = round-robin)      |-- key (String, nullable)
  |-- value (String, payload)                           |-- value (String, payload)
  |-- headers: Map<String,String>                       |-- partition (Integer, nullable = auto)
  |-- topic (String, destination)                       |-- headers: Map<String,String>
  |-- partition (int, assigned by partitioner)
  |-- offset (long, assigned by CommitLog)            ConsumerRecord (consumer-side, immutable)
  |-- timestamp (Instant)                               |-- topic, partition, offset
  |-- producerId (String)                               |-- key, value, headers, timestamp
  |-- setOffset(long) -- broker-side mutation            |-- (no setters -- immutable snapshot)
  |-- setPartition(int) -- partitioner mutation
  |-- Builder(topic, value) + fluent setters          MessageBatch (I/O optimization)
                                                        |-- batchId (UUID)
Topic (channel definition)                              |-- topicName, partition
  |-- name (String, unique)                             |-- messages: List<Message>
  |-- partitionCount (int)                              |-- getFirstOffset(), getLastOffset()
  |-- replicationFactor (int)                           |-- getSizeBytes()
  |-- retentionMs (long, default 7 days)
  |-- createdAt (Instant)                             Offset (consumer progress)
  |-- config: Map<String,String>                        |-- groupId, topicName, partitionId
  |-- getRetentionDuration() -> Duration                |-- committedOffset (long, -1 = none)
                                                        |-- lastCommitTime (Instant)
Partition (unit of parallelism + replication)            |-- commit(long newOffset)
  |-- topicName (String)
  |-- partitionId (int)                               ConsumerGroup (coordinated consumption)
  |-- leaderId (String, broker ID)                      |-- groupId (String, unique)
  |-- replicaIds: List<String>                          |-- subscribedTopics: Set<String>
  |-- inSyncReplicaIds: List<String> (ISR)              |-- members: Map<consumerId, ConsumerInstance>
  |-- isLeader(brokerId) / isInSync(brokerId)           |-- assignments: Map<consumerId, List<PartitionAssignment>>
  |-- addToIsr() / removeFromIsr()                      |-- addMember() / removeMember()
  |-- getPartitionKey() -> "topic-partition"             |-- setAssignment()

BrokerNode (cluster node)                             ConsumerInstance (single consumer)
  |-- brokerId (String)                                 |-- consumerId, groupId, host
  |-- host (String), port (int)                         |-- lastHeartbeat (Instant)
  |-- isController (boolean)                            |-- assignedPartitions: List<PartitionAssignment>
  |-- partitionLeadership: Set<String>                  |-- isAlive(Duration timeout)
  |-- lastHeartbeat (Instant)
  |-- isAlive(Duration timeout)                       PartitionAssignment (rebalance output)
  |-- addLeadership() / removeLeadership()              |-- topicName, partitionId, consumerId

AckMode (enum: NONE=0, LEADER=1, ALL=-1)             DeliveryGuarantee (enum)
  |-- fromValue(int) -> AckMode                         |-- AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE

RetentionPolicy (topic retention config)              QueueMetrics (per-topic counters)
  |-- retentionMs, retentionBytes                       |-- messagesIn, messagesOut
  |-- CleanupPolicy: DELETE | COMPACT | COMPACT_DELETE  |-- bytesIn, bytesOut, lag
  |-- timeBased(ms), sizeBased(bytes), compact()        |-- getProduceRate() -> msg/sec

CommitLog (append-only log per partition)              PartitionManager (partition registry)
  |-- topicName, partitionId                            |-- commitLogs: ConcurrentHashMap<key, CommitLog>
  |-- log: List<Message> (append-only)                  |-- createPartition() / getPartition()
  |-- currentOffset: AtomicLong                         |-- getPartitionsForTopic()
  |-- append(message) -> long offset                    |-- deletePartition()
  |-- read(fromOffset, maxMessages) -> List<Message>
  |-- getLatestOffset() / getEarliestOffset()         ConsumerGroupCoordinator (group lifecycle)
  |-- truncateBefore(offset) -- retention cleanup       |-- groups: ConcurrentHashMap<groupId, ConsumerGroup>
  |-- size() / getMessages()                            |-- committedOffsets: ConcurrentHashMap<key, Long>
                                                        |-- createGroup() / joinGroup() / leaveGroup()
MessageRouter (partition routing)                       |-- rebalance(groupId, partitionCount) -- range assignment
  |-- roundRobinCounter: AtomicInteger                  |-- commitOffset() / getCommittedOffset() / getLag()
  |-- routeToPartition(record, partitionCount)
  |    explicit > key hash > round-robin              ReplicationEngine (ISR replication)
                                                        |-- replicationFactor (int)
PartitioningStrategy (Strategy interface)               |-- replicate(message, partition, ackMode)
  |-- HashPartitioningStrategy                          |    NONE -> immediate, LEADER -> leader ack,
  |    Math.abs(key.hashCode()) % partitionCount        |    ALL -> iterate ISR, warn if ISR < replicationFactor
  |-- RoundRobinPartitioningStrategy
  |    AtomicInteger counter % partitionCount          DeliveryStrategy (Strategy interface)
                                                        |-- AtLeastOnceDeliveryStrategy
StorageStrategy (Strategy interface)                    |    retry up to 3x with 5% failure rate
  |-- LogCompactionStrategy                             |-- ExactlyOnceDeliveryStrategy
  |    LinkedHashMap keeps latest per key,              |    ConcurrentHashMap.newKeySet() dedup by message ID
  |    null-key messages always survive
  |-- TimeBasedRetentionStrategy                      TopicService (topic lifecycle)
  |    now - timestamp > retentionMs = expired          |-- topicRepo, partitionManager
                                                        |-- createTopic() / deleteTopic() / getTopic()
ProducerService (message production)
  |-- partitionManager, partitioningStrategy          ConsumerService (message consumption)
  |-- replicationEngine, topicRepo                      |-- partitionManager, coordinator, deliveryStrategy
  |-- send(record, ackMode) -> offset                   |-- poll(groupId, topic, partition, max) -> List<ConsumerRecord>
  |    resolve partition -> build Message ->             |-- commit(groupId, topic, partition, offset)
  |    append to CommitLog -> replicate                  |-- subscribe() / unsubscribe() -> triggers rebalance
  |-- sendBatch(records, ackMode) -> List<Long>          |-- getLag()

BrokerService (cluster management)                    RetentionService (cleanup & compaction)
  |-- brokerRepo                                        |-- partitionManager, storageStrategy
  |-- registerBroker() / electController()              |-- runCleanup(topic, retentionMs) -> removed count
  |-- handleBrokerFailure() -> re-election              |-- runCompaction(topic) -> removed count
  |-- getAliveBrokers() / getAllBrokers()                |-- getStorageStats(topic) -> Map<partition, count>

MetricsService (throughput tracking)                  MessageQueueService (FACADE -- unified API)
  |-- topicMetrics: ConcurrentHashMap<topic, QueueMetrics>  |-- topicService, producerService, consumerService
  |-- recordProduce() / recordConsume()                 |-- brokerService, retentionService, metricsService
  |-- getMetrics(topic) / getAllMetrics()                |-- createTopic() / produce() / consume() / commit()
  |-- printDashboard() -- formatted console output      |-- subscribe() / runRetention() / getSystemOverview()

MessageQueueController (REST-like facade)             MessageQueueStatsDisplay (console output)
  |-- POST /topics -> createTopic                       |-- printTopics(), printPartitionDetails()
  |-- POST /topics/{topic}/messages -> produce          |-- printConsumerGroups(), printBrokerCluster()
  |-- GET /topics/{topic}/messages -> consume           |-- printMessageLog(), printStats()
  |-- POST /topics/{topic}/commit -> commit
  |-- POST /consumer-groups/subscribe -> subscribe    TopicRepository / ConsumerGroupRepository /
  |-- GET /dashboard -> printDashboard                BrokerRepository / OffsetRepository (4 interfaces)
                                                        |-- InMemoryTopicRepository
AppConfig (Composition Root / Factory / Singleton)      |-- InMemoryConsumerGroupRepository
  |-- creates repositories (4 InMemory impls)           |-- InMemoryBrokerRepository
  |-- creates engines (PartitionManager,                |-- InMemoryOffsetRepository
  |    ConsumerGroupCoordinator, MessageRouter,
  |    ReplicationEngine)                             Exceptions
  |-- creates strategies (PartitioningStrategy,         |-- MessageQueueException (base)
  |    DeliveryStrategy, StorageStrategy) -- swappable  |-- TopicNotFoundException
  |-- creates services (Topic, Producer, Consumer,      |-- PartitionNotFoundException
  |    Broker, Retention, Metrics -> MQService)         |-- ConsumerGroupException
  |-- creates controller + display
  |-- setPartitioningStrategy() / setDeliveryStrategy()
  |    / setStorageStrategy() -> invalidate dependents
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Message` | Core message entity with Builder pattern. Required fields: `topic` and `value`. Optional: `key` (null = round-robin), `headers` (metadata), `partition` (-1 = auto), `offset` (-1 = unassigned), `timestamp` (default now), `producerId`. Builder enforces required fields via constructor, defaults optional ones. Offset and partition are mutated broker-side after routing and append. Analogous to a Kafka ProducerRecord after it has been assigned an offset. |
| `CommitLog` | Append-only log for a single partition -- THE core data structure. `append()` assigns `currentOffset.getAndIncrement()` and adds to list. `read(fromOffset, maxMessages)` returns a bounded subList with bounds checking. `truncateBefore(offset)` removes expired messages for retention cleanup. All methods are `synchronized` for thread safety. The log is NOT deleted on consumption -- this is the key difference from traditional queues (RabbitMQ deletes on ack). |
| `PartitionManager` | Registry of all partition commit logs across all topics. Keyed by `"topicName-partitionId"`. `createPartition()` uses `putIfAbsent()` for idempotent creation. `getPartitionsForTopic()` scans all entries by topic name. Single source of truth for partition lifecycle. |
| `ConsumerGroupCoordinator` | Manages group lifecycle, membership, rebalancing, and offset tracking. `rebalance()` implements range assignment: sort consumer IDs, divide partitions evenly, first `remainder` consumers get one extra. Offset storage keyed by `"groupId-topic-partition"`. `getLag()` computes `latestOffset - committedOffset`. |
| `ReplicationEngine` | Simulates ISR replication with three ack modes. `NONE` returns immediately, `LEADER` logs leader ack, `ALL` iterates all ISR replicas and warns if ISR size < replicationFactor (data durability at risk). In production: followers pull from the leader's log; ISR = replicas within `replica.lag.time.max.ms` of the leader. |
| `MessageRouter` | Routes messages to partitions using cascading strategy: explicit partition > key-based hash > round-robin. Mirrors Kafka's DefaultPartitioner. Round-robin uses `AtomicInteger` for thread-safe distribution. |
| `ProducerService` | Message production pipeline: resolve partition (via strategy) -> build Message from ProducerRecord -> append to CommitLog (assigns offset) -> replicate to ISR replicas based on AckMode -> return offset. Supports single and batch sends. |
| `ConsumerService` | Message consumption pipeline: get committed offset -> read from CommitLog -> convert Message to immutable ConsumerRecord -> return batch. `subscribe()` creates group if needed, joins consumer, triggers rebalance. `getLag()` computes distance from latest offset. |
| `MessageQueueService` | **Facade Pattern (GoF)** -- single entry point orchestrating all subsystems. Clients interact with this service instead of wiring individual services. `produce()` delegates to ProducerService and records metrics. `consume()` delegates to ConsumerService and records metrics. Provides `getSystemOverview()` for full cluster status. |
| `AppConfig` | **Factory Pattern + Composition Root + Singleton** -- lazily creates and wires all 30+ objects. Strategy setters (`setPartitioningStrategy()`, `setDeliveryStrategy()`, `setStorageStrategy()`) invalidate dependent objects for automatic re-creation on next access. Wiring graph: Repositories -> Engines -> Strategies -> Services -> MessageQueueService (Facade) -> Controller -> Display. |
| `BrokerService` | Broker cluster management. Controller election: collect alive brokers, sort by ID, elect lowest. `handleBrokerFailure()` marks broker dead and triggers re-election if it was the controller. Heartbeat timeout: 30 seconds. |
| `RetentionService` | Enforces retention policies. `runCleanup()` iterates partitions, evaluates each message against the retention window via StorageStrategy, truncates expired. `runCompaction()` iterates partitions, compacts via StorageStrategy (keeps latest per key), re-populates log. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Core abstraction | **Traditional queue** (message deleted on consumption, point-to-point delivery, no replay) | **Append-only commit log** (messages retained after consumption, offset-based, multi-consumer replay) | **Commit log** -- messages persist after consumption. Multiple consumer groups read independently at their own pace. Enables replay (reprocess from offset 0), multi-consumer (analytics + search + notifications all read same data), and time-travel debugging. Tradeoff: storage grows until retention kicks in. Kafka's insight: a distributed log is both a queue and a pub/sub system. RabbitMQ uses traditional queue semantics; Kafka uses the log. |
| Partitioning strategy | **Key-based hash** (consistent partition assignment, per-key ordering, potential hot partitions) | **Round-robin** (even distribution, maximum throughput, no ordering guarantee) | **Both via Strategy pattern** -- key-based hash for ordered workloads (user events, transaction sequences), round-robin for throughput-first workloads (metrics, logs). Hash uses `Math.abs(key.hashCode()) % partitionCount`. Tradeoff: hot keys (e.g., a viral user with millions of events) create hot partitions. In production: Kafka uses Murmur2 hash. Mitigation: salt the key or use a custom partitioner that sub-partitions hot keys. Partition count is set at creation and is expensive to change (requires data migration). |
| Delivery guarantee | **At-most-once** (commit before process -- no duplicates, possible loss) | **At-least-once** (process then commit -- no loss, possible duplicates) | **At-least-once as default**, with exactly-once via idempotent consumer deduplication. At-least-once is Kafka's default because data loss is usually worse than duplicates. Consumer must be idempotent (dedup by message ID or database UPSERT). Exactly-once in Kafka: idempotent producer (producer ID + sequence number for broker-side dedup) + transactions (atomic read-process-write across topics). Tradeoff: exactly-once adds ~20% latency overhead and requires transactional API. Stripe uses at-least-once + idempotency keys for payment processing. |
| Ack mode | **acks=0** (fire-and-forget, fastest, ~500K msg/sec, messages may be lost) | **acks=all** (all ISR ack, safest, ~50K msg/sec, highest latency) | **Configurable per-produce call** -- acks=0 for metrics/logs (acceptable loss), acks=1 for most workloads (balanced), acks=all for financial data (zero loss). `acks=all + min.insync.replicas=2` guarantees no data loss if at least 2 replicas survive. Tradeoff: acks=all increases latency by 2-5x vs acks=1 (cross-datacenter replication adds network RTT). LinkedIn uses acks=all for critical data pipelines and acks=1 for activity tracking. |
| Retention strategy | **Time-based retention** (delete messages older than window, bounded storage, simple) | **Log compaction** (keep latest per key, unbounded unique keys, complex) | **Both via Strategy pattern** -- time-based for event streams (7-day default, bounded storage), compaction for state changelogs (KTable, CDC). Can combine: `cleanup.policy=compact,delete` compacts first, then deletes segments exceeding retention. Tradeoff: compaction is CPU-intensive (background cleaner threads scan dirty segments). Compacted topics can grow if key cardinality is high (one entry per unique key). In production: Kafka's `__consumer_offsets` topic uses compaction (latest offset per group-partition is all that matters). |
| Consumer group rebalancing | **Eager rebalancing** (stop-the-world: all consumers revoke all partitions, reassign) | **Cooperative rebalancing** (incremental: only revoke partitions that need to move) | **Eager range assignment** (simplified). Sort consumers, divide partitions evenly. Tradeoff: eager rebalance pauses all consumers in the group for the duration of the rebalance protocol. In production: Kafka's CooperativeSticky assignor allows incremental rebalancing -- only the partitions that need to move are revoked, other consumers continue processing. This reduces rebalance latency from seconds to milliseconds for large groups. Sticky assignor minimizes partition movement to preserve local state (RocksDB stores in Kafka Streams). |
| Broker coordination | **ZooKeeper-based** (external consensus service, battle-tested, operational overhead) | **KRaft (Raft-based)** (self-managed metadata quorum, no external dependency) | **Simplified controller election** (lowest broker ID wins). In production: Kafka is migrating from ZooKeeper to KRaft (Kafka Raft). ZooKeeper adds operational complexity (separate cluster, ephemeral znodes for broker liveness, controller election via /controller znode). KRaft uses a Raft consensus quorum of broker nodes to manage metadata, eliminating the ZK dependency. KRaft supports millions of partitions (ZK limit ~200K). GA in Kafka 3.3+, ZK deprecated in 4.0. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** (x3) | `PartitioningStrategy`: Hash vs RoundRobin | Swap partition assignment algorithm at runtime. Hash for per-key ordering (`key.hashCode() % partitionCount`), round-robin for max throughput (AtomicInteger counter). Both implement `assignPartition(key, partitionCount)`. Switch via `config.setPartitioningStrategy()`. |
| **Strategy** (x3) | `DeliveryStrategy`: AtLeastOnce vs ExactlyOnce | Swap delivery guarantee semantics. AtLeastOnce retries up to 3x with 5% simulated failure rate (consumers must be idempotent). ExactlyOnce uses `ConcurrentHashMap.newKeySet()` for consumer-side dedup by message ID. Both implement `deliver(message, consumerId)`. |
| **Strategy** (x3) | `StorageStrategy`: TimeBasedRetention vs LogCompaction | Swap retention/cleanup algorithm. TimeBasedRetention compares `now - timestamp` against `retentionMs`. LogCompaction groups by key in `LinkedHashMap`, keeps highest-offset entry per key, null-key messages always survive. Both implement `shouldRetain()` and `compact()`. |
| **Builder** | `Message.Builder(topic, value)` | Complex object with 9 fields. Builder enforces required fields (`topic`, `value`) via constructor, defaults optional fields (`id = UUID`, `key = null`, `offset = -1`, `partition = -1`, `timestamp = now`, `producerId = "unknown"`). Fluent API: `new Message.Builder("orders", payload).key("user-123").header("source", "web").build()`. Avoids telescoping constructors. |
| **Factory** | `AppConfig` as Composition Root | The ONLY place where `new ConcreteClass()` appears. Lazily creates 30+ objects across 5 layers: Repositories -> Engines -> Strategies -> Services -> Facade. Strategy setters (`setDeliveryStrategy()`, etc.) null out dependent objects so the graph rebuilds on next access. No DI framework needed. |
| **Repository** (x4) | `TopicRepository`, `ConsumerGroupRepository`, `BrokerRepository`, `OffsetRepository` | Abstract data access behind interfaces. Each has an InMemory implementation (`ConcurrentHashMap`-backed). Swap for Redis, PostgreSQL, or ZooKeeper without touching service logic. Standard CRUD + domain-specific queries (`findByName`, `findAlive`, `findController`). |
| **Facade** | `MessageQueueService` orchestrates 6 sub-services | Single unified API for the entire message queue. `produce()` delegates to ProducerService + MetricsService. `consume()` delegates to ConsumerService + MetricsService. Controllers and demos interact with one class instead of six. Hides the complexity of partition routing, offset management, and replication behind clean `produce/consume/commit` methods. |
| **Observer** | Consumer group rebalancing on member change | When a consumer joins (`subscribe()`) or leaves (`unsubscribe()`), the coordinator automatically triggers `rebalance()` to redistribute partitions. This is the Observer pattern: the group observes membership changes and reacts by reassigning partitions. In Kafka: the GroupCoordinator on the broker side triggers rebalance via the JoinGroup/SyncGroup protocol. |
| **State** | Offset lifecycle (uncommitted -> committed -> stale) | Consumer offset transitions through states: `-1` (no offset committed = start from beginning), committed (consumer has processed up to this point), stale (if consumer crashes and offset is not committed, messages are re-delivered). The `Offset` model tracks `committedOffset` and `lastCommitTime`. State transitions happen via `commit(newOffset)`. |
| **Singleton** | `AppConfig` lazy initialization | Each getter creates the instance once and caches it. Subsequent calls return the cached instance. Strategy setters clear dependents to force re-creation. Thread-unsafe by design (single-threaded demo). In production: use DI container (Spring) for lifecycle management, or double-checked locking for thread safety. |

---

## Real-World Use Cases & Industry Applications

### 1. LinkedIn / Apache Kafka -- Event Streaming Backbone for 7T+ Messages/Day

**Problem:** LinkedIn operates the world's largest professional network with 900M+ members. Every user action (profile view, post impression, connection request, job application, message sent) generates events that must be delivered reliably to 100+ downstream systems: search indexing, recommendation engines, analytics pipelines, notification services, fraud detection, and data warehouses. At peak, LinkedIn processes 7 trillion messages per day across thousands of topics and millions of partitions.

**How this system solves it:** The commit log abstraction is literally what LinkedIn built Kafka around. Each event type (profile-views, connection-requests, job-applications) is a topic with hundreds of partitions for parallelism. Key-based hash partitioning on `userId` ensures all events for a user are ordered within a partition -- critical for the recommendation engine that needs to see a user's full activity stream in sequence. Consumer groups enable independent processing: the search indexer group, the analytics group, and the recommendation group all read from the same topics at their own pace without interfering. Offset management tracks each group's progress independently -- if the analytics pipeline falls behind (lag increases), it catches up without blocking the search indexer. Log compaction on the `member-profiles` topic keeps the latest profile state per member ID, enabling consumers to rebuild materialized views from the topic. acks=all with replication factor 3 ensures zero data loss for critical pipelines. Time-based retention (7 days for activity events, unlimited for compacted topics) controls storage costs across petabytes of data.

**Production numbers:** 7T+ messages/day, 100K+ topics, millions of partitions across 4000+ brokers. Single-cluster throughput: 13M messages/sec. LinkedIn runs Kafka in multiple data centers with MirrorMaker for cross-DC replication. The `__consumer_offsets` topic alone handles billions of offset commits per day. Kafka was born at LinkedIn in 2011, open-sourced via Apache in 2012.

### 2. Uber -- Trip Lifecycle Events, Surge Pricing, and Driver Matching via Kafka

**Problem:** Uber processes 100M+ trips per day across 10,000+ cities. Each trip generates a sequence of events that must be processed in order: ride-request, driver-matched, driver-en-route, pickup, in-trip-location-updates (every 4 seconds), dropoff, payment, rating. These events feed surge pricing (real-time supply/demand calculation), driver matching (nearest available driver), ETA computation, fraud detection (unusual trip patterns), and financial reconciliation. Out-of-order processing causes incorrect fare calculations, phantom surges, and accounting errors.

**How this system solves it:** Key-based hash partitioning on `trip-id` guarantees all events for a single trip land in the same partition, processed by the same consumer in strict order. This solves the ordering problem without global ordering (which would be a bottleneck). Consumer groups separate concerns: the matching group processes ride-requests in real-time (<100ms SLA), the pricing group computes surge multipliers, the analytics group builds dashboards, and the financial group reconciles payments. At-least-once delivery with idempotent consumers (dedup by `trip-id + event-type + timestamp`) ensures no events are lost during driver-matching or payment processing. Acks=all for payment events (cannot lose a fare calculation), acks=1 for location updates (acceptable to lose an occasional GPS ping). The rebalancing protocol ensures that when a consumer instance crashes mid-trip, another consumer picks up the partition and resumes from the committed offset -- the trip's events are replayed from the last checkpoint.

**Production numbers:** 100M+ trips/day, each generating 15-20 events = 1.5B+ trip events/day. Location updates alone: 100M trips x 15 min avg x 1 update/4sec = 22B+ location events/day. Uber runs Kafka clusters across multiple regions with active-active replication. Their consumer groups process events with P99 latency <200ms for real-time matching and pricing.

### 3. Netflix -- Real-Time Analytics, A/B Testing Events, and Recommendation Pipeline

**Problem:** Netflix serves 230M+ subscribers watching 100M+ hours of content daily. Every user interaction (play, pause, skip, browse, search, rate) generates events that feed the recommendation engine (which drives 80% of content watched), A/B testing framework (hundreds of concurrent experiments), and real-time analytics (per-title viewing metrics, regional trends, content performance). The system must handle 100B+ events/day with low latency for real-time dashboards and high throughput for batch ETL to the data warehouse.

**How this system solves it:** Topics for each event type (`play-events`, `browse-events`, `search-events`) with key-based partitioning on `memberId` for per-user ordering. The recommendation consumer group processes viewing events to update the personalization model -- seeing that a user watched 3 sci-fi movies in sequence (in order) is critical for recommendations. A/B testing uses consumer groups that read from the same event topics but filter by experiment allocation -- each experiment's consumer tracks which variant (control/treatment) each user is in and measures metric differences. Log compaction on the `member-preferences` topic maintains the latest preference state per member. Time-based retention of 14 days for raw events (enough for model retraining), compaction for state topics (indefinite). Consumer lag monitoring triggers auto-scaling: if the recommendation pipeline lag exceeds 5 minutes, additional consumers are spun up.

**Production numbers:** 100B+ events/day across the Netflix data pipeline (Kafka + Apache Flink). Netflix Keystone pipeline processes events from 200+ microservices. Their Kafka clusters handle 6M+ messages/sec sustained, with peaks during prime-time viewing hours (8-11 PM per timezone). A/B testing framework runs 400+ concurrent experiments, each reading from shared event topics via independent consumer groups.

### 4. Stripe -- Payment Event Processing, Webhook Delivery, and Financial Event Sourcing

**Problem:** Stripe processes hundreds of billions of dollars in payments annually. Every payment lifecycle event (charge.created, charge.succeeded, charge.failed, charge.refunded, payout.created, dispute.opened) must be: (1) durably stored with zero loss (losing a payment event means accounting discrepancies), (2) delivered to merchant webhooks reliably (merchants depend on webhook notifications for order fulfillment), (3) processed exactly once for financial reconciliation (double-processing a charge.succeeded could double-credit a merchant). The system must handle thousands of payment events per second with strict ordering per payment intent.

**How this system solves it:** Key-based hash partitioning on `payment_intent_id` ensures all events for a single payment flow (create -> authenticate -> capture -> succeed/fail) are processed in order. acks=all with replication factor 3 and min.insync.replicas=2 guarantees zero data loss -- a payment event is not acknowledged to the API until it is durably replicated across multiple brokers. Exactly-once delivery via idempotent consumer pattern: each event carries an idempotency key (`event_id`), and the consumer maintains a dedup set (in practice, a database unique constraint on `event_id`). Webhook delivery uses at-least-once semantics with exponential backoff retries -- merchants must handle duplicate webhook deliveries (Stripe includes `idempotency_key` in webhook payloads). Log compaction on the `payment-state` topic maintains the latest state per payment intent, enabling the reconciliation service to rebuild the current state of any payment by reading from the compacted topic.

**Production numbers:** Stripe processes 250M+ API requests/day. Their event pipeline handles thousands of payment events per second. Webhook delivery system retries failed deliveries for up to 72 hours with exponential backoff (1s, 2s, 4s... up to 1 hour intervals). Financial event sourcing enables point-in-time reconstruction of any account balance by replaying events from the commit log. Stripe's architecture is a textbook example of event sourcing built on top of a distributed message queue.

### 5. Shopify -- Order Processing, Inventory Updates, and Merchant Notifications

**Problem:** Shopify powers 4M+ merchants processing $200B+ in annual GMV. When a customer places an order, a cascade of events must happen in sequence: (1) payment capture, (2) inventory decrement, (3) merchant notification, (4) shipping label generation, (5) customer confirmation email, (6) analytics update. During Black Friday/Cyber Monday (BFCM), Shopify handles 10,000+ orders per second at peak. Out-of-order processing causes overselling (inventory decremented after another order grabs the last unit), duplicate notifications, and incorrect sales dashboards.

**How this system solves it:** Key-based hash partitioning on `order_id` ensures all events for a single order (created, paid, fulfilled, shipped, delivered) are processed in strict order by a single consumer. Consumer groups separate the order processing pipeline: the payment group captures charges, the inventory group decrements stock, the notification group sends merchant/customer notifications, and the analytics group updates dashboards. Each group processes independently at its own pace -- the notification group can lag behind the payment group without blocking payment capture. At-least-once delivery with idempotent inventory decrements (check-and-decrement with optimistic locking) prevents overselling. Consumer lag monitoring is critical during BFCM: if the inventory consumer group lag exceeds 500ms, additional consumers are auto-scaled to prevent overselling due to stale inventory reads. Log compaction on the `product-inventory` topic maintains the latest stock count per product, enabling the storefront to read current inventory from the compacted topic.

**Production numbers:** 10,000+ orders/second during BFCM peak. $7.5B+ in BFCM 2023 sales processed through their event pipeline. Shopify's Kafka clusters handle 1M+ events/second across order, inventory, payment, and analytics topics. Consumer lag SLA during BFCM: <2 seconds for inventory consumers (prevents overselling). Zero data loss tolerance for payment events (acks=all). Merchant webhook delivery with retry: 99.9%+ delivery rate within 60 seconds.

### 6. Twitter/X -- Tweet Fan-Out, Timeline Assembly, and Ad Event Tracking

**Problem:** Twitter/X handles 500M+ tweets per day from 400M+ monthly active users. When a user tweets, the content must be delivered to all followers' timelines (fan-out), indexed for search, processed for trend detection, evaluated for content moderation, and tracked for ad engagement metrics. A user with 100M followers (e.g., @elonmusk) creates a massive fan-out problem: 100M timeline updates from a single tweet. The system must handle both real-time timeline delivery (users expect to see tweets within seconds) and batch analytics (ad impression counting, engagement metrics).

**How this system solves it:** Topics for different event types: `tweet-created`, `tweet-engagement` (likes, retweets, replies), `ad-impressions`, `timeline-updates`. Key-based partitioning on `tweet_id` for engagement events ensures all engagements for a single tweet are processed by the same consumer (accurate engagement counting without distributed coordination). Fan-out uses a hybrid push/pull model: for users with <10K followers, events are pushed to each follower's timeline partition (fan-out on write). For celebrity users with millions of followers, the tweet is stored once and pulled at read time (fan-out on read) -- the message queue handles the write-path fan-out for non-celebrity tweets. Consumer groups: timeline assembly group (real-time, <5s SLA), search indexer group (near-real-time, <30s), trend detection group (real-time, sliding window aggregation), ad analytics group (batch, 1-minute windows). At-least-once delivery for ad impressions (losing an impression means lost revenue), with dedup in the analytics pipeline.

**Production numbers:** 500M+ tweets/day, 1B+ timeline delivery events/day. Ad event tracking: 10B+ impression events/day. Twitter's Kafka clusters handle sustained throughput of millions of events per second. Timeline assembly SLA: P99 <5 seconds from tweet creation to follower timeline visibility. Fan-out for a 10K-follower user: 10K events produced in <100ms via batch send. Celebrity fan-out (100M followers) uses a read-path strategy: the tweet is stored in a hot cache and assembled into timelines on demand.

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :20-distributed-message-queue:run
```

---

## Demo Output Preview

```
======================================================================
   DISTRIBUTED MESSAGE QUEUE -- System Design Demo
   Staff Engineer Interview Prep: Kafka/RabbitMQ Internals
======================================================================

[SETUP] 3-broker cluster ready

======================================================================
  DEMO 1: Produce and Consume Messages
======================================================================
[ROUTER] Key 'order-123' hashed to partition 1 for topic 'orders'
[REPLICATION] acks=LEADER for orders-1 -- leader 'broker-0' acknowledged
[PRODUCER] Sent message to orders-1 at offset=0 (ack=LEADER, key=order-123)
[DEMO] Produced 3 messages to 'orders' topic
  Offsets: 0, 0, 0
[CONSUMER] Poll groupId='order-group' topic='orders' partition=0 from offset=0
[DEMO] Consumed 2 messages from partition 0
  offset=0 key=order-456 value={"item":"phone","qty":2}
  offset=1 key=order-789 value={"item":"tablet","qty":1}

  KEY INSIGHT: Messages are appended to a commit log (append-only).
  Each message gets a monotonically increasing offset. Consumers
  read from an offset position -- the log is NOT deleted on consumption
  (unlike traditional queues). This enables replay and multi-consumer.

======================================================================
  DEMO 2: Key-Based Partitioning
======================================================================
[DEMO] Key -> Partition mapping (consistent hash):
  user-100 -> partition 3
  user-200 -> partition 0
  user-300 -> partition 2
[DEMO] Messages per partition:
  partition 0: 2 messages
  partition 2: 1 messages
  partition 3: 3 messages

  KEY INSIGHT: Same key -> same partition guarantees ordering per key.
  user-100's events are always in partition 3 in order.
  This is how Kafka guarantees per-user ordering without global order.
  Hash(key) % partitionCount. Null key -> round-robin (no ordering).

======================================================================
  DEMO 3: Consumer Group Rebalancing
======================================================================
[REBALANCE] Group 'analytics-group': 4 partitions across 2 consumers
[REBALANCE]   consumer-A -> partitions [0, 1]
[REBALANCE]   consumer-B -> partitions [2, 3]
[DEMO] Added consumer-C -> rebalance:
[REBALANCE]   consumer-A -> partitions [0, 1]
[REBALANCE]   consumer-B -> partitions [2]
[REBALANCE]   consumer-C -> partitions [3]
[DEMO] Removed consumer-B -> rebalance:
[REBALANCE]   consumer-A -> partitions [0, 1]
[REBALANCE]   consumer-C -> partitions [2, 3]

  KEY INSIGHT: Consumer group rebalancing redistributes partitions
  when members join/leave. Range assignment: sort consumers, divide
  partitions evenly. In Kafka: also Sticky and CooperativeSticky
  assignors to minimize partition movement during rebalance.

======================================================================
  DEMO 5: Ack Modes (acks=0, acks=1, acks=all)
======================================================================
[DEMO] acks=0 (fire-and-forget) -- fastest, may lose messages:
[REPLICATION] acks=NONE -- no acknowledgment, returning immediately

[DEMO] acks=1 (leader ack) -- balanced speed/safety:
[REPLICATION] acks=LEADER -- leader 'broker-0' acknowledged

[DEMO] acks=all (all ISR ack) -- safest, slowest:
[REPLICATION] acks=ALL -- replicating to 1 ISR replicas

  +----------+------------+------------+-----------------+
  | Ack Mode | Durability |  Latency   |    Use Case      |
  +----------+------------+------------+-----------------+
  | acks=0   | Lowest     | Fastest    | Metrics, logs    |
  | acks=1   | Medium     | Medium     | Most workloads   |
  | acks=all | Highest    | Slowest    | Financial data   |
  +----------+------------+------------+-----------------+

======================================================================
  DEMO 7: Exactly-Once Delivery (Idempotent Consumer)
======================================================================
[DEMO] First consumption: 2 messages
[DEMO] Simulating redelivery (consumer restart without commit)...
[DELIVERY] EXACTLY_ONCE -- duplicate detected (skipped, idempotent)
[DEMO] With exactly-once, idempotent consumer deduplicates by message ID

  KEY INSIGHT: Exactly-once = at-least-once + idempotent processing.
  Kafka EOS uses producer ID + sequence number for dedup on broker.
  Consumer side: dedup by message ID in a Set or database constraint.
  Transactions (read-process-write) span consume + produce + commit.

======================================================================
  DEMO 8: Log Compaction (Keep Latest Per Key)
======================================================================
[DEMO] Before compaction: 5 messages
[COMPACTION] Log compaction -- before: 5, after: 2
[DEMO] After compaction: 2 messages (3 removed)
[DEMO] Only latest version of each key is retained

  KEY INSIGHT: Log compaction keeps the LATEST value for each key.
  Used for changelog topics (KTable in Kafka Streams), CDC streams,
  and materialized views. cleanup.policy=compact retains forever but
  removes superseded values. Tombstone (null value) = key deletion.

======================================================================
  DEMO 10: Broker Cluster & Controller Election
======================================================================
[DEMO] Simulating controller failure...
[BROKER] Controller broker-1 failed!
[BROKER] Triggering re-election
[BROKER] Elected broker-2 as cluster controller

  KEY INSIGHT: The controller broker handles partition leader election,
  topic creation, and replica management. Kafka uses ZooKeeper for
  controller election (KRaft mode removes ZK dependency). When the
  controller fails, brokers race to claim the /controller znode.

======================================================================
  DEMO 11: Replication (ISR & Ack Modes)
======================================================================
[DEMO] Partition: replicated-topic-0
  Leader: broker-1
  Replicas: [broker-1, broker-2, broker-3]
  ISR: [broker-1, broker-2, broker-3]

[DEMO] Replicating with acks=all:
[REPLICATION] All 3 ISR replicas acknowledged
  Result: SUCCESS

[DEMO] After broker-3 falls out of ISR:
  ISR: [broker-1, broker-2]
  acks=all with 2/3 ISR: SUCCESS

  KEY INSIGHT: ISR (In-Sync Replicas) = replicas that are caught up
  with the leader. acks=all waits for ALL ISR replicas. If ISR shrinks
  below min.insync.replicas, producer gets NotEnoughReplicasException.

======================================================================
  DESIGN SUMMARY -- Distributed Message Queue
======================================================================

  Core Data Structures:
    * Commit Log (append-only) -- O(1) append, O(1) read by offset
    * Partitions -- parallel processing units within a topic
    * Consumer Group -- coordinated consumption with offset tracking
    * ISR (In-Sync Replicas) -- durability guarantee set

  Key Algorithms:
    * Hash partitioning -- key.hashCode() % partitionCount
    * Range assignment -- distribute partitions evenly to consumers
    * Log compaction -- keep latest value per key
    * Time-based retention -- expire messages older than window

  Design Patterns (GoF):
    * Strategy -- delivery, partitioning, storage/compaction
    * Builder -- Message construction
    * Factory -- AppConfig composition root
    * Repository -- data access (Topic, ConsumerGroup, Broker, Offset)
    * Facade -- MessageQueueService orchestrates all services
    * Observer -- consumer group rebalancing on member change
    * State -- consumer offset lifecycle
    * Singleton -- AppConfig lazy initialization
```
